package app.baseline.ui.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.baseline.Model
import app.baseline.insight.InsightEngine
import app.baseline.insight.Level
import app.baseline.ui.charts.ChartLabels
import app.baseline.ui.charts.Hypnogram
import app.baseline.ui.charts.SleepWindows
import app.baseline.ui.charts.StackBar
import app.baseline.ui.charts.TrendChart
import app.baseline.ui.common.CardHeader
import app.baseline.ui.common.Fmt
import app.baseline.ui.common.LegendItem
import app.baseline.ui.common.LevelPill
import app.baseline.ui.common.RefreshablePage
import app.baseline.ui.common.ScreenTitle
import app.baseline.ui.common.SectionCard
import app.baseline.ui.story.Metric
import app.baseline.ui.theme.Numeric
import app.baseline.ui.theme.Brand
import java.time.Instant
import kotlin.math.roundToInt

@Composable
fun SleepScreen(m: Model, onRefresh: () -> Unit, onMetric: (Metric) -> Unit) {
    val s = m.insights.sleep
    val h = Brand.health
    val zone = m.insights.zone
    val e = m.engine
    val mains = e.mainSleeps

    RefreshablePage(m.data.refreshing, onRefresh) {
        item { ScreenTitle("Sleep", s.last?.let { "Night ending ${Fmt.fullDate(Instant.ofEpochSecond(it.end).atZone(zone).toLocalDate())}" } ?: "No recent night") }

        item {
            val last = s.last
            SectionCard(onClick = { onMetric(Metric.Sleep) }) {
                if (last == null) {
                    Text("No sleep recorded in the last day. Wear your watch to bed and open the Zepp app in the morning so it syncs.", style = MaterialTheme.typography.bodyMedium)
                    return@SectionCard
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Fmt.duration(s.asleepMin), style = MaterialTheme.typography.displayMedium.merge(Numeric), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    LevelPill(
                        when (s.level) { Level.Great -> "Fully rested"; Level.Good -> "Nearly there"; Level.Fair -> "Short"; Level.Low -> "Very short"; else -> "—" },
                        s.level, Modifier.padding(bottom = 10.dp),
                    )
                }
                Text("${Fmt.clock(last.start, zone)} – ${Fmt.clock(last.end, zone)}  ·  need ${Fmt.duration(s.needMin)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(18.dp))
                val stages = last.stages.orEmpty().mapNotNull { st ->
                    val a = st.getOrNull(0)?.toLongOrNull(); val b = st.getOrNull(1)?.toLongOrNull(); val k = st.getOrNull(2)
                    if (a != null && b != null && k != null) Triple(a, b, k) else null
                }
                if (stages.isNotEmpty()) {
                    Hypnogram(stages, mapOf("deep" to h.deep, "light" to h.light, "rem" to h.rem, "awake" to h.awake), { Fmt.hour(it, zone) }, zone = zone)
                } else {
                    StackBar(listOf((last.deepMin ?: 0).toFloat() to h.deep, (last.lightMin ?: 0).toFloat() to h.light, (last.remMin ?: 0).toFloat() to h.rem, (last.awakeMin ?: 0).toFloat() to h.awake), height = 14.dp)
                }
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LegendItem(h.deep, "Deep ${Fmt.duration(last.deepMin)}")
                    LegendItem(h.light, "Light ${Fmt.duration(last.lightMin)}")
                    LegendItem(h.rem, "REM ${Fmt.duration(last.remMin)}")
                    LegendItem(h.awake, "Awake ${Fmt.duration(last.awakeMin)}")
                }
            }
        }

        item {
            val dates = e.dates(14)
            val values = dates.map { d -> mains[d]?.asleepMin?.div(60.0) }
            SectionCard(onClick = { onMetric(Metric.Sleep) }) {
                CardHeader("Last 14 nights", Icons.Rounded.CalendarMonth, h.sleep, trailing = s.avg7Min?.let { "avg ${Fmt.duration(it.roundToInt())}" }, chevron = true)
                Spacer(Modifier.height(12.dp))
                TrendChart(values, h.sleep, ChartLabels(dates.map { Fmt.weekday(it) }) { "%.0fh".format(it) }, bars = true, goal = s.needMin / 60.0)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (s.debt7Min >= 60) "${Fmt.duration(s.debt7Min)} short over the last 7 nights." else "No meaningful sleep debt this week.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item {
            val dates = e.dates(14)
            fun rel(epoch: Long): Float {
                val t = Instant.ofEpochSecond(epoch).atZone(zone)
                var mins = t.hour * 60 + t.minute - 18 * 60
                if (mins < 0) mins += 1440
                return mins.toFloat()
            }
            val windows = dates.map { d -> mains[d]?.let { rel(it.start) to rel(it.end).let { w -> if (w < rel(it.start)) w + 1440 else w } } }
            SectionCard(onClick = { onMetric(Metric.Consistency) }) {
                CardHeader("Rhythm", Icons.Rounded.Schedule, h.sleep, trailing = listOfNotNull(s.bedtimeSdMin, s.wakeSdMin).maxOrNull()?.let { "±${it.roundToInt()} min" }, chevron = true)
                Spacer(Modifier.height(12.dp))
                SleepWindows(
                    windows, dates.map { Fmt.weekday(it) }, h.sleep,
                    targetBed = s.suggestedBedtime?.let { (it.hour * 60 + it.minute - 18 * 60).let { v -> if (v < 0) v + 1440 else v }.toFloat() },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    s.suggestedBedtime?.let { "Tonight, aim to be in bed by ${InsightEngine.fmtTime(it)} (dashed line)." } ?: "A few more nights are needed to suggest a bedtime.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
