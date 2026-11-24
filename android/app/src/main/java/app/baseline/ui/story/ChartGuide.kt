package app.baseline.ui.story

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.baseline.Model
import app.baseline.insight.InsightEngine
import app.baseline.insight.Stats
import app.baseline.ui.charts.ChartZone
import app.baseline.ui.charts.KeyKind
import app.baseline.ui.charts.LegendKey
import app.baseline.ui.charts.LegendSwatch
import app.baseline.ui.common.Fmt
import app.baseline.ui.theme.HealthColors
import java.time.LocalDate
import kotlin.math.roundToInt

/** Named score zones for metrics that have fixed meaning bands. Empty when the metric is personal-only. */
fun zonesFor(metric: Metric, h: HealthColors, m: Model? = null): List<ChartZone> = when (metric) {
    // score = 70 + 15·z; verbs switch at z = −1.5, −0.5, +0.5.
    Metric.Readiness -> if (m != null && m.insights.readiness.score == null) emptyList() else listOf(
        ChartZone(0.0, 47.5, "Recover", h.low), ChartZone(47.5, 62.5, "Easy", h.fair),
        ChartZone(62.5, 77.5, "Ready", h.good), ChartZone(77.5, 100.0, "Push", h.great),
    )
    Metric.Consistency -> listOf(
        ChartZone(0.0, 72.0, "Irregular", h.fair), ChartZone(72.0, 83.0, "Fairly regular", h.good), ChartZone(83.0, 100.0, "Regular", h.great),
    )
    Metric.Stress -> listOf(
        ChartZone(0.0, 25.0, "Relaxed", h.great), ChartZone(25.0, 40.0, "Normal", h.good),
        ChartZone(40.0, 60.0, "Elevated", h.fair), ChartZone(60.0, 100.0, "High", h.low),
    )
    else -> emptyList()
}

/** Which zone a value falls in, by label. */
fun List<ChartZone>.labelFor(v: Double): String? = firstOrNull { v >= it.from && v < it.to }?.label ?: lastOrNull()?.takeIf { v >= it.to }?.label

/** Everything a metric's trend chart needs, in one place, so the chart, legend, tooltip and guide agree. */
data class ChartSetup(
    val values: List<Double?>,
    val dates: List<LocalDate>,
    val trimmed: Boolean,
    val average: List<Double?>?,
    val averageLabel: String?,
    val band: ClosedFloatingPointRange<Double>?,
    val bandLabel: String?,
    val goal: Double?,
    val goalLabel: String?,
    val zones: List<ChartZone>,
    val note: String? = null,
    /** Bars grouped by week (long ranges): each value is that week's daily average, dated by its first day. */
    val weekly: Boolean = false,
    /** Where the period is heading (first → last point of the trend line), when it can be told. */
    val trend: TrendInfo? = null,
    // ---- Bevel style
    /** Your normal at each point (trailing mean ± SD over [normalWindow] days). */
    val rolling: List<ClosedFloatingPointRange<Double>?>? = null,
    /** −1 below your normal, 0 inside, +1 above, per point. */
    val position: List<Int>? = null,
    /** Days averaged into each point (1, 3, 6 or 30). */
    val bucketDays: Int = 1,
    /** Last day of each point's bucket. */
    val ends: List<LocalDate>? = null,
    /** Average of the visible period. */
    val avg: Double? = null,
    /** Your normal as of today. */
    val normalNow: ClosedFloatingPointRange<Double>? = null,
    val normalWindow: Int = 28,
    /** Trends analysis (7/14/30/90 days). */
    val rows: List<TrendRow> = emptyList(),
) {
    val count get() = values.count { it != null }
}

/** Direction of travel over the visible period. [good] is null for metrics where neither direction is better. */
data class TrendInfo(val from: Double, val to: Double, val good: Boolean?, val steady: Boolean) {
    val delta: Double get() = to - from
    val pct: Double? get() = if (kotlin.math.abs(from) > 1e-9) delta / kotlin.math.abs(from) * 100 else null
}

/** Centered moving average over [window] days (nulls skipped), with gaps inside the series bridged. */
fun smooth(xs: List<Double?>, window: Int): List<Double?> {
    val half = window / 2
    val need = maxOf(2, window / 3)
    val out = xs.indices.map { i ->
        xs.subList(maxOf(0, i - half), minOf(xs.size, i + half + 1)).filterNotNull().takeIf { it.size >= need }?.average()
    }
    return interpolate(out)
}

