package com.titus.dynamicisland.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** Loads and caches app labels and icons. Safe to call from any thread. */
class AppIconLoader(private val context: Context) {
    private val pm = context.packageManager
    private val icons = LruCache<String, ImageBitmap>(64)
    private val labels = LruCache<String, String>(128)

    fun label(pkg: String): String = labels.get(pkg) ?: try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }.also { labels.put(pkg, it) }

    fun icon(pkg: String): ImageBitmap? = icons.get(pkg) ?: try {
        pm.getApplicationIcon(pkg).toImage(ICON_PX)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }?.also { icons.put(pkg, it) }

    fun load(icon: Icon?, sizePx: Int = ICON_PX): ImageBitmap? =
        icon?.let { runCatching { it.loadDrawable(context)?.toImage(sizePx) }.getOrNull() }

    private fun Drawable.toImage(sizePx: Int): ImageBitmap = toBitmap(sizePx, sizePx).asImageBitmap()

    private companion object {
        const val ICON_PX = 96
    }
}
