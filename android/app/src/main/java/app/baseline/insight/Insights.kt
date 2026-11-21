package app.baseline.insight

import app.baseline.data.AppSettings
import app.baseline.data.Day
import app.baseline.data.Sleep
import app.baseline.data.Summary
import app.baseline.data.Workout
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns raw daily data into the few judgements the app shows. Every rule is personal-baseline based and
 * documented in docs/METRICS.md — no black boxes. Thresholds live in [Rules].
 */
object Rules {
    /** Rolling window for the personal "normal range" (HRV4Training / Plews-style ~60 days). */
    const val BASELINE_DAYS = 60
    /** Minimum nights before a baseline is trusted. */
    const val MIN_BASELINE = 14
    /** Normal band = baseline mean ± this × SD — the smallest worthwhile change (Plews/Buchheit). */
    const val NORMAL_BAND_SD = 0.5
    const val SHORT_WINDOW = 7

    // Readiness = 70 + 15 × weighted z, clamped 0..100.
    const val W_HRV = 0.5
    const val W_RHR = 0.30
    const val W_SLEEP = 0.2

    /** Illness / strain flag: RHR ≥ +X SD *and* breathing rate ≥ +Y brpm over baseline. */
    const val ILL_RHR_Z = 1.5
    const val ILL_RESP_DELTA = 1.0

    const val SLEEP_LATENCY_MIN = 15
    const val ODI_ATTENTION = 5.0
}

enum class Level { Great, Good, Fair, Low, Unknown }

data class Baseline(val mean: Double, val sd: Double, val n: Int, val minHalfWidth: Double = 0.0) {
    private val half get() = maxOf(Rules.NORMAL_BAND_SD * sd, minHalfWidth)
    val low get() = mean - half
    val high get() = mean + half
}

/** One recovery signal (HRV, resting HR, breathing) judged against its personal normal range. */
data class Signal(
    val today: Double?,
    val avg7: Double?,
    /** Baseline in the metric's own units (HRV converted back from log space). */
    val baseline: Baseline?,
    /** Today vs baseline in SDs, sign-corrected so positive = better. */
    val z: Double?,
    val level: Level,
    val trend: List<Double?>,
)

data class Contributor(val name: String, val detail: String, val level: Level)

data class Readiness(
    val score: Int?,
    val level: Level,
    val headline: String,
    val contributors: List<Contributor>,
    val calibratingNights: Int?,
    val zeppScore: Int?,
)

data class SleepInsight(
    val last: Sleep?,
    val asleepMin: Int?,
    val needMin: Int,
    val avg7Min: Double?,
    val debt7Min: Int,
    val bedtimeSdMin: Double?,
    val wakeSdMin: Double?,
    val consistency: Level,
    val efficiency: Double?,
    val deepPct: Double?,
    val remPct: Double?,
    val suggestedBedtime: LocalTime?,
    val typicalWake: LocalTime?,
    val level: Level,
)

data class LoadState(val acute: Double, val chronic: Double, val form: Double, val ratio: Double?, val label: String, val level: Level)

data class ActivityInsight(
    val stepsToday: Int?,
    val stepGoal: Int,
    val steps7Avg: Double?,
    val activeWeek: Int,
    val activeGoal: Int,
    val paiWeek: Double?,
    val workoutsWeek: List<Workout>,
    val load: LoadState?,
    val vo2max: Double?,
    val vo2Trend: Double?,
)

data class Focus(val title: String, val body: String, val kind: Kind, val level: Level) {
    enum class Kind { Rest, Sleep, Train, Move, Steady, Calibrate, Sick }
}

data class Insights(
    val today: LocalDate,
    val zone: ZoneId,
    val hrv: Signal,
    val rhr: Signal,
    val resp: Signal,
    val readiness: Readiness,
    val sleep: SleepInsight,
    val activity: ActivityInsight,
    val focus: Focus,
    val illnessFlag: Boolean,
    val stressToday: Double?,
    val stress7: Double?,
    val spo2Score: Double?,
    val odi: Double?,
    val nightBreathingLevel: Level,
    val weightTrend: List<Pair<Long, Double>>,
    val weightWeeklyChange: Double?,
    val daysOfData: Int,
    /** The richer view behind [illnessFlag]: which overnight signals are off and for how many nights. */
    val signalsOff: SignalsOff? = null,
    /** Phase 3 metrics: SRI, sleep need and timing, HRV trend, overnight HR dip, zones, VO₂max band (Advanced.kt). */
    val advanced: AdvancedInsights? = null,
    /** Today's movement in plain minutes ("30–45 min easy today") for the Today screen. */
    val movementRange: MovementRange? = null,
)

