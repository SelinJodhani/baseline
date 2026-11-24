package app.baseline.ui.activity

import androidx.compose.material3.TextButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.Pool
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.SportsTennis
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.baseline.Model
import app.baseline.data.Workout
import app.baseline.ui.charts.ChartLabels
import app.baseline.ui.charts.Ring
import app.baseline.ui.charts.StackBar
import app.baseline.ui.charts.TrendChart
import app.baseline.ui.common.CardHeader
import app.baseline.ui.common.Fmt
import app.baseline.ui.common.KeyValue
import app.baseline.ui.common.LevelPill
import app.baseline.ui.common.RefreshablePage
import app.baseline.ui.common.ScreenTitle
import app.baseline.ui.common.SectionCard
import app.baseline.ui.theme.Numeric
import app.baseline.ui.theme.Brand
import app.baseline.ui.common.BigTile
import app.baseline.ui.common.CountUp
import app.baseline.ui.common.PairedTiles
import app.baseline.ui.common.SegmentRow
import app.baseline.ui.common.SegmentedColumn
import app.baseline.ui.common.ThickProgress
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import java.time.Instant
import kotlin.math.roundToInt

@Composable
fun ActivityScreen(m: Model, onRefresh: () -> Unit, onMetric: (app.baseline.ui.story.Metric) -> Unit) {
    val a = m.insights.activity
    val h = Brand.health
    val e = m.engine
    val zone = m.insights.zone
    RefreshablePage(m.data.refreshing || m.data.syncing, onRefresh) {
        item { ScreenTitle("Move", "Enough movement, built up gradually") }

        item {
            val left = (a.activeGoal - a.activeWeek).coerceAtLeast(0)
            Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable { onMetric(app.baseline.ui.story.Metric.Active) }.padding(horizontal = 4.dp, vertical = 4.dp)) {
                Text("Active this week", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.Bottom) {
                    CountUp(a.activeWeek, MaterialTheme.typography.displayLarge)
                    Spacer(Modifier.width(10.dp))
                    Text("of ${a.activeGoal} min", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp))
                }
                Spacer(Modifier.height(8.dp))
                ThickProgress(a.activeWeek / a.activeGoal.toFloat(), height = 14.dp)
                Spacer(Modifier.height(14.dp))
                Text(
                    if (left == 0) "Weekly target hit. Up to 300 min brings extra benefit." else "$left minutes to go: about ${kotlin.math.ceil(left / 30.0).toInt()} brisk 30-minute sessions.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        item {
            PairedTiles(
                { mod, shape ->
                    BigTile(
                        "Steps today", a.stepsToday?.let { "%,d".format(it) } ?: "—", mod, shape,
                        caption = "goal ${"%,d".format(a.stepGoal)}", onClick = { onMetric(app.baseline.ui.story.Metric.Steps) },
                    )
                },
                { mod, shape ->
                    val pai = a.paiWeek
                    if (pai != null) BigTile("PAI", "${pai.roundToInt()}", mod, shape, unit = "/ 100", caption = if (pai >= 100) "protective level reached" else "100 keeps your heart protected")
                    else BigTile("Workouts", "${a.workoutsWeek.size}", mod, shape, caption = "in the last 7 days")
                },
            )
        }

        item {
            val dates = e.dates(14)
            val steps = e.series(14) { it.steps }
            SectionCard(onClick = { onMetric(app.baseline.ui.story.Metric.Steps) }) {
                CardHeader("Steps, last 14 days", Icons.Rounded.DirectionsWalk, h.steps, trailing = a.steps7Avg?.let { "avg ${Fmt.int(it)}" }, chevron = true)
                Spacer(Modifier.height(12.dp))
                val kfmt: (Double) -> String = { if (it >= 1000) "%.1fk".format(it / 1000).replace(".0k", "k") else "%.0f".format(it) }
                TrendChart(
                    steps, h.steps, ChartLabels(dates.map { "${Fmt.weekday(it)}\n${it.dayOfMonth}" }, kfmt), bars = true,
                    goal = a.stepGoal.toDouble(), goalLabel = "Goal ${kfmt(a.stepGoal.toDouble())}",
                    tooltip = { i -> "${Fmt.weekdayShort(dates[i])} ${Fmt.dayMonth(dates[i])}\n${steps[i]?.let { "%,d steps".format(it.roundToInt()) } ?: "No data"}" },
                )
                Spacer(Modifier.height(8.dp))
                val hit = steps.count { (it ?: 0.0) >= a.stepGoal }
                Text("Goal reached on $hit of 14 days", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        a.load?.let { load ->
            item {
                val loads = e.dailyLoad(42)
                val dates = e.dates(42)
                SectionCard(onClick = { onMetric(app.baseline.ui.story.Metric.Load) }) {
                    CardHeader("Training load", Icons.Rounded.ShowChart, h.fair, chevron = true)
                    Spacer(Modifier.height(12.dp))
                    LevelPill(load.label, load.level)
                    Spacer(Modifier.height(12.dp))
                    TrendChart(loads.map { it.takeIf { v -> v > 0 } }, h.steps, ChartLabels(dates.map { Fmt.dayMonth(it) }) { "%.0f".format(it) }, bars = true)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        KeyValue("Fatigue (7d)", "%.0f".format(load.acute), Modifier.weight(1f))
                        KeyValue("Fitness (42d)", "%.0f".format(load.chronic), Modifier.weight(1f))
                        KeyValue("Ratio", load.ratio?.let { "%.2f".format(it) } ?: "—", Modifier.weight(1f))
                    }
                }
            }
        }

        val all = m.data.summary?.workouts.orEmpty().sortedByDescending { it.start }
        item {
            var showAll by rememberSaveable { mutableStateOf(false) }
            val recent = if (showAll) all.take(60) else all.take(6)
            Column {
                Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                    Text("Workouts", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (all.isNotEmpty()) Text("${all.size} recorded", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (recent.isEmpty()) SectionCard {
                    Text("No workouts recorded yet. Start one on your watch and it shows up here after the next sync.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SegmentedColumn(recent.size) { idx ->
                    SegmentRow(idx, recent.size, padding = 18.dp) { WorkoutRow(recent[idx], zone) }
                }
                if (all.size > 6) TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(if (showAll) "Show fewer" else "Show more (${minOf(all.size, 60) - 6} more)")
                }
            }
        }
    }
}

private fun iconFor(type: String): ImageVector = when {
    "run" in type -> Icons.Rounded.DirectionsRun
    "cycl" in type || "bike" in type -> Icons.Rounded.DirectionsBike
    "strength" in type || "gym" in type || "weight" in type -> Icons.Rounded.FitnessCenter
    "swim" in type -> Icons.Rounded.Pool
    "hik" in type -> Icons.Rounded.Hiking
    "yoga" in type || "stretch" in type -> Icons.Rounded.SelfImprovement
    "tennis" in type || "badminton" in type || "squash" in type -> Icons.Rounded.SportsTennis
    else -> Icons.Rounded.DirectionsWalk
}

@Composable
private fun WorkoutRow(w: Workout, zone: java.time.ZoneId) {
    val h = Brand.health
    val date = Instant.ofEpochSecond(w.start).atZone(zone).toLocalDate()
    val cs = MaterialTheme.colorScheme
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(iconFor(w.type), null, tint = cs.onSecondaryContainer, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(Fmt.workoutName(w.type), style = MaterialTheme.typography.titleMedium)
                Text("${Fmt.weekdayShort(date)} ${Fmt.dayMonth(date)} · ${Fmt.clock(w.start, zone)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(w.durationMin?.let { Fmt.duration(it.roundToInt()) } ?: "—", style = MaterialTheme.typography.titleLarge.merge(Numeric))
                Text(
                    listOfNotNull(w.distanceM?.takeIf { it > 0 }?.let { "${Fmt.km(it)} km" }, w.avgHr?.let { "${it.roundToInt()} bpm" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val z = w.zoneSeconds
        if (!z.isNullOrEmpty() && z.sum() > 0) {
            Spacer(Modifier.height(12.dp))
            val zoneColors = listOf(cs.outlineVariant, cs.secondary, cs.primary, cs.tertiary, cs.error)
            StackBar(z.mapIndexed { i, s -> s.toFloat() to zoneColors[i.coerceAtMost(4)] }, height = 8.dp)
        }
        val extras = listOfNotNull(
            w.trainingLoad?.let { "Load ${it.roundToInt()}" },
            w.aerobicTe?.let { "Aerobic TE ${"%.1f".format(it)}" },
            w.calories?.let { "${it.roundToInt()} kcal" },
        )
        if (extras.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(extras.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 62.dp, top = 4.dp))
        }
    }
}
