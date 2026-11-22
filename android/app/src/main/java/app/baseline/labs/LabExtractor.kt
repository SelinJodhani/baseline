package app.baseline.labs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import app.baseline.ai.Gemini
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID

/** What Gemini read off a lab report, normalised. */
data class ExtractedLab(val isLabReport: Boolean, val date: LocalDate?, val lab: String?, val markers: List<LabMarker>)

/** What Gemini read off an InBody result sheet. */
data class ExtractedInBody(val isInBody: Boolean, val scans: List<InBodyScan>) {
    /** The most recent scan. */
    val scan: InBodyScan get() = scans.last()
}

/**
 * Reads lab reports and InBody result sheets with Gemini. The file is read into memory, sent once as inline data
 * and dropped; only the structured values that come back are kept. The response is constrained by a strict
 * JSON Schema, and nothing in the schema asks for names, IDs or other identifiers.
 */
object LabExtractor {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // --- schemas (JSON Schema subset understood by Gemini's responseJsonSchema)

    private fun str(desc: String? = null) = buildJsonObject { put("type", "string"); desc?.let { put("description", it) } }
    private fun nullable(type: String, desc: String? = null) = buildJsonObject {
        put("type", buildJsonArray { add(JsonPrimitive(type)); add(JsonPrimitive("null")) })
        desc?.let { put("description", it) }
    }
    private fun enumOf(values: List<String>, desc: String? = null) = buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(values.map { JsonPrimitive(it) }))
        desc?.let { put("description", it) }
    }
    private fun obj(props: Map<String, JsonElement>) = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        put("required", JsonArray(props.keys.map { JsonPrimitive(it) }))
    }

    val labSchema: JsonObject by lazy {
        val marker = obj(linkedMapOf(
            "name" to str("Test name exactly as printed"),
            "canonicalName" to enumOf(Biomarkers.ids + "other", "Matching canonical id, or 'other' if none fits exactly. Don't map post-meal or random glucose to glucose_fasting."),
            "value" to nullable("number", "Numeric result as printed. For '<5' use 5. Null if the result is not a number."),
            "unit" to nullable("string", "Unit as printed, e.g. mg/dL, mmol/L, %"),
            "refLow" to nullable("number", "Lower end of the printed reference range"),
            "refHigh" to nullable("number", "Upper end of the printed reference range"),
            "flag" to enumOf(listOf("low", "normal", "high", "abnormal", "unknown"), "Flag printed on the report, else judge from the range"),
            "category" to enumOf(LabCategory.entries.map { it.name }),
            "collectedDate" to nullable("string", "Sample collection date as YYYY-MM-DD if printed"),
        ))
        obj(linkedMapOf(
            "isLabReport" to buildJsonObject { put("type", "boolean"); put("description", "True if this is a blood or urine test report") },
            "reportDate" to nullable("string", "Sample collection date, else report date, as YYYY-MM-DD"),
            "labName" to nullable("string", "Name of the laboratory company only, never a person"),
            "biomarkers" to buildJsonObject { put("type", "array"); put("items", marker) },
        ))
    }

    private val segments = listOf("rightArm", "leftArm", "trunk", "rightLeg", "leftLeg")

    val inBodySchema: JsonObject by lazy {
        val seg = obj(linkedMapOf(
            "part" to enumOf(segments),
            "kg" to nullable("number", "Mass in kg"),
            "percent" to nullable("number", "Percent of the normal or ideal value, as printed"),
        ))
        fun num(desc: String) = nullable("number", desc)
        val scan = obj(linkedMapOf(
            "testDate" to nullable("string", "Date of this measurement as YYYY-MM-DD"),
            "weightKg" to num("Weight in kg (convert from lb if needed)"),
            "skeletalMuscleMassKg" to num("Skeletal Muscle Mass (SMM) in kg"),
            "bodyFatMassKg" to num("Body Fat Mass in kg"),
            "percentBodyFat" to num("Percent Body Fat (PBF) in %"),
            "bmi" to num("Body Mass Index in kg/m²"),
            "visceralFatLevel" to num("Visceral Fat Level (a 1–20 level, not an area)"),
            "bmrKcal" to num("Basal Metabolic Rate in kcal (convert from kJ by dividing by 4.184)"),
            "totalBodyWaterL" to num("Total Body Water in litres"),
            "ecwTbwRatio" to num("ECW/TBW ratio, e.g. 0.380"),
            "inBodyScore" to num("InBody Score out of 100"),
            "segmentalLean" to buildJsonObject { put("type", "array"); put("items", seg) },
            "segmentalFat" to buildJsonObject { put("type", "array"); put("items", seg) },
        ))
        obj(linkedMapOf(
            "isInBodySheet" to buildJsonObject { put("type", "boolean"); put("description", "True if this is body composition data (an InBody or similar BIA result sheet, history or export)") },
            "scans" to buildJsonObject {
                put("type", "array"); put("items", scan)
                put("description", "One entry per measurement date, oldest first. A single result sheet gives one entry, plus one per date in any history section.")
            },
        ))
    }

    const val LAB_PROMPT = "Extract every numeric test result from this lab report into the schema. Include each test once " +
        "(skip duplicates and calculated ratios like 'LDL/HDL ratio'). Copy values, units and reference ranges exactly as " +
        "printed; for ranges like '<200' set only refHigh, for '>40' set only refLow. Use canonicalName only when the test " +
        "clearly is that biomarker. Ignore everything about the patient (name, age, IDs, doctor, address): don't return it. " +
        "If the document is not a lab report, set isLabReport to false and return an empty list."

    const val INBODY_PROMPT = "Extract the results from this body composition result sheet (InBody or similar) into the " +
        "schema. Use the measured value, not the normal range. Leave fields null if they aren't printed. If the document " +
        "holds more than one measurement (a 'Body Composition History' section, a table of dated rows, or several sheets), " +
        "return one entry in scans per date, each with its own values; history rows often have only weight, skeletal muscle " +
        "mass and percent body fat, so leave the rest null for those. Ignore everything " +
        "about the person (name, ID, height, age, gender) and don't return it. If it is not a body composition sheet, set " +
        "isInBodySheet to false."

    // --- parsing (pure)

    private fun JsonObject.d(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.doubleOrNull ?: it.contentOrNull?.replace(",", "")?.trim()?.toDoubleOrNull() }
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    fun parseLab(text: String): ExtractedLab {
        val root = json.parseToJsonElement(Gemini.stripFence(text)).jsonObject
        val raws = (root["biomarkers"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            RawBiomarker(
                name = o.s("name") ?: return@mapNotNull null,
                canonicalName = o.s("canonicalName"), value = o.d("value"), unit = o.s("unit"),
                refLow = o.d("refLow"), refHigh = o.d("refHigh"), flag = o.s("flag"), category = o.s("category"),
                collectedDate = o.s("collectedDate"),
            )
        }
        val markers = raws.mapNotNull(Normalizer::normalize).distinctBy { it.key }
        val date = LabDates.parse(root.s("reportDate")) ?: markers.firstNotNullOfOrNull { LabDates.parse(it.collectedDate) }
        return ExtractedLab(
            isLabReport = (root["isLabReport"] as? JsonPrimitive)?.booleanOrNull ?: markers.isNotEmpty(),
            date = date, lab = root.s("labName")?.take(60), markers = markers,
        )
    }

    fun parseInBody(text: String, id: String = UUID.randomUUID().toString(), today: LocalDate = LocalDate.now(), now: Long = System.currentTimeMillis()): ExtractedInBody {
        val root = json.parseToJsonElement(Gemini.stripFence(text)).jsonObject
        // New shape: {"scans": [...]}. Older shape: the scan's fields at the top level.
        val items = (root["scans"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOf(root)
        val single = items.size == 1
        val parsed = items.mapIndexedNotNull { i, o ->
            val date = LabDates.parse(o.s("testDate")) ?: if (single) today else return@mapIndexedNotNull null
            scanOf(o, if (single) id else "$id-$i", date, now)
        }.filter { it.weightKg != null || it.percentBodyFat != null || it.skeletalMuscleKg != null }
        // One scan per date: keep the entry with the most values.
        val scans = parsed.groupBy { it.date }.map { (_, same) -> same.maxBy { filled(it) } }.sortedBy { it.localDate }
        val flagged = (root["isInBodySheet"] as? JsonPrimitive)?.booleanOrNull
        return ExtractedInBody(
            isInBody = (flagged ?: scans.isNotEmpty()) && scans.isNotEmpty(),
            scans = scans.ifEmpty { listOf(InBodyScan(id = id, date = today.toString(), addedAt = now)) },
        )
    }

    private fun filled(s: InBodyScan) = listOf(s.weightKg, s.skeletalMuscleKg, s.bodyFatKg, s.percentBodyFat, s.bmi, s.visceralFatLevel, s.bmrKcal, s.totalBodyWaterL, s.ecwTbw, s.inBodyScore)
        .count { it != null } + s.segmentalLean.size + s.segmentalFat.size

    private fun scanOf(o: JsonObject, id: String, date: LocalDate, now: Long): InBodyScan {
        fun segs(k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { e ->
            val s = e as? JsonObject ?: return@mapNotNull null
            val part = s.s("part")?.takeIf { it in segments } ?: return@mapNotNull null
            Segment(part, s.d("kg"), s.d("percent")).takeIf { it.kg != null || it.percent != null }
        }.distinctBy { it.part }
        // Guard against kJ slipping through: no adult BMR is above ~4000 kcal.
        val bmr = o.d("bmrKcal")?.let { if (it > 4000) it / 4.184 else it }
        return InBodyScan(
            id = id,
            date = date.toString(),
            addedAt = now,
            weightKg = o.d("weightKg"), skeletalMuscleKg = o.d("skeletalMuscleMassKg"), bodyFatKg = o.d("bodyFatMassKg"),
            percentBodyFat = o.d("percentBodyFat"), bmi = o.d("bmi"), visceralFatLevel = o.d("visceralFatLevel"),
            bmrKcal = bmr?.let { kotlin.math.round(it) }, totalBodyWaterL = o.d("totalBodyWaterL"), ecwTbw = o.d("ecwTbwRatio"),
            inBodyScore = o.d("inBodyScore"), segmentalLean = segs("segmentalLean"), segmentalFat = segs("segmentalFat"),
        )
    }

    // --- files

    class Picked(val bytes: ByteArray, val mimeType: String)

    /** Read a SAF document into memory, shrinking big photos. Never written anywhere. */
    suspend fun read(ctx: Context, uri: Uri): Picked = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val mime = cr.getType(uri)?.lowercase() ?: guessMime(uri, ctx)
        val supported = mime == "application/pdf" || mime in setOf("image/jpeg", "image/jpg", "image/png", "image/webp", "image/heic", "image/heif")
        if (!supported) throw IllegalArgumentException("Pick a PDF, a photo (JPEG, PNG, WebP or HEIC), or for InBody the CSV export")
        val size = cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        if (mime == "application/pdf" && size != null && size > Gemini.MAX_FILE_BYTES) throw IllegalArgumentException("That PDF is over 20 MB — try a smaller export")
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalArgumentException("Couldn't open that file")
        if (mime.startsWith("image/") && bytes.size > 4 * 1024 * 1024) {
            shrink(bytes)?.let { return@withContext Picked(it, "image/jpeg") }
        }
        if (bytes.size > Gemini.MAX_FILE_BYTES) throw IllegalArgumentException("That file is over 20 MB")
        Picked(bytes, if (mime == "image/jpg") "image/jpeg" else mime)
    }

    private val CSV_MIMES = setOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain", "application/vnd.ms-excel", "text/tab-separated-values")

    /** True for a CSV/text export (InBody's app exports CSV), decided by type or file name. */
    suspend fun isCsv(ctx: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val mime = ctx.contentResolver.getType(uri)?.lowercase().orEmpty()
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }.orEmpty().lowercase()
        if (mime == "application/pdf" || mime.startsWith("image/") || name.endsWith(".pdf")) return@withContext false
        if (mime in CSV_MIMES || name.endsWith(".csv") || name.endsWith(".tsv") || (mime.startsWith("text/") && !mime.contains("html"))) return@withContext true
        // Shared or downloaded files often arrive as application/octet-stream with no extension: look inside.
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(4096).let { buf -> val n = input.read(buf); if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8) }
                head.none { it == '\u0000' } && InBodyCsv.looksLikeInBody(head)
            } ?: false
        }.getOrDefault(false)
    }

    /** Read a small text file (≤ 5 MB) as a string, honouring a UTF-16 byte-order mark. */
    suspend fun readText(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val size = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        if (size != null && size > 5L * 1024 * 1024) throw IllegalArgumentException("That file is over 5 MB — is it the right export?")
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalArgumentException("Couldn't open that file")
        when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> bytes.decodeToString()
        }
    }

    private fun guessMime(uri: Uri, ctx: Context): String {
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment.orEmpty()
        return when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "heic" -> "image/heic"; "heif" -> "image/heif"
            else -> "application/octet-stream"
        }
    }

    /** Downscale a large photo to ~2400 px on the long side; plenty for reading printed text. */
    private fun shrink(bytes: ByteArray): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val long = maxOf(bounds.outWidth, bounds.outHeight).takeIf { it > 0 } ?: return null
        var sample = 1
        while (long / (sample * 2) >= 2400) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, out); bmp.recycle(); out.toByteArray() }
    }.getOrNull()

    // --- calls

    suspend fun extractLab(picked: Picked, key: String, model: String): ExtractedLab {
        val text = Gemini.generate(
            key = key, model = model, system = null,
            parts = listOf(Gemini.Part.File(picked.mimeType, picked.bytes), Gemini.Part.Text(LAB_PROMPT)),
            schema = labSchema, temperature = 0.0, maxOutputTokens = 16384, readTimeoutMs = 180_000,
        )
        return withContext(Dispatchers.Default) { parseLab(text) }
    }

    suspend fun extractInBody(picked: Picked, key: String, model: String): ExtractedInBody {
        val text = Gemini.generate(
            key = key, model = model, system = null,
            parts = listOf(Gemini.Part.File(picked.mimeType, picked.bytes), Gemini.Part.Text(INBODY_PROMPT)),
            schema = inBodySchema, temperature = 0.0, maxOutputTokens = 8192, readTimeoutMs = 120_000,
        )
        return withContext(Dispatchers.Default) { parseInBody(text) }
    }
}
