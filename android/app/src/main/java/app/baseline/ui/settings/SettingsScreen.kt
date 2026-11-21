package app.baseline.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.baseline.AppViewModel
import app.baseline.BuildConfig
import app.baseline.ui.common.Fmt
import app.baseline.ui.common.SectionCard
import app.baseline.ui.onboarding.SETUP_GUIDE_URL
import kotlin.math.roundToInt

private const val REPO_URL = "https://github.com/SelinJodhani/baseline"
private const val METRICS_URL = "https://github.com/SelinJodhani/baseline/blob/main/docs/METRICS.md"

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onConnect: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val s = settings ?: return
    val ctx = LocalContext.current
    var confirmSignOut by remember { mutableStateOf(false) }
    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top, bottom = bottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Text("Settings", style = MaterialTheme.typography.headlineMedium)
                }
            }
            item {
                SectionCard {
                    Text("Data source", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (s.demo) {
                        Text("Demo mode — a fictional person's data.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(s.serverUrl.ifBlank { "Not connected" }, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            data.error?.let { "Last attempt failed: $it" } ?: "Last checked ${Fmt.ago(data.fetchedAt)} · bridge ${data.summary?.bridgeVersion ?: "?"}",
                            style = MaterialTheme.typography.bodySmall, color = if (data.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = onConnect) { Text(if (s.demo || s.serverUrl.isBlank()) "Connect a bridge" else "Change") }
                        OutlinedButton(onClick = { confirmSignOut = true }) { Text(if (s.demo) "Leave demo" else "Disconnect") }
                    }
                }
            }
            item {
                SectionCard {
                    Text("Your targets", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Used for sleep debt, bedtime suggestions and progress rings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    SliderSetting("Sleep need", Fmt.duration(s.sleepNeedMin), s.sleepNeedMin.toFloat(), 360f..600f, 15) { v -> vm.update { it.copy(sleepNeedMin = v) } }
                    SliderSetting("Daily steps", "%,d".format(s.stepGoal), s.stepGoal.toFloat(), 3000f..15000f, 500) { v -> vm.update { it.copy(stepGoal = v) } }
                    SliderSetting("Weekly active minutes", "${s.weeklyActiveGoal} min", s.weeklyActiveGoal.toFloat(), 75f..450f, 15) { v -> vm.update { it.copy(weeklyActiveGoal = v) } }
                }
            }
            item {
                SectionCard {
                    Text("Widgets", style = MaterialTheme.typography.titleMedium)
                    Text("Glanceable health on your home screen — and on the lock screen where your phone supports it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    val widgets = listOf(
                        "Today" to app.baseline.widget.TodayWidgetReceiver::class.java,
                        "Today strip" to app.baseline.widget.TodayPillWidgetReceiver::class.java,
                        "Readiness" to app.baseline.widget.ReadinessWidgetReceiver::class.java,
                        "Sleep" to app.baseline.widget.SleepWidgetReceiver::class.java,
                        "Move" to app.baseline.widget.MoveWidgetReceiver::class.java,
                        "Body signals" to app.baseline.widget.SignalsWidgetReceiver::class.java,
                    )
                    val mgr = android.appwidget.AppWidgetManager.getInstance(ctx)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        widgets.forEach { (label, cls) ->
                            androidx.compose.material3.AssistChip(
                                onClick = { mgr.requestPinAppWidget(android.content.ComponentName(ctx, cls), null, null) },
                                label = { Text("Add $label") },
                                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Rounded.Add, null) },
                                enabled = mgr.isRequestPinAppWidgetSupported,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Lock screen: on Android 16+ phones and tablets that support it, long-press the lock screen (or open the hub) and add a Baseline widget.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionCard {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    val modes = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { i, (k, l) ->
                            SegmentedButton(selected = s.theme == k, onClick = { vm.update { it.copy(theme = k) } }, shape = SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(l) }
                        }
                    }
                    ListItem(
                        headlineContent = { Text("Wallpaper colours") },
                        supportingContent = { Text("Material You on Android 12+") },
                        trailingContent = { Switch(checked = s.dynamicColor, onCheckedChange = { c -> vm.update { it.copy(dynamicColor = c) } }) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            item {
                var key by remember(s.geminiKey) { mutableStateOf(s.geminiKey) }
                var model by remember(s.geminiModel) { mutableStateOf(s.geminiModel) }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("AI brief", style = MaterialTheme.typography.titleMedium)
                            Text("Optional · Google Gemini, your own key", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = s.aiEnabled, onCheckedChange = { c -> vm.update { it.copy(aiEnabled = c) } })
                    }
                    if (s.aiEnabled) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = key, onValueChange = { key = it }, label = { Text("Gemini API key") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Row {
                            TextButton(onClick = { open("https://aistudio.google.com/apikey") }) { Text("Get a free key") }
                            Spacer(Modifier.weight(1f))
                            FilledTonalButton(
                                onClick = { vm.update { it.copy(geminiKey = key.trim(), geminiModel = model.trim().ifBlank { "gemini-3.5-flash-lite" }) }; app.baseline.ai.BriefService.clear(ctx) },
                                enabled = key.trim() != s.geminiKey || model.trim() != s.geminiModel,
                            ) { Text("Save") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "When on, once a day Baseline sends Gemini a short text summary of the numbers you see on Today (scores, averages, targets) — never raw readings, times or anything that identifies you — and shows a three-sentence brief. Google's API terms apply to that text.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { SectionCard { app.baseline.update.UpdateSettings() } }
            item {
                SectionCard {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Link("How the numbers are calculated", { open(METRICS_URL) })
                    Link("Set up ZeppBridge + Baseline Bridge", { open(SETUP_GUIDE_URL) })
                    Link("Source code on GitHub", { open(REPO_URL) })
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Baseline ${BuildConfig.VERSION_NAME}. Your data stays between your server and this phone; the token is encrypted with a hardware-backed key and nothing is backed up to the cloud. Not a medical device.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(if (s.demo) "Leave demo mode?" else "Disconnect?") },
            text = { Text("This removes the server address, token and the offline copy of your data from this phone. Your bridge and ZeppBridge library are not touched.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut(); onBack() }) { Text(if (s.demo) "Leave" else "Disconnect") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SliderSetting(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, step: Int, onDone: (Int) -> Unit) {
    var v by remember(current) { mutableFloatStateOf(current) }
    val shown = (v / step).roundToInt() * step
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(if (shown == current.roundToInt()) value else when {
                label.startsWith("Sleep") -> Fmt.duration(shown)
                label.startsWith("Daily") -> "%,d".format(shown)
                else -> "$shown min"
            }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = v, onValueChange = { v = it }, valueRange = range,
            onValueChangeFinished = { onDone(shown) },
        )
    }
}

@Composable
private fun Link(text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
