package app.baseline.ui.story

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.baseline.Model
import app.baseline.insight.DebtTrend
import app.baseline.insight.DipTiming
import app.baseline.insight.InsightEngine
import app.baseline.insight.Level
import app.baseline.insight.Stats
import app.baseline.insight.zoneBuckets
import app.baseline.ui.common.Fmt
import app.baseline.ui.theme.HealthColors
import app.baseline.ui.theme.Brand
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt

/** How a metric is charted and phrased. */
data class Spec(
    val unit: String,
    val color: Color,
    val higherIsBetter: Boolean?,
    val bars: Boolean,
    val format: (Double) -> String,
)

/** Minutes after midnight (any sign) → "3:40 AM". */
private fun clockText(minutes: Double): String {
    val m = (((minutes.roundToInt()) % 1440) + 1440) % 1440
    return InsightEngine.fmtTime(LocalTime.of(m / 60, m % 60))
}

/** Signed minutes → "1h 10m". */
private fun spanText(min: Double): String = InsightEngine.fmtDuration(abs(min).roundToInt())

private fun ordinal(n: Int) = "$n" + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }

fun spec(m: Metric, h: HealthColors): Spec = when (m) {
    Metric.Readiness -> Spec("", h.great, true, false, { "%.0f".format(it) })
    Metric.Hrv -> Spec("ms", h.hrv, true, false, { "%.0f".format(it) })
    Metric.RestingHr -> Spec("bpm", h.heart, false, false, { "%.0f".format(it) })
    Metric.Breathing -> Spec("/min", h.oxygen, false, false, { "%.1f".format(it) })
    Metric.Sleep -> Spec("h", h.sleep, true, true, { "%.1f".format(it) })
    Metric.Consistency -> Spec("", h.sleep, true, false, { "%.0f".format(it) })
    Metric.Steps -> Spec("", h.steps, true, true, { if (it >= 1000) "%.1fk".format(it / 1000).replace(".0k", "k") else "%.0f".format(it) })
    Metric.Active -> Spec("min", h.great, true, true, { "%.0f".format(it) })
    Metric.Vo2 -> Spec("", h.great, true, false, { "%.1f".format(it) })
    Metric.Load -> Spec("", h.fair, null, true, { "%.0f".format(it) })
    Metric.Stress -> Spec("", h.stress, false, false, { "%.0f".format(it) })
    Metric.Spo2 -> Spec("", h.oxygen, true, false, { "%.0f".format(it) })
    Metric.Weight -> Spec("kg", h.steps, null, false, { "%.1f".format(it) })
    Metric.Signals -> Spec("off", h.heart, false, true, { "%.0f".format(it) })
    Metric.HrvTrend -> Spec("ms", h.hrv, true, false, { "%.0f".format(it) })
    Metric.HrDip -> Spec("bpm", h.heart, false, false, { "%.0f".format(it) })
    Metric.SleepNeed -> Spec("h short", h.sleep, false, false, { "%.1f".format(it) })
    Metric.SleepTiming -> Spec("", h.sleep, null, false, { clockText(it * 60) })
    Metric.Zones -> Spec("min", h.great, null, true, { "%.0f".format(it) })
    Metric.Movement -> Spec("min", h.great, true, true, { "%.0f".format(it) })
}

fun hasData(m: Model, metric: Metric): Boolean = when (metric) {
    Metric.Readiness -> m.data.summary?.days?.any { it.sleepHrv != null || it.zeppReadiness != null } == true
    Metric.Weight -> m.data.summary?.weights?.isNotEmpty() == true
    Metric.Load -> m.data.summary?.workouts?.any { it.trainingLoad != null } == true
    Metric.Sleep, Metric.Consistency, Metric.SleepNeed, Metric.SleepTiming -> m.data.summary?.sleeps?.isNotEmpty() == true
    Metric.Signals -> m.engine.advanced.overnight.votes.isNotEmpty()
    Metric.Zones -> m.data.summary?.workouts?.any { zoneBuckets(it.zoneSeconds) != null } == true
    Metric.Movement -> m.data.summary?.days?.isNotEmpty() == true
    else -> values(m, metric, 120).any { it != null }
}

