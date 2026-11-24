package app.baseline.ui.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic values only. */
class ChartMathTest {
    @Test fun bucketsFollowBevel() {
        assertEquals(1, bucketDaysFor(14)); assertEquals(1, bucketDaysFor(30))
        assertEquals(3, bucketDaysFor(90)); assertEquals(6, bucketDaysFor(180)); assertEquals(30, bucketDaysFor(365))
    }

    @Test fun rollingNormalIsTrailingMeanPlusMinusSd() {
        val daily = List(10) { if (it % 2 == 0) 50.0 else 60.0 }
        val band = rollingNormal(daily, 28)
        assertNull("needs a week of readings", band[5])
        val b = band[9]!!
        assertEquals(50.0, b.start, 1e-9); assertEquals(60.0, b.endInclusive, 1e-9)
    }

    @Test fun trendRowsFlagOnlyClearChanges() {
        // 90 flat-ish days at 8,000 then 30 days at 4,000: the 30-day row is a clear, bad drop.
        val noise = listOf(-300.0, 200.0, 100.0, -100.0, 250.0, -150.0)
        val daily = List(120) { i -> (if (i < 90) 8000.0 else 4000.0) + noise[i % noise.size] }
        val rows = trendRows(daily, higherIsBetter = true).associateBy { it.days }
        val r30 = rows.getValue(30)
        assertTrue(r30.significant); assertEquals(false, r30.good); assertTrue(r30.delta < -3500)
        // Same level now and 7 days ago: not significant, no colour.
        val r7 = rows.getValue(7)
        assertFalse(r7.significant); assertNull(r7.good)
        // Neither direction is "good" for neutral metrics.
        assertNull(trendRows(daily, higherIsBetter = null).first { it.days == 30 }.good)
    }
}
