package app.baseline.labs

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One normalised result. Only extracted values are kept — never the original document. */
@Serializable
data class LabMarker(
    /** Canonical id from [Biomarkers] (e.g. "ldl"), or a stable slug for tests Baseline doesn't know. */
    val key: String,
    val name: String,
    val value: Double,
    val unit: String,
    val refLow: Double? = null,
    val refHigh: Double? = null,
    /** [LabFlag] name. */
    val flag: String = LabFlag.Unknown.name,
    /** [LabCategory] name. */
    val category: String = LabCategory.Other.name,
    val collectedDate: String? = null,
) {
    val labFlag: LabFlag get() = LabFlag.of(flag)

    /**
     * Where the value sits against its printed range. The numbers win over the extracted flag (AI can misread a
     * flag column); "Abnormal" from the report is kept for qualitative results.
     */
    val status: LabFlag get() {
        if (labFlag == LabFlag.Abnormal) return LabFlag.Abnormal
        refHigh?.let { if (value > it) return LabFlag.High }
        refLow?.let { if (value < it) return LabFlag.Low }
        if (refLow != null || refHigh != null) return LabFlag.Normal
        return labFlag
    }

    val outOfRange: Boolean get() = status == LabFlag.High || status == LabFlag.Low || status == LabFlag.Abnormal
    val labCategory: LabCategory get() = LabCategory.of(category)
}

@Serializable
data class LabReport(
    val id: String,
    /** ISO date (collection date if printed, else the report date). */
    val date: String,
    val lab: String? = null,
    val addedAt: Long = 0,
    val markers: List<LabMarker> = emptyList(),
) {
    val localDate: LocalDate get() = LabDates.parse(date) ?: LocalDate.MIN
}

@Serializable
data class Segment(val part: String, val kg: Double? = null, val percent: Double? = null)

/**
 * One InBody result sheet. Fields are null when the sheet doesn't print them.
 *
 * Future option: Health Connect has matching record types (WeightRecord, BodyFatRecord, LeanBodyMassRecord,
 * BasalMetabolicRateRecord, BodyWaterMassRecord, BoneMassRecord). InBody's own app is only documented to sync
 * body composition to Apple Health, but Samsung Health does write body composition into Health Connect, so
 * reading those records would cover InBody-via-Samsung and other smart scales without a photo.
 */
@Serializable
data class InBodyScan(
    val id: String,
    val date: String,
    val addedAt: Long = 0,
    val weightKg: Double? = null,
    val skeletalMuscleKg: Double? = null,
    val bodyFatKg: Double? = null,
    val percentBodyFat: Double? = null,
    val bmi: Double? = null,
    val visceralFatLevel: Double? = null,
    val bmrKcal: Double? = null,
    val totalBodyWaterL: Double? = null,
    val ecwTbw: Double? = null,
    val inBodyScore: Double? = null,
    val segmentalLean: List<Segment> = emptyList(),
    val segmentalFat: List<Segment> = emptyList(),
    // Extra fields InBody's CSV export carries (null when read from a photo or not measured).
    val softLeanKg: Double? = null,
    val proteinKg: Double? = null,
    val mineralKg: Double? = null,
    val bodyCellMassKg: Double? = null,
    /** Skeletal muscle index, kg/m². */
    val smi: Double? = null,
    val waistHipRatio: Double? = null,
    val waistCm: Double? = null,
    val visceralFatAreaCm2: Double? = null,
    val phaseAngle: Double? = null,
    /** Exact measurement time (epoch seconds) when known, for ordering scans on the same day. */
    val measuredAt: Long? = null,
    /** "csv" or "ai". */
    val source: String = "ai",
) {
    /** Lean (fat-free) mass: weight minus body fat mass. */
    val leanKg: Double? get() = if (weightKg != null && bodyFatKg != null) weightKg - bodyFatKg else null

    val localDate: LocalDate get() = LabDates.parse(date) ?: LocalDate.MIN
}

