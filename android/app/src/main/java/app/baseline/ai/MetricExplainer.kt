package app.baseline.ai

import android.content.Context
import app.baseline.Model
import app.baseline.insight.InsightEngine
import app.baseline.insight.Stats
import app.baseline.ui.story.Metric
import app.baseline.ui.story.Spec
import app.baseline.ui.story.now
import app.baseline.ui.story.takeaway
import app.baseline.ui.story.values
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** The questions a metric page can ask. The first is the card's main action; the rest are follow-up chips. */
enum class MetricQuestion(val chip: String, val instruction: String) {
    Explain(
        "Explain",
        "Explain what my numbers mean, what most likely drives them, and one or two things to try.",
    ),
    WhyChanged(
        "Why did it change?",
        "Focus on how the last week compares with the weeks before and the most likely reasons for the change (or lack of one). End with one thing to try.",
    ),
    Improve(
        "How do I improve it?",
        "Focus on the two or three most effective, realistic things I can do over the next few weeks, given my numbers. Say what change to expect and how long it usually takes.",
    ),
    Normal(
        "Is this normal for me?",
        "Focus on how today and this week compare with my own usual range and with typical values for adults, and say whether anything looks worth watching. Don't diagnose.",
    ),
}

@Serializable
data class Explanation(val date: String, val metric: String, val question: String, val text: String, val model: String)

/**
 * "Explain my <metric>": builds a compact, de-identified digest of one metric (summary statistics only — no
 * raw readings, timestamps, names or identifiers), asks Gemini for a short plain-language explanation and
 * caches the answer per metric, per question, per day.
 */
object MetricExplainer {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    const val SYSTEM = "You are a calm, practical health guide inside a personal health dashboard. You explain one " +
        "metric to the person it belongs to, using only the summary you are given. Write 4 to 6 short sentences of " +
        "plain text in the second person: no lists, headings, markdown or emoji. Use everyday words and explain any " +
        "term you must use. Compare with their own normal first, then with typical adult values if useful. When you " +
        "name likely causes (sleep, training, stress, alcohol, illness, travel, measurement quirks), only use what the " +
        "data supports and say 'often' or 'likely'. Wearable numbers are estimates: trends matter more than single days. " +
        "Never diagnose or name a disease. If something has looked off for a while, suggest checking in with a doctor. " +
        "Don't invent numbers that aren't given."

    fun ready(m: Model) = m.settings.aiEnabled && m.settings.geminiKey.isNotBlank()

