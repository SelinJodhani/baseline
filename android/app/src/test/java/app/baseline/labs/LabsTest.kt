package app.baseline.labs

import app.baseline.ai.Gemini
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Synthetic values only. */
class LabsTest {
    private fun raw(name: String, value: Double?, unit: String?, lo: Double? = null, hi: Double? = null, canonical: String? = "other", flag: String? = null) =
        RawBiomarker(name, canonical, value, unit, lo, hi, flag, null, null)

    @Test fun namesMatchAliases() {
        assertEquals("hba1c", Biomarkers.match("HbA1c (Glycated Haemoglobin)")?.id)
        assertEquals("hba1c", Biomarkers.match("Glycosylated Hemoglobin")?.id)
        assertEquals("ldl", Biomarkers.match("LDL Cholesterol - Direct")?.id)
        assertEquals("ldl", Biomarkers.match("LDL-C")?.id)
        assertEquals("hdl", Biomarkers.match("HDL Cholesterol")?.id)
        assertEquals("cholesterol_total", Biomarkers.match("Serum Cholesterol")?.id)
        assertEquals("glucose_fasting", Biomarkers.match("Fasting Blood Sugar (FBS)")?.id)
        assertEquals("vitamin_d", Biomarkers.match("25-OH Vitamin D")?.id)
        assertEquals("vitamin_b12", Biomarkers.match("Vitamin B12")?.id)
        assertEquals("alt", Biomarkers.match("SGPT")?.id)
        assertEquals("ast", Biomarkers.match("AST (SGOT)")?.id)
        assertEquals("hscrp", Biomarkers.match("hs-CRP")?.id)
        assertEquals("tsh", Biomarkers.match("TSH Ultrasensitive")?.id)
        assertEquals("egfr", Biomarkers.match("eGFR")?.id)
        assertEquals("apob", Biomarkers.match("Apolipoprotein B")?.id)
        assertEquals("haemoglobin", Biomarkers.match("Hemoglobin")?.id)
        // Post-meal glucose must not be filed as fasting glucose.
        assertNull(Biomarkers.match("Glucose, Post Prandial"))
    }

    @Test fun unitsConvertToCanonical() {
        val glucose = Normalizer.normalize(raw("Fasting glucose", 5.4, "mmol/L", 3.9, 5.5))!!
        assertEquals("glucose_fasting", glucose.key)
        assertEquals("mg/dL", glucose.unit)
        assertEquals(97.3, glucose.value, 0.1)
        assertEquals(70.3, glucose.refLow!!, 0.1)

        val ldl = Normalizer.normalize(raw("LDL cholesterol", 3.0, "mmol/L", hi = 2.6))!!
        assertEquals(116.0, ldl.value, 0.1)
        assertEquals(LabFlag.High.name, ldl.flag)

        val vitD = Normalizer.normalize(raw("Vitamin D", 75.0, "nmol/L"))!!
        assertEquals("ng/mL", vitD.unit)
        assertEquals(30.05, vitD.value, 0.05)

        val a1c = Normalizer.normalize(raw("HbA1c", 39.0, "mmol/mol"))!!
        assertEquals("%", a1c.unit)
        assertEquals(5.72, a1c.value, 0.02)

        val b12 = Normalizer.normalize(raw("Vitamin B12", 300.0, "pmol/L"))!!
        assertEquals(406.5, b12.value, 0.5)

        val creat = Normalizer.normalize(raw("Creatinine", 88.4, "µmol/L"))!!
        assertEquals(1.0, creat.value, 0.001)

        val hb = Normalizer.normalize(raw("Haemoglobin", 145.0, "g/L"))!!
        assertEquals(14.5, hb.value, 0.001)

        val tsh = Normalizer.normalize(raw("TSH", 2.1, "µIU/mL"))!!
        assertEquals("mIU/L", tsh.unit)
        assertEquals(2.1, tsh.value, 0.0)

        val plt = Normalizer.normalize(raw("Platelet count", 2.5, "lakhs/cumm"))!!
        assertEquals(250.0, plt.value, 0.001)
        val wbc = Normalizer.normalize(raw("Total Leucocyte Count", 7200.0, "cells/cumm"))!!
        assertEquals(7.2, wbc.value, 0.001)

        val alt = Normalizer.normalize(raw("ALT", 30.0, "IU/L"))!!
        assertEquals("U/L", alt.unit)
    }

