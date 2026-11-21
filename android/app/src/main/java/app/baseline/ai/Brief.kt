package app.baseline.ai

import android.content.Context
import app.baseline.insight.InsightEngine
import app.baseline.insight.Insights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

@Serializable
data class Brief(val date: String, val text: String, val model: String)

/**
 * Optional daily brief written by Google Gemini with the user's own key.
 * Only a short text digest of already-computed judgements is sent — no raw samples, no timestamps of
 * readings, no names or identifiers. Cached per day so it costs one request a day at most.
 */
object BriefService {
    private val json = Json { ignoreUnknownKeys = true }

    fun digest(i: Insights): String = buildString {
        val r = i.readiness
        appendLine("Readiness: ${r.score ?: "not yet calibrated"} (${r.headline}). Zepp's own readiness: ${r.zeppScore ?: "n/a"}.")
        r.contributors.forEach { appendLine("- ${it.name}: ${it.detail}") }
        i.hrv.avg7?.let { appendLine("HRV 7-day average: ${it.roundToInt()} ms; normal range ${i.hrv.baseline?.let { b -> "${b.low.roundToInt()}-${b.high.roundToInt()}" } ?: "n/a"}.") }
        i.rhr.avg7?.let { appendLine("Resting HR 7-day average: ${it.roundToInt()} bpm.") }
        i.resp.today?.let { appendLine("Breathing rate last night: ${"%.1f".format(it)} /min (usual ${i.resp.baseline?.mean?.let { b -> "%.1f".format(b) } ?: "n/a"}).") }
        val s = i.sleep
        appendLine("Last night asleep: ${s.asleepMin?.let { InsightEngine.fmtDuration(it) } ?: "no data"} vs need ${InsightEngine.fmtDuration(s.needMin)}; 7-night average ${s.avg7Min?.let { InsightEngine.fmtDuration(it.roundToInt()) } ?: "n/a"}; sleep debt this week ${InsightEngine.fmtDuration(s.debt7Min)}.")
        appendLine("Bedtime variability: ${s.bedtimeSdMin?.let { "±${it.roundToInt()} min" } ?: "n/a"}; suggested bedtime ${s.suggestedBedtime?.let { InsightEngine.fmtTime(it) } ?: "n/a"}.")
        val a = i.activity
        appendLine("Steps today so far: ${a.stepsToday ?: "n/a"} (goal ${a.stepGoal}); 7-day average ${a.steps7Avg?.roundToInt() ?: "n/a"}.")
        appendLine("Active minutes last 7 days: ${a.activeWeek} of ${a.activeGoal}. PAI: ${a.paiWeek?.roundToInt() ?: "n/a"}. Workouts this week: ${a.workoutsWeek.size}.")
        a.load?.let { appendLine("Training load: ${it.label} (7d ${it.acute.roundToInt()}, 42d ${it.chronic.roundToInt()}).") }
        a.vo2max?.let { appendLine("VO2max estimate: ${"%.1f".format(it)}.") }
        if (i.illnessFlag) appendLine("Flag: resting HR and breathing rate both elevated vs normal (possible illness).")
        appendLine("App's rule-based focus for today: ${i.focus.title} — ${i.focus.body}")
    }

    private const val SYSTEM = "You are a calm, practical health coach inside a personal dashboard app. " +
        "Write a brief for today in 3 short sentences, under 70 words, second person, plain text, no lists, no emoji. " +
        "Sentence 1: the single most important thing the numbers say. Sentence 2: why, citing at most two numbers. " +
        "Sentence 3: one concrete action for today or tonight. Never diagnose; if numbers suggest illness say to rest " +
        "and see a doctor if symptoms appear. Don't invent data that isn't given."

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "cache/brief.json")

    fun cached(ctx: Context, date: String): Brief? = runCatching {
        json.decodeFromString(Brief.serializer(), cacheFile(ctx).readText()).takeIf { it.date == date }
    }.getOrNull()

    fun clear(ctx: Context) { cacheFile(ctx).delete() }

    suspend fun generate(ctx: Context, i: Insights, key: String, model: String): Result<Brief> = withContext(Dispatchers.IO) {
        runCatching {
            val m = URLEncoder.encode(model.ifBlank { "gemini-3.5-flash-lite" }, "UTF-8")
            val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000; conn.readTimeout = 45_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key.trim())
            val body = buildJsonObject {
                putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", SYSTEM) }) } }
                putJsonArray("contents") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", "Today's numbers:\n" + digest(i)) }) }
                    })
                }
                putJsonObject("generationConfig") { put("temperature", 0.5); put("maxOutputTokens", 1024) }
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull()
                error(when (code) { 400, 403 -> "Gemini rejected the request: ${msg ?: "check the API key"}"; 404 -> "Model not found: $model"; 429 -> "Gemini rate limit — try later"; else -> "Gemini HTTP $code ${msg ?: ""}" })
            }
            val root = json.parseToJsonElement(text).jsonObject
            val out = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }?.joinToString("")?.trim()
            require(!out.isNullOrBlank()) { "Gemini returned no text" }
            Brief(i.today.toString(), out, model).also {
                cacheFile(ctx).parentFile?.mkdirs()
                cacheFile(ctx).writeText(json.encodeToString(Brief.serializer(), it))
            }
        }
    }

}
