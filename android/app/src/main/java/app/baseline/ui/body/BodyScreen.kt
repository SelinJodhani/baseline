package app.baseline.ui.body

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.baseline.Model
import app.baseline.insight.Level
import app.baseline.ui.charts.MiniBars
import app.baseline.ui.charts.Sparkline
import app.baseline.ui.common.RefreshablePage
import app.baseline.ui.common.ScreenTitle
import app.baseline.ui.story.Metric
import app.baseline.ui.story.hasData
import app.baseline.ui.story.now
import app.baseline.ui.story.spec
import app.baseline.ui.story.values
import app.baseline.ui.theme.Numeric
import app.baseline.ui.theme.Brand

private val groups = listOf(
    "Recovery" to listOf(Metric.Readiness, Metric.Signals, Metric.Hrv, Metric.HrvTrend, Metric.RestingHr, Metric.HrDip, Metric.Breathing),
    "Sleep" to listOf(Metric.Sleep, Metric.SleepNeed, Metric.Consistency, Metric.SleepTiming, Metric.Spo2),
    "Fitness" to listOf(Metric.Movement, Metric.Vo2, Metric.Active, Metric.Zones, Metric.Steps, Metric.Load),
    "Everyday" to listOf(Metric.Stress, Metric.Weight),
)

@Composable
fun BodyScreen(m: Model, onRefresh: () -> Unit, onMetric: (Metric) -> Unit, onLabs: () -> Unit = {}, onBodyComp: () -> Unit = {}, onHealthspan: () -> Unit = {}) {
    val available = remember(m) { Metric.entries.filter { hasData(m, it) }.toSet() }
    RefreshablePage(m.data.refreshing || m.data.syncing, onRefresh) {
        item { ScreenTitle("Body", "Every signal, with the story behind it") }
        item { app.baseline.ui.healthspan.HealthspanEntry(m, onHealthspan) }
        item { app.baseline.labs.ui.LabsEntries(onLabs, onBodyComp) }
        groups.forEach { (title, metrics) ->
            val shown = metrics.filter { it in available }
            if (shown.isEmpty()) return@forEach
            item {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 6.dp))
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        shown.forEachIndexed { i, metric ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                            MetricRow(m, metric) { onMetric(metric) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricRow(m: Model, metric: Metric, onClick: () -> Unit) {
    val h = Brand.health
    val sp = spec(metric, h)
    val n = now(m, metric, sp)
    // Start at the first reading so a short history fills the sparkline instead of hugging its right edge.
    val trend = remember(m, metric) { values(m, metric, 21).let { v -> v.drop(v.indexOfFirst { it != null }.coerceIn(0, maxOf(0, v.size - 7))) } }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(metric.label, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (n.level != Level.Unknown) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(h.of(n.level)))
                    Spacer(Modifier.width(6.dp))
                }
                Text(n.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
        if (metric != Metric.Signals && trend.count { it != null } >= 2) {
            if (sp.bars) MiniBars(trend.takeLast(14), sp.color, Modifier.width(64.dp).height(28.dp))
            else Sparkline(trend, sp.color, Modifier.width(64.dp).height(28.dp))
            Spacer(Modifier.width(14.dp))
        } else Spacer(Modifier.width(78.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 56.dp)) {
            Text(n.value, style = MaterialTheme.typography.titleLarge.merge(Numeric), fontWeight = FontWeight.Bold, maxLines = 1)
            if (n.unit.isNotBlank()) Text(n.unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