/** Trend direction from a line: first and last points, "steady" under a 2 % change. */
fun trendOf(line: List<Double?>, higherIsBetter: Boolean?): TrendInfo? {
    val pts = line.filterNotNull()
    if (pts.size < 2) return null
    val from = pts.first(); val to = pts.last()
    val pct = if (kotlin.math.abs(from) > 1e-9) kotlin.math.abs(to - from) / kotlin.math.abs(from) * 100 else 0.0
    val steady = pct < 2
    return TrendInfo(from, to, if (steady) null else higherIsBetter?.let { (to > from) == it }, steady)
}

/** Metrics whose daily value is already an average or rolling index, so a second average line would mislead. */
private val alreadySmoothed = setOf(Metric.Consistency, Metric.HrvTrend, Metric.SleepNeed)

/** How a value reads in words and pills: durations as "7h 24m", clock times as "5:30 AM", else the axis format. */
fun displayFormat(metric: Metric, sp: Spec): (Double) -> String = when (metric) {
    Metric.Sleep, Metric.SleepNeed -> { v -> InsightEngine.fmtDuration((v * 60).roundToInt().coerceAtLeast(0)) }
    else -> sp.format
}

/** Metrics drawn the Bevel way (moving normal band, hollow dots, bucketed long ranges, trend table). */
private val bevelStyle = Metric.entries.toSet() - setOf(Metric.Weight, Metric.Signals, Metric.Load, Metric.Zones)

/** Days per point: 1 up to a month, then 3-day (3M), 6-day (6M) and monthly (1Y) averages, like Bevel. */
fun bucketDaysFor(days: Int) = when { days >= 365 -> 30; days >= 180 -> 6; days >= 90 -> 3; else -> 1 }

/** How far back "your normal" looks at each point. */
fun normalWindowFor(days: Int) = when { days >= 365 -> 90; days >= 180 -> 42; else -> 28 }

/** Trailing mean ± 1 SD over [window] days (≥ 7 readings), per day. */
fun rollingNormal(daily: List<Double?>, window: Int): List<ClosedFloatingPointRange<Double>?> = daily.indices.map { i ->
    val w = daily.subList(maxOf(0, i - window + 1), i + 1).filterNotNull()
    if (w.size < 7) null else {
        val mean = w.average()
        val sd = kotlin.math.sqrt(w.sumOf { (it - mean) * (it - mean) } / w.size)
        (mean - sd)..(mean + sd)
    }
}

/** One row of the trend table: your recent average against the same span [days] earlier. */
data class TrendRow(val days: Int, val delta: Double, val significant: Boolean, val good: Boolean?)

/**
 * Bevel's "Trends Analysis": how your recent average moved over 7/14/30/90 days. Each row compares the last
 * min(k, 30) days with the same span ending k days earlier, so long rows aren't decided by one noisy week. A change
 * counts as clear only beyond ~90 % confidence (1.65·SD·√(2/w)); otherwise it's shown grey.
 */
fun trendRows(daily: List<Double?>, higherIsBetter: Boolean?): List<TrendRow> {
    fun mean(xs: List<Double?>, need: Int) = xs.filterNotNull().takeIf { it.size >= need }?.average()
    val n = daily.size
    val recent = daily.takeLast(120).filterNotNull()
    if (recent.size < 10) return emptyList()
    val m = recent.average()
    val sd = kotlin.math.sqrt(recent.sumOf { (it - m) * (it - m) } / recent.size)
    return listOf(7, 14, 30, 90).mapNotNull { k ->
        val w = minOf(k, 30)
        if (n < k + w) return@mapNotNull null
        val need = maxOf(3, w / 2)
        val cur = mean(daily.takeLast(w), need) ?: return@mapNotNull null
        val prev = mean(daily.subList(n - k - w, n - k), need) ?: return@mapNotNull null
        val d = cur - prev
        val sig = sd > 0 && kotlin.math.abs(d) >= 1.65 * sd * kotlin.math.sqrt(2.0 / w)
        TrendRow(k, d, sig, if (sig) higherIsBetter?.let { (d > 0) == it } else null)
    }
}

