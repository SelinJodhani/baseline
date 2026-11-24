package app.baseline.health

import app.baseline.data.Summary
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Decides what goes into Health Connect, from Zepp's data alone. Plain values only (no Health Connect types), so
 * the rules are unit-testable. All times are epoch seconds.
 *
 * The rules, and why:
 *  - Readings (heart rate, HRV, SpO2) are copied as recorded, one record per reading (heart rate: one record per
 *    hour), each with a fixed client id, so re-sending one updates it instead of duplicating it.
 *  - A night is written only once it is settled: Zepp has readings from at least [SLEEP_SETTLE_SEC] after it
 *    ended. The Zepp app writes a half-finished night around 5 am and extends it later; Google Health keeps the
 *    first copy it sees, so writing early would lock in a 3-hour "nap".
 *  - Daily values (steps, distance, active calories) are one record per local day, rewritten as the day grows.
 *  - Resting heart rate and breathing rate are night values in Zepp, so they wait for that night to settle.
 */
object HcPlanner {
    const val SLEEP_SETTLE_SEC = 90 * 60L

    data class Point(val t: Long, val v: Double)
    data class HrHour(val start: Long, val end: Long, val samples: List<Point>)
    data class Stage(val start: Long, val end: Long, val kind: String)
    data class Night(val start: Long, val end: Long, val stages: List<Stage>, val score: Int?)
    data class DayTotals(val date: LocalDate, val start: Long, val end: Long, val steps: Long?, val distanceM: Double?, val activeKcal: Double?)
    data class Vital(val date: LocalDate, val at: Long, val value: Double)
    data class Session(val id: String, val type: String, val start: Long, val end: Long)

    /** `[epoch, value]` rows as points, keeping only values in [lo, hi] (Health Connect rejects the rest). */
    fun points(rows: List<List<Double>>, lo: Double, hi: Double): List<Point> = rows.mapNotNull { r ->
        val t = r.getOrNull(0)?.toLong() ?: return@mapNotNull null
        val v = r.getOrNull(1) ?: return@mapNotNull null
        if (v in lo..hi) Point(t, v) else null
    }

    /** Heart-rate readings grouped into clock hours. An hour still in progress ends just after its last reading. */
    fun hrHours(rows: List<List<Double>>, now: Long): List<HrHour> =
        points(rows, 1.0, 300.0).filter { it.t <= now }.groupBy { it.t - Math.floorMod(it.t, 3600L) }.toSortedMap().map { (start, ps) ->
            val full = start + 3600
            HrHour(start, if (full <= now) full else ps.maxOf { it.t } + 1, ps.sortedBy { it.t })
        }

    /**
     * Nights that ended at or after [since] and have settled, with clean stages (clipped, sorted, no overlaps).
     * Where Zepp has two sessions that overlap (it happens when two accounts' libraries are merged), only the
     * longer one is kept: Health Connect would otherwise count that time twice.
     */
    fun settledNights(summary: Summary, since: Long, lastReadingAt: Long?, now: Long): List<Night> {
        val settledBy = lastReadingAt ?: return emptyList()
        val kept = mutableListOf<app.baseline.data.Sleep>()
        summary.sleeps.filter { it.end > it.start && it.end >= since && it.end <= now && settledBy >= it.end + SLEEP_SETTLE_SEC }
            .sortedWith(compareByDescending<app.baseline.data.Sleep> { it.end - it.start }.thenBy { it.start })
            .forEach { s -> if (kept.none { it.start < s.end && s.start < it.end }) kept += s }
        return kept.sortedBy { it.start }
            .map { s ->
                val stages = mutableListOf<Stage>()
                for ((a, b, kind) in s.stageSpans.sortedBy { it.first }) {
                    val start = maxOf(a, s.start, stages.lastOrNull()?.end ?: Long.MIN_VALUE)
                    val end = minOf(b, s.end)
                    if (end > start) stages += Stage(start, end, kind)
                }
                Night(s.start, s.end, stages, s.score)
            }
    }

    /** One totals record per local day from [sinceDate]; today's ends now (Health Connect takes no future times). */
    fun dayTotals(summary: Summary, sinceDate: LocalDate, now: Long, zone: ZoneId): List<DayTotals> = summary.days.mapNotNull { d ->
        val date = runCatching { d.localDate }.getOrNull() ?: return@mapNotNull null
        if (date < sinceDate) return@mapNotNull null
        val start = date.atStartOfDay(zone).toEpochSecond()
        val end = minOf(date.plusDays(1).atStartOfDay(zone).toEpochSecond(), now)
        val steps = d.steps?.toLong()?.takeIf { it > 0 }
        val dist = d.distanceM?.takeIf { it > 0 }
        val kcal = d.activeCalories?.takeIf { it > 0 }
        if (end - start < 60 || (steps == null && dist == null && kcal == null)) null else DayTotals(date, start, end, steps, dist, kcal)
    }

