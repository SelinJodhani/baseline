package app.baseline.labs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.baseline.ai.AiCard
import app.baseline.labs.Biomarkers
import app.baseline.labs.DocKind
import app.baseline.labs.LabAi
import app.baseline.labs.LabCategory
import app.baseline.labs.LabDates
import app.baseline.labs.LabFlag
import app.baseline.labs.LabMarker
import app.baseline.labs.LabReport
import app.baseline.labs.LabsData
import app.baseline.labs.LabsViewModel
import app.baseline.labs.fmtNum
import app.baseline.ui.charts.ChartLabels
import app.baseline.ui.charts.Sparkline
import app.baseline.ui.charts.TrendChart
import app.baseline.ui.common.SectionCard
import app.baseline.ui.theme.Numeric

/** Lab reports: biomarkers by category, trends across reports, and AI explanations. */
@Composable
fun LabsScreen(onBack: () -> Unit, onOpenSettings: () -> Unit, vm: LabsViewModel = viewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    val upload by vm.upload.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    vm.settings.collectAsStateWithLifecycle().value // recompose when AI settings change
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = selected != null) { selected = null }

    val key = selected
    if (key != null && data.history(key).isNotEmpty()) {
        MarkerDetail(vm, data, ai, key, onBack = { selected = null }, onOpenSettings = onOpenSettings)
        return
    }

    val latest = data.reportsByDate.lastOrNull()
    val markers = remember(data) { data.latestMarkers() }
    var deleting by remember { mutableStateOf<LabReport?>(null) }

    OverlayPage("Lab reports", "Blood tests, read by AI, kept on this phone", onBack) {
        item { AddDocument(vm, data, DocKind.Lab, "Add a report", onOpenSettings, upload) }
        if (latest == null) item {
            SectionCard {
                Text("No reports yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Add a PDF or a photo of a blood test. Baseline pulls out each result with its unit and reference range, " +
                        "groups them, and shows how they change from one test to the next.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                val out = latest.markers.count { it.labFlag == LabFlag.High || it.labFlag == LabFlag.Low || it.labFlag == LabFlag.Abnormal }
                Text(
                    "Latest ${LabDates.pretty(latest.date)} · ${latest.markers.size} results · " + if (out == 0) "all in range" else "$out outside range",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp),
                )
            }
            item {
                val k = LabAi.reportKey(latest)
                AiCard(
                    title = "What stands out", state = vm.aiState(data, ai, k), ready = vm.aiReady || data.notes[k] != null,
                    onAsk = { vm.explain(k, LabAi.reportPrompt(data, latest)) }, onOpenSettings = onOpenSettings,
                    idleHint = "A short read of your latest report", footer = "Written by Gemini from your values only. Not medical advice.",
                )
            }
            LabCategory.entries.forEach { cat ->
                val rows = markers.filter { it.second.labCategory == cat }
                if (rows.isEmpty()) return@forEach
                item {
                    SectionLabel(cat.label)
                    Spacer(Modifier.height(8.dp))
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column {
                            rows.forEachIndexed { i, (_, m) ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                                MarkerRow(m, data.history(m.key).map { it.second.value }, stale = data.history(m.key).last().first.id != latest.id) { selected = m.key }
                            }
                        }
                    }
                }
            }
            item {
                SectionLabel("Reports")
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        data.reportsByDate.asReversed().forEachIndexed { i, r ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(LabDates.pretty(r.date), style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        listOfNotNull(r.lab, "${r.markers.size} results").joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!vm.demo) IconButton(onClick = { deleting = r }) { Icon(Icons.Outlined.Delete, "Delete report") }
                            }
                        }
                    }
                }
            }
        }
        item { Disclaimer(MEDICAL_DISCLAIMER) }
    }
    deleting?.let { r -> ConfirmDelete("the report from ${LabDates.pretty(r.date)}", onConfirm = { vm.deleteReport(r.id) }, onDismiss = { deleting = null }) }
}

