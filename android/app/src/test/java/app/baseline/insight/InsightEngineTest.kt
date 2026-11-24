package app.baseline.insight

import app.baseline.data.AppSettings
import app.baseline.data.Day
import app.baseline.data.DemoData
import app.baseline.data.Feel
import app.baseline.data.Journal
import app.baseline.data.LifeMode
import app.baseline.data.ModeSpan
import app.baseline.data.Sleep
import app.baseline.data.Summary
import app.baseline.data.showNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class InsightEngineTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 3, 10)
    private val now = ZonedDateTime.of(today, LocalTime.of(9, 0), zone).toInstant()
    private val settings = AppSettings(sleepNeedMin = 480)

    /** [n] steady days ending today; [tweakToday] edits the last [nights] records; [tweakPast] edits older day i (days ago). */
    private fun summary(n: Int, sleepMin: Int = 480, nights: Int = 1, tweakPast: (Int, Day) -> Day = { _, d -> d }, tweakToday: (Day) -> Day = { it }): Summary {
        val days = (n - 1 downTo 0).map { i ->
            val d = today.minusDays(i.toLong())
            val wobble = if (i % 2 == 0) 1.0 else -1.0
            Day(date = d.toString(), sleepHrv = 60.0 + 3 * wobble, restingHr = 55.0 + wobble, respRate = 15.0 + 0.2 * wobble, steps = 8000.0)
                .let { if (i < nights) tweakToday(it) else tweakPast(i, it) }
        }
        val sleeps = (n - 1 downTo 0).map { i ->
            val end = ZonedDateTime.of(today.minusDays(i.toLong()), LocalTime.of(7, 0), zone).toEpochSecond()
            Sleep(start = end - sleepMin * 60L, end = end, durationMin = sleepMin, deepMin = 90, lightMin = sleepMin - 190, remMin = 100, awakeMin = 0)
        }
        return Summary(timezone = zone.id, days = days, sleeps = sleeps)
    }

    private fun engine(s: Summary, journal: Journal = Journal(), at: java.time.Instant = now, st: AppSettings = settings) =
        InsightEngine(s, st, at, journal)

    private val pushDay: (Day) -> Day = { it.copy(sleepHrv = 75.0, restingHr = 52.0) }

    @Test fun calibratingUntil14Nights() {
        val i = InsightEngine(summary(10), settings, now).compute()
        assertNull(i.readiness.score)
        assertNotNull(i.readiness.calibratingNights)
        assertEquals(Focus.Kind.Calibrate, i.focus.kind)
        assertNull(i.readiness.verb)
        assertEquals("Calibrating", i.readiness.headline)
        assertEquals(Confidence.Calibrating, i.readiness.confidence)
        assertEquals(Confidence.Calibrating, i.hrv.confidence)
    }

    @Test fun normalDayScoresAroundSeventy() {
        val i = InsightEngine(summary(40), settings, now).compute()
        val score = i.readiness.score!!
        assertTrue("score $score", score in 55..85)
        assertFalse(i.illnessFlag)
        assertEquals(Verb.Ready, i.readiness.verb)
        assertEquals("Ready", i.readiness.headline)
        assertEquals(Confidence.High, i.readiness.confidence)
    }

    @Test fun verbMapsFromLevel() {
        assertEquals(Verb.Push, Verb.of(Level.Great))
        assertEquals(Verb.Ready, Verb.of(Level.Good))
        assertEquals(Verb.Easy, Verb.of(Level.Fair))
        assertEquals(Verb.Recover, Verb.of(Level.Low))
        assertNull(Verb.of(Level.Unknown))

        val push = engine(summary(40, tweakToday = pushDay)).compute().readiness
        assertEquals(Level.Great, push.level)
        assertEquals(Verb.Push, push.verb)
        assertEquals("Push", push.headline)

        val easy = engine(summary(40) { it.copy(sleepHrv = 56.0, restingHr = 55.0) }).compute().readiness
        assertEquals(Verb.Easy, easy.verb)

        val recover = engine(summary(40) { it.copy(sleepHrv = 40.0, restingHr = 63.0) }).compute().readiness
        assertEquals(Verb.Recover, recover.verb)
        // Every input reads as a sentence, with raw numbers kept separately.
        recover.contributors.forEach { c -> assertTrue(c.sentence, c.sentence.none { it.isDigit() }) }
        assertTrue(recover.contributors.first().raw.contains("ms"))
    }

    @Test fun feelingDrainedSoftensAPushDay() {
        val j = Journal().withFeel(today, Feel.Drained)
        val i = engine(summary(40, tweakToday = pushDay), j).compute()
        assertEquals(Verb.Push, i.readiness.verb)
        assertEquals(FeelMatch.FeelsWorse, i.feelMatch)
        assertEquals("Keep it moderate", i.focus.title)
        assertTrue(i.focus.body.contains("you feel drained"))

        val agree = engine(summary(40, tweakToday = pushDay), Journal().withFeel(today, Feel.Great)).compute()
        assertEquals(FeelMatch.Agrees, agree.feelMatch)
        assertEquals(Focus.Kind.Train, agree.focus.kind)

        // OK against Push is neither agreement nor disagreement; yesterday's answer doesn't count today.
        assertNull(engine(summary(40, tweakToday = pushDay), Journal().withFeel(today, Feel.Ok)).compute().feelMatch)
        assertNull(engine(summary(40, tweakToday = pushDay), Journal().withFeel(today.minusDays(1), Feel.Drained)).compute().feelMatch)
    }

    @Test fun modePausesTargetsAndChangesCopy() {
        // 15:00 with few steps would normally nag about steps.
        val afternoon = ZonedDateTime.of(today, LocalTime.of(15, 0), zone).toInstant()
        val lazy = summary(40) { it.copy(steps = 1000.0) }
        assertEquals(Focus.Kind.Move, engine(lazy, at = afternoon).compute().focus.kind)

        val rest = Journal().withMode(LifeMode.Rest, today.minusDays(1))
        val i = engine(lazy, rest, afternoon).compute()
        assertEquals(LifeMode.Rest, i.mode?.mode)
        assertEquals(Focus.Kind.Rest, i.focus.kind)
        assertFalse(i.focus.body.contains("steps"))

        val sick = engine(summary(40), Journal().withMode(LifeMode.Sick, today)).compute()
        assertEquals(Focus.Kind.Sick, sick.focus.kind)
    }

    @Test fun modeExpiresAfterSevenDaysAndStampsHistory() {
        val old = Journal(mode = ModeSpan(LifeMode.Sick, today.minusDays(10).toString()))
        assertNull(old.activeMode(today))
        assertEquals(7, old.excludedDates(today).size)
        assertNotNull(Journal(mode = ModeSpan(LifeMode.Sick, today.minusDays(6).toString())).activeMode(today))

        val off = Journal().withMode(LifeMode.Travel, today.minusDays(3)).withMode(null, today)
        assertNull(off.mode)
        assertEquals(setOf(today.minusDays(3), today.minusDays(2), today.minusDays(1)), off.excludedDates(today))
        assertEquals(LifeMode.Travel, off.entry(today.minusDays(2))?.mode)

        // On and off the same day is a mis-tap: nothing is kept.
        assertTrue(Journal().withMode(LifeMode.Sick, today).withMode(null, today).excludedDates(today).isEmpty())
        // Rest days still count toward the normal.
        assertTrue(Journal().withMode(LifeMode.Rest, today.minusDays(2)).excludedDates(today).isEmpty())
    }

    @Test fun sickAndTravelDaysAreLeftOutOfBaselines() {
        // A week of unusually high-HRV, high-RHR days just before today: a trip, now over.
        val trip = summary(70, tweakPast = { i, d -> if (i in 1..7) d.copy(sleepHrv = 90.0, restingHr = 65.0) else d })
        val skewed = engine(trip).compute()
        val travel = Journal().withMode(LifeMode.Travel, today.minusDays(7)).withMode(null, today)
        val clean = engine(trip, travel).compute()
        assertEquals(7, travel.excludedDates(today).size)
        assertEquals(skewed.hrv.baseline!!.n - 7, clean.hrv.baseline!!.n)
        assertTrue(clean.hrv.baseline!!.mean < skewed.hrv.baseline!!.mean)
        assertEquals(60.0, clean.hrv.baseline!!.mean, 1.5)
        assertEquals(55.0, clean.rhr.baseline!!.mean, 0.5)
    }

    @Test fun confidenceReflectsThinData() {
        // 20 days: a baseline exists (19 nights) but it's thin, so the verdict is low confidence and never "Push".
        val thin = engine(summary(20, tweakToday = pushDay)).compute()
        assertNotNull(thin.readiness.score)
        assertEquals(Confidence.Low, thin.readiness.confidence)
        assertEquals(Confidence.Low, thin.hrv.confidence)
        assertEquals(Verb.Ready, thin.readiness.verb)

        // HRV missing last night: still a score from resting HR and sleep, labelled low confidence.
        val noHrv = engine(summary(40) { it.copy(sleepHrv = null) }).compute()
        assertNotNull(noHrv.readiness.score)
        assertEquals(Confidence.Low, noHrv.readiness.confidence)
        assertEquals(Confidence.Low, noHrv.hrv.confidence)
        assertTrue(noHrv.readiness.contributors.first().raw.contains("left out"))

        val full = engine(summary(40)).compute()
        assertEquals(Confidence.High, full.hrv.confidence)
        assertEquals(Confidence.High, full.rhr.confidence)
    }

    @Test fun calmModeKeepsNumbersOutOfFocus() {
        val calm = settings.copy(calmMode = true)
        val i = engine(summary(20, sleepMin = 420), st = calm).compute()
        assertEquals(Focus.Kind.Sleep, i.focus.kind)
        assertFalse(i.focus.body, i.focus.body.contains("7h"))
        assertFalse(calm.showNumbers())
        assertTrue(settings.showNumbers())
    }

    @Test fun illnessNeedsTwoSignalsForTwoNights() {
        val sick = InsightEngine(summary(40, nights = 2) { it.copy(restingHr = 63.0, respRate = 17.0, sleepHrv = 40.0) }, settings, now).compute()
        assertTrue(sick.illnessFlag)
        assertEquals(Focus.Kind.Sick, sick.focus.kind)
        assertEquals(Level.Low, sick.rhr.level)
        assertEquals(2, sick.signalsOff!!.nights)
        assertTrue(sick.focus.body.contains("may be fighting something"))
        // One night isn't enough.
        val once = InsightEngine(summary(40) { it.copy(restingHr = 63.0, respRate = 17.0, sleepHrv = 40.0) }, settings, now).compute()
        assertFalse(once.illnessFlag)
        assertEquals(1, once.signalsOff!!.nights)
        // Elevated RHR alone is not called illness, however long it lasts.
        val tired = InsightEngine(summary(40, nights = 3) { it.copy(restingHr = 63.0) }, settings, now).compute()
        assertFalse(tired.illnessFlag)
    }

    @Test fun sleepDebtAndBedtime() {
        val i = InsightEngine(summary(20, sleepMin = 420), settings, now).compute()
        assertEquals(7 * 60, i.sleep.debt7Min)
        assertEquals(LocalTime.of(22, 45), i.sleep.suggestedBedtime) // 07:00 − 8 h − 15 min
        assertEquals(Level.Great, i.sleep.consistency)
    }

    @Test fun dailyLoadUnrollsZeppsRollingWeek() {
        // Zepp reports a 7-day rolling sum: a 380 run 10 days ago shows as 380 for 7 days, then 0;
        // a 120 session 2 days ago adds on top for the rest of its week.
        val s = summary(20, tweakPast = { i, d ->
            val r = (if (i in 4..10) 380.0 else 0.0) + (if (i in 0..2) 120.0 else 0.0)
            d.copy(trainingLoad = r)
        }, tweakToday = { it.copy(trainingLoad = 120.0) })
        val loads = engine(s).dailyLoad(14)
        assertEquals(14, loads.size)
        assertEquals(380.0, loads[13 - 10], 0.01)
        assertEquals(120.0, loads[13 - 2], 0.01)
        assertEquals(380.0 + 120.0, loads.sum(), 0.01) // one value per session, not one per day of its week
    }

    @Test fun dayTurnsOverAtFourNotMidnight() {
        val s = summary(20)
        val oneAm = ZonedDateTime.of(today.plusDays(1), LocalTime.of(1, 0), zone).toInstant()
        assertEquals(today, engine(s, at = oneAm).today) // still the day you had
        val fiveAm = ZonedDateTime.of(today.plusDays(1), LocalTime.of(5, 0), zone).toInstant()
        assertEquals(today.plusDays(1), engine(s, at = fiveAm).today)
        // Woke from a proper sleep at 3:30 am: that's already the new day.
        val end = ZonedDateTime.of(today.plusDays(1), LocalTime.of(3, 30), zone).toEpochSecond()
        val early = s.copy(sleeps = s.sleeps + Sleep(start = end - 6 * 3600, end = end, durationMin = 360))
        val fourToFour = ZonedDateTime.of(today.plusDays(1), LocalTime.of(3, 45), zone).toInstant()
        assertEquals(today.plusDays(1), engine(early, at = fourToFour).today)
    }

    @Test fun clockSdHandlesMidnight() {
        val sd = Stats.clockSdMinutes(listOf(23 * 60 + 30.0, 30.0, 23 * 60 + 30.0, 30.0))!!
        assertTrue("sd $sd", sd in 25.0..40.0)
        assertEquals(0.0, Stats.clockMeanMinutes(listOf(23 * 60 + 30.0, 30.0))!! % 1440, 1.0)
    }

    @Test fun demoDataProducesAFullPicture() {
        val i = InsightEngine(DemoData.generate(LocalDate.now(), zone), settings).compute()
        assertNotNull(i.readiness.score)
        assertNotNull(i.hrv.baseline)
        assertNotNull(i.activity.load)
        assertTrue(i.weightTrend.isNotEmpty())
    }
}
