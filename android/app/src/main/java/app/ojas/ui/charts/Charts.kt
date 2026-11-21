package app.ojas.ui.charts

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
import kotlin.math.max
import kotlin.math.min

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
                rotate(135f) {
                    drawArc(
                        Brush.sweepGradient(
                            *(colors.mapIndexed { i, c -> (i / (colors.size - 1f)) * 0.75f to c } + (0.88f to colors.last()) + (1f to colors.first())).toTypedArray(),
                            center = Offset(size.width / 2, size.height / 2),
                        ),
                        0f, 270f * fraction.coerceIn(0f, 1f) * p, false, topLeft, arcSize,
                        style = Stroke(sw, cap = StrokeCap.Round),
                    )
                }
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
                    drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = .28f), color.copy(alpha = 0f))))
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

/**
 * The main trend chart: daily dots + smoothed average line + personal normal band + goal line,
 * with drag-to-inspect. [onSelect] reports the inspected index (null when released).
 */
@Composable
fun TrendChart(
    values: List<Double?>,
    color: Color,
    labels: ChartLabels,
    modifier: Modifier = Modifier,
    average: List<Double?>? = null,
    band: ClosedFloatingPointRange<Double>? = null,
    goal: Double? = null,
    bars: Boolean = false,
    zeroBased: Boolean = bars,
    onSelect: (Int?) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val p = rememberGrow(values.hashCode())
    var sel by remember { mutableStateOf<Int?>(null) }
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)
    val labelStyle = TextStyle(fontSize = 11.sp, color = onSurface)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .pointerInput(values.size) {
                val leftPad = 36.dp.toPx()
                fun idx(x: Float) = (((x - leftPad) / (size.width - leftPad)) * (values.size - 1)).let { kotlin.math.round(it).toInt() }.coerceIn(0, values.size - 1)
                detectHorizontalDragGestures(
                    onDragStart = { sel = idx(it.x); onSelect(sel) },
                    onDragEnd = { sel = null; onSelect(null) },
                    onDragCancel = { sel = null; onSelect(null) },
                ) { change, _ -> sel = idx(change.position.x); onSelect(sel) }
            }
            .pointerInput(values.size) {
                val leftPad = 36.dp.toPx()
                detectTapGestures { o ->
                    val i = (((o.x - leftPad) / (size.width - leftPad)) * (values.size - 1)).let { kotlin.math.round(it).toInt() }.coerceIn(0, values.size - 1)
                    sel = if (sel == i) null else i; onSelect(sel)
                }
            },
    ) {
        val vs = values.filterNotNull() + listOfNotNull(goal) + (average?.filterNotNull() ?: emptyList())
        if (vs.isEmpty()) return@Canvas
        val left = 36.dp.toPx(); val bottom = 22.dp.toPx(); val top = 8.dp.toPx()
        val w = size.width - left; val h = size.height - bottom - top
        var lo = if (zeroBased) 0.0 else vs.min(); var hi = vs.max()
        band?.let { lo = min(lo, it.start); hi = max(hi, it.endInclusive) }
        val span = (hi - lo).coerceAtLeast(1e-6)
        if (!zeroBased) lo -= span * .12
        hi += span * .12
        fun x(i: Int) = left + if (values.size <= 1) w / 2 else i / (values.size - 1f) * w
        fun y(v: Double) = (top + h - ((v - lo) / (hi - lo) * h)).toFloat()

        // grid + y labels (3 lines)
        for (k in 0..2) {
            val v = lo + (hi - lo) * (k / 2.0)
            val yy = y(v)
            drawLine(grid, Offset(left, yy), Offset(size.width, yy), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            drawLabel(measurer, labels.format(v), Offset(0f, yy - 7.sp.toPx()), labelStyle)
        }
        // x labels
        val n = labels.x.size
        if (n > 0) {
            val step = max(1, n / 5)
            for (i in 0 until n step step) {
                val t = labels.x[i]
                val m = measurer.measure(t, labelStyle)
                val xx = (x(i) - m.size.width / 2).coerceIn(left, size.width - m.size.width)
                drawText(m, topLeft = Offset(xx, size.height - m.size.height))
            }
        }
        band?.let {
            drawRoundRect(color.copy(alpha = .12f), Offset(left, y(it.endInclusive)), Size(w, y(it.start) - y(it.endInclusive)), CornerRadius(8f))
        }
        goal?.let {
            drawLine(color.copy(alpha = .7f), Offset(left, y(it)), Offset(size.width, y(it)), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
        }
        if (bars) {
            val bw = (w / values.size * .62f).coerceAtMost(28.dp.toPx())
            values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val yy = y(v * p)
                val hit = goal != null && v >= goal
                val c = if (sel == null || sel == i) color.copy(alpha = if (hit || goal == null) 1f else .55f) else color.copy(alpha = .25f)
                drawRoundRect(c, Offset(x(i) - bw / 2, yy), Size(bw, y(lo) - yy), CornerRadius(bw / 2.5f))
            }
        } else {
            val dot = 2.6.dp.toPx()
            values.forEachIndexed { i, v -> v?.let { drawCircle(color.copy(alpha = if (average != null) .45f else 1f), dot, Offset(x(i), y(it))) } }
            clipRevealRect(left, p) {
                val line = average ?: values
                runs(line).forEach { run ->
                    if (run.size > 1) drawPath(
                        smoothPath(run.map { Offset(x(it.first), y(it.second)) }), color,
                        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
        sel?.let { i ->
            val xx = x(i)
            drawLine(onSurface.copy(alpha = .5f), Offset(xx, top), Offset(xx, top + h), 1.dp.toPx())
            values.getOrNull(i)?.let { v ->
                drawCircle(Color.White, 6.dp.toPx(), Offset(xx, y(v)))
                drawCircle(color, 4.dp.toPx(), Offset(xx, y(v)))
            }
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
        clipReveal(p) {
            var prev: Pair<Float, Float>? = null
            stages.forEach { (a, b, st) ->
                val lane = lanes.indexOf(st).coerceAtLeast(0)
                val c = colors[st] ?: Color.Gray
                val yTop = lane * laneH + (laneH - barH) / 2
                val xa = x(a); val xb = max(x(b), xa + 1.5f)
                prev?.let { (px, py) ->
                    drawLine(c.copy(alpha = .35f), Offset(px, py), Offset(xa, yTop + barH / 2), 1.5f)
                }
                drawRoundRect(
                    Brush.verticalGradient(listOf(c, c.copy(alpha = .75f)), startY = yTop, endY = yTop + barH),
                    Offset(xa, yTop), Size(xb - xa, barH), CornerRadius(barH / 3),
                )
                prev = xb to yTop + barH / 2
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
 * Bed→wake windows for recent nights as vertical capsules on a 6 PM → 2 PM clock, with an optional
 * target bedtime line. windows = (bedMinutesFrom18h, wakeMinutesFrom18h) or null for no data.
 */
@Composable
fun SleepWindows(
    windows: List<Pair<Float, Float>?>,
    dayLabels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    targetBed: Float? = null,
    targetWake: Float? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)
    val p = rememberGrow(windows.hashCode())
    Canvas(modifier.fillMaxWidth().height(200.dp)) {
        val vals = windows.filterNotNull()
        if (vals.isEmpty()) return@Canvas
        val left = 40.dp.toPx(); val bottom = 20.dp.toPx()
        val lo = (vals.minOf { it.first } - 30).coerceAtLeast(0f).let { (it / 60).toInt() * 60f }
        val hi = (vals.maxOf { it.second } + 30).let { ((it / 60).toInt() + 1) * 60f }
        val h = size.height - bottom
        fun y(m: Float) = (m - lo) / (hi - lo) * h
        val style = TextStyle(fontSize = 11.sp, color = labelColor)
        var m = lo
        val step = if (hi - lo > 720) 180f else 120f
        while (m <= hi) {
            drawLine(grid, Offset(left, y(m)), Offset(size.width, y(m)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            val hour = ((18 + (m / 60).toInt()) % 24)
            val t = "%d %s".format(if (hour % 12 == 0) 12 else hour % 12, if (hour < 12) "AM" else "PM")
            drawText(measurer, t, topLeft = Offset(0f, y(m) - 7.sp.toPx()), style = style)
            m += step
        }
        val n = windows.size
        val slot = (size.width - left) / n
        val bw = (slot * .5f).coerceAtMost(18.dp.toPx())
        targetBed?.let { drawLine(color.copy(alpha = .8f), Offset(left, y(it)), Offset(size.width, y(it)), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))) }
        targetWake?.let { drawLine(color.copy(alpha = .4f), Offset(left, y(it)), Offset(size.width, y(it)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f))) }
        windows.forEachIndexed { i, w ->
            val cx = left + slot * i + slot / 2
            if (w != null) {
                val mid = (w.first + w.second) / 2
                val a = mid - (mid - w.first) * p; val b = mid + (w.second - mid) * p
                drawRoundRect(
                    Brush.verticalGradient(listOf(color.copy(alpha = .65f), color), startY = y(a), endY = y(b)),
                    Offset(cx - bw / 2, y(a)), Size(bw, y(b) - y(a)), CornerRadius(bw / 2),
                )
            }
            if (i < dayLabels.size && (n <= 14 || i % 2 == (n - 1) % 2)) {
                val mm = measurer.measure(dayLabels[i], style)
                drawText(mm, topLeft = Offset(cx - mm.size.width / 2, size.height - mm.size.height))
            }
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

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Canvas(Modifier.size(size)) { drawCircle(color) }
}
