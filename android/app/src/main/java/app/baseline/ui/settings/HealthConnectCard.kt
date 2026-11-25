package app.baseline.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.baseline.AppViewModel
import app.baseline.BaselineApp
import app.baseline.data.AppSettings
import app.baseline.health.HealthConnectSync
import app.baseline.ui.common.Fmt
import app.baseline.ui.common.SectionCard
import kotlinx.coroutines.launch

private val HISTORY = listOf(30 to "30 days", 90 to "90 days", 365 to "1 year")

/** Settings → Health Connect: copy Zepp's data in, as the one source, and help switch the Zepp app's own copy off. */
@Composable
fun HealthConnectCard(vm: AppViewModel, s: AppSettings, bridgeHasSamples: Boolean, onCheck: () -> Unit) {
    val ctx = LocalContext.current
    val sync = (ctx.applicationContext as BaselineApp).healthSync
    val status by sync.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val available = remember { HealthConnectSync.available(ctx) }
    var granted by remember { mutableStateOf<Boolean?>(null) }
    var zeppWrites by remember { mutableStateOf(emptyList<String>()) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    // Re-check on every return: people flip these in Health Connect's own screens.
    LifecycleResumeEffect(Unit) {
        scope.launch { granted = sync.hasPermissions() }
        zeppWrites = if (available) HealthConnectSync.zeppWrites(ctx) else emptyList()
        onPauseOrDispose { }
    }
    LaunchedEffect(granted, s.healthConnect) {
        if (granted == false && s.healthConnect) note = "Baseline lost its Health Connect access. Turn it on again to keep copying."
    }
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { got ->
        granted = got.containsAll(HealthConnectSync.PERMISSIONS)
        if (granted == true) {
            note = null
            vm.update { it.copy(healthConnect = true) }
            scope.launch { sync.run() }
        } else {
            note = "Baseline needs every one of these to be the single source. Nothing was written."
        }
    }
    val on = s.healthConnect && granted == true

    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Copy Zepp's readings into Health Connect exactly as Zepp recorded them, so Google Health shows the same numbers.",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        val blocked = when {
            !available -> "Health Connect isn't available on this phone."
            s.demo -> "Connect your bridge first. Demo data is never written."
            !bridgeHasSamples -> "Your bridge is too old for this. Update it to 0.6 or later."
            else -> null
        }
        if (blocked != null) {
            SettingsGroup { RowShell(Icons.Rounded.Info, blocked, null, null, summaryLines = 2) {} }
            return@Column
        }
        SettingsGroup {
            ToggleRow(Icons.Rounded.MonitorHeart, "Copy Zepp data", "Heart rate, HRV, sleep, steps, workouts and more", on) { want ->
                if (want) ask.launch(HealthConnectSync.PERMISSIONS) else vm.update { it.copy(healthConnect = false) }
            }
            if (on) {
                val failed = status.lastError != null && !status.running
                RowShell(
                    when { status.running -> Icons.Rounded.Sync; failed -> Icons.Rounded.ErrorOutline; else -> Icons.Rounded.CheckCircle },
                    statusTitle(status), statusDetail(status), null, summaryLines = 3,
                    iconTint = if (failed) cs.error else cs.onSurfaceVariant,
                ) { if (status.running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(horizontal = 8.dp)) }

        if (on) {
            GroupLabel("Options")
            SettingsGroup {
                ToggleRow(
                    Icons.Rounded.Schedule, "Date HRV and SpO₂ for Google Health", "Files each night under the day you woke up",
                    s.healthConnectGoogleHrv, enabled = !status.running,
                ) { c ->
                    vm.update { it.copy(healthConnectGoogleHrv = c) }
                    scope.launch { kotlinx.coroutines.delay(300); sync.run() }
                }
                ChoiceRow(Icons.Rounded.History, "Copy history from the last", HISTORY, s.healthConnectDays) { days ->
                    if (s.healthConnectDays != days) {
                        vm.update { it.copy(healthConnectDays = days) }
                        if (days > s.healthConnectDays) scope.launch { sync.recopy() }
                    }
                }
            }
            GroupLabel("Actions")
            SettingsGroup {
                NavRow(Icons.Rounded.Sync, "Write now", "Copy anything new right away") { if (!status.running) scope.launch { sync.run() } }
                NavRow(Icons.Rounded.Search, "What's in Health Connect", "Check what Google Health will read") { onCheck() }
                RowShell(
                    Icons.Rounded.DeleteOutline, "Remove Baseline's data", "Deletes what Baseline wrote and turns copying off",
                    { if (!status.running) confirmRemove = true }, iconTint = cs.error, titleColor = cs.error,
                ) {}
            }
        }

        if (zeppWrites.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Text("The Zepp app writes here too", style = MaterialTheme.typography.titleMedium, color = cs.onSecondaryContainer)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Zepp still writes ${zeppWrites.size} kinds of data, including half-finished nights at about 5 am that Google Health keeps. " +
                            "To keep Baseline the only source, turn off Zepp's access, then delete Zepp's data in Health Connect.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { ctx.startActivity(HealthConnectSync.manageIntent(ctx, HealthConnectSync.ZEPP)) }) { Text("Zepp's access") }
                        TextButton(onClick = { ctx.startActivity(HealthConnectSync.manageIntent(ctx, null)) }) { Text("Open Health Connect") }
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove Baseline's data?") },
            text = { Text("This deletes everything Baseline wrote to Health Connect and turns copying off. Zepp's own data and your bridge are not touched. You can copy it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    vm.update { it.copy(healthConnect = false) }
                    scope.launch { note = sync.removeOurData() ?: "Removed. Nothing from Baseline is left in Health Connect." }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

private fun statusTitle(st: HealthConnectSync.Status): String = when {
    st.running -> st.progress ?: "Writing…"
    st.lastError != null -> "Last attempt failed"
    st.lastRunAt == null -> "Not written yet"
    else -> "Last written ${Fmt.ago(st.lastRunAt)}"
}

private fun statusDetail(st: HealthConnectSync.Status): String? = when {
    st.running -> null
    st.lastError != null -> st.lastError
    st.lastRunAt == null -> null
    else -> buildList {
        val parts = st.written.entries.joinToString(", ") { (k, v) -> "%,d %s".format(v, if (v == 1) k.removeSuffix("s") else k) }
        add(if (parts.isBlank()) "Nothing new" else parts.replaceFirstChar { it.uppercase() })
        st.copiedUntil?.let { add("readings up to ${Fmt.ago(it)}") }
    }.joinToString(" · ")
}
