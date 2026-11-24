package app.baseline.insight

import app.baseline.data.Day
import app.baseline.data.Sleep
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Coachable Health Age (CHA), from Google Research, "Personalized health coaching via coachable health age and
 * counterfactual wearable trajectories" (arXiv 2611.08568, 2026). A fixed, published formula — not a fitted model:
 * each modifiable habit is turned into a hazard ratio from published dose–response curves, the four core
 * log-hazards are weighted to correct for their overlap, and the sum is converted to years with an age-rate
 * constant (≈ 10 % more mortality risk per year of age):
 *
 *     gap (years) = Σ wᵢ · ln HRᵢ / 0.1        health age = max(18, age + gap)
 *
 * Inputs are 90-day means, accepted only when the window has ≥ 5 valid ISO weeks (a valid week has ≥ 2 days with
 * steps, main sleep and heart rate). Validated by its authors on Fitbit cohorts only; Baseline uses Amazfit data,
 * with Active Zone Minutes computed by the bridge from minute heart rate the way Fitbit defines them.
 */
object Cha {
    const val AGE_RATE = 0.1
    const val WINDOW_DAYS = 91
    const val MIN_VALID_WEEKS = 5
    const val MIN_DAYS_PER_WEEK = 2
    const val HEALTH_AGE_FLOOR = 18.0

    enum class Lever(val label: String, val unit: String) {
        Sleep("Sleep", "h a night"),
        RestingHr("Resting heart rate", "bpm"),
        ActiveZone("Active zone minutes", "min a week"),
        Steps("Steps", "a day"),
        Regularity("Sleep timing", "min spread"),
        Fitness("Cardio fitness", "VO₂max"),
    }

    /** Multicollinearity-adjusted weights for the four core domains (paper, Eqs. 7–9). Optional terms weigh 1. */
    val WEIGHTS = mapOf(Lever.Sleep to 0.8790, Lever.RestingHr to 0.7066, Lever.ActiveZone to 0.6950, Lever.Steps to 0.4773)

    // ---- per-domain log hazard ratios (paper Eqs. 3–6 and the optional terms)

    fun lnHrRestingHr(bpm: Double): Double = when {
        bpm <= 60 -> 0.0
        bpm <= 70 -> (bpm - 60) / 10 * ln(1.08)
        else -> ln(1.08) + (bpm - 70) / 20 * (ln(1.33) - ln(1.08))
    }

    /** 0.88 per 1,000 steps around 8,000; no extra benefit above 9,000. */
    fun lnHrSteps(steps: Double): Double = (min(steps, 9000.0) - 8000) / 1000 * ln(0.88)

    /** U-shaped: no effect between 6 and 9 hours. */
    fun lnHrSleep(hours: Double): Double = when {
        hours < 6 -> (6 - hours) * ln(1.20)
        hours > 9 -> (hours - 9) * ln(1.34)
        else -> 0.0
    }

    /** Weekly Active Zone Minutes: no effect under 115, benefit levels off at 500. */
    fun lnHrAzm(perWeek: Double): Double = when {
        perWeek < 115 -> 0.0
        perWeek < 245 -> (perWeek - 115) / 130 * ln(0.76)
        perWeek < 418 -> ln(0.76) + (perWeek - 245) / 173 * (ln(0.71) - ln(0.76))
        perWeek < 500 -> ln(0.71) + (perWeek - 418) / 82 * (ln(0.68) - ln(0.71))
        else -> ln(0.68)
    }

    /** Standard deviation of the sleep midpoint (minutes, capped at 120): 1.16 at 60, 1.52 at 90, 2.11 at 120. */
    fun lnHrRegularity(sdMin: Double): Double {
        val x = sdMin.coerceAtMost(120.0)
        val pts = listOf(30.0 to 0.0, 60.0 to ln(1.16), 90.0 to ln(1.52), 120.0 to ln(2.11))
        if (x <= 30) return 0.0
        val (a, b) = pts.zipWithNext().first { (_, hi) -> x <= hi.first }
        return a.second + (x - a.first) / (b.first - a.first) * (b.second - a.second)
    }