class InsightEngine(private val s: Summary, private val settings: AppSettings, now: Instant = Instant.now()) {
    val zone: ZoneId = runCatching { ZoneId.of(s.timezone ?: "") }.getOrElse { ZoneId.systemDefault() }
    val today: LocalDate = now.atZone(zone).toLocalDate()
    private val byDate: Map<LocalDate, Day> = s.days.associateBy { it.localDate }
    private val nowSec = now.epochSecond

    fun dates(back: Int, end: LocalDate = today): List<LocalDate> = (back - 1 downTo 0).map { end.minusDays(it.toLong()) }
    fun day(d: LocalDate) = byDate[d]
    fun series(back: Int, end: LocalDate = today, f: (Day) -> Double?): List<Double?> = dates(back, end).map { d -> byDate[d]?.let(f) }

    /** Main sleep per wake-date: the longest session that ended that day. */
    val mainSleeps: Map<LocalDate, Sleep> by lazy {
        s.sleeps.groupBy { Instant.ofEpochSecond(it.end).atZone(zone).toLocalDate() }.mapValues { (_, v) -> v.maxBy { it.asleepMin } }
    }

    /** Phase 3 metrics and their per-day series (insight/Advanced.kt). */
    val advanced: AdvancedEngine by lazy { AdvancedEngine(s, settings, zone, today, mainSleeps) }

    /** Readiness as it would have been scored on [d] (for the history chart). Null when baselines are short. */
    fun readinessOn(d: LocalDate): Double? {
        val hrv = signal(logSpace = true, higherIsBetter = true, on = d) { it.sleepHrv }
        val rhr = signal(logSpace = false, higherIsBetter = false, minHalfWidth = 2.0, sdFloor = 1.0, on = d) { it.restingHr ?: it.sleepRhr }
        val hz = hrv.z ?: return null
        val rz = rhr.z ?: return null
        val sz = mainSleeps[d]?.let { ((it.asleepMin - settings.sleepNeedMin) / 60.0).coerceIn(-3.0, 1.0) } ?: 0.0
        val z = Rules.W_HRV * hz.coerceIn(-3.0, 2.0) + Rules.W_RHR * rz.coerceIn(-3.0, 2.0) + Rules.W_SLEEP * sz
        return (70 + 15 * z).coerceIn(1.0, 100.0)
    }

    /** Sleep-timing variability (the worse of bedtime and wake-time circular SD, minutes) over 14 nights ending [d]. */
    fun bedtimeSdOn(d: LocalDate): Double? {
        val nights = dates(14, d).mapNotNull { mainSleeps[it] }
        if (nights.size < 5) return null
        fun clock(e: Long) = Instant.ofEpochSecond(e).atZone(zone).toLocalTime().let { t -> t.hour * 60.0 + t.minute }
        return listOfNotNull(Stats.clockSdMinutes(nights.map { clock(it.start) }), Stats.clockSdMinutes(nights.map { clock(it.end) })).maxOrNull()
    }

