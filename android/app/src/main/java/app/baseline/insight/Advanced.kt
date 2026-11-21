package app.baseline.insight

import app.baseline.data.AppSettings
import app.baseline.data.Day
import app.baseline.data.Sleep
import app.baseline.data.Summary
import app.baseline.data.Workout
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Phase 3 derived metrics: signals-off warning, Sleep Regularity Index, sleep need / social jet lag, HRV trend,
 * overnight heart-rate dip, time in zones, today's movement range, VO₂max percentile band and age-based step goal.
 * Pure Kotlin, no Android types. Formulas and evidence: docs/METRICS.md. [H] marks a heuristic threshold.
 */
object AdvancedRules {
    // 12 · Signals off (Mishra 2020, Quer 2021, Natarajan 2020, Mason 2022)
    const val SIGNALS_BASELINE_DAYS = 28
    const val SIGNALS_MIN_NIGHTS = 14
    const val SIG_RHR_Z = Rules.ILL_RHR_Z // +1.5 SD
    const val SIG_RESP_DELTA = Rules.ILL_RESP_DELTA // +1 breath/min
    const val SIG_HRV_Z = -1.0
    /** Skin-temperature readiness in its personal bottom 10 % [H]. */
    const val SIG_SKIN_PERCENTILE = 0.10
    const val SIG_MIN_VOTES = 2
    const val SIG_NIGHTS = 2
    /** The day before counts as "hard" when its load is above the 90th percentile of the last 90 days. */
    const val HARD_DAY_PERCENTILE = 0.90

    // 13 · Sleep Regularity Index (Phillips 2017, Windred 2024)
    const val SRI_WINDOW = 14
    const val SRI_MIN_DAYS = 7
    const val SRI_GOOD = 83.0
    const val SRI_OK = 72.0
    /** A sleep day counts only when at least 3 h of sleep was recorded in it (otherwise the watch was likely off). */
    const val SRI_MIN_SLEEP_MIN = 180

    // 14 · Sleep need, debt, social jet lag (Wittmann 2006, Roenneberg 2012)
    const val NEED_WINDOW = 14
    const val NEED_MIN_FREE_NIGHTS = 3
    /** Plausible adult range for an estimated need [H]. */
    const val NEED_FLOOR_MIN = 390
    const val NEED_CEIL_MIN = 570
    const val SJL_WINDOW = 28
    const val SJL_MIN_FREE = 4
    const val SJL_MIN_WORK = 8
    const val DEBT_TREND_MIN = 60

    // 15 · HRV trend (Plews 2014)
    const val HRV_WINDOW = 7
    const val HRV_MIN_NIGHTS = 3
    /** CV counts as rising at 1.25 × its usual weekly value [H]. */
    const val HRV_CV_RISE = 1.25

    // 16 · Overnight HR dip — timing interpretation is a consumer-wearable heuristic [H]
    const val DIP_EARLY = 0.5
    const val DIP_LATE = 0.75

    // 19 · VO₂max band: ± typical wearable estimation error (mL/kg/min)
    const val VO2_ERROR = 3.5
    const val VO2_MEDIAN_DAYS = 90
}

// ------------------------------------------------------------------------------------------- 12 · signals off

enum class OvernightSignal(val label: String, val offText: String) {
    RestingHr("Resting heart rate", "resting heart rate up"),
    Breathing("Breathing rate", "breathing rate up"),
    Hrv("HRV", "HRV down"),
    SkinTemp("Skin temperature", "skin-temperature score low"),
}

/** One night's vote: which signals could be judged ([checked]) and which were outside their band ([off]). */
data class NightVotes(val date: LocalDate, val checked: Set<OvernightSignal>, val off: Set<OvernightSignal>) {
    val votes: Int get() = off.size
}

data class SignalsOff(
    /** ≥2 signals off on each of the last 2 consecutive nights. */
    val active: Boolean,
    /** The signals off on the latest night. */
    val signals: List<OvernightSignal>,
    /** Consecutive nights (ending on the latest one) with ≥2 signals off. */
    val nights: Int,
    val latest: NightVotes?,
    /** Nights with resting HR or HRV in the 28 days before the latest night. */
    val historyNights: Int,
    /** The day before was unusually hard; the app holds the illness flag back and says so. */
    val afterHardTraining: Boolean,
    val message: String,
) {
    val calibrating: Boolean get() = historyNights < AdvancedRules.SIGNALS_MIN_NIGHTS
}

/**
 * Votes per night against a trailing 28-day baseline that leaves out nights which were themselves flagged
 * (so an illness can't hide inside its own baseline). Each signal needs ≥14 nights in its window to vote.
 */
