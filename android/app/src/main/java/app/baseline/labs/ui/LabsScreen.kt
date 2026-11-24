package app.baseline.labs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import app.baseline.ui.theme.Brand
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
    var deleting by remember { mutableStateOf<LabReport?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") } // all | out | in | cat:<name>
    var reportId by rememberSaveable { mutableStateOf<String?>(null) }
    val report = data.reports.firstOrNull { it.id == reportId }
    // Latest value per biomarker, or one report's values when a report is picked.
    val markers = remember(data, reportId) { report?.markers?.map { report to it } ?: data.latestMarkers() }
    val outCount = markers.count { it.second.outOfRange }
    val q = query.trim()
    val shown = remember(markers, q, filter) {
        markers.filter { (_, mk) ->
            (q.isEmpty() || matches(mk.name, q) || (Biomarkers.byId(mk.key)?.let { d -> matches(d.name, q) || d.aliases.any { a -> matches(a, q) } } == true) ||
                matches(mk.labCategory.label, q)) &&
                when {
                    filter == "out" -> mk.outOfRange
                    filter == "in" -> mk.status == LabFlag.Normal
                    filter.startsWith("cat:") -> mk.labCategory.name == filter.removePrefix("cat:")
                    else -> true
                }
        }
    }

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
                SummaryStrip(
                    total = markers.size, out = outCount,
                    title = report?.let { "Report from ${LabDates.pretty(it.date)}" } ?: "Latest results · ${LabDates.pretty(latest.date)}",
                    onOut = { filter = if (filter == "out") "all" else "out" }, outSelected = filter == "out",
                )
            }
            if (report == null) item {
                val k = LabAi.reportKey(latest)
                AiCard(
                    title = "What stands out", state = vm.aiState(data, ai, k), ready = vm.aiReady || data.notes[k] != null,
                    onAsk = { vm.explain(k, LabAi.reportPrompt(data, latest)) }, onOpenSettings = onOpenSettings,
                    idleHint = "A short read of your latest report", footer = "Written by Gemini from your values only. Not medical advice.",
                )
            }
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(40) }, singleLine = true,
                    placeholder = { Text("Search ${markers.size} results") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } },
                    shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                val cats = markers.map { it.second.labCategory }.distinct().sortedBy { it.ordinal }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == "all", onClick = { filter = "all" }, label = { Text("All") })
                    if (outCount > 0) FilterChip(
                        selected = filter == "out", onClick = { filter = if (filter == "out") "all" else "out" },
                        label = { Text("Outside range · $outCount") },
                        leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(Brand.health.fair)) },
                    )
                    FilterChip(selected = filter == "in", onClick = { filter = if (filter == "in") "all" else "in" }, label = { Text("In range") })
                    cats.forEach { c ->
                        val key = "cat:${c.name}"
                        FilterChip(selected = filter == key, onClick = { filter = if (filter == key) "all" else key }, label = { Text(c.label) })
                    }
                }
                if (report != null) {
                    Spacer(Modifier.height(8.dp))
                    AssistChip(onClick = { reportId = null }, label = { Text("Showing one report · show latest of all") }, trailingIcon = { Icon(Icons.Rounded.Close, null, Modifier.size(16.dp)) })
                }
            }
            if (shown.isEmpty()) item {
                Text(
                    if (q.isNotEmpty()) "No results match \"$q\"." else "Nothing in this filter.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp),
                )
            }
            // Out-of-range results first, as their own group, unless a filter already narrows the list.
            val attention = if (filter == "all" && q.isEmpty()) shown.filter { it.second.outOfRange } else emptyList()
            if (attention.isNotEmpty()) item {
                SectionLabel("Needs a look · ${attention.size}")
                Spacer(Modifier.height(8.dp))
                MarkerGroup(data, attention, latest, report) { selected = it }
            }
            LabCategory.entries.forEach { cat ->
                val rows = shown.filter { it.second.labCategory == cat && it !in attention }
                    .sortedWith(compareByDescending<Pair<LabReport, LabMarker>> { it.second.outOfRange }.thenBy { it.second.name.lowercase() })
                if (rows.isEmpty()) return@forEach
                item {
                    SectionLabel(cat.label)
                    Spacer(Modifier.height(8.dp))
                    MarkerGroup(data, rows, latest, report) { selected = it }
                }
            }
            item {
                SectionLabel("Reports · tap one to see only its results")
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column {
                        data.reportsByDate.asReversed().forEachIndexed { i, r ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                            val out = r.markers.count { it.outOfRange }
                            Row(
                                Modifier.fillMaxWidth().clickable { reportId = if (reportId == r.id) null else r.id; filter = "all"; query = "" }
                                    .background(if (r.id == reportId) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(LabDates.pretty(r.date), style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        listOfNotNull(r.lab, "${r.markers.size} results", if (out > 0) "$out outside range" else "all in range").joinToString(" · "),
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
    deleting?.let { r -> ConfirmDelete("the report from ${LabDates.pretty(r.date)}", onConfirm = { if (reportId == r.id) reportId = null; vm.deleteReport(r.id) }, onDismiss = { deleting = null }) }
}

/** Word-start match ("vit" finds "Vitamin D", not "high sensitivity"); short codes like "ldl" match anywhere in a word. */
private fun matches(text: String, q: String): Boolean {
    val words = text.lowercase().split(Regex("[^a-z0-9₀-₉]+")).filter { it.isNotEmpty() }
    val ql = q.lowercase()
    return text.startsWith(q, true) || words.any { it.startsWith(ql) } || (ql.length <= 4 && text.lowercase().replace(Regex("[^a-z0-9]"), "").startsWith(ql))
}

@Composable
private fun SummaryStrip(total: Int, out: Int, title: String, onOut: () -> Unit, outSelected: Boolean) {
    val h = Brand.health
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp)) {
                    Text("${total - out}", style = MaterialTheme.typography.headlineMedium.merge(Numeric), fontWeight = FontWeight.Bold, color = h.great)
                    Text("in range", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(
                shape = RoundedCornerShape(20.dp), color = if (out > 0) h.fair.copy(alpha = if (outSelected) .28f else .14f) else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable(enabled = out > 0, onClick = onOut),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("$out", style = MaterialTheme.typography.headlineMedium.merge(Numeric), fontWeight = FontWeight.Bold, color = if (out > 0) h.fair else MaterialTheme.colorScheme.onSurface)
                    Text(if (out > 0) "outside range · tap to filter" else "outside range", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MarkerGroup(data: LabsData, rows: List<Pair<LabReport, LabMarker>>, latest: LabReport, report: LabReport?, onOpen: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            rows.forEachIndexed { i, (r, m) ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                val hist = data.history(m.key)
                MarkerRow(m, hist.map { it.second.value }, stale = report == null && r.id != latest.id, staleDate = r.date) { onOpen(m.key) }
            }
        }
    }
}

@Composable
private fun MarkerRow(m: LabMarker, history: List<Double>, stale: Boolean, staleDate: String, onClick: () -> Unit) {
    val h = Brand.health
    val out = m.outOfRange
    val color = if (out) h.fair else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (out) h.fair.copy(alpha = .08f) else Color.Transparent)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(if (out) h.fair else Color.Transparent))
        Row(Modifier.weight(1f).padding(start = 16.dp, end = 20.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RangeChip(m.status)
                    if (stale) {
                        Spacer(Modifier.width(8.dp))
                        Text(LabDates.pretty(staleDate), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (m.refLow != null || m.refHigh != null) {
                    Spacer(Modifier.height(8.dp))
                    RangeBar(m, Modifier.fillMaxWidth(.9f))
                }
            }
            if (history.size >= 2) {
                Sparkline(history, color, Modifier.width(48.dp).height(26.dp))
                Spacer(Modifier.width(10.dp))
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(80.dp)) {
                Text(fmtNum(m.value), style = MaterialTheme.typography.titleLarge.merge(Numeric), fontWeight = FontWeight.Bold, maxLines = 1, color = if (out) h.fair else MaterialTheme.colorScheme.onSurface)
                if (m.unit.isNotBlank()) Text(m.unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                rangeText(m)?.let { Text(it.removeSuffix(" ${m.unit}").removeSuffix(m.unit).trim(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
        }
    }
}

/**
 * Where a value sits against its reference range: the in-range stretch is the middle of the track, the dot is the
 * value. One-sided ranges ("below 200") put the limit three quarters along.
 */
@Composable
fun RangeBar(m: LabMarker, modifier: Modifier = Modifier) {
    val h = Brand.health
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val out = m.outOfRange
    Canvas(modifier.height(10.dp)) {
        val lo = m.refLow; val hi = m.refHigh
        val (dMin, dMax, a, b) = when {
            lo != null && hi != null && hi > lo -> { val span = hi - lo; listOf(lo - span * .6, hi + span * .6, lo, hi) }
            hi != null -> listOf(0.0, hi / .75, 0.0, hi)
            lo != null -> { val span = kotlin.math.abs(lo).coerceAtLeast(1e-6); listOf(lo - span * .5, lo + span, lo, lo + span) }
            else -> return@Canvas
        }
        fun x(v: Double) = ((v - dMin) / (dMax - dMin)).coerceIn(0.0, 1.0).toFloat() * size.width
        val ty = size.height / 2; val th = 4.dp.toPx()
        drawRoundRect(track, Offset(0f, ty - th / 2), Size(size.width, th), CornerRadius(th / 2))
        drawRoundRect(h.great.copy(alpha = .55f), Offset(x(a), ty - th / 2), Size((x(b) - x(a)).coerceAtLeast(2f), th), CornerRadius(th / 2))
        val vx = x(m.value).coerceIn(5.dp.toPx(), size.width - 5.dp.toPx())
        drawCircle(Color.White, 5.dp.toPx(), Offset(vx, ty))
        drawCircle(if (out) h.fair else h.great, 4.dp.toPx(), Offset(vx, ty))
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
            RangeChip(latest.status)
            if (latest.refLow != null || latest.refHigh != null) { Spacer(Modifier.height(12.dp)); RangeBar(latest, Modifier.fillMaxWidth()) }
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
                val days = history.map { it.first.localDate.toEpochDay() }
                val span = (days.last() - days.first()).coerceAtLeast(1)
                TrendChart(
                    values = vals, color = color,
                    labels = ChartLabels(history.map { LabDates.axis(it.first.date) }, ::fmtNum),
                    band = band, bandLabel = band?.let { "Lab range" }, goal = goal, goalLabel = goal?.let { "Limit ${fmtNum(it)}" },
                    positions = if (history.size > 1) days.map { ((it - days.first()).toFloat() / span) } else null,
                    tooltip = { i -> "${LabDates.pretty(history[i].first.date)}\n${fmtNum(vals[i])} ${latest.unit} · ${when (history[i].second.status) { LabFlag.High -> "high"; LabFlag.Low -> "low"; LabFlag.Normal -> "in range"; LabFlag.Abnormal -> "flagged"; else -> "no range" }}" },
                    onSelect = { sel = it },
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
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    history.asReversed().forEachIndexed { i, (r, m) ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(LabDates.pretty(r.date), style = MaterialTheme.typography.bodyLarge)
                                r.lab?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            RangeChip(m.status)
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
