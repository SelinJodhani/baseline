package app.baseline.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * A small client for the Gemini `generateContent` REST endpoint, called with the user's own API key.
 * Supports plain text prompts, inline files (PDF / image as base64 `inlineData`) and JSON output constrained
 * by a JSON Schema (`responseMimeType: application/json` + `responseJsonSchema`).
 *
 * Nothing here logs or stores the key, the prompt or the file.
 */
object Gemini {
    const val DEFAULT_TEXT_MODEL = "gemini-3.5-flash-lite"
    const val DEFAULT_DOC_MODEL = "gemini-3.6-flash"
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/"

    /** Keep inline uploads well under the API's request limit (base64 adds a third). */
    const val MAX_FILE_BYTES = 20 * 1024 * 1024

    sealed interface Part {
        data class Text(val text: String) : Part
        class File(val mimeType: String, val bytes: ByteArray) : Part
    }

    class GeminiException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }

    /** The request body. Pure, so it can be unit-tested. */
    fun request(
        system: String?,
        parts: List<Part>,
        schema: JsonElement? = null,
        temperature: Double? = null,
        maxOutputTokens: Int = 2048,
    ): JsonObject = buildJsonObject {
        if (!system.isNullOrBlank()) putJsonObject("systemInstruction") {
            putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
        }
        putJsonArray("contents") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    parts.forEach { p ->
                        when (p) {
                            is Part.Text -> add(buildJsonObject { put("text", p.text) })
                            is Part.File -> add(buildJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", p.mimeType)
                                    put("data", Base64.getEncoder().encodeToString(p.bytes))
                                }
                            })
                        }
                    }
                }
            })
        }
        putJsonObject("generationConfig") {
            temperature?.let { put("temperature", it) }
            put("maxOutputTokens", maxOutputTokens)
            if (schema != null) {
                put("responseMimeType", "application/json")
                put("responseJsonSchema", schema)
            }
        }
    }

    /** The concatenated text of the first candidate, or a readable error. Pure, so it can be unit-tested. */
    fun parseText(body: String): String {
        val root = json.parseToJsonElement(body).jsonObject
        root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.content?.let {
            throw GeminiException("Gemini declined this request ($it)")
        }
        val cand = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw GeminiException("Gemini returned no answer")
        val text = cand["content"]?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { (it as? JsonObject)?.takeIf { p -> p["thought"]?.jsonPrimitive?.content != "true" }?.get("text")?.jsonPrimitive?.content }
            ?.joinToString("")?.trim()
        if (text.isNullOrBlank()) {
            val reason = cand["finishReason"]?.jsonPrimitive?.content
            throw GeminiException(
                when (reason) {
                    "MAX_TOKENS" -> "Gemini ran out of room before answering — try again"
                    "SAFETY", "PROHIBITED_CONTENT", "RECITATION" -> "Gemini declined this request ($reason)"
                    else -> "Gemini returned no text"
                },
            )
        }
        return text
    }

    /** Strip a ```json fence if a model adds one despite the JSON mime type. */
    fun stripFence(text: String): String {
        val t = text.trim()
        if (!t.startsWith("```")) return t
        return t.removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
    }

    fun errorMessage(code: Int, body: String, model: String): String {
        val msg = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull()
        return when (code) {
            400, 403 -> "Gemini rejected the request: ${msg ?: "check the API key"}"
            404 -> "Model not found: $model"
            413 -> "That file is too large for Gemini"
            429 -> "Gemini rate limit — try later"
            in 500..599 -> "Gemini is having trouble — try again shortly"
            else -> "Gemini HTTP $code ${msg.orEmpty()}".trim()
        }
    }

    suspend fun generate(
        key: String,
        model: String,
        system: String?,
        parts: List<Part>,
        schema: JsonElement? = null,
        temperature: Double? = null,
        maxOutputTokens: Int = 2048,
        readTimeoutMs: Int = 60_000,
    ): String = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw GeminiException("Add a Gemini API key in Settings → AI")
        val m = URLEncoder.encode(model.trim(), "UTF-8")
        val conn = URL("$ENDPOINT$m:generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key.trim())
            val body = request(system, parts, schema, temperature, maxOutputTokens).toString().toByteArray()
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            if (code !in 200..299) throw GeminiException(errorMessage(code, text, model))
            parseText(text)
        } catch (e: java.net.SocketTimeoutException) {
            throw GeminiException("Gemini took too long — try again")
        } catch (e: java.io.IOException) {
            throw GeminiException("Couldn't reach Gemini — check your connection")
        } finally {
            conn.disconnect()
        }
    }
}
