package app.baseline.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.inputstream.ZipInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * History imported from a Zepp "Export data" zip (for example an older account that ZeppBridge can't sign
 * in to). Lives only on this phone and is merged under whatever the bridge sends: the bridge always wins.
 */
@Serializable
data class Archive(
    val importedAt: Long,
    val days: List<Day> = emptyList(),
    val sleeps: List<Sleep> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val weights: List<Weight> = emptyList(),
) {
    val from: String? get() = days.firstOrNull()?.date
    val to: String? get() = days.lastOrNull()?.date
}

class ExportException(message: String) : Exception(message)

/**
 * Reads Zepp's data export: a (usually AES-encrypted) zip of CSV folders — ACTIVITY, SLEEP, SLEEP_MINUTE,
 * SPORT, BODY, … Dates in ACTIVITY and the *_MINUTE files are local; SLEEP/SPORT/BODY times carry +0000.
 * There is no HRV or breathing rate in the export; resting HR is derived from the overnight minute data.
 */
object ZeppExport {
    private val offsetTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ")
    private val localMinute = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** Zepp workout type ids seen in exports, checked against pace/distance; unknown ids become "workout". */
    private val SPORT_TYPES = mapOf(1 to "running", 6 to "walking", 8 to "treadmill", 52 to "strength_training")

    fun read(zip: InputStream, password: String?, zone: ZoneId, now: Long = System.currentTimeMillis() / 1000): Archive {
        val files = HashMap<String, List<Map<String, String>>>()
        try {
            ZipInputStream(zip, password?.takeIf { it.isNotEmpty() }?.toCharArray()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (e.isDirectory) continue
                    val kind = e.fileName.substringAfterLast('/').substringBeforeLast('_')
                    if (kind in WANTED) files[kind] = readCsv(BufferedReader(InputStreamReader(zis, Charsets.UTF_8)))
                }
            }
        } catch (e: ZipException) {
            throw when (e.type) {
                ZipException.Type.WRONG_PASSWORD -> ExportException("Wrong password — use the one Zepp showed or emailed with the export")
                else -> if (e.message?.contains("password", ignoreCase = true) == true) ExportException("This zip needs its password")
                else ExportException("Couldn't open the zip: ${e.message}")
            }
        }
        return parse(files, zone, now)
    }

    private val WANTED = setOf("ACTIVITY", "SLEEP", "SLEEP_MINUTE", "SPORT", "BODY")

    /** [files]: folder kind (ACTIVITY, SLEEP, …) → rows keyed by header. */
    fun parse(files: Map<String, List<Map<String, String>>>, zone: ZoneId, now: Long): Archive {
        if ("ACTIVITY" !in files && "SLEEP" !in files) throw ExportException("This doesn't look like a Zepp data export")

        val days = sortedMapOf<String, Day>()
        files["ACTIVITY"].orEmpty().forEach { r ->
            val date = r["date"]?.takeIf { isDate(it) } ?: return@forEach
            val steps = r["steps"].num(); val dist = r["distance"].num(); val kcal = r["calories"].num()
            if ((steps ?: 0.0) <= 0 && (dist ?: 0.0) <= 0) return@forEach // watch not worn
            days[date] = Day(date = date, steps = steps, distanceM = dist, activeCalories = kcal)
        }

        // Minute-level sleep: local time, stage + heart rate.
        val minutes = files["SLEEP_MINUTE"].orEmpty().mapNotNull { r ->
            val t = runCatching { LocalDateTime.parse("${r["date"]} ${r["time"]}", localMinute).atZone(zone).toEpochSecond() }.getOrNull()
                ?: return@mapNotNull null
            Minute(t, stageName(r["stage"]), r["hr"].num()?.takeIf { it in 25.0..220.0 })
        }.sortedBy { it.t }
        val times = LongArray(minutes.size) { minutes[it].t }

        val sleeps = mutableListOf<Sleep>()
        files["SLEEP"].orEmpty().forEach { r ->
            val start = epoch(r["start"]) ?: return@forEach
            val end = epoch(r["stop"]) ?: return@forEach
            val deep = r["deepSleepTime"].num()?.toInt() ?: 0
            val light = r["shallowSleepTime"].num()?.toInt() ?: 0
            val rem = r["REMTime"].num()?.toInt() ?: 0
            if (end <= start || deep + light + rem < 60) return@forEach
            val inNight = slice(minutes, times, start, end)
            sleeps += Sleep(
                start = start, end = end, durationMin = deep + light + rem,
                deepMin = deep, lightMin = light, remMin = rem, awakeMin = r["wakeTime"].num()?.toInt(),
                stages = stages(inNight, end).ifEmpty { null },
            )
            val wake = r["date"]?.takeIf { isDate(it) } ?: return@forEach
            lowestFiveMinuteHr(inNight)?.let { rhr ->
                days[wake] = (days[wake] ?: Day(date = wake)).copy(sleepRhr = rhr)
            }
        }

        val workouts = files["SPORT"].orEmpty().mapNotNull { r ->
            val start = epoch(r["startTime"]) ?: return@mapNotNull null
            val secs = r["sportTime(s)"].num()?.toLong()?.takeIf { it > 0 } ?: return@mapNotNull null
            val type = SPORT_TYPES[r["type"].num()?.toInt()] ?: "workout"
            Workout(
                id = "zepp-export-$start", type = type, start = start, end = start + secs,
                distanceM = r["distance(m)"].num()?.takeIf { it > 0 }, calories = r["calories(kcal)"].num()?.takeIf { it > 0 }?.let { it.roundToInt().toDouble() },
            )
        }.sortedBy { it.start }

        val weights = files["BODY"].orEmpty().mapNotNull { r ->
            val t = epoch(r["time"]) ?: return@mapNotNull null
            val kg = r["weight"].num()?.takeIf { it in 20.0..400.0 } ?: return@mapNotNull null
            Weight(t, kg)
        }.sortedBy { it.t }

        return Archive(importedAt = now, days = days.values.toList(), sleeps = sleeps.sortedBy { it.start }, workouts = workouts, weights = weights)
    }

    private class Minute(val t: Long, val stage: String?, val hr: Double?)

    private fun slice(m: List<Minute>, times: LongArray, from: Long, to: Long): List<Minute> {
        var i = java.util.Arrays.binarySearch(times, from).let { if (it < 0) -it - 1 else it }
        val out = mutableListOf<Minute>()
        while (i < m.size && m[i].t < to) out += m[i++]
        return out
    }

    private fun stageName(s: String?) = when (s?.uppercase()) {
        "DEEP" -> "deep"; "LIGHT" -> "light"; "REM" -> "rem"; "WAKE", "AWAKE" -> "awake"; else -> null
    }

    /** Consecutive minutes of the same stage → [start, end, stage] spans. */
    private fun stages(night: List<Minute>, end: Long): List<List<JsonPrimitive>> {
        val out = mutableListOf<List<JsonPrimitive>>()
        var curStage: String? = null; var curStart = 0L; var last = 0L
        fun flush() { if (curStage != null && last > curStart) out += listOf(JsonPrimitive(curStart), JsonPrimitive(last), JsonPrimitive(curStage)) }
        night.forEach { m ->
            val st = m.stage ?: return@forEach
            if (st != curStage || m.t - last > 180) { flush(); curStage = st; curStart = m.t }
            last = minOf(m.t + 60, end)
        }
        flush()
        return out
    }

    /** Lowest 5-minute average heart rate during the night (needs an hour of readings). */
    private fun lowestFiveMinuteHr(night: List<Minute>): Double? {
        val pts = night.filter { it.hr != null }
        if (pts.size < 60) return null
        var best = Double.MAX_VALUE
        for (i in 0..pts.size - 5) {
            if (pts[i + 4].t - pts[i].t > 6 * 60) continue // a gap: not five real consecutive minutes
            val avg = (i until i + 5).sumOf { pts[it].hr!! } / 5
            if (avg < best) best = avg
        }
        return if (best == Double.MAX_VALUE) null else (best * 10).roundToInt() / 10.0
    }

    private fun isDate(s: String) = runCatching { LocalDate.parse(s) }.isSuccess

    private fun epoch(s: String?): Long? = s?.takeIf { it.isNotBlank() && it != "null" }?.let {
        runCatching { OffsetDateTime.parse(it, offsetTime).toEpochSecond() }.getOrNull()
    }

    private fun String?.num(): Double? = this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.toDoubleOrNull()

    /** Minimal CSV reader: quoted fields, "" and \" escapes, BOM-tolerant header. */
    fun readCsv(reader: BufferedReader): List<Map<String, String>> {
        val header = reader.readLine()?.removePrefix("﻿")?.let(::splitCsv) ?: return emptyList()
        val rows = ArrayList<Map<String, String>>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val cells = splitCsv(line)
            if (cells.firstOrNull()?.removePrefix("﻿") == header.first()) continue // repeated header
            rows += HashMap<String, String>(header.size * 2).apply { header.forEachIndexed { i, h -> put(h, cells.getOrElse(i) { "" }) } }
        }
        return rows
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                q && c == '\\' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                c == ',' && !q -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}

