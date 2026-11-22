package app.baseline.labs

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reads the CSV that InBody's app and LookinBody export ("Date, Measurement device., Weight(kg), …"), on the
 * phone, with no AI. Columns are matched by name, so the order and the set of columns can vary; "-" means not
 * measured. Units in the header are respected (lb → kg, kJ → kcal).
 *
 * Repeat measurements are collapsed: exact duplicate rows are dropped, and several scans on the same day keep
 * only the last one (people often step on twice in a row), so each day is one point on the timeline.
 */
object InBodyCsv {

    data class Result(
        val scans: List<InBodyScan>,
        /** Exact duplicate rows dropped. */
        val duplicates: Int,
        /** Earlier scans on a day that has a later one, dropped. */
        val sameDay: Int,
        /** Rows that had no usable date or no body numbers. */
        val unreadable: Int,
    )

    class NotInBodyCsv(message: String) : IllegalArgumentException(message)

    /** Quick sniff: a header line with a date column and a weight or body-fat column. */
    fun looksLikeInBody(text: String): Boolean {
        val header = text.removePrefix("﻿").lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        val cols = split(header, delimiterOf(header)).map { norm(it).first }
        return cols.any { it in DATE_KEYS } && cols.any { it == "weight" || it == "percentbodyfat" || it == "skeletalmusclemass" }
    }

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): Result {
        val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }
        if (lines.size < 2) throw NotInBodyCsv("That file has no rows to import.")
        val delim = delimiterOf(lines[0])
        val header = split(lines[0], delim).map(::norm)
        val dateCol = header.indexOfFirst { it.first in DATE_KEYS }
        if (dateCol < 0 || !looksLikeInBody(lines[0])) throw NotInBodyCsv("This doesn't look like an InBody export. It needs Date and Weight columns.")

        data class Row(val at: LocalDateTime, val raw: String, val scan: InBodyScan)
        var unreadable = 0
        val rows = lines.drop(1).mapNotNull { line ->
            val cells = split(line, delim)
            val at = parseDate(cells.getOrNull(dateCol)) ?: run { unreadable++; return@mapNotNull null }
            val v = HashMap<String, Double>()
            header.forEachIndexed { i, (key, unit) ->
                if (i == dateCol) return@forEachIndexed
                val x = number(cells.getOrNull(i), delim) ?: return@forEachIndexed
                v[key] = convert(key, unit, x) ?: return@forEachIndexed
            }
            val scan = toScan(v, at, zone, now)
            if (scan == null) { unreadable++; return@mapNotNull null }
            Row(at, cells.filterIndexed { i, _ -> i != dateCol }.joinToString(",") + "|" + at, scan)
        }
        val unique = rows.distinctBy { it.raw }
        val duplicates = rows.size - unique.size
        val perDay = unique.groupBy { it.at.toLocalDate() }.map { (_, sameDay) -> sameDay.maxBy { it.at } }
        val sameDay = unique.size - perDay.size
        return Result(perDay.sortedBy { it.at }.map { it.scan }, duplicates, sameDay, unreadable)
    }

    // ------------------------------------------------------------------ columns

    private val DATE_KEYS = setOf("date", "testdate", "datetime", "measurementdate", "testdatetime", "measuredate")

    /** Normalised column name (letters and digits only, no unit) and its unit (lowercase, from the brackets). */
    private fun norm(h: String): Pair<String, String> {
        val t = h.trim().trim('"').lowercase(Locale.ROOT)
        val unit = Regex("[(\\[]([^)\\]]*)[)\\]]").find(t)?.groupValues?.get(1)?.trim().orEmpty()
        val name = t.replace(Regex("[(\\[][^)\\]]*[)\\]]"), "").replace(Regex("[^a-z0-9%]"), "")
        val canonical = when (name) {
            "pbf", "bodyfat%", "bodyfatpercent", "percentbodyfat%" -> "percentbodyfat"
            "smm" -> "skeletalmusclemass"
            "bfm" -> "bodyfatmass"
            "bmr" -> "basalmetabolicrate"
            "tbw" -> "totalbodywater"
            "ecwtbw", "ecw/tbw" -> "ecwratio"
            "vfl" -> "visceralfatlevel"
            "vfa" -> "visceralfatarea"
            "whr" -> "waisthipratio"
            "phaseangle" -> "wholebodyphaseangle"
            else -> name
        }
        return canonical to unit
    }

    /** Unit-aware value in the scan's units; null drops an impossible value. */
    private fun convert(key: String, unit: String, x: Double): Double? {
        val massKeys = key.endsWith("mass") || key in setOf("weight", "protein", "mineral", "bonemineralcontent", "bodycellmass")
        if (massKeys) {
            if (x <= 0) return null
            return if (unit == "lb" || unit == "lbs") x * 0.45359237 else x
        }
        return when (key) {
            "basalmetabolicrate" -> when { x <= 0 -> null; unit == "kj" || x > 4000 -> x / 4.184; else -> x }
            "totalbodywater", "intracellularwater", "extracellularwater" -> when { x <= 0 -> null; unit == "lb" || unit == "lbs" -> x * 0.45359237; else -> x }
            "waistcircumference" -> when { x <= 0 -> null; unit == "in" || unit == "inch" -> x * 2.54; else -> x }
            "percentbodyfat", "bmi", "inbodyscore", "smi", "waisthipratio", "visceralfatarea", "ecwratio", "wholebodyphaseangle" -> x.takeIf { it > 0 }
            "visceralfatlevel" -> x.takeIf { it > 0 }
            else -> x
        }
    }

    private fun toScan(v: Map<String, Double>, at: LocalDateTime, zone: ZoneId, now: Long): InBodyScan? {
        fun seg(prefix: String, kind: String) = listOf("rightarm", "leftarm", "trunk", "rightleg", "leftleg").mapNotNull { part ->
            v["$part$kind"]?.let { Segment(PARTS.getValue(part), kg = it) }
        }
        val weight = v["weight"]
        val pbf = v["percentbodyfat"]
        val smm = v["skeletalmusclemass"]
        if (weight == null && pbf == null && smm == null) return null
        return InBodyScan(
            id = "inbody-csv-" + at.format(ID_FORMAT),
            date = at.toLocalDate().toString(),
            addedAt = now,
            weightKg = weight?.round(1),
            skeletalMuscleKg = smm,
            bodyFatKg = v["bodyfatmass"],
            percentBodyFat = pbf,
            bmi = v["bmi"],
            visceralFatLevel = v["visceralfatlevel"],
            bmrKcal = v["basalmetabolicrate"]?.let { kotlin.math.round(it) },
            totalBodyWaterL = v["totalbodywater"],
            ecwTbw = v["ecwratio"],
            inBodyScore = v["inbodyscore"],
            segmentalLean = seg("", "leanmass"),
            segmentalFat = seg("", "fatmass"),
            softLeanKg = v["softleanmass"],
            proteinKg = v["protein"],
            mineralKg = v["mineral"],
            bodyCellMassKg = v["bodycellmass"],
            smi = v["smi"],
            waistHipRatio = v["waisthipratio"],
            waistCm = v["waistcircumference"],
            visceralFatAreaCm2 = v["visceralfatarea"],
            phaseAngle = v["wholebodyphaseangle"],
            measuredAt = at.atZone(zone).toEpochSecond(),
            source = "csv",
        )
    }

    private val PARTS = mapOf("rightarm" to "rightArm", "leftarm" to "leftArm", "trunk" to "trunk", "rightleg" to "rightLeg", "leftleg" to "leftLeg")
    private val ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    private fun Double.round(places: Int): Double {
        val f = Math.pow(10.0, places.toDouble())
        return kotlin.math.round(this * f) / f
    }

    // ------------------------------------------------------------------ cells

    private fun delimiterOf(header: String): Char =
        listOf(',', ';', '\t').maxBy { d -> header.count { it == d } }

    /** RFC-4180-ish split: quotes may wrap a cell and "" is a literal quote. */
    internal fun split(line: String, delim: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == delim && !quoted -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString().trim()
        return out
    }

    private fun number(cell: String?, delim: Char): Double? {
        val t = cell?.trim()?.trim('"')?.takeIf { it.isNotEmpty() && it != "-" && it != "--" && !it.equals("n/a", true) } ?: return null
        val normal = if (delim == ';') t.replace(',', '.') else t
        return normal.toDoubleOrNull()
    }

    private val DATE_TIME_FORMATS = listOf(
        "yyyyMMddHHmmss", "yyyyMMddHHmm", "yyyy.MM.dd HH:mm:ss", "yyyy.MM.dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm", "dd.MM.yyyy HH:mm", "dd/MM/yyyy HH:mm", "yyyy-MM-dd'T'HH:mm:ss",
    ).map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }
    private val DATE_FORMATS = listOf("yyyyMMdd", "yyyy.MM.dd", "yyyy-MM-dd", "yyyy/MM/dd", "dd.MM.yyyy", "dd/MM/yyyy")
        .map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

    internal fun parseDate(cell: String?): LocalDateTime? {
        val t = cell?.trim()?.trim('"')?.takeIf { it.isNotEmpty() } ?: return null
        DATE_TIME_FORMATS.forEach { f -> runCatching { return LocalDateTime.parse(t, f) } }
        DATE_FORMATS.forEach { f -> runCatching { return LocalDate.parse(t, f).atStartOfDay() } }
        return LabDates.parse(t)?.atStartOfDay()
    }
}
