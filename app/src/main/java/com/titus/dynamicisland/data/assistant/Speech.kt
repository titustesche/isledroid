package com.titus.dynamicisland.data.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Callbacks from a [Speaker], always on the main thread. */
interface SpeakerListener {
    /** Audio started coming out. */
    fun onSpeaking() {}
    /** Loudness (0..1) of what is audible right now, for the voice animation. */
    fun onLevel(level: Float) {}
    /** Everything queued has been played. */
    fun onIdle() {}
    /** The speaker gave up; [unspoken] are the queued pieces that were not played. */
    fun onFailed(reason: String, unspoken: List<String>) {}
}

/** Reads text aloud. Pieces queued with [speak] play back to back. Call from the main thread. */
interface Speaker {
    fun speak(text: String)
    /** Drops everything queued and goes quiet now. */
    fun stop()
    fun release()
    /** Something is queued or playing. */
    val busy: Boolean
}

/** Maps an RMS amplitude (0..1 of full scale) to a 0..1 level: about −42 dBFS is silence, −8 dBFS is loud. */
private fun levelFromRms(rms: Double): Float =
    if (rms <= 0.0) 0f else ((20 * log10(rms) + 42) / 34).toFloat().coerceIn(0f, 1f)

// ---------------- Phone (Android text-to-speech) ----------------

/** Installed speech engines and their voices. */
object PhoneVoices {
    const val GOOGLE_TTS = "com.google.android.tts"

    data class Engine(val pkg: String, val label: String)

    fun engines(context: Context): List<Engine> {
        val pm = context.packageManager
        return pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { Engine(it.serviceInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * The engine to use: the chosen one if still installed, else Google's (much better than most
     * phone makers' engines, e.g. Samsung's), else the system default (null).
     */
    fun resolveEngine(context: Context, chosen: String): String? {
        val installed = engines(context).map { it.pkg }
        return when {
            chosen.isNotBlank() && chosen in installed -> chosen
            GOOGLE_TTS in installed -> GOOGLE_TTS
            else -> null
        }
    }

    /** Voices for [locale]'s language that are ready to use, best first. */
    fun usable(voices: Set<Voice>?, locale: Locale = Locale.getDefault()): List<Voice> = voices.orEmpty()
        .filter { it.locale.language == locale.language && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
        .sortedWith(
            compareByDescending<Voice> { it.locale.country == locale.country }
                .thenByDescending { it.quality }
                // Offline voices answer immediately; network ones add a round trip per sentence.
                .thenBy { it.isNetworkConnectionRequired }
                .thenBy { it.name },
        )

    /** e.g. "Voice IOL · US · offline" for Google's "en-us-x-iol-local". */
    fun label(v: Voice): String {
        val id = v.name.substringAfter("-x-", "").substringBefore('-').uppercase().ifBlank { v.name }
        val where = if (v.isNetworkConnectionRequired) "online" else "offline"
        val country = v.locale.country.ifBlank { v.locale.language.uppercase() }
        return "Voice $id · $country · $where"
    }
}

/**
 * Android text-to-speech. Plays on its own audio session so a [Visualizer] can measure the real
 * loudness of the voice; if that isn't available, each spoken word gives the animation a beat.
 */
class PhoneSpeaker(
    context: Context,
    enginePackage: String?,
    private val voiceName: String,
    private val rate: () -> Float,
    private val listener: SpeakerListener,
) : Speaker {
    private val main = Handler(Looper.getMainLooper())
    private val sessionId = context.getSystemService(AudioManager::class.java).generateAudioSessionId()
    private var ready = false
    private var failed = false
    private val pending = ArrayDeque<String>()
    private val inFlight = mutableSetOf<String>()
    private var counter = 0
    private var visualizer: Visualizer? = null
    private var visualizerBroken = false
    private var lastHeardAt = 0L
    private val endBeat = Runnable { listener.onLevel(0.12f) }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, { status -> main.post { onInit(status) } }, enginePackage)

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Text-to-speech unavailable ($status)")
            failed = true
            val unspoken = pending.toList()
            pending.clear()
            listener.onFailed("The phone's speech engine isn't available.", unspoken)
            return
        }
        val locale = Locale.getDefault()
        tts.language = locale
        val voices = runCatching { tts.voices }.getOrNull()
        val voice = voices?.firstOrNull { it.name == voiceName } ?: PhoneVoices.usable(voices, locale).firstOrNull()
        voice?.let { runCatching { tts.voice = it } }
        tts.setOnUtteranceProgressListener(progress)
        ready = true
        while (pending.isNotEmpty()) speak(pending.removeFirst())
    }

