package app.ojas.ui.trends

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ojas.Model
import app.ojas.insight.InsightEngine
import app.ojas.insight.Rules
import app.ojas.insight.Stats
import app.ojas.ui.charts.ChartLabels
import app.ojas.ui.charts.TrendChart
import app.ojas.ui.common.Explainer
import app.ojas.ui.common.Fmt
import app.ojas.ui.common.KeyValue
import app.ojas.ui.common.RefreshablePage
import app.ojas.ui.common.ScreenTitle
import app.ojas.ui.common.SectionCard
import app.ojas.ui.theme.HealthColors
import app.ojas.ui.theme.Numeric
import app.ojas.ui.theme.Ojas
import java.time.Instant
import java.time.LocalDate

enum class Metric(val label: String) {
    Hrv("HRV"), RestingHr("Resting HR"), Sleep("Sleep"), Steps("Steps"), Active("Active min"),
    Breathing("Breathing"), Stress("Stress"), Spo2("Night SpO₂"), Vo2("VO₂ max"), Weight("Weight"), Load("Training load"),
}

private data class Spec(
    val unit: String,
    val color: Color,
    val higherIsBetter: Boolean?,
    val bars: Boolean,
    val format: (Double) -> String,
    val why: String,
)

private fun spec(m: Metric, h: HealthColors): Spec = when (m) {
    Metric.Hrv -> Spec("ms", h.hrv, true, false, { "%.0f".format(it) },
        "Heart-rate variability while you sleep. Higher than *your* normal usually means you've recovered; a drop for several days points to stress, illness, alcohol or overreaching. Compare to your band, not to other people — HRV varies hugely between individuals.")
    Metric.RestingHr -> Spec("bpm", h.heart, false, false, { "%.0f".format(it) },
        "Lowest stable heart rate of the day. A long-term decline means improving fitness. A rise of a few beats over your normal is one of the earliest signs of illness, poor sleep or accumulated fatigue. Lower resting HR is linked to lower all-cause mortality.")
    Metric.Sleep -> Spec("h", h.sleep, true, true, { "%.1f".format(it) },
        "Time actually asleep. Adults need 7–9 hours; both short sleep and irregular timing are linked to worse cardiometabolic health. Duration and consistency matter far more than stage percentages, which wrist trackers estimate poorly.")
    Metric.Steps -> Spec("", h.steps, true, true, { if (it >= 1000) "%.0fk".format(it / 1000) else "%.0f".format(it) },
        "Daily steps. Mortality risk keeps falling up to roughly 8–10k steps a day for adults under 60 (about 6–8k over 60), then plateaus. The dashed line is your goal.")
    Metric.Active -> Spec("min", h.great, true, true, { "%.0f".format(it) },
        "Moderate minutes plus double-counted vigorous minutes, the WHO way. The guideline is 150–300 per week (≈ 22–43 a day); more brings additional benefit.")
    Metric.Breathing -> Spec("/min", h.oxygen, false, false, { "%.1f".format(it) },
        "Breathing rate during sleep. It is remarkably stable for each person, so a jump of 1+ breaths/min over your normal — especially with a higher resting HR — often comes a day before you feel sick.")
    Metric.Stress -> Spec("", h.stress, false, false, { "%.0f".format(it) },
        "Zepp's HRV-based stress score (0–100) averaged over the day. Useful as a pattern — which days and hours run hot — rather than as an absolute measure.")
    Metric.Spo2 -> Spec("", h.oxygen, true, false, { "%.0f".format(it) },
        "Zepp's overnight blood-oxygen score. Wrist SpO₂ is noisy; look for repeated low nights rather than single readings. Frequent desaturations (ODI ≥ 5/h) are worth discussing with a doctor — the watch can't diagnose sleep apnoea.")
    Metric.Vo2 -> Spec("", h.great, true, false, { "%.1f".format(it) },
        "Estimated cardiorespiratory fitness — one of the strongest predictors of long-term health. Watch estimates can be off by ~10% in absolute terms, so the trend is what counts.")
    Metric.Weight -> Spec("kg", h.steps, null, false, { "%.1f".format(it) },
        "Scale readings swing 1–2 kg with water and food. The line is a smoothed trend (exponential moving average), which shows the real direction.")
    Metric.Load -> Spec("", h.fair, null, true, { "%.0f".format(it) },
        "Training load from your workouts per day. Fitness builds when load rises gradually; sudden spikes well above your recent average are when injuries and illness tend to happen.")
}

