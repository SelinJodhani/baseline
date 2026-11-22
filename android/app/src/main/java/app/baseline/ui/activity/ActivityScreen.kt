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
            SectionCard(onClick = { onMetric(app.baseline.ui.story.Metric.Active) }) {
                CardHeader("This week", Icons.Rounded.Whatshot, h.great, chevron = true)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ring(a.activeWeek / a.activeGoal.toFloat(), h.great, Modifier.size(120.dp), stroke = 12.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${a.activeWeek}", style = MaterialTheme.typography.headlineMedium.merge(Numeric), fontWeight = FontWeight.Bold)
                            Text("of ${a.activeGoal} min", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val left = (a.activeGoal - a.activeWeek).coerceAtLeast(0)
                        Text(
                            if (left == 0) "Weekly target hit. Up to 300 min brings extra benefit." else "$left active minutes to go — about ${(left / 30.0).let { kotlin.math.ceil(it).toInt() }} brisk 30-min sessions.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        a.paiWeek?.let { LevelPill("PAI ${it.roundToInt()} / 100", if (it >= 100) app.baseline.insight.Level.Great else app.baseline.insight.Level.Fair) }
                        Text("${a.workoutsWeek.size} workout${if (a.workoutsWeek.size == 1) "" else "s"} in 7 days", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            val dates = e.dates(14)
            val steps = e.series(14) { it.steps }
            SectionCard(onClick = { onMetric(app.baseline.ui.story.Metric.Steps) }) {
                CardHeader("Steps · 14 days", Icons.Rounded.DirectionsWalk, h.steps, trailing = a.steps7Avg?.let { "avg ${Fmt.int(it)}" })
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
                    TrendChart(loads.map { it.takeIf { v -> v > 0 } }, h.fair, ChartLabels(dates.map { Fmt.dayMonth(it) }) { "%.0f".format(it) }, bars = true)
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
            SectionCard {
                CardHeader("Workouts", Icons.Rounded.DirectionsRun, h.heart, trailing = if (all.isEmpty()) null else "${all.size} recorded")
                Spacer(Modifier.height(8.dp))
                if (recent.isEmpty()) {
                    Text("No workouts recorded yet. Start one on your watch and it shows up here after the next sync.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                recent.forEachIndexed { idx, w ->
                    if (idx > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    WorkoutRow(w, zone)
                }
                if (all.size > 6) TextButton(onClick = { showAll = !showAll }) {
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
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(h.heart.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                Icon(iconFor(w.type), null, tint = h.heart, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(Fmt.workoutName(w.type), style = MaterialTheme.typography.titleSmall)
                Text("${Fmt.weekdayShort(date)} ${Fmt.dayMonth(date)} · ${Fmt.clock(w.start, zone)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(w.durationMin?.let { Fmt.duration(it.roundToInt()) } ?: "—", style = MaterialTheme.typography.titleSmall.merge(Numeric))
                Text(
                    listOfNotNull(w.distanceM?.takeIf { it > 0 }?.let { "${Fmt.km(it)} km" }, w.avgHr?.let { "${it.roundToInt()} bpm" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val z = w.zoneSeconds
        if (!z.isNullOrEmpty() && z.sum() > 0) {
            Spacer(Modifier.height(8.dp))
            val zoneColors = listOf(h.light, h.great, h.good, h.fair, h.low)
            StackBar(z.mapIndexed { i, s -> s.toFloat() to zoneColors[i.coerceAtMost(4)] }, height = 6.dp)
        }
        val extras = listOfNotNull(
            w.trainingLoad?.let { "Load ${it.roundToInt()}" },
            w.aerobicTe?.let { "Aerobic TE ${"%.1f".format(it)}" },
            w.calories?.let { "${it.roundToInt()} kcal" },
        )
        if (extras.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(extras.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 52.dp))
        }
    }
}