class OvernightSignals(private val byDate: Map<LocalDate, Day>) {
    val votes: Map<LocalDate, NightVotes> by lazy {
        val out = HashMap<LocalDate, NightVotes>()
        val flagged = HashSet<LocalDate>()
        for (d in byDate.keys.sorted()) {
            val v = evaluate(d, flagged) ?: continue
            out[d] = v
            if (v.votes >= AdvancedRules.SIG_MIN_VOTES) flagged += d
        }
        out
    }

    private fun evaluate(d: LocalDate, flagged: Set<LocalDate>): NightVotes? {
        val day = byDate[d] ?: return null
        val window = (1..AdvancedRules.SIGNALS_BASELINE_DAYS).map { d.minusDays(it.toLong()) }
            .filter { it !in flagged }.mapNotNull { byDate[it] }
        val checked = mutableSetOf<OvernightSignal>()
        val off = mutableSetOf<OvernightSignal>()
        fun judge(sig: OvernightSignal, value: Double?, base: List<Double>, isOff: (Double, List<Double>) -> Boolean) {
            if (value == null || base.size < AdvancedRules.SIGNALS_MIN_NIGHTS) return
            checked += sig
            if (isOff(value, base)) off += sig
        }
        judge(OvernightSignal.RestingHr, rhrOf(day), window.mapNotNull(::rhrOf)) { v, b ->
            (v - b.average()) / (Stats.sd(b) ?: 0.0).coerceAtLeast(1.0) >= AdvancedRules.SIG_RHR_Z
        }
        judge(OvernightSignal.Breathing, day.respRate, window.mapNotNull { it.respRate }) { v, b ->
            v - b.average() >= AdvancedRules.SIG_RESP_DELTA
        }
        judge(OvernightSignal.Hrv, Stats.lnOrNull(day.sleepHrv), window.mapNotNull { Stats.lnOrNull(it.sleepHrv) }) { v, b ->
            (v - b.average()) / (Stats.sd(b) ?: 0.0).coerceAtLeast(0.03) <= AdvancedRules.SIG_HRV_Z
        }
        judge(OvernightSignal.SkinTemp, day.zeppSkinTempScore, window.mapNotNull { it.zeppSkinTempScore }) { v, b ->
            // Bottom 10 %, and strictly below the median so a flat score history can't vote every night.
            v <= percentile(b, AdvancedRules.SIG_SKIN_PERCENTILE) && v < (Stats.median(b) ?: v)
        }
        return if (checked.isEmpty()) null else NightVotes(d, checked, off)
    }

    /** The current state for [today]; [hardDay] says whether a date's training was unusually hard. */
    fun state(today: LocalDate, hardDay: (LocalDate) -> Boolean): SignalsOff {
        val latestDate = votes.keys.filter { !it.isAfter(today) }.maxOrNull()
        val latest = latestDate?.takeIf { !it.isBefore(today.minusDays(1)) }?.let { votes[it] }
        val anchor = latest?.date ?: today
        val history = (1..AdvancedRules.SIGNALS_BASELINE_DAYS).count { k ->
            byDate[anchor.minusDays(k.toLong())]?.let { rhrOf(it) != null || it.sleepHrv != null } == true
        }
        var nights = 0
        if (latest != null) {
            var d = latest.date
            while ((votes[d]?.votes ?: 0) >= AdvancedRules.SIG_MIN_VOTES) { nights++; d = d.minusDays(1) }
        }
        val active = nights >= AdvancedRules.SIG_NIGHTS
        val hard = active && latest != null && hardDay(latest.date.minusDays(1))
        val message = when {
            active -> "Several of your overnight signals are off — your body may be fighting something. Rest if you can." +
                if (hard) " Yesterday's hard session may explain some of it." else ""
            latest != null && nights == 1 ->
                "${latest.votes} of ${latest.checked.size} signals were off last night. One night is usually noise; Baseline will say if it holds a second night."
            latest != null -> "Your overnight signals are within your usual range."
            history < AdvancedRules.SIGNALS_MIN_NIGHTS ->
                "Baseline needs ${AdvancedRules.SIGNALS_MIN_NIGHTS - history} more nights to learn your usual overnight signals."
            else -> "No overnight readings from last night yet."
        }
        return SignalsOff(active, latest?.off?.sortedBy { it.ordinal }.orEmpty(), nights, latest, history, hard, message)
    }

    companion object {
        fun rhrOf(d: Day): Double? = d.restingHr ?: d.sleepRhr
    }
}

