package app.baseline.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.SentimentDissatisfied
import androidx.compose.material.icons.rounded.SentimentNeutral
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.Sick
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.baseline.Model
import app.baseline.data.Feel
import app.baseline.data.Journal
import app.baseline.data.LifeMode
import app.baseline.data.ModeSpan
import app.baseline.insight.Confidence
import app.baseline.insight.FeelMatch
import app.baseline.insight.Level
import app.baseline.insight.Rules
import app.baseline.insight.Signal
import app.baseline.insight.Verb
import app.baseline.ui.theme.Brand
import app.baseline.ui.theme.Numeric

// ---- Shared bits -------------------------------------------------------------------------------------------

fun feelIcon(f: Feel): ImageVector = when (f) {
    Feel.Drained -> Icons.Rounded.SentimentDissatisfied
    Feel.Ok -> Icons.Rounded.SentimentNeutral
    Feel.Great -> Icons.Rounded.SentimentSatisfied
}

fun modeIcon(m: LifeMode): ImageVector = when (m) {
    LifeMode.Rest -> Icons.Rounded.SelfImprovement
    LifeMode.Sick -> Icons.Rounded.Sick
    LifeMode.Travel -> Icons.Rounded.Flight
}

/** Arc fill for calm mode: conveys the state without a number. */
fun verbFraction(v: Verb?): Float? = when (v) {
    Verb.Push -> 1f
    Verb.Ready -> .75f
    Verb.Easy -> .5f
    Verb.Recover -> .25f
    null -> null
}

/** A small flat label: "Low confidence", "Calibrating", "9 of 14 nights". */
@Composable
fun QuietChip(text: String, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier) {
        Text(
            text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** The chip a readiness verdict carries when it shouldn't be read as confident, or null. */
fun readinessChip(m: Model): String? {
    val r = m.insights.readiness
    return when {
        r.calibratingNights != null -> "${r.calibratingNights} of ${Rules.MIN_BASELINE} nights"
        r.confidence == Confidence.Low -> Confidence.Low.label
        else -> null
    }
}

fun signalChip(s: Signal): String? = when (s.confidence) {
    Confidence.High -> null
    Confidence.Low -> if (s.today == null) null else Confidence.Low.label // "No reading" already says it
    Confidence.Calibrating -> Confidence.Calibrating.label
}

// ---- "How do you feel?" ------------------------------------------------------------------------------------

@Composable
fun CheckIn(m: Model, onFeel: (Feel?) -> Unit, onTakeItEasy: () -> Unit) {
    val feel = m.insights.feel
    var editing by rememberSaveable(m.insights.today.toString()) { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    if (feel == null || editing) {
        Column {
            Text("How do you feel?", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Feel.entries.forEach { f ->
                    val selected = f == feel
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) cs.secondaryContainer else Color.Transparent,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .border(1.dp, if (selected) cs.secondaryContainer else cs.outlineVariant, RoundedCornerShape(16.dp))
                            .clickable { onFeel(f); editing = false },
                    ) {
                        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(feelIcon(f), null, tint = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(f.label, style = MaterialTheme.typography.labelLarge, color = if (selected) cs.onSecondaryContainer else cs.onSurface)
                        }
                    }
                }
            }
        }
    } else {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(feelIcon(feel), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    when (feel) { Feel.Drained -> "You feel drained"; Feel.Ok -> "You feel OK"; Feel.Great -> "You feel great" },
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { editing = true }) { Text("Change") }
            }
            if (feel == Feel.Drained && m.insights.mode == null) {
                TextButton(onClick = onTakeItEasy, modifier = Modifier.padding(start = 20.dp)) { Text("Taking it easy? Pause targets") }
            }
        }
    }
}

