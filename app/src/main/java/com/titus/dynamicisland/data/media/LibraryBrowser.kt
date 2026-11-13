package com.titus.dynamicisland.data.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A player's media library as Android Auto sees it (its MediaBrowserService): the user's own playlists
 * live there, while "play from search" goes to the player's catalog. Many players only let Android Auto
 * and Google in; [tree] says whether this one lets us.
 */
class LibraryBrowser(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())

    fun service(pkg: String): ComponentName? = context.packageManager
        .queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0)
        .firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }

    /** Debug: the library's items down to [depth] levels, or why it couldn't connect. Blocks; not on the main thread. */
    fun tree(pkg: String, depth: Int = 2): String {
        val component = service(pkg) ?: return "$pkg has no media browser service."
        val out = StringBuilder("service: ${component.className}\n")
        val connected = CountDownLatch(1)
        var browser: MediaBrowser? = null
        var failure: String? = null
        main.post {
            browser = MediaBrowser(context, component, object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() = connected.countDown()
                override fun onConnectionFailed() { failure = "connection refused (the app only lets certain callers in)"; connected.countDown() }
                override fun onConnectionSuspended() { failure = "connection suspended"; connected.countDown() }
            }, null).also { it.connect() }
        }
        if (!connected.await(8, TimeUnit.SECONDS)) failure = "no answer within 8 s"
        val b = browser
        if (failure != null || b == null) {
            main.post { b?.disconnect() }
            return out.append(failure).toString()
        }
        fun walk(id: String, level: Int) {
            val done = CountDownLatch(1)
            var items: List<MediaBrowser.MediaItem> = emptyList()
            main.post {
                b.subscribe(id, object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                        items = children.toList(); b.unsubscribe(id); done.countDown()
                    }
                    override fun onError(parentId: String) { done.countDown() }
                })
            }
            if (!done.await(6, TimeUnit.SECONDS)) { out.append("  ".repeat(level)).append("(no answer for $id)\n"); return }
            for (item in items.take(MAX_PER_LEVEL)) {
                val kind = listOfNotNull("browsable".takeIf { item.isBrowsable }, "playable".takeIf { item.isPlayable }).joinToString("+")
                out.append("  ".repeat(level)).append("- ${item.description.title} [$kind] id=${item.mediaId}\n")
                if (item.isBrowsable && level < depth) item.mediaId?.let { walk(it, level + 1) }
            }
            if (items.isEmpty()) out.append("  ".repeat(level)).append("(empty)\n")
        }
        out.append("root: ${b.root}\n")
        walk(b.root, 0)
        main.post { b.disconnect() }
        return out.toString()
    }

    private companion object {
        const val MAX_PER_LEVEL = 25
    }
}