/** Nearest-rank percentile (q in 0..1). */
internal fun percentile(xs: List<Double>, q: Double): Double {
    val s = xs.sorted()
    val idx = (ceil(q * s.size).toInt() - 1).coerceIn(0, s.size - 1)
    return s[idx]
}

// --------------------------------------------------------------------------------- 13 · sleep regularity index

data class Sri(val value: Double?, val validDays: Int, val pairs: Int, val level: Level) {
    companion object {
        fun level(v: Double?): Level = when {
            v == null -> Level.Unknown
            v >= AdvancedRules.SRI_GOOD -> Level.Great
            v >= AdvancedRules.SRI_OK -> Level.Good
            else -> Level.Fair // "low" — kept amber, never red: it's a habit, not an alarm
        }
    }
}

/**
 * Per-minute sleep/wake, one array per "sleep day" (noon the day before → noon on that date). Sleep = minutes
 * inside a deep/light/REM stage span of any session (naps included); when a session has no stage timeline its
 * whole start–end window counts as sleep. Days with under 3 h of recorded sleep are treated as not worn.
 */
class SleepRegularity(private val sleeps: List<Sleep>, private val zone: ZoneId) {
    private val cache = HashMap<LocalDate, BooleanArray?>()

    fun minutes(d: LocalDate): BooleanArray? {
        if (cache.containsKey(d)) return cache[d]
        val from = ZonedDateTime.of(d.minusDays(1), LocalTime.NOON, zone).toEpochSecond()
        val to = from + 1440 * 60L
        val arr = BooleanArray(1440)
        fun mark(a: Long, b: Long) {
            val i0 = floor((max(a, from) - from) / 60.0).toInt()
            val i1 = floor((min(b, to) - from) / 60.0).toInt().coerceAtMost(1440)
            for (i in i0 until i1) arr[i] = true
        }
        for (s in sleeps) {
            if (s.end <= from || s.start >= to) continue
            val spans = s.stageSpans
            if (spans.isEmpty()) mark(s.start, s.end)
            else spans.filter { isSleepStage(it.third) }.forEach { mark(it.first, it.second) }
        }
        val result = arr.takeIf { a -> a.count { it } >= AdvancedRules.SRI_MIN_SLEEP_MIN }
        cache[d] = result
        return result
    }

    /** SRI = −100 + 200 / (M·pairs) · Σ 1[same state 24 h apart], over consecutive valid day pairs in the window. */
    fun sri(end: LocalDate, window: Int = AdvancedRules.SRI_WINDOW): Sri {
        val arrays = (window - 1 downTo 0).map { minutes(end.minusDays(it.toLong())) }
        val valid = arrays.count { it != null }
        var pairs = 0
        var matches = 0L
        for (i in 0 until arrays.size - 1) {
            val a = arrays[i] ?: continue
            val b = arrays[i + 1] ?: continue
            pairs++
            for (m in 0 until 1440) if (a[m] == b[m]) matches++
        }
        val value = if (valid >= AdvancedRules.SRI_MIN_DAYS && pairs >= AdvancedRules.SRI_MIN_DAYS - 1)
            -100 + 200.0 * matches / (1440.0 * pairs) else null
        return Sri(value, valid, pairs, Sri.level(value))
    }

    companion object {
        fun isSleepStage(stage: String): Boolean = stage.lowercase() !in setOf("awake", "wake", "unknown")
    }
}

// ------------------------------------------------------------------------ 14 · sleep need, debt, social jet lag

enum class DebtTrend { Easing, Steady, Building, Unknown }

data class SleepNeed(
    val needMin: Int,
    /** True when estimated from your free-day sleep; false = your setting. */
    val estimated: Boolean,
    val freeNights: Int,
    val includesNaps: Boolean,
    val settingMin: Int,
    /** Shortfall vs [needMin] summed over the last 7 nights with data (null with fewer than 4 nights). */
    val debt7Min: Int?,
    val debtPrev7Min: Int?,
    val trend: DebtTrend,
)

data class SleepTiming(
    /** Mean sleep midpoint over the last 14 nights, minutes after midnight (circular). */
    val midpointMin: Double?,
    val workMidMin: Double?,
    val freeMidMin: Double?,
    /** Free-day minus work-day midpoint, minutes (positive = later on free days). */
    val socialJetLagMin: Double?,
    /** MCTQ chronotype (MSFsc), minutes after midnight — an adaptation of a questionnaire to watch data [H]. */
    val chronotypeMin: Double?,
    val workNights: Int,
    val freeNights: Int,
)