/** Daily series for [days] days ending today; null = no reading that day (never zero-filled). */
fun values(m: Model, metric: Metric, days: Int): List<Double?> {
    val e = m.engine
    val a = e.advanced
    return when (metric) {
        // Baseline's own score needs 14 nights of history; until then (and for any day it can't score) use Zepp's.
        Metric.Readiness -> e.dates(days).map { d -> e.readinessOn(d) ?: e.day(d)?.zeppReadiness }
        Metric.Hrv -> e.series(days) { it.sleepHrv }
        Metric.RestingHr -> e.series(days) { it.restingHr ?: it.sleepRhr }
        Metric.Breathing -> e.series(days) { it.respRate }
        Metric.Sleep -> e.dates(days).map { d -> e.mainSleeps[d]?.asleepMin?.div(60.0) }
        Metric.Consistency -> e.dates(days).map { a.sriOn(it) }
        Metric.Steps -> e.series(days) { it.steps }
        Metric.Active, Metric.Movement -> e.series(days) { e.activeMinutes(it) }
        Metric.Vo2 -> e.series(days) { it.vo2max }
        Metric.Load -> e.dailyLoad(days).map { it.takeIf { v -> v > 0 } }
        Metric.Stress -> e.series(days) { it.stress }
        Metric.Spo2 -> e.series(days) { it.spo2NightScore }
        Metric.Weight -> {
            val byDay: Map<LocalDate, Double> = m.data.summary?.weights.orEmpty()
                .groupBy { Instant.ofEpochSecond(it.t).atZone(e.zone).toLocalDate() }.mapValues { (_, v) -> v.last().kg }
            e.dates(days).map { byDay[it] }
        }
        Metric.Signals -> e.dates(days).map { a.votesOn(it) }
        Metric.HrvTrend -> e.dates(days).map { a.hrvMeanOn(it) }
        Metric.HrDip -> e.dates(days).map { a.nadirOn(it) }
        Metric.SleepNeed -> e.dates(days).map { a.debtOn(it) }
        Metric.SleepTiming -> e.dates(days).map { a.midpointOn(it) }
        Metric.Zones -> e.dates(days).map { a.zoneMinutesOn(it) }
    }
}

data class Now(val value: String, val unit: String, val status: String, val level: Level)