fun chartSetup(m: Model, metric: Metric, days: Int, sp: Spec, h: HealthColors): ChartSetup {
    if (metric !in bevelStyle) return classicSetup(m, metric, days, sp, h)
    val window = normalWindowFor(days)
    val full = values(m, metric, days + window)
    val normalFull = rollingNormal(full, window)
    val raw = full.takeLast(days)
    val normalDaily = normalFull.takeLast(days)
    val allDates = m.engine.dates(days)
    // Start at the first reading (keeping at least a week) so a short history isn't squeezed into a corner.
    val from = raw.indexOfFirst { it != null }.let { if (it < 0) 0 else minOf(it, maxOf(0, raw.size - 7)) }
    val k = bucketDaysFor(days)
    val idx = raw.indices.drop(from)
    // Buckets end today, so the newest point is always a full bucket.
    val chunks = idx.reversed().chunked(k).reversed().map { it.reversed() }
    val vals = chunks.map { c -> c.mapNotNull { raw[it] }.takeIf { it.isNotEmpty() }?.average() }
    val dates = chunks.map { allDates[it.first()] }
    val ends = chunks.map { allDates[it.last()] }
    val rolling = chunks.map { normalDaily[it.last()] }
    val position = vals.mapIndexed { i, v -> val b = rolling[i]; when { v == null || b == null -> 0; v > b.endInclusive -> 1; v < b.start -> -1; else -> 0 } }
    val visible = raw.drop(from).filterNotNull()
    val stepGoal = m.settings.stepGoal.toDouble()
    val (goal, goalLabel) = when (metric) {
        Metric.Steps -> stepGoal to "Goal ${sp.format(stepGoal)}"
        Metric.Sleep -> m.settings.sleepNeedMin / 60.0 to "Need ${InsightEngine.fmtDuration(m.settings.sleepNeedMin)}"
        else -> null to null
    }
    val note = when {
        metric == Metric.Readiness && m.insights.readiness.score == null ->
            "These are Zepp's scores. Baseline's own score (and the Recover–Push zones) starts after 14 nights with HRV."
        rolling.all { it == null } && vals.count { it != null } > 0 -> "Your normal range appears after a week of readings."
        else -> null
    }
    return ChartSetup(
        values = vals, dates = dates, trimmed = from > 0,
        average = null, averageLabel = null,
        band = null, bandLabel = null,
        goal = goal, goalLabel = goalLabel,
        zones = zonesFor(metric, h, m),
        note = note,
        weekly = false,
        rolling = rolling, position = position, bucketDays = k, ends = ends,
        avg = visible.takeIf { it.isNotEmpty() }?.average(),
        normalNow = normalDaily.lastOrNull { it != null },
        normalWindow = window,
        rows = trendRows(values(m, metric, 120), sp.higherIsBetter),
    )
}

private fun classicSetup(m: Model, metric: Metric, days: Int, sp: Spec, h: HealthColors): ChartSetup {
    val raw = values(m, metric, days)
    // Start at the first reading (keeping at least a week) so a short history isn't squeezed into a corner.
    val from = raw.indexOfFirst { it != null }.let { if (it < 0) 0 else minOf(it, maxOf(0, raw.size - 7)) }
    var vals = raw.drop(from)
    var dates = m.engine.dates(days).drop(from)
    // Hundreds of daily bars read as a barcode: group long bar ranges into weeks (ending today).
    val weekly = sp.bars && vals.size > 45
    if (weekly) {
        val chunks = vals.indices.reversed().chunked(7).reversed().map { it.reversed() }
        dates = chunks.map { dates[it.first()] }
        vals = chunks.map { idx -> idx.mapNotNull { vals[it] }.takeIf { it.isNotEmpty() }?.average() }
    }
    val avg: List<Double?>? = when {
        metric == Metric.Weight -> interpolate(Stats.timeEwma(vals, WEIGHT_TAU_DAYS).mapIndexed { i, t -> t.takeIf { vals[i] != null } })
        // Weekly bars over months: a 4-week trend line on top shows where they're heading.
        weekly -> if (vals.size >= 8) smooth(vals, 5) else null
        sp.bars || metric in alreadySmoothed -> null
        // Over a month or less every day is readable on its own; a trend line only adds clutter.
        vals.size <= 31 -> null
        // Longer: a smooth trend (Bevel-style) so the direction is obvious, wider the longer the range.
        vals.size <= 120 -> smooth(vals, 15)
        vals.size <= 200 -> smooth(vals, 21)
        else -> smooth(vals, 31)
    }?.takeIf { a -> a.count { it != null } >= 2 }
    val adv = m.insights.advanced ?: m.engine.advanced.insights
    val band = when (metric) {
        Metric.Hrv -> m.insights.hrv.baseline?.let { it.low..it.high }
        Metric.RestingHr -> m.insights.rhr.baseline?.let { it.low..it.high }
        Metric.Breathing -> m.insights.resp.baseline?.let { it.low..it.high }
        Metric.HrvTrend -> adv.hrvTrend.let { t -> if (t.bandLowMs != null && t.bandHighMs != null) t.bandLowMs..t.bandHighMs else null }
        else -> null
    }
    val stepGoal = m.settings.stepGoal.toDouble()
    val (goal, goalLabel) = when (metric) {
        Metric.Steps -> stepGoal to "Goal ${sp.format(stepGoal)}"
        Metric.Sleep -> m.settings.sleepNeedMin / 60.0 to "Need ${InsightEngine.fmtDuration(m.settings.sleepNeedMin)}"
        Metric.Active -> m.settings.weeklyActiveGoal / 7.0 to "${(m.settings.weeklyActiveGoal / 7.0).roundToInt()} min/day"
        Metric.Signals -> 2.0 to "2+ = off"
        else -> null to null
    }
    val note = when {
        metric == Metric.Readiness && m.insights.readiness.score == null ->
            "These are Zepp's scores. Baseline's own score (and the Recover–Push zones) starts after 14 nights with HRV."
        metric == Metric.Weight && vals.count { it != null } in 1..2 -> "A trend line appears after a few weigh-ins."
        else -> null
    }
    return ChartSetup(
        values = vals, dates = dates, trimmed = from > 0,
        average = avg,
        averageLabel = if (avg == null) null else "Trend",
        band = band, bandLabel = band?.let { "Your normal" },
        goal = goal, goalLabel = goalLabel,
        zones = zonesFor(metric, h, m),
        note = note,
        weekly = weekly,
        trend = if ((if (weekly) vals.size * 7 else vals.size) > 31 || metric == Metric.Weight) trendOf(avg ?: vals, sp.higherIsBetter) else null,
    )
}