    override val busy get() = inFlight.isNotEmpty() || pending.isNotEmpty()

    override fun speak(text: String) {
        if (failed) return listener.onFailed("The phone's speech engine isn't available.", listOf(text))
        if (!ready) return run { pending.addLast(text) }
        tts.setSpeechRate(rate())
        val id = "u${counter++}"
        inFlight += id
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, sessionId) }
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, params, id) != TextToSpeech.SUCCESS) finished(id)
    }

    override fun stop() {
        pending.clear()
        inFlight.clear()
        if (ready) tts.stop()
        main.removeCallbacks(endBeat)
        visualizer?.runCatching { enabled = false }
        listener.onLevel(0f)
    }

    override fun release() {
        stop()
        tts.shutdown()
        visualizer?.release()
        visualizer = null
    }

    private fun finished(id: String?) {
        if (!inFlight.remove(id)) return // stopped meanwhile
        if (busy) return
        visualizer?.runCatching { enabled = false }
        listener.onLevel(0f)
        listener.onIdle()
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            main.post {
                if (utteranceId !in inFlight) return@post
                startVisualizer()
                listener.onSpeaking()
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            main.post {
                // Only a fallback: real levels from the visualizer win while they arrive.
                if (utteranceId !in inFlight || SystemClock.elapsedRealtime() - lastHeardAt < 400) return@post
                listener.onLevel(0.55f + (Math.random() * 0.45).toFloat())
                main.removeCallbacks(endBeat)
                main.postDelayed(endBeat, 140)
            }
        }

        override fun onDone(utteranceId: String?) {
            main.post { finished(utteranceId) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            main.post { finished(utteranceId) }
        }
    }

    private fun startVisualizer() {
        if (visualizerBroken) return
        visualizer?.let { v -> v.runCatching { enabled = true }; return }
        visualizer = runCatching {
            Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(256)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                            if (waveform == null || waveform.isEmpty()) return
                            var sum = 0.0
                            for (b in waveform) {
                                val s = (b.toInt() and 0xFF) - 128 // 8-bit unsigned PCM
                                sum += s * s
                            }
                            val rms = sqrt(sum / waveform.size) / 128.0
                            val now = SystemClock.elapsedRealtime()
                            when {
                                rms > 0.004 -> {
                                    lastHeardAt = now
                                    listener.onLevel(levelFromRms(rms))
                                }
                                // A real pause between words (not a visualizer that hears nothing at all).
                                now - lastHeardAt < 400 -> listener.onLevel(0f)
                            }
                        }

                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) = Unit
                    },
                    Visualizer.getMaxCaptureRate(), true, false,
                )
                enabled = true
            }
        }.onFailure {
            visualizerBroken = true
            Log.i(TAG, "No visualizer for the voice; using word beats", it)
        }.getOrNull()
    }

    private companion object {
        const val TAG = "PhoneSpeaker"
    }
}

// ---------------- Streamed PCM (ElevenLabs, Piper) ----------------

/** Turns text into 16-bit little-endian mono PCM at [sampleRate]. */
interface PcmSource {
    val sampleRate: Int

    /** Shown when [render] fails with something other than an [AssistantException]. */
    val failureReason: String

    /**
     * Renders [text] (which followed [previous]), handing audio to [emit] as it's ready; chunks must
     * hold whole samples. Runs on an IO thread and may block. Throws [AssistantException] with a
     * reason fit for the user when it can't.
     */
    suspend fun render(text: String, previous: String?, emit: (ByteArray) -> Unit)
}

/**
 * Plays what a [PcmSource] renders through an [AudioTrack]. The next sentence renders while the
 * current one plays, so there are no gaps; the animation level is computed from the samples
 * themselves and released in step with the playback position.
 */
