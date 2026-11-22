package app.baseline.labs.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.FilterChip
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
import app.baseline.ui.theme.Brand
import app.baseline.ui.theme.Numeric
import kotlin.math.abs

/** Numbers worth trending from an InBody scan. [higherIsBetter] null = neither direction is "good". */
private enum class CompMetric(val label: String, val unit: String, val get: (InBodyScan) -> Double?, val higherIsBetter: Boolean?, val about: String) {
    Weight("Weight", "kg", { it.weightKg }, null, "Total body weight. On its own it can't tell fat from muscle or water, so read it next to muscle and fat."),
    Muscle("Skeletal muscle", "kg", { it.skeletalMuscleKg }, true, "Muscle you can train (arms, legs, trunk). Holding or growing it while fat falls is the goal of most body-composition work."),
    FatPct("Body fat", "%", { it.percentBodyFat }, false, "Share of your weight that is fat. Healthy ranges are roughly 10–20% for men and 18–28% for women."),
    FatKg("Fat mass", "kg", { it.bodyFatKg }, false, "Fat in kilograms. Unlike the percentage, it doesn't change just because muscle does."),
    Visceral("Visceral fat", "level", { it.visceralFatLevel }, false, "Estimated fat around the organs, as a 1–20 level. Under 10 is the healthy range."),
    Bmr("BMR", "kcal", { it.bmrKcal }, true, "Calories your body burns at complete rest, estimated mostly from lean mass."),
    Score("InBody score", "", { it.inBodyScore }, true, "InBody's 0–100 summary of muscle versus fat for your size. 70–90 is typical for a fit adult."),
    Smi("SMI", "kg/m²", { it.smi }, true, "Skeletal muscle index: arm and leg muscle divided by height squared. Low values (under about 7.0 for men, 5.7 for women) signal low muscle."),
    Water("Body water", "L", { it.totalBodyWaterL }, null, "Total body water. It swings with hydration, food and exercise, which is why scans should be taken under the same conditions."),
    Protein("Protein", "kg", { it.proteinKg }, true, "Protein mass in the body, which tracks muscle and organ tissue."),
}

private val partNames = mapOf("rightArm" to "Right arm", "leftArm" to "Left arm", "trunk" to "Trunk", "rightLeg" to "Right leg", "leftLeg" to "Left leg")

/** "+0.4 kg", "−0.7 pts" (percent changes are percentage points), "no change". */
private fun signed(d: Double, unit: String): String {
    if (abs(d) < 0.05) return "no change"
    val u = when (unit) { "" -> ""; "%" -> " pts"; else -> " $unit" }
    return "${if (d > 0) "+" else "−"}${fmtNum(abs(d))}$u"
}