/** Straight lines between known points (nulls inside the series filled; leading/trailing nulls kept). */
fun interpolate(xs: List<Double?>): List<Double?> {
    val known = xs.indices.filter { xs[it] != null }
    if (known.size < 2) return xs
    val out = xs.toMutableList()
    known.zipWithNext().forEach { (a, b) ->
        for (i in a + 1 until b) out[i] = xs[a]!! + (xs[b]!! - xs[a]!!) * (i - a) / (b - a)
    }
    return out
}

/** Smoothing time constant for the weight trend: a reading 10 days after the last one moves the line ~63%. */
const val WEIGHT_TAU_DAYS = 10.0

/** The legend under a chart: only what's actually drawn. */
fun ChartSetup.legend(metric: Metric, sp: Spec): List<LegendKey> = buildList {
    val c = sp.color
    if (rolling != null) {
        add(LegendKey(KeyKind.Dot, c, when (bucketDays) { 1 -> dailyName(metric); 30 -> "Monthly average"; else -> "$bucketDays-day average" }))
        if (rolling.any { it != null }) add(LegendKey(KeyKind.Band, c, "Your normal (last ${if (normalWindow >= 60) "${normalWindow / 30} months" else "${normalWindow / 7} weeks"})"))
        if (avg != null) add(LegendKey(KeyKind.Dash, c, "Average"))
        goalLabel?.let { add(LegendKey(KeyKind.Dash, c, it)) }
        return@buildList
    }
    if (sp.bars) add(LegendKey(KeyKind.Bar, c, if (weekly) "Average per day, by week" else dailyName(metric)))
    else add(LegendKey(if (average != null) KeyKind.Dot else KeyKind.Line, c, dailyName(metric)))
    averageLabel?.let { add(LegendKey(KeyKind.Line, c, it)) }
    bandLabel?.let { add(LegendKey(KeyKind.Band, c, it)) }
    goalLabel?.let { add(LegendKey(KeyKind.Dash, c, it)) }
}

private fun dailyName(metric: Metric) = when (metric) {
    Metric.Consistency -> "14-day score"
    Metric.HrvTrend -> "7-night average"
    Metric.SleepNeed -> "7-night shortfall"
    Metric.SleepTiming -> "Sleep midpoint"
    Metric.Weight -> "Weigh-in"
    Metric.Signals -> "Signals off"
    Metric.Sleep -> "Asleep"
    Metric.Readiness -> "Score"
    else -> "Daily value"
}

/** Text for the inspect tooltip: date, value, zone or band position, and average. */
fun ChartSetup.tooltip(i: Int, sp: Spec, metric: Metric = Metric.Steps): String {
    val d = dates.getOrNull(i) ?: return ""
    val v = values.getOrNull(i)
    if (rolling != null) {
        val e = ends?.getOrNull(i) ?: d
        val head = if (bucketDays == 1) "${Fmt.weekdayShort(d)} ${Fmt.dayMonth(d)}" else "${Fmt.dayMonth(d)} – ${Fmt.dayMonth(e)}"
        if (v == null) return "$head\nNo reading"
        val unit = if (sp.unit.isBlank() || sp.unit == "off") "" else " ${sp.unit}"
        val where = when (position?.getOrNull(i)) { 1 -> "above your normal"; -1 -> "below your normal"; else -> if (rolling[i] != null) "in your normal" else null }
        val fmt = displayFormat(metric, sp)
        val u = if (metric == Metric.Sleep || metric == Metric.SleepNeed) "" else unit
        return listOfNotNull(head, listOfNotNull("${fmt(v)}$u${if (bucketDays > 1) " avg" else ""}", where).joinToString(" · ")).joinToString("\n")
    }
    val head = if (weekly) "Week of ${Fmt.dayMonth(d)}" else "${Fmt.weekdayShort(d)} ${Fmt.dayMonth(d)}"
    if (v == null) return "$head\nNo reading"
    val unit = if (sp.unit.isBlank() || sp.unit == "off") "" else " ${sp.unit}"
    val where = zones.takeIf { it.isNotEmpty() }?.labelFor(v)
        ?: band?.let { b -> when { v > b.endInclusive -> "above your normal"; v < b.start -> "below your normal"; else -> "in your normal range" } }
        ?: goal?.let { g -> if (v >= g) "goal met" else null }
    val avg = average?.getOrNull(i)?.let { "${averageLabel ?: "Average"} ${sp.format(it)}" }
    return listOfNotNull(head, listOfNotNull("${sp.format(v)}$unit${if (weekly) " a day" else ""}", where?.takeIf { !weekly || it != "goal met" } ?: if (weekly && goal != null && v >= goal) "goal met on average" else null).joinToString(" · "), avg).joinToString("\n")
}

