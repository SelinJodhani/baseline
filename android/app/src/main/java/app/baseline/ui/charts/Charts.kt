package app.baseline.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private suspend fun Animatable<Float, *>.grow() = animateTo(1f, tween(900, easing = FastOutSlowInEasing))

@Composable
private fun rememberGrow(key: Any?): Float {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.grow() }
    return a.value
}

/** 270° gradient gauge. [fraction] 0..1; null draws only the track. */
@Composable
fun Gauge(
    fraction: Float?,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    stroke: Dp = 18.dp,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: @Composable () -> Unit = {},
) {
    val p = rememberGrow(fraction)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val sw = stroke.toPx()
            val inset = sw / 2
            val arcSize = Size(size.minDimension - sw, size.minDimension - sw)
            val topLeft = Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)
            drawArc(track, 135f, 270f, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            if (fraction != null && fraction > 0f) {
                drawArc(colors.last(), 135f, 270f * fraction.coerceIn(0f, 1f) * p, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            }
            if (inset < 0) return@Canvas
        }
        content()
    }
}

/** Simple progress ring (full circle). */
@Composable
fun Ring(fraction: Float, color: Color, modifier: Modifier = Modifier, stroke: Dp = 8.dp, content: @Composable () -> Unit = {}) {
    val p = rememberGrow(fraction)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val sw = stroke.toPx()
            val s = Size(size.minDimension - sw, size.minDimension - sw)
            val tl = Offset((size.width - s.width) / 2, (size.height - s.height) / 2)
            drawArc(track, 0f, 360f, false, tl, s, style = Stroke(sw))
            val sweep = 360f * fraction.coerceIn(0f, 1f) * p
            drawArc(color, -90f, sweep, false, tl, s, style = Stroke(sw, cap = StrokeCap.Round))
            if (fraction > 1f) { // overflow lap, slightly brighter
                drawArc(color.copy(alpha = .55f), -90f, 360f * ((fraction - 1f).coerceAtMost(1f)) * p, false, tl, s, style = Stroke(sw * .5f, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

private fun smoothPath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) {
        val p0 = points[i - 1]; val p1 = points[i]
        val cx = (p0.x + p1.x) / 2
        cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
    }
}

/** Splits a nullable series into contiguous runs so gaps stay gaps (no invented values). */
private fun runs(values: List<Double?>): List<List<Pair<Int, Double>>> {
    val out = mutableListOf<MutableList<Pair<Int, Double>>>()
    var cur: MutableList<Pair<Int, Double>>? = null
    values.forEachIndexed { i, v ->
        if (v == null) cur = null else {
            if (cur == null) { cur = mutableListOf(); out += cur!! }
            cur!! += i to v
        }
    }
    return out
}

/** Tiny line with soft fill; optional normal band. */
@Composable
fun Sparkline(
    values: List<Double?>,
    color: Color,
    modifier: Modifier = Modifier,
    band: ClosedFloatingPointRange<Double>? = null,
) {
    val p = rememberGrow(values.size)
    Canvas(modifier) {
        val vs = values.filterNotNull()
        if (vs.size < 2) return@Canvas
        var lo = vs.min(); var hi = vs.max()
        band?.let { lo = min(lo, it.start); hi = max(hi, it.endInclusive) }
        val pad = (hi - lo).coerceAtLeast(1e-6) * 0.15
        lo -= pad; hi += pad
        val inset = 6.dp.toPx()
        fun x(i: Int) = if (values.size == 1) inset else inset + i / (values.size - 1f) * (size.width - 2 * inset)
        fun y(v: Double) = (size.height - ((v - lo) / (hi - lo) * size.height)).toFloat()
        band?.let { drawRoundRect(color.copy(alpha = .10f), Offset(0f, y(it.endInclusive)), Size(size.width, y(it.start) - y(it.endInclusive)), CornerRadius(6f)) }
        clipReveal(p) {
            runs(values).forEach { run ->
                val pts = run.map { Offset(x(it.first), y(it.second)) }
                if (pts.size == 1) drawCircle(color, 3.dp.toPx(), pts[0]) else {
                    val path = smoothPath(pts)
                    val fill = Path().apply { addPath(path); lineTo(pts.last().x, size.height); lineTo(pts.first().x, size.height); close() }
                    drawPath(fill, color.copy(alpha = .10f))
                    drawPath(path, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            runs(values).lastOrNull()?.lastOrNull()?.let {
                val c = Offset(x(it.first), y(it.second))
                drawCircle(color.copy(alpha = .25f), 6.dp.toPx(), c)
                drawCircle(color, 3.dp.toPx(), c)
            }
        }
    }
}

private inline fun DrawScope.clipReveal(progress: Float, block: DrawScope.() -> Unit) {
    drawContext.canvas.save()
    drawContext.canvas.clipRect(0f, -100f, size.width * progress, size.height + 100f)
    block()
    drawContext.canvas.restore()
}

data class ChartLabels(val x: List<String>, val format: (Double) -> String)

/** A named horizontal zone, e.g. "Regular" for 83–100. Drawn faintly behind the data. */
data class ChartZone(val from: Double, val to: Double, val label: String, val color: Color)

/** 3–5 round axis values inside [lo, hi] (steps of 1, 2 or 5 × 10ⁿ). */
fun niceTicks(lo: Double, hi: Double, target: Int = 4): List<Double> {
    val span = hi - lo
    if (span <= 1e-9 || span.isNaN()) return listOf(lo)
    val raw = span / target
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw * 0.999 }
    val out = mutableListOf<Double>()
    var v = ceil(lo / step - 1e-9) * step
    while (v <= hi + step * 1e-6 && out.size < 12) {
        out += if (abs(v) < step * 1e-6) 0.0 else v
        v += step
    }
    return out
}

/** The y-range a chart shows: data, average, band and goal; zone edges only when close to the data. */
internal fun chartRange(
    data: List<Double>, band: ClosedFloatingPointRange<Double>?, goal: Double?, zones: List<ChartZone>, zeroBased: Boolean,
): Pair<Double, Double> {
    var lo = data.min(); var hi = data.max()
    goal?.let { lo = min(lo, it); hi = max(hi, it) }
    band?.let { lo = min(lo, it.start); hi = max(hi, it.endInclusive) }
    val base = (hi - lo).coerceAtLeast(maxOf(abs(hi) * 0.05, 1e-3))
    zones.flatMap { listOf(it.from, it.to) }.distinct()
        .filter { it > lo - base * .8 && it < hi + base * .8 }
        .forEach { lo = min(lo, it); hi = max(hi, it) }
    if (zeroBased) lo = min(0.0, lo)
    val span = (hi - lo).coerceAtLeast(maxOf(abs(hi) * 0.05, 1e-3))
    if (!zeroBased) lo -= span * .12
    hi += span * .14
    if (lo < 0 && data.all { it >= 0 } && (band?.start ?: 0.0) >= 0) lo = 0.0
    return lo to hi
}

/**
 * The main trend chart: daily dots + average line + personal band + labelled goal line + named zones, with
 * tap/drag-to-inspect and a tooltip. [positions] (0..1 per value) spaces irregular dates by real time.
 * [onSelect] reports the inspected index (null when released).
 */
@Composable
fun TrendChart(
    values: List<Double?>,
    color: Color,
    labels: ChartLabels,
    modifier: Modifier = Modifier,
    average: List<Double?>? = null,
    band: ClosedFloatingPointRange<Double>? = null,
    bandLabel: String? = null,
    goal: Double? = null,
    goalLabel: String? = null,
    zones: List<ChartZone> = emptyList(),
    bars: Boolean = false,
    zeroBased: Boolean = bars,
    positions: List<Float>? = null,
    tooltip: ((Int) -> String)? = null,
    height: Dp = 220.dp,
    /** Colour of the trend line and its fill (e.g. green when heading the right way); defaults to [color]. */
    trendColor: Color? = null,
    /** Bevel-style: a personal normal range per point (rolling), drawn as a moving band behind hollow dots. */
    rollingBand: List<ClosedFloatingPointRange<Double>?>? = null,
    /** Per-point dot colours (e.g. green above your normal, orange below); defaults to [color]. */
    dotColors: List<Color>? = null,
    /** A dotted reference line with a pill label, e.g. the period average. */
    avgValue: Double? = null,
    avgLabel: String? = null,
    onSelect: (Int?) -> Unit = {},
) {
    val tc = trendColor ?: color
    val measurer = rememberTextMeasurer()
    val p = rememberGrow(values.hashCode())
    var sel by remember(values.size) { mutableStateOf<Int?>(null) }
    val cs = MaterialTheme.colorScheme
    val onSurface = cs.onSurfaceVariant
    val grid = cs.outlineVariant.copy(alpha = .5f)
    val tipBg = cs.inverseSurface
    val tipFg = cs.inverseOnSurface
    val pillBg = cs.surfaceContainerLow
    val labelStyle = TextStyle(fontSize = 11.sp, color = onSurface)
    val n = values.size
    val pos = positions?.takeIf { it.size == n }
    val dens = androidx.compose.ui.platform.LocalDensity.current.density

    // Geometry shared by drawing and touch handling.
    fun plotX(i: Int, left: Float, width: Float): Float {
        val inset = 10f * dens
        val w = width - left - 2 * inset
        return when {
            bars -> left + (width - left) / n * (i + .5f)
            pos != null -> left + inset + pos[i].coerceIn(0f, 1f) * w
            n <= 1 -> left + (width - left) / 2
            else -> left + inset + i / (n - 1f) * w
        }
    }
    val railRoom = if (zones.isNotEmpty()) 10f * dens else 0f
    fun nearest(x: Float, left: Float, width: Float): Int =
        (0 until n).minByOrNull { abs(plotX(it, left, width - railRoom) - x) } ?: 0

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(n, pos) {
                val left = 40.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { sel = nearest(it.x, left, size.width.toFloat()); onSelect(sel) },
                    onDragEnd = { sel = null; onSelect(null) },
                    onDragCancel = { sel = null; onSelect(null) },
                ) { change, _ -> sel = nearest(change.position.x, left, size.width.toFloat()); onSelect(sel) }
            }
            .pointerInput(n, pos) {
                val left = 40.dp.toPx()
                detectTapGestures { o ->
                    val i = nearest(o.x, left, size.width.toFloat())
                    sel = if (sel == i) null else i; onSelect(sel)
                }
            },
    ) {
        if (values.none { it != null }) {
            val m = measurer.measure("Not enough data yet", labelStyle.copy(fontSize = 13.sp))
            drawText(m, topLeft = Offset((size.width - m.size.width) / 2, (size.height - m.size.height) / 2))
            return@Canvas
        }
        val multiLine = labels.x.any { '\n' in it }
        val left = 40.dp.toPx(); val bottom = (if (multiLine) 34 else 22).dp.toPx(); val top = 8.dp.toPx()
        val h = size.height - bottom - top
        val data = values.filterNotNull() + (average?.filterNotNull() ?: emptyList()) +
            (rollingBand?.filterNotNull()?.flatMap { listOf(it.start, it.endInclusive) } ?: emptyList()) + listOfNotNull(avgValue)
        val (lo, hi) = chartRange(data, band, goal, zones, zeroBased)
        fun y(v: Double) = (top + h - ((v - lo) / (hi - lo) * h)).toFloat()

        // Named zones: a thin coloured rail on the right edge plus a hairline at each boundary, so the plot stays clean.
        val rail = 4.dp.toPx()
        val plotRight = if (zones.isNotEmpty()) size.width - rail - 6.dp.toPx() else size.width
        val zoneLabels = mutableListOf<Pair<androidx.compose.ui.text.TextLayoutResult, Offset>>()
        zones.forEach { z ->
            val a = max(z.from, lo); val b = min(z.to, hi)
            if (b <= a) return@forEach
            drawRoundRect(z.color.copy(alpha = .75f), Offset(size.width - rail, y(b) + 1f), Size(rail, (y(a) - y(b) - 2f).coerceAtLeast(1f)), CornerRadius(rail / 2))
            if (z.from > lo && z.from < hi) {
                drawLine(z.color.copy(alpha = .45f), Offset(left, y(z.from)), Offset(plotRight, y(z.from)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
            }
            val tm = measurer.measure(z.label, labelStyle.copy(color = z.color, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
            if (y(a) - y(b) >= tm.size.height + 4) {
                val px = 5.dp.toPx(); val py = 1.dp.toPx()
                val bx = plotRight - tm.size.width - px * 2; val by = y(b) + 3.dp.toPx()
                zoneLabels += tm to Offset(bx, by)
            }
        }
        fun x(i: Int) = plotX(i, left, plotRight)
        // Grid on round values: quiet solid hairlines.
        niceTicks(lo, hi).forEach { v ->
            val yy = y(v)
            if (yy < top - 1 || yy > top + h + 1) return@forEach
            drawLine(grid, Offset(left, yy), Offset(plotRight, yy), 1f)
            val tm = measurer.measure(labels.format(v), labelStyle)
            drawText(tm, topLeft = Offset((left - tm.size.width - 6.dp.toPx()).coerceAtLeast(0f), yy - tm.size.height / 2))
        }
        // X labels, thinned so they never collide.
        if (labels.x.isNotEmpty()) {
            val widest = labels.x.maxOf { t -> t.split('\n').maxOf { measurer.measure(it, labelStyle).size.width } }
            val room = (plotRight - left) / labels.x.size
            val step = max(1, kotlin.math.ceil((widest * 1.15f + 6.dp.toPx()) / room).toInt())
            val last = labels.x.lastIndex
            for (i in labels.x.indices) {
                if ((last - i) % step != 0) continue
                val m = measurer.measure(labels.x[i], labelStyle.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                val xx = (x(i) - m.size.width / 2).coerceIn(left - 6.dp.toPx(), size.width - m.size.width)
                drawText(m, topLeft = Offset(xx, size.height - m.size.height))
            }
        }
        band?.let {
            drawRoundRect(color.copy(alpha = .12f), Offset(left, y(it.endInclusive)), Size(plotRight - left, y(it.start) - y(it.endInclusive)), CornerRadius(8f))
            bandLabel?.let { t ->
                val tm = measurer.measure(t, labelStyle.copy(color = color, fontSize = 10.sp))
                if (y(it.start) - y(it.endInclusive) >= tm.size.height) drawText(tm, topLeft = Offset(left + 6.dp.toPx(), y(it.endInclusive) + 2.dp.toPx()))
            }
        }
        if (rollingBand != null) {
            // Bevel-style: your normal as a band that moves with your history, readings as hollow dots on a thin line.
            val upper = rollingBand.mapIndexedNotNull { i, b -> b?.let { Offset(x(i), y(it.endInclusive)) } }
            val lower = rollingBand.mapIndexedNotNull { i, b -> b?.let { Offset(x(i), y(it.start)) } }
            if (upper.size > 1) {
                val path = smoothPath(upper).apply {
                    val rev = lower.asReversed()
                    lineTo(rev[0].x, rev[0].y)
                    for (k in 1 until rev.size) { val p0 = rev[k - 1]; val p1 = rev[k]; val cx = (p0.x + p1.x) / 2; cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y) }
                    close()
                }
                drawPath(path, color.copy(alpha = .15f))
            }
            avgValue?.let { a ->
                val ay = y(a)
                drawLine(color.copy(alpha = .85f), Offset(left, ay), Offset(plotRight, ay), 1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
            }
            clipRevealRect(left, p) {
                runs(values).forEach { run ->
                    if (run.size > 1) {
                        val path = Path().apply { run.forEachIndexed { k, (i, v) -> if (k == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
                        drawPath(path, color.copy(alpha = .9f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                val r = (if (n > 40) 2.4 else 3.0).dp.toPx()
                val lastI = values.indices.lastOrNull { values[it] != null }
                values.forEachIndexed { i, v ->
                    if (v == null) return@forEachIndexed
                    val c = dotColors?.getOrNull(i) ?: color
                    val o = Offset(x(i), y(v))
                    if (i == lastI) { drawCircle(c.copy(alpha = .25f), r * 2.4f, o); drawCircle(c, r * 1.5f, o) }
                    else { drawCircle(pillBg, r, o); drawCircle(c, r, o, style = Stroke(1.6.dp.toPx())) }
                }
            }
            avgValue?.let { a ->
                val t = avgLabel ?: "Avg ${labels.format(a)}"
                val tm = measurer.measure(t, labelStyle.copy(color = color, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                val padX = 6.dp.toPx(); val padY = 2.dp.toPx()
                val bw = tm.size.width + padX * 2; val bh = tm.size.height + padY * 2
                val bx = left + (plotRight - left) * .38f; val by = y(a) - bh / 2
                drawRoundRect(pillBg, Offset(bx, by), Size(bw, bh), CornerRadius(bh / 2))
                drawRoundRect(color.copy(alpha = .45f), Offset(bx, by), Size(bw, bh), CornerRadius(bh / 2), style = Stroke(1.dp.toPx()))
                drawText(tm, topLeft = Offset(bx + padX, by + padY))
            }
        } else if (bars) {
            val bw = ((plotRight - left) / n * .62f).coerceAtMost(28.dp.toPx())
            values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val yy = y(lo + (v - lo) * p)
                val hit = goal != null && v >= goal
                // With a trend line on top, bars step back so the direction reads first.
                val dim = if (average != null) .55f else 1f
                val c = if (sel == null || sel == i) color.copy(alpha = (if (hit || goal == null) 1f else .55f) * dim) else color.copy(alpha = .25f)
                drawRoundRect(c, Offset(x(i) - bw / 2, yy), Size(bw, (y(lo) - yy).coerceAtLeast(2f)), CornerRadius(bw / 2.5f))
            }
            average?.takeIf { a -> a.count { it != null } >= 2 }?.let { avg ->
                clipRevealRect(left, p) {
                    runs(avg).forEach { run ->
                        if (run.size > 1) drawPath(smoothPath(run.map { Offset(x(it.first), y(it.second)) }), tc, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
        } else {
            val avgLine = average?.takeIf { a -> a.count { it != null } >= 2 }
            if (avgLine != null) {
                // Long range: days fade into faint dots; one smooth trend line with a soft fill shows the direction.
                values.forEachIndexed { i, v -> v?.let { drawCircle(color.copy(alpha = .18f), 1.8.dp.toPx(), Offset(x(i), y(it))) } }
                clipRevealRect(left, p) {
                    runs(avgLine).forEach { run ->
                        val pts = run.map { Offset(x(it.first), y(it.second)) }
                        if (pts.size > 1) {
                            val path = smoothPath(pts)
                            val fill = Path().apply { addPath(path); lineTo(pts.last().x, top + h); lineTo(pts.first().x, top + h); close() }
                            drawPath(fill, tc.copy(alpha = .14f))
                            drawPath(path, tc, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
            } else {
                // Short range: one clear line through each day, a soft fill, and visible points.
                clipRevealRect(left, p) {
                    runs(values).forEach { run ->
                        val pts = run.map { Offset(x(it.first), y(it.second)) }
                        if (pts.size > 1) {
                            val path = smoothPath(pts)
                            if (n <= 60) {
                                val fill = Path().apply { addPath(path); lineTo(pts.last().x, top + h); lineTo(pts.first().x, top + h); close() }
                                drawPath(fill, tc.copy(alpha = .10f))
                            }
                            drawPath(path, tc, style = Stroke((if (n <= 60) 2.5 else 1.8).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        } else if (n > 45) drawCircle(tc, 2.dp.toPx(), pts[0])
                    }
                    if (n <= 45) values.forEachIndexed { i, v -> v?.let { drawCircle(tc, 3.dp.toPx(), Offset(x(i), y(it))) } }
                }
            }
            // The latest reading, ringed.
            (avgLine ?: values).let { line -> line.indices.lastOrNull { line[it] != null }?.let { i ->
                val c = Offset(x(i), y(line[i]!!))
                drawCircle(tc.copy(alpha = .25f), 7.dp.toPx() * p, c)
                drawCircle(tc, 4.dp.toPx() * p, c)
                if (avgLine != null && sel == null && p > .99f) {
                    // Where the trend is now, labelled at the end of the line.
                    val tm = measurer.measure(labels.format(line[i]!!), TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                    val padX = 6.dp.toPx(); val padY = 2.dp.toPx()
                    val bw = tm.size.width + padX * 2; val bh = tm.size.height + padY * 2
                    val bx = (c.x - bw - 8.dp.toPx()).coerceAtLeast(left)
                    val by = (c.y - bh - 6.dp.toPx()).let { if (it < top) c.y + 6.dp.toPx() else it }
                    drawRoundRect(tc, Offset(bx, by), Size(bw, bh), CornerRadius(bh / 2))
                    drawText(tm, topLeft = Offset(bx + padX, by + padY))
                }
            } }
        }
        goal?.let { g ->
            val gy = y(g)
            drawLine(color.copy(alpha = .8f), Offset(left, gy), Offset(plotRight, gy), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            goalLabel?.let { t ->
                val tm = measurer.measure(t, labelStyle.copy(color = color, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 10.sp))
                val padX = 6.dp.toPx(); val padY = 2.dp.toPx()
                val bw = tm.size.width + padX * 2; val bh = tm.size.height + padY * 2
                val bx = plotRight - bw
                val by = if (gy - bh - 2 >= top) gy - bh - 2 else gy + 2
                drawRoundRect(pillBg.copy(alpha = .85f), Offset(bx, by), Size(bw, bh), CornerRadius(bh / 2))
                drawText(tm, topLeft = Offset(bx + padX, by + padY))
            }
        }
        // Zone names on top of the data so the line never hides them.
        zoneLabels.forEach { (tm, o) ->
            val px = 5.dp.toPx(); val py = 1.dp.toPx()
            drawRoundRect(pillBg.copy(alpha = .9f), o, Size(tm.size.width + px * 2, tm.size.height + py * 2), CornerRadius(6.dp.toPx()))
            drawText(tm, topLeft = Offset(o.x + px, o.y + py))
        }
        sel?.let { i ->
            val xx = x(i)
            drawLine(onSurface.copy(alpha = .5f), Offset(xx, top), Offset(xx, top + h), 1.dp.toPx())
            val v = values.getOrNull(i)
            if (v != null && !bars) {
                drawCircle(Color.White, 6.dp.toPx(), Offset(xx, y(v)))
                drawCircle(color, 4.dp.toPx(), Offset(xx, y(v)))
            }
            val text = tooltip?.invoke(i) ?: listOfNotNull(labels.x.getOrNull(i)?.replace('\n', ' '), v?.let(labels.format) ?: "no data").joinToString("  ·  ")
            val tm = measurer.measure(text, TextStyle(fontSize = 12.sp, color = tipFg, lineHeight = 16.sp))
            val padX = 10.dp.toPx(); val padY = 6.dp.toPx()
            val bw = tm.size.width + padX * 2; val bh = tm.size.height + padY * 2
            val bx = (xx - bw / 2).coerceIn(0f, size.width - bw)
            val anchor = v?.let { y(if (bars) lo + (it - lo) * p else it) } ?: (top + h / 2)
            val by = if (anchor - bh - 10.dp.toPx() >= 0f) anchor - bh - 10.dp.toPx() else (anchor + 10.dp.toPx()).coerceAtMost(top + h - bh)
            drawRoundRect(tipBg, Offset(bx, by), Size(bw, bh), CornerRadius(10.dp.toPx()))
            drawText(tm, topLeft = Offset(bx + padX, by + padY))
        }
    }
}

/** What a legend swatch looks like. */
enum class KeyKind { Dot, Line, Dash, Band, Bar }

data class LegendKey(val kind: KeyKind, val color: Color, val label: String)

/** A compact legend under a chart: every mark on the chart, named. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChartLegend(keys: List<LegendKey>, modifier: Modifier = Modifier) {
    if (keys.isEmpty()) return
    androidx.compose.foundation.layout.FlowRow(
        modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        keys.forEach { k ->
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                LegendSwatch(k.kind, k.color)
                androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                androidx.compose.material3.Text(k.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun LegendSwatch(kind: KeyKind, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 20.dp, height = 12.dp)) {
        val cy = size.height / 2
        when (kind) {
            KeyKind.Dot -> drawCircle(color.copy(alpha = .6f), 3.dp.toPx(), Offset(size.width / 2, cy))
            KeyKind.Line -> drawLine(color, Offset(1f, cy), Offset(size.width - 1f, cy), 3.dp.toPx(), cap = StrokeCap.Round)
            KeyKind.Dash -> drawLine(color, Offset(0f, cy), Offset(size.width, cy), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            KeyKind.Band -> drawRoundRect(color.copy(alpha = .22f), Offset.Zero, size, CornerRadius(3.dp.toPx()))
            KeyKind.Bar -> drawRoundRect(color, Offset(size.width / 2 - 4.dp.toPx(), 0f), Size(8.dp.toPx(), size.height), CornerRadius(3.dp.toPx()))
        }
    }
}

private inline fun DrawScope.clipRevealRect(left: Float, progress: Float, block: DrawScope.() -> Unit) {
    drawContext.canvas.save()
    drawContext.canvas.clipRect(0f, -100f, left + (size.width - left) * progress, size.height + 100f)
    block()
    drawContext.canvas.restore()
}

private fun DrawScope.drawLabel(m: TextMeasurer, text: String, at: Offset, style: TextStyle) {
    drawText(m, text, topLeft = at, style = style)
}

/** Sleep stages over the night. stages = (startSec, endSec, stage). */
@Composable
fun Hypnogram(
    stages: List<Triple<Long, Long, String>>,
    colors: Map<String, Color>,
    timeLabel: (Long) -> String,
    modifier: Modifier = Modifier,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val p = rememberGrow(stages.size)
    Canvas(modifier.fillMaxWidth().height(150.dp)) {
        if (stages.isEmpty()) return@Canvas
        val lanes = listOf("awake", "rem", "light", "deep")
        val bottom = 20.dp.toPx()
        val laneH = (size.height - bottom) / lanes.size
        val t0 = stages.first().first; val t1 = stages.last().second
        val span = (t1 - t0).coerceAtLeast(1).toFloat()
        fun x(t: Long) = (t - t0) / span * size.width
        val barH = laneH * .62f
        // Faint lane guides, then each stage as a block in its lane. Adjacent blocks of the same stage are joined and
        // blocks get a minimum width, so a restless stretch reads as a band rather than a mess of hairlines.
        lanes.indices.forEach { i ->
            val cy = i * laneH + laneH / 2
            drawLine(colors[lanes[i]]?.copy(alpha = .12f) ?: Color.Gray, Offset(0f, cy), Offset(size.width, cy), 1.dp.toPx())
        }
        val merged = mutableListOf<Triple<Long, Long, String>>()
        stages.forEach { s -> val last = merged.lastOrNull(); if (last != null && last.third == s.third && s.first - last.second <= 60) merged[merged.lastIndex] = Triple(last.first, s.second, s.third) else merged += s }
        clipReveal(p) {
            val minW = 2.5.dp.toPx()
            merged.forEach { (a, b, st) ->
                val lane = lanes.indexOf(st).coerceAtLeast(0)
                val c = colors[st] ?: Color.Gray
                val yTop = lane * laneH + (laneH - barH) / 2
                val xa = x(a); val xb = max(x(b), xa + minW)
                drawRoundRect(c, Offset(xa, yTop), Size(xb - xa, barH), CornerRadius(minOf(barH / 3, (xb - xa) / 2)))
            }
        }
        val style = TextStyle(fontSize = 11.sp, color = labelColor)
        // Labels on round hours (every 1–2 h) rather than at the odd start/end minute.
        val hours = ((t1 - t0) / 3600).coerceAtLeast(1)
        val stepH = if (hours > 6) 2 else 1
        val off = zone.rules.getOffset(java.time.Instant.ofEpochSecond(t0)).totalSeconds
        var t = ((t0 + off) / 3600 + 1) * 3600 - off
        while (t < t1) {
            val m = measurer.measure(timeLabel(t), style)
            val xx = x(t) - m.size.width / 2
            if (xx >= 0 && xx + m.size.width <= size.width) drawText(m, topLeft = Offset(xx, size.height - m.size.height))
            t += stepH * 3600
        }
    }
}

/** Horizontal stacked proportion bar. */
@Composable
fun StackBar(parts: List<Pair<Float, Color>>, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    val p = rememberGrow(parts.hashCode())
    Canvas(modifier.fillMaxWidth().height(height)) {
        val total = parts.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(1e-6f)
        val gap = 3.dp.toPx()
        var x = 0f
        val usable = size.width * p - gap * (parts.count { it.first > 0 } - 1).coerceAtLeast(0)
        parts.filter { it.first > 0 }.forEach { (v, c) ->
            val w = usable * v / total
            drawRoundRect(c, Offset(x, 0f), Size(w.coerceAtLeast(0f), size.height), CornerRadius(size.height / 2))
            x += w + gap
        }
    }
}

/**
 * Bed→wake windows for recent nights as vertical capsules on a 6 PM → 2 PM clock, with an optional labelled
 * target bedtime line. windows = (bedMinutesFrom18h, wakeMinutesFrom18h) or null for no data. Tap a night to
 * see its times ([tooltip]).
 */
@Composable
fun SleepWindows(
    windows: List<Pair<Float, Float>?>,
    dayLabels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    targetBed: Float? = null,
    targetLabel: String? = null,
    tooltip: ((Int) -> String)? = null,
) {
    val measurer = rememberTextMeasurer()
    val cs = MaterialTheme.colorScheme
    val labelColor = cs.onSurfaceVariant
    val grid = cs.outlineVariant.copy(alpha = .5f)
    val tipBg = cs.inverseSurface; val tipFg = cs.inverseOnSurface; val pillBg = cs.surfaceContainerLow
    val p = rememberGrow(windows.hashCode())
    var sel by remember(windows.size) { mutableStateOf<Int?>(null) }
    val n = windows.size
    Canvas(
        modifier.fillMaxWidth().height(210.dp).pointerInput(n) {
            val left = 44.dp.toPx()
            detectTapGestures { o ->
                if (n == 0) return@detectTapGestures
                val i = ((o.x - left) / ((size.width - left) / n)).toInt().coerceIn(0, n - 1)
                sel = if (sel == i || windows[i] == null) null else i
            }
        },
    ) {
        val vals = windows.filterNotNull()
        if (vals.isEmpty()) return@Canvas
        val multi = dayLabels.any { '\n' in it }
        val left = 44.dp.toPx(); val bottom = (if (multi) 34 else 20).dp.toPx(); val top = 4.dp.toPx()
        val lo = (min(vals.minOf { it.first }, targetBed ?: Float.MAX_VALUE) - 30).coerceAtLeast(0f).let { (it / 60).toInt() * 60f }
        val hi = (vals.maxOf { it.second } + 30).let { ((it / 60).toInt() + 1) * 60f }
        val h = size.height - bottom - top
        fun y(m: Float) = top + (m - lo) / (hi - lo) * h
        val style = TextStyle(fontSize = 11.sp, color = labelColor)
        val step = if (hi - lo > 720) 180f else 120f
        var m = (kotlin.math.ceil(lo / step) * step)
        while (m <= hi) {
            drawLine(grid, Offset(left, y(m)), Offset(size.width, y(m)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f)))
            val hour = ((18 + (m / 60).toInt()) % 24)
            val t = "%d %s".format(if (hour % 12 == 0) 12 else hour % 12, if (hour < 12) "AM" else "PM")
            val tm = measurer.measure(t, style)
            drawText(tm, topLeft = Offset(left - tm.size.width - 6.dp.toPx(), y(m) - tm.size.height / 2))
            m += step
        }
        val slot = (size.width - left) / n
        val bw = (slot * .5f).coerceAtMost(18.dp.toPx())
        windows.forEachIndexed { i, w ->
            val cx = left + slot * i + slot / 2
            if (w != null) {
                val mid = (w.first + w.second) / 2
                val a = mid - (mid - w.first) * p; val b = mid + (w.second - mid) * p
                val c = if (sel == null || sel == i) color else color.copy(alpha = .3f)
                drawRoundRect(c, Offset(cx - bw / 2, y(a)), Size(bw, y(b) - y(a)), CornerRadius(bw / 2))
            }
            if (i < dayLabels.size) {
                val mm = measurer.measure(dayLabels[i], style.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                drawText(mm, topLeft = Offset(cx - mm.size.width / 2, size.height - mm.size.height))
            }
        }
        targetBed?.let { tb ->
            val ty = y(tb)
            drawLine(color.copy(alpha = .85f), Offset(left, ty), Offset(size.width, ty), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            targetLabel?.let { t ->
                val tm = measurer.measure(t, style.copy(color = color, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                val padX = 6.dp.toPx(); val padY = 2.dp.toPx()
                val bw2 = tm.size.width + padX * 2; val bh = tm.size.height + padY * 2
                val by = if (ty - bh - 2 >= 0) ty - bh - 2 else ty + 2
                drawRoundRect(pillBg, Offset(size.width - bw2, by), Size(bw2, bh), CornerRadius(bh / 2))
                drawText(tm, topLeft = Offset(size.width - bw2 + padX, by + padY))
            }
        }
        sel?.let { i ->
            val w = windows.getOrNull(i) ?: return@let
            val text = tooltip?.invoke(i) ?: return@let
            val tm = measurer.measure(text, TextStyle(fontSize = 12.sp, color = tipFg, lineHeight = 16.sp))
            val padX = 10.dp.toPx(); val padY = 6.dp.toPx()
            val bw2 = tm.size.width + padX * 2; val bh = tm.size.height + padY * 2
            val cx = left + slot * i + slot / 2
            val bx = (cx - bw2 / 2).coerceIn(0f, size.width - bw2)
            val by = if (y(w.first) - bh - 8.dp.toPx() >= 0) y(w.first) - bh - 8.dp.toPx() else (y(w.second) + 8.dp.toPx()).coerceAtMost(size.height - bottom - bh)
            drawRoundRect(tipBg, Offset(bx, by), Size(bw2, bh), CornerRadius(10.dp.toPx()))
            drawText(tm, topLeft = Offset(bx + padX, by + padY))
        }
    }
}

/** Intraday min–max band with average line. rows = [epoch, min, avg, max]; x spans [from, to]. */
@Composable
fun IntradayChart(
    rows: List<List<Double>>,
    from: Long,
    to: Long,
    color: Color,
    timeLabel: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val p = rememberGrow(rows.size)
    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        val data = rows.filter { it.size >= 4 && it[0] >= from && it[0] < to }
        if (data.isEmpty()) return@Canvas
        val bottom = 20.dp.toPx(); val left = 30.dp.toPx()
        val h = size.height - bottom; val w = size.width - left
        val lo = data.minOf { it[1] } - 5; val hi = data.maxOf { it[3] } + 5
        fun x(t: Double) = left + ((t + 1800 - from) / (to - from) * w).toFloat()
        fun y(v: Double) = (h - (v - lo) / (hi - lo) * h).toFloat()
        val style = TextStyle(fontSize = 11.sp, color = labelColor)
        drawText(measurer, "%.0f".format(hi - 5), topLeft = Offset(0f, 0f), style = style)
        drawText(measurer, "%.0f".format(lo + 5), topLeft = Offset(0f, h - 14.sp.toPx()), style = style)
        clipRevealRect(left, p) {
            val bw = (w / 24f * .55f)
            data.forEach { r ->
                drawRoundRect(color.copy(alpha = .22f), Offset(x(r[0]) - bw / 2, y(r[3])), Size(bw, (y(r[1]) - y(r[3])).coerceAtLeast(2f)), CornerRadius(bw / 2))
            }
            val pts = data.map { Offset(x(it[0]), y(it[2])) }
            if (pts.size > 1) drawPath(smoothPath(pts), color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        for (k in 0..4) {
            val t = from + (to - from) * k / 4
            val m = measurer.measure(timeLabel(t), style)
            val xx = left + (t - from).toFloat() / (to - from) * w
            drawText(m, topLeft = Offset((xx - m.size.width / 2).coerceIn(left, size.width - m.size.width), size.height - m.size.height))
        }
    }
}

/** Tiny bar sparkline for daily totals (steps, active minutes). */
@Composable
fun MiniBars(values: List<Double?>, color: Color, modifier: Modifier = Modifier) {
    val p = rememberGrow(values.hashCode())
    Canvas(modifier) {
        val hi = values.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: return@Canvas
        val slot = size.width / values.size
        val bw = slot * .6f
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val bh = (v / hi * size.height * p).toFloat().coerceAtLeast(1.5f)
            drawRoundRect(color.copy(alpha = if (i == values.lastIndex) 1f else .55f), Offset(slot * i + (slot - bw) / 2, size.height - bh), Size(bw, bh), CornerRadius(bw / 3))
        }
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Canvas(Modifier.size(size)) { drawCircle(color) }
}