/** Free day = a night that ends on Saturday or Sunday (MCTQ: sleep before a day off). Work days default Mon–Fri. */
fun isFreeWake(d: LocalDate): Boolean = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

/** Signed difference a − b on a 24 h clock, in −720..720 minutes. */
fun clockDiff(a: Double, b: Double): Double {
    var d = (a - b) % 1440.0
    if (d > 720) d -= 1440.0
    if (d < -720) d += 1440.0
    return d
}

// ----------------------------------------------------------------------------------------------- 15 · HRV trend

data class HrvTrend(
    /** 7-day mean of ln(HRV), shown back in ms. */
    val mean7Ms: Double?,
    val mean7Ln: Double?,
    /** Coefficient of variation of the 7 nightly ln(HRV) values, %. */
    val cv7: Double?,
    val nights7: Int,
    val bandLowMs: Double?,
    val bandHighMs: Double?,
    val level: Level,
    /** Median weekly CV over the previous 4 weeks. */
    val typicalCv: Double?,
    val cvRising: Boolean,
    val meanFalling: Boolean,
) {
    /** Rising day-to-day swings with a falling weekly mean: an early fatigue pattern (Plews 2014). */
    val fatigueNote: Boolean get() = cvRising && meanFalling
}

// ---------------------------------------------------------------------------------------- 16 · overnight HR dip

enum class DipTiming(val label: String) { Early("early in the night"), Middle("mid-night"), Late("late in the night") }

data class HrDip(
    val date: LocalDate,
    val bpm: Double,
    val at: Long,
    /** 0 = sleep onset, 1 = wake. */
    val fraction: Double,
    val timing: DipTiming,
    /** From hourly minimums (±1 h) rather than the bridge's per-minute nadir. */
    val approximate: Boolean,
)

// ---------------------------------------------------------------------------------------------- 17 · HR zones

data class ZoneSplit(val lowMin: Int, val thresholdMin: Int, val highMin: Int, val workouts: Int) {
    val totalMin: Int get() = lowMin + thresholdMin + highMin
    val lowShare: Double? get() = if (totalMin > 0) lowMin.toDouble() / totalMin else null
}

/**
 * Zepp zone seconds → (low, threshold, high) seconds. With the usual 5 zones: Z1–2 low, Z3 threshold, Z4–5 high.
 * With 4+ zones the top two are high, the one below is threshold, the rest low; 3 zones map one-to-one. Null under 3.
 */
fun zoneBuckets(zoneSeconds: List<Int>?): Triple<Int, Int, Int>? {
    val z = zoneSeconds ?: return null
    if (z.size < 3) return null
    val n = z.size
    return when (n) {
        3 -> Triple(z[0], z[1], z[2])
        4 -> Triple(z[0], z[1], z[2] + z[3])
        else -> Triple(z.subList(0, n - 3).sum(), z[n - 3], z[n - 2] + z[n - 1])
    }
}

// --------------------------------------------------------------------------------------- 18 · movement range

enum class Intensity(val label: String) { Gentle("gentle"), Easy("easy"), Moderate("moderate"), Hard("harder") }

data class MovementRange(val minMinutes: Int, val maxMinutes: Int, val intensity: Intensity, val text: String, val reason: String)

/**
 * Today's movement in plain minutes [H]: a tier from readiness, one notch easier when this week's load is well above
 * your usual (acute/chronic > 1.3), and a rest suggestion when signals are off.
 */
fun movementRange(readiness: Level, signalsOff: Boolean, loadRatio: Double?, activeWeek: Int, activeGoal: Int): MovementRange {
    if (signalsOff) {
        return MovementRange(0, 20, Intensity.Gentle, "Up to 20 min gentle movement today",
            "Several overnight signals are off, so rest comes first. A short easy walk is plenty.")
    }
    data class Tier(val lo: Int, val hi: Int, val i: Intensity)
    val tiers = listOf(
        Tier(10, 20, Intensity.Gentle), Tier(20, 40, Intensity.Easy), Tier(30, 45, Intensity.Easy),
        Tier(30, 60, Intensity.Moderate), Tier(45, 75, Intensity.Hard),
    )
    var t = when (readiness) { Level.Low -> 0; Level.Fair -> 1; Level.Unknown -> 2; Level.Good -> 3; Level.Great -> 4 }
    val reasons = mutableListOf(
        when (readiness) {
            Level.Low -> "Your body is asking for recovery."
            Level.Fair -> "Recovery is a little below your normal."
            Level.Unknown -> "Baseline is still learning your normal."
            Level.Good -> "Recovery looks normal."
            Level.Great -> "You're well recovered."
        },
    )
    if (loadRatio != null && loadRatio > 1.3 && t > 0) {
        t--; reasons += "This week's training is well above your usual, so a notch easier."
    }
    val tier = tiers[t]
    if (t >= 3) reasons += if (activeWeek >= activeGoal) "Weekly target already met; anything more is a bonus."
    else "${activeGoal - activeWeek} active minutes to go this week."
    return MovementRange(tier.lo, tier.hi, tier.i, "${tier.lo}–${tier.hi} min ${tier.i.label} today", reasons.joinToString(" "))
}