/** What the number itself means, in plain words. */
fun meaningOf(metric: Metric): String = when (metric) {
    Metric.Readiness -> "A 1–100 score for how recovered you are compared with your own normal. 70 is a typical day for you, and every 15 points is one standard deviation from your usual. 78 and up means Push, 63–77 Ready, 48–62 Easy, and under 48 Recover."
    Metric.Hrv -> "Heart-rate variability is how much the gap between heartbeats varies while you sleep, in milliseconds. Higher usually means better recovered. Compare it only with your own range: 30 ms can be great for one person and low for another."
    Metric.RestingHr -> "Your lowest steady heart rate in beats per minute, mostly measured while you sleep. Lower over months means fitter. A few beats above your usual for a day or two points to strain, short sleep, alcohol or an oncoming bug."
    Metric.Breathing -> "Breaths per minute while you're asleep. It's very stable for most people, usually 12–20. A rise of one breath or more above your normal is one of the earliest signs your body is fighting something."
    Metric.Sleep -> "Hours actually asleep, which is time in bed minus time awake. The dashed line is the sleep need set in Settings."
    Metric.Consistency -> "The Sleep Regularity Index (SRI). For every minute of the day, Baseline checks whether you were in the same state (asleep or awake) at that minute the day before, over the last 14 days. 100 means every minute matches, an identical schedule. 0 means only half match, which is what a random schedule gives. Each 10 points is about 1.2 hours a day spent differently from the day before. 83 and up is regular, 72–83 fairly regular, and under 72 irregular."
    Metric.Steps -> "Steps per day counted by your watch. The dashed line is your daily goal."
    Metric.Active -> "Minutes per day of at least moderate effort, like brisk walking or harder. Health guidelines suggest 150 a week; the dashed line spreads that over 7 days."
    Metric.Vo2 -> "VO₂max estimates the most oxygen your body can use per minute for each kg of body weight (ml/kg/min). It's the best single measure of cardio fitness. Watches estimate it from heart rate against pace on outdoor runs and walks, so read it as roughly ±3–5."
    Metric.Load -> "How much your workouts stressed your body each day, worked out from heart-rate time in zones. Bars are daily totals."
    Metric.Stress -> "Zepp's stress score from 0 to 100, worked out from your heart rhythm through the day. Under 25 is relaxed, 25–39 normal, 40–59 elevated and 60 or more high. It also reacts to exercise, caffeine and excitement, not only to mental stress."
    Metric.Spo2 -> "Zepp's overnight blood-oxygen score: how steady your oxygen stayed while asleep. Brief dips are normal; frequent ones are worth watching."
    Metric.Weight -> "Your weigh-ins (dots) and a smoothed trend line that filters out day-to-day water swings. Follow the line, not single readings. The line follows real time, so after a long gap a new weigh-in pulls it most of the way."
    Metric.Signals -> "How many of four overnight signals (resting HR, breathing rate, HRV and skin temperature) were outside your usual range each night. Two or more for two nights in a row raises a 'signals off' note."
    Metric.HrvTrend -> "Your HRV averaged over the last 7 nights. Averaging removes night-to-night noise so the real direction shows. The shaded band is your normal range."
    Metric.HrDip -> "The lowest heart rate of each night. Lower, and earlier in the night, usually means better recovery. A late or shallow low often follows alcohol, a late meal or a hard evening workout."
    Metric.SleepNeed -> "How far your sleep fell short of your need over the last 7 nights, in hours. 0 means you're keeping up."
    Metric.SleepTiming -> "The midpoint of your sleep, halfway between falling asleep and waking up. It's the steadiest marker of your body clock. Your free-day midpoint minus your work-day midpoint is your 'social jet lag'."
    Metric.Zones -> "Minutes each day spent in heart-rate zones during workouts."
    Metric.Movement -> "Active minutes per day. Today's suggested range comes from your readiness and your recent training load."
}