class PcmSpeaker(
    private val source: PcmSource,
    private val listener: SpeakerListener,
    private val tag: String,
) : Speaker {
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null

    override val busy get() = session?.busy == true

    override fun speak(text: String) {
        val s = session ?: Session().also { session = it }
        s.enqueue(text)
    }

    override fun stop() {
        session?.close()
        session = null
        listener.onLevel(0f)
    }

    override fun release() = stop()

    private inner class Session {
        private val rate = source.sampleRate
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val texts = Channel<String>(Channel.UNLIMITED)
        private val pcm = Channel<ByteArray>(Channel.UNLIMITED)
        private val waitingTexts = AtomicInteger() // queued or rendering
        private val waitingChunks = AtomicInteger() // rendered, not yet handed to the track
        private val idleAnnounced = AtomicBoolean(true)
        @Volatile private var written = 0L // frames handed to the track
        @Volatile private var started = false
        @Volatile private var closed = false
        private val levels = LevelTimeline(window = rate / 50)
        private val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                max(
                    AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                    rate * 2 / 5, // 200 ms
                ),
            )
            .build()

        val busy get() = waitingTexts.get() > 0 || waitingChunks.get() > 0 || playedFrames() < written

        init {
            scope.launch { renderLoop() }
            scope.launch { writeLoop() }
            scope.launch { meterLoop() }
        }

        fun enqueue(text: String) {
            waitingTexts.incrementAndGet()
            idleAnnounced.set(false)
            texts.trySend(text)
        }

        fun close() {
            closed = true
            runCatching {
                track.pause() // also wakes a blocked write
                track.flush()
            }
            texts.close()
            pcm.close()
            scope.cancel()
        }

        private fun playedFrames(): Long = runCatching { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(written)

        private suspend fun renderLoop() {
            var previous: String? = null
            for (text in texts) {
                try {
                    Log.d(tag, "render \"${text.take(40)}\"")
                    source.render(text, previous) { chunk ->
                        if (chunk.isNotEmpty()) {
                            waitingChunks.incrementAndGet()
                            pcm.trySend(chunk)
                        }
                    }
                    Log.d(tag, "rendered, written so far=$written")
                    previous = text
                    waitingTexts.decrementAndGet()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (closed) return
                    Log.w(tag, "Rendering failed", e)
                    val unspoken = buildList {
                        add(text)
                        while (true) add(texts.tryReceive().getOrNull() ?: break)
                    }
                    waitingTexts.addAndGet(-unspoken.size)
                    val reason = (e as? AssistantException)?.message ?: source.failureReason
                    main.post { if (!closed) listener.onFailed(reason, unspoken) }
                }
            }
        }

        private suspend fun writeLoop() {
            try {
                for (chunk in pcm) {
                    if (!started) {
                        track.play()
                        started = true
                        main.post { if (!closed) listener.onSpeaking() }
                    }
                    levels.add(chunk)
                    var off = 0
                    while (off < chunk.size) {
                        coroutineContext.ensureActive()
                        val n = track.write(chunk, off, chunk.size - off)
                        if (n <= 0) break
                        off += n
                    }
                    written += chunk.size / 2
                    waitingChunks.decrementAndGet()
                }
            } finally {
                runCatching { track.release() }
            }
        }

        private suspend fun meterLoop() {
            while (coroutineContext.isActive) {
                delay(33)
                if (!started) continue
                val played = playedFrames()
                val level = levels.at(played)
                val done = waitingTexts.get() == 0 && waitingChunks.get() == 0 && played >= written
                if (done && !idleAnnounced.get()) Log.d(tag, "idle: played=$played written=$written")
                main.post { if (!closed) listener.onLevel(if (done) 0f else level) }
                if (done && idleAnnounced.compareAndSet(false, true)) {
                    main.post {
                        if (closed) return@post
                        // New text may have arrived in between; then the next round announces it.
                        if (busy) idleAnnounced.set(false) else listener.onIdle()
                    }
                }
            }
        }
    }

    /** Loudness per [window] frames (20 ms) of audio, indexed by frame position. */
    private class LevelTimeline(private val window: Int) {
        private val lock = Any()
        private var values = FloatArray(1024)
        private var count = 0
        private var sum = 0.0
        private var frames = 0

        fun add(chunk: ByteArray) = synchronized(lock) {
            var i = 0
            while (i + 1 < chunk.size) {
                val s = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xFF)).toShort() / 32768.0
                sum += s * s
                if (++frames == window) {
                    if (count == values.size) values = values.copyOf(values.size * 2)
                    values[count++] = levelFromRms(sqrt(sum / window))
                    sum = 0.0
                    frames = 0
                }
                i += 2
            }
        }

        fun at(frame: Long): Float = synchronized(lock) {
            val idx = (frame / window).toInt()
            if (idx in 0 until count) values[idx] else 0f
        }
    }
}

// ---------------- ElevenLabs ----------------

data class ElevenVoice(val id: String, val name: String, val description: String)

