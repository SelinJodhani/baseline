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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import app.baseline.ui.common.CountUp
import app.baseline.ui.common.ExpressiveLoading
import app.baseline.ui.common.FitText
import app.baseline.ui.common.ThickProgress
import app.baseline.ui.common.MorphBlob
import app.baseline.ui.common.SegmentRow
import app.baseline.ui.common.SegmentedColumn
import app.baseline.ui.common.blobFor
import app.baseline.ui.theme.Motion
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
        item { app.baseline.update.UpdateBanner() }
        if (mode != null) item { ModeBanner(mode, m, onOff = { vm.setMode(null) }) }
        item { Hero(m, onWhy = { showWhy = true }, onFeel = { vm.setFeel(it) }, onTakeItEasy = { showModes = true }) }
        if (fresh.showCard) item { FreshnessCard(m, fresh, onSync, onOpenZepp) }
        if (m.settings.aiEnabled && m.settings.geminiKey.isNotBlank() && m.settings.briefOnToday && m.settings.briefHiddenOn != m.insights.today.toString()) item {
            BriefCard(m, onHide = { vm.update { it.copy(briefHiddenOn = m.insights.today.toString()) } })
        }
        item {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
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
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(Fmt.fullDate(m.insights.today), style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
            Text(greeting, style = MaterialTheme.typography.headlineMedium)
        }
        FilledTonalIconButton(
            onClick = onTakeItEasy, modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (m.insights.mode != null) cs.primaryContainer else cs.surfaceContainerHigh,
                contentColor = if (m.insights.mode != null) cs.onPrimaryContainer else cs.onSurfaceVariant,
            ),
        ) { Icon(m.insights.mode?.let { modeIcon(it.mode) } ?: Icons.Rounded.Spa, "Taking it easy?") }
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(
            onClick = onSettings, modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.surfaceContainerHigh, contentColor = cs.onSurfaceVariant),
        ) { Icon(Icons.Rounded.Settings, "Settings") }
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

/**
 * The verdict: one huge word on the left, the score inside a Material shape on the right. The shape (cookie, petal,
 * soft square, pill) and its tone follow the verdict and morph when it changes. Tap either for "why".
 */
