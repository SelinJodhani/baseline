package app.baseline.ui.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import app.baseline.ui.common.StatusBarScrim
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

/** Settings: one short list; anything with more than a switch opens its own page. */
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onConnect: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val s = settings ?: return
    val ctx = LocalContext.current
    var confirmSignOut by remember { mutableStateOf(false) }
    var showHcCheck by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != null && !showHcCheck) { page = null }
    if (showHcCheck) {
        HealthConnectCheckScreen(vm, onBack = { showHcCheck = false })
        return
    }
    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))

    when (page) {
        "bridge" -> SettingsPage("Bridge", onBack = { page = null }) {
            item {
                SectionCard {
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
        }
        "hc" -> SettingsPage("Health Connect", onBack = { page = null }) {
            item { HealthConnectCard(vm, s, bridgeHasSamples = data.summary?.capabilities?.samples == true, onCheck = { showHcCheck = true }) }
        }
        "goals" -> SettingsPage("Goals", onBack = { page = null }) {
            item {
                SectionCard {
                    Spacer(Modifier.height(4.dp))
                    Text("Used for sleep debt, bedtime suggestions and progress rings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    SliderSetting("Sleep need", Fmt.duration(s.sleepNeedMin), s.sleepNeedMin.toFloat(), 360f..600f, 15) { v -> vm.update { it.copy(sleepNeedMin = v) } }
                    SliderSetting("Daily steps", "%,d".format(s.stepGoal), s.stepGoal.toFloat(), 3000f..15000f, 500) { v -> vm.update { it.copy(stepGoal = v) } }
                    SliderSetting("Weekly active minutes", "${s.weeklyActiveGoal} min", s.weeklyActiveGoal.toFloat(), 75f..450f, 15) { v -> vm.update { it.copy(weeklyActiveGoal = v) } }
                }
            }
        }
        "ai" -> SettingsPage("AI", onBack = { page = null }) {
            item {
                var key by remember(s.geminiKey) { mutableStateOf(s.geminiKey) }
                var model by remember(s.geminiModel) { mutableStateOf(s.geminiModel) }
                var docModel by remember(s.geminiDocModel) { mutableStateOf(s.geminiDocModel) }
                var whatsSent by remember { mutableStateOf(false) }
                SectionCard {
                    SettingRow("Use AI", "Optional · Google Gemini, with your own key") {
                        Switch(checked = s.aiEnabled, onCheckedChange = { c -> vm.update { it.copy(aiEnabled = c) } })
                    }
                    if (s.aiEnabled) {
                        Spacer(Modifier.height(8.dp))
                        SettingRow("Daily brief on Today", "Written once a day. Close it on Today to hide it until tomorrow.") {
                            Switch(checked = s.briefOnToday, onCheckedChange = { c -> vm.update { it.copy(briefOnToday = c, briefHiddenOn = "") } })
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = key, onValueChange = { key = it }, label = { Text("Gemini API key") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = docModel, onValueChange = { docModel = it }, label = { Text("Model for reading reports") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Row {
                            TextButton(onClick = { open("https://aistudio.google.com/apikey") }) { Text("Get a free key") }
                            Spacer(Modifier.weight(1f))
                            FilledTonalButton(
                                onClick = { vm.update { it.copy(geminiKey = key.trim(), geminiModel = model.trim().ifBlank { "gemini-3.5-flash-lite" }, geminiDocModel = docModel.trim().ifBlank { "gemini-3.6-flash" }) }; app.baseline.ai.BriefService.clear(ctx); app.baseline.ai.MetricExplainer.clear(ctx) },
                                enabled = key.trim() != s.geminiKey || model.trim() != s.geminiModel || docModel.trim() != s.geminiDocModel,
                            ) { Text("Save") }
                        }
                    }
                    TextButton(onClick = { whatsSent = !whatsSent }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(if (whatsSent) "Hide what's sent" else "What's sent to Gemini") }
                    if (whatsSent) Text(
                        "When on, once a day Baseline sends Gemini a short text summary of the numbers you see on Today (scores, averages, targets) — never raw readings, times or anything that identifies you — and shows a three-sentence brief. Explanations on metric pages and lab results are sent only when you tap them. A lab report or InBody sheet you upload is sent once to read its values; only the values are kept, encrypted on this phone. Google's API terms apply to what is sent.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        "you" -> SettingsPage("About you", onBack = { page = null }) {
            item {
                // About you (optional): birth year + sex for age-based step goals and VO₂max percentiles. Stays on the device.
                var year by remember(s.birthYear) { mutableStateOf(s.birthYear?.toString() ?: "") }
                val parsed = year.trim().toIntOrNull()?.takeIf { it in 1900..java.time.LocalDate.now().year - 13 }
                SectionCard {
                    Text("Used only for age-based step goals and how your VO₂max compares with your age group. It stays on this phone and is never sent anywhere.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = year, onValueChange = { v -> year = v.filter(Char::isDigit).take(4) }, label = { Text("Birth year") }, singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            isError = year.isNotBlank() && parsed == null, modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.padding(start = 10.dp))
                        TextButton(onClick = { vm.update { it.copy(birthYear = parsed) } }, enabled = (year.isBlank() || parsed != null) && parsed != s.birthYear) { Text("Save") }
                    }
                    Spacer(Modifier.height(12.dp))
                    val sexes = listOf("female" to "Female", "male" to "Male", "" to "Not set")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        sexes.forEachIndexed { i, (k, l) ->
                            SegmentedButton(selected = s.sex == k, onClick = { vm.update { it.copy(sex = k) } }, shape = SegmentedButtonDefaults.itemShape(i, sexes.size)) { Text(l) }
                        }
                    }
                }
            }
        }
        "widgets" -> SettingsPage("Widgets", onBack = { page = null }) {
            item {
                SectionCard {
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
        }
        "updates" -> SettingsPage("Updates", onBack = { page = null }) {
            item { SectionCard { app.baseline.update.UpdateSettings() } }
        }
        "about" -> SettingsPage("About", onBack = { page = null }) {
            item {
                SectionCard {
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
        else -> SettingsPage("Settings", onBack = onBack) {
            item {
                SettingsGroup {
                    NavRow(Icons.Rounded.Dns, "Bridge", when {
                        s.demo -> "Demo data"
                        data.error != null -> "Can't reach it right now"
                        else -> "Connected · bridge ${data.summary?.bridgeVersion ?: "?"}"
                    }) { page = "bridge" }
                    NavRow(Icons.Rounded.MonitorHeart, "Health Connect", if (s.healthConnect) "Copying Zepp's data" else "Off") { page = "hc" }
                }
            }
            item { GroupLabel("Today") }
            item {
                SettingsGroup {
                    NavRow(Icons.Rounded.Flag, "Goals", "${Fmt.duration(s.sleepNeedMin).removeSuffix(" 00m")} sleep · ${"%,d".format(s.stepGoal)} steps · ${s.weeklyActiveGoal} min a week") { page = "goals" }
                    ToggleRow(Icons.Rounded.SelfImprovement, "Calm mode", "Words instead of numbers on Today", s.calmMode) { c -> vm.update { it.copy(calmMode = c) } }
                    ToggleRow(Icons.Rounded.Bedtime, "Hold sleep until noon", "Sleep details wait for the afternoon", s.holdSleepScoreUntilAfternoon) { c -> vm.update { it.copy(holdSleepScoreUntilAfternoon = c) } }
                }
            }
            item { GroupLabel("Look") }
            item {
                SettingsGroup {
                    ThemeRow(s.theme) { k -> vm.update { it.copy(theme = k) } }
                    ToggleRow(Icons.Rounded.Wallpaper, "Wallpaper colours", "Match your phone's colours", s.dynamicColor) { c -> vm.update { it.copy(dynamicColor = c) } }
                }
            }
            item { GroupLabel("More") }
            item {
                SettingsGroup {
                    NavRow(Icons.Rounded.AutoAwesome, "AI", if (s.aiEnabled) "On · Google Gemini" else "Off") { page = "ai" }
                    NavRow(Icons.Rounded.Person, "About you", listOfNotNull(s.birthYear?.let { "Born $it" }, s.sex.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }).joinToString(" · ").ifBlank { "Optional" }) { page = "you" }
                    NavRow(Icons.Rounded.Widgets, "Widgets", "Add Baseline to your home screen") { page = "widgets" }
                    NavRow(Icons.Rounded.SystemUpdate, "Updates", "Version ${BuildConfig.VERSION_NAME}") { page = "updates" }
                    NavRow(Icons.Rounded.Info, "About", "How it works and the source code") { page = "about" }
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

/** A settings screen: back arrow, title, then its content. */
@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(
            contentPadding = PaddingValues(start = app.baseline.ui.common.Space.page, end = app.baseline.ui.common.Space.page, top = top + 8.dp, bottom = bottom + 32.dp),
            verticalArrangement = Arrangement.spacedBy(app.baseline.ui.common.Space.gap),
        ) {
            item { app.baseline.ui.common.PageHeader(title, onBack) }
            content()
        }
        StatusBarScrim()
    }
}

/** One card holding a group of rows, with hairlines between them. */
@Composable
internal fun SettingsGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(vertical = 6.dp), content = content)
    }
}

@Composable
internal fun RowShell(
    icon: ImageVector, title: String, summary: String?, onClick: (() -> Unit)?,
    summaryLines: Int = 1, iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = summaryLines, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
internal fun NavRow(icon: ImageVector, title: String, summary: String?, onClick: () -> Unit) =
    RowShell(icon, title, summary, onClick, summaryLines = 2) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
internal fun ToggleRow(icon: ImageVector, title: String, summary: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    RowShell(icon, title, summary, if (enabled) ({ onChange(!checked) }) else null, summaryLines = 2) { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) }

/** A row that picks one of a few options from a small menu. */
@Composable
internal fun <T> ChoiceRow(icon: ImageVector, title: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RowShell(icon, title, options.firstOrNull { it.first == selected }?.second, { open = true }) {
            Icon(Icons.Rounded.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = androidx.compose.ui.unit.DpOffset(60.dp, 0.dp)) {
            options.forEach { (k, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onPick(k); open = false }) }
        }
    }
}

@Composable
private fun ThemeRow(current: String, onPick: (String) -> Unit) {
    val modes = listOf("system" to "Same as phone", "light" to "Light", "dark" to "Dark")
    var open by remember { mutableStateOf(false) }
    Box {
        RowShell(Icons.Rounded.Palette, "Theme", modes.firstOrNull { it.first == current }?.second ?: "Same as phone", { open = true }) {}
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = androidx.compose.ui.unit.DpOffset(60.dp, 0.dp)) {
            modes.forEach { (k, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onPick(k); open = false }) }
        }
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
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** A small section label above a settings card. */
@Composable
internal fun GroupLabel(text: String) = app.baseline.ui.common.SectionLabel(text)

/** One setting: title and an optional line, full width, with a trailing control. No list-item inset. */
@Composable
internal fun SettingRow(title: String, body: String? = null, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(16.dp))
        trailing()
    }
}
