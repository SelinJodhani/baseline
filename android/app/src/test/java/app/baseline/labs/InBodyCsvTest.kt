package app.baseline.labs

import app.baseline.insight.Stats
import app.baseline.ui.charts.niceTicks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Synthetic values only, in the column layout of InBody's own CSV export. */
class InBodyCsvTest {
    private val header = "Date,Measurement device.,Weight(kg),Skeletal Muscle Mass(kg),Soft Lean Mass(kg),Body Fat Mass(kg),BMI(kg/m²)," +
        "Percent Body Fat(%),Basal Metabolic Rate(kcal),InBody Score,Right Arm Lean Mass(kg),Left Arm Lean Mass(kg),Trunk Lean Mass(kg)," +
        "Right Leg Lean Mass(kg),Left leg Lean Mass(kg),Right Arm Fat Mass(kg),Left Arm Fat Mass(kg),Trunk Fat Mass(kg),Right Leg Fat Mass(kg)," +
        "Left Leg Fat Mass(kg),Waist Hip Ratio,Visceral Fat Level(Level),Total Body Water(L),ECW Ratio,Upper-Lower,Protein(kg),Mineral(kg),SMI(kg/m²),Whole Body Phase Angle(°)"

    private fun row(date: String, w: String, smm: String = "30.0", bfm: String = "15.0", pbf: String = "20.0") =
        "$date,270,$w,$smm,-,$bfm,22.0,$pbf,1500,75.0,3.0,3.1,25.0,8.5,8.6,0.8,0.8,7.0,2.2,2.3,0.88,5.0,40.0,-,0,10.5,3.4,7.5,-"

    private val utc = ZoneId.of("UTC")

    @Test fun parsesInBodyExport() {
        val csv = listOf(header, row("20260301090000", "75.0"), row("20260201090000", "76.0")).joinToString("\n")
        val r = InBodyCsv.parse(csv, utc, now = 0)
        assertEquals(2, r.scans.size)
        val s = r.scans.last()
        assertEquals("2026-03-01", s.date)
        assertEquals(75.0, s.weightKg!!, 1e-9)
        assertEquals(30.0, s.skeletalMuscleKg!!, 1e-9)
        assertEquals(20.0, s.percentBodyFat!!, 1e-9)
        assertEquals(5.0, s.visceralFatLevel!!, 1e-9)
        assertEquals(1500.0, s.bmrKcal!!, 1e-9)
        assertEquals(7.5, s.smi!!, 1e-9)
        assertEquals(0.88, s.waistHipRatio!!, 1e-9)
        assertEquals(5, s.segmentalLean.size)
        assertEquals(8.6, s.segmentalLean.first { it.part == "leftLeg" }.kg!!, 1e-9)
        assertNull("'-' means not measured", s.ecwTbw)
        assertNull(s.softLeanKg)
        assertNull(s.phaseAngle)
        assertEquals(60.0, s.leanKg!!, 1e-9)
        assertEquals("csv", s.source)
        assertEquals("oldest first", "2026-02-01", r.scans.first().date)
    }

    @Test fun collapsesDuplicatesAndSameDayRepeats() {
        val csv = listOf(
            header,
            row("20260725151224", "58.0"), row("20260725151224", "58.0"), // exact duplicate
            row("20260725151100", "58.1"), row("20260725150919", "58.0"), // earlier same-day scans
            row("20260704125757", "58.6"),
        ).joinToString("\n")
        val r = InBodyCsv.parse(csv, utc, now = 0)
        assertEquals(2, r.scans.size)
        assertEquals(1, r.duplicates)
        assertEquals(2, r.sameDay)
        assertEquals("keeps the last scan of the day", 58.0, r.scans.last().weightKg!!, 1e-9)
        assertTrue(r.scans.last().id.endsWith("20260725151224"))
    }

    @Test fun reimportGivesSameIds() {
        val csv = listOf(header, row("20260301090000", "75.0")).joinToString("\n")
        assertEquals(InBodyCsv.parse(csv, utc, now = 1).scans.single().id, InBodyCsv.parse(csv, utc, now = 2).scans.single().id)
    }

    @Test fun handlesPoundsSemicolonsAndQuotes() {
        val csv = "﻿\"Test Date\";\"Weight(lb)\";\"PBF(%)\"\n\"2026.03.01 08:30\";\"165,0\";\"21,5\""
        val s = InBodyCsv.parse(csv, utc, now = 0).scans.single()
        assertEquals("2026-03-01", s.date)
        assertEquals(74.8, s.weightKg!!, 0.05)
        assertEquals(21.5, s.percentBodyFat!!, 1e-9)
    }

