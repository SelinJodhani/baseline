package app.baseline.ui.healthspan

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.baseline.Model
import app.baseline.ai.AiCard
import app.baseline.ai.AiState
import app.baseline.ai.Gemini
import app.baseline.insight.Cha
import app.baseline.insight.Level
import app.baseline.insight.ageOf
import app.baseline.labs.LabDates
import app.baseline.labs.LabsViewModel
import app.baseline.labs.PhenoAge
import app.baseline.labs.fmtNum
import app.baseline.labs.ui.Disclaimer
import app.baseline.labs.ui.OverlayPage
import app.baseline.labs.ui.SectionLabel
import app.baseline.ui.charts.ChartLabels
import app.baseline.ui.charts.ChartLegend
import app.baseline.ui.charts.KeyKind
import app.baseline.ui.charts.LegendKey
import app.baseline.ui.charts.TrendChart
import app.baseline.ui.common.Fmt
import app.baseline.ui.common.LevelPill
import app.baseline.ui.common.SectionCard
import app.baseline.ui.story.Metric
import app.baseline.ui.theme.Brand
import app.baseline.ui.theme.Numeric
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Everything the Healthspan page needs, computed once from the model. */
private class Healthspan(m: Model) {
    val e = m.engine
    val age: Int? = ageOf(m.settings, e.today)
    val female: Boolean? = when (m.settings.sex) { "female" -> true; "male" -> false; else -> null }
    val weight: Double? = m.data.summary?.weights?.maxByOrNull { it.t }?.kg
    val adv = m.insights.advanced ?: e.advanced.insights
    val days: Map<LocalDate, app.baseline.data.Day> = m.data.summary?.days.orEmpty()
        .mapNotNull { d -> runCatching { LocalDate.parse(d.date) }.getOrNull()?.let { it to d } }.toMap()
    /** Yesterday: today is still in progress. */
    val end: LocalDate = e.today.minusDays(1)
    val cha: Cha.Result? = Cha.compute(days, e.mainSleeps, e.zone, end, age, female, weight, adv.vo2Median)
    val whatIfs: List<Cha.WhatIf> = cha?.let { Cha.whatIfs(it, age, female, weight) }.orEmpty()
    val history: List<Pair<LocalDate, Double?>> = Cha.history(days, e.mainSleeps, e.zone, end)
}

/** "+0.3 years" / "−2.1 years". */
private fun years(v: Double): String = "${if (v >= 0) "+" else "−"}${"%.1f".format(abs(v))} ${if (abs(v) >= 0.95 && abs(v) < 1.05) "year" else "years"}"

private fun gapWords(gap: Double): Pair<String, Level> = when {
    gap <= -2 -> "Your habits look ${"%.0f".format(-gap)} years younger" to Level.Great
    gap <= -0.5 -> "Your habits look a little younger than your age" to Level.Great
    gap < 0.5 -> "Your habits look about your age" to Level.Good
    gap < 2 -> "Your habits look a little older than your age" to Level.Fair
    else -> "Your habits look ${"%.0f".format(gap)} years older" to Level.Low
}

private fun leverValue(p: Cha.Part): String = when (p.lever) {
    Cha.Lever.Sleep -> "${Fmt.duration((p.value * 60).roundToInt())} a night"
    Cha.Lever.RestingHr -> "${p.value.roundToInt()} bpm (daily low)"
    Cha.Lever.ActiveZone -> "${p.value.roundToInt()} min a week"
    Cha.Lever.Steps -> "${"%,d".format(p.value.roundToInt())} a day"
    Cha.Lever.Regularity -> "±${p.value.roundToInt()} min midpoint"
    Cha.Lever.Fitness -> "VO₂max ${fmtNum(p.value)}"
}

/** The Healthspan row at the top of the Body tab. */
@Composable
fun HealthspanEntry(m: Model, onClick: () -> Unit) {
    val hs = remember(m) { Healthspan(m) }
    val cs = MaterialTheme.colorScheme
    val line = hs.cha?.let { r ->
        hs.age?.let { a -> "Health age ${"%.0f".format(r.healthAge(a))} · you're $a" } ?: "Habits: ${years(r.gapYears)} vs your age"
    } ?: "Needs about 5 weeks of sleep, steps and heart rate"
    Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerLow, modifier = Modifier.clip(RoundedCornerShape(28.dp)).clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(Brand.health.great.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Spa, null, tint = Brand.health.great, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Healthspan", style = MaterialTheme.typography.titleMedium)
                Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
        }
    }
}

