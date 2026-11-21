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
import androidx.compose.material3.CircularProgressIndicator
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
            !s.onboarded -> ConnectScreen(vm, initialUrl = s.serverUrl, onBack = null)
            else -> Main(vm)
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
    val requested by vm.requestedTab.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        requested?.let { tab = it.coerceIn(0, 3); story = null; showSettings = false; showConnect = false; vm.consumeTab() }
    }

    BackHandler(enabled = showSettings || showConnect || story != null || tab != 0) {
        when {
            showConnect -> showConnect = false
            showSettings -> showSettings = false
            story != null -> story = null
            else -> tab = 0
        }
    }

    AnimatedContent(
        targetState = when { showConnect -> 3; showSettings -> 2; story != null -> 1; else -> 0 },
        transitionSpec = {
            if (targetState > initialState) (slideInHorizontally { it / 3 } + fadeIn()) togetherWith fadeOut()
            else fadeIn() togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
        },
        label = "overlay",
    ) { layer ->
        when (layer) {
            3 -> ConnectScreen(vm, initialUrl = vm.settings.value?.serverUrl.orEmpty(), onBack = { showConnect = false }, onDone = { showConnect = false; showSettings = false })
            2 -> SettingsScreen(vm, onBack = { showSettings = false }, onConnect = { showConnect = true })
            1 -> model?.let { m -> story?.let { StoryScreen(m, it, onBack = { story = null }) } } ?: Box(Modifier.fillMaxSize())
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
                        if (data.error != null) EmptyState(data.error!!, onRetry = { vm.refresh() }, onSettings = { showSettings = true })
                        else CircularProgressIndicator()
                    }
                } else {
                    Box(Modifier.fillMaxSize().padding(inner)) {
                        val openSettings = { showSettings = true }
                        val openMetric: (Metric) -> Unit = { story = it }
                        AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                            when (t) {
                                0 -> TodayScreen(m, onRefresh = { vm.refresh() }, onSettings = openSettings, onMetric = openMetric, onTab = { tab = it })
                                1 -> SleepScreen(m, onRefresh = { vm.refresh() }, onMetric = openMetric)
                                2 -> BodyScreen(m, onRefresh = { vm.refresh() }, onMetric = openMetric)
                                else -> ActivityScreen(m, onRefresh = { vm.refresh() }, onMetric = openMetric)
                            }
                        }
                    }
                }
            }
        }
    }
}