    @Test fun skipsRowsWithoutDateOrNumbers() {
        val csv = listOf(header, row("not a date", "75.0"), row("20260301090000", "-", smm = "-", pbf = "-"), row("20260302090000", "74.0")).joinToString("\n")
        val r = InBodyCsv.parse(csv, utc, now = 0)
        assertEquals(1, r.scans.size)
        assertEquals(2, r.unreadable)
    }

    @Test fun rejectsOtherCsv() {
        assertFalse(InBodyCsv.looksLikeInBody("name,email\nA,b@c"))
        assertTrue(InBodyCsv.looksLikeInBody(header))
        assertTrue(runCatching { InBodyCsv.parse("name,email\nA,b@c", utc) }.exceptionOrNull() is InBodyCsv.NotInBodyCsv)
    }

    @Test fun statusUsesNumbersOverFlag() {
        val m = LabMarker("ldl", "LDL", 160.0, "mg/dL", refHigh = 130.0, flag = "normal")
        assertEquals(LabFlag.High, m.status)
        assertTrue(m.outOfRange)
        assertEquals(LabFlag.Normal, m.copy(value = 100.0).status)
        assertEquals(LabFlag.Low, LabMarker("hdl", "HDL", 35.0, "mg/dL", refLow = 40.0).status)
        assertEquals(LabFlag.Unknown, LabMarker("x", "X", 1.0, "").status)
    }

    @Test fun niceTicksAreRound() {
        assertEquals(listOf(70.0, 75.0, 80.0, 85.0), niceTicks(68.6, 86.0))
        assertEquals(listOf(0.0, 5000.0, 10000.0), niceTicks(0.0, 11000.0, target = 3))
        assertTrue(niceTicks(55.0, 61.0).all { it % 1.0 == 0.0 })
    }

    @Test fun weightTrendCatchesUpAfterGap() {
        // Readings at day 0..2 near 60, then one at day 74 at 55: a reading-count EWMA would still say ~59.5.
        val daily = MutableList<Double?>(75) { null }
        daily[0] = 60.0; daily[1] = 60.2; daily[2] = 59.8; daily[74] = 55.0
        val t = Stats.timeEwma(daily, 10.0)
        assertEquals(55.0, t.last()!!, 0.01)
        assertNull(Stats.timeEwma(listOf(null, 1.0), 10.0).first())
        val pts = Stats.timeEwmaAt(listOf(0L to 60.0, 74L * 86400 to 55.0), 10.0)
        assertEquals(55.0, pts.last().second, 0.01)
    }

    @Test fun aiReadsEveryScanInAHistory() {
        val text = """{"isInBodySheet": true, "scans": [
            {"testDate": "2026-04-05", "weightKg": 59.1, "skeletalMuscleMassKg": 25.9, "percentBodyFat": 22.5},
            {"testDate": "2026-08-23", "weightKg": 57.3, "skeletalMuscleMassKg": 25.7, "percentBodyFat": 19.6, "bmrKcal": 1366},
            {"testDate": "2026-08-23", "weightKg": 57.3},
            {"testDate": null, "weightKg": 50.0}
        ]}"""
        val x = LabExtractor.parseInBody(text, id = "t", today = java.time.LocalDate.of(2026, 11, 21), now = 0)
        assertTrue(x.isInBody)
        assertEquals("undated rows dropped, one per date", 2, x.scans.size)
        assertEquals("2026-04-05", x.scans.first().date)
        assertEquals("keeps the fuller entry for a date", 1366.0, x.scan.bmrKcal!!, 1e-9)
        assertTrue(x.scans.map { it.id }.distinct().size == 2)
    }

    @Test fun mergeKeepsOnePerDateAndPrefersCsv() {
        val csv = InBodyScan("inbody-csv-1", "2026-08-23", weightKg = 57.3, source = "csv")
        val ai = InBodyScan("ai-1", "2026-08-23", weightKg = 57.0, source = "ai")
        val other = InBodyScan("ai-2", "2026-07-25", weightKg = 58.0, source = "ai")
        val m1 = mergeScans(listOf(csv), listOf(ai, other))
        assertEquals(1, m1.kept); assertEquals(1, m1.added)
        assertEquals(57.3, m1.scans.first { it.date == "2026-08-23" }.weightKg!!, 1e-9)
        val m2 = mergeScans(listOf(ai), listOf(csv))
        assertEquals(1, m2.replaced)
        assertEquals("csv", m2.scans.single().source)
    }
}
