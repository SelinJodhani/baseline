package app.baseline.health

import app.baseline.data.Day
import app.baseline.data.Sleep
import app.baseline.data.Summary
import app.baseline.data.Weight
import app.baseline.data.Workout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HcPlannerTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun t(s: String) = java.time.LocalDateTime.parse(s).atZone(ist).toEpochSecond()
    private fun stage(a: String, b: String, k: String) = listOf(JsonPrimitive(t(a)), JsonPrimitive(t(b)), JsonPrimitive(k))

    // The 24 Nov night: Zepp's 4:52 am copy, and the finished one.
    private val partial = Sleep(start = t("2026-11-24T02:02"), end = t("2026-11-24T04:52"), score = 60,
        stages = listOf(stage("2026-11-24T02:02", "2026-11-24T03:00", "light"), stage("2026-11-24T03:00", "2026-11-24T04:52", "deep")))
    private val full = Sleep(start = t("2026-11-24T02:00"), end = t("2026-11-24T09:29"), score = 82,
        stages = listOf(
            stage("2026-11-24T02:00", "2026-11-24T03:00", "light"),
            stage("2026-11-24T02:50", "2026-11-24T05:00", "deep"),   // overlaps the previous one: trimmed
            stage("2026-11-24T05:00", "2026-11-24T09:40", "rem"),    // runs past the end: clipped
        ))

    @Test fun `a night isn't written until Zepp has readings well after it ended`() {
        val s = Summary(sleeps = listOf(partial))
        // Zepp synced at 4:54 am: last reading two minutes after the "end", so the night is still open.
        assertTrue(HcPlanner.settledNights(s, 0, t("2026-11-24T04:54"), t("2026-11-24T05:00")).isEmpty())
        val done = Summary(sleeps = listOf(full))
        // Woke at 9:29; Zepp's upload reaches only 9:40: not yet. By 9:52 (23 minutes on, like 25 Nov): finished.
        assertTrue(HcPlanner.settledNights(done, 0, t("2026-11-24T09:40"), t("2026-11-24T11:00")).isEmpty())
        val nights = HcPlanner.settledNights(done, 0, t("2026-11-24T09:52"), t("2026-11-24T11:05"))
        assertEquals(1, nights.size)
        assertEquals(listOf(
            HcPlanner.Stage(t("2026-11-24T02:00"), t("2026-11-24T03:00"), "light"),
            HcPlanner.Stage(t("2026-11-24T03:00"), t("2026-11-24T05:00"), "deep"),
            HcPlanner.Stage(t("2026-11-24T05:00"), t("2026-11-24T09:29"), "rem"),
        ), nights[0].stages)
    }

    @Test fun `overlapping sessions from two accounts become one night, the longer`() {
        val a = Sleep(start = t("2026-10-26T01:08"), end = t("2026-10-26T09:28"))
        val b = Sleep(start = t("2026-10-26T06:36"), end = t("2026-10-26T12:33"))
        val nap = Sleep(start = t("2026-10-26T15:00"), end = t("2026-10-26T15:40"))
        val nights = HcPlanner.settledNights(Summary(sleeps = listOf(b, nap, a)), 0, t("2026-10-27T00:00"), t("2026-10-27T00:00"))
        assertEquals(listOf(a.start, nap.start), nights.map { it.start })
    }

    @Test fun `heart rate is grouped by hour and the open hour ends at its last reading`() {
        val now = t("2026-11-24T10:20")
        val rows = listOf(
            listOf(t("2026-11-24T08:31").toDouble(), 60.0), listOf(t("2026-11-24T09:10").toDouble(), 61.0),
            listOf(t("2026-11-24T10:15").toDouble(), 75.0), listOf(t("2026-11-24T10:16").toDouble(), 400.0),
        )
        val hours = HcPlanner.hrHours(rows, now)
        assertEquals(2, hours.size) // IST hours start at :30 UTC-aligned; 08:31 and 09:10 share one
        assertEquals(hours[0].start + 3600, hours[0].end)
        assertEquals(t("2026-11-24T10:15") + 1, hours[1].end)
        assertEquals(listOf(75.0), hours[1].samples.map { it.v }) // 400 bpm dropped
    }

    @Test fun `HRV outside Health Connect's range is dropped, not clamped`() {
        val pts = HcPlanner.points(listOf(listOf(1.0, 0.5), listOf(2.0, 76.0), listOf(3.0, 250.0)), 1.0, 200.0)
        assertEquals(listOf(HcPlanner.Point(2, 76.0)), pts)
    }

    @Test fun `daily totals cover the local day and today ends now`() {
        val now = t("2026-11-24T19:30")
        val s = Summary(days = listOf(
            Day("2026-11-23", steps = 12230.0, distanceM = 8445.0, activeCalories = 357.0),
            Day("2026-11-24", steps = 2737.0),
            Day("2026-11-25", steps = 0.0),
        ))
        val d = HcPlanner.dayTotals(s, LocalDate.parse("2026-11-01"), now, ist)
        assertEquals(2, d.size)
        assertEquals(t("2026-11-23T00:00"), d[0].start)
        assertEquals(t("2026-11-24T00:00"), d[0].end)
        assertEquals(now, d[1].end)
        assertNull(d[1].distanceM)
    }

    @Test fun `resting HR and breathing wait for the night, past days without one get noon`() {
        val today = LocalDate.parse("2026-11-24")
        val s = Summary(days = listOf(
            Day("2026-11-22", restingHr = 59.0, respRate = 18.0),
            Day("2026-11-24", restingHr = 58.0, respRate = 17.0),
        ))
        val since = LocalDate.parse("2026-11-01")
        val noNight = HcPlanner.restingHr(s, since, emptyMap(), today, ist)
        assertEquals(listOf(HcPlanner.Vital(LocalDate.parse("2026-11-22"), t("2026-11-22T12:00"), 59.0)), noNight)
        assertTrue(HcPlanner.breathing(s, since, emptyMap(), ist).isEmpty())

        val night = HcPlanner.Night(t("2026-11-24T02:00"), t("2026-11-24T09:29"), emptyList(), 82)
        val main = HcPlanner.mainNights(listOf(night), ist)
        assertEquals(t("2026-11-24T09:29"), HcPlanner.restingHr(s, since, main, today, ist).last().at)
        // Mid-night is 5:44:30 am IST, inside the 15-minute margin past midnight UTC: it goes to 5:45.
        assertEquals(t("2026-11-24T05:45"), HcPlanner.breathing(s, since, main, ist).single().at)
        // A night that starts after midnight UTC keeps its mid-night stamp.
        val late = HcPlanner.Night(t("2026-11-24T06:00"), t("2026-11-24T12:00"), emptyList(), null)
        assertEquals((late.start + late.end) / 2, HcPlanner.nightStamp(late, ist))
    }

    @Test fun `breathing rate of an early night moves to just past midnight UTC on the wake-up day`() {
        // 25 Nov: 1:05–9:06 am IST; mid-night 5:05 am IST is still the 24th in UTC.
        val night = HcPlanner.Night(t("2026-11-25T01:05"), t("2026-11-25T09:06"), emptyList(), null)
        val at = HcPlanner.nightStamp(night, ist)
        assertEquals(t("2026-11-25T05:45"), at)
        assertEquals(LocalDate.parse("2026-11-25"), java.time.Instant.ofEpochSecond(at).atZone(java.time.ZoneOffset.UTC).toLocalDate())
    }

    @Test fun `workouts need an end, weights a plausible value`() {
        val s = Summary(
            workouts = listOf(Workout("1", "strength", 100, 200), Workout("2", "walking", 300, null)),
            weights = listOf(Weight(150, 72.3), Weight(160, 0.0)),
        )
        assertEquals(listOf("1"), HcPlanner.workouts(s, 0, 1000).map { it.id })
        assertEquals(listOf(HcPlanner.Point(150, 72.3)), HcPlanner.weights(s, 0, 1000))
    }

    @Test fun `Google HRV shift puts a night's first reading just past midnight UTC on the wake-up day`() {
        fun night(a: String, b: String) = HcPlanner.Night(t(a), t(b), emptyList(), null)
        val n = night("2026-11-24T02:00", "2026-11-24T09:29")
        assertEquals(3 * 3600L + 45 * 60, HcPlanner.googleHrvShift(n, ist)) // to 05:45 IST = 00:15 UTC
        val moved = HcPlanner.googleHrv(n, listOf(
            HcPlanner.Point(t("2026-11-24T01:00"), 99.0),  // before the night: not written
            HcPlanner.Point(t("2026-11-24T02:00"), 70.0),
            HcPlanner.Point(t("2026-11-24T09:00"), 80.0),
            HcPlanner.Point(t("2026-11-24T12:00"), 99.0),  // after it: not written
        ), ist)
        assertEquals(listOf(70.0, 80.0), moved.map { it.second.v })
        assertEquals(t("2026-11-24T02:00"), moved[0].first) // keeps its original time for the record id
        val firstUtc = java.time.Instant.ofEpochSecond(moved[0].second.t).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        assertEquals(LocalDate.parse("2026-11-24"), firstUtc)
        // Already on the wake-up day in UTC (after 05:30 IST): at most the margin.
        assertEquals(10 * 60L, HcPlanner.googleHrvShift(night("2026-10-10T05:35", "2026-10-10T09:30"), ist))
        assertEquals(0L, HcPlanner.googleHrvShift(night("2026-10-10T06:00", "2026-10-10T09:30"), ist))
        // Bedtime before midnight: moved to the wake-up day too.
        assertEquals(6 * 3600L + 35 * 60, HcPlanner.googleHrvShift(night("2026-11-08T23:10", "2026-11-09T07:38"), ist))
        // Far east of UTC the move would be too big for Google to keep counting: left alone.
        val nz = java.time.ZoneOffset.ofHours(12)
        val far = HcPlanner.Night(java.time.LocalDateTime.parse("2026-11-23T22:00").atZone(nz).toEpochSecond(),
            java.time.LocalDateTime.parse("2026-11-24T06:00").atZone(nz).toEpochSecond(), emptyList(), null)
        assertEquals(0L, HcPlanner.googleHrvShift(far, nz))
    }

    @Test fun `heart rate is written once - only readings after the watermark, one record per clock hour`() {
        val pts = listOf(t("2026-11-24T10:01"), t("2026-11-24T10:05"), t("2026-11-24T10:40"), t("2026-11-24T10:50")).map { HcPlanner.Point(it, 60.0) }
        // IST hours start at :30 in UTC terms, so 10:01/10:05 and 10:40/10:50 fall in different clock hours.
        val chunks = HcPlanner.hrChunks(pts, after = t("2026-11-24T10:01"))
        assertEquals(listOf(listOf(t("2026-11-24T10:05")), listOf(t("2026-11-24T10:40"), t("2026-11-24T10:50"))), chunks.map { c -> c.samples.map { it.t } })
        assertEquals(t("2026-11-24T10:50") + 1, chunks.last().end)
        assertTrue(HcPlanner.hrChunks(pts, after = t("2026-11-24T11:00")).isEmpty())
    }

    @Test fun `daily totals grow by adding what's new, never by rewriting`() {
        val date = LocalDate.parse("2026-11-24")
        fun totals(end: String, steps: Long?, dist: Double? = null) = HcPlanner.DayTotals(date, t("2026-11-24T00:00"), t(end), steps, dist, null)
        val first = HcPlanner.dayStep(totals("2026-11-24T10:00", 2000, 1500.0), null)
        assertEquals(HcPlanner.DayTotals(date, t("2026-11-24T00:00"), t("2026-11-24T10:00"), 2000, 1500.0, null), first.add)
        // An hour later: only the 737 new steps, from 10:00 to 11:00; distance unchanged, so not written.
        val second = HcPlanner.dayStep(totals("2026-11-24T11:00", 2737, 1500.0), first.progress)
        assertEquals(HcPlanner.DayTotals(date, t("2026-11-24T10:00"), t("2026-11-24T11:00"), 737, null, null), second.add)
        // Nothing new: nothing written, but the window moves on.
        val third = HcPlanner.dayStep(totals("2026-11-24T12:00", 2737, 1500.0), second.progress)
        assertNull(third.add)
        assertEquals(t("2026-11-24T12:00"), third.progress.until)
        // Zepp lowered the day: replace it with one record of the current totals.
        val lowered = HcPlanner.dayStep(totals("2026-11-24T13:00", 2600, 1500.0), third.progress)
        assertNull(lowered.add)
        assertEquals(t("2026-11-24T00:00"), lowered.full!!.start)
        assertEquals(2600L, lowered.full!!.steps)
        assertEquals(2600L, lowered.progress.steps)
    }

    @Test fun `each rewrite generation gets its own ids, generation 0 keeps the original form`() {
        assertEquals("baseline-hrv-1764000000", HcPlanner.recordId("hrv", 0, 1764000000L))
        assertEquals("baseline-hrv-g1-1764000000", HcPlanner.recordId("hrv", 1, 1764000000L))
        assertTrue(HcPlanner.recordId("hrv", 2, 1764000000L) != HcPlanner.recordId("hrv", 1, 1764000000L))
        // Daily-total ids still start with their kind, which the fingerprint clean-up relies on.
        assertTrue(HcPlanner.recordId("steps", 3, "2026-11-24-1763922600").startsWith("baseline-steps-"))
    }

    @Test fun `a refill adds only hours with none of Baseline's readings, inside the window`() {
        val pts = listOf("09:40", "10:10", "10:20", "10:45", "11:35", "12:40").map { HcPlanner.Point(t("2026-10-28T$it"), 70.0) }
        // IST clock hours run :30–:30 in epoch terms; Baseline already has the 10:30–11:30 hour.
        val covered = setOf(HcPlanner.floorHour(t("2026-10-28T10:45")))
        val fill = HcPlanner.missingHours(pts, covered, from = t("2026-10-28T09:30"), to = t("2026-10-28T12:30"))
        assertEquals(listOf(listOf("09:40", "10:10", "10:20"), listOf("11:35")).map { h -> h.map { t("2026-10-28T$it") } }, fill.map { c -> c.samples.map { it.t } })
        assertEquals(t("2026-10-28T10:20") + 1, fill.first().end)
        // Nothing left to add once every hour is there.
        assertTrue(HcPlanner.missingHours(pts, fill.map { HcPlanner.floorHour(it.start) }.toSet() + covered, t("2026-10-28T09:30"), t("2026-10-28T12:30")).isEmpty())
    }

    @Test fun `daytime SpO2 keeps its times, readings inside a night are left for the night`() {
        val night = HcPlanner.Night(t("2026-11-24T02:00"), t("2026-11-24T09:29"), emptyList(), null)
        val pts = listOf("2026-11-23T19:00", "2026-11-24T02:00", "2026-11-24T05:10", "2026-11-24T09:29", "2026-11-24T19:00")
            .map { HcPlanner.Point(t(it), 98.0) }
        assertEquals(listOf("2026-11-23T19:00", "2026-11-24T09:29", "2026-11-24T19:00").map { t(it) },
            HcPlanner.outsideNights(pts, listOf(night)).map { it.t })
        // The same mover as HRV: the night's first reading lands just past midnight UTC on the wake-up day.
        val moved = HcPlanner.googleHrv(night, pts, ist)
        assertEquals(2, moved.size)
        assertEquals(java.time.LocalDate.parse("2026-11-24"), java.time.Instant.ofEpochSecond(moved.first().second.t).atZone(java.time.ZoneOffset.UTC).toLocalDate())
    }

    @Test fun `zepp workout types map to Health Connect types`() {
        assertEquals(androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, HealthConnectSync.exerciseType("treadmill"))
        assertEquals(androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, HealthConnectSync.exerciseType("unknown:15"))
        assertEquals("Pool swimming", HealthConnectSync.title("pool_swimming"))
        assertEquals("Workout", HealthConnectSync.title("unknown:15"))
    }
}
