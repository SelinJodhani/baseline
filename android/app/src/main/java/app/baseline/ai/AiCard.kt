package app.baseline.ai

import androidx.compose.material3.IconButton
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.baseline.Model
import app.baseline.ui.story.Metric
import app.baseline.ui.story.Spec
import kotlinx.coroutines.launch

/** What an AI card is showing. */
sealed interface AiState {
    data object Idle : AiState
    data object Loading : AiState
    data class Done(val text: String, val note: String? = null) : AiState
    data class Error(val message: String) : AiState
}

/**
 * A quiet, flat card for an on-demand AI explanation. Nothing is sent until the person taps it.
 * When AI isn't set up it shows one line pointing to Settings → AI.
 */
@Composable
fun AiCard(
    title: String,
    state: AiState,
    ready: Boolean,
    onAsk: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    idleHint: String = "A plain-language read of your numbers",
    chips: List<String> = emptyList(),
    selectedChip: Int? = null,
    onChip: (Int) -> Unit = {},
    footer: String = "Written by Gemini from a summary of your numbers. Not medical advice.",
) {
    val cs = MaterialTheme.colorScheme
    var collapsed by rememberSaveable(title) { mutableStateOf(false) }
    val clickable = ready && state is AiState.Idle
    Surface(
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .then(if (clickable) Modifier.clickable(onClick = onAsk) else Modifier)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                when {
                    collapsed -> TextButton(onClick = { collapsed = false }) { Text("Show") }
                    state is AiState.Loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    clickable -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
                    state is AiState.Done || state is AiState.Error ->
                        IconButton(onClick = { collapsed = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Hide", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
                }
            }
            if (collapsed) return@Column
            if (!ready) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Turn on AI in Settings to get an explanation.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                }
                return@Column
            }
            when (state) {
                AiState.Idle -> {
                    Spacer(Modifier.height(4.dp))
                    Text(idleHint, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                AiState.Loading -> {
                    Spacer(Modifier.height(8.dp))
                    Text("Thinking…", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                is AiState.Error -> {
                    Spacer(Modifier.height(8.dp))
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = cs.error)
                    TextButton(onClick = onAsk, modifier = Modifier.padding(top = 4.dp)) { Text("Try again") }
                }
                is AiState.Done -> {
                    Spacer(Modifier.height(10.dp))
                    Text(state.text, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp))
                    state.note?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            if (state !is AiState.Idle) {
                if (chips.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chips.forEachIndexed { i, c ->
                            FilterChip(selected = selectedChip == i, onClick = { onChip(i) }, label = { Text(c) }, enabled = state !is AiState.Loading)
                        }
                    }
                }
                if (state is AiState.Done) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Text(footer, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = onAsk) {
                            Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Regenerate")
                        }
                    }
                }
            }
        }
    }
}

/** "Explain my <metric>" with follow-up chips, for a metric story page. */
@Composable
fun MetricAiCard(m: Model, metric: Metric, sp: Spec, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val date = m.insights.today.toString()
    val ready = MetricExplainer.ready(m)
    var q by rememberSaveable(metric) { mutableStateOf(MetricQuestion.Explain) }
    val states = remember(metric, date) { mutableStateMapOf<MetricQuestion, AiState>() }
    LaunchedEffect(metric, date) {
        MetricQuestion.entries.forEach { mq -> cachedState(ctx, date, metric, mq)?.let { states[mq] = it } }
    }
    val scope = rememberCoroutineScope()
    fun ask(which: MetricQuestion) {
        q = which
        states[which] = AiState.Loading
        scope.launch {
            states[which] = MetricExplainer.generate(ctx, m, metric, sp, which).fold(
                { AiState.Done(it.text) }, { AiState.Error(it.message ?: "Something went wrong") },
            )
        }
    }
    val state = states[q] ?: AiState.Idle
    val followUps = MetricQuestion.entries.drop(1)
    AiCard(
        title = "Explain my ${metric.label.let { if (it.first().isUpperCase() && it.drop(1).any { c -> c.isUpperCase() }) it else it.lowercase() }}",
        state = if (state == AiState.Idle && q != MetricQuestion.Explain) AiState.Loading else state,
        ready = ready,
        onAsk = { ask(q) },
        onOpenSettings = onOpenSettings,
        chips = followUps.map { it.chip },
        selectedChip = followUps.indexOf(q).takeIf { it >= 0 },
        onChip = { i ->
            val which = followUps[i]
            if (q == which) { q = MetricQuestion.Explain; if (states[MetricQuestion.Explain] == null) ask(MetricQuestion.Explain) }
            else if (states[which] is AiState.Done) q = which else ask(which)
        },
    )
}

private fun cachedState(ctx: Context, date: String, metric: Metric, q: MetricQuestion): AiState? =
    MetricExplainer.cached(ctx, date, metric, q)?.let { AiState.Done(it.text) }
