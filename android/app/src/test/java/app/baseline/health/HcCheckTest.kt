package app.baseline.health

import app.baseline.data.Day
import app.baseline.data.Sleep
import app.baseline.data.Summary
import app.baseline.health.HcCheck.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class HcCheckTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun t(s: String) = java.time.LocalDateTime.parse(s).atZone(ist).toEpochSecond()
    private val self = "app.baseline"

    @Test fun `counts per app, Baseline first, other writers flagged`() {
        val recs = listOf(
            HcCheck.Rec(Kind.HEART_RATE, self, 60), HcCheck.Rec(Kind.HEART_RATE, self, 58),
            HcCheck.Rec(Kind.STEPS, "com.android.healthconnect.phone.x", 1), HcCheck.Rec(Kind.STEPS, self, 1),
            HcCheck.Rec(Kind.STEPS, "com.android.healthconnect.phone.x", 1),
        )
        val rows = HcCheck.byApp(recs, self)
        assertEquals(listOf(Kind.HEART_RATE, Kind.STEPS), rows.map { it.first })
        assertEquals(listOf(self to 118), rows[0].second)
        assertEquals(listOf(self to 1, "com.android.healthconnect.phone.x" to 2), rows[1].second)
        assertEquals(mapOf("com.android.healthconnect.phone.x" to listOf(Kind.STEPS)), HcCheck.otherWriters(rows, self))
    }

    private val zeppNight = Sleep(start = t("2026-11-24T02:00"), end = t("2026-11-24T09:29"))
    private val summary = Summary(sleeps = listOf(zeppNight), days = listOf(Day("2026-11-24", sleepHrv = 76.0)))

    @Test fun `the full night with Zepp's HRV matches`() {
        val night = HcCheck.Night(zeppNight.start, zeppNight.end, self, mapOf("deep" to 60))
        val hrv = listOf(t("2026-11-24T03:00") to 70.0, t("2026-11-24T06:00") to 82.0, t("2026-11-24T12:00") to 20.0)
        val c = HcCheck.lastNight(listOf(night), hrv, summary, ist)!!
        assertEquals(76.0, c.hrvAvg!!, 0.01) // the 12:00 reading is outside the night
        assertTrue(c.matchesZepp)
    }

    @Test fun `Zepp's 5 am half night does not match`() {
        val partial = HcCheck.Night(t("2026-11-24T02:02"), t("2026-11-24T04:52"), "com.huami.watch.hmwatchmanager", emptyMap())
        val c = HcCheck.lastNight(listOf(partial), emptyList(), summary, ist)!!
        assertFalse(c.matchesZepp)
        assertNull(c.hrvAvg)
    }

    @Test fun `a nap after the night doesn't hide the night`() {
        val night = HcCheck.Night(zeppNight.start, zeppNight.end, self, emptyMap())
        val nap = HcCheck.Night(t("2026-11-24T15:00"), t("2026-11-24T15:40"), self, emptyMap())
        assertEquals(night, HcCheck.lastNight(listOf(nap, night), emptyList(), summary, ist)!!.night)
    }
}