@Composable
fun TrendsScreen(m: Model, metric: Metric, onMetric: (Metric) -> Unit, onRefresh: () -> Unit) {
    val ranges = listOf(14 to "2W", 30 to "1M", 90 to "3M", 365 to "1Y")
    var rangeIdx by rememberSaveable { mutableIntStateOf(1) }
    val h = Ojas.health
    val e = m.engine
    val available = remember(m) { Metric.entries.filter { hasData(m, it) } }
    RefreshablePage(m.data.refreshing, onRefresh) {
        item { ScreenTitle("Trends", "Your numbers against your own normal") }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                available.forEach { mm -> FilterChip(selected = mm == metric, onClick = { onMetric(mm) }, label = { Text(mm.label) }) }
            }
        }
        item {
            val days = ranges[rangeIdx].first
            val sp = spec(metric, h)
            val values = remember(m, metric, days) { values(m, e, metric, days) }
            val dates = e.dates(days)
            val avg = remember(values, metric) {
                if (metric == Metric.Weight) Stats.ewma(values, 0.1).map { it }
                else if (sp.bars) null
                else rolling(values, 7)
            }
            val band = when (metric) {
                Metric.Hrv -> m.insights.hrv.baseline?.let { it.low..it.high }
                Metric.RestingHr -> m.insights.rhr.baseline?.let { it.low..it.high }
                Metric.Breathing -> m.insights.resp.baseline?.let { it.low..it.high }
                else -> null
            }
            val goal = when (metric) {
                Metric.Steps -> m.settings.stepGoal.toDouble()
                Metric.Sleep -> m.settings.sleepNeedMin / 60.0
                Metric.Active -> m.settings.weeklyActiveGoal / 7.0
                else -> null
            }
            var sel by remember(metric, days) { mutableStateOf<Int?>(null) }
            SectionCard {
                val shown = sel?.let { values.getOrNull(it) }
                val label = sel?.let { Fmt.dayMonth(dates[it]) } ?: (if (sp.bars) "Daily average" else "Latest")
                val headline = sel?.let { shown } ?: if (sp.bars) Stats.mean(values.filterNotNull()) else values.lastOrNull { it != null }
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(headline?.let(sp.format) ?: "—", style = MaterialTheme.typography.displaySmall.merge(Numeric), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Text(sp.unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                }
                Spacer(Modifier.height(12.dp))
                TrendChart(
                    values = values, color = sp.color,
                    labels = ChartLabels(dates.map { if (days <= 14) Fmt.weekdayShort(it) else Fmt.dayMonth(it) }, sp.format),
                    average = avg, band = band, goal = goal, bars = sp.bars,
                    onSelect = { sel = it },
                )
                Spacer(Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ranges.forEachIndexed { i, (_, l) ->
                        SegmentedButton(selected = rangeIdx == i, onClick = { rangeIdx = i }, shape = SegmentedButtonDefaults.itemShape(i, ranges.size)) { Text(l) }
                    }
                }
            }
        }
        item {
            val sp = spec(metric, h)
            val days = ranges[rangeIdx].first
            val values = values(m, e, metric, maxOf(days, 35))
            val last7 = Stats.mean(values.takeLast(7).filterNotNull())
            val prev = Stats.mean(values.dropLast(7).takeLast(28).filterNotNull())
            val all = values.takeLast(days).filterNotNull()
            SectionCard {
                Text("What it says", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Text(narrative(metric, sp, last7, prev), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth()) {
                    KeyValue("7-day avg", last7?.let(sp.format) ?: "—", Modifier.weight(1f))
                    KeyValue("Prior 4 wks", prev?.let(sp.format) ?: "—", Modifier.weight(1f))
                    KeyValue("Range", if (all.isEmpty()) "—" else "${sp.format(all.min())}–${sp.format(all.max())}", Modifier.weight(1f))
                }
            }
        }
        item {
            SectionCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Text("Why it matters", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Explainer(spec(metric, h).why.replace("*", ""))
                if (metric == Metric.Hrv || metric == Metric.RestingHr || metric == Metric.Breathing) {
                    Spacer(Modifier.height(8.dp))
                    Explainer("Shaded band: your normal range — the mean of the previous ${Rules.BASELINE_DAYS} days ± ${Rules.NORMAL_BAND_SD} SD${if (metric == Metric.Hrv) " (computed on log HRV)" else ""}. Line: 7-day rolling average.")
                }
            }
        }
    }
}