/** A cached AI answer. */
@Serializable
data class AiNote(val text: String, val model: String, val at: Long = 0, val sample: Boolean = false)

/** Everything in `datastore/labs.enc`. */
@Serializable
data class LabsData(
    val reports: List<LabReport> = emptyList(),
    val scans: List<InBodyScan> = emptyList(),
    /** The person agreed that documents are sent to Google Gemini with their key. */
    val consent: Boolean = false,
    /** AI answers keyed by what they describe ("report:<id>", "marker:<key>:<reportId>", "inbody:<id>"). */
    val notes: Map<String, AiNote> = emptyMap(),
) {
    val reportsByDate: List<LabReport> get() = reports.sortedBy { it.localDate }
    val scansByDate: List<InBodyScan> get() = scans.sortedBy { it.localDate }

    /** Latest value of every biomarker across reports, newest report wins. */
    fun latestMarkers(): List<Pair<LabReport, LabMarker>> {
        val seen = LinkedHashMap<String, Pair<LabReport, LabMarker>>()
        reportsByDate.asReversed().forEach { r -> r.markers.forEach { m -> seen.putIfAbsent(m.key, r to m) } }
        return seen.values.toList()
    }

    /** One biomarker's history, oldest first. */
    fun history(key: String): List<Pair<LabReport, LabMarker>> =
        reportsByDate.mapNotNull { r -> r.markers.firstOrNull { it.key == key }?.let { r to it } }
}

/** What [mergeScans] did. */
data class ScanMerge(val scans: List<InBodyScan>, val added: Int, val replaced: Int, val kept: Int)

/**
 * One scan per date. An incoming scan replaces a stored one with the same id or date, except that a scan from
 * InBody's CSV (exact numbers) is never replaced by one read by AI — that one is skipped ([ScanMerge.kept]).
 */
fun mergeScans(existing: List<InBodyScan>, incoming: List<InBodyScan>): ScanMerge {
    val out = existing.toMutableList()
    var added = 0; var replaced = 0; var kept = 0
    incoming.forEach { n ->
        val i = out.indexOfFirst { it.id == n.id || it.date == n.date }
        when {
            i < 0 -> { out += n; added++ }
            out[i].source == "csv" && n.source != "csv" -> kept++
            else -> { out[i] = n; replaced++ }
        }
    }
    return ScanMerge(out, added, replaced, kept)
}

object LabDates {
    private val patterns = listOf("yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "dd-MMM-yyyy", "dd MMM yyyy", "d MMM yyyy", "MMM d, yyyy", "MMMM d, yyyy", "yyyy/MM/dd")
        .map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

    fun parse(s: String?): LocalDate? {
        val t = s?.trim()?.take(32)?.takeIf { it.isNotEmpty() } ?: return null
        val head = t.substringBefore('T').substringBefore(' ').takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) }
        head?.let { runCatching { return LocalDate.parse(it) } }
        patterns.forEach { f -> runCatching { return LocalDate.parse(t, f) } }
        return null
    }

    private val show = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    private val month = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
    private val short = DateTimeFormatter.ofPattern("MMM yy", Locale.getDefault())
    fun pretty(d: LocalDate): String = d.format(show)
    fun pretty(s: String): String = parse(s)?.let(::pretty) ?: s
    /** Month and year only — what goes into AI prompts. */
    fun monthYear(s: String): String = parse(s)?.format(month) ?: "unknown date"
    fun axis(s: String): String = parse(s)?.format(short) ?: ""
}

/** Number formatting that drops trailing zeros: 5.40 → 5.4, 120.0 → 120. */
fun fmtNum(v: Double): String {
    val a = kotlin.math.abs(v)
    val s = when {
        a >= 100 -> String.format(Locale.ROOT, "%.0f", v)
        a >= 10 -> String.format(Locale.ROOT, "%.1f", v)
        a >= 1 -> String.format(Locale.ROOT, "%.2f", v)
        else -> String.format(Locale.ROOT, "%.3f", v)
    }
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}
