package app.baseline.data

import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic, deterministic data for demo mode and screenshots — a fictional person, never real readings.
 * ~7 months: a steady base, a training block, a short illness (HRV down, RHR + breathing rate up) two
 * weeks ago, and a clean recovery since.
 */
object DemoData {
    private const val DAYS = 210

    fun generate(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Summary {
        val r = Random(7)
        val days = mutableListOf<Day>()
        val sleeps = mutableListOf<Sleep>()
        val workouts = mutableListOf<Workout>()
        val weights = mutableListOf<Weight>()
        var vo2 = 43.5
        var weight = 78.6
        var paiWindow = ArrayDeque<Double>()

        for (i in DAYS - 1 downTo 0) {
            val date = today.minusDays(i.toLong())
            val dow = date.dayOfWeek.value // 1 = Mon
            val weekend = dow >= 6
            // Illness 12–16 days ago, peaking at 14.
            val ill = when (i) { in 12..16 -> 1.0 - kotlin.math.abs(i - 14) * 0.28; else -> 0.0 }.coerceAtLeast(0.0)
            val fitness = (DAYS - i) / DAYS.toDouble() // slowly improving
            val trainingDay = !ill.isPositive() && (dow == 2 || dow == 4 || dow == 6 || (dow == 7 && r.nextDouble() < .5))

            // --- Sleep (ends on `date` morning)
            val bedMinutes = (if (weekend) 24 * 60 + 5 else 23 * 60 + 25) + r.gauss(0.0, 28.0) + ill * 20
            val asleepTarget = (if (weekend) 455.0 else 418.0) + r.gauss(0.0, 26.0) + ill * 40 - (if (dow == 4) 35 else 0)
            val bed = ZonedDateTime.of(date.minusDays(1), LocalTime.MIDNIGHT, zone).plusMinutes(bedMinutes.roundToInt().toLong())
            val sleep = buildSleep(r, bed.toEpochSecond(), asleepTarget.roundToInt(), ill, keepStages = i < 50)
            sleeps += sleep

            // --- Recovery signals
            val hrvLn = ln(58.0) + 0.10 * fitness + 0.06 * sin(i * 2 * PI / 7) - 0.38 * ill +
                (if (sleep.asleepMin < 400) -0.07 else 0.03) + r.gauss(0.0, 0.07)
            val hrv = exp(hrvLn)
            val rhr = 57.0 - 2.2 * fitness + 6.5 * ill + (if (sleep.asleepMin < 400) 1.2 else 0.0) + r.gauss(0.0, 1.1)
            val resp = 15.2 + 2.1 * ill + r.gauss(0.0, 0.25)

            // --- Activity
            var steps = (if (weekend) 9800.0 else 7200.0) + r.gauss(0.0, 2300.0) - ill * 5200
            var moderate = max(0.0, r.gauss(14.0, 8.0) - ill * 12)
            var vigorous = 0.0
            var load = 0.0
            if (trainingDay) {
                val type = when (dow) { 2 -> "running"; 4 -> "strength_training"; 6 -> "running"; else -> "cycling" }
                val dur = when (type) { "strength_training" -> 52.0; "cycling" -> 75.0; else -> if (dow == 6) 62.0 else 38.0 } + r.gauss(0.0, 6.0)
                val startT = ZonedDateTime.of(date, LocalTime.of(if (weekend) 8 else 7, 10), zone).plusMinutes(r.nextInt(0, 40).toLong())
                val avgHr = when (type) { "strength_training" -> 118.0; "cycling" -> 136.0; else -> 148.0 } + r.gauss(0.0, 4.0)
                load = (dur * (avgHr - 60) / 60.0 * 1.25).coerceAtLeast(10.0)
                val dist = when (type) { "running" -> dur * 175 + r.gauss(0.0, 300.0); "cycling" -> dur * 410; else -> null }
                val zones = when (type) {
                    "strength_training" -> listOf(900, 1500, 600, 120, 0)
                    "cycling" -> listOf(600, 1700, 1800, 300, 60)
                    else -> if (dow == 6) listOf(240, 1900, 1400, 120, 0) else listOf(120, 300, 900, 840, 120)
                }.map { (it * dur / 60.0).roundToInt() } // shapes are per 60 min
                workouts += Workout(
                    id = "demo-${date}", type = type, start = startT.toEpochSecond(),
                    end = startT.plusMinutes(dur.roundToInt().toLong()).toEpochSecond(),
                    distanceM = dist?.let { it.roundToInt().toDouble() }, calories = (dur * (avgHr - 40) / 10).roundToInt().toDouble(),
                    avgHr = avgHr.roundToInt().toDouble(), maxHr = (avgHr + 22 + r.gauss(0.0, 4.0)).roundToInt().toDouble(),
                    trainingLoad = load.roundToInt().toDouble(), aerobicTe = (2.4 + load / 90).coerceAtMost(4.6).round1(),
                    anaerobicTe = if (dow == 2) 2.1 else 0.6, vo2max = if (type == "running") vo2.round1() else null,
                    steps = if (type == "running") dur * 165 else null, elevationGainM = if (type == "cycling") 240.0 else null,
                    zoneSeconds = zones,
                )
                steps += if (type == "running") dur * 165 else 1200.0
                if (type == "strength_training") moderate += dur * 0.55 else { moderate += dur * 0.6; vigorous += dur * 0.3 }
                vo2 += 0.035
            }
            steps = steps.coerceAtLeast(900.0)
            if (i == 0) { // today is still in progress
                val frac = (java.time.LocalTime.now(zone).toSecondOfDay() / 86400.0 * 1.25).coerceIn(0.05, 1.0)
                steps *= frac; moderate *= frac
            }
            val pai = (moderate * 0.09 + vigorous * 0.32).coerceAtMost(40.0)
            paiWindow.addLast(pai); if (paiWindow.size > 7) paiWindow.removeFirst()
            val stress = (30 + 9 * (if (weekend) -1 else 1) + 18 * ill + r.gauss(0.0, 6.0)).coerceIn(8.0, 80.0)
            val relaxed = (62 - stress * 0.7 + r.gauss(0.0, 4.0)).coerceIn(5.0, 90.0)
            val zr = (78 + (hrvLn - ln(60.0)) * 70 - (rhr - 56) * 2.2 + (sleep.asleepMin - 420) / 9.0).coerceIn(20.0, 99.0)
            if (i % 3 == 0 || i < 4) {
                weight += -0.018 + r.gauss(0.0, 0.03)
                weights += Weight(ZonedDateTime.of(date, LocalTime.of(7, 5), zone).toEpochSecond(), (weight + r.gauss(0.0, 0.35)).round2())
            }

            days += Day(
                date = date.toString(),
                steps = steps.roundToInt().toDouble(), stepGoal = 8000.0,
                distanceM = (steps * 0.74).roundToInt().toDouble(),
                calories = (steps * 0.042 + load * 3).roundToInt().toDouble(), activeCalories = (steps * 0.04 + load * 3).roundToInt().toDouble(),
                activeMinutes = (moderate + vigorous).roundToInt().toDouble(), activeMinutesGoal = 30.0,
                restingHr = rhr.roundToInt().toDouble(), sleepHrv = hrv.roundToInt().toDouble(), sleepRhr = (rhr + 1).roundToInt().toDouble(),
                maxHr = 190.0, respRate = resp.round1(),
                spo2NightScore = (97 - ill * 6 + r.gauss(0.0, 1.5)).coerceIn(80.0, 100.0).roundToInt().toDouble(),
                spo2Odi = (0.6 + ill * 2 + r.nextDouble() * 0.8).round1(),
                stress = stress.roundToInt().toDouble(), stressMin = 4.0, stressMax = (stress + 30).coerceAtMost(98.0).roundToInt().toDouble(),
                stressRelaxedPct = relaxed.roundToInt().toDouble(), stressNormalPct = (100 - relaxed - 12).coerceAtLeast(0.0).roundToInt().toDouble(),
                stressMediumPct = 9.0, stressHighPct = 3.0,
                pai = pai.round1(), paiWeek = paiWindow.sum().round1() + 38,
                lowZoneMin = (moderate * 1.8).roundToInt().toDouble(), moderateZoneMin = moderate.roundToInt().toDouble(), vigorousZoneMin = vigorous.roundToInt().toDouble(),
                zeppReadiness = zr.roundToInt().toDouble(), charge = (zr - 6 + r.gauss(0.0, 5.0)).coerceIn(5.0, 100.0).roundToInt().toDouble(),
                trainingLoad = load.roundToInt().toDouble(), vo2max = vo2.round1(),
            )
        }

        val hourly = buildHourly(r, today, zone, days.takeLast(8), workouts)
        val nowSec = System.currentTimeMillis() / 1000
        return Summary(
            bridgeVersion = "demo", generatedAt = nowSec, timezone = zone.id,
            freshness = Freshness(lastReadingAt = nowSec - 23 * 60, libraryModifiedAt = nowSec - 9 * 60),
            profile = Profile(maxHr = 190.0), days = days, sleeps = sleeps, hourly = hourly,
            workouts = workouts, weights = weights,
        )
    }

    private fun buildSleep(r: Random, bed: Long, asleepTarget: Int, ill: Double, keepStages: Boolean): Sleep {
        val stages = mutableListOf<List<JsonPrimitive>>()
        var t = bed
        var asleep = 0
        var deep = 0; var light = 0; var rem = 0; var awake = 0; var wakes = 0
        var cycle = 0
        fun add(stage: String, minutes: Int) {
            val m = max(1, minutes)
            stages += listOf(JsonPrimitive(t), JsonPrimitive(t + m * 60), JsonPrimitive(stage))
            t += m * 60
            when (stage) { "deep" -> deep += m; "light" -> light += m; "rem" -> rem += m; else -> awake += m }
            if (stage != "awake") asleep += m
        }
        while (asleep < asleepTarget) {
            val early = cycle < 2
            add("light", (if (early) 18 else 28) + r.nextInt(0, 12))
            add("deep", ((if (early) 38 else 12) * (1 - ill * .4)).roundToInt() + r.nextInt(0, 10))
            add("light", 12 + r.nextInt(0, 10))
            add("rem", (if (early) 12 else 26) + r.nextInt(0, 12))
            if (r.nextDouble() < 0.35 + ill * .4) { add("awake", 2 + r.nextInt(0, 7)); wakes++ }
            cycle++
        }
        return Sleep(
            start = bed, end = t, durationMin = asleep, deepMin = deep, lightMin = light, remMin = rem,
            awakeMin = awake, wakeCount = wakes, score = (60 + (asleep - 380) / 5 - awake / 3).coerceIn(30, 98),
            stages = if (keepStages) stages else null,
        )
    }

    private fun buildHourly(r: Random, today: LocalDate, zone: ZoneId, days: List<Day>, workouts: List<Workout>): Hourly {
        val hr = mutableListOf<List<Double>>(); val stress = mutableListOf<List<Double>>(); val spo2 = mutableListOf<List<Double>>()
        val now = ZonedDateTime.now(zone)
        for (d in days) {
            val date = LocalDate.parse(d.date)
            for (h in 0..23) {
                val t = ZonedDateTime.of(date, LocalTime.of(h, 0), zone)
                if (t.isAfter(now)) break
                val sleeping = h < 7 || h >= 24
                val rhr = d.restingHr ?: 56.0
                val w = workouts.firstOrNull { it.start in t.toEpochSecond() until t.toEpochSecond() + 3600 }
                val avg = when {
                    w != null -> (w.avgHr ?: 140.0) - 8
                    sleeping -> rhr + 2 + r.gauss(0.0, 1.5)
                    else -> rhr + 18 + 8 * sin((h - 7) / 16.0 * PI) + r.gauss(0.0, 4.0)
                }
                val s = t.toEpochSecond().toDouble()
                hr += listOf(s, (avg - 9 - r.nextDouble() * 4).roundToInt().toDouble(), avg.round1(), (avg + 14 + (w?.let { 25 } ?: 0) + r.nextDouble() * 6).roundToInt().toDouble())
                if (!sleeping) {
                    val sv = ((d.stress ?: 30.0) + 14 * sin((h - 8) / 12.0 * PI) + r.gauss(0.0, 7.0)).coerceIn(3.0, 95.0)
                    stress += listOf(s, (sv - 14).coerceAtLeast(1.0).roundToInt().toDouble(), sv.round1(), min(99.0, sv + 18).roundToInt().toDouble())
                } else {
                    spo2 += listOf(s, 94.0, (97.2 + r.gauss(0.0, .6)).coerceAtMost(99.0).round1(), 99.0)
                }
            }
        }
        return Hourly(heartRate = hr, stress = stress, spo2 = spo2)
    }

    private fun Random.gauss(mean: Double, sd: Double): Double {
        // Box–Muller
        val u = nextDouble().coerceAtLeast(1e-9); val v = nextDouble()
        return mean + sd * kotlin.math.sqrt(-2 * ln(u)) * kotlin.math.cos(2 * PI * v)
    }

    private fun Double.isPositive() = this > 0.0
    private fun Double.round1() = (this * 10).roundToInt() / 10.0
    private fun Double.round2() = (this * 100).roundToInt() / 100.0
}
