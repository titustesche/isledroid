package com.titus.dynamicisland.data.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import android.os.Bundle
import android.os.Handler
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tracks active media sessions. Requires notification-listener access: the listener's component
 * is what authorises [MediaSessionManager.getActiveSessions].
 */
class MediaRepository(private val context: Context, private val scope: CoroutineScope) {

    private val _media = MutableStateFlow<MediaInfo?>(null)
    val media: StateFlow<MediaInfo?> = _media.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    /** Compat controllers expose repeat/shuffle state, which the framework controller doesn't. */
    private val compat = mutableMapOf<MediaSession.Token, Pair<MediaControllerCompat, MediaControllerCompat.Callback>>()
    private var active: MediaController? = null
    private var listenerComponent: ComponentName? = null

    /** Palette colors keyed by artwork *content*: sessions hand out a new Bitmap object on every update. */
    private val accentCache = mutableMapOf<Long, Int?>()
    private var lastArt: Bitmap? = null
    private var lastArtKey: Long? = null
    private var lastAccent: Int? = null
    private var lastArtSeenAt = 0L
    private val graceExpired = Runnable { recompute() }

    var blockedPackages: Set<String> = emptySet()
        set(value) {
            field = value
            recompute()
        }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        bind(controllers.orEmpty())
    }

    fun start(component: ComponentName) {
        if (listenerComponent != null) return
        listenerComponent = component
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            bind(sessionManager.getActiveSessions(component))
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification access not granted", e)
            listenerComponent = null
        }
    }

    fun stop() {
        listenerComponent ?: return
        sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }
        callbacks.clear()
        compat.values.forEach { (c, cb) -> c.unregisterCallback(cb) }
        compat.clear()
        active = null
        listenerComponent = null
        _media.value = null
    }

    /** Resumes the current player, or (with none) whatever played last, like a headset's play button. */
    fun play() {
        val c = active
        if (c != null) c.transportControls.play() else mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun pause() {
        active?.transportControls?.pause()
    }

    private fun mediaKey(code: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun playPause() {
        val c = active ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    /**
     * Packages of running sessions that say they can search-and-play without being opened: playing
     * ones first, then the most recently used. Not all of them are music players (Signal's voice
     * notes claim it too), so callers check against the apps that really handle a play search.
     */
    fun searchablePlayers(): List<String> =
        callbacks.keys.filter { it.canPlayFromSearch() }
            .sortedWith(compareByDescending<MediaController> { it.playbackState.isActive() }
                .thenByDescending { it.playbackState?.lastPositionUpdateTime ?: 0L })
            .map { it.packageName }.distinct()

    /** Asks [pkg]'s running session to find and play [query]. False when it has none that can. */
    fun playFromSearch(pkg: String, query: String, extras: Bundle): Boolean {
        val c = callbacks.keys.firstOrNull { it.packageName == pkg && it.canPlayFromSearch() } ?: return false
        c.transportControls.playFromSearch(query, extras)
        return true
    }

    private fun MediaController.canPlayFromSearch() =
        (playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L

    fun next() = active?.transportControls?.skipToNext()
    fun previous() = active?.transportControls?.skipToPrevious()
    fun seekTo(positionMs: Long) = active?.transportControls?.seekTo(positionMs)

    fun toggleShuffle() {
        val c = active ?: return
        val on = compat[c.sessionToken]?.first?.shuffleMode?.let { it == PlaybackStateCompat.SHUFFLE_MODE_ALL || it == PlaybackStateCompat.SHUFFLE_MODE_GROUP } ?: false
        val mode = if (on) PlaybackStateCompat.SHUFFLE_MODE_NONE else PlaybackStateCompat.SHUFFLE_MODE_ALL
        val cc = compat[c.sessionToken]?.first
        if (cc != null) cc.transportControls.setShuffleMode(mode)
        else c.transportControls.sendCustomAction(COMPAT_SET_SHUFFLE, Bundle().apply { putInt(COMPAT_ARG_SHUFFLE, mode) })
    }

    /** Cycles off → all → one → off, like Android's player. */
    fun cycleRepeat() {
        val c = active ?: return
        val cc = compat[c.sessionToken]?.first
        val mode = when (cc?.repeatMode) {
            PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP -> PlaybackStateCompat.REPEAT_MODE_ONE
            PlaybackStateCompat.REPEAT_MODE_ONE -> PlaybackStateCompat.REPEAT_MODE_NONE
            else -> PlaybackStateCompat.REPEAT_MODE_ALL
        }
        if (cc != null) cc.transportControls.setRepeatMode(mode)
        else c.transportControls.sendCustomAction(COMPAT_SET_REPEAT, Bundle().apply { putInt(COMPAT_ARG_REPEAT, mode) })
    }

    fun sendCustomAction(action: MediaCustomAction) {
        val c = active ?: return
        val raw = c.playbackState?.customActions?.firstOrNull { it.action == action.action } ?: return
        c.transportControls.sendCustomAction(raw.action, raw.extras)
    }

    private val actionIcons = LruCache<String, ImageBitmap>(48)

    /**
     * Media3 sessions only accept custom actions from controllers they trust (the system's media
     * notification), so for them the buttons would do nothing; skip them there.
     */
    private fun MediaController.customActions(): List<MediaCustomAction> =
        if (tag?.startsWith("androidx.media3") == true) emptyList()
        else playbackState?.customActions.orEmpty().map { a ->
            MediaCustomAction(a.action, a.name?.toString().orEmpty(), loadActionIcon(packageName, a.icon))
        }

    /** Custom-action icons are resource ids inside the player app. */
    private fun loadActionIcon(pkg: String, resId: Int): ImageBitmap? {
        if (resId == 0) return null
        val key = "$pkg/$resId"
        actionIcons.get(key)?.let { return it }
        return runCatching {
            val res = context.packageManager.getResourcesForApplication(pkg)
            ResourcesCompat.getDrawable(res, resId, null)?.toBitmap(72, 72)?.asImageBitmap()
        }.getOrNull()?.also { actionIcons.put(key, it) }
    }

    private fun bind(controllers: List<MediaController>) {
        val tokens = controllers.map { it.sessionToken }.toSet()
        callbacks.keys.filter { it.sessionToken !in tokens }.forEach { stale ->
            callbacks.remove(stale)?.let(stale::unregisterCallback)
        }
        compat.keys.filter { it !in tokens }.forEach { t -> compat.remove(t)?.let { (c, cb) -> c.unregisterCallback(cb) } }
        controllers.forEach { c ->
            if (callbacks.keys.none { it.sessionToken == c.sessionToken }) {
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) = recompute()
                    override fun onMetadataChanged(metadata: MediaMetadata?) = recompute()
                    override fun onSessionDestroyed() {
                        callbacks.remove(c)?.let(c::unregisterCallback)
                        recompute()
                    }
                }
                c.registerCallback(cb, handler)
                callbacks[c] = cb
                runCatching {
                    val cc = MediaControllerCompat(context, MediaSessionCompat.Token.fromToken(c.sessionToken))
                    val ccb = object : MediaControllerCompat.Callback() {
                        override fun onRepeatModeChanged(repeatMode: Int) = recompute()
                        override fun onShuffleModeChanged(shuffleMode: Int) = recompute()
                        override fun onSessionReady() = recompute()
                    }
                    cc.registerCallback(ccb, handler)
                    compat[c.sessionToken] = cc to ccb
                }
            }
        }
        recompute()
    }

    private fun recompute() {
        val candidates = callbacks.keys.filter { it.packageName !in blockedPackages && it.metadata != null }
        val chosen = candidates.firstOrNull { it.playbackState.isActive() }
            ?: active?.takeIf { it in candidates }
            ?: candidates.firstOrNull()
        active = chosen
        _media.value = chosen?.toInfo()
    }

    private fun PlaybackState?.isActive() = this?.state == PlaybackState.STATE_PLAYING ||
        this?.state == PlaybackState.STATE_BUFFERING

    private fun MediaController.toInfo(): MediaInfo? {
        val md = metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val rawArt = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val state = playbackState
        val art = rawArt?.let(::stableArt)
        val accent = accentFor(art)
        return MediaInfo(
            packageName = packageName,
            title = title,
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            art = art,
            accentColor = accent,
            isPlaying = state.isActive(),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = state?.position ?: 0L,
            positionUpdatedAt = state?.lastPositionUpdateTime ?: 0L,
            playbackSpeed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            sessionActivity = sessionActivity,
            customActions = customActions(),
            shuffle = shuffleState(state),
            repeat = repeatState(state),
        )
    }

    private fun MediaController.shuffleState(state: PlaybackState?): Boolean? {
        if (state == null || state.actions and PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE == 0L) return null
        val mode = compat[sessionToken]?.first?.shuffleMode ?: PlaybackStateCompat.SHUFFLE_MODE_NONE
        return mode == PlaybackStateCompat.SHUFFLE_MODE_ALL || mode == PlaybackStateCompat.SHUFFLE_MODE_GROUP
    }

    private fun MediaController.repeatState(state: PlaybackState?): RepeatMode? {
        if (state == null || state.actions and PlaybackStateCompat.ACTION_SET_REPEAT_MODE == 0L) return null
        return when (compat[sessionToken]?.first?.repeatMode) {
            PlaybackStateCompat.REPEAT_MODE_ONE -> RepeatMode.ONE
            PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }
    }

    /** Returns the previous Bitmap instance when the cover didn't change, and kicks off color extraction for new ones. */
    private fun stableArt(raw: Bitmap): Bitmap {
        lastArtSeenAt = SystemClock.elapsedRealtime()
        val key = contentKey(raw)
        if (key == lastArtKey) lastArt?.let { return it }
        lastArtKey = key
        lastArt = raw
        if (!accentCache.containsKey(key)) extractAccent(raw, key)
        return raw
    }

    /**
     * The accent for the current cover. While a new cover's color is still being computed — or the
     * new track's artwork hasn't arrived yet — the previous accent is kept, so it never flashes the default.
     */
    private fun accentFor(art: Bitmap?): Int? {
        val key = lastArtKey
        return when {
            art != null && key != null && accentCache.containsKey(key) -> accentCache[key].also { lastAccent = it }
            art != null -> lastAccent
            SystemClock.elapsedRealtime() - lastArtSeenAt < ART_GRACE_MS -> {
                handler.removeCallbacks(graceExpired)
                handler.postDelayed(graceExpired, ART_GRACE_MS)
                lastAccent
            }
            else -> null
        }
    }

    /** Cheap content fingerprint: dimensions plus a 12×12 grid of sampled pixels. */
    private fun contentKey(b: Bitmap): Long = runCatching {
        var h = (b.width.toLong() shl 32) xor b.height.toLong()
        val n = 12
        for (i in 0 until n) for (j in 0 until n) {
            h = 31 * h + b.getPixel((b.width - 1) * i / (n - 1), (b.height - 1) * j / (n - 1))
        }
        h
    }.getOrElse { System.identityHashCode(b).toLong() }

    private fun extractAccent(art: Bitmap, key: Long) {
        scope.launch {
            val color = withContext(Dispatchers.Default) {
                runCatching {
                    val p = Palette.from(art).maximumColorCount(16).generate()
                    (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch)?.rgb
                }.getOrNull()
            }
            if (accentCache.size > 32) accentCache.clear()
            accentCache[key] = color
            if (key == lastArtKey) recompute()
        }
    }

    private companion object {
        const val TAG = "MediaRepository"
        /** Tracks often switch title first and artwork a moment later. */
        const val ART_GRACE_MS = 1500L
        // MediaSessionCompat's wire format for repeat/shuffle, used if the compat controller isn't ready.
        const val COMPAT_SET_SHUFFLE = "android.support.v4.media.session.action.SET_SHUFFLE_MODE"
        const val COMPAT_ARG_SHUFFLE = "android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE"
        const val COMPAT_SET_REPEAT = "android.support.v4.media.session.action.SET_REPEAT_MODE"
        const val COMPAT_ARG_REPEAT = "android.support.v4.media.session.action.ARGUMENT_REPEAT_MODE"
    }
}