/** The headline value + one-word status for a metric. */
fun now(m: Model, metric: Metric, sp: Spec): Now {
    val i = m.insights
    val adv = i.advanced ?: m.engine.advanced.insights
    fun lv(v: Double?, l: Level, status: String) = Now(v?.let(sp.format) ?: "—", sp.unit, status, l)
    fun word(l: Level) = when (l) { Level.Great -> "Above your normal"; Level.Good -> "In your normal range"; Level.Fair -> "Below your normal"; Level.Low -> "Well below normal"; else -> "Building your baseline" }
    return when (metric) {
        Metric.Readiness -> {
            val r = i.readiness
            Now((r.score ?: r.zeppScore)?.toString() ?: "—", "", if (r.score == null) "Learning your normal" else r.headline, r.level)
        }
        Metric.Hrv -> lv(i.hrv.today, i.hrv.level, word(i.hrv.level))
        Metric.RestingHr -> lv(i.rhr.today, i.rhr.level, when (i.rhr.level) {
            Level.Great -> "Lower than usual"; Level.Good -> "In your normal range"; Level.Fair -> "A little elevated"; Level.Low -> "Elevated"; else -> "Building your baseline"
        })
        Metric.Breathing -> lv(i.resp.today, i.resp.level, if (i.resp.level == Level.Low || i.resp.level == Level.Fair) "Higher than usual" else if (i.resp.level == Level.Unknown) "Building your baseline" else "Steady")
        Metric.Sleep -> Now(Fmt.duration(i.sleep.asleepMin), "", when (i.sleep.level) {
            Level.Great -> "Fully rested"; Level.Good -> "Nearly there"; Level.Fair -> "Short night"; Level.Low -> "Very short night"; else -> "No sleep recorded"
        }, i.sleep.level)
        Metric.Consistency -> {
            val sri = adv.sri
            Now(sri.value?.roundToInt()?.toString() ?: "—", "/ 100", when (sri.level) {
                Level.Great -> "Regular"; Level.Good -> "Fairly regular"; Level.Fair, Level.Low -> "Irregular"
                else -> "Needs ${(7 - sri.validDays).coerceAtLeast(1)} more nights"
            }, sri.level)
        }
        Metric.Steps -> {
            val a = i.activity
            val l = a.steps7Avg?.let { if (it >= a.stepGoal) Level.Great else if (it >= a.stepGoal * .75) Level.Good else if (it >= 5000) Level.Fair else Level.Low } ?: Level.Unknown
            Now(a.stepsToday?.let { "%,d".format(it) } ?: "—", "today", a.steps7Avg?.let { "7-day avg ${"%,d".format(it.roundToInt())}" } ?: "", l)
        }
        Metric.Active -> {
            val a = i.activity
            val l = when { a.activeWeek >= 300 -> Level.Great; a.activeWeek >= a.activeGoal -> Level.Great; a.activeWeek >= 75 -> Level.Fair; else -> Level.Low }
            Now("${a.activeWeek}", "min / 7 days", if (a.activeWeek >= a.activeGoal) "Weekly target met" else "${a.activeGoal - a.activeWeek} min to go", l)
        }
        Metric.Vo2 -> lv(i.activity.vo2max, Level.Unknown,
            adv.vo2Band?.let { "${ordinal(it.lowPct)}–${ordinal(it.highPct)} percentile" }
                ?: i.activity.vo2Trend?.let { "%+.1f vs a month ago".format(it) } ?: "Cardio fitness")
        Metric.Load -> i.activity.load?.let { Now("%.0f".format(it.acute), "7-day", it.label, it.level) } ?: Now("—", "", "Needs a few workouts", Level.Unknown)
        Metric.Stress -> {
            val v = i.stressToday
            lv(v, v?.let { if (it < 25) Level.Great else if (it < 40) Level.Good else if (it < 60) Level.Fair else Level.Low } ?: Level.Unknown,
                v?.let { if (it < 25) "Relaxed" else if (it < 40) "Normal" else if (it < 60) "Elevated" else "High" } ?: "No reading yet")
        }
        Metric.Spo2 -> lv(i.spo2Score, i.nightBreathingLevel, when (i.nightBreathingLevel) { Level.Good, Level.Great -> "Normal"; Level.Fair -> "Worth watching"; Level.Low -> "Frequent dips"; else -> "No reading" })
        Metric.Weight -> {
            val t = i.weightTrend.lastOrNull()?.second
            Now(t?.let(sp.format) ?: "—", "kg trend", i.weightWeeklyChange?.let { "%+.2f kg / week".format(it) } ?: "", Level.Unknown)
        }
        Metric.Signals -> {
            val so = i.signalsOff ?: adv.signalsOff
            val last = so.latest
            when {
                last == null -> Now("—", "", if (so.calibrating) "Learning your usual range" else "No reading last night", Level.Unknown)
                so.active -> Now("${last.votes}", "of ${last.checked.size} off", "Off for ${so.nights} nights", Level.Fair)
                else -> Now("${last.votes}", "of ${last.checked.size} off", if (last.votes == 0) "All in your usual range" else "${last.votes} off last night", if (last.votes >= 2) Level.Fair else Level.Good)
            }
        }
        Metric.HrvTrend -> {
            val t = adv.hrvTrend
            Now(t.mean7Ms?.let(sp.format) ?: "—", "ms · 7-day", when {
                t.fatigueNote -> "Swings rising, trend falling"
                t.level == Level.Great -> "Above your normal range"
                t.level == Level.Good -> "In your normal range"
                t.level == Level.Fair -> "Below your normal range"
                t.mean7Ms == null -> "Needs 3 nights a week"
                else -> "Building your baseline"
            }, if (t.fatigueNote) Level.Fair else t.level)
        }
        Metric.HrDip -> adv.hrDip?.let { d ->
            Now(sp.format(d.bpm), "bpm", "at ${Fmt.clock(d.at, i.zone)} · ${d.timing.label}", if (d.timing == DipTiming.Late) Level.Fair else Level.Good)
        } ?: Now("—", "", "No overnight heart rate yet", Level.Unknown)
        Metric.SleepNeed -> {
            val n = adv.need
            Now(Fmt.duration(n.needMin), "", when (n.trend) {
                DebtTrend.Easing -> "Shortfall easing"; DebtTrend.Steady -> "Shortfall steady"; DebtTrend.Building -> "Shortfall building"
                DebtTrend.Unknown -> if (n.estimated) "From your free days" else "From your setting"
            }, when (n.trend) { DebtTrend.Easing -> Level.Great; DebtTrend.Steady -> Level.Good; DebtTrend.Building -> Level.Fair; else -> Level.Unknown })
        }
        Metric.SleepTiming -> {
            val t = adv.timing
            val sjl = t.socialJetLagMin
            Now(t.midpointMin?.let(::clockText) ?: "—", "midpoint", when {
                sjl == null -> "Needs a few more weekends"
                abs(sjl) < 30 -> "Same on weekends"
                else -> "Weekends ${spanText(sjl)} ${if (sjl > 0) "later" else "earlier"}"
            }, when { sjl == null -> Level.Unknown; abs(sjl) < 60 -> Level.Good; else -> Level.Fair })
        }
        Metric.Zones -> {
            val z = adv.zones
            Now("${z.totalMin}", "min / 7 days", z.lowShare?.let { "${(it * 100).roundToInt()}% easy" } ?: "No zone data this week", Level.Unknown)
        }
        Metric.Movement -> i.movementRange?.let { r ->
            Now("${r.minMinutes}–${r.maxMinutes}", "min ${r.intensity.label}", "Suggested for today", Level.Unknown)
        } ?: Now("—", "", "", Level.Unknown)
    }
}

