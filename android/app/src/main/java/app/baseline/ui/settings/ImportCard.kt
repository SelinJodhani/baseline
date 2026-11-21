package app.baseline.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.baseline.AppViewModel
import app.baseline.AppViewModel.ImportState
import app.baseline.ui.common.Fmt
import java.time.LocalDate

/** Settings card: bring in older history from a Zepp "Export data" zip (e.g. a previous account). */
@Composable
fun ImportCard(vm: AppViewModel) {
    val archive by vm.archive.collectAsStateWithLifecycle()
    val state by vm.importState.collectAsStateWithLifecycle()
    var picked by remember { mutableStateOf<Uri?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) picked = uri }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Text("Older history", style = MaterialTheme.typography.titleMedium)
    Text(
        "Bring in data from another Zepp account — for example one that signs in with Google or Apple. In the Zepp app on that account, " +
            "request \"Export data\"; you get a zip and a password. It's read and kept only on this phone, and anything your bridge has always wins.",
        style = MaterialTheme.typography.bodySmall, color = muted,
    )
    Spacer(Modifier.height(10.dp))
    archive?.let { a ->
        val range = listOfNotNull(a.from, a.to).map { Fmt.dayMonth(LocalDate.parse(it)) + " " + it.take(4) }.joinToString(" – ")
        Text("Imported: $range", style = MaterialTheme.typography.bodyLarge)
        Text("${a.days.size} days · ${a.sleeps.size} nights · ${a.workouts.size} workouts", style = MaterialTheme.typography.bodySmall, color = muted)
        Spacer(Modifier.height(10.dp))
    }
    when (val s = state) {
        ImportState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Reading the export…", style = MaterialTheme.typography.bodyMedium)
        }
        is ImportState.Failed -> Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        is ImportState.Done -> Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        null -> Unit
    }
    if (state != null) Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(onClick = { pick.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, enabled = state != ImportState.Running) {
            Text(if (archive == null) "Import Zepp export" else "Replace")
        }
        if (archive != null) OutlinedButton(onClick = { confirmRemove = true }, enabled = state != ImportState.Running) { Text("Remove") }
    }

    picked?.let { uri ->
        var password by remember(uri) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text("Export password") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("Zepp protects the export zip with a password, shown or emailed when you requested it. Leave empty if it has none.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { picked = null; vm.importZeppExport(uri, password) }) { Text("Import") } },
            dismissButton = { TextButton(onClick = { picked = null }) { Text("Cancel") } },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove imported history?") },
            text = { Text("Deletes the imported days, nights and workouts from this phone. Your bridge data isn't touched, and you can import the zip again later.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; vm.removeImported() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
