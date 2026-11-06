package com.titus.dynamicisland.ui.island

import android.graphics.BlurMaskFilter
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titus.dynamicisland.appGraph
import com.titus.dynamicisland.data.assistant.GeoPoint
import com.titus.dynamicisland.data.map.MapTile
import com.titus.dynamicisland.data.map.Mercator
import com.titus.dynamicisland.data.settings.IslandSettings
import com.titus.dynamicisland.data.system.Geo
import com.titus.dynamicisland.domain.IslandActivity
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** The map island's size and corners (the design's: like the assistant). */
val MapWidth = 236.dp
val MapHeight = 270.dp
val MapCorner = 44.dp

// ---------------- Scene: streets and buildings in metres around the target ----------------

/** [points] as x0,y0,x1,y1… in metres east (x) and south (y) of the scene's origin. */
class SceneRoad(val width: Float, val points: FloatArray)
class SceneBuilding(val ring: FloatArray, val heightM: Float, val cx: Float, val cy: Float)
class MapScene(val origin: GeoPoint, val roads: List<SceneRoad>, val buildings: List<SceneBuilding>)

/** Width in dp by street class (OpenMapTiles), before the street-width setting. */
private fun roadWidth(cls: String) = when (cls) {
    "motorway", "trunk", "primary", "secondary" -> 9.2f
    "path", "track", "pier", "bridleway" -> 2f
    else -> 4.6f
}

fun sceneFrom(tiles: List<MapTile>, origin: GeoPoint): MapScene {
    val x0 = Mercator.x(origin.lon)
    val y0 = Mercator.y(origin.lat)
    val m = Mercator.metres(origin.lat)
    val roads = ArrayList<SceneRoad>()
    val buildings = ArrayList<SceneBuilding>()
    for (t in tiles) {
        val n = (1 shl t.z).toDouble()
        val unit = 1.0 / (t.extent * n)
        val tx = t.x / n - x0
        val ty = t.y / n - y0
        fun conv(p: FloatArray) = FloatArray(p.size) { i ->
            (((if (i % 2 == 0) tx else ty) + p[i] * unit) * m).toFloat()
        }
        t.roads.forEach { roads += SceneRoad(roadWidth(it.cls), conv(it.points)) }
        t.buildings.forEach { b ->
            val r = conv(b.ring)
            var sx = 0f
            var sy = 0f
            for (i in r.indices step 2) { sx += r[i]; sy += r[i + 1] }
            buildings += SceneBuilding(r, b.heightM, sx / (r.size / 2), sy / (r.size / 2))
        }
    }
    return MapScene(origin, roads, buildings)
}

/** A made-up neighbourhood for the settings preview, laid out like the design canvas. */
private val SampleScene: MapScene by lazy {
    val roads = ArrayList<SceneRoad>()
    val xs = listOf(-190f, -95f, 0f, 100f, 200f)
    val ys = listOf(-180f, -60f, 60f, 170f)
    xs.forEach { x -> roads += SceneRoad(if (x == 0f) 9.2f else 4.6f, floatArrayOf(x, -400f, x, 400f)) }
    ys.forEach { y -> roads += SceneRoad(if (y == 60f) 9.2f else 4.6f, floatArrayOf(-400f, y, 400f, y)) }
    roads += SceneRoad(4.6f, floatArrayOf(-250f, 170f, 100f, -180f))
    val buildings = ArrayList<SceneBuilding>()
    var seed = 7
    fun rnd(): Float { seed = (seed * 1103515245 + 12345) and 0x7fffffff; return (seed % 1000) / 1000f }
    for (i in 0 until xs.size - 1) for (j in 0 until ys.size - 1) {
        val bx = xs[i] + 12
        val by = ys[j] + 12
        val w = xs[i + 1] - xs[i] - 24
        val h = ys[j + 1] - ys[j] - 24
        var y = by
        while (y < by + h - 10) {
            var x = bx
            val rowH = 14 + rnd() * 10
            while (x < bx + w - 10) {
                val bw = 12 + rnd() * 14
                val r = floatArrayOf(x, y, x + bw, y, x + bw, y + rowH, x, y + rowH)
                buildings += SceneBuilding(r, 8 + rnd() * 14, x + bw / 2, y + rowH / 2)
                x += bw + 3
            }
            y += rowH + 4
        }
    }
    MapScene(GeoPoint(0.0, 0.0), roads, buildings)
}

