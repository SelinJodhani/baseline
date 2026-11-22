package app.baseline.labs

/**
 * Prompts for AI explanations of lab results and InBody scans. Only values, units, reference ranges and
 * month-level dates are sent — never names, IDs, lab addresses or the document itself.
 */
object LabAi {
    const val SYSTEM = "You are a calm, practical health guide inside a personal health app. You help someone understand " +
        "their own test results using only the values you are given. Write plain text in the second person: no lists, " +
        "headings, markdown or emoji. Use everyday words and explain any term you must use. Never diagnose, never name a " +
        "disease the person might have, and never suggest starting, stopping or changing medicine. Mention ordinary " +
        "things that move results (fasting, hydration, recent exercise, illness, supplements, time of day, lab-to-lab " +
        "differences) where relevant. A result just outside the range is often not meaningful on its own; say so when " +
        "true. When something is clearly out of range or keeps moving the wrong way, suggest discussing it with their " +
        "doctor. Don't invent numbers that aren't given."

    private fun range(m: LabMarker) = when {
        m.refLow != null && m.refHigh != null -> "range ${fmtNum(m.refLow)}–${fmtNum(m.refHigh)}"
        m.refHigh != null -> "range below ${fmtNum(m.refHigh)}"
        m.refLow != null -> "range above ${fmtNum(m.refLow)}"
        else -> "no range printed"
    }

    private fun line(m: LabMarker) = "${m.name}: ${fmtNum(m.value)} ${m.unit} (${range(m)}; ${m.status.name.lowercase()})".replace("  ", " ")

    fun markerPrompt(data: LabsData, key: String): String = buildString {
        val h = data.history(key)
        val latest = h.lastOrNull()?.second ?: return@buildString
        appendLine("In 4 to 6 short sentences: what ${latest.name} is, what my latest result means against the reference range and my own history, what commonly moves it, and one or two practical things I could do or ask about.")
        appendLine()
        appendLine("Latest: ${line(latest)}, ${LabDates.monthYear(h.last().first.date)}.")
        if (h.size > 1) {
            appendLine("Earlier results:")
            h.dropLast(1).asReversed().take(6).forEach { (r, m) -> appendLine("- ${LabDates.monthYear(r.date)}: ${fmtNum(m.value)} ${m.unit}") }
        }
    }

    fun reportPrompt(data: LabsData, report: LabReport): String = buildString {
        appendLine("Here is my latest blood test. In 4 to 6 short sentences: start with the overall picture, then the two or three results that stand out most (out of range, or changed a lot since last time), then one or two practical next steps, including anything worth raising with a doctor. If everything is in range, say so plainly.")
        appendLine()
        appendLine("Test from ${LabDates.monthYear(report.date)}:")
        val previous = data.reportsByDate.filter { it.localDate < report.localDate }
        report.markers.sortedBy { it.labCategory.ordinal }.forEach { m ->
            val prev = previous.asReversed().firstNotNullOfOrNull { r -> r.markers.firstOrNull { it.key == m.key }?.let { r to it } }
            append("- ${line(m)}")
            prev?.let { (r, pm) -> append("; previously ${fmtNum(pm.value)} in ${LabDates.monthYear(r.date)}") }
            appendLine()
        }
    }

    fun inBodyPrompt(data: LabsData): String = buildString {
        val scans = data.scansByDate
        val latest = scans.lastOrNull() ?: return@buildString
        appendLine("Here are my body composition scans (bioimpedance, InBody). In 4 to 6 short sentences: what the latest scan says, how it has changed since my first and previous scans, and one or two practical things to focus on (training, protein, sleep). Remember these scans shift with hydration, food and time of day, so small changes may be noise.")
        appendLine()
        fun scanLine(s: InBodyScan) = listOfNotNull(
            s.weightKg?.let { "weight ${fmtNum(it)} kg" },
            s.skeletalMuscleKg?.let { "skeletal muscle ${fmtNum(it)} kg" },
            s.bodyFatKg?.let { "fat mass ${fmtNum(it)} kg" },
            s.percentBodyFat?.let { "body fat ${fmtNum(it)}%" },
            s.visceralFatLevel?.let { "visceral fat level ${fmtNum(it)}" },
            s.bmrKcal?.let { "BMR ${fmtNum(it)} kcal" },
            s.ecwTbw?.let { "ECW/TBW ${fmtNum(it)}" },
            s.inBodyScore?.let { "InBody score ${fmtNum(it)}" },
            s.smi?.let { "SMI ${fmtNum(it)} kg/m²" },
            s.proteinKg?.let { "protein ${fmtNum(it)} kg" },
            s.waistHipRatio?.let { "waist-hip ratio ${fmtNum(it)}" },
        ).joinToString(", ")
        appendLine("Latest (${LabDates.monthYear(latest.date)}): ${scanLine(latest)}.")
        if (scans.size > 1) appendLine("Previous (${LabDates.monthYear(scans[scans.size - 2].date)}): ${scanLine(scans[scans.size - 2])}.")
        if (scans.size > 2) appendLine("First (${LabDates.monthYear(scans.first().date)}): ${scanLine(scans.first())}.")
        if (scans.size > 3) appendLine("All scans, oldest first: " + scans.joinToString("; ") { s -> "${LabDates.monthYear(s.date)} ${s.weightKg?.let { "${fmtNum(it)} kg" } ?: "?"}, ${s.percentBodyFat?.let { "${fmtNum(it)}% fat" } ?: "?"}, ${s.skeletalMuscleKg?.let { "${fmtNum(it)} kg muscle" } ?: "?"}" } + ".")
        if (latest.segmentalLean.isNotEmpty()) appendLine("Segmental lean: " + latest.segmentalLean.joinToString(", ") { seg -> "${seg.part} " + listOfNotNull(seg.kg?.let { "${fmtNum(it)} kg" }, seg.percent?.let { "${fmtNum(it)}% of normal" }).joinToString(" ") } + ".")
    }

    fun reportKey(r: LabReport) = "report:${r.id}"
    fun markerKey(key: String, latestReportId: String) = "marker:$key:$latestReportId"
    fun inBodyKey(s: InBodyScan) = "inbody:${s.id}"
}
