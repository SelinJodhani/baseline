package app.baseline.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.baseline.AppViewModel
import app.baseline.health.HcCheck
import app.baseline.health.HcInspector
import app.baseline.health.HealthConnectSync
import app.baseline.ui.common.Fmt
import app.baseline.ui.common.SectionCard
import java.time.ZoneId

/** Settings → Health Connect → "What's in Health Connect": who wrote what, and whether it matches Zepp. */
@Composable
fun HealthConnectCheckScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val inspector = remember { HcInspector(ctx.applicationContext) }
    val data by vm.data.collectAsStateWithLifecycle()
    var canRead by remember { mutableStateOf<Boolean?>(null) }
    var report by remember { mutableStateOf<HcInspector.Report?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { got ->
        canRead = got.containsAll(HcInspector.READ)
        reload++
    }
    LaunchedEffect(reload) {
        if (canRead == null || reload > 0) canRead = inspector.canRead()
        if (canRead == true) {
            report = null; error = null
            runCatching { inspector.inspect(data.summary) }
                .onSuccess { report = it }
                .onFailure { error = HealthConnectSync.describe(it) }
        }
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top, bottom = bottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Text("In Health Connect", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    if (canRead == true) IconButton(onClick = { reload++ }, enabled = report != null || error != null) { Icon(Icons.Rounded.Refresh, "Check again") }
                }
            }
            when {
                canRead == false -> item {
                    SectionCard {
                        Text("Allow Baseline to look", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "To check what other apps will read, Baseline needs to read the same kinds of data it writes. It reads only while this page is open, keeps nothing, and sends nothing anywhere.",
                            style = MaterialTheme.typography.bodySmall, color = muted,
                        )
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = { ask.launch(HcInspector.READ) }) { Text("Allow reading") }
                    }
                }
                error != null -> item { SectionCard { Text("Couldn't read Health Connect: $error", color = MaterialTheme.colorScheme.error) } }
                report == null -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                else -> {
                    val r = report!!
                    r.lastNight?.let { n -> item { LastNightCard(n, inspector) } }
                    if (r.stepsToday != null || r.zeppStepsToday != null) item { StepsCard(r) }
                    if (r.others.isNotEmpty()) item { OthersCard(r, inspector, onOpen = { ctx.startActivity(HealthConnectSync.manageIntent(ctx, null)) }) }
                    item { ByAppCard(r, inspector) }
                }
            }
        }
    }
}

@Composable
private fun LastNightCard(c: HcCheck.NightCheck, inspector: HcInspector) {
    val zone = ZoneId.systemDefault()
    val n = c.night
    SectionCard {
        Text("Last night, as apps read it", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("${Fmt.clock(n.start, zone)} – ${Fmt.clock(n.end, zone)} · ${Fmt.duration(((n.end - n.start) / 60).toInt())}", style = MaterialTheme.typography.bodyLarge)
        val stages = listOf("deep", "light", "rem", "awake").mapNotNull { k -> n.stageMin[k]?.let { "${if (k == "rem") "REM" else k.replaceFirstChar(Char::uppercase)} ${Fmt.duration(it)}" } }
        Text((stages.ifEmpty { listOf("No stages") } + "from ${inspector.label(n.origin)}").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Compare("HRV during the night", c.hrvAvg?.let { "${Fmt.int(it)} ms" } ?: "—", c.zeppHrv?.let { "${Fmt.int(it)} ms" } ?: "—")
        Compare("Night", Fmt.duration(((n.end - n.start) / 60).toInt()),
            if (c.zeppStart != null && c.zeppEnd != null) Fmt.duration(((c.zeppEnd - c.zeppStart) / 60).toInt()) else "—")
        Spacer(Modifier.height(8.dp))
        Text(
            if (c.matchesZepp) "Matches Zepp." else "Differs from Zepp — another app's copy, or Zepp hasn't finished this night yet.",
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            color = if (c.matchesZepp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun StepsCard(r: HcInspector.Report) {
    SectionCard {
        Text("Steps today", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Compare("What apps read", r.stepsToday?.let { "%,d".format(it) } ?: "—", r.zeppStepsToday?.let { "%,d".format(it) } ?: "—")
        Text("Health Connect merges steps from every app by its app-priority list.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OthersCard(r: HcInspector.Report, inspector: HcInspector, onOpen: () -> Unit) {
    SectionCard(container = MaterialTheme.colorScheme.secondaryContainer) {
        Text("Other apps write here too", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        r.others.forEach { (pkg, kinds) ->
            Text("${inspector.label(pkg)}: ${kinds.joinToString(", ") { it.title.lowercase() }}", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(4.dp))
        Text("For Zepp's numbers everywhere, turn off their access to these, or put Baseline first in each data type's app priority.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onOpen) { Text("Open Health Connect") }
    }
}

@Composable
private fun ByAppCard(r: HcInspector.Report, inspector: HcInspector) {
    SectionCard {
        Text("Last ${r.days} days, by app", style = MaterialTheme.typography.titleMedium)
        Text("Read ${Fmt.ago(r.readAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (r.rows.isEmpty()) Text("Nothing in Health Connect for these kinds of data.", style = MaterialTheme.typography.bodyMedium)
        r.rows.forEachIndexed { i, (kind, apps) ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(kind.title, style = MaterialTheme.typography.bodyLarge)
            apps.forEach { (pkg, count) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(inspector.label(pkg), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                        color = if (inspector.isSelf(pkg)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    Text("%,d %s".format(count, if (count == 1) kind.unit.removeSuffix("s") else kind.unit), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Compare(label: String, hc: String, zepp: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("$hc  ·  Zepp $zepp", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
