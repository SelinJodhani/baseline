package app.baseline.labs

import kotlin.math.round

/** Display groups for lab results, in the order they're shown. */
enum class LabCategory(val label: String) {
    Metabolic("Blood sugar"),
    Lipids("Heart & cholesterol"),
    Inflammation("Inflammation"),
    Vitamins("Vitamins & minerals"),
    Blood("Blood count"),
    Thyroid("Thyroid"),
    Kidney("Kidney"),
    Liver("Liver"),
    Hormones("Hormones"),
    Electrolytes("Electrolytes"),
    Other("Other");

    companion object {
        fun of(s: String?): LabCategory = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: Other
    }
}

enum class LabFlag { Low, Normal, High, Abnormal, Unknown;
    companion object {
        fun of(s: String?): LabFlag = when (s?.trim()?.lowercase()) {
            "low", "l" -> Low
            "normal", "n", "in range" -> Normal
            "high", "h" -> High
            "abnormal", "a" -> Abnormal
            else -> Unknown
        }
    }
}

/** A unit conversion into a biomarker's canonical unit. */
class Conversion(val units: List<String>, val toCanonical: (Double) -> Double)

private fun factor(f: Double, vararg units: String) = Conversion(units.toList()) { it * f }

/** A known biomarker: canonical id, display name, category, canonical unit, the names labs use, and conversions. */
class BiomarkerDef(
    val id: String,
    val name: String,
    val category: LabCategory,
    val unit: String,
    val aliases: List<String>,
    val conversions: List<Conversion> = emptyList(),
    val about: String,
    /** Extra spellings of the canonical unit (already in canonical scale). */
    val sameUnits: List<String> = emptyList(),
)

/**
 * The biomarkers Baseline knows by name, with the units most labs in India, the US and the UK report.
 * Values in other units are converted to the canonical one so trends line up across labs.
 */
