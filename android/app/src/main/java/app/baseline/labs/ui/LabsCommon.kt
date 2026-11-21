package app.baseline.labs.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.baseline.insight.Level
import app.baseline.labs.DocKind
import app.baseline.labs.LabFlag
import app.baseline.labs.LabsData
import app.baseline.labs.LabsViewModel
import app.baseline.labs.UploadState
import app.baseline.ui.common.LevelPill

/** A full-screen overlay page with a back button and a title, like a metric story. */
@Composable
fun OverlayPage(title: String, subtitle: String?, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      Box(Modifier.fillMaxSize()) { // Surface would stretch the status-bar scrim over the whole page
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 4.dp, bottom = bottom + 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                FilledTonalIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Spacer(Modifier.height(16.dp))
                Text(title, style = MaterialTheme.typography.headlineLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
        Box(Modifier.fillMaxWidth().height(top).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f)))
      }
    }
}

@Composable
fun RangeChip(flag: LabFlag, modifier: Modifier = Modifier) {
    val (text, level) = when (flag) {
        LabFlag.Normal -> "In range" to Level.Good
        LabFlag.High -> "High" to Level.Fair
        LabFlag.Low -> "Low" to Level.Fair
        LabFlag.Abnormal -> "Flagged" to Level.Fair
        LabFlag.Unknown -> "No range" to Level.Unknown
    }
    LevelPill(text, level, modifier)
}

@Composable
fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
}

@Composable
fun Disclaimer(text: String) {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

const val MEDICAL_DISCLAIMER = "Baseline is not a medical device and doesn't diagnose. Reference ranges come from your lab. " +
    "AI can misread a document, so check values against the original, and talk to your doctor about your results."

/**
 * The "Add" button plus everything around picking a file: AI-not-set-up prompt, one-time consent, the Storage
 * Access Framework picker (no storage permission) and the upload status line.
 */
@Composable
fun AddDocument(vm: LabsViewModel, data: LabsData, kind: DocKind, label: String, onOpenSettings: () -> Unit, upload: UploadState) {
    var ask by rememberSaveable { mutableStateOf<String?>(null) } // "setup" | "consent"
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.upload(it, kind) } }
    val launch = { picker.launch(arrayOf("application/pdf", "image/*")) }
    val working = upload is UploadState.Working

    Column {
        FilledTonalButton(
            onClick = {
                when {
                    !vm.aiReady -> ask = "setup"
                    !data.consent -> ask = "consent"
                    else -> launch()
                }
            },
            enabled = !working && !vm.demo,
        ) {
            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
        if (vm.demo) {
            Spacer(Modifier.height(6.dp))
            Text("Demo mode shows sample data. Connect your own data to add files.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (upload) {
            is UploadState.Working -> if (upload.kind == kind) {
                Spacer(Modifier.height(12.dp))
                Text(if (kind == DocKind.Lab) "Reading your report…" else "Reading your scan…", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is UploadState.Done -> if (upload.kind == kind) StatusLine(upload.message, error = false) { vm.dismissUpload() }
            is UploadState.Failed -> if (upload.kind == kind) StatusLine(upload.message, error = true) { vm.dismissUpload() }
            UploadState.Idle -> {}
        }
    }

    when (ask) {
        "setup" -> AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text("Set up AI first") },
            text = { Text("Reading a ${if (kind == DocKind.Lab) "lab report" else "result sheet"} uses Google Gemini with your own API key. Turn on AI and add a key in Settings.") },
            confirmButton = { TextButton(onClick = { ask = null; onOpenSettings() }) { Text("Open Settings") } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Not now") } },
        )
        "consent" -> AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text("Before you upload") },
            text = {
                Text(
                    "The file you pick is sent once to Google Gemini using your API key, so Google's API terms apply to it.\n\n" +
                        "Baseline keeps only the values it extracts, encrypted on this phone. It never stores the file and never sends your " +
                        "results anywhere else.\n\nNames and ID numbers aren't needed, so feel free to crop them out first.",
                )
            },
            confirmButton = { TextButton(onClick = { ask = null; vm.giveConsent(); launch() }) { Text("Agree and choose file") } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusLine(text: String, error: Boolean, onDismiss: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Surface(shape = MaterialTheme.shapes.medium, color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Dismiss", modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
fun ConfirmDelete(what: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $what?") },
        text = { Text("Its values and any AI notes about it are removed from this phone. This can't be undone.") },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