    @Test fun canonicalFromModelWinsAndUnknownsKeepTheirUnit() {
        val m = Normalizer.normalize(raw("Glucose (F)", 92.0, "mg/dl", canonical = "glucose_fasting"))!!
        assertEquals("glucose_fasting", m.key)

        val unknown = Normalizer.normalize(raw("Homocysteine", 11.2, "µmol/L", 5.0, 15.0))!!
        assertEquals("x_homocysteine", unknown.key)
        assertEquals("µmol/L", unknown.unit)
        assertEquals(LabFlag.Normal.name, unknown.flag)

        // Known test, unit we can't convert: kept apart so trends never mix units.
        val odd = Normalizer.normalize(raw("Ferritin", 50.0, "furlongs"))!!
        assertTrue(odd.key.startsWith("ferritin@"))

        assertNull(Normalizer.normalize(raw("Urine colour", null, null)))
    }

    @Test fun flagsComeFromTheRange() {
        assertEquals(LabFlag.Low, Normalizer.flagFor(25.0, 30.0, 100.0, "normal"))
        assertEquals(LabFlag.Normal, Normalizer.flagFor(45.0, 30.0, 100.0, "high"))
        assertEquals(LabFlag.High, Normalizer.flagFor(120.0, null, 100.0, null))
        assertEquals(LabFlag.High, Normalizer.flagFor(1.0, null, null, "H"))
        assertEquals(LabFlag.Unknown, Normalizer.flagFor(1.0, null, null, null))
    }

    @Test fun parsesLabResponse() {
        val text = """
            {"isLabReport": true, "reportDate": "2026-10-03", "labName": "Example Labs",
             "biomarkers": [
               {"name": "HbA1c", "canonicalName": "hba1c", "value": 5.4, "unit": "%", "refLow": 4.0, "refHigh": 5.6, "flag": "normal", "category": "Metabolic", "collectedDate": "2026-10-03"},
               {"name": "Fasting Plasma Glucose", "canonicalName": "glucose_fasting", "value": 5.1, "unit": "mmol/L", "refLow": 3.9, "refHigh": 5.5, "flag": "normal", "category": "Metabolic", "collectedDate": null},
               {"name": "LDL Cholesterol (Direct)", "canonicalName": "other", "value": 131, "unit": "mg/dL", "refLow": null, "refHigh": 100, "flag": "high", "category": "Lipids", "collectedDate": null},
               {"name": "Vitamin D, 25-Hydroxy", "canonicalName": "vitamin_d", "value": "18.2", "unit": "ng/mL", "refLow": 30, "refHigh": 100, "flag": "low", "category": "Vitamins", "collectedDate": null},
               {"name": "Remarks", "canonicalName": "other", "value": null, "unit": null, "refLow": null, "refHigh": null, "flag": "unknown", "category": "Other", "collectedDate": null},
               {"name": "HbA1c", "canonicalName": "hba1c", "value": 5.4, "unit": "%", "refLow": 4.0, "refHigh": 5.6, "flag": "normal", "category": "Metabolic", "collectedDate": null}
             ]}
        """.trimIndent()
        val x = LabExtractor.parseLab(text)
        assertTrue(x.isLabReport)
        assertEquals(LocalDate.of(2026, 10, 3), x.date)
        assertEquals("Example Labs", x.lab)
        assertEquals(4, x.markers.size) // null value dropped, duplicate removed
        val byKey = x.markers.associateBy { it.key }
        assertEquals(91.9, byKey.getValue("glucose_fasting").value, 0.1)
        assertEquals(LabFlag.High.name, byKey.getValue("ldl").flag)
        assertEquals(LabCategory.Lipids.name, byKey.getValue("ldl").category)
        assertEquals(18.2, byKey.getValue("vitamin_d").value, 0.0)
        assertEquals(LabFlag.Low.name, byKey.getValue("vitamin_d").flag)
    }

    @Test fun parsesFencedJsonAndNotAReport() {
        val x = LabExtractor.parseLab("```json\n{\"isLabReport\": false, \"reportDate\": null, \"labName\": null, \"biomarkers\": []}\n```")
        assertFalse(x.isLabReport)
        assertTrue(x.markers.isEmpty())
        assertNull(x.date)
    }

