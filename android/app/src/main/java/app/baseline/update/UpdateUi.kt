package app.baseline.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.baseline.BaselineApp
import kotlinx.coroutines.launch

private const val PLAY_PROTECT_HINT =
    "Android said \"App not installed\"? Google Play Protect sometimes blocks apps installed by other apps. Download it from GitHub in your browser instead."

/** A quiet card on Today while an update is available or downloading. Renders nothing otherwise. */
@Composable
fun UpdateBanner() {
    val state by Updater.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = (ctx.applicationContext as? BaselineApp)?.store ?: return // previews / tests
    val cs = MaterialTheme.colorScheme
    val update = when (val s = state) {
        is Updater.State.Available -> s.update
        is Updater.State.Downloading -> s.update
        is Updater.State.Failed -> s.update ?: return
        is Updater.State.Ready -> s.update
        else -> return
    }
    Surface(shape = RoundedCornerShape(28.dp), color = cs.primaryContainer) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SystemUpdate, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Baseline ${update.version} is available", style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer, modifier = Modifier.weight(1f))
            }
            when (val s = state) {
                is Updater.State.Downloading -> {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), strokeCap = StrokeCap.Round)
                }
                is Updater.State.Ready -> {
                    Spacer(Modifier.height(6.dp))
                    Text(PLAY_PROTECT_HINT, style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
                    Row {
                        TextButton(onClick = { Updater.install(ctx, s.file) }) { Text("Install") }
                        TextButton(onClick = { Updater.openPage(ctx, update.pageUrl) }) { Text("Download from GitHub") }
                    }
                }
                is Updater.State.Failed -> {
                    Spacer(Modifier.height(6.dp))
                    Text(s.message, style = MaterialTheme.typography.bodySmall, color = cs.error)
                    TextButton(onClick = { scope.launch { Updater.downloadAndInstall(ctx, update) } }) { Text("Try again") }
                }
                else -> {
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(onClick = { scope.launch { Updater.downloadAndInstall(ctx, update) } }) { Text("Update") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { Updater.openPage(ctx, update.pageUrl) }) { Text("What's new") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { Updater.skip(store, update) } }) { Text("Skip") }
                    }
                }
            }
        }
    }
}

/** Settings → Updates. */
@Composable
fun UpdateSettings() {
    val state by Updater.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = (ctx.applicationContext as BaselineApp).store
    Column {
        Text("Updates", style = MaterialTheme.typography.titleMedium)
        Text("Version ${Updater.currentVersion} · from GitHub Releases, checksum-verified", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        when (val s = state) {
            is Updater.State.Checking -> Text("Checking…", style = MaterialTheme.typography.bodyMedium)
            is Updater.State.UpToDate -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("You're on the latest version.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { Updater.manualCheck(store) } }) { Text("Check again") }
            }
            is Updater.State.Available -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${s.update.version} is available", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Button(onClick = { scope.launch { Updater.downloadAndInstall(ctx, s.update) } }) { Text("Update") }
            }
            is Updater.State.Downloading -> LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), strokeCap = StrokeCap.Round)
            is Updater.State.Ready -> Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloaded ${s.update.version}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Button(onClick = { Updater.install(ctx, s.file) }) { Text("Install") }
                }
                Text(PLAY_PROTECT_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { Updater.openPage(ctx, s.update.pageUrl) }) { Text("Download from GitHub") }
            }
            is Updater.State.Failed -> Column {
                Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { Updater.manualCheck(store) } }) { Text("Try again") }
            }
            Updater.State.Idle -> FilledTonalButton(onClick = { scope.launch { Updater.manualCheck(store) } }) { Text("Check for updates") }
        }
    }
}
