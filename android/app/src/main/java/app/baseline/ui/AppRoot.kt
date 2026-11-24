package app.baseline.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import app.baseline.ui.common.ExpressiveLoading
import app.baseline.ui.theme.Motion
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.baseline.AppViewModel
import app.baseline.ui.activity.ActivityScreen
import app.baseline.ui.common.EmptyState
import app.baseline.ui.onboarding.ConnectScreen
import app.baseline.ui.settings.SettingsScreen
import app.baseline.ui.sleep.SleepScreen
import app.baseline.ui.theme.BaselineTheme
import app.baseline.ui.today.TodayScreen
import app.baseline.ui.body.BodyScreen
import app.baseline.ui.story.Metric
import app.baseline.ui.story.StoryScreen
import app.baseline.labs.ui.BodyCompScreen
import app.baseline.labs.ui.LabsScreen

private data class Tab(val label: String, val icon: ImageVector, val selected: ImageVector)

private val tabs = listOf(
    Tab("Today", Icons.Outlined.WbSunny, Icons.Rounded.WbSunny),
    Tab("Sleep", Icons.Outlined.Bedtime, Icons.Rounded.Bedtime),
    Tab("Body", Icons.Outlined.FavoriteBorder, Icons.Rounded.Favorite),
    Tab("Move", Icons.Outlined.DirectionsRun, Icons.Rounded.DirectionsRun),
)

@Composable
fun AppRoot(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings
    BaselineTheme(mode = s?.theme ?: "system", dynamic = s?.dynamicColor ?: true) {
        when {
            s == null -> Box(Modifier.fillMaxSize())
            !s.onboarded -> {
                val pairing by vm.pairing.collectAsStateWithLifecycle()
                ConnectScreen(vm, initialUrl = pairing?.first ?: s.serverUrl, initialToken = pairing?.second.orEmpty(), onBack = null)
            }
            else -> androidx.compose.runtime.CompositionLocalProvider(
                app.baseline.ai.LocalAiHidden provides app.baseline.ai.AiHidden(s.aiHidden) { title, hide ->
                    vm.update { it.copy(aiHidden = if (hide) it.aiHidden + title else it.aiHidden - title) }
                },
            ) { Main(vm) }
        }
    }
}

@Composable
private fun Main(vm: AppViewModel) {
    val model by vm.model.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showConnect by rememberSaveable { mutableStateOf(false) }
    var story by rememberSaveable { mutableStateOf<Metric?>(null) }
    var showLabs by rememberSaveable { mutableStateOf(false) }
    var showBodyComp by rememberSaveable { mutableStateOf(false) }
    var showHealthspan by rememberSaveable { mutableStateOf(false) }
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    LaunchedEffect(pairing) { if (pairing != null) { showConnect = true } }
    val requested by vm.requestedTab.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        requested?.let { tab = it.coerceIn(0, 3); story = null; showLabs = false; showBodyComp = false; showHealthspan = false; showSettings = false; showConnect = false; vm.consumeTab() }
    }

    BackHandler(enabled = showSettings || showConnect || showLabs || showBodyComp || showHealthspan || story != null || tab != 0) {
        when {
            showConnect -> showConnect = false
            showSettings -> showSettings = false
            showLabs -> showLabs = false
            showBodyComp -> showBodyComp = false
            showHealthspan -> showHealthspan = false
            story != null -> story = null
            else -> tab = 0
        }
    }

    AnimatedContent(
        targetState = when { showConnect -> 6; showSettings -> 5; showLabs -> 4; showBodyComp -> 3; showHealthspan -> 2; story != null -> 1; else -> 0 },
        transitionSpec = {
            if (targetState > initialState) (slideInHorizontally(Motion.spatial()) { it / 4 } + fadeIn(Motion.effects())) togetherWith
                (scaleOut(Motion.spatial(), targetScale = .94f) + fadeOut(Motion.fastEffects()))
            else (scaleIn(Motion.spatial(), initialScale = .94f) + fadeIn(Motion.effects())) togetherWith
                (slideOutHorizontally(Motion.spatial()) { it / 4 } + fadeOut(Motion.fastEffects()))
        },
        label = "overlay",
    ) { layer ->
        when (layer) {
            6 -> ConnectScreen(
                vm, initialUrl = pairing?.first ?: vm.settings.value?.serverUrl.orEmpty(), initialToken = pairing?.second.orEmpty(),
                onBack = { showConnect = false; vm.pairing.value = null }, onDone = { showConnect = false; showSettings = false; vm.pairing.value = null },
            )
            5 -> SettingsScreen(vm, onBack = { showSettings = false }, onConnect = { showConnect = true })
            4 -> LabsScreen(onBack = { showLabs = false }, onOpenSettings = { showSettings = true })
            3 -> BodyCompScreen(onBack = { showBodyComp = false }, onOpenSettings = { showSettings = true })
            2 -> model?.let { m ->
                app.baseline.ui.healthspan.HealthspanScreen(
                    m, onBack = { showHealthspan = false }, onOpenSettings = { showSettings = true }, onLabs = { showLabs = true },
                    onMetric = { story = it; showHealthspan = false },
                )
            } ?: Box(Modifier.fillMaxSize())
            1 -> model?.let { m -> story?.let { StoryScreen(m, it, onBack = { story = null }, onOpenSettings = { showSettings = true }) } } ?: Box(Modifier.fillMaxSize())
            else -> Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        tabs.forEachIndexed { i, t ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { Icon(if (tab == i) t.selected else t.icon, null) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                },
            ) { pad ->
                val m = model
                val inner = PaddingValues(bottom = pad.calculateBottomPadding())
                if (m == null) {
                    Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                        // A failed refresh only matters when there's no saved copy to show; otherwise keep loading.
                        if (data.error != null && data.summary == null) EmptyState(data.error!!, onRetry = { vm.refresh() }, onSettings = { showSettings = true })
                        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ExpressiveLoading(size = 64.dp)
                            Spacer(Modifier.height(20.dp))
                            Text(
                                if (data.summary != null) "Reading your data…" else "Connecting to your bridge…",
                                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize().padding(inner)) {
                        val openSettings = { showSettings = true }
                        val openMetric: (Metric) -> Unit = { story = it }
                        AnimatedContent(tab, transitionSpec = { fadeIn(Motion.effects()) togetherWith fadeOut(Motion.fastEffects()) }, label = "tab") { t ->
                            when (t) {
                                0 -> TodayScreen(
                                    m, onRefresh = { vm.pullToRefresh() }, onSettings = openSettings, onMetric = openMetric, onTab = { tab = it },
                                    onSync = { vm.syncNow() }, onOpenZepp = { vm.syncOnReturn = true },
                                )
                                1 -> SleepScreen(m, onRefresh = { vm.pullToRefresh() }, onMetric = openMetric)
                                2 -> BodyScreen(m, onRefresh = { vm.pullToRefresh() }, onMetric = openMetric, onLabs = { showLabs = true }, onBodyComp = { showBodyComp = true }, onHealthspan = { showHealthspan = true })
                                else -> ActivityScreen(m, onRefresh = { vm.pullToRefresh() }, onMetric = openMetric)
                            }
                        }
                    }
                }
            }
        }
    }
}
