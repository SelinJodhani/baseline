package app.baseline.labs

import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Phenotypic Age (Levine et al., Aging 2018;10:573–591): a blood-based biological age trained to predict
 * 10-year mortality in NHANES III and validated in NHANES IV. Nine routine markers plus chronological age:
 *
 *     xb  = −19.907 − 0.0336·albumin(g/L) + 0.0095·creatinine(µmol/L) + 0.1953·glucose(mmol/L)
 *           + 0.0954·ln CRP(mg/dL) − 0.0120·lymphocyte% + 0.0268·MCV(fL) + 0.3306·RDW(%)
 *           + 0.00188·ALP(U/L) + 0.0554·WBC(10³/µL) + 0.0804·age
 *     M   = 1 − exp(−exp(xb)·(exp(120·γ) − 1)/γ),  γ = 0.0076927
 *     age = 141.50225 + ln(−0.00553·ln(1 − M)) / 0.090165
 */
object PhenoAge {
    private const val GAMMA = 0.0076927

    /** The markers it needs, as Baseline's canonical ids and units. */
    enum class Input(val id: String, val label: String) {
        Albumin("albumin", "Albumin"), Creatinine("creatinine", "Creatinine"), Glucose("glucose_fasting", "Fasting glucose"),
        Crp("hscrp", "CRP"), Lymphocytes("lymphocytes_pct", "Lymphocytes %"), Mcv("mcv", "MCV"), Rdw("rdw", "RDW"),
        Alp("alp", "ALP"), Wbc("wbc", "White blood cells"),
    }

    /** Per-marker linear terms (in the paper's units), from Baseline's canonical units. */
    private fun term(i: Input, v: Double): Double = when (i) {
        Input.Albumin -> -0.0336 * (v * 10)                 // g/dL → g/L
        Input.Creatinine -> 0.0095 * (v * 88.4)             // mg/dL → µmol/L
        Input.Glucose -> 0.1953 * (v / 18.016)              // mg/dL → mmol/L
        Input.Crp -> 0.0954 * ln(max(v / 10, 0.01))         // mg/L → mg/dL; floor below detection
        Input.Lymphocytes -> -0.0120 * v
        Input.Mcv -> 0.0268 * v
        Input.Rdw -> 0.3306 * v
        Input.Alp -> 0.00188 * v
        Input.Wbc -> 0.0554 * v
    }

    fun compute(values: Map<Input, Double>, age: Double): Double? {
        if (!values.keys.containsAll(Input.entries)) return null
        val xb = -19.907 + Input.entries.sumOf { term(it, values.getValue(it)) } + 0.0804 * age
        val m = 1 - exp(-exp(xb) * (exp(120 * GAMMA) - 1) / GAMMA)
        if (m <= 0 || m >= 1) return null
        return 141.50225 + ln(-0.00553 * ln(1 - m)) / 0.090165
    }

    /** How many years each marker adds (+) or takes off (−) compared with a typical healthy value. */
    val REFERENCE = mapOf(
        Input.Albumin to 4.5, Input.Creatinine to 0.9, Input.Glucose to 90.0, Input.Crp to 1.0, Input.Lymphocytes to 30.0,
        Input.Mcv to 90.0, Input.Rdw to 13.0, Input.Alp to 70.0, Input.Wbc to 6.0,
    )

    sealed interface Outcome

    data class Result(
        val phenoAge: Double,
        val age: Double,
        val date: LocalDate,
        val values: Map<Input, Double>,
        /** Marker → years it moves Phenotypic Age versus [REFERENCE], holding the others at your values. */
        val effects: Map<Input, Double>,
    ) : Outcome {
        val gap: Double get() = phenoAge - age
    }

    /** No report has all nine markers; [missing] is what the most complete one lacks. */
    data class Missing(val date: LocalDate, val missing: List<Input>) : Outcome

    /** A complete set exists, but Phenotypic Age needs your birth year. */
    data class NeedsAge(val date: LocalDate) : Outcome

    /**
     * The newest report (or set of reports within 30 days of it) that has all nine markers in canonical units.
     * Returns [Missing] with what's absent when no complete set exists.
     */
    fun fromLabs(data: LabsData, birthYear: Int?): Outcome? {
        val reports = data.reportsByDate.asReversed()
        if (reports.isEmpty()) return null
        var best: Missing? = null
        for (anchor in reports) {
            val d0 = anchor.localDate
            val pool = reports.filter { kotlin.math.abs(it.localDate.toEpochDay() - d0.toEpochDay()) <= 30 }
                .sortedBy { kotlin.math.abs(it.localDate.toEpochDay() - d0.toEpochDay()) }
            val values = mutableMapOf<Input, Double>()
            for (r in pool) for (m in r.markers) {
                val id = canonicalId(m) ?: continue
                Input.entries.firstOrNull { it.id == id }?.let { values.putIfAbsent(it, m.value) }
            }
            val missing = Input.entries.filter { it !in values }
            if (missing.isEmpty()) {
                val age = birthYear?.let { (d0.year - it).toDouble() } ?: return NeedsAge(d0)
                val p = compute(values, age) ?: return null
                val effects = Input.entries.associateWith { i ->
                    p - (compute(values + (i to REFERENCE.getValue(i)), age) ?: p)
                }
                return Result(p, age, d0, values, effects)
            }
            if (best == null || missing.size < best.missing.size) best = Missing(d0, missing)
        }
        return best
    }

    /** A stored marker's canonical id when it's in canonical units (older reports may hold a slug). */
    private fun canonicalId(m: LabMarker): String? {
        if ('@' in m.key) return null
        Biomarkers.byId(m.key)?.let { return it.id }
        val def = Biomarkers.match(m.name) ?: return null
        return def.id.takeIf { Biomarkers.toCanonical(def, 1.0, m.unit) == 1.0 }
    }
}