object Biomarkers {
    val all: List<BiomarkerDef> = listOf(
        // Blood sugar
        BiomarkerDef(
            "hba1c", "HbA1c", LabCategory.Metabolic, "%",
            listOf("hba1c", "hb a1c", "a1c", "glycated haemoglobin", "glycated hemoglobin", "glycosylated haemoglobin", "glycosylated hemoglobin", "haemoglobin a1c", "hemoglobin a1c"),
            listOf(Conversion(listOf("mmol/mol")) { it / 10.929 + 2.15 }),
            "Your average blood sugar over roughly the last three months.",
            sameUnits = listOf("% of total hb", "%hb", "ngsp%"),
        ),
        BiomarkerDef(
            "glucose_fasting", "Fasting glucose", LabCategory.Metabolic, "mg/dL",
            listOf("fasting glucose", "glucose fasting", "fasting blood glucose", "fasting blood sugar", "blood sugar fasting", "fbs", "fbg", "fasting plasma glucose", "fpg", "plasma glucose fasting", "glucose f"),
            listOf(factor(18.016, "mmol/l")),
            "Blood sugar after not eating for 8 hours or more.",
        ),
        BiomarkerDef(
            "insulin_fasting", "Fasting insulin", LabCategory.Metabolic, "µIU/mL",
            listOf("fasting insulin", "insulin fasting", "insulin"),
            listOf(factor(1 / 6.0, "pmol/l")),
            "How much insulin your body needs to keep fasting blood sugar steady.",
            sameUnits = listOf("uiu/ml", "miu/l", "mu/l"),
        ),
        // Lipids
        BiomarkerDef(
            "cholesterol_total", "Total cholesterol", LabCategory.Lipids, "mg/dL",
            listOf("total cholesterol", "cholesterol total", "cholesterol", "serum cholesterol", "cholesterol serum", "t chol", "tc"),
            listOf(factor(38.67, "mmol/l")),
            "All the cholesterol carried in your blood, good and bad together.",
        ),
        BiomarkerDef(
            "ldl", "LDL cholesterol", LabCategory.Lipids, "mg/dL",
            listOf("ldl", "ldl cholesterol", "ldl c", "ldl-c", "ldl cholesterol direct", "direct ldl", "ldl direct", "low density lipoprotein", "ldl calculated", "cholesterol ldl"),
            listOf(factor(38.67, "mmol/l")),
            "The cholesterol that builds up in artery walls over time. Lower is generally better.",
        ),
        BiomarkerDef(
            "hdl", "HDL cholesterol", LabCategory.Lipids, "mg/dL",
            listOf("hdl", "hdl cholesterol", "hdl c", "hdl-c", "high density lipoprotein", "hdl cholesterol direct", "cholesterol hdl"),
            listOf(factor(38.67, "mmol/l")),
            "The cholesterol that helps clear excess cholesterol away.",
        ),
        BiomarkerDef(
            "non_hdl", "Non-HDL cholesterol", LabCategory.Lipids, "mg/dL",
            listOf("non hdl", "non hdl cholesterol", "non-hdl cholesterol", "non hdl c"),
            listOf(factor(38.67, "mmol/l")),
            "Total cholesterol minus HDL: all the cholesterol that can clog arteries.",
        ),
        BiomarkerDef(
            "triglycerides", "Triglycerides", LabCategory.Lipids, "mg/dL",
            listOf("triglycerides", "triglyceride", "tg", "trigs", "serum triglycerides"),
            listOf(factor(88.57, "mmol/l")),
            "Fat in your blood. Sensitive to recent meals, sugar and alcohol.",
        ),
        BiomarkerDef(
            "apob", "ApoB", LabCategory.Lipids, "mg/dL",
            listOf("apob", "apo b", "apolipoprotein b", "apolipoprotein b100", "apo b100"),
            listOf(factor(100.0, "g/l")),
            "A count of the particles that carry cholesterol into artery walls.",
        ),
        BiomarkerDef(
            "lpa", "Lp(a)", LabCategory.Lipids, "nmol/L",
            listOf("lp(a)", "lpa", "lipoprotein a", "lipoprotein(a)", "lipoprotein little a"),
            emptyList(),
            "A mostly inherited cholesterol particle. One measurement is usually enough for life.",
        ),
        // Inflammation
        BiomarkerDef(
            "hscrp", "hs-CRP", LabCategory.Inflammation, "mg/L",
            listOf("hs crp", "hscrp", "hs-crp", "high sensitivity crp", "high sensitivity c reactive protein", "c reactive protein high sensitivity", "crp hs", "crp", "c reactive protein"),
            listOf(factor(10.0, "mg/dl")),
            "A general marker of inflammation. A recent cold or hard workout can raise it for a few days.",
        ),
        BiomarkerDef(
            "esr", "ESR", LabCategory.Inflammation, "mm/hr",
            listOf("esr", "erythrocyte sedimentation rate", "sed rate"),
            emptyList(),
            "A slow, general marker of inflammation.",
            sameUnits = listOf("mm/h", "mm/1sthr", "mm/1st hr", "mm/1st hour"),
        ),
        // Vitamins & minerals
        BiomarkerDef(
            "vitamin_d", "Vitamin D", LabCategory.Vitamins, "ng/mL",
            listOf("vitamin d", "vitamin d total", "25 oh vitamin d", "25-oh vitamin d", "25 hydroxy vitamin d", "25-hydroxyvitamin d", "25(oh)d", "vitamin d3", "vit d", "vitamin d 25 hydroxy", "total 25 oh vitamin d"),
            listOf(factor(1 / 2.496, "nmol/l")),
            "Made in skin from sunlight and stored in the body. Matters for bones, muscles and immunity.",
        ),
        BiomarkerDef(
            "vitamin_b12", "Vitamin B12", LabCategory.Vitamins, "pg/mL",
            listOf("vitamin b12", "b12", "vit b12", "cobalamin", "cyanocobalamin", "serum b12"),
            listOf(factor(1.355, "pmol/l")),
            "Needed for nerves and red blood cells. Often low on vegetarian diets or with long-term metformin.",
            sameUnits = listOf("ng/l"),
        ),
        BiomarkerDef(
            "folate", "Folate", LabCategory.Vitamins, "ng/mL",
            listOf("folate", "folic acid", "serum folate", "vitamin b9"),
            listOf(factor(1 / 2.266, "nmol/l")),
            "A B vitamin needed to make healthy cells.",
            sameUnits = listOf("ug/l"),
        ),
        BiomarkerDef(
            "ferritin", "Ferritin", LabCategory.Vitamins, "ng/mL",
            listOf("ferritin", "serum ferritin"),
            listOf(factor(1 / 2.247, "pmol/l")),
            "Your iron stores. It also rises with inflammation.",
            sameUnits = listOf("ug/l"),
        ),
        BiomarkerDef(
            "iron", "Iron", LabCategory.Vitamins, "µg/dL",
            listOf("iron", "serum iron"),
            listOf(factor(5.585, "umol/l")),
            "Iron circulating in the blood right now; it swings through the day.",
        ),
        BiomarkerDef(
            "magnesium", "Magnesium", LabCategory.Vitamins, "mg/dL",
            listOf("magnesium", "serum magnesium", "mg"),
            listOf(factor(2.431, "mmol/l")),
            "A mineral used by muscles and nerves.",
        ),
        BiomarkerDef(
            "calcium", "Calcium", LabCategory.Vitamins, "mg/dL",
            listOf("calcium", "serum calcium", "total calcium", "ca"),
            listOf(factor(4.008, "mmol/l")),
            "Mostly about bones, kept in a tight range by your body.",
        ),
        // Blood count
        BiomarkerDef(
            "haemoglobin", "Haemoglobin", LabCategory.Blood, "g/dL",
            listOf("haemoglobin", "hemoglobin", "hb", "hgb"),
            listOf(factor(0.1, "g/l"), factor(1.611, "mmol/l")),
            "The protein in red blood cells that carries oxygen.",
        ),
        BiomarkerDef(
            "haematocrit", "Haematocrit", LabCategory.Blood, "%",
            listOf("haematocrit", "hematocrit", "hct", "pcv", "packed cell volume"),
            listOf(factor(100.0, "l/l")),
            "The share of your blood made up of red cells.",
        ),
        BiomarkerDef(
            "rbc", "Red blood cells", LabCategory.Blood, "million/µL",
            listOf("rbc", "rbc count", "red blood cell count", "red blood cells", "erythrocytes", "total rbc count"),
            emptyList(),
            "How many oxygen-carrying cells are in your blood.",
            sameUnits = listOf("10^6/ul", "x10^6/ul", "mill/cumm", "million/cumm", "10^12/l", "x10^12/l", "mil/ul", "million/ul"),
        ),
        BiomarkerDef(
            "wbc", "White blood cells", LabCategory.Blood, "thousand/µL",
            listOf("wbc", "wbc count", "white blood cell count", "white blood cells", "total leucocyte count", "total leukocyte count", "tlc", "leukocytes"),
            listOf(factor(0.001, "/ul", "cells/ul", "/cumm", "cells/cumm", "/mm3")),
            "Your immune cells. They rise with infection and fall with some illnesses and medicines.",
            sameUnits = listOf("10^3/ul", "x10^3/ul", "10^9/l", "x10^9/l", "thou/ul", "thousand/ul", "k/ul"),
        ),
        BiomarkerDef(
            "platelets", "Platelets", LabCategory.Blood, "thousand/µL",
            listOf("platelets", "platelet count", "plt"),
            listOf(factor(0.001, "/ul", "cells/ul", "/cumm", "cells/cumm", "/mm3"), factor(100.0, "lakh/cumm", "lakhs/cumm", "lakh/ul")),
            "Cells that help blood clot.",
            sameUnits = listOf("10^3/ul", "x10^3/ul", "10^9/l", "x10^9/l", "thou/ul", "thousand/ul", "k/ul"),
        ),
        BiomarkerDef(
            "mcv", "MCV", LabCategory.Blood, "fL",
            listOf("mcv", "mean corpuscular volume", "mean cell volume"),
            emptyList(),
            "The average size of your red blood cells.",
        ),
        // Thyroid
        BiomarkerDef(
            "tsh", "TSH", LabCategory.Thyroid, "mIU/L",
            listOf("tsh", "thyroid stimulating hormone", "tsh ultrasensitive", "ultrasensitive tsh", "tsh 3rd generation", "thyrotropin"),
            emptyList(),
            "The signal your brain sends to the thyroid. It moves opposite to thyroid output.",
            sameUnits = listOf("uiu/ml", "mu/l"),
        ),
        BiomarkerDef(
            "ft4", "Free T4", LabCategory.Thyroid, "ng/dL",
            listOf("free t4", "ft4", "free thyroxine", "t4 free"),
            listOf(factor(1 / 12.87, "pmol/l")),
            "The main hormone made by your thyroid.",
        ),
        BiomarkerDef(
            "ft3", "Free T3", LabCategory.Thyroid, "pg/mL",
            listOf("free t3", "ft3", "free triiodothyronine", "t3 free"),
            listOf(factor(0.651, "pmol/l")),
            "The active form of thyroid hormone.",
        ),
        // Kidney
        BiomarkerDef(
            "creatinine", "Creatinine", LabCategory.Kidney, "mg/dL",
            listOf("creatinine", "serum creatinine", "creatinine serum", "s creatinine"),
            listOf(factor(1 / 88.4, "umol/l")),
            "A waste product from muscle that kidneys clear. Higher with more muscle or heavy training.",
        ),
        BiomarkerDef(
            "egfr", "eGFR", LabCategory.Kidney, "mL/min/1.73m²",
            listOf("egfr", "estimated gfr", "estimated glomerular filtration rate", "gfr", "egfr ckd epi"),
            emptyList(),
            "An estimate of how well your kidneys filter, worked out from creatinine, age and sex.",
            sameUnits = listOf("ml/min/1.73m2", "ml/min/1.73 m2", "ml/min/1.73m^2", "ml/min"),
        ),
        BiomarkerDef(
            "urea", "Urea", LabCategory.Kidney, "mg/dL",
            listOf("urea", "blood urea", "serum urea"),
            listOf(factor(6.006, "mmol/l")),
            "A waste product from protein. Rises with a high-protein diet or dehydration.",
        ),
        BiomarkerDef(
            "bun", "BUN", LabCategory.Kidney, "mg/dL",
            listOf("bun", "blood urea nitrogen", "urea nitrogen"),
            listOf(factor(2.801, "mmol/l")),
            "The nitrogen part of urea in your blood.",
        ),
        BiomarkerDef(
            "uric_acid", "Uric acid", LabCategory.Kidney, "mg/dL",
            listOf("uric acid", "serum uric acid", "urate"),
            listOf(factor(1 / 59.48, "umol/l"), factor(1000 / 59.48, "mmol/l")),
            "A breakdown product of purines (in meat, seafood, beer and sugary drinks).",
        ),
        // Liver
        BiomarkerDef(
            "alt", "ALT", LabCategory.Liver, "U/L",
            listOf("alt", "sgpt", "alt sgpt", "sgpt alt", "alanine aminotransferase", "alanine transaminase"),
            emptyList(),
            "A liver enzyme. Rises when liver cells are under strain, including after very hard exercise.",
            sameUnits = listOf("iu/l", "u/l"),
        ),
        BiomarkerDef(
            "ast", "AST", LabCategory.Liver, "U/L",
            listOf("ast", "sgot", "ast sgot", "sgot ast", "aspartate aminotransferase", "aspartate transaminase"),
            emptyList(),
            "An enzyme found in liver and muscle, so it also rises after hard training.",
            sameUnits = listOf("iu/l", "u/l"),
        ),
        BiomarkerDef(
            "ggt", "GGT", LabCategory.Liver, "U/L",
            listOf("ggt", "gamma gt", "gamma glutamyl transferase", "ggtp", "gamma glutamyl transpeptidase"),
            emptyList(),
            "A liver enzyme that is sensitive to alcohol.",
            sameUnits = listOf("iu/l", "u/l"),
        ),
        BiomarkerDef(
            "alp", "ALP", LabCategory.Liver, "U/L",
            listOf("alp", "alkaline phosphatase"),
            emptyList(),
            "An enzyme from liver and bone.",
            sameUnits = listOf("iu/l", "u/l"),
        ),
        BiomarkerDef(
            "bilirubin_total", "Bilirubin", LabCategory.Liver, "mg/dL",
            listOf("bilirubin", "total bilirubin", "bilirubin total", "serum bilirubin"),
            listOf(factor(1 / 17.1, "umol/l")),
            "A yellow pigment from old red cells, cleared by the liver.",
        ),
        BiomarkerDef(
            "albumin", "Albumin", LabCategory.Liver, "g/dL",
            listOf("albumin", "serum albumin"),
            listOf(factor(0.1, "g/l")),
            "The main protein in blood, made by the liver.",
        ),
        // Hormones
        BiomarkerDef(
            "testosterone", "Testosterone", LabCategory.Hormones, "ng/dL",
            listOf("testosterone", "total testosterone", "testosterone total", "serum testosterone"),
            listOf(factor(28.84, "nmol/l"), factor(100.0, "ng/ml")),
            "The main male sex hormone, also present in women. Varies with time of day, sleep and training.",
        ),
        BiomarkerDef(
            "cortisol", "Cortisol", LabCategory.Hormones, "µg/dL",
            listOf("cortisol", "serum cortisol", "cortisol morning", "morning cortisol"),
            listOf(factor(1 / 27.59, "nmol/l")),
            "A stress hormone that peaks in the morning.",
        ),
        // Electrolytes
        BiomarkerDef(
            "sodium", "Sodium", LabCategory.Electrolytes, "mmol/L",
            listOf("sodium", "serum sodium", "na", "na+"),
            emptyList(),
            "Keeps fluid balance; held in a tight range.",
            sameUnits = listOf("meq/l"),
        ),
        BiomarkerDef(
            "potassium", "Potassium", LabCategory.Electrolytes, "mmol/L",
            listOf("potassium", "serum potassium", "k", "k+"),
            emptyList(),
            "Needed for muscles and heart rhythm.",
            sameUnits = listOf("meq/l"),
        ),
    )