    @Test fun parsesInBodyResponse() {
        val text = """
            {"isInBodySheet": true, "testDate": "2026-11-02", "weightKg": 77.9, "skeletalMuscleMassKg": 34.2,
             "bodyFatMassKg": 15.9, "percentBodyFat": 20.4, "bmi": 24.6, "visceralFatLevel": 7, "bmrKcal": 7201,
             "totalBodyWaterL": 45.5, "ecwTbwRatio": 0.379, "inBodyScore": 80,
             "segmentalLean": [{"part": "rightArm", "kg": 3.6, "percent": 104.0}, {"part": "elbow", "kg": 1, "percent": 1}],
             "segmentalFat": []}
        """.trimIndent()
        val x = LabExtractor.parseInBody(text, id = "t", today = LocalDate.of(2026, 11, 21), now = 0)
        assertTrue(x.isInBody)
        assertEquals("2026-11-02", x.scan.date)
        assertEquals(34.2, x.scan.skeletalMuscleKg!!, 0.0)
        assertEquals(1721.0, x.scan.bmrKcal!!, 1.0) // kJ slipped through → converted to kcal
        assertEquals(1, x.scan.segmentalLean.size) // unknown segment dropped

        val empty = LabExtractor.parseInBody("""{"isInBodySheet": true, "testDate": null}""", today = LocalDate.of(2026, 11, 21))
        assertFalse(empty.isInBody)
        assertEquals("2026-11-21", empty.scan.date)
    }

    @Test fun datesParseCommonFormats() {
        assertEquals(LocalDate.of(2026, 3, 7), LabDates.parse("2026-03-07"))
        assertEquals(LocalDate.of(2026, 3, 7), LabDates.parse("07/03/2026"))
        assertEquals(LocalDate.of(2026, 3, 7), LabDates.parse("07-Mar-2026"))
        assertEquals(LocalDate.of(2026, 3, 7), LabDates.parse("2026-03-07T09:30:00"))
        assertNull(LabDates.parse("soon"))
    }

    @Test fun latestAndHistoryAcrossReports() {
        val d = DemoLabs.data(LocalDate.of(2026, 11, 21))
        assertEquals(3, d.reports.size)
        assertEquals(3, d.scans.size)
        val ldl = d.history("ldl")
        assertEquals(listOf(138.0, 126.0, 117.0), ldl.map { it.second.value })
        val latest = d.latestMarkers().first { it.second.key == "vitamin_d" }
        assertEquals(41.0, latest.second.value, 0.0)
        assertEquals(LabFlag.Normal, latest.second.labFlag)
        assertTrue(LabAi.reportPrompt(d, d.reportsByDate.last()).contains("previously 126"))
    }

    @Test fun geminiRequestCarriesFileAndSchema() {
        val body = Gemini.request(
            system = "sys", parts = listOf(Gemini.Part.File("application/pdf", byteArrayOf(1, 2, 3)), Gemini.Part.Text("read")),
            schema = LabExtractor.labSchema, temperature = 0.0, maxOutputTokens = 100,
        )
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        val inline = parts[0].jsonObject["inlineData"]!!.jsonObject
        assertEquals("application/pdf", inline["mimeType"]!!.jsonPrimitive.content)
        assertEquals("AQID", inline["data"]!!.jsonPrimitive.content)
        val cfg = body["generationConfig"]!!.jsonObject
        assertEquals("application/json", cfg["responseMimeType"]!!.jsonPrimitive.content)
        assertNotNull(cfg["responseJsonSchema"]!!.jsonObject["properties"]!!.jsonObject["biomarkers"])
        assertEquals("sys", body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun geminiResponseText() {
        val ok = """{"candidates":[{"content":{"parts":[{"text":"Hello "},{"text":"there."}]},"finishReason":"STOP"}]}"""
        assertEquals("Hello there.", Gemini.parseText(ok))
        val cut = """{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}"""
        val err = runCatching { Gemini.parseText(cut) }.exceptionOrNull()
        assertTrue(err?.message.orEmpty().contains("ran out of room"))
        // Schema survives a round trip as valid JSON.
        Json.parseToJsonElement(LabExtractor.inBodySchema.toString())
    }
}
