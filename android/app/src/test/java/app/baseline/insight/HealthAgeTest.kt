package app.baseline.insight

import app.baseline.data.Day
import app.baseline.data.Sleep
import app.baseline.labs.LabMarker
import app.baseline.labs.LabReport
import app.baseline.labs.LabsData
import app.baseline.labs.PhenoAge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Synthetic values only. */
class HealthAgeTest {
    private val utc: ZoneId = ZoneOffset.UTC

    @Test fun paperWorkedExample() {
        // Paper, Figure 2: age 29, 9,000 steps, 420 AZM/week, RHR 60, 7 h sleep → Health Age 26.0.
        val (parts, missing) = Cha.score(Cha.Inputs(steps = 9000.0, sleepHours = 7.0, restingHr = 60.0, azmPerWeek = 420.0))
        val gap = parts.sumOf { it.years }
        assertTrue(missing.isEmpty())
        assertEquals(-2.998, gap, 0.01)
        assertEquals(26.0, maxOf(18.0, 29 + gap), 0.05)
        assertEquals(-0.610, parts.first { it.lever == Cha.Lever.Steps }.years, 0.005)
        assertEquals(-2.388, parts.first { it.lever == Cha.Lever.ActiveZone }.years, 0.005)
    }

    @Test fun domainCurves() {
        assertEquals(0.0, Cha.lnHrRestingHr(55.0), 0.0)
        assertEquals(kotlin.math.ln(1.33), Cha.lnHrRestingHr(90.0), 1e-9)
        assertEquals(Cha.lnHrSteps(9000.0), Cha.lnHrSteps(15000.0), 1e-12) // plateau
        assertEquals(0.0, Cha.lnHrSleep(7.5), 0.0)
        assertTrue(Cha.lnHrSleep(5.0) > 0 && Cha.lnHrSleep(10.0) > 0)       // U-shape
        assertEquals(0.0, Cha.lnHrAzm(100.0), 0.0)
        assertEquals(kotlin.math.ln(0.68), Cha.lnHrAzm(900.0), 1e-9)
        assertEquals(kotlin.math.ln(1.52), Cha.lnHrRegularity(90.0), 1e-9)
        assertEquals(kotlin.math.ln(2.11), Cha.lnHrRegularity(400.0), 1e-9) // capped at 120 min
    }

    private fun sleepEnding(d: LocalDate, hours: Double): Sleep {
        val end = d.atTime(7, 0).toEpochSecond(ZoneOffset.UTC)
        val min = (hours * 60).toInt()
        return Sleep(start = end - min * 60L, end = end, durationMin = min)
    }

    @Test fun windowNeedsFiveValidWeeks() {
        val end = LocalDate.of(2026, 11, 22)
        fun data(daysBack: Int): Pair<Map<LocalDate, Day>, Map<LocalDate, Sleep>> {
            val ds = (0 until daysBack).map { end.minusDays(it.toLong()) }
            return ds.associateWith { Day(it.toString(), steps = 7000.0, minHr = 58.0, azm = 20.0) } to ds.associateWith { sleepEnding(it, 7.0) }
        }
        val (d3, s3) = data(21)
        assertNull("three weeks isn't enough", Cha.compute(d3, s3, utc, end))
        val (d8, s8) = data(56)
        val r = Cha.compute(d8, s8, utc, end)!!
        assertTrue(r.validWeeks >= 5)
        assertEquals(140.0, r.inputs.azmPerWeek!!, 1e-9)
        assertEquals(58.0, r.inputs.restingHr!!, 1e-9)
        // 7,000 steps costs years; the what-ifs say how to win them back.
        assertTrue(r.yearsFor(Cha.Lever.Steps) > 0)
        val ifs = Cha.whatIfs(r)
        assertTrue(ifs.any { it.lever == Cha.Lever.Steps && it.yearsGained > 0 })
        assertTrue(ifs.zipWithNext().all { (a, b) -> a.yearsGained >= b.yearsGained })
    }

    @Test fun olderBridgeWithoutAzmMarksItMissing() {
        val end = LocalDate.of(2026, 11, 22)
        val ds = (0 until 60).map { end.minusDays(it.toLong()) }
        val r = Cha.compute(ds.associateWith { Day(it.toString(), steps = 9000.0, restingHr = 62.0) }, ds.associateWith { sleepEnding(it, 7.5) }, utc, end)!!
        assertTrue(Cha.Lever.ActiveZone in r.missing)
        assertEquals(62.0, r.inputs.restingHr!!, 1e-9) // falls back to Zepp's resting HR
    }

    @Test fun phenoAgeFormula() {
        val healthy = PhenoAge.REFERENCE
        assertEquals(32.916, PhenoAge.compute(healthy, 40.0)!!, 0.01)
        // About 0.9 years of PhenoAge per year of age, and worse markers age you.
        val older = PhenoAge.compute(healthy, 50.0)!!
        assertEquals(8.9, older - PhenoAge.compute(healthy, 40.0)!!, 0.4)
        val inflamed = PhenoAge.compute(healthy + (PhenoAge.Input.Crp to 10.0) + (PhenoAge.Input.Rdw to 15.0), 40.0)!!
        assertTrue(inflamed > 32.916 + 3)
        assertNull(PhenoAge.compute(healthy - PhenoAge.Input.Rdw, 40.0))
    }

    @Test fun phenoAgeFromLabsAcrossNearbyReports() {
        fun m(key: String, v: Double, unit: String) = LabMarker(key, key, v, unit)
        val a = LabReport("a", "2026-06-01", markers = listOf(m("albumin", 4.5, "g/dL"), m("creatinine", 0.9, "mg/dL"), m("glucose_fasting", 90.0, "mg/dL"),
            m("hscrp", 1.0, "mg/L"), m("mcv", 90.0, "fL"), m("alp", 70.0, "U/L"), m("wbc", 6.0, "thousand/µL")))
        // CBC done ten days later, with an older-style slug for lymphocytes.
        val b = LabReport("b", "2026-06-11", markers = listOf(LabMarker("x_lymphocytes", "Lymphocytes", 30.0, "%"), m("rdw", 13.0, "%")))
        val noAge = PhenoAge.fromLabs(LabsData(reports = listOf(a, b)), birthYear = null)
        assertTrue(noAge is PhenoAge.NeedsAge)
        val r = PhenoAge.fromLabs(LabsData(reports = listOf(a, b)), birthYear = 1986) as PhenoAge.Result
        assertEquals(32.916, r.phenoAge, 0.05)
        val partial = PhenoAge.fromLabs(LabsData(reports = listOf(a)), birthYear = 1986) as PhenoAge.Missing
        assertEquals(setOf(PhenoAge.Input.Lymphocytes, PhenoAge.Input.Rdw), partial.missing.toSet())
    }
}
