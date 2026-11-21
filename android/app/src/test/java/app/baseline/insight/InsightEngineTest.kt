package app.baseline.insight

import app.baseline.data.AppSettings
import app.baseline.data.Day
import app.baseline.data.DemoData
import app.baseline.data.Sleep
import app.baseline.data.Summary
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

    /** [n] steady days ending today; [tweakToday] edits today's record. */
    private fun summary(n: Int, sleepMin: Int = 480, tweakToday: (Day) -> Day = { it }): Summary {
        val days = (n - 1 downTo 0).map { i ->
            val d = today.minusDays(i.toLong())
            val wobble = if (i % 2 == 0) 1.0 else -1.0
            Day(date = d.toString(), sleepHrv = 60.0 + 3 * wobble, restingHr = 55.0 + wobble, respRate = 15.0 + 0.2 * wobble, steps = 8000.0)
                .let { if (i == 0) tweakToday(it) else it }
        }
        val sleeps = (n - 1 downTo 0).map { i ->
            val end = ZonedDateTime.of(today.minusDays(i.toLong()), LocalTime.of(7, 0), zone).toEpochSecond()
            Sleep(start = end - sleepMin * 60L, end = end, durationMin = sleepMin, deepMin = 90, lightMin = sleepMin - 190, remMin = 100, awakeMin = 0)
        }
        return Summary(timezone = zone.id, days = days, sleeps = sleeps)
    }

    @Test fun calibratingUntil14Nights() {
        val i = InsightEngine(summary(10), settings, now).compute()
        assertNull(i.readiness.score)
        assertNotNull(i.readiness.calibratingNights)
        assertEquals(Focus.Kind.Calibrate, i.focus.kind)
    }

    @Test fun normalDayScoresAroundSeventy() {
        val i = InsightEngine(summary(40), settings, now).compute()
        val score = i.readiness.score!!
        assertTrue("score $score", score in 55..85)
        assertFalse(i.illnessFlag)
    }

    @Test fun illnessNeedsBothRhrAndBreathing() {
        val sick = InsightEngine(summary(40) { it.copy(restingHr = 63.0, respRate = 17.0, sleepHrv = 40.0) }, settings, now).compute()
        assertTrue(sick.illnessFlag)
        assertEquals(Focus.Kind.Sick, sick.focus.kind)
        assertEquals(Level.Low, sick.rhr.level)
        // Elevated RHR alone is not called illness.
        val tired = InsightEngine(summary(40) { it.copy(restingHr = 63.0) }, settings, now).compute()
        assertFalse(tired.illnessFlag)
    }

    @Test fun sleepDebtAndBedtime() {
        val i = InsightEngine(summary(20, sleepMin = 420), settings, now).compute()
        assertEquals(7 * 60, i.sleep.debt7Min)
        assertEquals(LocalTime.of(22, 45), i.sleep.suggestedBedtime) // 07:00 − 8 h − 15 min
        assertEquals(Level.Great, i.sleep.consistency)
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