private fun guideText(k: LegendKey): String = when (k.kind) {
    KeyKind.Dot -> "Each dot is one day's reading. Single days are noisy, so don't read much into one dot."
    KeyKind.Line -> if (k.label.contains("average", true) || k.label == "Trend") "The trend line smooths out day-to-day noise so you can see where you're heading. It takes your accent colour when the change is good for you and the second colour when it isn't." else "The line joins each day's value. Gaps mean no reading that day."
    KeyKind.Band -> "Shaded: your personal normal range, worked out from your own previous weeks. Inside it is a typical day for you."
    KeyKind.Dash -> "Dashed: a target (${k.label}). Bars that reach it are drawn solid."
    KeyKind.Bar -> "Each bar is one day."
}

/** "How to read this chart": what the number means, a picture of its scale, and what each mark is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartGuideSheet(m: Model, metric: Metric, sp: Spec, setup: ChartSetup, current: Double?, onDismiss: () -> Unit) {
    val h = app.baseline.ui.theme.Brand.health
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text("How to read ${metric.label.lowercase().let { if (metric == Metric.Hrv || metric == Metric.HrvTrend || metric == Metric.Vo2) metric.label else it }}", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            GuideHeading("What the number means")
            Text(meaningOf(metric), style = MaterialTheme.typography.bodyLarge)

            val zones = zonesFor(metric, h)
            when {
                metric == Metric.Consistency -> {
                    Spacer(Modifier.height(16.dp)); RegularityPicture(sp.color)
                    Spacer(Modifier.height(16.dp)); ScalePicture(zones, current, sp.format)
                }
                metric == Metric.Readiness -> {
                    Spacer(Modifier.height(16.dp)); MixPicture(listOf(.5f to "HRV 50%", .3f to "Resting HR 30%", .2f to "Sleep 20%"), listOf(h.hrv, h.heart, h.sleep))
                    Spacer(Modifier.height(16.dp)); ScalePicture(zonesFor(metric, h), current, sp.format)
                }
                zones.isNotEmpty() -> { Spacer(Modifier.height(16.dp)); ScalePicture(zones, current, sp.format) }
                setup.band != null -> { Spacer(Modifier.height(16.dp)); BandPicture(sp.color, cs.onSurfaceVariant) }
                metric == Metric.SleepTiming -> { Spacer(Modifier.height(16.dp)); MidpointPicture(sp.color, cs.onSurfaceVariant) }
                metric == Metric.HrDip -> { Spacer(Modifier.height(16.dp)); DipPicture(sp.color, cs.onSurfaceVariant) }
            }

            Spacer(Modifier.height(24.dp))
            GuideHeading("On the chart")
            setup.legend(metric, sp).forEach { k ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    LegendSwatch(k.kind, k.color, Modifier.padding(top = 4.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(k.label, style = MaterialTheme.typography.titleSmall)
                        Text(guideText(k), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
            }
            if (setup.zones.isNotEmpty()) Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                LegendSwatch(KeyKind.Band, setup.zones.last().color, Modifier.padding(top = 4.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Coloured zones", style = MaterialTheme.typography.titleSmall)
                    Text("Faint stripes behind the line name each part of the scale (${setup.zones.joinToString(", ") { it.label }}).", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
            setup.note?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh) {
                Text(
                    "Tip: tap any day, or drag across the chart, to see its exact value.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            GuideHeading("How it's measured")
            Text(storyOf(metric).measured, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Text(storyOf(metric).reliability.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun GuideHeading(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp), color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

/** A segmented scale with each zone named and a marker for today's value. */
@Composable
fun ScalePicture(zones: List<ChartZone>, value: Double?, format: (Double) -> String, modifier: Modifier = Modifier) {
    if (zones.isEmpty()) return
    Column(modifier) {
        ScaleBar(zones, value, format)
        Spacer(Modifier.height(4.dp))
        zones.forEachIndexed { i, z ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                LegendSwatch(KeyKind.Band, z.color)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${z.label}: " + when (i) { 0 -> "under ${format(z.to)}"; zones.lastIndex -> "${format(z.from)} and up"; else -> "${format(z.from)}–${format(z.to)}" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScaleBar(zones: List<ChartZone>, value: Double?, format: (Double) -> String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val cs = MaterialTheme.colorScheme
    val fg = cs.onSurface; val sub = cs.onSurfaceVariant
    Canvas(modifier.fillMaxWidth().height(86.dp)) {
        val lo = zones.first().from; val hi = zones.last().to
        val barY = 30.dp.toPx(); val barH = 14.dp.toPx(); val gap = 3.dp.toPx()
        fun x(v: Double) = ((v - lo) / (hi - lo) * size.width).toFloat()
        zones.forEach { z ->
            val a = x(z.from) + gap / 2; val b = x(z.to) - gap / 2
            drawRoundRect(z.color.copy(alpha = .85f), Offset(a, barY), Size((b - a).coerceAtLeast(2f), barH), CornerRadius(barH / 2))
            val name = measurer.measure(z.label, TextStyle(fontSize = 11.sp, color = sub))
            val cx = (a + b) / 2
            if (name.size.width < b - a + 10) drawText(name, topLeft = Offset((cx - name.size.width / 2).coerceIn(0f, size.width - name.size.width), barY + barH + 6.dp.toPx()))
            if (z != zones.last()) {
                val edge = measurer.measure(format(z.to), TextStyle(fontSize = 10.sp, color = sub))
                drawText(edge, topLeft = Offset(x(z.to) - edge.size.width / 2, barY + barH + 22.dp.toPx()))
            }
        }
        value?.let { v ->
            val vx = x(v.coerceIn(lo, hi)).coerceIn(4f, size.width - 4f)
            val tri = Path().apply { moveTo(vx, barY - 2.dp.toPx()); lineTo(vx - 6.dp.toPx(), barY - 10.dp.toPx()); lineTo(vx + 6.dp.toPx(), barY - 10.dp.toPx()); close() }
            drawPath(tri, fg)
            val t = measurer.measure("You · ${format(v)}", TextStyle(fontSize = 12.sp, color = fg, fontWeight = FontWeight.SemiBold))
            drawText(t, topLeft = Offset((vx - t.size.width / 2).coerceIn(0f, size.width - t.size.width), barY - 12.dp.toPx() - t.size.height))
        }
    }
}

/** Two days side by side: minutes where you were in the same state count toward regularity. */
@Composable
private fun RegularityPicture(color: Color) {
    val measurer = rememberTextMeasurer()
    val cs = MaterialTheme.colorScheme
    val sub = cs.onSurfaceVariant; val miss = app.baseline.ui.theme.Brand.health.fair
    Column {
        Canvas(Modifier.fillMaxWidth().height(118.dp)) {
            val left = 52.dp.toPx(); val w = size.width - left
            // Clock from 9 PM to 1 PM (16 h).
            fun x(h: Float) = left + (h - 21f) / 16f * w
            val rowH = 16.dp.toPx()
            val rows = listOf(28.dp.toPx() to (24f + 1f to 24f + 8f), 62.dp.toPx() to (24f + 3f to 24f + 10f))
            val style = TextStyle(fontSize = 11.sp, color = sub)
            rows.forEachIndexed { i, (y, span) ->
                drawText(measurer, if (i == 0) "Monday" else "Tuesday", topLeft = Offset(0f, y), style = style)
                drawRoundRect(cs.surfaceContainerHighest, Offset(left, y), Size(w, rowH), CornerRadius(rowH / 2))
                drawRoundRect(color, Offset(x(span.first), y), Size(x(span.second) - x(span.first), rowH), CornerRadius(rowH / 2))
            }
            // Mismatch windows: 1–3 AM and 8–10 AM.
            listOf(25f to 27f, 32f to 34f).forEach { (a, b) ->
                drawRect(miss.copy(alpha = .22f), Offset(x(a), 22.dp.toPx()), Size(x(b) - x(a), 62.dp.toPx()))
            }
            listOf(21f to "9 PM", 25f to "1 AM", 29f to "5 AM", 33f to "9 AM", 37f to "1 PM").forEach { (hh, t) ->
                val tm = measurer.measure(t, style)
                drawText(tm, topLeft = Offset((x(hh) - tm.size.width / 2).coerceIn(left, size.width - tm.size.width), size.height - tm.size.height))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendSwatch(KeyKind.Band, miss)
            Spacer(Modifier.width(8.dp))
            Text(
                "Asleep 1–8 AM, then 3–10 AM. The 4 hours that don't match (1–3 AM and 8–10 AM) bring this pair of days down to about 67; matching all 24 hours would score 100.",
                style = MaterialTheme.typography.bodySmall, color = sub,
            )
        }
    }
}

/** A stacked bar showing how a score is weighted. */
@Composable
private fun MixPicture(parts: List<Pair<Float, String>>, colors: List<Color>) {
    val measurer = rememberTextMeasurer()
    val fg = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(48.dp)) {
        var x = 0f; val gap = 3.dp.toPx(); val barH = 18.dp.toPx()
        parts.forEachIndexed { i, (f, label) ->
            val w = size.width * f - gap
            drawRoundRect(colors[i], Offset(x, 0f), Size(w, barH), CornerRadius(barH / 2))
            val tm = measurer.measure(label, TextStyle(fontSize = 11.sp, color = fg))
            drawText(tm, topLeft = Offset(x + (w - tm.size.width).coerceAtLeast(0f) / 2, barH + 8.dp.toPx()))
            x += w + gap
        }
    }
}

/** Dots wandering inside a shaded band, one outside it. */
@Composable
private fun BandPicture(color: Color, sub: Color) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val bandTop = 30.dp.toPx(); val bandBot = 70.dp.toPx()
        drawRoundRect(color.copy(alpha = .14f), Offset(0f, bandTop), Size(size.width, bandBot - bandTop), CornerRadius(8.dp.toPx()))
        val ys = listOf(.55f, .4f, .62f, .48f, .35f, .58f, .5f, -.35f, .45f)
        ys.forEachIndexed { i, f ->
            val cx = 16.dp.toPx() + i * (size.width - 32.dp.toPx()) / (ys.size - 1)
            val cy = bandTop + f * (bandBot - bandTop)
            val out = f < 0
            drawCircle(if (out) color else color.copy(alpha = .6f), (if (out) 6 else 4).dp.toPx(), Offset(cx, cy))
            if (out) {
                val tm = measurer.measure("Outside: unusual for you", TextStyle(fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold))
                drawText(tm, topLeft = Offset((cx - tm.size.width / 2).coerceIn(0f, size.width - tm.size.width), (cy - 22.dp.toPx()).coerceAtLeast(0f)))
            }
        }
        val tm = measurer.measure("Inside the band: a normal day for you", TextStyle(fontSize = 11.sp, color = sub))
        drawText(tm, topLeft = Offset(0f, size.height - tm.size.height))
    }
}

