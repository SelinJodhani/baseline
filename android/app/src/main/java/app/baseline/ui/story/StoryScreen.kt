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
import androidx.compose.material3.HorizontalDivider
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
import app.baseline.ui.common.ConnectedButtonGroup
import app.baseline.ui.common.FitText
import app.baseline.ui.common.ProvideEntrance
import app.baseline.ui.common.StaggeredListScope
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
      ProvideEntrance {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 4.dp, bottom = bottom + 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { StaggeredListScope(this).run {
            item {
                Column(Modifier.padding(horizontal = 4.dp)) {
                    FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Spacer(Modifier.height(24.dp))
                    Text(metric.label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Row(verticalAlignment = Alignment.Bottom) {
                        FitText(n.value, MaterialTheme.typography.displayLarge, Modifier.weight(1f, fill = false), min = 44.sp)
                        if (n.unit.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(n.unit, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp))
                        }
                    }
                    if (n.status.isNotBlank()) LevelPill(n.status, n.level, Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(takeaway(m, metric, sp), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 29.sp))
                }
            }
            item {
                val days = ranges[rangeIdx].first
                val setup = remember(m, metric, days) { chartSetup(m, metric, days, sp, h) }
                val dates = setup.dates
                var guide by rememberSaveable(metric) { mutableStateOf(false) }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (setup.trimmed) "Since ${Fmt.dayMonth(dates.first())}" else "Last ${periodName(days)}",
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
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
                            false -> h.fair
                            null -> MaterialTheme.colorScheme.onSurface
                        }
                    }
                    setup.trend?.let { t -> TrendHeadline(metric, t, sp, if (setup.trimmed) "since ${Fmt.dayMonth(dates.first())}" else "over the last ${periodName(days)}", tColor ?: sp.color) }
                    setup.normalNow?.let { b -> NormalHeader(metric, b, sp, setup.values.lastOrNull { it != null }, setup.position?.lastOrNull() ?: 0, h) }
                    Spacer(Modifier.height(4.dp))
                    val bevel = setup.rolling != null
                    TrendChart(
                        values = setup.values, color = sp.color, trendColor = tColor,
                        labels = ChartLabels(dates.map { if (dates.size <= 14 && !setup.weekly && setup.bucketDays == 1) "${Fmt.weekday(it)}\n${it.dayOfMonth}" else if (setup.bucketDays >= 30) Fmt.monthShort(it) else Fmt.dayMonth(it) }, sp.format),
                        average = setup.average, band = setup.band, bandLabel = setup.bandLabel,
                        goal = setup.goal, goalLabel = setup.goalLabel, zones = setup.zones, bars = sp.bars && !bevel,
                        zeroBased = sp.bars && !bevel,
                        tooltip = { i -> setup.tooltip(i, sp, metric) },
                        rollingBand = setup.rolling,
                        dotColors = setup.position?.map { pos -> positionColor(pos, sp, h) },
                        avgValue = setup.avg,
                        avgLabel = setup.avg?.let { "Avg ${displayFormat(metric, sp)(it)}" },
                    )
                    Spacer(Modifier.height(10.dp))
                    ChartLegend(setup.legend(metric, sp).map { k -> if (k.label == "Trend" && tColor != null) k.copy(color = tColor) else k })
                    val st = setup.stats()
                    if (st.withData >= 2 && metric != Metric.SleepTiming) {
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth()) {
                            val df = displayFormat(metric, sp)
                            PeriodStat("Average", st.avg?.let(df), Modifier.weight(1f))
                            PeriodStat("Low", st.low?.let(df), Modifier.weight(1f))
                            PeriodStat("High", st.high?.let(df), Modifier.weight(1f))
                            PeriodStat(if (setup.weekly) "Weeks" else if (setup.bucketDays > 1) "Points" else "Days", "${st.withData}/${st.total}", Modifier.weight(1f))
                        }
                    }
                    if (setup.rows.isNotEmpty() && days >= 90) {
                        Spacer(Modifier.height(16.dp))
                        TrendTable(metric, setup.rows, sp)
                    }
                    setup.note?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(18.dp))
                    ConnectedButtonGroup(
                        options = ranges.indices.toList(), selected = rangeIdx, onSelect = { rangeIdx = it },
                        label = { ranges[it].second }, height = 48.dp,
                    )
                }
                if (guide) ChartGuideSheet(m, metric, sp, setup, currentValue(m, metric), onDismiss = { guide = false })
            }
            if (metric == Metric.RestingHr) item {
                val zone = m.insights.zone
                val from = m.insights.today.atStartOfDay(zone).toEpochSecond()
                val rows = m.data.summary?.hourly?.heartRate.orEmpty()
                if (rows.any { it[0] >= from }) SectionCard {
                    Text("Heart rate today", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    IntradayChart(rows, from, from + 86400, h.heart, { Fmt.hour(it, zone) })
                }
            }
            item { MetricAiCard(m, metric, sp, onOpenSettings) }
            item { Section(Icons.Rounded.Lightbulb, "Why it matters", sp.color) { Body(story.why) } }
            item { Section(Icons.Rounded.Visibility, "What to look for", sp.color) { Bullets(story.lookFor, sp.color) } }
            item { Section(Icons.Rounded.AutoGraph, "How to improve", sp.color) { Bullets(story.improve, sp.color, numbered = true) } }
            item {
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Icon(Icons.Rounded.Straighten, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(story.reliability.label, style = MaterialTheme.typography.titleSmall)
                        Text(story.measured, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } }
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
                null, tint = tint, modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (t.steady) "Steady $period" else "${trendPhrase(metric, t, sp)} $period",
                style = MaterialTheme.typography.titleLarge, color = tint,
            )
        }
        Text(
            (if (t.steady) "Trend around ${sp.format(t.to)}$unit" else "Trend ${sp.format(t.from)} → ${sp.format(t.to)}$unit") +
                when (t.good) { true -> " · good for you"; false -> " · worth turning around"; null -> "" },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 34.dp),
        )
    }
}

