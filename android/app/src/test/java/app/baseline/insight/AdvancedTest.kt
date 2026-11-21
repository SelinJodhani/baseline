package app.baseline.insight

import app.baseline.data.AppSettings
import app.baseline.data.Day
import app.baseline.data.DemoData
import app.baseline.data.HrNadir
import app.baseline.data.Sleep
import app.baseline.data.Summary
import app.baseline.data.Workout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.ln
import kotlin.math.sqrt

class AdvancedTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 3, 10) // a Tuesday
    private val now = ZonedDateTime.of(today, LocalTime.of(9, 0), zone).toInstant()

    private fun at(d: LocalDate, h: Int, m: Int = 0) = ZonedDateTime.of(d, LocalTime.of(h, m), zone).toEpochSecond()

    /** A night ending on [wake] from [bedH]:[bedM] the evening before (or after midnight when bedH < 12) to [wakeH]:[wakeM]. */
    private fun night(wake: LocalDate, bedH: Int, bedM: Int, wakeH: Int, wakeM: Int, staged: Boolean = true, awake: Pair<Int, Int>? = null): Sleep {
        val start = if (bedH >= 12) at(wake.minusDays(1), bedH, bedM) else at(wake, bedH, bedM)
        val end = at(wake, wakeH, wakeM)
        val stages = if (!staged) null else buildList<List<JsonPrimitive>> {
            if (awake == null) add(listOf(JsonPrimitive(start), JsonPrimitive(end), JsonPrimitive("light")))
            else {
                val a = start + awake.first * 60L; val b = a + awake.second * 60L
                add(listOf(JsonPrimitive(start), JsonPrimitive(a), JsonPrimitive("deep")))
                add(listOf(JsonPrimitive(a), JsonPrimitive(b), JsonPrimitive("awake")))
                add(listOf(JsonPrimitive(b), JsonPrimitive(end), JsonPrimitive("rem")))
            }
        }
        val min = ((end - start) / 60).toInt()
        return Sleep(start = start, end = end, durationMin = min, stages = stages)
    }

    private fun dates(n: Int, end: LocalDate = today) = (n - 1 downTo 0).map { end.minusDays(it.toLong()) }

    // ------------------------------------------------------------------------------------------------- SRI

    @Test fun sriPerfectlyRegularIs100() {
        val sleeps = dates(14).map { night(it, 23, 0, 7, 0) }
        val sri = SleepRegularity(sleeps, zone).sri(today)
        assertEquals(100.0, sri.value!!, 1e-9)
        assertEquals(Level.Great, sri.level)
        assertEquals(14, sri.validDays)
    }

    @Test fun sriAlternatingFourHourShift() {
        // 23:00–07:00 and 03:00–11:00 on alternate nights: 480 of 1440 minutes differ per pair → −100 + 200·960/1440.
        val sleeps = dates(14).mapIndexed { i, d -> if (i % 2 == 0) night(d, 23, 0, 7, 0) else night(d, 3, 0, 11, 0) }
        val sri = SleepRegularity(sleeps, zone).sri(today)
        assertEquals(-100 + 200.0 * 960 / 1440, sri.value!!, 1e-9)
        assertEquals(Level.Fair, sri.level)
    }

    @Test fun sriCountsAwakeStagesAsWakeAndNeedsSevenDays() {
        // Every night is identical incl. a 30-min awake spell → still 100.
        val same = dates(14).map { night(it, 23, 0, 7, 0, awake = 120 to 30) }
        assertEquals(100.0, SleepRegularity(same, zone).sri(today).value!!, 1e-9)
        // The awake spell on alternate nights only: 30 min differ per pair.
        val alt = dates(14).mapIndexed { i, d -> if (i % 2 == 0) night(d, 23, 0, 7, 0, awake = 120 to 30) else night(d, 23, 0, 7, 0) }
        assertEquals(-100 + 200.0 * 1410 / 1440, SleepRegularity(alt, zone).sri(today).value!!, 1e-9)
        // Five nights is too few; a missing night is a gap, not a "wake" day.
        assertNull(SleepRegularity(dates(5).map { night(it, 23, 0, 7, 0) }, zone).sri(today).value)
        val gappy = dates(14).filterIndexed { i, _ -> i != 6 }.map { night(it, 23, 0, 7, 0) }
        val g = SleepRegularity(gappy, zone).sri(today)
        assertEquals(13, g.validDays)
        assertEquals(11, g.pairs)
        assertEquals(100.0, g.value!!, 1e-9)
    }

    @Test fun sriFallsBackToSessionWindowWithoutStages() {
        val sleeps = dates(10).map { night(it, 23, 0, 7, 0, staged = false) }
        assertEquals(100.0, SleepRegularity(sleeps, zone).sri(today).value!!, 1e-9)
    }

    // ----------------------------------------------------------------------------------------- signals off

    private fun steadyDays(n: Int, skin: Boolean = false): MutableMap<LocalDate, Day> =
        dates(n).mapIndexed { i, d ->
            val w = if (i % 2 == 0) 1.0 else -1.0
            d to Day(date = d.toString(), sleepHrv = 60.0 + 3 * w, restingHr = 55.0 + w, respRate = 15.0 + 0.2 * w,
                zeppSkinTempScore = if (skin) 80.0 + 4 * w + (i % 5) else null)
        }.toMap().toMutableMap()

    @Test fun signalsOffNeedsTwoVotesOnTwoConsecutiveNights() {
        val days = steadyDays(40)
        for (k in 0..1) days[today.minusDays(k.toLong())] = days[today.minusDays(k.toLong())]!!.copy(restingHr = 60.0, sleepHrv = 45.0)
        val s = OvernightSignals(days).state(today) { false }
        assertTrue(s.active)
        assertEquals(2, s.nights)
        assertEquals(listOf(OvernightSignal.RestingHr, OvernightSignal.Hrv), s.signals)
        assertTrue(s.message.startsWith("Several of your overnight signals are off"))
        assertFalse(s.calibrating)
    }

    @Test fun singleSignalOrSingleNightDoesNotTrigger() {
        val oneSignal = steadyDays(40).apply {
            for (k in 0..2) this[today.minusDays(k.toLong())] = this[today.minusDays(k.toLong())]!!.copy(respRate = 17.0)
        }
        val a = OvernightSignals(oneSignal).state(today) { false }
        assertFalse(a.active)
        assertEquals(listOf(OvernightSignal.Breathing), a.signals)

        val oneNight = steadyDays(40).apply { this[today] = this[today]!!.copy(restingHr = 60.0, respRate = 16.5) }
        val b = OvernightSignals(oneNight).state(today) { false }
        assertFalse(b.active)
        assertEquals(1, b.nights)
    }

    @Test fun signalsOffNeedsFourteenNightsOfHistory() {
        val days = steadyDays(10)
        for (k in 0..1) days[today.minusDays(k.toLong())] = days[today.minusDays(k.toLong())]!!.copy(restingHr = 65.0, sleepHrv = 30.0, respRate = 18.0)
        val s = OvernightSignals(days).state(today) { false }
        assertFalse(s.active)
        assertTrue(s.calibrating)
        assertNull(s.latest)
    }

    @Test fun skinTemperatureVotesOnlyInItsBottomTenPercent() {
        val days = steadyDays(40, skin = true)
        days[today] = days[today]!!.copy(zeppSkinTempScore = 60.0, restingHr = 60.0)
        days[today.minusDays(1)] = days[today.minusDays(1)]!!.copy(zeppSkinTempScore = 60.0, restingHr = 60.0)
        val s = OvernightSignals(days).state(today) { false }
        assertTrue(s.active)
        assertTrue(OvernightSignal.SkinTemp in s.signals)
        // A flat score history can't vote every night.
        val flat = steadyDays(40).mapValues { (_, d) -> d.copy(zeppSkinTempScore = 80.0) }
        assertTrue(OvernightSignals(flat).votes.values.none { OvernightSignal.SkinTemp in it.off })
    }

    @Test fun flaggedNightsStayOutOfTheBaseline() {
        // Five sick nights: if the first ones leaked into the baseline, the later ones would stop voting.
        val days = steadyDays(45)
        for (k in 0..4) days[today.minusDays(k.toLong())] = days[today.minusDays(k.toLong())]!!.copy(restingHr = 61.0, sleepHrv = 44.0)
        val s = OvernightSignals(days).state(today) { false }
        assertEquals(5, s.nights)
    }

    @Test fun hardTrainingDayHoldsTheIllnessFlagBack() {
        val days = steadyDays(40).values.toMutableList()
        val sick = days.map { d -> if (d.localDate >= today.minusDays(1)) d.copy(restingHr = 61.0, sleepHrv = 44.0) else d }
        val workouts = dates(40).filter { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.WEDNESDAY }
            .map { Workout(id = it.toString(), start = at(it, 8), trainingLoad = 60.0) } +
            Workout(id = "big", start = at(today.minusDays(1), 8), trainingLoad = 300.0) // the day before last night
        val summary = Summary(timezone = zone.id, days = sick, workouts = workouts)
        val i = InsightEngine(summary, AppSettings(), now).compute()
        assertTrue(i.signalsOff!!.active)
        assertTrue(i.signalsOff!!.afterHardTraining)
        assertFalse(i.illnessFlag)
        assertTrue(i.signalsOff!!.message.contains("hard session"))
    }

    // ------------------------------------------------------------------------------------------------ HRV CV

    @Test fun hrvRollingMeanAndCv() {
        val hrv = listOf(50.0, 55.0, 60.0, 65.0, 70.0, 58.0, 62.0)
        val days = dates(7).mapIndexed { i, d -> Day(date = d.toString(), sleepHrv = hrv[i]) }
        val e = AdvancedEngine(Summary(timezone = zone.id, days = days), AppSettings(), zone, today, emptyMap())
        val (mean, cv) = e.hrvRolling(today)!!
        val lns = hrv.map { ln(it) }
        val m = lns.average()
        val sd = sqrt(lns.sumOf { (it - m) * (it - m) } / (lns.size - 1))
        assertEquals(m, mean, 1e-12)
        assertEquals(sd / m * 100, cv, 1e-9)
        // Fewer than 3 nights in the week: no trend.
        val sparse = days.filterIndexed { i, _ -> i == 0 || i == 6 }
        assertNull(AdvancedEngine(Summary(timezone = zone.id, days = sparse), AppSettings(), zone, today, emptyMap()).hrvRolling(today))
    }

    @Test fun risingCvWithFallingMeanIsAFatigueNote() {
        val days = (69 downTo 0).map { k ->
            val d = today.minusDays(k.toLong())
            val v = when {
                k >= 7 -> 60.0 + if (k % 2 == 0) 1.5 else -1.5 // calm weeks: small swings
                else -> listOf(40.0, 58.0, 38.0, 55.0, 36.0, 52.0, 40.0)[k] // last week: lower and swingy
            }
            Day(date = d.toString(), sleepHrv = v)
        }
        val t = AdvancedEngine(Summary(timezone = zone.id, days = days), AppSettings(), zone, today, emptyMap()).hrvTrend
        assertTrue(t.cvRising)
        assertTrue(t.meanFalling)
        assertTrue(t.fatigueNote)
        assertEquals(Level.Fair, t.level)
    }

    // ------------------------------------------------------------------------------ social jet lag & sleep need

    private fun engineFor(sleeps: List<Sleep>, settings: AppSettings = AppSettings()): AdvancedEngine {
        val s = Summary(timezone = zone.id, sleeps = sleeps)
        return InsightEngine(s, settings, now).advanced
    }

    @Test fun socialJetLagIsFreeMinusWorkMidpoint() {
        // Work nights 23:00–07:00 (midpoint 3:00), free nights 00:30–08:30 (midpoint 4:30) for four weeks.
        val sleeps = dates(28).map { d -> if (isFreeWake(d)) night(d, 0, 30, 8, 30) else night(d, 23, 0, 7, 0) }
        val t = engineFor(sleeps).sleepTiming
        assertEquals(90.0, t.socialJetLagMin!!, 0.5)
        assertEquals(8, t.freeNights)
        assertEquals(20, t.workNights)
        assertEquals(3 * 60.0, t.workMidMin!!, 0.5)
        // Same durations, so the MCTQ chronotype equals the free-day midpoint.
        assertEquals(4 * 60 + 30.0, t.chronotypeMin!!, 0.5)
        // Too few free days → no number.
        assertNull(engineFor(sleeps.takeLast(8)).sleepTiming.socialJetLagMin)
    }

    @Test fun midpointHandlesMidnight() {
        assertEquals(-60.0, clockDiff(23 * 60.0, 0.0), 1e-9)
        assertEquals(90.0, clockDiff(30.0, 23 * 60.0), 1e-9)
    }

    @Test fun sleepNeedFromFreeDaysWithNaps() {
        // Work nights 7 h, free nights 8 h, plus a 30-min Sunday nap → free-day total median 8h 15m.
        val mains = dates(14).map { d -> if (isFreeWake(d)) night(d, 23, 0, 7, 0) else night(d, 0, 0, 7, 0) }
        val naps = dates(14).filter { it.dayOfWeek == DayOfWeek.SUNDAY }.map { Sleep(start = at(it, 15), end = at(it, 15, 30), durationMin = 30) }
        val n = engineFor(mains + naps, AppSettings(sleepNeedMin = 450)).sleepNeed
        assertTrue(n.estimated)
        assertEquals(4, n.freeNights)
        assertTrue(n.includesNaps)
        assertEquals(495, n.needMin) // median of 480, 510, 480, 510
        // Without free nights the setting is used.
        val weekdaysOnly = engineFor(mains.filter { !isFreeWake(wakeOf(it)) }, AppSettings(sleepNeedMin = 450)).sleepNeed
        assertFalse(weekdaysOnly.estimated)
        assertEquals(450, weekdaysOnly.needMin)
    }

    private fun wakeOf(s: Sleep) = java.time.Instant.ofEpochSecond(s.end).atZone(zone).toLocalDate()

    @Test fun debtTrendEasesWhenNightsGetLonger() {
        val sleeps = dates(14).mapIndexed { i, d -> if (i < 7) night(d, 1, 0, 7, 0) else night(d, 23, 0, 7, 0) } // 6 h then 8 h
        val n = engineFor(sleeps, AppSettings(sleepNeedMin = 480)).sleepNeed
        assertEquals(0, n.debt7Min)
        assertEquals(DebtTrend.Easing, n.trend)
    }

    // ----------------------------------------------------------------------------------------- HR dip, zones

    @Test fun hrDipUsesBridgeNadirAndClassifiesTiming() {
        val sl = night(today, 23, 0, 7, 0).copy(hrNadir = HrNadir(48.0, at(today, 1, 0)))
        val dip = engineFor(listOf(sl)).hrDip(today, sl)!!
        assertEquals(48.0, dip.bpm, 0.0)
        assertEquals(0.25, dip.fraction, 1e-9)
        assertEquals(DipTiming.Early, dip.timing)
        assertFalse(dip.approximate)
        val late = sl.copy(hrNadir = HrNadir(50.0, at(today, 6, 30)))
        assertEquals(DipTiming.Late, engineFor(listOf(late)).hrDip(today, late)!!.timing)
    }

    @Test fun hrDipFallsBackToHourlyMinimum() {
        val sl = night(today, 23, 0, 7, 0)
        val rows = listOf(
            listOf(at(today, 2).toDouble(), 52.0, 56.0, 60.0),
            listOf(at(today, 5).toDouble(), 49.0, 54.0, 58.0),
            listOf(at(today, 9).toDouble(), 40.0, 70.0, 90.0), // after waking: ignored
        )
        val s = Summary(timezone = zone.id, sleeps = listOf(sl), hourly = app.baseline.data.Hourly(heartRate = rows))
        val dip = InsightEngine(s, AppSettings(), now).advanced.hrDip(today, sl)!!
        assertEquals(49.0, dip.bpm, 0.0)
        assertTrue(dip.approximate)
        assertEquals(at(today, 5, 30), dip.at)
    }

    @Test fun zonesSplitLowThresholdHigh() {
        assertEquals(Triple(300, 300, 900), zoneBuckets(listOf(100, 200, 300, 400, 500)))
        assertNull(zoneBuckets(listOf(60, 900)))
        val w = listOf(
            Workout(id = "a", start = at(today, 8), zoneSeconds = listOf(600, 1200, 600, 300, 0)),
            Workout(id = "b", start = at(today.minusDays(3), 8), zoneSeconds = listOf(0, 1800, 0, 0, 0)),
            Workout(id = "old", start = at(today.minusDays(9), 8), zoneSeconds = listOf(0, 0, 0, 6000, 0)),
        )
        val z = InsightEngine(Summary(timezone = zone.id, workouts = w), AppSettings(), now).advanced.zoneSplit()
        assertEquals(60, z.lowMin)
        assertEquals(10, z.thresholdMin)
        assertEquals(5, z.highMin)
        assertEquals(2, z.workouts)
    }

    // ------------------------------------------------------------------------------- movement, VO₂max, steps

    @Test fun movementRangeFollowsReadinessLoadAndSignals() {
        assertEquals("30–60 min moderate today", movementRange(Level.Good, false, 1.0, 60, 150).text)
        assertEquals("45–75 min harder today", movementRange(Level.Great, false, 1.0, 60, 150).text)
        assertEquals("20–40 min easy today", movementRange(Level.Fair, false, null, 0, 150).text)
        // Load well above usual knocks it down a notch.
        assertEquals("30–60 min moderate today", movementRange(Level.Great, false, 1.4, 60, 150).text)
        assertEquals(Intensity.Gentle, movementRange(Level.Great, true, 1.0, 0, 150).intensity)
        assertTrue(movementRange(Level.Good, false, 1.0, 200, 150).reason.contains("already met"))
    }

    @Test fun vo2PercentileBand() {
        // Man aged 35 at 42.4 (the P50): 38.9 → ~36th, 45.9 → ~63rd → widened to 35th–65th.
        val b = vo2Band(42.4, 35, "male")!!
        assertEquals(35, b.lowPct)
        assertEquals(65, b.highPct)
        assertEquals("roughly 35th–65th percentile for men in their 30s", b.text)
        assertNull(vo2Band(42.4, 35, ""))
        assertNull(vo2Band(42.4, null, "female"))
        assertTrue(vo2Band(70.0, 25, "female")!!.text.startsWith("above the 95th"))
    }

    @Test fun stepGoalByAge() {
        assertEquals(8000..10000, stepBand(40))
        assertEquals(6000..8000, stepBand(65))
        assertNull(stepBand(null))
        assertEquals(36, ageOf(AppSettings(birthYear = 1990), today))
        assertNull(ageOf(AppSettings(), today))
    }

    // --------------------------------------------------------------------------------------------- demo data

    @Test fun demoDataExercisesPhase3() {
        val demoToday = LocalDate.now(zone)
        val e = InsightEngine(DemoData.generate(demoToday, zone), AppSettings(birthYear = 1990, sex = "male"))
        val i = e.compute()
        val votes = e.advanced.overnight.votes
        // The illness episode 12–16 days ago reads as "signals off" for at least two nights running.
        val episode = (11..17).map { demoToday.minusDays(it.toLong()) }
        assertTrue("votes ${episode.map { votes[it]?.off }}",
            episode.zipWithNext().any { (a, b) -> (votes[a]?.votes ?: 0) >= 2 && (votes[b]?.votes ?: 0) >= 2 })
        assertFalse(i.illnessFlag) // recovered since
        val adv = i.advanced!!
        assertNotNull(adv.sri.value)
        assertTrue(adv.need.estimated)
        assertTrue(adv.timing.socialJetLagMin!! > 15)
        assertNotNull(adv.hrDip)
        assertNotNull(adv.hrvTrend.mean7Ms)
        assertTrue(adv.zones.totalMin > 0)
        assertNotNull(adv.vo2Band)
        assertNotNull(i.movementRange)
    }
}