// ------------------------------------------------------------------------------ 19 · VO₂max band, step goal

/**
 * FRIEND treadmill VO₂peak percentiles (mL/kg/min) at P5, 10, 25, 50, 75, 90, 95 by decade (20–29 … 70–79).
 * Values: Kaminsky et al., Mayo Clin Proc 2015 (FRIEND). The 2022 update (Kaminsky 2022, doi:10.1016/j.mayocp.2021.08.020)
 * reports treadmill values 1.5–4.6 lower; swap its table in here when available — the code doesn't change.
 */
object Friend {
    val PCTS = doubleArrayOf(5.0, 10.0, 25.0, 50.0, 75.0, 90.0, 95.0)
    val MEN = arrayOf(
        doubleArrayOf(29.0, 32.1, 40.1, 48.0, 55.2, 61.8, 66.3),
        doubleArrayOf(27.2, 30.2, 35.9, 42.4, 49.2, 56.5, 59.8),
        doubleArrayOf(24.2, 26.8, 31.9, 37.8, 45.0, 52.1, 55.6),
        doubleArrayOf(20.9, 22.8, 27.1, 32.6, 39.7, 45.6, 50.7),
        doubleArrayOf(17.4, 20.0, 23.7, 28.2, 34.5, 40.3, 43.0),
        doubleArrayOf(16.3, 17.3, 20.4, 24.4, 30.4, 36.6, 39.7),
    )
    val WOMEN = arrayOf(
        doubleArrayOf(21.7, 25.3, 30.5, 37.6, 44.7, 51.3, 56.0),
        doubleArrayOf(19.0, 21.1, 25.3, 30.2, 36.1, 41.4, 45.8),
        doubleArrayOf(17.0, 18.8, 22.1, 26.7, 32.4, 38.4, 41.7),
        doubleArrayOf(16.0, 17.3, 19.9, 23.4, 27.6, 32.0, 35.9),
        doubleArrayOf(13.4, 15.6, 17.2, 20.0, 23.8, 27.0, 29.4),
        doubleArrayOf(13.1, 14.2, 15.6, 18.3, 20.8, 23.1, 24.1),
    )

    fun row(age: Int, sex: String): DoubleArray? {
        val table = when (sex) { "male" -> MEN; "female" -> WOMEN; else -> return null }
        return table[((age / 10) - 2).coerceIn(0, table.size - 1)]
    }

    /** Percentile of [v] by linear interpolation; 2 below P5 and 98 above P95. */
    fun percentileOf(v: Double, row: DoubleArray): Double {
        if (v < row[0]) return 2.0
        if (v > row[row.size - 1]) return 98.0
        for (i in 0 until row.size - 1) {
            if (v <= row[i + 1]) {
                val f = if (row[i + 1] == row[i]) 0.0 else (v - row[i]) / (row[i + 1] - row[i])
                return PCTS[i] + f * (PCTS[i + 1] - PCTS[i])
            }
        }
        return 98.0
    }
}

data class Vo2Band(val vo2: Double, val lowPct: Int, val highPct: Int, val sex: String, val age: Int) {
    private fun ord(n: Int) = "$n" + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }
    val group: String get() = "${if (sex == "female") "women" else "men"} in their ${(age / 10 * 10).coerceIn(20, 70)}s"
    val text: String get() = when {
        highPct <= 5 -> "below the 5th percentile for $group"
        lowPct >= 95 -> "above the 95th percentile for $group"
        else -> "roughly ${ord(lowPct)}–${ord(highPct)} percentile for $group"
    }
}