    fun compute(): Insights {
        val hrv = signal(logSpace = true, higherIsBetter = true) { it.sleepHrv }
        // RHR: normal = ±2 bpm at least (amber starts at +3); breathing: ±0.5 brpm at least.
        val rhr = signal(logSpace = false, higherIsBetter = false, minHalfWidth = 2.0, sdFloor = 1.0) { it.restingHr ?: it.sleepRhr }
        val resp = signal(logSpace = false, higherIsBetter = false, minHalfWidth = 0.5, sdFloor = 0.3) { it.respRate }
        val sleep = sleepInsight()
        // "Signals off": ≥2 of 4 overnight signals out of band for 2 nights (Advanced.kt). Held back after a hard day.
        val signalsOff = advanced.signalsOff
        val illness = signalsOff.active && !signalsOff.afterHardTraining
        val readiness = readiness(hrv, rhr, sleep)
        val activity = activityInsight()
        val todayDay = byDate[today] ?: byDate[today.minusDays(1)]
        val odi = latest { it.spo2Odi }
        val spo2 = latest { it.spo2NightScore }
        val (wTrend, wRate) = weightTrend()
        return Insights(
            today = today, zone = zone, hrv = hrv, rhr = rhr, resp = resp,
            readiness = readiness, sleep = sleep, activity = activity,
            focus = focus(readiness, sleep, activity, illness, rhr, resp),
            illnessFlag = illness,
            stressToday = todayDay?.stress,
            stress7 = Stats.mean(series(7) { it.stress }.filterNotNull()),
            spo2Score = spo2, odi = odi,
            nightBreathingLevel = when {
                odi == null && spo2 == null -> Level.Unknown
                (odi ?: 0.0) >= Rules.ODI_ATTENTION -> Level.Low
                (spo2 ?: 100.0) < 80 -> Level.Fair
                else -> Level.Good
            },
            weightTrend = wTrend, weightWeeklyChange = wRate,
            daysOfData = s.days.count { it.restingHr != null || it.sleepHrv != null || it.steps != null },
            signalsOff = signalsOff,
            advanced = advanced.insights,
            movementRange = movementRange(readiness.level, illness, activity.load?.ratio, activity.activeWeek, activity.activeGoal),
        )
    }

    private fun latest(f: (Day) -> Double?): Double? =
        (0..2).firstNotNullOfOrNull { byDate[today.minusDays(it.toLong())]?.let(f) }

    /** Baseline from the [Rules.BASELINE_DAYS] days *before* the last week, so a bad week can't hide itself. */
    private fun signal(logSpace: Boolean, higherIsBetter: Boolean, minHalfWidth: Double = 0.0, sdFloor: Double = 0.5, on: LocalDate = today, f: (Day) -> Double?): Signal {
        val tx: (Double?) -> Double? = { v -> if (logSpace) Stats.lnOrNull(v) else v }
        val todayRaw = byDate[on]?.let(f)
        val recent = series(Rules.SHORT_WINDOW, on) { f(it) }
        // The 60 days up to yesterday; with a short history, whatever there is.
        val base = series(Rules.BASELINE_DAYS, on.minusDays(1)) { f(it) }.mapNotNull(tx)
        val baseline = if (base.size >= Rules.MIN_BASELINE) {
            val m = base.average()
            val sd = (Stats.sd(base) ?: 0.0).coerceAtLeast(if (logSpace) 0.03 else sdFloor)
            Baseline(m, sd, base.size, minHalfWidth)
        } else null
        val todayTx = tx(todayRaw)
        val z = if (baseline != null && todayTx != null) {
            val raw = (todayTx - baseline.mean) / baseline.sd
            if (higherIsBetter) raw else -raw
        } else null
        val shownBaseline = baseline?.let {
            if (logSpace) {
                // Convert the log-space band back to ms so the UI speaks plain units.
                val lo = exp(it.low); val hi = exp(it.high); val mid = exp(it.mean)
                Baseline(mid, (hi - lo) / (2 * Rules.NORMAL_BAND_SD), it.n)
            } else it
        }
        // RHR uses absolute bpm cut-offs (+3 amber, +5 red over the 60-day mean, Oura-style); others use z.
        val rhrDelta = if (!higherIsBetter && !logSpace && baseline != null && todayRaw != null) todayRaw - baseline.mean else null
        val level = when {
            z == null -> Level.Unknown
            rhrDelta != null && rhrDelta >= 5 -> Level.Low
            rhrDelta != null && rhrDelta >= 3 -> Level.Fair
            rhrDelta != null -> if (rhrDelta <= -2) Level.Great else Level.Good
            z >= Rules.NORMAL_BAND_SD -> Level.Great
            z >= -Rules.NORMAL_BAND_SD -> Level.Good
            z >= -1.0 -> Level.Fair
            else -> Level.Low
        }
        return Signal(
            today = todayRaw, avg7 = Stats.mean(recent.filterNotNull()), baseline = shownBaseline,
            z = z, level = level, trend = series(30, on) { f(it) },
        )
    }