/**
 * Lays imported history under the bridge's data. Per day the bridge wins field by field; archive sleeps are
 * added only where they don't overlap a bridge sleep; workouts and weigh-ins are de-duplicated by time.
 */
fun Summary.withArchive(a: Archive?): Summary {
    if (a == null) return this
    val json = Summary.json
    val mine = days.associateBy { it.date }
    val oldDates = a.days.mapTo(HashSet()) { it.date }
    val mergedDays = (a.days.map { old ->
        val cur = mine[old.date] ?: return@map old
        val o = json.encodeToJsonElement(Day.serializer(), old).jsonObject
        val c = json.encodeToJsonElement(Day.serializer(), cur).jsonObject
        json.decodeFromJsonElement(Day.serializer(), JsonObject(o + c))
    } + days.filter { it.date !in oldDates }).sortedBy { it.date }

    val extraSleeps = a.sleeps.filter { s -> sleeps.none { c -> s.start < c.end && c.start < s.end } }
    val extraWorkouts = a.workouts.filter { w -> workouts.none { abs(it.start - w.start) < 15 * 60 } }
    val extraWeights = a.weights.filter { w -> weights.none { abs(it.t - w.t) < 6 * 3600 } }
    return copy(
        days = mergedDays,
        sleeps = (sleeps + extraSleeps).sortedBy { it.start },
        workouts = (workouts + extraWorkouts).sortedBy { it.start },
        weights = (weights + extraWeights).sortedBy { it.t },
    )
}