    /** Summary statistics for one metric. Kept small and free of anything that identifies the person. */
    fun digest(m: Model, metric: Metric, sp: Spec): String = buildString {
        val i = m.insights
        val n = now(m, metric, sp)
        val u = sp.unit.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        fun f(v: Double?) = v?.let { sp.format(it) + u } ?: "n/a"
        val v90 = values(m, metric, 90)
        val v30 = v90.takeLast(30).filterNotNull()
        val last7 = Stats.mean(v90.takeLast(7).filterNotNull())
        val prev28 = Stats.mean(v90.dropLast(7).takeLast(28).filterNotNull())

        appendLine("Metric: ${metric.label}${if (sp.unit.isNotBlank()) " (unit: ${sp.unit})" else ""}.")
        appendLine("Higher is ${when (sp.higherIsBetter) { true -> "usually better"; false -> "usually worse"; null -> "neither better nor worse in itself" }}.")
        appendLine("Today / latest: ${n.value}${if (n.unit.isNotBlank()) " ${n.unit}" else ""}. App's status: ${n.status.ifBlank { "n/a" }}.")
        if (v30.isNotEmpty()) appendLine("Last 30 days: average ${f(Stats.mean(v30))}, lowest ${f(v30.min())}, highest ${f(v30.max())}, ${v30.size} days with data.")
        Stats.mean(v90.filterNotNull())?.let { appendLine("Last 90 days: average ${f(it)} over ${v90.count { v -> v != null }} days with data.") }
        if (last7 != null) {
            append("Last 7 days average ${f(last7)}")
            if (prev28 != null && prev28 != 0.0) {
                val pct = (last7 - prev28) / prev28 * 100
                append(" vs ${f(prev28)} over the 4 weeks before (${if (abs(pct) < 3) "steady" else "%+.0f%%".format(pct)})")
            }
            appendLine(".")
        }
        val slope = Stats.slope(v90.takeLast(30).filterNotNull())
        if (slope != null && v30.size >= 10) appendLine("30-day direction: ${if (abs(slope) * 30 < (Stats.sd(v30) ?: 0.0) * 0.5) "flat" else if (slope > 0) "rising" else "falling"}.")
        when (metric) {
            Metric.Hrv -> i.hrv.baseline?.let { appendLine("Personal normal range (previous 60 days): ${f(it.low)} to ${f(it.high)}.") }
            Metric.RestingHr -> i.rhr.baseline?.let { appendLine("Personal normal range (previous 60 days): ${f(it.low)} to ${f(it.high)}.") }
            Metric.Breathing -> i.resp.baseline?.let { appendLine("Personal normal range (previous 60 days): ${f(it.low)} to ${f(it.high)}.") }
            Metric.Readiness -> {
                appendLine("Readiness is the app's own score: 70 is a typical day, 62 to 80 is the normal band.")
                i.readiness.contributors.forEach { appendLine("- ${it.name}: ${it.detail}") }
            }
            Metric.Steps -> appendLine("Daily step goal: ${"%,d".format(m.settings.stepGoal)}.")
            Metric.Active -> appendLine("Weekly active-minutes goal: ${m.settings.weeklyActiveGoal} (WHO suggests 150 to 300).")
            Metric.Sleep -> appendLine("Personal sleep need: ${InsightEngine.fmtDuration(m.settings.sleepNeedMin)}; sleep debt over 7 nights: ${InsightEngine.fmtDuration(i.sleep.debt7Min)}.")
            Metric.Consistency -> appendLine("Value is how much bedtime moves night to night (standard deviation in minutes); under 30 is regular.")
            else -> {}
        }
        appendLine("App's own takeaway: ${takeaway(m, metric, sp)}")
        // A little context so the answer can point at likely drivers.
        appendLine("Context:")
        if (metric != Metric.Sleep) i.sleep.avg7Min?.let { appendLine("- Sleep, 7-night average: ${InsightEngine.fmtDuration(it.roundToInt())} vs need ${InsightEngine.fmtDuration(i.sleep.needMin)}.") }
        if (metric != Metric.Readiness) i.readiness.score?.let { appendLine("- Readiness today: $it (${i.readiness.headline}).") }
        if (metric != Metric.Active) appendLine("- Active minutes, last 7 days: ${i.activity.activeWeek} of ${i.activity.activeGoal}.")
        if (metric != Metric.Load) i.activity.load?.let { appendLine("- Training load: ${it.label}.") }
        if (i.illnessFlag) appendLine("- Resting heart rate and breathing rate are both above normal right now.")
    }

    fun prompt(metric: Metric, question: MetricQuestion, digest: String): String =
        "${question.instruction}\n\nMy ${metric.label.lowercase()} summary:\n$digest"

    // --- cache: one small file, today's answers only

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "cache/explain.json")
    private val listSer = ListSerializer(Explanation.serializer())

    private fun readAll(ctx: Context): List<Explanation> =
        runCatching { json.decodeFromString(listSer, cacheFile(ctx).readText()) }.getOrDefault(emptyList())

    fun cached(ctx: Context, date: String, metric: Metric, q: MetricQuestion): Explanation? =
        readAll(ctx).firstOrNull { it.date == date && it.metric == metric.name && it.question == q.name }

    fun clear(ctx: Context) { cacheFile(ctx).delete() }

    suspend fun generate(ctx: Context, m: Model, metric: Metric, sp: Spec, q: MetricQuestion): Result<Explanation> =
        withContext(Dispatchers.IO) {
            runCatching {
                val model = m.settings.geminiModel.ifBlank { Gemini.DEFAULT_TEXT_MODEL }
                val text = Gemini.generate(
                    key = m.settings.geminiKey, model = model, system = SYSTEM,
                    parts = listOf(Gemini.Part.Text(prompt(metric, q, digest(m, metric, sp)))),
                    temperature = 0.4, maxOutputTokens = 2048,
                )
                val date = m.insights.today.toString()
                val e = Explanation(date, metric.name, q.name, text, model)
                lock.withLock {
                    val keep = readAll(ctx).filter { it.date == date && !(it.metric == metric.name && it.question == q.name) }
                    cacheFile(ctx).parentFile?.mkdirs()
                    cacheFile(ctx).writeText(json.encodeToString(listSer, keep + e))
                }
                e
            }
        }
}
