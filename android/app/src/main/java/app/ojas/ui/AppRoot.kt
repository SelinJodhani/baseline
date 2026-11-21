package app.ojas.ui

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
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ojas.AppViewModel
import app.ojas.ui.activity.ActivityScreen
import app.ojas.ui.common.EmptyState
import app.ojas.ui.onboarding.ConnectScreen
import app.ojas.ui.settings.SettingsScreen
import app.ojas.ui.sleep.SleepScreen
import app.ojas.ui.theme.OjasTheme
import app.ojas.ui.today.TodayScreen
import app.ojas.ui.trends.Metric
import app.ojas.ui.trends.TrendsScreen

private data class Tab(val label: String, val icon: ImageVector, val selected: ImageVector)

private val tabs = listOf(
    Tab("Today", Icons.Outlined.WbSunny, Icons.Rounded.WbSunny),
    Tab("Sleep", Icons.Outlined.Bedtime, Icons.Rounded.Bedtime),
    Tab("Trends", Icons.Outlined.Insights, Icons.Rounded.Insights),
    Tab("Activity", Icons.Outlined.DirectionsRun, Icons.Rounded.DirectionsRun),
)

@Composable
fun AppRoot(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings
    OjasTheme(mode = s?.theme ?: "system", dynamic = s?.dynamicColor ?: true) {
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
    var metric by rememberSaveable { mutableStateOf(Metric.Hrv) }

    BackHandler(enabled = showSettings || showConnect || tab != 0) {
        when {
            showConnect -> showConnect = false
            showSettings -> showSettings = false
            else -> tab = 0
        }
    }

    AnimatedContent(
        targetState = when { showConnect -> 2; showSettings -> 1; else -> 0 },
        transitionSpec = {
            if (targetState > initialState) (slideInHorizontally { it / 3 } + fadeIn()) togetherWith fadeOut()
            else fadeIn() togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
        },
        label = "overlay",
    ) { layer ->
        when (layer) {
            2 -> ConnectScreen(vm, initialUrl = vm.settings.value?.serverUrl.orEmpty(), onBack = { showConnect = false }, onDone = { showConnect = false; showSettings = false })
            1 -> SettingsScreen(vm, onBack = { showSettings = false }, onConnect = { showConnect = true })
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
                        val openMetric: (Metric) -> Unit = { metric = it; tab = 2 }
                        when (tab) {
                            0 -> TodayScreen(m, onRefresh = { vm.refresh() }, onSettings = openSettings, onMetric = openMetric, onTab = { tab = it })
                            1 -> SleepScreen(m, onRefresh = { vm.refresh() })
                            2 -> TrendsScreen(m, metric = metric, onMetric = { metric = it }, onRefresh = { vm.refresh() })
                            else -> ActivityScreen(m, onRefresh = { vm.refresh() })
                        }
                    }
                }
            }
        }
    }
}
