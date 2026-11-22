package app.baseline.ui.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sick
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import app.baseline.AppViewModel
import app.baseline.Model
import app.baseline.ai.Brief
import app.baseline.ai.BriefService
import app.baseline.data.Feel
import app.baseline.data.Source
import app.baseline.data.holdSleepDetail
import app.baseline.data.showNumbers
import app.baseline.insight.Focus
import app.baseline.insight.InsightEngine
import app.baseline.insight.Level
import app.baseline.ui.charts.Gauge
import app.baseline.ui.charts.Ring
import app.baseline.ui.charts.StackBar
import app.baseline.ui.common.Fmt
import app.baseline.ui.common.RefreshablePage
import app.baseline.ui.story.Metric
import app.baseline.ui.story.now
import app.baseline.ui.story.spec
import app.baseline.ui.theme.Numeric
import app.baseline.ui.theme.Brand
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

@Composable
fun TodayScreen(
    m: Model, onRefresh: () -> Unit, onSettings: () -> Unit, onMetric: (Metric) -> Unit, onTab: (Int) -> Unit,
    onSync: () -> Unit = {}, onOpenZepp: () -> Unit = {},
) {
    val fresh = freshnessOf(m)
    // The activity-scoped view model (same instance AppRoot uses) for check-ins and modes.
    val vm: AppViewModel = viewModel()
    var showWhy by rememberSaveable { mutableStateOf(false) }
    var showModes by rememberSaveable { mutableStateOf(false) }
    val mode = m.insights.mode
    RefreshablePage(m.data.refreshing || m.data.syncing, onRefresh) {
        item { Header(m, onSettings, onTakeItEasy = { showModes = true }) }
        if (fresh.showCard) item { FreshnessCard(m, fresh, onSync, onOpenZepp) }
        item { app.baseline.update.UpdateBanner() }
        if (mode != null) item { ModeBanner(mode, m, onOff = { vm.setMode(null) }) }
        item { Hero(m, onWhy = { showWhy = true }, onFeel = { vm.setFeel(it) }, onTakeItEasy = { showModes = true }) }
        if (m.settings.aiEnabled && m.settings.geminiKey.isNotBlank() && m.settings.briefOnToday && m.settings.briefHiddenOn != m.insights.today.toString()) item {
            BriefCard(m, onHide = { vm.update { it.copy(briefHiddenOn = m.insights.today.toString()) } })
        }
        item {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SleepTile(m, Modifier.weight(1f).fillMaxHeight()) { onTab(1) }
                MoveTile(m, Modifier.weight(1f).fillMaxHeight()) { onTab(3) }
            }
        }
        item { Signals(m, onMetric) }
        item { Freshness(m) }
    }
    if (showWhy) WhySheet(m, onHistory = { showWhy = false; onMetric(Metric.Readiness) }, onDismiss = { showWhy = false })
    if (showModes) ModeSheet(mode, onPick = { vm.setMode(it); showModes = false }, onDismiss = { showModes = false })
}

/** Local hour in the data's timezone, for the "hold sleep until noon" option. */
private fun localHour(m: Model) = ZonedDateTime.now(m.insights.zone).hour