    private fun readiness(hrv: Signal, rhr: Signal, sleep: SleepInsight): Readiness {
        val zepp = (byDate[today] ?: byDate[today.minusDays(1)])?.zeppReadiness?.roundToInt()
        val sleepZ = sleep.asleepMin?.let { ((it - sleep.needMin) / 60.0).coerceIn(-3.0, 1.0) }
        val contributors = buildList {
            add(Contributor("HRV", hrv.describe("ms", higherIsBetter = true), hrv.level))
            add(Contributor("Resting HR", rhr.describe("bpm", higherIsBetter = false), rhr.level))
            add(Contributor("Sleep", sleep.asleepMin?.let { "${fmtDuration(it)} of ${fmtDuration(sleep.needMin)} need" } ?: "No sleep recorded", sleep.level))
        }
        if (hrv.z == null || rhr.z == null) {
            val nights = s.days.count { it.sleepHrv != null }
            return Readiness(
                score = null, level = Level.Unknown,
                headline = if (nights < Rules.MIN_BASELINE) "Calibrating" else "Waiting for data",
                contributors = contributors,
                calibratingNights = nights.takeIf { it < Rules.MIN_BASELINE },
                zeppScore = zepp,
            )
        }
        val z = Rules.W_HRV * hrv.z.coerceIn(-3.0, 2.0) + Rules.W_RHR * rhr.z.coerceIn(-3.0, 2.0) +
            Rules.W_SLEEP * (sleepZ ?: 0.0)
        val score = (70 + 15 * z).roundToInt().coerceIn(1, 100)
        val parts = listOfNotNull(hrv.z, rhr.z, sleepZ)
        // Bands from the evidence review: red needs a broad deficit; one weak signal only makes it amber.
        val level = when {
            z < -1.5 -> Level.Low
            z < -0.5 || parts.any { it < -1.5 } -> Level.Fair
            z >= 0.5 -> Level.Great
            else -> Level.Good
        }
        val headline = when (level) {
            Level.Great -> "Primed"
            Level.Good -> "Ready"
            Level.Fair -> "Take it easier"
            else -> "Recover"
        }
        return Readiness(score, level, headline, contributors, null, zepp)
    }

    private fun Signal.describe(unit: String, higherIsBetter: Boolean): String {
        val t = today ?: return "No reading last night"
        val b = baseline ?: return "${t.roundToInt()} $unit · building baseline"
        val diff = t - b.mean
        val pct = diff / b.mean * 100
        val word = when {
            abs(z ?: 0.0) < Rules.NORMAL_BAND_SD -> "in your normal range"
            (diff > 0) == higherIsBetter -> "better than usual"
            else -> if (higherIsBetter) "below your normal" else "above your normal"
        }
        val delta = if (unit == "ms") "%+.0f%%".format(pct) else "%+.0f".format(diff)
        return "${t.roundToInt()} $unit ($delta) · $word"
    }