/**
 * Healthspan: how your habits (Coachable Health Age), fitness (VO₂max) and blood (Phenotypic Age) compare with
 * your age — as trends with the levers that move them, never as a daily score.
 */
@Composable
fun HealthspanScreen(m: Model, onBack: () -> Unit, onOpenSettings: () -> Unit, onLabs: () -> Unit, onMetric: (Metric) -> Unit, labsVm: LabsViewModel = viewModel()) {
    val hs = remember(m) { Healthspan(m) }
    val labs by labsVm.data.collectAsStateWithLifecycle()
    val pheno = remember(labs, m.settings.birthYear) { PhenoAge.fromLabs(labs, m.settings.birthYear) }
    val h = Brand.health

    OverlayPage("Healthspan", "How your habits, fitness and blood compare with your age", onBack) {
        // --- Habits
        item {
            val r = hs.cha
            SectionCard {
                CardTitle(Icons.Rounded.Favorite, "Habits", h.great, "Coachable health age")
                Spacer(Modifier.height(14.dp))
                if (r == null) {
                    Text(
                        "This needs at least 5 weeks in the last 13 with two or more days of steps, sleep and heart rate each. Keep wearing your watch day and night.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@SectionCard
                }
                val (words, level) = gapWords(r.gapYears)
                Row(verticalAlignment = Alignment.Bottom) {
                    val big = hs.age?.let { "%.0f".format(r.healthAge(it)) } ?: years(r.gapYears).substringBefore(" ")
                    Text(big, style = MaterialTheme.typography.displayMedium.merge(Numeric), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        hs.age?.let { "health age · you're $it" } ?: "years vs your age",
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                LevelPill(words, level)
                if (hs.age == null) TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 4.dp)) { Text("Add your birth year to see your health age") }
                Spacer(Modifier.height(18.dp))
                Text("What each habit adds or takes off", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                val scale = max(1.0, r.parts.maxOf { abs(it.years) })
                r.parts.sortedByDescending { abs(it.years) }.forEach { p -> LeverRow(p, scale) }
                r.missing.forEach { l ->
                    Text(
                        "${l.label}: " + if (l == Cha.Lever.ActiveZone) "needs Baseline Bridge 0.4 or newer (counted as 0 for now)" else "no data yet (counted as 0)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "From ${r.days} days across ${r.validWeeks} weeks, ending ${Fmt.dayMonth(hs.end)}. Green takes years off, amber adds them; zero means you're already in the no-effect range.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // --- What would help
        if (hs.whatIfs.isNotEmpty()) item {
            SectionLabel("What would move it")
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    hs.whatIfs.take(4).forEachIndexed { i, w ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(w.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(12.dp))
                            Surface(shape = CircleShape, color = h.great.copy(alpha = .16f)) {
                                Text(
                                    "−${"%.1f".format(w.yearsGained)} y", style = MaterialTheme.typography.labelLarge.merge(Numeric),
                                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Exact changes to the formula, holding everything else the same. They show where the years are; your body's response will vary.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        // --- Trend
        if (hs.history.count { it.second != null } >= 2) item {
            val pts = hs.history
            val vals = pts.map { p -> p.second?.let { g -> hs.age?.let { a -> max(Cha.HEALTH_AGE_FLOOR, a + g) } ?: g } }
            SectionCard {
                Text("Last 6 months", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                TrendChart(
                    values = vals, color = h.great,
                    labels = ChartLabels(pts.map { Fmt.dayMonth(it.first) }) { "%.1f".format(it) },
                    goal = hs.age?.toDouble() ?: 0.0, goalLabel = "Your age",
                    tooltip = { i -> "Week to ${Fmt.dayMonth(pts[i].first)}\n" + (vals[i]?.let { v -> if (hs.age != null) "Health age ${"%.1f".format(v)}" else years(v) } ?: "Not enough data") },
                    height = 180.dp,
                )
                Spacer(Modifier.height(8.dp))
                ChartLegend(listOf(LegendKey(KeyKind.Line, h.great, if (hs.age != null) "Health age, each week's 90 days" else "Years vs your age"), LegendKey(KeyKind.Dash, h.great, "Your age")))
            }
        }
        // --- AI plan
        hs.cha?.let { r -> item { PlanCard(m, hs, r, onOpenSettings) } }
        // --- Fitness
        item {
            SectionCard(onClick = { onMetric(Metric.Vo2) }) {
                CardTitle(Icons.Rounded.DirectionsRun, "Fitness", h.steps, "VO₂max for your age")
                Spacer(Modifier.height(12.dp))
                val band = hs.adv.vo2Band
                val vo2 = hs.adv.vo2Median
                Text(
                    when {
                        band != null -> "VO₂max ${fmtNum(band.vo2)}: ${band.text}."
                        vo2 != null -> "VO₂max ${fmtNum(vo2)}. Add your birth year and sex in Settings to compare it with your age group."
                        else -> "No VO₂max yet. Your watch estimates it from outdoor runs or brisk walks with GPS and heart rate. It's the single strongest fitness link to a long life, so it's worth getting."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (vo2 != null) Text(
                    "Counted in your health age as an optional term (each 3.5 ml/kg/min above expected takes off about 1.3 years).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        // --- Blood
        item { BloodCard(pheno, onLabs, onOpenSettings) }
        // --- How it works
        item {
            SectionCard {
                Text("How this works", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Habits use Google Research's Coachable Health Age (2026). Your 90-day averages of sleep, daily lowest heart rate, steps and active zone minutes " +
                        "are each turned into a risk from large studies, weighted so overlapping habits aren't counted twice, and converted into years " +
                        "(about 10% more risk per year of age). Sleep timing and VO₂max are added when available. Every input is something you can change.\n\n" +
                        "Blood uses Phenotypic Age (Levine, 2018): nine routine markers trained to predict ten-year mortality in tens of thousands of people in the US national health survey.\n\n" +
                        "Neither is a diagnosis. The habit score was validated on Fitbit users; Baseline computes active zone minutes from your watch's minute heart rate the same way. " +
                        "Read both as trends over months, not a verdict on any one week.",
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { Disclaimer("Baseline is not a medical device. These estimates come from population studies and can't account for everything about you.") }
    }
}

@Composable
private fun CardTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, tint: Color, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(tint.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One habit: name and value on the left, a bar growing from the centre (green = years off, amber = years on). */
@Composable
private fun LeverRow(p: Cha.Part, scale: Double) {
    val h = Brand.health
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val c = if (p.years < -0.05) h.great else if (p.years > 0.05) h.fair else MaterialTheme.colorScheme.outline
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.lever.label, style = MaterialTheme.typography.bodyLarge)
                Text(leverValue(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (abs(p.years) < 0.05) "0" else "${if (p.years > 0) "+" else "−"}${"%.1f".format(abs(p.years))} y",
                style = MaterialTheme.typography.titleMedium.merge(Numeric), fontWeight = FontWeight.SemiBold, color = c,
            )
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val mid = size.width / 2
            drawRoundRect(track, Offset.Zero, size, CornerRadius(size.height / 2))
            drawRect(track.copy(alpha = 1f), Offset(mid - 1, 0f), Size(2f, size.height))
            val w = (abs(p.years) / scale).toFloat().coerceIn(0f, 1f) * mid
            if (w > 1f) {
                val x = if (p.years < 0) mid - w else mid
                drawRoundRect(c, Offset(x, 0f), Size(w, size.height), CornerRadius(size.height / 2))
            }
            drawRect(Color.Gray.copy(alpha = .5f), Offset(mid - 1, -2f), Size(2f, size.height + 4))
        }
    }
}

@Composable
private fun BloodCard(p: PhenoAge.Outcome?, onLabs: () -> Unit, onOpenSettings: () -> Unit) {
    val h = Brand.health
    SectionCard(onClick = onLabs) {
        CardTitle(Icons.Rounded.Bloodtype, "Blood", h.heart, "Phenotypic age from your lab reports")
        Spacer(Modifier.height(12.dp))
        when (p) {
            is PhenoAge.Result -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%.0f".format(p.phenoAge), style = MaterialTheme.typography.displaySmall.merge(Numeric), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text("blood age · you were ${p.age.roundToInt()}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                }
                val g = p.gap
                LevelPill(
                    when { g <= -2 -> "${"%.0f".format(-g)} years younger than your age"; g < 2 -> "About your age"; else -> "${"%.0f".format(g)} years older than your age" },
                    when { g <= -2 -> Level.Great; g < 2 -> Level.Good; g < 5 -> Level.Fair; else -> Level.Low },
                )
                Spacer(Modifier.height(12.dp))
                Text("From your test on ${LabDates.pretty(p.date)}. Biggest effects:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                p.effects.entries.sortedByDescending { abs(it.value) }.take(3).forEach { (input, yrs) ->
                    Text(
                        "${input.label} ${fmtNum(p.values.getValue(input))}: ${if (yrs >= 0) "adds" else "takes off"} ${"%.1f".format(abs(yrs))} years versus a typical healthy value",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            is PhenoAge.NeedsAge -> {
                Text("Your test on ${LabDates.pretty(p.date)} has all nine markers. Add your birth year to see your blood age.", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onOpenSettings) { Text("Open Settings") }
            }
            is PhenoAge.Missing -> {
                Text(
                    "Your closest report (${LabDates.pretty(p.date)}) is missing ${p.missing.joinToString(", ") { it.label }}. " +
                        "A complete blood count plus a basic metabolic panel, CRP and fasting glucose usually covers all nine.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            null -> Text(
                "Add a blood test with albumin, creatinine, fasting glucose, CRP, lymphocytes, MCV, RDW, ALP and white cells to see your blood age. Most routine check-ups include them.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------- AI plan

@Serializable
private data class CachedPlan(val key: String, val text: String)

private object PlanCache {
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(ctx: Context) = File(ctx.filesDir, "cache/healthspan_plan.json")
    fun get(ctx: Context, key: String): String? = runCatching { json.decodeFromString(CachedPlan.serializer(), file(ctx).readText()) }.getOrNull()?.takeIf { it.key == key }?.text
    fun put(ctx: Context, key: String, text: String) {
        file(ctx).parentFile?.mkdirs(); file(ctx).writeText(json.encodeToString(CachedPlan.serializer(), CachedPlan(key, text)))
    }
}

private const val PLAN_SYSTEM = "You are a calm, practical health coach inside a personal dashboard app. Write a 12-week plan in plain text: " +
    "three phases of four weeks, each with one or two concrete weekly targets and one sentence on how to make it easy. Under 160 words, " +
    "second person, no emoji, no medical claims, no diagnosis. Prioritise the changes worth the most years, build gradually, and keep " +
    "rest and sleep protected. Use only the numbers given."

@Composable
private fun PlanCard(m: Model, hs: Healthspan, r: Cha.Result, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val ready = m.settings.aiEnabled && m.settings.geminiKey.isNotBlank()
    val key = "${hs.end}:${"%.1f".format(r.gapYears)}"
    var state by remember(key) { mutableStateOf(PlanCache.get(ctx, key)?.let { AiState.Done(it) } ?: AiState.Idle) }
    val scope = rememberCoroutineScope()
    fun ask() {
        state = AiState.Loading
        val prompt = buildString {
            appendLine("My Coachable Health Age breakdown over the last 90 days (years added + or taken off −):")
            r.parts.forEach { appendLine("- ${it.lever.label}: ${leverValue(it)} → ${"%+.1f".format(it.years)} years") }
            r.missing.forEach { appendLine("- ${it.label}: no data") }
            appendLine("Changes worth the most, from the formula:")
            hs.whatIfs.forEach { appendLine("- ${it.text}: −${"%.1f".format(it.yearsGained)} years") }
            if (hs.whatIfs.isEmpty()) appendLine("- Everything is already in the no-effect or best range; focus on keeping it there.")
            appendLine("Readiness today: ${m.insights.readiness.headline}.")
        }
        scope.launch {
            state = runCatching {
                Gemini.generate(m.settings.geminiKey, m.settings.geminiModel.ifBlank { Gemini.DEFAULT_TEXT_MODEL }, PLAN_SYSTEM, listOf(Gemini.Part.Text(prompt)), temperature = 0.5, maxOutputTokens = 1024)
            }.fold({ PlanCache.put(ctx, key, it); AiState.Done(it) }, { AiState.Error(it.message ?: "Something went wrong") })
        }
    }
    AiCard(
        title = "Make me a 12-week plan", state = state, ready = ready, onAsk = { ask() }, onOpenSettings = onOpenSettings,
        idleHint = "Turns the changes above into three four-week steps",
        footer = "Written by Gemini from your habit averages only. Not medical advice.",
    )
}
