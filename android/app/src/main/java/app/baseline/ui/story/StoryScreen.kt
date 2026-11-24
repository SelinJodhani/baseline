package app.baseline.ui.story

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoGraph
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import app.baseline.ui.charts.ChartLegend
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.baseline.Model
import app.baseline.ai.MetricAiCard
import app.baseline.insight.Level
import app.baseline.insight.Rules
import app.baseline.insight.Stats
import app.baseline.ui.charts.ChartLabels
import app.baseline.ui.charts.IntradayChart
import app.baseline.ui.charts.TrendChart
import app.baseline.ui.common.Fmt
import app.baseline.ui.common.LevelPill
import app.baseline.ui.common.SectionCard
import app.baseline.ui.common.StatusBarScrim
import app.baseline.ui.theme.Numeric
import app.baseline.ui.theme.Brand
import kotlin.math.roundToInt

@Composable
fun StoryScreen(m: Model, metric: Metric, onBack: () -> Unit, onOpenSettings: () -> Unit = {}) {
    val h = Brand.health
    val sp = spec(metric, h)
    val story = storyOf(metric)
    val n = now(m, metric, sp)
    val accent = if (n.level == Level.Unknown) sp.color else h.of(n.level)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val ranges = listOf(14 to "2W", 30 to "1M", 90 to "3M", 180 to "6M", 365 to "1Y")
    var rangeIdx by rememberSaveable(metric) { mutableIntStateOf(1) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      Box(Modifier.fillMaxSize()) { // Surface would stretch the status-bar scrim over the whole page
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 4.dp, bottom = bottom + 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                FilledTonalIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Spacer(Modifier.height(20.dp))
                Text(metric.label.uppercase(), style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.5.sp), color = sp.color)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(n.value, style = MaterialTheme.typography.displayLarge.merge(Numeric), fontWeight = FontWeight.Bold)
                    if (n.unit.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(n.unit, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                    }
                }
                if (n.status.isNotBlank()) LevelPill(n.status, n.level)
                Spacer(Modifier.height(16.dp))
                Text(takeaway(m, metric, sp), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal, lineHeight = 26.sp))
            }
            item {
                val days = ranges[rangeIdx].first
                val setup = remember(m, metric, days) { chartSetup(m, metric, days, sp, h) }
                val dates = setup.dates
                var guide by rememberSaveable(metric) { mutableStateOf(false) }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (setup.trimmed) "Since ${Fmt.dayMonth(dates.first())}  ·  all your data so far"
                            else "Last ${periodName(days)}",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { guide = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.HelpOutline, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("How to read")
                        }
                    }
                    val tColor = setup.trend?.let { t ->
                        when (t.good) {
                            true -> h.great
                            false -> if (sp.color == h.fair || sp.color == h.steps) h.low else h.fair // stay distinct from amber bars
                            null -> if (sp.bars) MaterialTheme.colorScheme.onSurface else sp.color
                        }
                    }
                    setup.trend?.let { t -> TrendHeadline(metric, t, sp, if (setup.trimmed) "since ${Fmt.dayMonth(dates.first())}" else "over the last ${periodName(days)}", tColor ?: sp.color) }
                    Spacer(Modifier.height(4.dp))
                    TrendChart(
                        values = setup.values, color = sp.color, trendColor = tColor,
                        labels = ChartLabels(dates.map { if (dates.size <= 14 && !setup.weekly) "${Fmt.weekday(it)}\n${it.dayOfMonth}" else Fmt.dayMonth(it) }, sp.format),
                        average = setup.average, band = setup.band, bandLabel = setup.bandLabel,
                        goal = setup.goal, goalLabel = setup.goalLabel, zones = setup.zones, bars = sp.bars,
                        tooltip = { i -> setup.tooltip(i, sp) },
                    )
                    Spacer(Modifier.height(10.dp))
                    ChartLegend(setup.legend(metric, sp).map { k -> if (k.label == "Trend" && tColor != null) k.copy(color = tColor) else k })
                    val st = setup.stats()
                    if (st.withData >= 2 && metric != Metric.SleepTiming) {
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth()) {
                            PeriodStat("Average", st.avg?.let(sp.format), Modifier.weight(1f))
                            PeriodStat("Low", st.low?.let(sp.format), Modifier.weight(1f))
                            PeriodStat("High", st.high?.let(sp.format), Modifier.weight(1f))
                            PeriodStat(if (setup.weekly) "Weeks" else "Days", "${st.withData}/${st.total}", Modifier.weight(1f))
                        }
                    }
                    setup.note?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(14.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ranges.forEachIndexed { i, (_, l) ->
                            SegmentedButton(selected = rangeIdx == i, onClick = { rangeIdx = i }, shape = SegmentedButtonDefaults.itemShape(i, ranges.size), icon = {}) { Text(l) }
                        }
                    }
                }
                if (guide) ChartGuideSheet(m, metric, sp, setup, currentValue(m, metric), onDismiss = { guide = false })
            }
            if (metric == Metric.RestingHr) item {
                val zone = m.insights.zone
                val from = m.insights.today.atStartOfDay(zone).toEpochSecond()
                val rows = m.data.summary?.hourly?.heartRate.orEmpty()
                if (rows.any { it[0] >= from }) SectionCard {
                    Text("Heart rate today", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    IntradayChart(rows, from, from + 86400, h.heart, { Fmt.hour(it, zone) })
                }
            }
            item { MetricAiCard(m, metric, sp, onOpenSettings) }
            item { Section(Icons.Rounded.Lightbulb, "Why it matters", sp.color) { Body(story.why) } }
            item { Section(Icons.Rounded.Visibility, "What to look for", sp.color) { Bullets(story.lookFor, sp.color) } }
            item { Section(Icons.Rounded.AutoGraph, "How to improve", sp.color) { Bullets(story.improve, sp.color, numbered = true) } }
            item {
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Icon(Icons.Rounded.Straighten, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(story.reliability.label, style = MaterialTheme.typography.labelLarge)
                        Text(story.measured, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        StatusBarScrim()
      }
    }
}

private fun periodName(days: Int) = when (days) { 365 -> "year"; 180 -> "6 months"; 90 -> "3 months"; 30 -> "month"; else -> "2 weeks" }

/** "↑ 6% over 3 months · 62 → 66 ms": where the period is heading, coloured by whether that's good for you. */
@Composable
private fun TrendHeadline(metric: Metric, t: TrendInfo, sp: Spec, period: String, tint: Color) {
    val unit = if (sp.unit.isBlank() || sp.unit == "off") "" else " ${sp.unit}"
    Column(Modifier.padding(top = 2.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when { t.steady -> Icons.AutoMirrored.Rounded.TrendingFlat; t.delta > 0 -> Icons.AutoMirrored.Rounded.TrendingUp; else -> Icons.AutoMirrored.Rounded.TrendingDown },
                null, tint = tint, modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (t.steady) "Steady $period" else "${trendPhrase(metric, t, sp)} $period",
                style = MaterialTheme.typography.titleMedium, color = tint, fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            (if (t.steady) "Trend around ${sp.format(t.to)}$unit" else "Trend ${sp.format(t.from)} → ${sp.format(t.to)}$unit") +
                when (t.good) { true -> " · good for you"; false -> " · worth turning around"; null -> "" },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 30.dp),
        )
    }
}