    /** VO₂max against the FRIEND-style expected value for age, sex and weight; 0.88 per MET (3.5 ml/kg/min). */
    fun lnHrFitness(vo2: Double, age: Int, female: Boolean, weightKg: Double): Double {
        val expected = 79.9 - 0.39 * age - 13.7 * (if (female) 1 else 0) - 0.127 * weightKg * 2.20462
        return (vo2 - expected) / 3.5 * ln(0.88)
    }

    data class Inputs(
        val steps: Double?,
        val sleepHours: Double?,
        val restingHr: Double?,
        val azmPerWeek: Double?,
        val midpointSdMin: Double? = null,
        val vo2max: Double? = null,
    )

    data class Part(val lever: Lever, val value: Double, val years: Double)

    data class Result(
        val gapYears: Double,
        val parts: List<Part>,
        /** Core domains that had no data (counted as 0 years). */
        val missing: List<Lever>,
        val inputs: Inputs,
        val validWeeks: Int,
        val days: Int,
    ) {
        fun healthAge(age: Int?): Double? = age?.let { max(HEALTH_AGE_FLOOR, it + gapYears) }
        fun yearsFor(l: Lever): Double = parts.firstOrNull { it.lever == l }?.years ?: 0.0
    }

    /** The formula itself, for any inputs (also used for what-ifs). */
    fun score(i: Inputs, age: Int? = null, female: Boolean? = null, weightKg: Double? = null): Pair<List<Part>, List<Lever>> {
        val parts = mutableListOf<Part>()
        val missing = mutableListOf<Lever>()
        fun core(l: Lever, v: Double?, f: (Double) -> Double) {
            if (v == null) { missing += l; return }
            parts += Part(l, v, WEIGHTS.getValue(l) * f(v) / AGE_RATE)
        }
        core(Lever.Sleep, i.sleepHours, ::lnHrSleep)
        core(Lever.RestingHr, i.restingHr, ::lnHrRestingHr)
        core(Lever.ActiveZone, i.azmPerWeek, ::lnHrAzm)
        core(Lever.Steps, i.steps, ::lnHrSteps)
        i.midpointSdMin?.let { parts += Part(Lever.Regularity, it, lnHrRegularity(it) / AGE_RATE) }
        if (i.vo2max != null && age != null && female != null && weightKg != null) {
            parts += Part(Lever.Fitness, i.vo2max, lnHrFitness(i.vo2max, age, female, weightKg) / AGE_RATE)
        }
        return parts to missing
    }