    /** The longest settled night ending on each local date. */
    fun mainNights(nights: List<Night>, zone: ZoneId): Map<LocalDate, Night> =
        nights.groupBy { Instant.ofEpochSecond(it.end).atZone(zone).toLocalDate() }.mapValues { (_, ns) -> ns.maxBy { it.end - it.start } }

    /**
     * Zepp's resting heart rate per day, stamped at wake-up of that day's night. A day without a night gets noon,
     * but only once the day is over; today waits for its night to settle.
     */
    fun restingHr(summary: Summary, sinceDate: LocalDate, main: Map<LocalDate, Night>, today: LocalDate, zone: ZoneId): List<Vital> =
        summary.days.mapNotNull { d ->
            val date = runCatching { d.localDate }.getOrNull() ?: return@mapNotNull null
            val bpm = d.restingHr?.takeIf { it in 20.0..250.0 } ?: return@mapNotNull null
            if (date < sinceDate) return@mapNotNull null
            val at = main[date]?.end ?: if (date < today) date.atTime(LocalTime.NOON).atZone(zone).toEpochSecond() else return@mapNotNull null
            Vital(date, at, bpm)
        }

    /** Zepp's breathing rate is a sleep average, so it is stamped mid-night and only for settled nights. */
    fun breathing(summary: Summary, sinceDate: LocalDate, main: Map<LocalDate, Night>): List<Vital> =
        summary.days.mapNotNull { d ->
            val date = runCatching { d.localDate }.getOrNull() ?: return@mapNotNull null
            val rate = d.respRate?.takeIf { it in 4.0..60.0 } ?: return@mapNotNull null
            val night = main[date] ?: return@mapNotNull null
            if (date < sinceDate) null else Vital(date, (night.start + night.end) / 2, rate)
        }

    fun workouts(summary: Summary, since: Long, now: Long): List<Session> = summary.workouts.mapNotNull { w ->
        val end = w.end ?: return@mapNotNull null
        if (end <= w.start || end < since || end > now) null else Session(w.id, w.type, w.start, end)
    }

    fun weights(summary: Summary, since: Long, now: Long): List<Point> =
        summary.weights.filter { it.t in since..now && it.kg in 1.0..999.0 }.map { Point(it.t, it.kg) }

    /** Round an epoch down to its clock hour. */
    fun floorHour(t: Long): Long = t - Math.floorMod(t, 3600L)

    // ------------------------------------------------------------------ Google Health HRV dates (optional)
    //
    // Google Health files a night's HRV under the UTC calendar date of that night's first reading, and averages the
    // readings that follow it, also ones after wake-up (measured 24 Nov 2026 with synthetic nights: a 02:00–09:30 IST
    // night landed on the day before; the same night with its readings moved 5 h 30 m later landed on the wake-up
    // day; readings up to 5½ h after wake-up still counted; readings hours before bedtime did not). For anyone
    // east of UTC who falls asleep after local midnight, every night therefore shows up a day early.
    //
    // With the option on, each settled night's readings (only those inside the night, so the average stays Zepp's
    // sleep HRV) are moved later by one fixed amount, so the first lands just after 00:00 UTC on the wake-up date.
    // Values, order and spacing are unchanged; only the clock times move.

    /** Just past the UTC day change, so the first reading is safely on the wake-up date. */
    const val GOOGLE_DAY_MARGIN_SEC = 15 * 60L

    /** Never move readings further than this (beyond it Google may stop counting them); such nights stay as they are. */
    const val GOOGLE_MAX_SHIFT_SEC = 8 * 3600L

    /** How far a night's HRV readings move for Google Health: 0 when the night already starts on its wake-up date in UTC. */
    fun googleHrvShift(night: Night, zone: ZoneId): Long {
        val wake = Instant.ofEpochSecond(night.end).atZone(zone).toLocalDate()
        val utcDay = wake.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()
        val shift = utcDay + GOOGLE_DAY_MARGIN_SEC - night.start
        return if (shift <= 0 || shift > GOOGLE_MAX_SHIFT_SEC) 0 else shift
    }

    /** The night's readings (inside it only) as (original time, moved point). */
    fun googleHrv(night: Night, hrv: List<Point>, zone: ZoneId): List<Pair<Long, Point>> {
        val shift = googleHrvShift(night, zone)
        return hrv.filter { it.t >= night.start && it.t < night.end }.sortedBy { it.t }.map { it.t to Point(it.t + shift, it.v) }
    }
}