object ElevenLabs {
    /** "George": a warm, slightly wry British narrator, a decent fit for a dry assistant. */
    const val DEFAULT_VOICE_ID = "JBFqnCBsd6RMkjVDRZzb"
    const val DEFAULT_VOICE_NAME = "George"
    private const val BASE = "https://api.elevenlabs.io/v1"

    /** The account's voices (premade and own). Blocking; call off the main thread. */
    fun voices(apiKey: String): List<ElevenVoice> {
        val conn = (URL("$BASE/voices").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("xi-api-key", apiKey)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw AssistantException(errorMessage(code, conn.errorStream?.bufferedReader()?.use { it.readText() }))
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val arr = json.getJSONArray("voices")
            return (0 until arr.length()).map { i ->
                val v = arr.getJSONObject(i)
                val labels = v.optJSONObject("labels")
                val desc = listOfNotNull(
                    labels?.optString("accent")?.takeIf { it.isNotBlank() },
                    labels?.optString("gender")?.takeIf { it.isNotBlank() },
                    labels?.optString("age")?.takeIf { it.isNotBlank() },
                    (labels?.optString("description") ?: labels?.optString("descriptive"))?.takeIf { it.isNotBlank() },
                ).joinToString(", ")
                ElevenVoice(v.getString("voice_id"), v.optString("name", "Voice"), desc)
            }.sortedBy { it.name.lowercase() }
        } catch (e: IOException) {
            throw AssistantException("No connection to ElevenLabs.", e)
        } finally {
            conn.disconnect()
        }
    }

    fun errorMessage(code: Int, body: String?): String = when {
        code == 401 -> "ElevenLabs rejected the API key."
        body?.contains("quota_exceeded") == true || code == 402 -> "Your ElevenLabs quota is used up."
        code == 404 -> "That ElevenLabs voice doesn't exist (any more)."
        code == 429 -> "ElevenLabs is busy right now."
        else -> "ElevenLabs answered $code."
    }

    internal fun streamUrl(voiceId: String) =
        URL("$BASE/text-to-speech/${URLEncoder.encode(voiceId, "UTF-8")}/stream?output_format=pcm_$SAMPLE_RATE")

    const val SAMPLE_RATE = 24_000
}

/** ElevenLabs voices, streamed as raw PCM. */
class ElevenLabsSpeaker(
    apiKey: String,
    voiceId: String,
    modelId: String,
    rate: () -> Float,
    listener: SpeakerListener,
) : Speaker by PcmSpeaker(ElevenLabsSource(apiKey, voiceId, modelId, rate), listener, "ElevenLabsSpeaker")

private class ElevenLabsSource(
    private val apiKey: String,
    private val voiceId: String,
    private val modelId: String,
    private val rate: () -> Float,
) : PcmSource {
    override val sampleRate = ElevenLabs.SAMPLE_RATE
    override val failureReason = "ElevenLabs isn't reachable."

    override suspend fun render(text: String, previous: String?, emit: (ByteArray) -> Unit) {
        val body = JSONObject()
            .put("text", text)
            .put("model_id", modelId)
            .put(
                "voice_settings",
                JSONObject()
                    .put("stability", 0.45)
                    .put("similarity_boost", 0.75)
                    .put("style", 0.0)
                    .put("use_speaker_boost", true)
                    .put("speed", rate().coerceIn(0.7f, 1.2f).toDouble()),
            )
            .apply { previous?.let { put("previous_text", it) } }
            .toString()
        val conn = (ElevenLabs.streamUrl(voiceId).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("xi-api-key", apiKey)
            setRequestProperty("Content-Type", "application/json")
        }
        // Blocking reads don't notice cancellation; dropping the connection does.
        val hook = coroutineContext.job.invokeOnCompletion { conn.disconnect() }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "ElevenLabs $code for \"${text.take(80)}\": ${detail?.take(500)}")
                throw AssistantException(ElevenLabs.errorMessage(code, detail))
            }
            conn.inputStream.use { input ->
                val buf = ByteArray(4096)
                var carry: Byte? = null // 16-bit samples must not be split
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val joined = if (carry != null) byteArrayOf(carry) + buf.copyOf(n) else buf.copyOf(n)
                    val even = joined.size and 1.inv()
                    carry = if (even < joined.size) joined.last() else null
                    if (even > 0) emit(joined.copyOf(even))
                }
            }
        } catch (e: IOException) {
            throw AssistantException("No connection to ElevenLabs.", e)
        } finally {
            hook.dispose()
            conn.disconnect()
        }
    }

    private companion object {
        const val TAG = "ElevenLabsSpeaker"
    }
}