    /**
     * CHA for the 91 days ending [end]. Null when the window doesn't have [MIN_VALID_WEEKS] valid weeks.
     * Resting HR is the bridge's daily minimum heart rate, falling back to Zepp's resting HR on older bridges.
     */
    fun compute(
        days: Map<LocalDate, Day>, mainSleeps: Map<LocalDate, Sleep>, zone: ZoneId, end: LocalDate,
        age: Int? = null, female: Boolean? = null, weightKg: Double? = null, vo2max: Double? = null,
    ): Result? {
        val window = (WINDOW_DAYS - 1 downTo 0).map { end.minusDays(it.toLong()) }
        fun rhr(d: Day?) = d?.minHr ?: d?.restingHr ?: d?.sleepRhr
        val complete = window.filter { d ->
            val day = days[d]
            (day?.steps ?: 0.0) >= 100 && mainSleeps[d]?.let { it.asleepMin in 120..960 } == true && rhr(day) != null
        }
        val weeks = complete.groupBy { it.get(IsoFields.WEEK_BASED_YEAR) * 100 + it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) }
            .filterValues { it.size >= MIN_DAYS_PER_WEEK }
        if (weeks.size < MIN_VALID_WEEKS) return null
        val used = weeks.values.flatten()
        fun mean(f: (LocalDate) -> Double?) = used.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
        val azmDays = used.mapNotNull { days[it]?.azm }
        val inputs = Inputs(
            steps = mean { days[it]?.steps },
            sleepHours = mean { mainSleeps[it]?.asleepMin?.div(60.0) },
            restingHr = mean { rhr(days[it]) },
            // Only trust AZM when the bridge sent it for most of the window (bridge 0.4+).
            azmPerWeek = if (azmDays.size >= used.size / 2) azmDays.average() * 7 else null,
            midpointSdMin = midpointSd(mainSleeps, zone, end),
            vo2max = vo2max,
        )
        val (parts, missing) = score(inputs, age, female, weightKg)
        return Result(parts.sumOf { it.years }, parts, missing, inputs, weeks.size, used.size)
    }

    /** Circular SD of main-sleep midpoints over the last 28 nights (≥ 7 nights), in minutes. */
    fun midpointSd(mainSleeps: Map<LocalDate, Sleep>, zone: ZoneId, end: LocalDate): Double? {
        val mids = (0 until 28).mapNotNull { mainSleeps[end.minusDays(it.toLong())] }.map { s ->
            val mid = Instant.ofEpochSecond((s.start + s.end) / 2).atZone(zone)
            (mid.hour * 60 + mid.minute).toDouble()
        }
        if (mids.size < 7) return null
        return Stats.clockSdMinutes(mids)
    }

    /** One change worth making, and what it would do to the gap. */
    data class WhatIf(val lever: Lever, val text: String, val yearsGained: Double)

    /**
     * Exact what-ifs on the formula (Baseline's honest stand-in for the paper's unreleased Sensor World Model):
     * the next realistic step for each habit, ranked by how many years it would take off.
     */
    fun whatIfs(r: Result, age: Int? = null, female: Boolean? = null, weightKg: Double? = null): List<WhatIf> {
        val i = r.inputs
        fun gain(changed: Inputs) = r.gapYears - score(changed, age, female, weightKg).first.sumOf { it.years }
        val out = mutableListOf<WhatIf>()
        i.steps?.let { s ->
            if (s < 9000) {
                val target = min(9000.0, ((s / 1000).toInt() + 2) * 1000.0)
                out += WhatIf(Lever.Steps, "Average ${"%,d".format(target.roundToInt())} steps a day", gain(i.copy(steps = target)))
            }
        }
        i.azmPerWeek?.let { a ->
            val target = listOf(150.0, 245.0, 418.0, 500.0).firstOrNull { it > a + 10 }
            if (target != null) out += WhatIf(Lever.ActiveZone, "Reach ${target.roundToInt()} active zone minutes a week", gain(i.copy(azmPerWeek = target)))
        }
        i.sleepHours?.let { h ->
            if (h < 6) out += WhatIf(Lever.Sleep, "Sleep 7 hours a night", gain(i.copy(sleepHours = 7.0)))
            if (h > 9) out += WhatIf(Lever.Sleep, "Sleep 8 hours a night", gain(i.copy(sleepHours = 8.0)))
        }
        i.restingHr?.let { b ->
            if (b > 60) out += WhatIf(Lever.RestingHr, "Bring resting heart rate to 60 bpm", gain(i.copy(restingHr = 60.0)))
        }
        i.midpointSdMin?.let { sd ->
            if (sd > 30) out += WhatIf(Lever.Regularity, "Keep your sleep midpoint within ±30 min", gain(i.copy(midpointSdMin = 30.0)))
        }
        return out.filter { it.yearsGained >= 0.05 }.sortedByDescending { it.yearsGained }
    }

    /**
     * Weekly CHA history (each point its own 91-day window), oldest first. Null where the window wasn't valid or a
     * core habit had no data — counting a missing habit as zero would show a jump that never happened.
     */
    fun history(
        days: Map<LocalDate, Day>, mainSleeps: Map<LocalDate, Sleep>, zone: ZoneId, today: LocalDate, weeks: Int = 26,
    ): List<Pair<LocalDate, Double?>> = (weeks - 1 downTo 0).map { w ->
        val end = today.minusWeeks(w.toLong())
        end to compute(days, mainSleeps, zone, end)?.takeIf { it.missing.isEmpty() }?.gapYears
    }
}
