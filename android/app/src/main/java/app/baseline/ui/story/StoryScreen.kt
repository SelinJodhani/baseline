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
import app.baseline.insight.Level
import app.baseline.insight.Rules
import app.baseline.insight.Stats
import app.baseline.ui.charts.ChartLabels
import app.baseline.ui.charts.IntradayChart
import app.baseline.ui.charts.TrendChart
import app.baseline.ui.common.Fmt
import app.baseline.ui.common.LevelPill
import app.baseline.ui.common.SectionCard
import app.baseline.ui.theme.Numeric
import app.baseline.ui.theme.Brand

@Composable
fun StoryScreen(m: Model, metric: Metric, onBack: () -> Unit) {
    val h = Brand.health
    val sp = spec(metric, h)
    val story = storyOf(metric)
    val n = now(m, metric, sp)
    val accent = if (n.level == Level.Unknown) sp.color else h.of(n.level)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val ranges = listOf(14 to "2W", 30 to "1M", 90 to "3M", 365 to "1Y")
    var rangeIdx by rememberSaveable(metric) { mutableIntStateOf(1) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
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
                val vals = remember(m, metric, days) { values(m, metric, days) }
                val dates = m.engine.dates(days)
                val avg = remember(vals) {
                    when {
                        metric == Metric.Weight -> Stats.ewma(vals, 0.1)
                        sp.bars -> null
                        else -> vals.indices.map { i -> vals.subList(maxOf(0, i - 6), i + 1).filterNotNull().takeIf { it.size >= 3 }?.average() }
                    }
                }
                val band = when (metric) {
                    Metric.Hrv -> m.insights.hrv.baseline?.let { it.low..it.high }
                    Metric.RestingHr -> m.insights.rhr.baseline?.let { it.low..it.high }
                    Metric.Breathing -> m.insights.resp.baseline?.let { it.low..it.high }
                    Metric.Readiness -> 62.0..80.0
                    else -> null
                }
                val goal = when (metric) {
                    Metric.Steps -> m.settings.stepGoal.toDouble()
                    Metric.Sleep -> m.settings.sleepNeedMin / 60.0
                    Metric.Active -> m.settings.weeklyActiveGoal / 7.0
                    Metric.Consistency -> 30.0
                    else -> null
                }
                var sel by remember(metric, days) { mutableStateOf<Int?>(null) }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            sel?.let { i -> "${Fmt.weekdayShort(dates[i])} ${Fmt.dayMonth(dates[i])}  ·  ${vals.getOrNull(i)?.let(sp.format) ?: "no data"} ${sp.unit}" }
                                ?: "Last ${ranges[rangeIdx].second.lowercase().let { if (it == "1y") "year" else if (it == "3m") "3 months" else if (it == "1m") "month" else "2 weeks" }}",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TrendChart(
                        values = vals, color = sp.color,
                        labels = ChartLabels(dates.map { if (days <= 14) Fmt.weekdayShort(it) else Fmt.dayMonth(it) }, sp.format),
                        average = avg, band = band, goal = goal, bars = sp.bars, onSelect = { sel = it },
                    )
                    Spacer(Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ranges.forEachIndexed { i, (_, l) ->
                            SegmentedButton(selected = rangeIdx == i, onClick = { rangeIdx = i }, shape = SegmentedButtonDefaults.itemShape(i, ranges.size), icon = {}) { Text(l) }
                        }
                    }
                    if (band != null && metric != Metric.Readiness) {
                        Spacer(Modifier.height(10.dp))
                        Text("Shaded: your normal range (previous ${Rules.BASELINE_DAYS} days). Line: 7-day average.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
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
        Box(Modifier.fillMaxWidth().height(top).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f)))
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
