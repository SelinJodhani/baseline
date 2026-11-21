package app.baseline.data

import app.baseline.insight.InsightEngine
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId

/** Synthetic fixtures only — never commit a real export. */
class ZeppExportTest {
    private val ist = ZoneId.of("Asia/Kolkata")

    private val activity = "﻿date,steps,distance,runDistance,calories\n" +
        "2026-01-09,0,0,0,0\n" +
        "2026-01-10,7805,5507,0,271\n" +
        "2026-01-11,9000,6400,0,300\n"

    // Night of 10→11 Jan: 22:00–06:00 IST = 16:30–00:30 UTC.
    private val sleep = "﻿date,deepSleepTime,shallowSleepTime,wakeTime,start,stop,REMTime,naps\n" +
        "2026-01-10,0,0,0,2026-01-09 18:30:00+0000,2026-01-09 18:30:00+0000,0,\n" +
        "2026-01-11,90,300,5,2026-01-10 16:30:00+0000,2026-01-11 00:30:00+0000,85," +
        "\"[{\\\"start\\\":\\\"2024-10-04 00:38:34+0000\\\",\\\"end\\\":\\\"2024-10-04 00:38:54+0000\\\"}]\"\n"

    /** 8 h of minutes from 22:00 IST: light, then 2 h deep at a low HR, then REM. */
    private val sleepMinute = buildString {
        append("﻿date,time,stage,hr,respiratory_rate\n")
        var t = java.time.LocalDateTime.of(2026, 1, 10, 22, 0)
        repeat(480) { i ->
            val stage = when { i < 120 -> "LIGHT"; i < 240 -> "DEEP"; else -> "REM" }
            val hr = when { i < 120 -> 62; i < 240 -> 50 + (i % 3); else -> 58 }
            append("${t.toLocalDate()},${"%02d:%02d".format(t.hour, t.minute)},$stage,$hr,\n")
            t = t.plusMinutes(1)
        }
    }

    private val sport = "﻿type,startTime,sportTime(s),maxPace(/meter),minPace(/meter),distance(m),avgPace(/meter),calories(kcal)\n" +
        "1,2026-01-10 01:00:00+0000,1800,1.9,0.2,5000.0,0.36,400.0\n" +
        "52,2026-01-11 03:00:00+0000,3600,0.0,0.0,0.0,0.0,450.0\n" +
        "92,2026-01-11 10:00:00+0000,3000,0.0,0.0,0.0,0.0,700.0\n"

    private val body = "﻿time,weight,height,bmi,fatRate,bodyWaterRate,boneMass,metabolism,muscleRate,visceralFat\n" +
        "2026-01-10 03:00:00+0000,61.0,168.0,21.6,null,null,null,null,null,null\n"