@Composable
private fun Hero(m: Model, onWhy: () -> Unit, onFeel: (Feel?) -> Unit, onTakeItEasy: () -> Unit) {
    val r = m.insights.readiness
    val f = m.insights.focus
    val h = Brand.health
    val cs = MaterialTheme.colorScheme
    val numbers = m.settings.showNumbers()
    val shown = r.score ?: r.zeppScore
    val chip = readinessChip(m)
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onWhy).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (r.verb != null) "Today you're" else "Readiness",
                    style = MaterialTheme.typography.titleMedium, color = if (r.level == Level.Unknown) cs.onSurfaceVariant else h.of(r.level),
                )
                if (r.verb != null) FitText(r.verb.label, MaterialTheme.typography.displayLarge, min = 40.sp)
                else Text(r.headline, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 4.dp))
                chip?.let { QuietChip(it, Modifier.padding(top = 6.dp)) }
                FeelAgreement(m)
            }
            Spacer(Modifier.width(12.dp))
            MorphBlob(blobFor(r.verb), h.container(r.level), Modifier.size(136.dp)) {
                if (numbers && shown != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CountUp(shown, MaterialTheme.typography.displaySmall.copy(fontSize = 46.sp), color = h.onContainer(r.level))
                        if (r.score == null && r.zeppScore != null) Text("Zepp", style = MaterialTheme.typography.labelMedium, color = h.onContainer(r.level))
                    }
                } else {
                    Icon(m.insights.mode?.let { modeIcon(it.mode) } ?: focusIcon(f.kind), null, tint = h.onContainer(r.level), modifier = Modifier.size(48.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(f.title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(f.body, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onWhy, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text("Why this verdict", style = MaterialTheme.typography.labelLarge)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(10.dp))
        CheckIn(m, onFeel, onTakeItEasy)
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
    Surface(shape = MaterialTheme.shapes.large, color = cs.secondaryContainer) {
        Column(Modifier.padding(start = 22.dp, end = 10.dp, top = 14.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Your brief", style = MaterialTheme.typography.titleMedium, color = cs.onSecondaryContainer, modifier = Modifier.weight(1f))
                if (busy) ExpressiveLoading(Modifier.padding(12.dp), size = 24.dp, color = cs.onSecondaryContainer)
                else IconButton(onClick = { run() }) { Icon(Icons.Rounded.Refresh, "Regenerate", tint = cs.onSecondaryContainer) }
                IconButton(onClick = onHide) { Icon(Icons.Rounded.Close, "Hide for today", tint = cs.onSecondaryContainer) }
            }
            Column(Modifier.padding(end = 12.dp)) {
                AnimatedVisibility(brief != null || error != null) {
                    Text(
                        error ?: brief?.text.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (error != null) cs.error else cs.onSecondaryContainer,
                    )
                }
                if (brief == null && error == null && !busy) TextButton(onClick = { run() }) { Text("Write today's brief") }
                if (brief != null) Text(
                    "Hidden until tomorrow if you close it. Turn it off in Settings → AI.",
                    style = MaterialTheme.typography.labelMedium, color = cs.onSecondaryContainer.copy(alpha = .75f), modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

/** Paired tiles: mirrored corners (big outside, small where they meet). */
private val LeftTile = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp, topEnd = 8.dp, bottomEnd = 8.dp)
private val RightTile = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 28.dp, bottomEnd = 28.dp)

@Composable
private fun Tile(modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.clip(shape).clickable(onClick = onClick)) {
        Column(Modifier.padding(20.dp)) { content() }
    }
}

@Composable
private fun TileLabel(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SleepTile(m: Model, modifier: Modifier, onClick: () -> Unit) {
    val s = m.insights.sleep
    val h = Brand.health
    val cs = MaterialTheme.colorScheme
    val hold = m.settings.holdSleepDetail(localHour(m))
    Tile(modifier, LeftTile, onClick) {
        TileLabel("Sleep", Icons.Rounded.Bedtime)
        Spacer(Modifier.height(14.dp))
        when {
            hold -> {
                Text("After noon", style = MaterialTheme.typography.headlineSmall)
                Text("Last night's details wait until the afternoon", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            !m.settings.showNumbers() -> {
                Text(sleepWord(s.level), style = MaterialTheme.typography.headlineSmall)
                Text(if (s.level == Level.Unknown) "Nothing recorded yet" else "last night", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            else -> {
                FitText(Fmt.duration(s.asleepMin), MaterialTheme.typography.headlineLarge, min = 22.sp)
                Text("of ${Fmt.duration(s.needMin)} need", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (!hold) s.last?.let { l ->
            StackBar(listOf((l.deepMin ?: 0).toFloat() to h.deep, (l.lightMin ?: 0).toFloat() to h.light, (l.remMin ?: 0).toFloat() to h.rem, (l.awakeMin ?: 0).toFloat() to h.awake), height = 10.dp)
            Spacer(Modifier.height(12.dp))
        }
        Text(s.suggestedBedtime?.let { "Bed by ${InsightEngine.fmtTime(it)}" } ?: "—", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun MoveTile(m: Model, modifier: Modifier, onClick: () -> Unit) {
    val a = m.insights.activity
    val cs = MaterialTheme.colorScheme
    val numbers = m.settings.showNumbers()
    val paused = m.insights.mode != null
    Tile(modifier, RightTile, onClick) {
        TileLabel("Move", Icons.Rounded.DirectionsWalk)
        Spacer(Modifier.height(14.dp))
        if (numbers) FitText(a.stepsToday?.let { "%,d".format(it) } ?: "—", MaterialTheme.typography.headlineLarge, min = 22.sp)
        else Text(if (paused) "Paused" else stepsWord(a.stepsToday, a.stepGoal, localHour(m)), style = MaterialTheme.typography.headlineSmall)
        Text("steps of ${"%,d".format(a.stepGoal)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        ThickProgress((a.stepsToday ?: 0) / a.stepGoal.toFloat())
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                paused -> "Targets paused"
                numbers -> "${a.activeWeek} of ${a.activeGoal} active min"
                else -> activeWord(a.activeWeek, a.activeGoal)
            },
            style = MaterialTheme.typography.labelLarge.merge(Numeric), color = cs.onSurfaceVariant,
        )
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

/** Overnight signals as a segmented list with big numbers. */
@Composable
private fun Signals(m: Model, onMetric: (Metric) -> Unit) {
    val h = Brand.health
    val cs = MaterialTheme.colorScheme
    val metrics = listOf(Metric.Hrv, Metric.RestingHr, Metric.Breathing)
    val numbers = m.settings.showNumbers()
    Column {
        Text("Overnight", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 10.dp))
        SegmentedColumn(metrics.size) { i ->
            val metric = metrics[i]
            val sp = spec(metric, h)
            val n = now(m, metric, sp)
            val signal = when (metric) { Metric.Hrv -> m.insights.hrv; Metric.RestingHr -> m.insights.rhr; else -> m.insights.resp }
            val chip = signalChip(signal)
            SegmentRow(i, metrics.size, onClick = { onMetric(metric) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (n.level == Level.Unknown) cs.outlineVariant else h.of(n.level)))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(metric.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (signal.today == null) "No reading last night" else n.status,
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                        chip?.let { QuietChip(it, Modifier.padding(top = 6.dp)) }
                    }
                    if (numbers) {
                        Spacer(Modifier.width(12.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(n.value, style = MaterialTheme.typography.headlineLarge.merge(Numeric))
                            Spacer(Modifier.width(4.dp))
                            Text(n.unit, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                        }
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
    Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerHigh) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m.data.syncing) ExpressiveLoading(size = 24.dp)
                else Icon(Icons.Rounded.Sync, null, tint = cs.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    when {
                        m.data.syncing -> "Pulling from the Zepp cloud…"
                        f.stale -> "Last watch reading ${ageText(f.readingAgeMin)}"
                        else -> "Up to date"
                    },
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                m.data.syncNote ?: "Open the Zepp app so your watch syncs, then pull the latest here.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 36.dp),
            )
            if (!m.data.syncing) {
                Spacer(Modifier.height(14.dp))
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(start = 36.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    if (zeppIntent != null) {
                        OutlinedButton(onClick = { onOpenZepp(); ctx.startActivity(zeppIntent) }, modifier = Modifier.height(48.dp)) { Text("Open Zepp") }
                    }
                    if (f.canSync) {
                        Button(onClick = onSync, modifier = Modifier.height(48.dp)) { Text("Sync now") }
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
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp))
}