// ---------------- The island ----------------

/** What the map needs each frame. Positions in scene metres. */
private class MapFrame(
    val scene: MapScene,
    val me: Offset?,
    val target: Offset,
    val headingDeg: Float,
    val distanceM: Double?,
)

@Composable
fun MapContent(map: IslandActivity.Map, s: IslandSettings, topInset: Dp) {
    val frame = if (map.preview) {
        remember { mutableStateOf(MapFrame(SampleScene, Offset.Zero, Offset(70f, -80f), 15f, 106.0)) }
    } else liveFrame(map)
    val f = frame.value
    val measurer = rememberTextMeasurer()
    val pulse = rememberInfiniteTransition(label = "pulse")
    val interval = (s.mapPulseIntervalS * 1000).toInt().coerceAtLeast(200)
    // One clock for all rings: each starts an interval after the last and lives three intervals.
    val phase by pulse.animateFloat(0f, 3f, infiniteRepeatable(tween(interval * 3, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val base = rememberGraphicsLayer()
    val steps = List(BLUR_STEPS) { rememberGraphicsLayer() }
    val composite = rememberGraphicsLayer()
    val masks = remember { MaskCache() }

    Canvas(Modifier.fillMaxSize()) {
        val pxPerM = size.width / s.mapSpanM.coerceAtLeast(50)
        val rot = if (s.mapHeadingUp) -f.headingDeg else 0f
        val me = f.me ?: f.target
        val view = MapView(center, me, pxPerM, rot)

        // Records get their own density: nested records on this scope's would chase each other's density forever.
        val dens = Density(density, fontScale)
        val px = IntSize(size.width.toInt(), size.height.toInt())
        // 1. The map itself, once.
        base.record(dens, layoutDirection, px) { drawMap(f, view, s) }
        // 2. Blur steps, sharpest innermost, each fading out into the next (the design's progressive blur).
        val blurOn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && s.mapBlurMax > 0f
        val m = masks.get(this, s)
        composite.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
        composite.record(dens, layoutDirection, px) {
            for (k in BLUR_STEPS - 1 downTo 0) {
                if (k > 0 && !blurOn) continue
                val layer = steps[k]
                val radius = s.mapBlurMax.dp.toPx() * (k / (BLUR_STEPS - 1f)).pow(s.mapBlurCurve)
                layer.renderEffect = if (k > 0 && radius > 0.5f) BlurEffect(radius, radius, TileMode.Decal) else null
                layer.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
                layer.record(dens, layoutDirection, px) {
                    drawLayer(base)
                    // The outermost (blurriest) step covers everything; every sharper one ends a bit further in.
                    if (k < BLUR_STEPS - 1 && blurOn) drawImage(m.steps[k], blendMode = BlendMode.DstIn)
                }
                drawLayer(layer)
            }
            drawImage(m.fade, blendMode = BlendMode.DstIn)
        }
        drawLayer(composite)

        // 3. Sharp on top: the target, you, the compass and the distance.
        drawTarget(f, view, s)
        if (f.me != null) drawMe(phase, s)
        if (s.mapCompass) drawCompass(f.headingDeg, topInset, measurer)
        if (s.mapDistanceLabel) drawDistance(map.target.label, f.distanceM, measurer)
    }
}

/** The live map: position, heading and tiles from the map controller, the scene rebuilt when tiles arrive. */
@Composable
private fun liveFrame(map: IslandActivity.Map): State<MapFrame> {
    val controller = LocalContext.current.appGraph.map
    val position by controller.position.collectAsState()
    val heading by controller.heading.collectAsState()
    val tiles by controller.loadedTiles.collectAsState()
    val origin = map.target.point
    val scene = remember(tiles, origin) { sceneFrom(tiles, origin) }
    val x0 = Mercator.x(origin.lon)
    val y0 = Mercator.y(origin.lat)
    val m = Mercator.metres(origin.lat)
    val me = position?.let { Offset(((Mercator.x(it.lon) - x0) * m).toFloat(), ((Mercator.y(it.lat) - y0) * m).toFloat()) }
    val dist = position?.let { Geo.distance(it, origin) }
    return remember { mutableStateOf(MapFrame(scene, me, Offset.Zero, heading, dist)) }.also {
        it.value = MapFrame(scene, me, Offset.Zero, heading, dist)
    }
}

/** Scene metres → screen pixels: [me] at [center], [rotDeg] turning the world (heading up). */
private class MapView(val center: Offset, val me: Offset, val pxPerM: Float, rotDeg: Float) {
    private val c = cos(Math.toRadians(rotDeg.toDouble())).toFloat()
    private val s = sin(Math.toRadians(rotDeg.toDouble())).toFloat()
    fun x(mx: Float, my: Float): Float { val dx = (mx - me.x) * pxPerM; val dy = (my - me.y) * pxPerM; return center.x + dx * c - dy * s }
    fun y(mx: Float, my: Float): Float { val dx = (mx - me.x) * pxPerM; val dy = (my - me.y) * pxPerM; return center.y + dx * s + dy * c }
    fun at(p: Offset) = Offset(x(p.x, p.y), y(p.x, p.y))
}

private const val BLUR_STEPS = 6

private fun DrawScope.drawMap(f: MapFrame, v: MapView, s: IslandSettings) {
    drawRect(Color.Black)
    val radiusM = hypot(size.width, size.height) / v.pxPerM * 0.6f
    fun near(x: Float, y: Float) = abs(x - v.me.x) < radiusM + 60 && abs(y - v.me.y) < radiusM + 60
    // Streets, grouped by width so each width is one path.
    val byWidth = HashMap<Float, Path>()
    for (r in f.scene.roads) {
        val p = r.points
        var any = false
        for (i in p.indices step 2) if (near(p[i], p[i + 1])) { any = true; break }
        if (!any) continue
        val path = byWidth.getOrPut(r.width) { Path() }
        path.moveTo(v.x(p[0], p[1]), v.y(p[0], p[1]))
        for (i in 2 until p.size step 2) path.lineTo(v.x(p[i], p[i + 1]), v.y(p[i], p[i + 1]))
    }
    byWidth.forEach { (w, path) ->
        drawPath(path, Color.White, style = Stroke(w * s.mapStreetWidth * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    // Buildings: roofs lifted away from the centre by their height, walls between, far ones first.
    val visible = f.scene.buildings.filter { near(it.cx, it.cy) }
    val lean = if (s.mapBuildings3d) 0.0035f * s.map3dStrength else 0f
    val ordered = if (lean > 0f) visible.sortedByDescending { hypot(it.cx - v.me.x, it.cy - v.me.y) } else visible
    val wall = Path()
    val roof = Path()
    for (b in ordered) {
        val r = b.ring
        val n = r.size / 2
        val bx = FloatArray(n) { v.x(r[it * 2], r[it * 2 + 1]) }
        val by = FloatArray(n) { v.y(r[it * 2], r[it * 2 + 1]) }
        val k = lean * b.heightM
        val rx = FloatArray(n) { bx[it] + (bx[it] - v.center.x) * k }
        val ry = FloatArray(n) { by[it] + (by[it] - v.center.y) * k }
        if (k > 0f) {
            for (i in 0 until n) {
                val j = (i + 1) % n
                wall.reset()
                wall.moveTo(bx[i], by[i]); wall.lineTo(bx[j], by[j]); wall.lineTo(rx[j], ry[j]); wall.lineTo(rx[i], ry[i]); wall.close()
                // Light from the top left: walls facing it are lighter (the design's two greys).
                val ex = bx[j] - bx[i]
                val ey = by[j] - by[i]
                val len = hypot(ex, ey).coerceAtLeast(0.001f)
                val lit = ((-ey / len) * -0.6f + (ex / len) * -0.8f).coerceIn(-1f, 1f)
                val g = (137 + 18 * lit) / 255f
                drawPath(wall, Color(g, g, g))
            }
        }
        roof.reset()
        roof.moveTo(rx[0], ry[0])
        for (i in 1 until n) roof.lineTo(rx[i], ry[i])
        roof.close()
        drawPath(roof, Color.White)
    }
    // The straight line from you to the target, the map's only color.
    if (s.mapLine && f.me != null) {
        drawLine(Color(s.mapLineColor.toInt()), v.center, v.at(f.target), strokeWidth = 5.5f * density, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawTarget(f: MapFrame, v: MapView, s: IslandSettings) {
    val color = Color(s.mapLineColor.toInt())
    val p = v.at(f.target)
    val inset = 22.dp.toPx()
    val inside = p.x in inset..(size.width - inset) && p.y in inset..(size.height - inset)
    if (inside) {
        drawCircle(Color.Black, 9.dp.toPx(), p)
        drawCircle(color, 6.5.dp.toPx(), p)
        drawCircle(Color.White, 2.5.dp.toPx(), p)
        return
    }
    // Off the map: an arrow at the edge, pointing the way.
    val dx = p.x - center.x
    val dy = p.y - center.y
    val t = min((size.width / 2 - inset) / abs(dx).coerceAtLeast(0.001f), (size.height / 2 - inset) / abs(dy).coerceAtLeast(0.001f))
    val at = Offset(center.x + dx * t, center.y + dy * t)
    val angle = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
    rotate(angle, at) {
        val a = 9.dp.toPx()
        val path = Path().apply {
            moveTo(at.x + a, at.y); lineTo(at.x - a * 0.7f, at.y - a * 0.75f); lineTo(at.x - a * 0.35f, at.y); lineTo(at.x - a * 0.7f, at.y + a * 0.75f); close()
        }
        drawPath(path, Color.Black, style = Stroke(5.dp.toPx(), join = StrokeJoin.Round))
        drawPath(path, color)
    }
}

/** You: a white dot in a black ring, and rings pulsing out (the design's). */
private fun DrawScope.drawMe(phase: Float, s: IslandSettings) {
    if (s.mapPulse) {
        repeat(3) { i ->
            // Ring i started i intervals ago; its life is three intervals.
            val t = ((phase - i + 3f) % 3f) / 3f
            val eased = 1f - (1f - t).pow(2.4f)
            drawCircle(Color.White.copy(alpha = 0.9f * (1f - t)), 26.dp.toPx() * (0.2f + 0.8f * eased), center, style = Stroke(1.5.dp.toPx()))
        }
    }
    drawCircle(Color.Black, 7.dp.toPx(), center)
    drawCircle(Color.White, 4.5.dp.toPx(), center)
}

/** A heading tape under the camera: ticks every 15°, letters at the cardinals, a mark at the middle. */
private fun DrawScope.drawCompass(headingDeg: Float, topInset: Dp, measurer: androidx.compose.ui.text.TextMeasurer) {
    val y = topInset.toPx() + 14.dp.toPx()
    val half = 64.dp.toPx()
    val spanDeg = 90f
    val pxPerDeg = half / (spanDeg / 2)
    val style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
        shadow = Shadow(Color.Black, blurRadius = 6f))
    var d = (kotlin.math.floor((headingDeg - spanDeg / 2) / 15f) * 15f)
    while (d <= headingDeg + spanDeg / 2) {
        val x = center.x + (d - headingDeg) * pxPerDeg
        val fadeOut = 1f - (abs(x - center.x) / half).coerceIn(0f, 1f)
        val norm = ((d % 360f) + 360f) % 360f
        val letter = when (norm.toInt()) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> null }
        if (letter != null) {
            val layout = measurer.measure(letter, style.copy(color = Color.White.copy(alpha = fadeOut)))
            drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f))
        } else {
            drawLine(Color.White.copy(alpha = 0.7f * fadeOut), Offset(x, y - 3.dp.toPx()), Offset(x, y + 3.dp.toPx()), strokeWidth = 1.dp.toPx())
        }
        d += 15f
    }
    val tip = 4.dp.toPx()
    val my = y + 9.dp.toPx()
    drawPath(Path().apply { moveTo(center.x, my - tip); lineTo(center.x - tip, my + tip * 0.4f); lineTo(center.x + tip, my + tip * 0.4f); close() }, Color.White)
}

private fun DrawScope.drawDistance(label: String, distanceM: Double?, measurer: androidx.compose.ui.text.TextMeasurer) {
    val text = listOfNotNull(label, distanceM?.let { Geo.distanceLabel(it) }).joinToString(" · ")
    val style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, shadow = Shadow(Color.Black, blurRadius = 10f))
    val layout = measurer.measure(text, style)
    val y = size.height - 30.dp.toPx() - layout.size.height
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, y))
    // OpenStreetMap's licence asks for the credit where the map is shown.
    val credit = measurer.measure("© OpenStreetMap", TextStyle(fontSize = 7.sp, color = Color.White.copy(alpha = 0.45f)))
    drawText(credit, topLeft = Offset(center.x - credit.size.width / 2f, size.height - 18.dp.toPx() - credit.size.height / 2f))
}

// ---------------- Soft masks for the blur steps and the fade ----------------

/** Rounded rectangles inset from the island's edge with soft edges, as alpha bitmaps; rebuilt when size or settings change. */
private class MaskCache {
    class Masks(val steps: List<ImageBitmap>, val fade: ImageBitmap)
    private var key: List<Any>? = null
    private var masks: Masks? = null

    fun get(scope: DrawScope, s: IslandSettings): Masks = with(scope) {
        val k = listOf(size, density, s.mapBlurStartDp, s.mapFadeStartDp)
        masks?.takeIf { key == k }?.let { return it }
        val w = size.width.toInt().coerceAtLeast(1)
        val h = size.height.toInt().coerceAtLeast(1)
        val r = MapCorner.toPx()
        val blurStart = s.mapBlurStartDp.dp.toPx()
        val soft = blurStart / BLUR_STEPS * 0.9f
        val steps = (0 until BLUR_STEPS - 1).map { kk ->
            softRect(w, h, blurStart * (1 - kk / (BLUR_STEPS - 1f)) + soft, r, soft)
        }
        val fadeStart = s.mapFadeStartDp.dp.toPx()
        return Masks(steps, softRect(w, h, fadeStart / 2, r, fadeStart / 4)).also { masks = it; key = k }
    }

    /** A white rounded rect inset by [inset], corners concentric with the island's, edges blurred by [sigma]. */
    private fun softRect(w: Int, h: Int, inset: Float, islandRadius: Float, sigma: Float): ImageBitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val canvas = android.graphics.Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            if (sigma > 0.5f) maskFilter = BlurMaskFilter(sigma * 1.7f, BlurMaskFilter.Blur.NORMAL)
        }
        val rr = max(islandRadius - inset, 6f)
        canvas.drawRoundRect(RectF(inset, inset, w - inset, h - inset), rr, rr, paint)
        return bmp.asImageBitmap()
    }
}

// ---------------- The pill ----------------

/** Left of the camera: an arrow pointing to the target, turned by where you're facing. */
@Composable
fun MapCompactLeft(map: IslandActivity.Map, s: IslandSettings, size: Dp) {
    val controller = LocalContext.current.appGraph.map
    val position by controller.position.collectAsState()
    val heading by controller.heading.collectAsState()
    val bearing = position?.let { Geo.bearing(it, map.target.point).toFloat() } ?: 0f
    Icon(
        Icons.Rounded.Navigation, null,
        Modifier.size(size).rotate(bearing - heading),
        tint = Color(s.mapLineColor.toInt()),
    )
}

/** Right of the camera: the distance. */
@Composable
fun MapCompactRight(map: IslandActivity.Map) {
    val controller = LocalContext.current.appGraph.map
    val position by controller.position.collectAsState()
    Text(
        position?.let { Geo.distanceLabel(Geo.distance(it, map.target.point)) } ?: "…",
        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1,
    )
}