    private fun zip(password: String?): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, password?.toCharArray()).use { z ->
            mapOf(
                "ACTIVITY/ACTIVITY_1.csv" to activity, "SLEEP/SLEEP_1.csv" to sleep, "SLEEP_MINUTE/SLEEP_MINUTE_1.csv" to sleepMinute,
                "SPORT/SPORT_1.csv" to sport, "BODY/BODY_1.csv" to body, "USER/USER_1.csv" to "userId,nickName\n1,Someone\n",
            ).forEach { (name, text) ->
                val p = ZipParameters().apply {
                    fileNameInZip = name
                    if (password != null) { isEncryptFiles = true; encryptionMethod = EncryptionMethod.AES; aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256 }
                }
                z.putNextEntry(p); z.write(text.toByteArray()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun read(pw: String?, zipPw: String? = pw) = ZeppExport.read(ByteArrayInputStream(zip(zipPw)), pw, ist, now = 1)

    @Test fun readsEncryptedExport() {
        val a = read("secret")
        assertEquals(listOf("2026-01-10", "2026-01-11"), a.days.map { it.date }) // unworn 9 Jan skipped
        assertEquals(7805.0, a.days[0].steps)
        assertEquals(271.0, a.days[0].activeCalories)
        assertEquals(1, a.sleeps.size) // empty placeholder night skipped
        val s = a.sleeps[0]
        assertEquals(475, s.durationMin)
        assertEquals(listOf("light", "deep", "rem"), s.stageSpans.map { it.third })
        assertEquals(50.8, a.days.first { it.date == "2026-01-11" }.sleepRhr!!, 0.21) // lowest 5-min average in the deep block
        assertEquals(listOf("running", "strength_training", "workout"), a.workouts.map { it.type })
        assertEquals(61.0, a.weights.single().kg, 0.0)
    }

    @Test fun plainZipNeedsNoPassword() = assertEquals(2, read(null).days.size)

    @Test(expected = ExportException::class) fun wrongPasswordIsFriendly() { read("nope", zipPw = "secret") }

    @Test(expected = ExportException::class) fun rejectsOtherZips() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipParameters().apply { fileNameInZip = "notes.txt" }); z.write("hi".toByteArray()); z.closeEntry() }
        ZeppExport.read(ByteArrayInputStream(out.toByteArray()), null, ist)
    }

    @Test fun bridgeWinsAndArchiveFillsGaps() {
        val a = read("secret")
        val bridge = Summary(
            days = listOf(Day("2026-01-11", steps = 12000.0, sleepHrv = 70.0), Day("2026-01-12", steps = 5000.0)),
            sleeps = listOf(Sleep(start = a.sleeps[0].start + 600, end = a.sleeps[0].end, durationMin = 420)),
            workouts = listOf(Workout(id = "b1", type = "strength_training", start = a.workouts[1].start + 120)),
        )
        val m = bridge.withArchive(a)
        assertEquals(listOf("2026-01-10", "2026-01-11", "2026-01-12"), m.days.map { it.date })
        val d11 = m.days[1]
        assertEquals(12000.0, d11.steps) // bridge wins
        assertEquals(70.0, d11.sleepHrv)
        assertNotNull(d11.sleepRhr) // filled from the archive
        assertEquals(1, m.sleeps.size) // overlapping archive night dropped
        assertEquals(420, m.sleeps[0].durationMin)
        assertEquals(listOf("running", "strength_training", "workout"), m.workouts.map { it.type }) // duplicate strength dropped
        assertEquals("b1", m.workouts[1].id)
        assertEquals(1, m.weights.size)
        assertNull(bridge.withArchive(null).days.firstOrNull { it.date == "2026-01-10" })
    }

    /** Local check against a real export: BASELINE_EXPORT_ZIP=/path BASELINE_EXPORT_PW=… (never in CI). */
    @Test fun realExportIfProvided() {
        val path = System.getenv("BASELINE_EXPORT_ZIP")
        assumeTrue(path != null && File(path).exists())
        val t0 = System.currentTimeMillis()
        val a = File(path!!).inputStream().use { ZeppExport.read(it, System.getenv("BASELINE_EXPORT_PW"), ist) }
        val ms = System.currentTimeMillis() - t0
        val rhr = a.days.mapNotNull { it.sleepRhr }
        println("REAL: ${a.days.size} days ${a.from}..${a.to}, ${a.sleeps.size} nights (${a.sleeps.count { it.stages != null }} with stages), " +
            "${a.workouts.size} workouts ${a.workouts.groupingBy { it.type }.eachCount()}, ${a.weights.size} weights, " +
            "rhr n=${rhr.size} min=${rhr.minOrNull()} median=${rhr.sorted().getOrNull(rhr.size / 2)} max=${rhr.maxOrNull()}, " +
            "json=${Summary.json.encodeToString(Archive.serializer(), a).length / 1024} KB, read in $ms ms")
        assertTrue(a.days.size > 100)
        val merged = Summary(days = emptyList()).withArchive(a)
        val i = InsightEngine(merged, AppSettings(), java.time.Instant.parse("2026-11-15T12:00:00Z")).compute()
        println("REAL insights: rhr baseline=${i.rhr.baseline} readiness=${i.readiness}")
    }
}
