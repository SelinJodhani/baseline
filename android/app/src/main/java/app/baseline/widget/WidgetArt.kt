package app.baseline.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Widget art as single-colour MASKS: everything is drawn opaque white and the widget tints each
 * layer with a palette colour resource (ColorFilter.tint). On Android 12+ the launcher resolves those
 * resources itself, so dynamic (wallpaper) colour and light/dark follow live without re-rendering.
 * Multi-colour art (rings, stage bars, charts) is several stacked masks, one per colour. Flat only.
 *
 * Bitmaps are sized in real pixels from the display density, so they stay crisp on every screen.
 */
object WidgetArt {
    /** Largest single bitmap we hand to RemoteViews (ARGB pixels); bigger shapes are drawn smaller and scaled. */
    private const val MAX_PIXELS = 420_000

    private fun density(context: Context) = context.resources.displayMetrics.density
    fun px(context: Context, dp: Dp): Float = dp.value * density(context)
    private fun ipx(context: Context, dp: Dp): Int = px(context, dp).roundToInt().coerceAtLeast(1)

    private fun bitmap(w: Int, h: Int) = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)

    private fun fill() = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }
    private fun stroke(w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = w
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    // ------------------------------------------------------------------ rings

    /** A progress ring as two masks: the indicator arc and the remaining track (null when empty). */
    class Ring(val track: Bitmap?, val indicator: Bitmap?)

    /**
     * Material 3 style ring: indicator from 12 o'clock clockwise, then a small gap, then the track for the
     * remainder. [fraction] null or 0 draws only the full track.
     */
    fun ring(context: Context, size: Dp, stroke: Dp, fraction: Float?): Ring {
        val s = ipx(context, size)
        val sw = px(context, stroke).coerceAtMost(s / 3f)
        val r = (s - sw) / 2f
        val rect = RectF(sw / 2, sw / 2, s - sw / 2, s - sw / 2)
        val capDeg = Math.toDegrees((sw / 2 / r).toDouble()).toFloat()
        val gapDeg = Math.toDegrees((sw * 0.85f / r).toDouble()).toFloat()
        val paint = stroke(sw)
        val f = fraction?.coerceIn(0f, 1f)

        fun segment(c: Canvas, from: Float, to: Float) {
            val sweep = (to - from) - 2 * capDeg
            if (sweep <= 0.5f) {
                val mid = Math.toRadians(((from + to) / 2 - 90).toDouble())
                c.drawCircle(s / 2f + r * cos(mid).toFloat(), s / 2f + r * sin(mid).toFloat(), sw / 2, fill())
            } else c.drawArc(rect, from + capDeg - 90f, sweep, false, paint)
        }

        return when {
            f == null || f < 0.005f -> {
                val t = bitmap(s, s); Canvas(t).drawCircle(s / 2f, s / 2f, r, stroke(sw).apply { strokeCap = Paint.Cap.BUTT })
                Ring(t, null)
            }
            f > 0.995f -> {
                val i = bitmap(s, s); Canvas(i).drawCircle(s / 2f, s / 2f, r, stroke(sw).apply { strokeCap = Paint.Cap.BUTT })
                Ring(null, i)
            }
            else -> {
                val end = 360f * f
                val i = bitmap(s, s); segment(Canvas(i), 0f, end)
                val trackFrom = end + gapDeg; val trackTo = 360f - gapDeg
                val t = if (trackTo - trackFrom > 0.5f) bitmap(s, s).also { segment(Canvas(it), trackFrom, trackTo) } else null
                Ring(t, i)
            }
        }
    }

    // ------------------------------------------------------------------ bars

    /**
     * Proportion bar (sleep stages): one capsule segment per part with 2dp gaps, one mask per part so
     * each can take its own colour. Returns null for parts that are zero; all-zero gives a single track.
     */
    fun segments(context: Context, width: Dp, height: Dp, parts: List<Float>): Pair<List<Bitmap?>, Bitmap?> {
        val w = ipx(context, width); val h = ipx(context, height)
        val visible = parts.withIndex().filter { it.value > 0f }
        val total = visible.sumOf { it.value.toDouble() }.toFloat()
        if (total <= 0f) {
            val t = bitmap(w, h); Canvas(t).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, fill())
            return parts.map { null } to t
        }
        val gap = px(context, 2.dp)
        val usable = w - gap * (visible.size - 1)
        // Minimum width = height so tiny parts stay visible as dots; take the excess from the largest.
        val widths = visible.map { (usable * it.value / total).coerceAtLeast(h.toFloat()) }.toMutableList()
        val excess = widths.sum() - usable
        if (excess > 0) { val big = widths.indices.maxBy { widths[it] }; widths[big] = (widths[big] - excess).coerceAtLeast(h.toFloat()) }
        val out = MutableList<Bitmap?>(parts.size) { null }
        var x = 0f
        visible.forEachIndexed { k, iv ->
            val bmp = bitmap(w, h)
            val right = (x + widths[k]).coerceAtMost(w.toFloat())
            Canvas(bmp).drawRoundRect(RectF(x, 0f, right, h.toFloat()), h / 2f, h / 2f, fill())
            out[iv.index] = bmp
            x = right + gap
        }
        return out to null
    }

    /** Seven nights as capsule bars: earlier nights, last night, and a dashed line at your sleep need. */
    class NightBars(val earlier: Bitmap, val last: Bitmap, val need: Bitmap)

    fun nightBars(context: Context, width: Dp, height: Dp, nights: List<Int?>, needMin: Int): NightBars {
        val w = ipx(context, width); val h = ipx(context, height)
        val n = nights.size.coerceAtLeast(1)
        val slot = w / n.toFloat()
        val bw = min(slot * 0.58f, px(context, 14.dp))
        val top = px(context, 2.dp)
        val scaleMax = max(needMin * 1.15f, (nights.filterNotNull().maxOrNull() ?: 0).toFloat()).coerceAtLeast(60f)
        val earlier = bitmap(w, h); val last = bitmap(w, h); val need = bitmap(w, h)
        val ce = Canvas(earlier); val cl = Canvas(last)
        nights.forEachIndexed { i, m ->
            val cx = slot * i + slot / 2
            val bh = if (m == null || m <= 0) bw else (m / scaleMax * (h - top)).coerceAtLeast(bw)
            val rect = RectF(cx - bw / 2, h - bh, cx + bw / 2, h.toFloat())
            val c = if (i == nights.lastIndex) cl else ce
            if (m == null || m <= 0) c.drawCircle(cx, h - bw / 2, bw / 2 * 0.6f, fill())
            else c.drawRoundRect(rect, bw / 2, bw / 2, fill())
        }
        val y = h - needMin / scaleMax * (h - top)
        val lw = px(context, 1.5f.dp)
        val dash = px(context, 3.dp)
        Canvas(need).drawLine(0f, y, w.toFloat(), y, stroke(lw).apply { strokeCap = Paint.Cap.BUTT; pathEffect = DashPathEffect(floatArrayOf(dash, dash), 0f) })
        return NightBars(earlier, last, need)
    }

    // ------------------------------------------------------------------ sparkline

    /** Sparkline masks: your normal band (optional), the line, and the latest point. */
    class Spark(val band: Bitmap?, val line: Bitmap, val dot: Bitmap)

    fun spark(context: Context, width: Dp, height: Dp, values: List<Double>, bandLow: Double?, bandHigh: Double?): Spark? {
        if (values.size < 2) return null
        val w = ipx(context, width); val h = ipx(context, height)
        val dotR = px(context, 2.75f.dp)
        val pad = dotR + px(context, 1.dp)
        val lo0 = listOfNotNull(values.min(), bandLow).min()
        val hi0 = listOfNotNull(values.max(), bandHigh).max()
        val span = (hi0 - lo0).takeIf { it > 1e-9 } ?: 1.0
        val lo = lo0 - span * 0.08; val hi = hi0 + span * 0.08
        fun x(i: Int) = pad + (w - 2 * pad) * i / (values.size - 1).toFloat()
        fun y(v: Double) = (pad + (h - 2 * pad) * (1 - (v - lo) / (hi - lo))).toFloat()
        val band = if (bandLow != null && bandHigh != null) bitmap(w, h).also {
            val r = px(context, 3.dp)
            Canvas(it).drawRoundRect(RectF(0f, y(bandHigh), w.toFloat(), max(y(bandLow), y(bandHigh) + px(context, 2.dp))), r, r, fill())
        } else null
        val line = bitmap(w, h)
        val path = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        Canvas(line).drawPath(path, stroke(px(context, 1.6f.dp)))
        val dot = bitmap(w, h)
        Canvas(dot).drawCircle(x(values.lastIndex), y(values.last()), dotR, fill())
        return Spark(band, line, dot)
    }

    // ------------------------------------------------------------------ shapes

    private fun capped(w: Int, h: Int): Float {
        val p = w.toLong() * h
        return if (p <= MAX_PIXELS) 1f else sqrt(MAX_PIXELS / p.toFloat())
    }

    /**
     * Squircle: a rectangle whose corners are superellipse quadrants (continuous curvature, the
     * "smooth corner" look). [radiusFraction] of the short side sets the corner size.
     */
    fun squircle(context: Context, width: Dp, height: Dp, radiusFraction: Float = 0.42f, exponent: Float = 4f): Bitmap {
        val k = capped(ipx(context, width), ipx(context, height))
        val w = (px(context, width) * k).roundToInt().coerceAtLeast(1)
        val h = (px(context, height) * k).roundToInt().coerceAtLeast(1)
        val r = min(w, h) * radiusFraction.coerceIn(0.05f, 0.5f)
        val path = Path()
        val corners = listOf(
            Triple(w - r, r, -90f), Triple(w - r, h - r, 0f), Triple(r, h - r, 90f), Triple(r, r, 180f),
        )
        val steps = 32
        var first = true
        val e = 2f / exponent
        corners.forEach { (cx, cy, a0) ->
            for (i in 0..steps) {
                val t = Math.toRadians((a0 + 90f * i / steps).toDouble())
                val c = cos(t).toFloat(); val s = sin(t).toFloat()
                val x = cx + r * sign(c) * abs(c).pow(e)
                val y = cy + r * sign(s) * abs(s).pow(e)
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            }
        }
        path.close()
        return bitmap(w, h).also { Canvas(it).drawPath(path, fill()) }
    }

    /** Soft "cookie": a circle with [lobes] gentle scallops (Material 3 expressive shape family). */
    fun cookie(context: Context, size: Dp, lobes: Int = 9, depth: Float = 0.045f): Bitmap {
        val s0 = ipx(context, size)
        val s = (s0 * capped(s0, s0)).roundToInt().coerceAtLeast(1)
        val c = s / 2f
        val path = Path()
        val n = 720
        for (i in 0..n) {
            val th = 2 * PI * i / n
            val r = c * (1 - depth + depth * cos(lobes * th)).toFloat()
            val x = c + r * sin(th).toFloat(); val y = c - r * cos(th).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return bitmap(s, s).also { Canvas(it).drawPath(path, fill()) }
    }
}