/** Body composition from InBody (CSV export, PDF or photo): a timeline, what changed, and the latest details. */
@Composable
fun BodyCompScreen(onBack: () -> Unit, onOpenSettings: () -> Unit, vm: LabsViewModel = viewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    val upload by vm.upload.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    vm.settings.collectAsStateWithLifecycle().value
    val scans = data.scansByDate
    val latest = scans.lastOrNull()
    var chart by rememberSaveable { mutableStateOf(CompMetric.Weight.name) }
    var deleting by remember { mutableStateOf<InBodyScan?>(null) }
    val h = Brand.health

    OverlayPage("Body composition", "InBody scans, kept on this phone", onBack) {
        item {
            AddDocument(vm, data, DocKind.InBody, "Add scans", onOpenSettings, upload)
            Spacer(Modifier.height(6.dp))
            Text(
                "Pick the CSV export from the InBody app (read on this phone, no AI) or a PDF or photo of a result sheet (read by AI).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (latest == null) item {
            SectionCard {
                Text("No scans yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "In the InBody app, open your results and export them as CSV, then add the file here: every scan lands on one " +
                        "timeline. Baseline shows weight, muscle, fat, visceral fat, BMR and body water, and what changed between scans.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item { ChangeCard(scans) }
            item {
                val k = LabAi.inBodyKey(latest)
                AiCard(
                    title = "What stands out", state = vm.aiState(data, ai, k), ready = vm.aiReady || data.notes[k] != null,
                    onAsk = { vm.explain(k, LabAi.inBodyPrompt(data)) }, onOpenSettings = onOpenSettings,
                    idleHint = "A short read of your scans", footer = "Written by Gemini from your values only. Not medical advice.",
                )
            }
            item {
                val tiles = listOf(CompMetric.Weight, CompMetric.Muscle, CompMetric.FatPct, CompMetric.Visceral).filter { m -> scans.any { m.get(it) != null } }
                Column {
                    tiles.chunked(2).forEach { pair ->
                        TileRow {
                            pair.forEach { m ->
                                val pts = scans.filter { m.get(it) != null }
                                val v = pts.lastOrNull()?.let(m.get)
                                val prev = pts.getOrNull(pts.size - 2)?.let(m.get)
                                val d = if (v != null && prev != null) v - prev else null
                                StatTile(
                                    label = m.label, value = v?.let(::fmtNum) ?: "—", unit = m.unit.takeIf { it.isNotBlank() },
                                    caption = d?.let { if (abs(it) < 0.05) "Same as previous scan" else "${signed(it, m.unit)} vs previous scan" } ?: pts.lastOrNull()?.let { LabDates.pretty(it.date) },
                                    modifier = Modifier.weight(1f), onClick = { chart = m.name },
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
            item {
                val available = CompMetric.entries.filter { m -> scans.count { m.get(it) != null } >= 1 }
                val m = CompMetric.entries.firstOrNull { it.name == chart }?.takeIf { it in available } ?: available.first()
                val pts = scans.filter { m.get(it) != null }
                SectionCard {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        available.forEach { c -> FilterChip(selected = c == m, onClick = { chart = c.name }, label = { Text(c.label) }) }
                    }
                    Spacer(Modifier.height(12.dp))
                    val first = pts.first(); val last = pts.last()
                    Text(
                        if (pts.size == 1) "One scan · ${LabDates.pretty(first.date)}"
                        else "${pts.size} scans · ${LabDates.pretty(first.date)} – ${LabDates.pretty(last.date)}",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    val days = pts.map { it.localDate.toEpochDay() }
                    val span = (days.last() - days.first()).coerceAtLeast(1)
                    TrendChart(
                        values = pts.map { m.get(it) }, color = MaterialTheme.colorScheme.primary,
                        labels = ChartLabels(pts.map { LabDates.axis(it.date) }, ::fmtNum),
                        positions = if (pts.size > 1) days.map { (it - days.first()).toFloat() / span } else null,
                        tooltip = { i ->
                            val v = m.get(pts[i])!!
                            val prev = pts.getOrNull(i - 1)?.let(m.get)
                            listOfNotNull(LabDates.pretty(pts[i].date), "${fmtNum(v)} ${m.unit}".trim(), prev?.let { "${signed(v - it, m.unit)} vs previous" }).joinToString("\n")
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    if (pts.size > 1) {
                        val d = m.get(last)!! - m.get(first)!!
                        val good = m.higherIsBetter?.let { hi -> abs(d) > 1e-9 && (d > 0) == hi }
                        Text(
                            "${signed(d, m.unit)} since ${LabDates.pretty(first.date)}",
                            style = MaterialTheme.typography.titleSmall,
                            color = when (good) { true -> h.great; false -> h.fair; null -> MaterialTheme.colorScheme.onSurface },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(m.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { LatestCard(latest) }
            item {
                SectionLabel("Scans · newest first")
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        val rev = scans.asReversed()
                        rev.forEachIndexed { i, s ->
                            val prev = rev.getOrNull(i + 1)
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(LabDates.pretty(s.date), style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        listOfNotNull(s.weightKg?.let { "${fmtNum(it)} kg" }, s.percentBodyFat?.let { "${fmtNum(it)}% fat" }, s.skeletalMuscleKg?.let { "${fmtNum(it)} kg muscle" }).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (prev != null) {
                                        val ds = listOf("weight" to delta(s.weightKg, prev.weightKg), "fat" to delta(s.bodyFatKg, prev.bodyFatKg), "muscle" to delta(s.skeletalMuscleKg, prev.skeletalMuscleKg))
                                            .filter { it.second != null }
                                        val bits = ds.filter { abs(it.second!!) >= 0.05 }.map { "${it.first} ${signed(it.second!!, "kg")}" }
                                        if (ds.isNotEmpty()) Text(if (bits.isEmpty()) "No change from the scan before" else bits.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
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
                    "under the same conditions (for example, mornings before eating). AI can misread a sheet, so check values against the original.",
            )
        }
    }
    deleting?.let { s -> ConfirmDelete("the scan from ${LabDates.pretty(s.date)}", onConfirm = { vm.deleteScan(s.id) }, onDismiss = { deleting = null }) }
}

private fun delta(a: Double?, b: Double?) = if (a != null && b != null) a - b else null

/** "Of the 1.9 kg you lost since April, 2.5 kg was fat; lean mass rose 0.6 kg." */
@Composable
private fun ChangeCard(scans: List<InBodyScan>) {
    val withComp = scans.filter { it.weightKg != null && it.bodyFatKg != null }
    SectionCard {
        Text("What changed", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (withComp.size < 2) {
            Text(
                if (scans.size < 2) "Add another scan to see what changed. Scans a few weeks apart, taken at the same time of day, compare best."
                else "These scans don't include fat mass, so the split between fat and lean can't be shown.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val a = withComp.first(); val b = withComp.last()
        val dw = b.weightKg!! - a.weightKg!!
        val df = b.bodyFatKg!! - a.bodyFatKg!!
        val dl = dw - df
        val weeks = ((b.localDate.toEpochDay() - a.localDate.toEpochDay()) / 7).coerceAtLeast(1)
        Text(
            buildString {
                append("Since ${LabDates.pretty(a.date)} ($weeks weeks): ")
                append(if (abs(dw) < 0.3) "weight about the same" else "${if (dw < 0) "down" else "up"} ${fmtNum(abs(dw))} kg")
                append(". ")
                append(when {
                    df < -0.3 && dl > -0.3 -> "Fat fell ${fmtNum(abs(df))} kg while lean mass ${if (dl >= 0.3) "rose ${fmtNum(dl)} kg" else "held steady"} — the change you want."
                    df < -0.3 && dl <= -0.3 -> "Fat fell ${fmtNum(abs(df))} kg and lean mass ${fmtNum(abs(dl))} kg. Strength training and enough protein help keep the lean part."
                    df > 0.3 && dl >= 0.3 -> "Lean mass rose ${fmtNum(dl)} kg and fat ${fmtNum(df)} kg."
                    df > 0.3 -> "Fat rose ${fmtNum(df)} kg${if (dl <= -0.3) " and lean mass fell ${fmtNum(abs(dl))} kg" else ""}."
                    dl >= 0.3 -> "Lean mass rose ${fmtNum(dl)} kg with fat about the same."
                    dl <= -0.3 -> "Lean mass fell ${fmtNum(abs(dl))} kg with fat about the same."
                    else -> "Fat and lean mass are both about the same."
                })
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            ChangeStat("Weight", dw, null, Modifier.weight(1f))
            ChangeStat("Fat", df, false, Modifier.weight(1f))
            ChangeStat("Lean", dl, true, Modifier.weight(1f))
        }
        if (abs(dw) > 0 && weeks > 0) {
            Spacer(Modifier.height(8.dp))
            Text("Lean = weight − fat mass (muscle, water, bone and organs).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChangeStat(label: String, d: Double, higherIsBetter: Boolean?, modifier: Modifier) {
    val h = Brand.health
    val c = when {
        higherIsBetter == null || abs(d) < 0.3 -> MaterialTheme.colorScheme.onSurface
        (d > 0) == higherIsBetter -> h.great
        else -> h.fair
    }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(signed(d, "kg"), style = MaterialTheme.typography.titleLarge.merge(Numeric), fontWeight = FontWeight.SemiBold, color = c)
    }
}

@Composable
private fun LatestCard(latest: InBodyScan) {
    SectionCard {
        Text("Latest scan · ${LabDates.pretty(latest.date)}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(14.dp))
        val cells = listOfNotNull(
            latest.bodyFatKg?.let { "Fat mass" to "${fmtNum(it)} kg" },
            latest.leanKg?.let { "Lean mass" to "${fmtNum(it)} kg" },
            latest.bmi?.let { "BMI" to fmtNum(it) },
            latest.bmrKcal?.let { "BMR" to "${fmtNum(it)} kcal" },
            latest.totalBodyWaterL?.let { "Body water" to "${fmtNum(it)} L" },
            latest.ecwTbw?.let { "ECW/TBW" to fmtNum(it) },
            latest.proteinKg?.let { "Protein" to "${fmtNum(it)} kg" },
            latest.mineralKg?.let { "Minerals" to "${fmtNum(it)} kg" },
            latest.smi?.let { "SMI" to "${fmtNum(it)} kg/m²" },
            latest.waistHipRatio?.let { "Waist–hip" to fmtNum(it) },
            latest.visceralFatLevel?.let { "Visceral fat" to "level ${fmtNum(it)}" },
            latest.inBodyScore?.let { "InBody score" to fmtNum(it) },
            latest.phaseAngle?.let { "Phase angle" to "${fmtNum(it)}°" },
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
                Text("Lean", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
                Text("Fat", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(90.dp))
            }
            partNames.forEach { (part, name) ->
                val lean = latest.segmentalLean.firstOrNull { it.part == part }
                val fat = latest.segmentalFat.firstOrNull { it.part == part }
                if (lean == null && fat == null) return@forEach
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(segText(lean?.kg, lean?.percent), style = MaterialTheme.typography.bodySmall.merge(Numeric), modifier = Modifier.width(100.dp))
                    Text(segText(fat?.kg, fat?.percent), style = MaterialTheme.typography.bodySmall.merge(Numeric), modifier = Modifier.width(90.dp))
                }
            }
            balance(latest)?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            if (latest.segmentalLean.any { it.percent != null }) {
                Spacer(Modifier.height(8.dp))
                Text("Percent is versus the normal value for your height and weight.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Left–right lean balance: within 3% is balanced (InBody's own guidance is similar). */
private fun balance(s: InBodyScan): String? {
    fun kg(p: String) = s.segmentalLean.firstOrNull { it.part == p }?.kg
    fun pair(l: Double?, r: Double?, what: String): String? {
        if (l == null || r == null || l <= 0 || r <= 0) return null
        val diff = abs(l - r) / maxOf(l, r) * 100
        return if (diff < 3) null else "$what: ${if (l > r) "left" else "right"} is ${"%.0f".format(diff)}% heavier"
    }
    val notes = listOfNotNull(pair(kg("leftArm"), kg("rightArm"), "Arms"), pair(kg("leftLeg"), kg("rightLeg"), "Legs"))
    if (s.segmentalLean.size < 4) return null
    return if (notes.isEmpty()) "Left and right sides are balanced (within 3%)." else notes.joinToString(". ") + ". Unilateral work (single-arm, single-leg) can even it out."
}

private fun segText(kg: Double?, pct: Double?): String =
    listOfNotNull(kg?.let { "${fmtNum(it)} kg" }, pct?.let { "${fmtNum(it)}%" }).joinToString(" · ").ifEmpty { "—" }

