package app.ojas.ui.today

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sick
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ojas.Model
import app.ojas.data.Source
import app.ojas.insight.Focus
import app.ojas.insight.Level
import app.ojas.ui.charts.Gauge
import app.ojas.ui.charts.IntradayChart
import app.ojas.ui.charts.Ring
import app.ojas.ui.charts.Sparkline
import app.ojas.ui.charts.StackBar
import app.ojas.ui.common.CardHeader
import app.ojas.ui.common.Fmt
import app.ojas.ui.common.LevelPill
import app.ojas.ui.common.RefreshablePage
import app.ojas.ui.common.SectionCard
import app.ojas.ui.common.StatTile
import app.ojas.ui.common.TileRow
import app.ojas.ui.theme.Numeric
import app.ojas.ui.theme.Ojas
import app.ojas.ui.trends.Metric
import java.time.ZonedDateTime
import kotlin.math.roundToInt

@Composable
fun TodayScreen(m: Model, onRefresh: () -> Unit, onSettings: () -> Unit, onMetric: (Metric) -> Unit, onTab: (Int) -> Unit) {
    val i = m.insights
    RefreshablePage(m.data.refreshing, onRefresh) {
        item { Header(m, onSettings) }
        item { ReadinessCard(m) }
        item { FocusCard(i.focus) }
        item { LastNightCard(m) { onTab(1) } }
        item { MovementCard(m) { onTab(3) } }
        item { SignalsGrid(m, onMetric) }
        item { HeartToday(m) }
        item { Footer(m) }
    }
}

@Composable
private fun Header(m: Model, onSettings: () -> Unit) {
    val hour = ZonedDateTime.now(m.insights.zone).hour
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Hello, night owl" }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(Fmt.fullDate(m.insights.today), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(greeting, style = MaterialTheme.typography.headlineLarge)
        }
        FilledTonalIconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
    }
    Spacer(Modifier.height(6.dp))
    val (text, level) = when {
        m.data.source == Source.Demo -> "Demo data — fictional person" to Level.Unknown
        m.data.error != null -> "Offline · ${m.data.error}" to Level.Fair
        else -> {
            val last = m.engine.let { m.data.summary?.freshness?.lastReadingAt }
            "Latest reading ${Fmt.ago(last)}" to Level.Good
        }
    }
    LevelPill(text, level)
}

@Composable
private fun ReadinessCard(m: Model) {
    val r = m.insights.readiness
    val h = Ojas.health
    SectionCard(container = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val shown = r.score ?: r.zeppScore
            Gauge(
                fraction = shown?.let { it / 100f },
                colors = listOf(h.low, h.fair, h.good, h.great),
                modifier = Modifier.size(148.dp),
                stroke = 14.dp,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(shown?.toString() ?: "—", style = MaterialTheme.typography.displayMedium.merge(Numeric), fontWeight = FontWeight.Bold)
                    Text(if (r.score == null && r.zeppScore != null) "Zepp" else "Readiness", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(r.headline, style = MaterialTheme.typography.headlineSmall, color = h.of(r.level).takeIf { r.level != Level.Unknown } ?: MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (r.calibratingNights != null) "${r.calibratingNights} of 14 nights collected" else "vs your 60-day normal",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (r.calibratingNights != null) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { r.calibratingNights / 14f }, modifier = Modifier.fillMaxWidth(), strokeCap = StrokeCap.Round)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        r.contributors.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp)) { app.ojas.ui.charts.Dot(h.of(c.level), 10.dp) }
                Spacer(Modifier.width(10.dp))
                Text(c.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(88.dp))
                Text(c.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FocusCard(f: Focus) {
    val icon = when (f.kind) {
        Focus.Kind.Rest -> Icons.Rounded.SelfImprovement
        Focus.Kind.Sleep -> Icons.Rounded.Bedtime
        Focus.Kind.Train -> Icons.Rounded.FitnessCenter
        Focus.Kind.Move -> Icons.Rounded.DirectionsWalk
        Focus.Kind.Steady -> Icons.Rounded.Spa
        Focus.Kind.Calibrate -> Icons.Rounded.Timelapse
        Focus.Kind.Sick -> Icons.Rounded.Sick
    }
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (f.kind) {
        Focus.Kind.Sick -> cs.errorContainer to cs.onErrorContainer
        Focus.Kind.Rest, Focus.Kind.Sleep -> cs.tertiaryContainer to cs.onTertiaryContainer
        else -> cs.primaryContainer to cs.onPrimaryContainer
    }
    SectionCard(container = bg) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text("TODAY'S FOCUS", style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = .7f))
                Text(f.title, style = MaterialTheme.typography.titleLarge, color = fg)
                Spacer(Modifier.height(4.dp))
                Text(f.body, style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = .9f))
            }
        }
    }
}