    private fun sleepInsight(): SleepInsight {
        val need = settings.sleepNeedMin
        // Main sleep per wake-date: the longest session that ended that day (naps don't move the needle here).
        val mains = s.sleeps.groupBy { Instant.ofEpochSecond(it.end).atZone(zone).toLocalDate() }
            .mapValues { (_, v) -> v.maxBy { it.asleepMin } }
        val lastDate = mains.keys.filter { !it.isAfter(today) }.maxOrNull()
        val last = lastDate?.let { mains[it] }?.takeIf { lastDate >= today.minusDays(1) }
        val week = dates(7).mapNotNull { mains[it] }
        val fortnight = dates(14).mapNotNull { mains[it] }
        val debt = dates(7).sumOf { d -> mains[d]?.let { max(0, need - it.asleepMin) } ?: 0 }
        fun clock(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(zone).toLocalTime().let { it.hour * 60.0 + it.minute }
        val bedSd = Stats.clockSdMinutes(fortnight.map { clock(it.start) })
        val wakeSd = Stats.clockSdMinutes(fortnight.map { clock(it.end) })
        val worst = listOfNotNull(bedSd, wakeSd).maxOrNull()
        val consistency = when {
            worst == null -> Level.Unknown
            worst <= 30 -> Level.Great
            worst <= 45 -> Level.Good
            worst <= 75 -> Level.Fair
            else -> Level.Low
        }
        val wakeMean = Stats.clockMeanMinutes(fortnight.map { clock(it.end) })
        val typicalWake = wakeMean?.let { LocalTime.of((it / 60).toInt() % 24, (it % 60).toInt()) }
        val suggested = typicalWake?.minusMinutes((need + Rules.SLEEP_LATENCY_MIN).toLong())
        val asleep = last?.asleepMin
        val inBed = last?.let { ((it.end - it.start) / 60).toInt() }
        val staged = last?.let { listOfNotNull(it.deepMin, it.lightMin, it.remMin).sum() }?.takeIf { it > 0 }
        val level = when {
            asleep == null -> Level.Unknown
            asleep >= need - 15 -> Level.Great
            asleep >= need - 60 -> Level.Good
            asleep >= need - 120 -> Level.Fair
            else -> Level.Low
        }
        return SleepInsight(
            last = last, asleepMin = asleep, needMin = need,
            avg7Min = Stats.mean(week.map { it.asleepMin.toDouble() }),
            debt7Min = debt, bedtimeSdMin = bedSd, wakeSdMin = wakeSd, consistency = consistency,
            efficiency = if (asleep != null && inBed != null && inBed > 0) (asleep.toDouble() / inBed).coerceAtMost(1.0) else null,
            deepPct = staged?.let { last.deepMin?.toDouble()?.div(it) },
            remPct = staged?.let { last.remMin?.toDouble()?.div(it) },
            suggestedBedtime = suggested, typicalWake = typicalWake, level = level,
        )
    }

    /** WHO-equivalent active minutes: moderate + 2 × vigorous over the last 7 days. */
    fun activeMinutes(d: Day): Double? {
        val m = d.moderateZoneMin; val v = d.vigorousZoneMin
        return if (m != null || v != null) (m ?: 0.0) + 2 * (v ?: 0.0) else d.activeMinutes
    }

    private fun activityInsight(): ActivityInsight {
        val todayDay = byDate[today]
        val stepGoal = settings.stepGoal
        val weekStart = today.minusDays(6)
        val weekWorkouts = s.workouts.filter {
            !Instant.ofEpochSecond(it.start).atZone(zone).toLocalDate().isBefore(weekStart)
        }.sortedByDescending { it.start }
        val vo2Days = s.days.filter { it.vo2max != null }
        val vo2s = vo2Days.mapNotNull { it.vo2max }.ifEmpty { s.workouts.mapNotNull { it.vo2max } }
        val vo2MonthAgo = vo2Days.lastOrNull { !it.localDate.isAfter(today.minusDays(30)) }?.vo2max
        return ActivityInsight(
            stepsToday = todayDay?.steps?.roundToInt(),
            stepGoal = stepGoal,
            steps7Avg = Stats.mean(series(7, today.minusDays(1)) { it.steps }.filterNotNull()),
            activeWeek = series(7) { activeMinutes(it) }.filterNotNull().sum().roundToInt(),
            activeGoal = settings.weeklyActiveGoal,
            paiWeek = todayDay?.paiWeek ?: byDate[today.minusDays(1)]?.paiWeek,
            workoutsWeek = weekWorkouts,
            load = loadState(),
            vo2max = vo2s.lastOrNull(),
            vo2Trend = vo2MonthAgo?.let { vo2s.last() - it },
        )
    }

    /** Daily load series: per-workout training load summed by local day (falls back to the day field). */
    fun dailyLoad(back: Int): List<Double> {
        val byDay = s.workouts.groupBy { Instant.ofEpochSecond(it.start).atZone(zone).toLocalDate() }
            .mapValues { (_, w) -> w.sumOf { it.trainingLoad ?: 0.0 } }
        return dates(back).map { d -> byDay[d] ?: byDate[d]?.trainingLoad ?: 0.0 }
    }

    /** Banister-style fitness/fatigue: acute 7-day and chronic 42-day EWMAs of daily load; form = chronic − acute. */
    private fun loadState(): LoadState? {
        val loads = dailyLoad(120)
        if (loads.count { it > 0 } < 4) return null
        val acute = Stats.ewma(loads, Stats.alphaForTimeConstant(7.0), 0.0).last() ?: return null
        val chronic = Stats.ewma(loads, Stats.alphaForTimeConstant(42.0), 0.0).last() ?: return null
        val ratio = if (chronic > 1) acute / chronic else null
        val form = chronic - acute
        val (label, level) = when {
            ratio == null -> "Building" to Level.Unknown
            ratio > 1.5 -> "Far above your usual" to Level.Low
            ratio > 1.3 -> "Above your usual" to Level.Fair
            ratio >= 0.8 -> "In your usual range" to Level.Great
            else -> "Lighter than usual" to Level.Fair
        }
        return LoadState(acute, chronic, form, ratio, label, level)
    }

    private fun focus(r: Readiness, sleep: SleepInsight, a: ActivityInsight, ill: Boolean, rhr: Signal, resp: Signal): Focus {
        val hour = Instant.ofEpochSecond(nowSec).atZone(zone).hour
        if (ill) {
            // Non-diagnostic copy from the signals-off rule (Advanced.kt); rhr/resp stay in the signature for callers.
            val so = advanced.signalsOff
            val which = so.signals.joinToString(", ") { it.offText }.replaceFirstChar { it.uppercase() }
            return Focus(
                "Your signals are off",
                "${so.message} $which for ${so.nights} nights.",
                Focus.Kind.Sick, Level.Low,
            )
        }
        if (r.calibratingNights != null) {
            return Focus(
                "Learning your normal",
                "Readiness unlocks after ${Rules.MIN_BASELINE} nights with HRV (${r.calibratingNights} so far). " +
                    "Wear your watch to sleep — until then you'll see Zepp's own score.",
                Focus.Kind.Calibrate, Level.Unknown,
            )
        }
        if (r.level == Level.Low) {
            return Focus("Make today a recovery day", "HRV and resting HR say you're under-recovered. Walk, stretch, eat well and protect tonight's sleep.", Focus.Kind.Rest, Level.Low)
        }
        if (sleep.debt7Min >= 180) {
            val bt = sleep.suggestedBedtime?.let { " Aim to be in bed by ${fmtTime(it)}." } ?: ""
            return Focus("Pay down your sleep debt", "You're ${fmtDuration(sleep.debt7Min)} short this week.$bt", Focus.Kind.Sleep, Level.Fair)
        }
        if (r.level == Level.Great && a.activeWeek < a.activeGoal) {
            return Focus("Good day to push", "You're well recovered and ${a.activeGoal - a.activeWeek} active minutes short of your weekly target. Make today's session count.", Focus.Kind.Train, Level.Great)
        }
        if (r.level == Level.Fair) {
            return Focus("Keep it easy", "Recovery is a little below your normal. Easy cardio or skill work beats intensity today.", Focus.Kind.Rest, Level.Fair)
        }
        val steps = a.stepsToday
        if (steps != null && hour in 12..20 && steps < a.stepGoal * 0.6) {
            val left = a.stepGoal - steps
            return Focus("${"%,d".format(left)} steps to go", "About ${(left / 110.0).roundToInt()} minutes of brisk walking gets you there.", Focus.Kind.Move, Level.Good)
        }
        return Focus(
            if (r.level == Level.Great) "You're primed" else "Steady — train as planned",
            if (a.activeWeek >= a.activeGoal) "You've hit ${a.activeWeek} of ${a.activeGoal} active minutes this week. Keep the rhythm." else "Recovery looks normal. Stick to the plan.",
            if (r.level == Level.Great) Focus.Kind.Train else Focus.Kind.Steady, r.level,
        )
    }

    /** Hacker's-Diet style trend: EWMA with ~10-weigh-in smoothing; weekly change from the last 14 days. */
    private fun weightTrend(): Pair<List<Pair<Long, Double>>, Double?> {
        val ws = s.weights.sortedBy { it.t }
        if (ws.isEmpty()) return emptyList<Pair<Long, Double>>() to null
        val trend = Stats.ewma(ws.map { it.kg }, 0.1).mapIndexed { i, v -> ws[i].t to v!! }
        val cutoff = ws.last().t - 14 * 86400
        val recent = trend.filter { it.first >= cutoff }
        val rate = if (recent.size >= 3) (recent.last().second - recent.first().second) /
            ((recent.last().first - recent.first().first) / 86400.0 / 7).coerceAtLeast(0.5) else null
        return trend to rate
    }

    companion object {
        fun fmtDuration(min: Int): String = if (min < 60) "${min}m" else "${min / 60}h ${"%02d".format(min % 60)}m"
        fun fmtTime(t: LocalTime): String = "%d:%02d %s".format(if (t.hour % 12 == 0) 12 else t.hour % 12, t.minute, if (t.hour < 12) "AM" else "PM")
    }
}
