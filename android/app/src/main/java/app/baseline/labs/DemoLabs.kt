package app.baseline.labs

import java.time.LocalDate

/**
 * Synthetic lab reports and InBody scans for demo mode — the same fictional person as DemoData, never real
 * results. Built from canonical biomarkers so the screens, trends and ranges look like real use.
 */
object DemoLabs {
    private data class Row(val key: String, val v: List<Double>, val lo: Double?, val hi: Double?)

    // Three reports: ~11 months ago, ~5 months ago, ~3 weeks ago. Vitamin D was low, then supplemented;
    // LDL and ApoB a little high and drifting down; everything else steady.
    private val rows = listOf(
        Row("hba1c", listOf(5.6, 5.5, 5.4), 4.0, 5.6),
        Row("glucose_fasting", listOf(97.0, 94.0, 91.0), 70.0, 99.0),
        Row("cholesterol_total", listOf(214.0, 201.0, 189.0), null, 200.0),
        Row("ldl", listOf(138.0, 126.0, 117.0), null, 100.0),
        Row("hdl", listOf(46.0, 49.0, 52.0), 40.0, null),
        Row("triglycerides", listOf(152.0, 128.0, 104.0), null, 150.0),
        Row("apob", listOf(104.0, 97.0, 91.0), null, 90.0),
        Row("hscrp", listOf(1.9, 1.2, 0.8), null, 3.0),
        Row("vitamin_d", listOf(17.0, 34.0, 41.0), 30.0, 100.0),
        Row("vitamin_b12", listOf(268.0, 352.0, 410.0), 211.0, 911.0),
        Row("ferritin", listOf(118.0, 104.0, 96.0), 30.0, 400.0),
        Row("haemoglobin", listOf(14.6, 14.9, 15.1), 13.0, 17.0),
        Row("platelets", listOf(238.0, 251.0, 246.0), 150.0, 410.0),
        Row("tsh", listOf(2.1, 2.4, 1.9), 0.4, 4.2),
        Row("creatinine", listOf(1.02, 0.98, 1.05), 0.7, 1.3),
        Row("egfr", listOf(94.0, 98.0, 92.0), 90.0, null),
        Row("uric_acid", listOf(6.9, 6.4, 6.1), 3.5, 7.2),
        Row("alt", listOf(41.0, 33.0, 27.0), null, 45.0),
        Row("ast", listOf(29.0, 26.0, 24.0), null, 40.0),
    )

    private val ago = listOf(330L, 150L, 21L)
    private val labs = listOf("Sample Diagnostics", "Sample Diagnostics", "Example Labs")

    fun data(today: LocalDate = LocalDate.now()): LabsData {
        val reports = ago.mapIndexed { i, days ->
            val date = today.minusDays(days).toString()
            LabReport(
                id = "demo-report-$i", date = date, lab = labs[i], addedAt = 0,
                markers = rows.mapNotNull { r ->
                    val def = Biomarkers.byId(r.key) ?: return@mapNotNull null
                    val v = r.v[i]
                    LabMarker(
                        key = def.id, name = def.name, value = v, unit = def.unit, refLow = r.lo, refHigh = r.hi,
                        flag = Normalizer.flagFor(v, r.lo, r.hi, null).name, category = def.category.name, collectedDate = date,
                    )
                },
            )
        }
        val scans = listOf(
            InBodyScan(
                "demo-scan-0", today.minusDays(120).toString(), weightKg = 79.4, skeletalMuscleKg = 33.1, bodyFatKg = 18.9,
                percentBodyFat = 23.8, bmi = 25.1, visceralFatLevel = 9.0, bmrKcal = 1685.0, totalBodyWaterL = 44.2, ecwTbw = 0.382, inBodyScore = 74.0,
            ),
            InBodyScan(
                "demo-scan-1", today.minusDays(62).toString(), weightKg = 78.5, skeletalMuscleKg = 33.6, bodyFatKg = 17.3,
                percentBodyFat = 22.0, bmi = 24.8, visceralFatLevel = 8.0, bmrKcal = 1702.0, totalBodyWaterL = 44.8, ecwTbw = 0.380, inBodyScore = 77.0,
            ),
            InBodyScan(
                "demo-scan-2", today.minusDays(14).toString(), weightKg = 77.9, skeletalMuscleKg = 34.2, bodyFatKg = 15.9,
                percentBodyFat = 20.4, bmi = 24.6, visceralFatLevel = 7.0, bmrKcal = 1721.0, totalBodyWaterL = 45.5, ecwTbw = 0.379, inBodyScore = 80.0,
                segmentalLean = listOf(
                    Segment("rightArm", 3.6, 104.0), Segment("leftArm", 3.5, 102.0), Segment("trunk", 26.8, 101.0),
                    Segment("rightLeg", 9.4, 98.0), Segment("leftLeg", 9.3, 97.0),
                ),
                segmentalFat = listOf(
                    Segment("rightArm", 0.9, 118.0), Segment("leftArm", 0.9, 120.0), Segment("trunk", 8.1, 152.0),
                    Segment("rightLeg", 2.6, 109.0), Segment("leftLeg", 2.6, 110.0),
                ),
            ),
        )
        val notes = mapOf(
            LabAi.reportKey(reports.last()) to AiNote(
                "Overall this is a reassuring test: almost everything sits inside its range, and several results have moved the right way over the year. " +
                    "LDL cholesterol (117 mg/dL) and ApoB (91 mg/dL) are still a touch above their ranges, but both have fallen steadily since last year, alongside better triglycerides and HDL. " +
                    "Vitamin D has climbed from 17 to 41 ng/mL, so whatever you changed is working. " +
                    "Keeping up fibre, regular training and fewer refined carbs should keep the cholesterol trend going, and it's worth asking your doctor at your next visit what LDL target makes sense for you.",
                "sample", sample = true,
            ),
            LabAi.inBodyKey(scans.last()) to AiNote(
                "Your latest scan shows a steady recomposition: about 1.1 kg more skeletal muscle and 3 kg less fat mass than four months ago, with weight down only 1.5 kg. " +
                    "Body fat has gone from 23.8% to 20.4% and the visceral fat level from 9 to 7, which is the direction that matters most. " +
                    "Legs are slightly behind arms and trunk for lean mass, so keeping two lower-body strength sessions a week is a good focus. " +
                    "Scan at the same time of day, before eating or training, so the next comparison is fair.",
                "sample", sample = true,
            ),
        )
        return LabsData(reports = reports, scans = scans, consent = true, notes = notes)
    }
}