/** A night as a bar with its midpoint marked, and a later weekend night under it. */
@Composable
private fun MidpointPicture(color: Color, sub: Color) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(96.dp)) {
        val left = 60.dp.toPx(); val w = size.width - left
        fun x(h: Float) = left + (h - 22f) / 14f * w
        val rowH = 14.dp.toPx()
        val style = TextStyle(fontSize = 11.sp, color = sub)
        listOf(Triple(10.dp.toPx(), "Weekday", 24f + 0f to 24f + 7f), Triple(44.dp.toPx(), "Weekend", 24f + 2f to 24f + 10f)).forEach { (y, name, span) ->
            drawText(measurer, name, topLeft = Offset(0f, y), style = style)
            drawRoundRect(color.copy(alpha = .35f), Offset(x(span.first), y), Size(x(span.second) - x(span.first), rowH), CornerRadius(rowH / 2))
            val mid = (span.first + span.second) / 2
            drawLine(color, Offset(x(mid), y - 4.dp.toPx()), Offset(x(mid), y + rowH + 4.dp.toPx()), 3.dp.toPx(), cap = StrokeCap.Round)
        }
        drawLine(sub, Offset(x(27.5f), 34.dp.toPx()), Offset(x(30f), 34.dp.toPx()), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
        val tm = measurer.measure("Midpoints 2½ h apart = 2½ h social jet lag", style)
        drawText(tm, topLeft = Offset(0f, size.height - tm.size.height))
    }
}