@Composable
private fun LastNightCard(m: Model, onOpen: () -> Unit) {
    val s = m.insights.sleep
    val h = Ojas.health
    val zone = m.insights.zone
    SectionCard(onClick = onOpen) {
        CardHeader("Last night", Icons.Rounded.Hotel, h.sleep, chevron = true)
        Spacer(Modifier.height(14.dp))
        val last = s.last
        if (last == null) {
            Text("No sleep recorded for last night yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Fmt.duration(s.asleepMin), style = MaterialTheme.typography.displaySmall.merge(Numeric), fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text("of ${Fmt.duration(s.needMin)} need", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text("${Fmt.clock(last.start, zone)} → ${Fmt.clock(last.end, zone)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        StackBar(
            listOf(
                (last.deepMin ?: 0).toFloat() to h.deep, (last.lightMin ?: 0).toFloat() to h.light,
                (last.remMin ?: 0).toFloat() to h.rem, (last.awakeMin ?: 0).toFloat() to h.awake,
            ),
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LevelPill(if (s.debt7Min > 0) "${Fmt.duration(s.debt7Min)} debt this week" else "No sleep debt", if (s.debt7Min >= 180) Level.Fair else if (s.debt7Min > 60) Level.Good else Level.Great)
            LevelPill(
                when (s.consistency) { Level.Great -> "Very consistent"; Level.Good -> "Consistent"; Level.Fair -> "Irregular"; Level.Low -> "Very irregular"; else -> "Consistency —" },
                s.consistency,
            )
        }
        s.suggestedBedtime?.let {
            Spacer(Modifier.height(10.dp))
            Text("Tonight: in bed by ${app.ojas.insight.InsightEngine.fmtTime(it)} to wake ~${s.typicalWake?.let { w -> app.ojas.insight.InsightEngine.fmtTime(w) }} rested",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MovementCard(m: Model, onOpen: () -> Unit) {
    val a = m.insights.activity
    val h = Ojas.health
    SectionCard(onClick = onOpen) {
        CardHeader("Movement", Icons.Rounded.DirectionsWalk, h.steps, chevron = true)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val steps = a.stepsToday ?: 0
            Ring(steps / a.stepGoal.toFloat(), h.steps, Modifier.size(96.dp), stroke = 10.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%,d".format(steps), style = MaterialTheme.typography.titleLarge.merge(Numeric), fontWeight = FontWeight.Bold)
                    Text("steps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text("Active minutes · 7 days", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${a.activeWeek}", style = MaterialTheme.typography.headlineSmall.merge(Numeric), fontWeight = FontWeight.Bold)
                    Text(" / ${a.activeGoal}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (a.activeWeek / a.activeGoal.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp), color = h.great, strokeCap = StrokeCap.Round,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, gapSize = 0.dp, drawStopIndicator = {},
                )
                a.paiWeek?.let {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.EmojiEvents, null, Modifier.size(16.dp), tint = if (it >= 100) h.great else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text("PAI ${it.roundToInt()} / 100", style = MaterialTheme.typography.labelLarge.merge(Numeric))
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalsGrid(m: Model, onMetric: (Metric) -> Unit) {
    val i = m.insights
    val h = Ojas.health
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Body signals", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        TileRow {
            StatTile(
                "HRV", i.hrv.today?.roundToInt()?.toString() ?: "—", "ms", Modifier.weight(1f),
                caption = i.hrv.baseline?.let { "Normal ${it.low.roundToInt()}–${it.high.roundToInt()}" } ?: "Building baseline",
                level = i.hrv.level, onClick = { onMetric(Metric.Hrv) },
            ) { Sparkline(i.hrv.trend.takeLast(21), h.hrv, Modifier.fillMaxWidth().height(36.dp), band = i.hrv.baseline?.let { it.low..it.high }) }
            StatTile(
                "Resting HR", i.rhr.today?.roundToInt()?.toString() ?: "—", "bpm", Modifier.weight(1f),
                caption = i.rhr.baseline?.let { "Normal ${it.low.roundToInt()}–${it.high.roundToInt()}" } ?: "Building baseline",
                level = i.rhr.level, onClick = { onMetric(Metric.RestingHr) },
            ) { Sparkline(i.rhr.trend.takeLast(21), h.heart, Modifier.fillMaxWidth().height(36.dp), band = i.rhr.baseline?.let { it.low..it.high }) }
        }
        TileRow {
            StatTile(
                "Breathing", Fmt.one(i.resp.today), "/min", Modifier.weight(1f),
                caption = i.resp.baseline?.let { "Usual ${"%.1f".format(it.mean)}" } ?: "While asleep",
                level = i.resp.level, onClick = { onMetric(Metric.Breathing) },
            ) { Sparkline(i.resp.trend.takeLast(21), h.oxygen, Modifier.fillMaxWidth().height(36.dp)) }
            StatTile(
                "Stress", Fmt.int(i.stressToday), null, Modifier.weight(1f),
                caption = i.stress7?.let { "7-day avg ${it.roundToInt()}" },
                level = i.stressToday?.let { if (it < 25) Level.Great else if (it < 40) Level.Good else if (it < 60) Level.Fair else Level.Low } ?: Level.Unknown,
                onClick = { onMetric(Metric.Stress) },
            ) { Sparkline(m.engine.series(21) { it.stress }, h.stress, Modifier.fillMaxWidth().height(36.dp)) }
        }
        if (i.spo2Score != null || i.activity.vo2max != null) TileRow {
            if (i.spo2Score != null || i.odi != null) StatTile(
                "Night breathing", when (i.nightBreathingLevel) { Level.Good, Level.Great -> "Normal"; Level.Fair -> "Watch"; Level.Low -> "Check"; else -> "—" }, null, Modifier.weight(1f),
                caption = listOfNotNull(i.spo2Score?.let { "SpO₂ score ${it.roundToInt()}" }, i.odi?.let { "ODI ${"%.1f".format(it)}/h" }).joinToString(" · "),
                level = i.nightBreathingLevel, onClick = { onMetric(Metric.Spo2) },
            )
            if (i.activity.vo2max != null) StatTile(
                "VO₂ max", Fmt.one(i.activity.vo2max), null, Modifier.weight(1f),
                caption = i.activity.vo2Trend?.let { "%+.1f vs a month ago".format(it) } ?: "Cardio fitness",
                onClick = { onMetric(Metric.Vo2) },
            )
        }
    }
}

@Composable
private fun HeartToday(m: Model) {
    val zone = m.insights.zone
    val today = m.insights.today
    val from = today.atStartOfDay(zone).toEpochSecond()
    val rows = m.data.summary?.hourly?.heartRate.orEmpty()
    val todays = rows.filter { it.firstOrNull()?.let { t -> t >= from } == true }
    if (todays.isEmpty()) return
    val h = Ojas.health
    SectionCard {
        CardHeader("Heart rate today", Icons.Rounded.MonitorHeart, h.heart,
            trailing = "${todays.minOf { it[1] }.roundToInt()}–${todays.maxOf { it[3] }.roundToInt()} bpm")
        Spacer(Modifier.height(12.dp))
        IntradayChart(rows, from, from + 86400, h.heart, { Fmt.hour(it, zone) })
    }
}

@Composable
private fun Footer(m: Model) {
    val s = m.data.summary ?: return
    val txt = buildString {
        append("Library updated ${Fmt.ago(s.freshness.libraryModifiedAt)}")
        append(" · checked ${Fmt.ago(m.data.fetchedAt)}")
        s.bridgeVersion?.let { append(" · bridge $it") }
    }
    Text(txt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(8.dp))
}