/** The change in the metric's own terms: minutes for clock times, points for scores, % for real quantities. */
private fun trendPhrase(metric: Metric, t: TrendInfo, sp: Spec): String {
    val up = t.delta > 0
    val d = kotlin.math.abs(t.delta)
    return when (metric) {
        Metric.SleepTiming -> (d * 60).roundToInt().let { m -> if (m < 60) "$m min" else app.baseline.insight.InsightEngine.fmtDuration(m) } + if (up) " later" else " earlier"
        Metric.Readiness, Metric.Consistency, Metric.Stress, Metric.Spo2 -> "${sp.format(d)} points ${if (up) "higher" else "lower"}"
        Metric.Vo2 -> "${sp.format(d)} ${if (up) "higher" else "lower"}"
        Metric.SleepNeed -> "Shortfall ${if (up) "up" else "down"} ${sp.format(d)} h"
        Metric.Signals -> "${sp.format(d)} ${if (up) "more" else "fewer"} signals off"
        else -> t.pct?.let { "${"%.0f".format(kotlin.math.abs(it))}% ${if (up) "higher" else "lower"}" } ?: "${if (up) "Up" else "Down"} ${sp.format(d)}"
    }
}

@Composable
private fun PeriodStat(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", style = MaterialTheme.typography.titleMedium.merge(Numeric), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Section(icon: ImageVector, title: String, tint: Color, content: @Composable () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp), color = MaterialTheme.colorScheme.onSurface)

@Composable
private fun Bullets(items: List<String>, tint: Color, numbered: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { i, t ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 2.dp).size(22.dp).clip(CircleShape).background(tint.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                    if (numbered) Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.Bold)
                    else Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
                }
                Spacer(Modifier.width(12.dp))
                Text(t, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp))
            }
        }
    }
}