/** Overnight heart rate: an early, deep low versus a late, shallow one. */
@Composable
private fun DipPicture(color: Color, sub: Color) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val h = size.height - 22.dp.toPx()
        fun curve(lowAt: Float, depth: Float) = Path().apply {
            moveTo(0f, h * .2f)
            cubicTo(size.width * lowAt * .5f, h * .2f, size.width * lowAt * .7f, h * depth, size.width * lowAt, h * depth)
            cubicTo(size.width * (lowAt + (1 - lowAt) * .4f), h * depth, size.width * .9f, h * .35f, size.width, h * .25f)
        }
        drawPath(curve(.35f, .9f), color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        drawPath(curve(.8f, .6f), sub.copy(alpha = .6f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        drawText(measurer, "Early, deep low: well recovered", topLeft = Offset(size.width * .05f, h * .9f - 4.dp.toPx()), style = TextStyle(fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold))
        drawText(measurer, "Late, shallow (dashed): alcohol or a late meal?", topLeft = Offset(0f, size.height - 16.dp.toPx()), style = TextStyle(fontSize = 11.sp, color = sub))
    }
}

/** Summary of the visible period: average, low, high and coverage. */
data class PeriodStats(val avg: Double?, val low: Double?, val high: Double?, val withData: Int, val total: Int)

fun ChartSetup.stats(): PeriodStats {
    val vs = values.filterNotNull()
    return PeriodStats(vs.takeIf { it.isNotEmpty() }?.average(), vs.minOrNull(), vs.maxOrNull(), vs.size, values.size)
}