/** VO₂max as a percentile *band*: the percentiles of vo2 ± 3.5 mL/kg/min, widened to multiples of 5. */
fun vo2Band(vo2: Double?, age: Int?, sex: String): Vo2Band? {
    if (vo2 == null || age == null) return null
    val row = Friend.row(age, sex) ?: return null
    val lo = Friend.percentileOf(vo2 - AdvancedRules.VO2_ERROR, row)
    val hi = Friend.percentileOf(vo2 + AdvancedRules.VO2_ERROR, row)
    val l = (floor(lo / 5) * 5).toInt().coerceIn(5, 95)
    val h = (ceil(hi / 5) * 5).toInt().coerceIn(5, 95).coerceAtLeast(l)
    return Vo2Band(vo2, l, h, sex, age)
}

/** Daily-step plateau from Paluch 2022: 8–10k under 60, 6–8k from 60. Null without an age. */
fun stepBand(age: Int?): IntRange? = age?.let { if (it >= 60) 6000..8000 else 8000..10000 }

fun ageOf(settings: AppSettings, today: LocalDate): Int? =
    settings.birthYear?.let { today.year - it }?.takeIf { it in 13..110 }

// ------------------------------------------------------------------------------------------------ the engine

data class AdvancedInsights(
    val signalsOff: SignalsOff,
    val sri: Sri,
    val timing: SleepTiming,
    val need: SleepNeed,
    val hrvTrend: HrvTrend,
    val hrDip: HrDip?,
    val zones: ZoneSplit,
    val vo2Median: Double?,
    val vo2Band: Vo2Band?,
    val stepBand: IntRange?,
    val age: Int?,
)

