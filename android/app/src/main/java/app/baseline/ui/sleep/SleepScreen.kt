package app.baseline.ui.sleep

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
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
import app.baseline.insight.DebtTrend
import app.baseline.insight.InsightEngine
import app.baseline.insight.Level
import app.baseline.ui.common.KeyValue
import app.baseline.ui.charts.ChartLabels
import app.baseline.ui.charts.ChartLegend
import app.baseline.ui.charts.KeyKind
import app.baseline.ui.charts.LegendKey
import app.baseline.ui.story.hoursDifferent
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
import app.baseline.ui.common.BigTile
import app.baseline.ui.common.FitText
import app.baseline.ui.common.PairedTiles
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SleepScreen(m: Model, onRefresh: () -> Unit, onMetric: (Metric) -> Unit) {
    val s = m.insights.sleep
    val h = Brand.health
    val zone = m.insights.zone
    val e = m.engine
    val mains = e.mainSleeps
    val adv = m.insights.advanced ?: e.advanced.insights

    RefreshablePage(m.data.refreshing || m.data.syncing, onRefresh) {
        item { ScreenTitle("Sleep", s.last?.let { "Night ending ${Fmt.fullDate(Instant.ofEpochSecond(it.end).atZone(zone).toLocalDate())}" } ?: "No recent night") }

        val last = s.last
        if (last == null) item {
            SectionCard {
                Text("No sleep recorded in the last day. Wear your watch to bed and open the Zepp app in the morning so it syncs.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            // The number is the headline.
            item {
                Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable { onMetric(Metric.Sleep) }.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        FitText(Fmt.duration(s.asleepMin), MaterialTheme.typography.displayLarge, Modifier.weight(1f, fill = false), min = 48.sp)
                        Spacer(Modifier.width(10.dp))
                        Text("asleep", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    LevelPill(
                        when (s.level) { Level.Great -> "Fully rested"; Level.Good -> "Nearly there"; Level.Fair -> "Short night"; Level.Low -> "Very short night"; else -> "—" },
                        s.level,
                    )
                    Spacer(Modifier.height(10.dp))
                    val gap = s.needMin - (s.asleepMin ?: 0)
                    Text(
                        if (gap >= 10) "${Fmt.duration(gap)} short of your ${Fmt.duration(s.needMin)} need" else "You met your ${Fmt.duration(s.needMin)} need",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text("${Fmt.clock(last.start, zone)} – ${Fmt.clock(last.end, zone)}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item {
                SectionCard(onClick = { onMetric(Metric.Sleep) }) {
                    CardHeader("Stages", Icons.Rounded.Bedtime, h.sleep)
                    Spacer(Modifier.height(16.dp))
                    val stages = last.stageSpans
                    if (stages.isNotEmpty()) {
                        Hypnogram(stages, mapOf("deep" to h.deep, "light" to h.light, "rem" to h.rem, "awake" to h.awake), { Fmt.hour(it, zone) }, zone = zone)
                    } else {
                        StackBar(listOf((last.deepMin ?: 0).toFloat() to h.deep, (last.lightMin ?: 0).toFloat() to h.light, (last.remMin ?: 0).toFloat() to h.rem, (last.awakeMin ?: 0).toFloat() to h.awake), height = 16.dp)
                    }
                    Spacer(Modifier.height(16.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LegendItem(h.deep, "Deep ${Fmt.duration(last.deepMin)}")
                        LegendItem(h.light, "Light ${Fmt.duration(last.lightMin)}")
                        LegendItem(h.rem, "REM ${Fmt.duration(last.remMin)}")
                        LegendItem(h.awake, "Awake ${Fmt.duration(last.awakeMin)}")
                    }
                    adv.hrDip?.takeIf { it.at in last.start..last.end }?.let { d ->
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Lowest heart rate ${d.bpm.roundToInt()} bpm at ${Fmt.clock(d.at, zone)} · ${d.timing.label}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { onMetric(Metric.HrDip) },
                        )
                    }
                }
            }

            item {
                PairedTiles(
                    { mod, shape -> BigTile("Deep", Fmt.duration(last.deepMin), mod, shape, caption = pctOf(last.deepMin, s.asleepMin)?.let { "$it% of the night" }, onClick = { onMetric(Metric.Sleep) }) },
                    { mod, shape -> BigTile("REM", Fmt.duration(last.remMin), mod, shape, caption = pctOf(last.remMin, s.asleepMin)?.let { "$it% of the night" }, onClick = { onMetric(Metric.Sleep) }) },
                )
            }
            item {
                PairedTiles(
                    { mod, shape ->
                        BigTile(
                            "Rhythm", adv.sri.value?.roundToInt()?.toString() ?: "—", mod, shape, unit = adv.sri.value?.let { "/ 100" },
                            caption = adv.sri.value?.let { when (adv.sri.level) { Level.Great -> "regular"; Level.Good -> "fairly regular"; else -> "irregular" } } ?: "a few more nights",
                            onClick = { onMetric(Metric.Consistency) },
                        )
                    },
                    { mod, shape ->
                        BigTile(
                            "Tonight", s.suggestedBedtime?.let { InsightEngine.fmtTime(it) } ?: "—", mod, shape,
                            caption = if (s.suggestedBedtime != null) "bed by, for ${Fmt.duration(s.needMin)}" else "needs a few nights",
                            onClick = { onMetric(Metric.SleepNeed) },
                        )
                    },
                )
            }
        }

        item {
            val dates = e.dates(14)
            val values = dates.map { d -> mains[d]?.asleepMin?.div(60.0) }
            SectionCard(onClick = { onMetric(Metric.Sleep) }) {
                CardHeader("Last 14 nights", Icons.Rounded.CalendarMonth, h.sleep, trailing = s.avg7Min?.let { "avg ${Fmt.duration(it.roundToInt())}" }, chevron = true)
                Spacer(Modifier.height(12.dp))
                TrendChart(
                    values, h.sleep, ChartLabels(dates.map { "${Fmt.weekday(it)}\n${it.dayOfMonth}" }) { "%.0fh".format(it) },
                    bars = true, goal = s.needMin / 60.0, goalLabel = "Need ${Fmt.duration(s.needMin)}",
                    tooltip = { i -> "${Fmt.weekdayShort(dates[i])} ${Fmt.dayMonth(dates[i])}\n" + (mains[dates[i]]?.let { n -> "${Fmt.duration(n.asleepMin)} asleep · ${Fmt.clock(n.start, zone)}–${Fmt.clock(n.end, zone)}" } ?: "No sleep recorded") },
                )
                Spacer(Modifier.height(10.dp))
                ChartLegend(listOf(LegendKey(KeyKind.Bar, h.sleep, "Hours asleep (solid = need met)"), LegendKey(KeyKind.Dash, h.sleep, "Your need (Settings)")))
                Spacer(Modifier.height(8.dp))
                Text(
                    if (s.debt7Min >= 60) "${Fmt.duration(s.debt7Min)} short of your ${Fmt.duration(s.needMin)} need over the last 7 nights." else "No meaningful sleep debt this week.",
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
                val spread = listOfNotNull(s.bedtimeSdMin, s.wakeSdMin).maxOrNull()?.let { "±${it.roundToInt()} min" }
                CardHeader("Rhythm", Icons.Rounded.Schedule, h.sleep, trailing = adv.sri.value?.let { "${it.roundToInt()} / 100" } ?: spread, chevron = true)
                Spacer(Modifier.height(12.dp))
                SleepWindows(
                    windows, dates.map { "${Fmt.weekday(it)}\n${it.dayOfMonth}" }, h.sleep,
                    targetBed = s.suggestedBedtime?.let { (it.hour * 60 + it.minute - 18 * 60).let { v -> if (v < 0) v + 1440 else v }.toFloat() },
                    targetLabel = s.suggestedBedtime?.let { "Bed by ${InsightEngine.fmtTime(it)}" },
                    tooltip = { i -> mains[dates[i]]?.let { n -> "${Fmt.weekdayShort(dates[i])} ${Fmt.dayMonth(dates[i])}\n${Fmt.clock(n.start, zone)} → ${Fmt.clock(n.end, zone)}" } ?: "" },
                )
                Spacer(Modifier.height(10.dp))
                ChartLegend(listOfNotNull(
                    LegendKey(KeyKind.Bar, h.sleep, "Fell asleep → woke up (tap a night)"),
                    s.suggestedBedtime?.let { LegendKey(KeyKind.Dash, h.sleep, "Tonight's bedtime") },
                ))
                Spacer(Modifier.height(10.dp))
                adv.sri.value?.let { v ->
                    Text(
                        "Regularity ${v.roundToInt()} / 100 · " + when (adv.sri.level) { Level.Great -> "regular"; Level.Good -> "fairly regular"; else -> "irregular" } +
                            ". About ${hoursDifferent(v)} of each day differs from the day before (83+ is regular).",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (spread != null) Text("Bedtime moves $spread night to night.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    s.suggestedBedtime?.let { "Tonight, aim to be in bed by ${InsightEngine.fmtTime(it)} to get your ${Fmt.duration(s.needMin)} before your usual wake time." } ?: "A few more nights are needed to suggest a bedtime.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item {
            val need = adv.need
            val t = adv.timing
            SectionCard(onClick = { onMetric(Metric.SleepNeed) }) {
                CardHeader("Need & timing", Icons.Rounded.Bedtime, h.sleep, chevron = true)
                Spacer(Modifier.height(14.dp))
                Row {
                    KeyValue(if (need.estimated) "Free-day sleep" else "Need (setting)", Fmt.duration(need.needMin), Modifier.weight(1f))
                    KeyValue("Midpoint", t.midpointMin?.let { m -> ((m.roundToInt() % 1440 + 1440) % 1440).let { InsightEngine.fmtTime(LocalTime.of(it / 60, it % 60)) } } ?: "—", Modifier.weight(1f))
                    KeyValue("Weekends", t.socialJetLagMin?.let { j -> if (abs(j) < 30) "Same" else "${if (j > 0) "+" else "−"}${Fmt.duration(abs(j).roundToInt())}" } ?: "—", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    when (need.trend) {
                        DebtTrend.Easing -> "Your 7-night shortfall is easing compared with the week before."
                        DebtTrend.Steady -> "Your 7-night shortfall is about the same as the week before."
                        DebtTrend.Building -> "Your 7-night shortfall is building. An earlier night or two will help."
                        DebtTrend.Unknown -> if (need.estimated) "Estimated from your free-day sleep over the last two weeks." else "Baseline estimates your need once it has a few free-day nights."
                    } + if (need.estimated && abs(need.needMin - s.needMin) >= 20) " On free days you sleep about ${Fmt.duration(need.needMin)}; the target in Settings is ${Fmt.duration(s.needMin)}." else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun pctOf(part: Int?, whole: Int?): Int? =
    if (part == null || whole == null || whole <= 0) null else (part * 100.0 / whole).roundToInt()
