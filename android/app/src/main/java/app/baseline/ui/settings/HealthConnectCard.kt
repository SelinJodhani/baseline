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

    SectionCard {
        Text("Health Connect", style = MaterialTheme.typography.titleMedium)
        Text(
            "Copy Zepp's readings into Health Connect exactly as Zepp recorded them, so Google Health and other apps show the same numbers as Zepp.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            !available -> {
                Spacer(Modifier.height(8.dp))
                Text("Health Connect isn't available on this phone.", style = MaterialTheme.typography.bodyMedium)
                return@SectionCard
            }
            s.demo -> {
                Spacer(Modifier.height(8.dp))
                Text("Connect your bridge first. Demo data is never written.", style = MaterialTheme.typography.bodyMedium)
                return@SectionCard
            }
            !bridgeHasSamples -> {
                Spacer(Modifier.height(8.dp))
                Text("Your bridge is too old for this. Update it to 0.6 or later.", style = MaterialTheme.typography.bodyMedium)
                return@SectionCard
            }
        }
        ListItem(
            headlineContent = { Text("Write Zepp data to Health Connect") },
            supportingContent = { Text("Heart rate, HRV, SpO₂, sleep with stages, resting heart rate, breathing rate, steps, distance, active calories, workouts and weight. A night is written once it's complete.") },
            trailingContent = {
                Switch(checked = on, onCheckedChange = { want ->
                    if (want) ask.launch(HealthConnectSync.PERMISSIONS) else vm.update { it.copy(healthConnect = false) }
                })
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        if (on) {
            Text(statusLine(status), style = MaterialTheme.typography.bodySmall,
                color = if (status.lastError != null && !status.running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            ListItem(
                headlineContent = { Text("Date HRV the way Google Health reads it") },
                supportingContent = {
                    Text(
                        "Google Health files a night's HRV under the UTC date of its first reading, so a night you fall asleep after midnight shows up on the day before. " +
                            "This moves each finished night's HRV readings a few hours later, just past midnight UTC, so Google files it under the day you woke up. " +
                            "The values stay Zepp's; other apps see these readings later than they happened.",
                    )
                },
                trailingContent = {
                    Switch(checked = s.healthConnectGoogleHrv, onCheckedChange = { c ->
                        vm.update { it.copy(healthConnectGoogleHrv = c) }
                        scope.launch { kotlinx.coroutines.delay(300); sync.run() }
                    }, enabled = !status.running)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Spacer(Modifier.height(4.dp))
            Text("How far back", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                HISTORY.forEachIndexed { i, (days, label) ->
                    SegmentedButton(
                        selected = s.healthConnectDays == days,
                        onClick = {
                            if (s.healthConnectDays != days) {
                                vm.update { it.copy(healthConnectDays = days) }
                                if (days > s.healthConnectDays) scope.launch { sync.recopy() }
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, HISTORY.size),
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { scope.launch { sync.run() } }, enabled = !status.running) { Text("Write now") }
                OutlinedButton(onClick = { confirmRemove = true }, enabled = !status.running) { Text("Remove Baseline's data") }
            }
            TextButton(onClick = onCheck) { Text("What's in Health Connect") }
        }

        if (zeppWrites.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(14.dp)) {
                    Text("The Zepp app writes here too", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Zepp still writes ${zeppWrites.size} kinds of data, including half-finished nights at about 5 am that Google Health keeps. " +
                            "So Baseline is the only source: 1) turn off all of Zepp's access, 2) in Health Connect, delete Zepp's data.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        TextButton(onClick = { ctx.startActivity(HealthConnectSync.manageIntent(ctx, HealthConnectSync.ZEPP)) }) { Text("Zepp's access") }
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

private fun statusLine(st: HealthConnectSync.Status): String = when {
    st.running -> st.progress ?: "Writing…"
    st.lastError != null -> "Last attempt failed: ${st.lastError}"
    st.lastRunAt == null -> "Not written yet."
    else -> buildString {
        append("Last written ${Fmt.ago(st.lastRunAt)}")
        val parts = st.written.entries.joinToString(", ") { (k, v) -> "%,d %s".format(v, if (v == 1) k.removeSuffix("s") else k) }
        append(if (parts.isBlank()) " · nothing new" else " · $parts")
        st.copiedUntil?.let { append(" · readings up to ${Fmt.ago(it)}") }
    }
}