/** A subtle confirmation under the verb when the check-in agrees with the numbers. */
@Composable
fun FeelAgreement(m: Model) {
    if (m.insights.feelMatch != FeelMatch.Agrees) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("Matches how you feel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Rest / Sick / Travel ----------------------------------------------------------------------------------

private fun modeTitle(m: LifeMode) = when (m) {
    LifeMode.Rest -> "Rest mode"
    LifeMode.Sick -> "Sick mode"
    LifeMode.Travel -> "Travel mode"
}

private fun modeLine(m: LifeMode) = when (m) {
    LifeMode.Rest -> "A lighter stretch. Targets pause."
    LifeMode.Sick -> "Not feeling well. Targets pause, and these days stay out of your normal."
    LifeMode.Travel -> "Away from your routine. Targets pause, and these days stay out of your normal."
}

/** The calm banner shown on Today while a mode is on. */
@Composable
fun ModeBanner(span: ModeSpan, m: Model, onOff: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val day = span.dayOf(m.insights.today).coerceIn(1, Journal.MODE_MAX_DAYS)
    Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(modeIcon(span.mode), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${modeTitle(span.mode)} · day $day of ${Journal.MODE_MAX_DAYS}", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (span.mode.excludedFromBaseline) "Targets are paused, and these days won't count toward your normal." else "Targets are paused.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOff) { Text("Turn off") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSheet(active: ModeSpan?, onPick: (LifeMode?) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = cs.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text("Taking it easy?", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "Pause your targets for a while. Baseline keeps it gentle until you turn it off, or for ${Journal.MODE_MAX_DAYS} days.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LifeMode.entries.forEach { mode ->
                val on = active?.mode == mode
                ListItem(
                    leadingContent = { Icon(modeIcon(mode), null, tint = if (on) cs.primary else cs.onSurfaceVariant) },
                    headlineContent = { Text(mode.label) },
                    supportingContent = { Text(modeLine(mode)) },
                    trailingContent = if (on) ({ Icon(Icons.Rounded.Check, "On", tint = cs.primary) }) else null,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onPick(if (on) null else mode) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            if (active != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) { Text("Turn off ${modeTitle(active.mode).lowercase()}") }
            }
        }
    }
}

// ---- "Why this score" --------------------------------------------------------------------------------------

/** Plain reasons a verdict is low confidence. */
private fun lowConfidenceReasons(m: Model): List<String> {
    val i = m.insights
    return buildList {
        if (i.hrv.today == null) add("there's no HRV reading from last night")
        if (i.rhr.today == null) add("there's no resting heart rate from last night")
        val n = listOfNotNull(i.hrv.baseline?.n, i.rhr.baseline?.n).minOrNull()
        if (n != null && n < Rules.CONFIDENT_BASELINE) add("your normal is based on $n nights so far")
        if (i.sleep.asleepMin == null) add("no sleep was recorded")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhySheet(m: Model, onHistory: () -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cs = MaterialTheme.colorScheme
    val h = Brand.health
    val r = m.insights.readiness
    var raw by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = cs.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(
                if (r.verb == null) r.headline else "Why “${r.verb.label}” today",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(6.dp))
            val intro = when {
                r.calibratingNights != null ->
                    "Readiness unlocks after ${Rules.MIN_BASELINE} nights with HRV (${r.calibratingNights} so far). Until then you see Zepp's own score."
                r.score == null -> "Waiting for last night's readings. Until they arrive you see Zepp's own score."
                r.confidence == Confidence.Low -> "Low confidence: ${lowConfidenceReasons(m).joinToString(", ").ifBlank { "there's less data than usual" }}."
                else -> "Each signal is compared with your own normal from the last ${Rules.BASELINE_DAYS} days."
            }
            Text(intro, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            r.contributors.forEachIndexed { idx, c ->
                if (idx > 0) HorizontalDivider(Modifier.padding(vertical = 12.dp), color = cs.outlineVariant.copy(alpha = .4f))
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(if (c.level == Level.Unknown) cs.outlineVariant else h.of(c.level)))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(c.name, style = MaterialTheme.typography.titleSmall)
                        Text(c.sentence, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        if (raw) {
                            Spacer(Modifier.height(4.dp))
                            Text(c.raw, style = MaterialTheme.typography.labelMedium.merge(Numeric), color = cs.onSurface)
                        }
                    }
                }
            }
            m.insights.feel?.let { f ->
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(feelIcon(f), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when (m.insights.feelMatch) {
                            FeelMatch.Agrees -> "You said you feel ${f.label.lowercase()}, which matches the numbers."
                            FeelMatch.FeelsWorse -> "You said you feel drained, so today's suggestion is gentler than the numbers alone."
                            FeelMatch.FeelsBetter -> "You said you feel great, so today's suggestion leans on how you feel."
                            null -> "You said you feel ${if (f == Feel.Ok) "OK" else f.label.lowercase()}."
                        },
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show the numbers", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = raw, onCheckedChange = { raw = it })
            }
            if (raw) {
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        r.score?.let { append("Score $it. ") }
                        append("Readiness = 70 + 15 × (0.5 × HRV z + 0.3 × resting HR z + 0.2 × sleep vs need). ")
                        append("z is how many standard deviations last night sits from your normal; positive is better.")
                        if (r.score == null) r.zeppScore?.let { append(" Zepp's own score today: $it.") }
                    },
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp), color = cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("See history", fontWeight = FontWeight.Medium) }
        }
    }
}