private fun hasData(m: Model, metric: Metric): Boolean = when (metric) {
    Metric.Weight -> m.data.summary?.weights?.isNotEmpty() == true
    Metric.Load -> m.data.summary?.workouts?.any { it.trainingLoad != null } == true
    Metric.Sleep -> m.data.summary?.sleeps?.isNotEmpty() == true
    else -> values(m, m.engine, metric, 120).any { it != null }
}

private fun values(m: Model, e: InsightEngine, metric: Metric, days: Int): List<Double?> = when (metric) {
    Metric.Hrv -> e.series(days) { it.sleepHrv }
    Metric.RestingHr -> e.series(days) { it.restingHr ?: it.sleepRhr }
    Metric.Steps -> e.series(days) { it.steps }
    Metric.Active -> e.series(days) { e.activeMinutes(it) }
    Metric.Breathing -> e.series(days) { it.respRate }
    Metric.Stress -> e.series(days) { it.stress }
    Metric.Spo2 -> e.series(days) { it.spo2NightScore }
    Metric.Vo2 -> e.series(days) { it.vo2max }
    Metric.Load -> e.dailyLoad(days).map { it.takeIf { v -> v > 0 } }
    Metric.Sleep -> {
        val zone = e.zone
        val byDay = m.data.summary?.sleeps.orEmpty().groupBy { Instant.ofEpochSecond(it.end).atZone(zone).toLocalDate() }
            .mapValues { (_, v) -> v.maxOf { it.asleepMin } / 60.0 }
        e.dates(days).map { byDay[it] }
    }
    Metric.Weight -> {
        val zone = e.zone
        val byDay: Map<LocalDate, Double> = m.data.summary?.weights.orEmpty()
            .groupBy { Instant.ofEpochSecond(it.t).atZone(zone).toLocalDate() }.mapValues { (_, v) -> v.last().kg }
        e.dates(days).map { byDay[it] }
    }
}

private fun rolling(values: List<Double?>, window: Int): List<Double?> = values.indices.map { i ->
    val w = values.subList(maxOf(0, i - window + 1), i + 1).filterNotNull()
    if (w.size >= maxOf(2, window / 2)) w.average() else null
}

private fun narrative(metric: Metric, sp: Spec, last7: Double?, prev: Double?): String {
    if (last7 == null) return "Not enough recent data yet."
    if (prev == null || prev == 0.0) return "This week you're averaging ${sp.format(last7)} ${sp.unit}. Keep wearing the watch to see how that compares over time."
    val pct = (last7 - prev) / prev * 100
    val dir = if (pct >= 0) "up" else "down"
    val mag = kotlin.math.abs(pct)
    val good = sp.higherIsBetter?.let { (pct > 0) == it }
    val tail = when {
        mag < 3 -> "Rock steady — that's what you want."
        good == true -> when (metric) {
            Metric.Hrv -> "Recovery is trending well; your body is absorbing the work."
            Metric.RestingHr -> "A falling resting HR usually means fitness is improving."
            Metric.Sleep -> "More sleep is the cheapest performance enhancer there is."
            Metric.Steps, Metric.Active -> "Nice — more movement, more benefit."
            else -> "Moving the right way."
        }
        good == false -> when (metric) {
            Metric.Hrv -> "Look at sleep, stress, alcohol and training volume this week."
            Metric.RestingHr -> "Could be fatigue, heat, alcohol, or the start of an illness — ease off if it keeps climbing."
            Metric.Sleep -> "Try protecting a consistent bedtime for the next few nights."
            Metric.Breathing -> "If you also feel off, treat it as an early warning and rest."
            Metric.Stress -> "Busy stretch. Short walks and breathing breaks help bring it back down."
            else -> "Worth keeping an eye on."
        }
        else -> ""
    }
    return "Your 7-day average is ${sp.format(last7)}${if (sp.unit.isNotEmpty()) " ${sp.unit}" else ""}, $dir ${"%.0f".format(mag)}% on the previous four weeks. $tail".trim()
}
