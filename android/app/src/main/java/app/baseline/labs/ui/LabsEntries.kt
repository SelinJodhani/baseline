package app.baseline.labs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.baseline.labs.LabDates
import app.baseline.labs.LabFlag
import app.baseline.labs.LabsViewModel
import app.baseline.labs.fmtNum

/** "Lab reports" and "Body composition" entries for the top of the Body tab. */
@Composable
fun LabsEntries(onLabs: () -> Unit, onBodyComp: () -> Unit, vm: LabsViewModel = viewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    val latest = data.reportsByDate.lastOrNull()
    val scan = data.scansByDate.lastOrNull()
    val labsLine = latest?.let { r ->
        val out = r.markers.count { it.outOfRange }
        "${data.reports.size} ${if (data.reports.size == 1) "report" else "reports"} · latest ${LabDates.pretty(r.date)}" + if (out > 0) " · $out outside range" else ""
    } ?: "Add a blood test to track your biomarkers"
    val compLine = scan?.let { s ->
        listOfNotNull("${data.scans.size} ${if (data.scans.size == 1) "scan" else "scans"}", s.percentBodyFat?.let { "${fmtNum(it)}% body fat" }, s.skeletalMuscleKg?.let { "${fmtNum(it)} kg muscle" }).joinToString(" · ")
    } ?: "Add an InBody result sheet"
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Entry(Icons.Rounded.Science, "Lab reports", labsLine, onLabs)
            HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Entry(Icons.Rounded.MonitorWeight, "Body composition", compLine, onBodyComp)
        }
    }
}

@Composable
private fun Entry(icon: ImageVector, title: String, line: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(cs.primary.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
    }
}
