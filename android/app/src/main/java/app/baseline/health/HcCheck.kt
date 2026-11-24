package app.baseline.health

import app.baseline.data.Summary
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * The rules behind Settings → Health Connect → "What's in Health Connect": who wrote what, and whether what other
 * apps will read matches Zepp. Plain values only, so it's unit-testable; [HcInspector] fills them in.
 */
object HcCheck {
    enum class Kind(val title: String, val unit: String) {
        HEART_RATE("Heart rate", "readings"), HRV("HRV", "readings"), SPO2("SpO₂", "readings"),
        SLEEP("Sleep", "nights"), RESTING_HR("Resting heart rate", "days"), BREATHING("Breathing rate", "nights"),
        STEPS("Steps", "records"), DISTANCE("Distance", "records"), ACTIVE_CALORIES("Active calories", "records"),
        WORKOUTS("Workouts", "sessions"), WEIGHT("Weight", "weigh-ins"),
    }

    /** One record as far as the check cares: what it is, who wrote it, and how many readings it carries. */
    data class Rec(val kind: Kind, val origin: String, val readings: Int = 1)

    /** Per kind, readings by writing app (origin package), Baseline's first. Kinds nobody wrote are left out. */
    fun byApp(recs: List<Rec>, self: String): List<Pair<Kind, List<Pair<String, Int>>>> =
        Kind.entries.mapNotNull { kind ->
            val counts = recs.filter { it.kind == kind }.groupBy { it.origin }.mapValues { (_, rs) -> rs.sumOf { it.readings } }
            if (counts.isEmpty()) null
            else kind to counts.entries.sortedWith(compareBy<Map.Entry<String, Int>> { it.key != self }.thenByDescending { it.value }).map { it.key to it.value }
        }

    /** Other apps writing the same kinds as Baseline: origin -> kinds. These compete with Zepp's copy. */
    fun otherWriters(rows: List<Pair<Kind, List<Pair<String, Int>>>>, self: String): Map<String, List<Kind>> =
        rows.flatMap { (kind, apps) -> apps.filter { it.first != self }.map { it.first to kind } }
            .groupBy({ it.first }, { it.second })

    data class Night(val start: Long, val end: Long, val origin: String, val stageMin: Map<String, Int>)

    data class NightCheck(
        val night: Night,
        /** Mean of every HRV reading in Health Connect inside the night (what apps like Google Health average). */
        val hrvAvg: Double?,
        val zeppStart: Long?,
        val zeppEnd: Long?,
        val zeppHrv: Double?,
    ) {
        /** Same night as Zepp's (within 5 minutes at both ends) and the same HRV (within 2 ms). */
        val matchesZepp: Boolean
            get() = zeppStart != null && zeppEnd != null && abs(night.start - zeppStart) <= 300 && abs(night.end - zeppEnd) <= 300 &&
                (zeppHrv == null || (hrvAvg != null && abs(hrvAvg - zeppHrv) <= 2.0))
    }

    /**
     * The most recent night in Health Connect (from any app), set against Zepp's own: its times from the summary's
     * sleeps, and its nightly HRV from that wake-up day's `sleepHrv`.
     */
    fun lastNight(nights: List<Night>, hrv: List<Pair<Long, Double>>, summary: Summary?, zone: ZoneId, hrvShift: (Night) -> Long = { 0 }): NightCheck? {
        val n = nights.filter { it.end - it.start >= 3 * 3600 }.maxByOrNull { it.end } ?: nights.maxByOrNull { it.end } ?: return null
        // With the Google Health option on, the night's readings sit [shift] later than the night itself.
        val shift = hrvShift(n)
        val inside = hrv.filter { (t, _) -> t >= n.start + shift && t < n.end + shift }.map { it.second }
        val zepp = summary?.sleeps?.filter { it.start < n.end && n.start < it.end }?.maxByOrNull { minOf(it.end, n.end) - maxOf(it.start, n.start) }
        val wakeDay = Instant.ofEpochSecond(zepp?.end ?: n.end).atZone(zone).toLocalDate().toString()
        val zeppHrv = summary?.days?.firstOrNull { it.date == wakeDay }?.sleepHrv
        return NightCheck(n, inside.takeIf { it.isNotEmpty() }?.average(), zepp?.start, zepp?.end, zeppHrv)
    }
}