/** A personal, plain-language takeaway: what your numbers say right now. */
fun takeaway(m: Model, metric: Metric, sp: Spec): String {
    val i = m.insights
    val adv = i.advanced ?: m.engine.advanced.insights
    val vals = values(m, metric, 35)
    val last7 = Stats.mean(vals.takeLast(7).filterNotNull())
    val prev = Stats.mean(vals.dropLast(7).takeLast(28).filterNotNull())
    val trend = if (last7 != null && prev != null && prev != 0.0) {
        val pct = (last7 - prev) / prev * 100
        if (abs(pct) < 3) "steady versus the previous four weeks"
        else "${if (pct > 0) "up" else "down"} ${"%.0f".format(abs(pct))}% on the previous four weeks"
    } else null
    val u = if (sp.unit.isBlank()) "" else " ${sp.unit}"
    return when (metric) {
        Metric.Readiness -> {
            val r = i.readiness
            if (r.score == null) "Baseline needs ${14 - (r.calibratingNights ?: 0)} more nights of HRV to learn your normal. Until then you're seeing Zepp's own score."
            else {
                val weakest = r.contributors.filter { it.level != Level.Unknown }.maxByOrNull { it.level.ordinal } ?: r.contributors.first()
                "${r.headline}. ${weakest.name}: ${weakest.detail}."
            }
        }
        Metric.Sleep -> {
            val s = i.sleep
            buildString {
                append("You're averaging ${s.avg7Min?.let { InsightEngine.fmtDuration(it.roundToInt()) } ?: "—"} a night this week against a need of ${InsightEngine.fmtDuration(s.needMin)}")
                append(if (s.debt7Min >= 60) ", ${InsightEngine.fmtDuration(s.debt7Min)} short in total." else " — no meaningful debt.")
                s.suggestedBedtime?.let { append(" Bed by ${InsightEngine.fmtTime(it)} tonight gets you a full night.") }
                if (adv.need.estimated && abs(adv.need.needMin - s.needMin) >= 15)
                    append(" Your free-day sleep suggests your need is closer to ${InsightEngine.fmtDuration(adv.need.needMin)}.")
            }
        }
        Metric.Consistency -> {
            val s = i.sleep
            val sri = adv.sri
            val spread = s.bedtimeSdMin?.let { "Your bedtime moves about ±${it.roundToInt()} min and wake time ±${s.wakeSdMin?.roundToInt() ?: 0} min night to night." }
            if (sri.value == null) listOfNotNull(
                "Sleep regularity needs at least 7 recorded days in the last two weeks (${sri.validDays} so far).", spread,
            ).joinToString(" ")
            else listOfNotNull(
                "Your sleep timing was ${sri.value.roundToInt()}/100 regular over the last ${sri.validDays} days.",
                spread,
                if (sri.level == Level.Great) "That's a well-anchored body clock." else "Anchoring your wake time, weekends included, is the quickest win.",
            ).joinToString(" ")
        }
        Metric.Active -> {
            val a = i.activity
            if (a.activeWeek >= a.activeGoal) "You've done ${a.activeWeek} active minutes in the last 7 days — target met. Up to 300 still adds benefit."
            else "${a.activeWeek} of ${a.activeGoal} minutes in the last 7 days. ${a.activeGoal - a.activeWeek} more — about ${(a.activeGoal - a.activeWeek + 29) / 30} brisk 30-minute walks — gets you there."
        }
        Metric.Steps -> {
            val a = i.activity
            val hit = values(m, metric, 14).count { (it ?: 0.0) >= a.stepGoal }
            "7-day average ${a.steps7Avg?.let { "%,d".format(it.roundToInt()) } ?: "—"} steps; goal reached on $hit of the last 14 days." +
                (trend?.let { " That's $it." } ?: "") +
                (adv.stepBand?.let { " For your age, the benefit levels off around ${it.first / 1000}–${it.last / 1000}k a day." } ?: "")
        }
        Metric.Vo2 -> {
            val v = adv.vo2Median ?: i.activity.vo2max
            when {
                v == null -> "No VO₂max estimate yet. Outdoor runs or walks with GPS and heart rate produce one."
                adv.vo2Band != null -> "Your VO₂max is around ${"%.0f".format(v)}, ${adv.vo2Band.text}. Watch estimates can be a few points off, so read it as a band and follow the trend."
                else -> "Your VO₂max is around ${"%.0f".format(v)} (90-day median)." + (trend?.let { " That's $it." } ?: "") +
                    " Add your birth year and sex in Settings (optional, stays on this phone) to see how it compares with people your age."
            }
        }
        Metric.Load -> i.activity.load?.let { "This week's load is ${it.label.lowercase()}: ${"%.0f".format(it.acute)} vs your usual ${"%.0f".format(it.chronic)}." }
            ?: "Log a few workouts with heart rate and this fills in."
        Metric.Weight -> i.weightWeeklyChange?.let { "Your trend is ${if (it < 0) "falling" else "rising"} ${"%.2f".format(abs(it))} kg a week. ${if (abs(it) > 1) "That's faster than is usually sustainable." else ""}".trim() }
            ?: "Weigh in a few more times to see a trend."
        Metric.Signals -> {
            val so = i.signalsOff ?: adv.signalsOff
            val last = so.latest
            buildString {
                append(so.message)
                if (last != null && last.off.isNotEmpty()) append(" Last night: ${last.off.sortedBy { it.ordinal }.joinToString(", ") { it.offText }}.")
                if (last != null && last.checked.size < 4) append(" ${last.checked.size} of 4 signals could be checked.")
            }
        }
        Metric.HrvTrend -> {
            val t = adv.hrvTrend
            if (t.mean7Ms == null) "The weekly trend needs HRV from at least 3 of the last 7 nights."
            else buildString {
                append("Your 7-day HRV averages ${"%.0f".format(t.mean7Ms)} ms")
                if (t.bandLowMs != null && t.bandHighMs != null) append(" (normal range ${"%.0f".format(t.bandLowMs)}–${"%.0f".format(t.bandHighMs)} ms)")
                append(".")
                t.cv7?.let { cv -> append(" Day-to-day swings are ${"%.1f".format(cv)}%" + (t.typicalCv?.let { ", against a usual ${"%.1f".format(it)}%." } ?: ".")) }
                append(when {
                    t.fatigueNote -> " Swings are rising while the weekly average falls — often an early sign of fatigue. A few easier days and good sleep usually turn it around."
                    t.level == Level.Fair -> " The weekly average is below your range; go lighter for a few days."
                    else -> " That's a steady picture."
                })
            }
        }
        Metric.HrDip -> adv.hrDip?.let { d ->
            "Lowest heart rate ${"%.0f".format(d.bpm)} bpm at ${Fmt.clock(d.at, i.zone)}, ${(d.fraction * 100).roundToInt()}% of the way through the night" +
                (if (d.approximate) " (to the nearest hour)." else ".") +
                " Early dips usually mean better recovery; late dips often follow alcohol or a late meal."
        } ?: "No overnight heart rate for last night yet."
        Metric.SleepNeed -> {
            val n = adv.need
            buildString {
                if (n.estimated) {
                    append("Your free-day sleep over the last two weeks suggests you need about ${InsightEngine.fmtDuration(n.needMin)}")
                    append(if (n.includesNaps) ", naps included" else "")
                    append(" (your setting is ${InsightEngine.fmtDuration(n.settingMin)}).")
                } else append("Not enough free-day nights yet, so Baseline uses your setting of ${InsightEngine.fmtDuration(n.needMin)}.")
                n.debt7Min?.let { d ->
                    append(if (d < 30) " You're keeping up over the last 7 nights" else " You're about ${InsightEngine.fmtDuration(d)} short over the last 7 nights")
                    append(when (n.trend) {
                        DebtTrend.Easing -> ", less than the week before."
                        DebtTrend.Steady -> ", about the same as the week before."
                        DebtTrend.Building -> ", more than the week before. An earlier night or two will help."
                        DebtTrend.Unknown -> "."
                    })
                }
            }
        }
        Metric.SleepTiming -> {
            val t = adv.timing
            listOfNotNull(
                t.midpointMin?.let { "The middle of your night is around ${clockText(it)}." },
                t.socialJetLagMin?.let { sjl ->
                    if (abs(sjl) < 30) "Free days and work days line up within half an hour — easy on your body clock."
                    else "On free days it shifts ${spanText(sjl)} ${if (sjl > 0) "later" else "earlier"} than on work days." +
                        if (abs(sjl) >= 60) " Keeping weekend wake-up within an hour of weekdays helps." else ""
                } ?: "Social jet lag needs at least 4 free-day and 8 work-day nights in the last four weeks.",
                t.chronotypeMin?.let { "Corrected for catch-up sleep, your natural midpoint is about ${clockText(it)}." },
            ).joinToString(" ")
        }
        Metric.Zones -> {
            val z = adv.zones
            if (z.totalMin == 0) "No workouts with heart-rate zones in the last 7 days."
            else "Last 7 days: ${z.lowMin} min low, ${z.thresholdMin} min threshold and ${z.highMin} min high across ${z.workouts} workout${if (z.workouts == 1) "" else "s"}" +
                (z.lowShare?.let { " — ${(it * 100).roundToInt()}% easy." } ?: ".") +
                " Plenty of easy time is the backbone; there's no exact split to hit."
        }
        Metric.Movement -> i.movementRange?.let { "${it.text}. ${it.reason}" } ?: "Not enough data for a suggestion yet."
        else -> {
            if (last7 == null) "Not enough recent readings yet."
            else buildString {
                append("7-day average ${sp.format(last7)}$u")
                trend?.let { append(", $it") }
                append(".")
                val good = sp.higherIsBetter?.let { hi -> prev != null && ((last7 > prev) == hi) && abs(last7 - prev) / prev > .03 }
                when (metric) {
                    Metric.Hrv -> append(if (good == true) " Recovery is trending well." else if (i.hrv.level == Level.Fair || i.hrv.level == Level.Low) " Go lighter on intensity and protect sleep for a few days." else " You're within your normal range.")
                    Metric.RestingHr -> append(if (i.rhr.level == Level.Low || i.rhr.level == Level.Fair) " It's elevated — think sleep, stress, alcohol or an oncoming bug." else " Nothing unusual.")
                    Metric.Breathing -> append(if (i.illnessFlag) " Several overnight signals are off — your body may be fighting something. Rest if you can." else " Stable, as it should be.")
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun specOf(metric: Metric) = spec(metric, Brand.health)