@Composable
private fun Header(m: Model, onSettings: () -> Unit, onTakeItEasy: () -> Unit) {
    val hour = ZonedDateTime.now(m.insights.zone).hour
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Rest well" }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(Fmt.fullDate(m.insights.today).uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(greeting, style = MaterialTheme.typography.headlineLarge)
        }
        IconButton(onClick = onTakeItEasy) {
            Icon(m.insights.mode?.let { modeIcon(it.mode) } ?: Icons.Rounded.Spa, "Taking it easy?", tint = if (m.insights.mode != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun focusIcon(k: Focus.Kind) = when (k) {
    Focus.Kind.Rest -> Icons.Rounded.SelfImprovement
    Focus.Kind.Sleep -> Icons.Rounded.Bedtime
    Focus.Kind.Train -> Icons.Rounded.FitnessCenter
    Focus.Kind.Move -> Icons.Rounded.DirectionsWalk
    Focus.Kind.Steady -> Icons.Rounded.Spa
    Focus.Kind.Calibrate -> Icons.Rounded.Timelapse
    Focus.Kind.Sick -> Icons.Rounded.Sick
}

@Composable
private fun Hero(m: Model, onWhy: () -> Unit, onFeel: (Feel?) -> Unit, onTakeItEasy: () -> Unit) {
    val r = m.insights.readiness
    val f = m.insights.focus
    val h = Brand.health
    val numbers = m.settings.showNumbers()
    val holdSleep = m.settings.holdSleepDetail(localHour(m))
    val tint = if (r.level == Level.Unknown) MaterialTheme.colorScheme.primary else h.of(r.level)
    val shown = r.score ?: r.zeppScore
    val counted by animateIntAsState(shown ?: 0, tween(1100), label = "score")
    val chip = readinessChip(m)
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(Modifier.background(tint.copy(alpha = .10f))) {
            Column(Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onWhy)) {
                    if (numbers) {
                        Gauge(shown?.let { it / 100f }, listOf(tint), Modifier.size(132.dp), stroke = 12.dp) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (shown == null) "—" else "$counted", style = MaterialTheme.typography.displayMedium.merge(Numeric), fontWeight = FontWeight.Bold)
                                Text(if (r.score == null && r.zeppScore != null) "ZEPP" else "READINESS", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        // Calm mode: the verb only, the arc a coarse picture of it.
                        Gauge(verbFraction(r.verb), listOf(tint), Modifier.size(132.dp), stroke = 12.dp) {
                            Text(
                                r.verb?.label ?: "Learning", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                                color = if (r.verb == null) MaterialTheme.colorScheme.onSurface else tint, maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        if (numbers) {
                            Text(r.headline, style = MaterialTheme.typography.headlineMedium, color = if (r.level == Level.Unknown) MaterialTheme.colorScheme.onSurface else tint)
                        }
                        chip?.let { QuietChip(it, Modifier.padding(top = 4.dp)) }
                        FeelAgreement(m)
                        Spacer(Modifier.height(10.dp))
                        r.contributors.forEach { c ->
                            val held = holdSleep && c.name == "Sleep"
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(if (held) MaterialTheme.colorScheme.outlineVariant else h.of(c.level)))
                                Spacer(Modifier.width(8.dp))
                                Text(c.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                CheckIn(m, onFeel, onTakeItEasy)
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(m.insights.mode?.let { modeIcon(it.mode) } ?: focusIcon(f.kind), null, tint = tint, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(f.title, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(f.body, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun BriefCard(m: Model, onHide: () -> Unit) {
    val ctx = LocalContext.current
    val date = m.insights.today.toString()
    var brief by remember(date) { mutableStateOf<Brief?>(BriefService.cached(ctx, date)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun run() {
        busy = true; error = null
        scope.launch {
            BriefService.generate(ctx, m.insights, m.settings.geminiKey, m.settings.geminiModel)
                .onSuccess { brief = it }.onFailure { error = it.message }
            busy = false
        }
    }
    LaunchedEffect(date) { if (brief == null) run() }
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = cs.secondaryContainer) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Your brief", style = MaterialTheme.typography.titleSmall, color = cs.onSecondaryContainer, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.onSecondaryContainer)
                else IconButton(onClick = { run() }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Refresh, "Regenerate", tint = cs.onSecondaryContainer, modifier = Modifier.size(18.dp)) }
                IconButton(onClick = onHide, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Hide for today", tint = cs.onSecondaryContainer, modifier = Modifier.size(18.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            AnimatedVisibility(brief != null || error != null) {
                Text(
                    error ?: brief?.text.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                    color = if (error != null) cs.error else cs.onSecondaryContainer,
                )
            }
            if (brief == null && error == null && !busy) TextButton(onClick = { run() }) { Text("Write today's brief") }
            if (brief != null) Text(
                "Hidden until tomorrow if you close it. Turn it off in Settings → AI.",
                style = MaterialTheme.typography.labelSmall, color = cs.onSecondaryContainer.copy(alpha = .7f), modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.clip(RoundedCornerShape(28.dp)).clickable(onClick = onClick)) {
        Column(Modifier.padding(18.dp)) { content() }
    }
}

@Composable
private fun TileLabel(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp), color = color, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SleepTile(m: Model, modifier: Modifier, onClick: () -> Unit) {
    val s = m.insights.sleep
    val h = Brand.health
    val hold = m.settings.holdSleepDetail(localHour(m))
    Tile(modifier, onClick) {
        TileLabel("Sleep", h.sleep)
        Spacer(Modifier.height(10.dp))
        when {
            hold -> {
                Text("After noon", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Last night's details wait until the afternoon", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            !m.settings.showNumbers() -> {
                Text(sleepWord(s.level), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(if (s.level == Level.Unknown) "Nothing recorded yet" else "last night", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                Text(Fmt.duration(s.asleepMin), style = MaterialTheme.typography.headlineMedium.merge(Numeric), fontWeight = FontWeight.Bold)
                Text("of ${Fmt.duration(s.needMin)} need", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (!hold) s.last?.let { l ->
            StackBar(listOf((l.deepMin ?: 0).toFloat() to h.deep, (l.lightMin ?: 0).toFloat() to h.light, (l.remMin ?: 0).toFloat() to h.rem, (l.awakeMin ?: 0).toFloat() to h.awake), height = 8.dp)
        }
        Spacer(Modifier.height(10.dp))
        Text(s.suggestedBedtime?.let { "Bed by ${InsightEngine.fmtTime(it)}" } ?: "—", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MoveTile(m: Model, modifier: Modifier, onClick: () -> Unit) {
    val a = m.insights.activity
    val h = Brand.health
    val numbers = m.settings.showNumbers()
    val paused = m.insights.mode != null
    Tile(modifier, onClick) {
        TileLabel("Move", h.steps)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ring((a.stepsToday ?: 0) / a.stepGoal.toFloat(), h.steps, Modifier.size(52.dp), stroke = 6.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                if (numbers) {
                    Text(a.stepsToday?.let { "%,d".format(it) } ?: "—", style = MaterialTheme.typography.titleLarge.merge(Numeric), fontWeight = FontWeight.Bold)
                } else {
                    Text(if (paused) "Paused" else stepsWord(a.stepsToday, a.stepGoal, localHour(m)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text("steps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        when {
            paused -> Text("Targets paused", style = MaterialTheme.typography.titleSmall)
            numbers -> Text("${a.activeWeek} / ${a.activeGoal} min", style = MaterialTheme.typography.titleSmall.merge(Numeric))
            else -> Text(activeWord(a.activeWeek, a.activeGoal), style = MaterialTheme.typography.titleSmall)
        }
        Text("active this week", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun sleepWord(l: Level) = when (l) {
    Level.Great -> "Slept well"
    Level.Good -> "Nearly enough"
    Level.Fair -> "Short night"
    Level.Low -> "Very short night"
    Level.Unknown -> "No sleep yet"
}

/** Steps against the goal, pro-rated over a 07:00–21:00 day. */
private fun stepsWord(steps: Int?, goal: Int, hour: Int): String {
    val s = steps ?: return "No steps yet"
    if (s >= goal) return "Goal reached"
    val dayShare = ((hour - 7) / 14f).coerceIn(0.15f, 1f)
    return if (s >= goal * dayShare * 0.9f) "On track" else "Behind"
}

private fun activeWord(week: Int, goal: Int) = when {
    week >= goal -> "Target met"
    week >= goal / 2 -> "On track"
    else -> "Behind"
}

@Composable
private fun Signals(m: Model, onMetric: (Metric) -> Unit) {
    val h = Brand.health
    val metrics = listOf(Metric.Hrv, Metric.RestingHr, Metric.Breathing)
    val numbers = m.settings.showNumbers()
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(vertical = 8.dp)) {
            metrics.forEachIndexed { i, metric ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                val sp = spec(metric, h)
                val n = now(m, metric, sp)
                Row(Modifier.fillMaxWidth().clickable { onMetric(metric) }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (n.level == Level.Unknown) MaterialTheme.colorScheme.outlineVariant else h.of(n.level)))
                    Spacer(Modifier.width(14.dp))
                    val signal = when (metric) { Metric.Hrv -> m.insights.hrv; Metric.RestingHr -> m.insights.rhr; else -> m.insights.resp }
                    val chip = signalChip(signal)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(metric.label, style = MaterialTheme.typography.titleSmall)
                            chip?.let { Spacer(Modifier.width(8.dp)); QuietChip(it) }
                        }
                        Text(
                            if (signal.today == null) "No reading last night" else n.status,
                            style = if (numbers) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (numbers) {
                        Text(n.value, style = MaterialTheme.typography.titleLarge.merge(Numeric), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(4.dp))
                        Text(n.unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** How current the data is, and whether a manual cloud sync makes sense right now. */
private data class FreshnessInfo(
    val readingAgeMin: Long?,
    val cloudCheckAgeMin: Long?,
    val stale: Boolean,
    val canSync: Boolean,
    val showCard: Boolean,
)

private const val STALE_AFTER_MIN = 90L

private fun freshnessOf(m: Model): FreshnessInfo {
    val s = m.data.summary
    val now = System.currentTimeMillis() / 1000
    val readingAge = s?.freshness?.lastReadingAt?.let { (now - it) / 60 }
    val checked = listOfNotNull(s?.freshness?.libraryModifiedAt, m.data.syncedAt).maxOrNull()
    val checkAge = checked?.let { (now - it) / 60 }
    val live = m.data.source == Source.Live || m.data.source == Source.Cached
    val stale = live && (readingAge == null || readingAge > STALE_AFTER_MIN)
    val canSync = live && s?.capabilities?.sync == true && !m.data.syncing &&
        (checkAge == null || checkAge * 60 >= app.baseline.SYNC_AFTER_SEC)
    return FreshnessInfo(readingAge, checkAge, stale, canSync, showCard = stale || m.data.syncing || m.data.syncNote != null)
}

private fun ageText(min: Long?): String = when {
    min == null -> "never"
    min < 2 -> "just now"
    min < 60 -> "$min min ago"
    min < 48 * 60 -> "${min / 60} h ago"
    else -> "${min / 1440} days ago"
}

/** Package names of the Zepp phone apps (global Zepp, and Zepp Life). */
private val ZEPP_PACKAGES = listOf("com.huami.watch.hmwatchmanager", "com.xiaomi.hm.health")

@Composable
private fun FreshnessCard(m: Model, f: FreshnessInfo, onSync: () -> Unit, onOpenZepp: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val zeppIntent = remember { ZEPP_PACKAGES.firstNotNullOfOrNull { ctx.packageManager.getLaunchIntentForPackage(it) } }
    Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m.data.syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Sync, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        m.data.syncing -> "Pulling from the Zepp cloud…"
                        f.stale -> "Last watch reading ${ageText(f.readingAgeMin)}"
                        else -> "Up to date"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                m.data.syncNote ?: "Your watch reaches the Zepp cloud only through the Zepp app on your phone. Open Zepp and let it sync, then pull the latest here.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            )
            if (!m.data.syncing) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (zeppIntent != null) {
                        OutlinedButton(onClick = { onOpenZepp(); ctx.startActivity(zeppIntent) }) { Text("Open Zepp") }
                        Spacer(Modifier.width(10.dp))
                    }
                    if (f.canSync) {
                        Button(onClick = onSync) { Text("Sync now") }
                    } else if (m.data.summary?.capabilities?.sync == true) {
                        Text("Zepp cloud checked ${ageText(f.cloudCheckAgeMin)}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Freshness(m: Model) {
    val s = m.data.summary ?: return
    val text = when {
        m.data.source == Source.Demo -> "Demo data · a fictional person"
        m.data.error != null -> "Offline · showing your last copy · ${m.data.error}"
        else -> "Latest reading ${Fmt.ago(s.freshness.lastReadingAt)} · Zepp cloud checked ${Fmt.ago(listOfNotNull(s.freshness.libraryModifiedAt, m.data.syncedAt).maxOrNull())}"
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
}
