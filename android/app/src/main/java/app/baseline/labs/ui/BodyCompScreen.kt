package app.baseline.labs.ui

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.baseline.ai.AiCard
import app.baseline.labs.DocKind
import app.baseline.labs.InBodyScan
import app.baseline.labs.LabAi
import app.baseline.labs.LabDates
import app.baseline.labs.LabsViewModel
import app.baseline.labs.fmtNum
import app.baseline.ui.charts.ChartLabels
import app.baseline.ui.charts.TrendChart
import app.baseline.ui.common.KeyValue
import app.baseline.ui.common.SectionCard
import app.baseline.ui.common.StatTile
import app.baseline.ui.common.TileRow

/** The five numbers worth trending from an InBody scan. */
private enum class CompMetric(val label: String, val unit: String, val get: (InBodyScan) -> Double?, val higherIsBetter: Boolean?) {
    Weight("Weight", "kg", { it.weightKg }, null),
    Muscle("Skeletal muscle", "kg", { it.skeletalMuscleKg }, true),
    Fat("Body fat", "%", { it.percentBodyFat }, false),
    Visceral("Visceral fat", "level", { it.visceralFatLevel }, false),
    Bmr("BMR", "kcal", { it.bmrKcal }, true),
}

private val partNames = mapOf("rightArm" to "Right arm", "leftArm" to "Left arm", "trunk" to "Trunk", "rightLeg" to "Right leg", "leftLeg" to "Left leg")

/** Body composition from InBody result sheets: key trends, latest details and an AI summary. */
@Composable
fun BodyCompScreen(onBack: () -> Unit, onOpenSettings: () -> Unit, vm: LabsViewModel = viewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    val upload by vm.upload.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    vm.settings.collectAsStateWithLifecycle().value
    val scans = data.scansByDate
    val latest = scans.lastOrNull()
    var chart by rememberSaveable { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<InBodyScan?>(null) }

    OverlayPage("Body composition", "InBody scans, read by AI, kept on this phone", onBack) {
        item { AddDocument(vm, data, DocKind.InBody, "Add a scan", onOpenSettings, upload) }
        if (latest == null) item {
            SectionCard {
                Text("No scans yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Add a photo or PDF of an InBody result sheet. Baseline reads weight, muscle, body fat, visceral fat, BMR and " +
                        "body water, and shows how they change from scan to scan.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                val k = LabAi.inBodyKey(latest)
                AiCard(
                    title = "What stands out", state = vm.aiState(data, ai, k), ready = vm.aiReady || data.notes[k] != null,
                    onAsk = { vm.explain(k, LabAi.inBodyPrompt(data)) }, onOpenSettings = onOpenSettings,
                    idleHint = "A short read of your scans", footer = "Written by Gemini from your values only. Not medical advice.",
                )
            }
            item {
                val first = scans.first()
                val shown = CompMetric.entries.filter { m -> scans.any { m.get(it) != null } }
                Column {
                    shown.chunked(2).forEach { pair ->
                        TileRow {
                            pair.forEach { m ->
                                val v = m.get(latest)
                                val d = if (scans.size > 1) v?.let { a -> m.get(first)?.let { b -> a - b } } else null
                                StatTile(
                                    label = m.label, value = v?.let(::fmtNum) ?: "—", unit = m.unit,
                                    caption = d?.let { "${if (it >= 0) "+" else "−"}${fmtNum(kotlin.math.abs(it))} since first scan" } ?: LabDates.pretty(latest.date),
                                    modifier = Modifier.weight(1f), onClick = { chart = m.ordinal },
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
            item {
                val m = CompMetric.entries[chart]
                val pts = scans.filter { m.get(it) != null }
                var sel by remember(chart) { mutableStateOf<Int?>(null) }
                SectionCard {
                    Text(
                        sel?.let { i -> "${LabDates.pretty(pts[i].date)}  ·  ${fmtNum(m.get(pts[i])!!)} ${m.unit}" } ?: "${m.label} over ${pts.size} scans",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (pts.isEmpty()) Text("Not on your result sheets.", style = MaterialTheme.typography.bodyMedium)
                    else TrendChart(
                        values = pts.map { m.get(it) }, color = MaterialTheme.colorScheme.primary,
                        labels = ChartLabels(pts.map { LabDates.axis(it.date) }, ::fmtNum), onSelect = { sel = it },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Tap a tile above to chart it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionCard {
                    Text("Latest scan · ${LabDates.pretty(latest.date)}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(14.dp))
                    val cells = listOfNotNull(
                        latest.bodyFatKg?.let { "Body fat mass" to "${fmtNum(it)} kg" },
                        latest.bmi?.let { "BMI" to fmtNum(it) },
                        latest.totalBodyWaterL?.let { "Body water" to "${fmtNum(it)} L" },
                        latest.ecwTbw?.let { "ECW/TBW" to fmtNum(it) },
                        latest.inBodyScore?.let { "InBody score" to fmtNum(it) },
                    )
                    cells.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            row.forEach { (l, v) -> KeyValue(l, v, Modifier.weight(1f)) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    if (latest.segmentalLean.isNotEmpty() || latest.segmentalFat.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Row {
                            Text("Segment", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text("Lean", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(108.dp))
                            Text("Fat", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(108.dp))
                        }
                        partNames.forEach { (part, name) ->
                            val lean = latest.segmentalLean.firstOrNull { it.part == part }
                            val fat = latest.segmentalFat.firstOrNull { it.part == part }
                            if (lean == null && fat == null) return@forEach
                            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(segText(lean?.kg, lean?.percent), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(108.dp))
                                Text(segText(fat?.kg, fat?.percent), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(108.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Percent is versus the normal value for your height and weight.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                SectionLabel("Scans")
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        scans.asReversed().forEachIndexed { i, s ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(LabDates.pretty(s.date), style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        listOfNotNull(s.weightKg?.let { "${fmtNum(it)} kg" }, s.percentBodyFat?.let { "${fmtNum(it)}% fat" }, s.skeletalMuscleKg?.let { "${fmtNum(it)} kg muscle" }).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!vm.demo) IconButton(onClick = { deleting = s }) { Icon(Icons.Outlined.Delete, "Delete scan") }
                            }
                        }
                    }
                }
            }
        }
        item {
            Disclaimer(
                "Bioimpedance scans are estimates that shift with hydration, food, exercise and time of day. Compare scans taken " +
                    "under the same conditions. AI can misread a sheet, so check values against the original.",
            )
        }
    }
    deleting?.let { s -> ConfirmDelete("the scan from ${LabDates.pretty(s.date)}", onConfirm = { vm.deleteScan(s.id) }, onDismiss = { deleting = null }) }
}

private fun segText(kg: Double?, pct: Double?): String =
    listOfNotNull(kg?.let { "${fmtNum(it)} kg" }, pct?.let { "${fmtNum(it)}%" }).joinToString(" · ").ifEmpty { "—" }