    private val byId = all.associateBy { it.id }
    private val byAlias: Map<String, BiomarkerDef> = buildMap {
        // Earlier entries win (e.g. "cholesterol" → total cholesterol).
        all.forEach { d -> (d.aliases + d.name + d.id).forEach { a -> putIfAbsent(nameKey(a), d) } }
    }

    val ids: List<String> get() = all.map { it.id }

    fun byId(id: String?): BiomarkerDef? = id?.let { byId[it.trim().lowercase()] }

    /** Lower-case, no punctuation, single spaces: "HbA1c (Glycated Hb)" → "hba1c glycated hb". */
    fun nameKey(s: String): String = s.lowercase()
        .replace("₂", "2").replace(Regex("[^a-z0-9+()%]+"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** Exact alias match, also trying the name without a trailing bracket ("LDL Cholesterol (Direct)"). */
    fun match(name: String): BiomarkerDef? {
        val k = nameKey(name)
        byAlias[k]?.let { return it }
        val noParen = nameKey(name.replace(Regex("\\(.*?\\)"), " "))
        byAlias[noParen]?.let { return it }
        val noPrefix = noParen.removePrefix("serum ").removePrefix("plasma ").removePrefix("blood ")
        return byAlias[noPrefix]
    }

    /** Lower-case, no spaces, µ/μ/mc → u, litre → l, "per" spellings tidied. */
    fun unitKey(u: String?): String = (u ?: "").trim().lowercase()
        .replace("μ", "u").replace("µ", "u").replace("mcg", "ug").replace("mc", "u")
        .replace("litre", "l").replace("liter", "l").replace("²", "2").replace("×", "x")
        .replace("cu mm", "cumm").replace("cmm", "cumm").replace("mm³", "mm3")
        .replace(" ", "")

    /** Convert [value] in [unit] into [def]'s canonical unit, or null if the unit isn't known. */
    fun toCanonical(def: BiomarkerDef, value: Double, unit: String?): Double? {
        val k = unitKey(unit)
        if (k.isEmpty() || k == unitKey(def.unit) || def.sameUnits.any { unitKey(it) == k }) return value
        def.conversions.firstOrNull { c -> c.units.any { unitKey(it) == k } }?.let { return it.toCanonical(value) }
        return null
    }

    /** Slug for biomarkers we don't know, so the same test still lines up across reports. */
    fun slug(name: String): String = "x_" + nameKey(name).replace(Regex("[^a-z0-9]+"), "_").trim('_')
}

/** One result as Gemini read it off the page, before normalisation. */
data class RawBiomarker(
    val name: String,
    val canonicalName: String?,
    val value: Double?,
    val unit: String?,
    val refLow: Double?,
    val refHigh: Double?,
    val flag: String?,
    val category: String?,
    val collectedDate: String?,
)

object Normalizer {
    private fun round3(v: Double) = round(v * 1000) / 1000

    /**
     * Map a raw result onto Baseline's catalogue: pick the canonical biomarker (Gemini's choice if it's a known
     * id, else an exact alias match on the printed name), convert value and range into the canonical unit, and
     * work out the flag from the range when there is one. Unknown tests are kept as-is under a stable slug.
     * Returns null for rows without a numeric value.
     */
    fun normalize(raw: RawBiomarker): LabMarker? {
        val value = raw.value ?: return null
        if (raw.name.isBlank() || value.isNaN() || value.isInfinite()) return null
        val def = Biomarkers.byId(raw.canonicalName)?.takeIf { raw.canonicalName != "other" } ?: Biomarkers.match(raw.name)
        if (def != null) {
            val v = Biomarkers.toCanonical(def, value, raw.unit)
            if (v != null) {
                val lo = raw.refLow?.let { Biomarkers.toCanonical(def, it, raw.unit) }
                val hi = raw.refHigh?.let { Biomarkers.toCanonical(def, it, raw.unit) }
                return LabMarker(
                    key = def.id, name = def.name, value = round3(v), unit = def.unit,
                    refLow = lo?.let(::round3), refHigh = hi?.let(::round3),
                    flag = flagFor(v, lo, hi, raw.flag).name, category = def.category.name,
                    collectedDate = raw.collectedDate?.let(LabDates::parse)?.toString(),
                )
            }
            // Known test in a unit we can't convert: keep it, but under its own key so trends don't mix units.
        }
        val unit = raw.unit?.trim().orEmpty()
        return LabMarker(
            key = if (def != null) "${def.id}@${Biomarkers.unitKey(unit)}" else Biomarkers.slug(raw.name),
            name = def?.name ?: raw.name.trim(),
            value = round3(value), unit = unit,
            refLow = raw.refLow?.let(::round3), refHigh = raw.refHigh?.let(::round3),
            flag = flagFor(value, raw.refLow, raw.refHigh, raw.flag).name,
            category = (def?.category ?: LabCategory.of(raw.category)).name,
            collectedDate = raw.collectedDate?.let(LabDates::parse)?.toString(),
        )
    }

    fun flagFor(value: Double, low: Double?, high: Double?, printed: String?): LabFlag = when {
        low != null && value < low -> LabFlag.Low
        high != null && value > high -> LabFlag.High
        low != null || high != null -> LabFlag.Normal
        else -> LabFlag.of(printed)
    }
}
