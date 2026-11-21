package app.ojas.ui.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ojas.Model
import app.ojas.insight.InsightEngine
import app.ojas.insight.Level
import app.ojas.ui.charts.ChartLabels
import app.ojas.ui.charts.Hypnogram
import app.ojas.ui.charts.SleepWindows
import app.ojas.ui.charts.StackBar
import app.ojas.ui.charts.TrendChart
import app.ojas.ui.common.CardHeader
import app.ojas.ui.common.Explainer
import app.ojas.ui.common.Fmt
import app.ojas.ui.common.KeyValue
import app.ojas.ui.common.LegendItem
import app.ojas.ui.common.LevelPill
import app.ojas.ui.common.RefreshablePage
import app.ojas.ui.common.ScreenTitle
import app.ojas.ui.common.SectionCard
import app.ojas.ui.common.StatTile
import app.ojas.ui.common.TileRow
import app.ojas.ui.theme.Numeric
import app.ojas.ui.theme.Ojas
import java.time.Instant
import kotlin.math.roundToInt

@Composable
fun SleepScreen(m: Model, onRefresh: () -> Unit) {
    val s = m.insights.sleep
    val h = Ojas.health
    val zone = m.insights.zone
    val e = m.engine
    val sleeps = m.data.summary?.sleeps.orEmpty()
    val mains = sleeps.groupBy { Instant.ofEpochSecond(it.end).atZone(zone).toLocalDate() }.mapValues { (_, v) -> v.maxBy { it.asleepMin } }

    RefreshablePage(m.data.refreshing, onRefresh) {
        item { ScreenTitle("Sleep", s.last?.let { "Night ending ${Fmt.fullDate(Instant.ofEpochSecond(it.end).atZone(zone).toLocalDate())}" } ?: "No recent night") }

        item {
            val last = s.last
            SectionCard {
                if (last == null) {
                    Text("No sleep recorded in the last day. Wear your watch to bed and sync the Zepp app in the morning.", style = MaterialTheme.typography.bodyMedium)
                    return@SectionCard
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Fmt.duration(s.asleepMin), style = MaterialTheme.typography.displayMedium.merge(Numeric), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    LevelPill(
                        when (s.level) { Level.Great -> "Fully rested"; Level.Good -> "Nearly there"; Level.Fair -> "Short"; Level.Low -> "Very short"; else -> "" },
                        s.level, Modifier.padding(bottom = 10.dp),
                    )
                }
                Text("${Fmt.clock(last.start, zone)} – ${Fmt.clock(last.end, zone)} · need ${Fmt.duration(s.needMin)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                val stages = last.stages.orEmpty().mapNotNull { st ->
                    val a = st.getOrNull(0)?.toLongOrNull(); val b = st.getOrNull(1)?.toLongOrNull(); val k = st.getOrNull(2)
                    if (a != null && b != null && k != null) Triple(a, b, k) else null
                }
                Spacer(Modifier.height(16.dp))
                if (stages.isNotEmpty()) {
                    Hypnogram(stages, mapOf("deep" to h.deep, "light" to h.light, "rem" to h.rem, "awake" to h.awake), { Fmt.hour(it, zone) }, zone = zone)
                } else {
                    StackBar(listOf((last.deepMin ?: 0).toFloat() to h.deep, (last.lightMin ?: 0).toFloat() to h.light, (last.remMin ?: 0).toFloat() to h.rem, (last.awakeMin ?: 0).toFloat() to h.awake), height = 16.dp)
                }
                Spacer(Modifier.height(14.dp))
                val total = listOfNotNull(last.deepMin, last.lightMin, last.remMin).sum().coerceAtLeast(1)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LegendItem(h.deep, "Deep ${Fmt.duration(last.deepMin)} · ${pct(last.deepMin, total)}")
                    LegendItem(h.light, "Light ${Fmt.duration(last.lightMin)}")
                    LegendItem(h.rem, "REM ${Fmt.duration(last.remMin)} · ${pct(last.remMin, total)}")
                    LegendItem(h.awake, "Awake ${Fmt.duration(last.awakeMin)} · ${last.wakeCount ?: 0}×")
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth()) {
                    KeyValue("Efficiency*", s.efficiency?.let { "${(it * 100).roundToInt()}%" } ?: "—", Modifier.weight(1f))
                    KeyValue("7-night avg", s.avg7Min?.let { Fmt.duration(it.roundToInt()) } ?: "—", Modifier.weight(1f))
                    KeyValue("Zepp score", last.score?.toString() ?: "—", Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Explainer("Stage split is an estimate — wrist trackers get total sleep roughly right but classify stages with only moderate accuracy. Lean on duration and timing. *Wrist devices miss some wake time, so efficiency reads high.")
            }
        }

        item {
            val dates = e.dates(14)
            val values = dates.map { d -> mains[d]?.asleepMin?.div(60.0) }
            SectionCard {
                CardHeader("Duration · 14 nights", Icons.Rounded.CalendarMonth, h.sleep, trailing = s.avg7Min?.let { "avg ${Fmt.duration(it.roundToInt())}" })
                Spacer(Modifier.height(12.dp))
                TrendChart(values, h.sleep, ChartLabels(dates.map { Fmt.weekday(it) }) { "%.0fh".format(it) }, bars = true, goal = s.needMin / 60.0)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LevelPill(if (s.debt7Min > 0) "Sleep debt ${Fmt.duration(s.debt7Min)} (7 nights)" else "No sleep debt", if (s.debt7Min >= 180) Level.Fair else Level.Great)
                }
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
            SectionCard {
                CardHeader("Consistency", Icons.Rounded.Schedule, h.sleep)
                Spacer(Modifier.height(12.dp))
                SleepWindows(
                    windows, dates.map { Fmt.weekday(it) }, h.sleep,
                    targetBed = s.suggestedBedtime?.let { (it.hour * 60 + it.minute - 18 * 60).let { v -> if (v < 0) v + 1440 else v }.toFloat() },
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    KeyValue("Bedtime varies", s.bedtimeSdMin?.let { "±${it.roundToInt()} min" } ?: "—", Modifier.weight(1f))
                    KeyValue("Wake varies", s.wakeSdMin?.let { "±${it.roundToInt()} min" } ?: "—", Modifier.weight(1f))
                    KeyValue("Aim for bed", s.suggestedBedtime?.let { InsightEngine.fmtTime(it) } ?: "—", Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Explainer("Regular sleep timing predicts health outcomes at least as strongly as duration. Under ±30 min night to night is excellent; over ±60 min is like mild jet lag every week. The dashed line is a bedtime that gives you your full sleep need before your usual wake time.")
            }
        }

        item {
            val d = m.engine.day(m.insights.today)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Overnight recovery", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                TileRow {
                    StatTile("Sleep HRV", Fmt.int(d?.sleepHrv), "ms", Modifier.weight(1f), level = m.insights.hrv.level)
                    StatTile("Sleeping HR", Fmt.int(d?.sleepRhr), "bpm", Modifier.weight(1f), level = m.insights.rhr.level)
                }
                TileRow {
                    StatTile("Breathing", Fmt.one(d?.respRate), "/min", Modifier.weight(1f), level = m.insights.resp.level)
                    StatTile("SpO₂ score", Fmt.int(d?.spo2NightScore), null, Modifier.weight(1f),
                        caption = d?.spo2Odi?.let { "ODI ${"%.1f".format(it)} events/h" }, level = m.insights.nightBreathingLevel)
                }
            }
        }

        item {
            val tips = buildList {
                if (s.consistency == Level.Fair || s.consistency == Level.Low) add("Anchor your wake-up time — even on weekends. Bedtime follows.")
                if (s.debt7Min >= 180) add("Bank an earlier night or two this week; a 20-minute early-afternoon nap also helps.")
                if ((s.efficiency ?: 1.0) < 0.85) add("Lots of time awake in bed: keep the room cool and dark, and get up if you can't sleep after ~20 minutes.")
                if (isEmpty()) add("You're doing the fundamentals right. Morning daylight and a consistent wind-down keep it that way.")
            }
            SectionCard(container = MaterialTheme.colorScheme.secondaryContainer) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Lightbulb, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tips.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer) }
                    }
                }
            }
        }
    }
}

private fun pct(v: Int?, total: Int) = v?.let { "${(it * 100.0 / total).roundToInt()}%" } ?: "—"