@Composable
private fun MarkerRow(m: LabMarker, history: List<Double>, stale: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RangeChip(m.labFlag)
                if (stale) {
                    Spacer(Modifier.width(8.dp))
                    Text("earlier report", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (history.size >= 2) {
            Sparkline(history, color, Modifier.width(56.dp).height(26.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(84.dp)) {
            Text(fmtNum(m.value), style = MaterialTheme.typography.titleLarge.merge(Numeric), fontWeight = FontWeight.Bold, maxLines = 1)
            if (m.unit.isNotBlank()) Text(m.unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

private fun rangeText(m: LabMarker): String? = when {
    m.refLow != null && m.refHigh != null -> "${fmtNum(m.refLow)}–${fmtNum(m.refHigh)} ${m.unit}"
    m.refHigh != null -> "below ${fmtNum(m.refHigh)} ${m.unit}"
    m.refLow != null -> "above ${fmtNum(m.refLow)} ${m.unit}"
    else -> null
}

@Composable
private fun MarkerDetail(
    vm: LabsViewModel, data: LabsData, ai: Map<String, app.baseline.ai.AiState>, key: String,
    onBack: () -> Unit, onOpenSettings: () -> Unit,
) {
    val history = data.history(key)
    val (lastReport, latest) = history.last()
    val def = Biomarkers.byId(key)
    val color = MaterialTheme.colorScheme.primary
    OverlayPage(latest.name, def?.category?.label ?: latest.labCategory.label, onBack) {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmtNum(latest.value), style = MaterialTheme.typography.displayLarge.merge(Numeric), fontWeight = FontWeight.Bold)
                if (latest.unit.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(latest.unit, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
            RangeChip(latest.labFlag)
            Spacer(Modifier.height(10.dp))
            Text(
                listOfNotNull(LabDates.pretty(lastReport.date), rangeText(latest)?.let { "Lab range $it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            def?.about?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal, lineHeight = 26.sp))
            }
        }
        item {
            val vals = history.map { it.second.value }
            val band = if (latest.refLow != null && latest.refHigh != null) latest.refLow..latest.refHigh else null
            val goal = if (band == null) latest.refHigh ?: latest.refLow else null
            var sel by remember(key) { mutableStateOf<Int?>(null) }
            SectionCard {
                Text(
                    sel?.let { i -> "${LabDates.pretty(history[i].first.date)}  ·  ${fmtNum(vals[i])} ${latest.unit}" }
                        ?: if (history.size == 1) "One result so far" else "${history.size} results",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TrendChart(
                    values = vals, color = color,
                    labels = ChartLabels(history.map { LabDates.axis(it.first.date) }, ::fmtNum),
                    band = band, goal = goal, onSelect = { sel = it },
                )
                if (band != null || goal != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (band != null) "Shaded: your lab's reference range." else "Dashed line: your lab's reference limit.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            val k = LabAi.markerKey(key, lastReport.id)
            AiCard(
                title = "Explain this result", state = vm.aiState(data, ai, k), ready = vm.aiReady || data.notes[k] != null,
                onAsk = { vm.explain(k, LabAi.markerPrompt(data, key)) }, onOpenSettings = onOpenSettings,
                idleHint = "What it means for you and what moves it", footer = "Written by Gemini from your values only. Not medical advice.",
            )
        }
        if (history.size > 1) item {
            SectionLabel("History")
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    history.asReversed().forEachIndexed { i, (r, m) ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(LabDates.pretty(r.date), style = MaterialTheme.typography.bodyLarge)
                                r.lab?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            RangeChip(m.labFlag)
                            Spacer(Modifier.width(12.dp))
                            Text("${fmtNum(m.value)} ${m.unit}", style = MaterialTheme.typography.titleMedium.merge(Numeric))
                        }
                    }
                }
            }
        }
        item { Disclaimer(MEDICAL_DISCLAIMER) }
    }
}