/** Dot colour for a point's place against your normal: green when that's good for you, orange when it isn't. */
private fun positionColor(pos: Int, sp: Spec, h: app.baseline.ui.theme.HealthColors): Color = when {
    pos == 0 || sp.higherIsBetter == null -> h.neutral
    (pos > 0) == sp.higherIsBetter -> h.great
    else -> h.fair
}

/** "Your normal 52–62 bpm" with where the latest point sits, Bevel-style, above the chart. */
@Composable
private fun NormalHeader(metric: Metric, b: ClosedFloatingPointRange<Double>, sp: Spec, latest: Double?, pos: Int, h: app.baseline.ui.theme.HealthColors) {
    val fmt = displayFormat(metric, sp)
    val unit = if (sp.unit.isBlank() || sp.unit == "off" || fmt !== sp.format) "" else " ${sp.unit}"
    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Your normal", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${fmt(b.start)} – ${fmt(b.endInclusive)}$unit", style = MaterialTheme.typography.headlineSmall.merge(Numeric))
        }
        if (latest != null) {
            val c = positionColor(pos, sp, h)
            LevelPill(
                when (pos) { 1 -> "Above"; -1 -> "Below"; else -> "Inside" },
                when { pos == 0 || sp.higherIsBetter == null -> Level.Good; (pos > 0) == sp.higherIsBetter -> Level.Great; else -> Level.Fair },
            )
        }
    }
}

@Composable
private fun LevelPillLike(text: String, c: Color) {
    Surface(shape = CircleShape, color = c.copy(alpha = .16f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Bevel's "Trends Analysis": change of the 7-day average over 7/14/30/90 days; grey when it's within the noise. */
@Composable
private fun TrendTable(metric: Metric, rows: List<TrendRow>, sp: Spec) {
    val h = Brand.health
    val df = displayFormat(metric, sp)
    val unit = if (sp.unit.isBlank() || sp.unit == "off" || metric == Metric.SleepTiming || df !== sp.format) "" else " ${sp.unit}"
    Text("Trend analysis", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    rows.forEach { r ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${r.days} days", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            val c = when (r.good) { true -> h.great; false -> h.fair; null -> MaterialTheme.colorScheme.onSurfaceVariant }
            Icon(
                when { !r.significant -> Icons.AutoMirrored.Rounded.TrendingFlat; r.delta > 0 -> Icons.AutoMirrored.Rounded.TrendingUp; else -> Icons.AutoMirrored.Rounded.TrendingDown },
                null, tint = c, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            val amount = if (metric == Metric.SleepTiming) "${(kotlin.math.abs(r.delta) * 60).roundToInt()} min ${if (r.delta > 0) "later" else "earlier"}"
                else "${if (r.delta >= 0) "+" else "−"}${df(kotlin.math.abs(r.delta))}$unit"
            Text(amount, style = MaterialTheme.typography.titleMedium.merge(Numeric), color = c)
        }
    }
    Text(
        "Your recent average (last 7, 14 or 30 days) against the same stretch that many days earlier. Grey means the change is within your usual day-to-day swing.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
    )
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
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", style = MaterialTheme.typography.titleLarge.merge(Numeric))
    }
}

@Composable
private fun Section(icon: ImageVector, title: String, tint: Color, content: @Composable () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)

@Composable
private fun Bullets(items: List<String>, tint: Color, numbered: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { i, t ->
            Row(verticalAlignment = Alignment.Top) {
                val cs = MaterialTheme.colorScheme
                Box(Modifier.padding(top = 2.dp).size(26.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                    if (numbered) Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
                    else Box(Modifier.size(7.dp).clip(CircleShape).background(cs.onPrimaryContainer))
                }
                Spacer(Modifier.width(14.dp))
                Text(t, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