/** Computes [AdvancedInsights] and per-day series for charts. Null in a series = no data that day. */
class AdvancedEngine(
    private val s: Summary,
    private val settings: AppSettings,
    val zone: ZoneId,
    val today: LocalDate,
    private val mainSleeps: Map<LocalDate, Sleep>,
) {
    private val byDate: Map<LocalDate, Day> = s.days.associateBy { it.localDate }
    val overnight = OvernightSignals(byDate)
    val regularity = SleepRegularity(s.sleeps, zone)

    private fun wakeDate(sl: Sleep): LocalDate = Instant.ofEpochSecond(sl.end).atZone(zone).toLocalDate()
    private fun clockMin(epoch: Long): Double = Instant.ofEpochSecond(epoch).atZone(zone).toLocalTime().let { it.hour * 60.0 + it.minute + it.second / 60.0 }

    private val sleepsByWake: Map<LocalDate, List<Sleep>> by lazy { s.sleeps.groupBy(::wakeDate) }

    /** Naps: every session ending on [d] other than the main sleep. */
    fun napMinutes(d: LocalDate): Int {
        val main = mainSleeps[d]
        return sleepsByWake[d].orEmpty().filter { it !== main }.sumOf { it.asleepMin }
    }

    /** Main sleep + naps for the sleep day ending on [d]; null when there's no main sleep. */
    fun totalSleep(d: LocalDate): Int? = mainSleeps[d]?.let { it.asleepMin + napMinutes(d) }

    private val loadByDate: Map<LocalDate, Double> by lazy {
        val fromWorkouts = s.workouts.groupBy { Instant.ofEpochSecond(it.start).atZone(zone).toLocalDate() }
            .mapValues { (_, w) -> w.sumOf { it.trainingLoad ?: 0.0 } }
        byDate.mapNotNull { (d, day) -> day.trainingLoad?.let { d to it } }.toMap() + fromWorkouts
    }

    fun hardDay(d: LocalDate): Boolean {
        val load = loadByDate[d] ?: return false
        val past = (1..90).mapNotNull { loadByDate[d.minusDays(it.toLong())] }.filter { it > 0 }
        if (past.size < 10) return false
        return load > percentile(past, AdvancedRules.HARD_DAY_PERCENTILE)
    }

    val signalsOff: SignalsOff by lazy { overnight.state(today, ::hardDay) }

    // --- series for charts
    fun votesOn(d: LocalDate): Double? = overnight.votes[d]?.votes?.toDouble()
    fun sriOn(d: LocalDate): Double? = regularity.sri(d).value

    /** Sleep midpoint as hours from midnight (−12..12, so 23:30 = −0.5 and 03:30 = 3.5). */
    fun midpointOn(d: LocalDate): Double? = mainSleeps[d]?.let { sl ->
        val m = clockMin((sl.start + sl.end) / 2)
        (if (m >= 720) m - 1440 else m) / 60.0
    }

    /** Rolling 7-night sleep shortfall vs the estimated need, in hours; null with fewer than 4 nights. */
    fun debtOn(d: LocalDate, need: Int = sleepNeed.needMin): Double? = debtMinutes(d, need)?.div(60.0)

    private fun debtMinutes(end: LocalDate, need: Int): Int? {
        val totals = (0 until 7).mapNotNull { totalSleep(end.minusDays(it.toLong())) }
        if (totals.size < 4) return null
        return totals.sumOf { max(0, need - it) }
    }

    fun hrvMeanOn(d: LocalDate): Double? = hrvRolling(d)?.first?.let { exp(it) }
    fun nadirOn(d: LocalDate): Double? = mainSleeps[d]?.let { hrDip(d, it)?.bpm }
    fun zoneMinutesOn(d: LocalDate): Double? {
        val ws = s.workouts.filter { Instant.ofEpochSecond(it.start).atZone(zone).toLocalDate() == d }
        val b = ws.mapNotNull { zoneBuckets(it.zoneSeconds) }
        return if (b.isEmpty()) null else b.sumOf { it.first + it.second + it.third } / 60.0
    }

    // --- 14
    val sleepNeed: SleepNeed by lazy {
        val dates = (0 until AdvancedRules.NEED_WINDOW).map { today.minusDays(it.toLong()) }
        val free = dates.filter(::isFreeWake).mapNotNull(::totalSleep)
        val naps = dates.filter(::isFreeWake).any { napMinutes(it) > 0 }
        val estimated = free.size >= AdvancedRules.NEED_MIN_FREE_NIGHTS
        val need = if (estimated) {
            val med = Stats.median(free.map { it.toDouble() })!!
            ((med / 5).roundToInt() * 5).coerceIn(AdvancedRules.NEED_FLOOR_MIN, AdvancedRules.NEED_CEIL_MIN)
        } else settings.sleepNeedMin
        val d7 = debtMinutes(today, need)
        val p7 = debtMinutes(today.minusDays(7), need)
        val trend = when {
            d7 == null || p7 == null -> DebtTrend.Unknown
            d7 - p7 >= AdvancedRules.DEBT_TREND_MIN -> DebtTrend.Building
            p7 - d7 >= AdvancedRules.DEBT_TREND_MIN -> DebtTrend.Easing
            else -> DebtTrend.Steady
        }
        SleepNeed(need, estimated, free.size, naps && estimated, settings.sleepNeedMin, d7, p7, trend)
    }

    val sleepTiming: SleepTiming by lazy {
        val nights = (0 until AdvancedRules.SJL_WINDOW).mapNotNull { k -> today.minusDays(k.toLong()).let { d -> mainSleeps[d]?.let { d to it } } }
        fun mid(sl: Sleep) = clockMin((sl.start + sl.end) / 2)
        val recent = nights.filter { it.first > today.minusDays(AdvancedRules.SRI_WINDOW.toLong()) }
        val free = nights.filter { isFreeWake(it.first) }
        val work = nights.filter { !isFreeWake(it.first) }
        val freeMid = Stats.clockMeanMinutes(free.map { mid(it.second) })
        val workMid = Stats.clockMeanMinutes(work.map { mid(it.second) })
        val enough = free.size >= AdvancedRules.SJL_MIN_FREE && work.size >= AdvancedRules.SJL_MIN_WORK
        val sjl = if (enough && freeMid != null && workMid != null) clockDiff(freeMid, workMid) else null
        val chrono = if (enough && freeMid != null) {
            val sdF = free.map { it.second.asleepMin.toDouble() }.average()
            val sdW = work.map { it.second.asleepMin.toDouble() }.average()
            val sdWeek = (5 * sdW + 2 * sdF) / 7
            if (sdF > sdW) ((freeMid - 0.5 * (sdF - sdWeek)) % 1440 + 1440) % 1440 else freeMid
        } else null
        SleepTiming(Stats.clockMeanMinutes(recent.map { mid(it.second) }), workMid, freeMid, sjl, chrono, work.size, free.size)
    }

    // --- 15
    /** (mean ln HRV, CV %) of the 7 nights ending [d]; null under 3 nights. */
    fun hrvRolling(d: LocalDate): Pair<Double, Double>? {
        val xs = (0 until AdvancedRules.HRV_WINDOW).mapNotNull { Stats.lnOrNull(byDate[d.minusDays(it.toLong())]?.sleepHrv) }
        if (xs.size < AdvancedRules.HRV_MIN_NIGHTS) return null
        val m = xs.average()
        val sd = Stats.sd(xs) ?: return null
        return m to sd / m * 100
    }

    val hrvTrend: HrvTrend by lazy {
        val now = hrvRolling(today) ?: hrvRolling(today.minusDays(1))
        val nights = (0 until AdvancedRules.HRV_WINDOW).count { byDate[today.minusDays(it.toLong())]?.sleepHrv != null }
        val base = (AdvancedRules.HRV_WINDOW until AdvancedRules.HRV_WINDOW + Rules.BASELINE_DAYS)
            .mapNotNull { Stats.lnOrNull(byDate[today.minusDays(it.toLong())]?.sleepHrv) }
        val bMean = if (base.size >= Rules.MIN_BASELINE) base.average() else null
        val bSd = if (bMean != null) (Stats.sd(base) ?: 0.0).coerceAtLeast(0.03) else null
        val lo = if (bMean != null && bSd != null) bMean - Rules.NORMAL_BAND_SD * bSd else null
        val hi = if (bMean != null && bSd != null) bMean + Rules.NORMAL_BAND_SD * bSd else null
        val level = when {
            now == null || lo == null || hi == null -> Level.Unknown
            now.first > hi -> Level.Great
            now.first >= lo -> Level.Good
            else -> Level.Fair
        }
        val weeklyCvs = (1..4).mapNotNull { hrvRolling(today.minusDays(7L * it))?.second }
        val typical = if (weeklyCvs.size >= 2) Stats.median(weeklyCvs) else null
        val prev = hrvRolling(today.minusDays(7))
        HrvTrend(
            mean7Ms = now?.first?.let { exp(it) }, mean7Ln = now?.first, cv7 = now?.second, nights7 = nights,
            bandLowMs = lo?.let { exp(it) }, bandHighMs = hi?.let { exp(it) }, level = level, typicalCv = typical,
            cvRising = now != null && typical != null && now.second >= typical * AdvancedRules.HRV_CV_RISE,
            meanFalling = now != null && prev != null && bSd != null && now.first - prev.first <= -Rules.NORMAL_BAND_SD * bSd,
        )
    }

    // --- 16
    fun hrDip(d: LocalDate, sl: Sleep): HrDip? {
        val span = (sl.end - sl.start).toDouble()
        if (span <= 0) return null
        var approximate = false
        val (bpm, at) = sl.hrNadir?.let { it.bpm to it.at } ?: run {
            approximate = true
            val rows = s.hourly.heartRate.filter { it.size >= 2 && it[0] + 3600 > sl.start && it[0] < sl.end }
            val r = rows.minByOrNull { it[1] } ?: return null
            r[1] to (r[0].toLong() + 1800).coerceIn(sl.start, sl.end)
        }
        val f = ((at - sl.start) / span).coerceIn(0.0, 1.0)
        val timing = when {
            f < AdvancedRules.DIP_EARLY -> DipTiming.Early
            f < AdvancedRules.DIP_LATE -> DipTiming.Middle
            else -> DipTiming.Late
        }
        return HrDip(d, bpm, at, f, timing, approximate)
    }

    val latestDip: HrDip? by lazy {
        listOf(today, today.minusDays(1)).firstNotNullOfOrNull { d -> mainSleeps[d]?.let { hrDip(d, it) } }
    }

    // --- 17
    fun zoneSplit(end: LocalDate = today, days: Int = 7): ZoneSplit {
        val from = end.minusDays(days - 1L)
        val ws = s.workouts.filter { Instant.ofEpochSecond(it.start).atZone(zone).toLocalDate().let { d -> !d.isBefore(from) && !d.isAfter(end) } }
        val b = ws.mapNotNull { zoneBuckets(it.zoneSeconds) }
        return ZoneSplit(
            (b.sumOf { it.first } / 60.0).roundToInt(), (b.sumOf { it.second } / 60.0).roundToInt(),
            (b.sumOf { it.third } / 60.0).roundToInt(), b.size,
        )
    }

    // --- 19
    val vo2Median: Double? by lazy {
        val recent = (0 until AdvancedRules.VO2_MEDIAN_DAYS).mapNotNull { byDate[today.minusDays(it.toLong())]?.vo2max }
            .ifEmpty { s.workouts.filter { it.start >= today.minusDays(AdvancedRules.VO2_MEDIAN_DAYS.toLong()).atStartOfDay(zone).toEpochSecond() }.mapNotNull(Workout::vo2max) }
        Stats.median(recent)
    }

    val insights: AdvancedInsights by lazy {
        val age = ageOf(settings, today)
        AdvancedInsights(
            signalsOff = signalsOff, sri = regularity.sri(today), timing = sleepTiming, need = sleepNeed,
            hrvTrend = hrvTrend, hrDip = latestDip, zones = zoneSplit(), vo2Median = vo2Median,
            vo2Band = vo2Band(vo2Median, age, settings.sex), stepBand = stepBand(age), age = age,
        )
    }
}
