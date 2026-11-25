package app.baseline.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshablePage(refreshing: Boolean, onRefresh: () -> Unit, content: LazyListScope.() -> Unit) {
    // (callers pass data.refreshing || data.syncing)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
        StatusBarScrim()
    }
}

/**
 * Keeps scrolled content from colliding with the status bar icons.
 *
 * wrapContentHeight matters: parents such as Material's Surface pass their full-screen minimum size down
 * to children, and a plain `height(top)` is then stretched over the whole page — a 90% veil that left
 * every screen looking almost black (v0.2.0).
 */
@Composable
fun StatusBarScrim(
    alpha: Float = .94f,
    top: Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
    modifier: Modifier = Modifier,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .wrapContentHeight(Alignment.Top, unbounded = false)
            .then(modifier)
            .height(top)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = alpha)),
    )
}

@Composable
fun EmptyState(message: String, onRetry: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.CloudOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("Can't reach your bridge", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row {
            OutlinedButton(onClick = onSettings) { Text("Settings") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}
