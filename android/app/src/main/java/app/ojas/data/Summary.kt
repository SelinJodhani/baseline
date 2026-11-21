package app.ojas.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * The `ojas.v1` document served by Ojas Bridge (`GET /api/v1/summary`). Every field is optional:
 * a missing reading is null, never zero — the bridge copies ZeppBridge's "no fabricated values" rule.
 * Times are Unix epoch seconds.
 */
@Serializable
data class Summary(
    val schema: String = "ojas.v1",
    val bridgeVersion: String? = null,
    val generatedAt: Long = 0,
    val timezone: String? = null,
    val utcOffsetMinutes: Int? = null,
    val freshness: Freshness = Freshness(),
    val profile: Profile = Profile(),
    val days: List<Day> = emptyList(),
    val sleeps: List<Sleep> = emptyList(),
    val hourly: Hourly = Hourly(),
    val workouts: List<Workout> = emptyList(),
    val weights: List<Weight> = emptyList(),
) {
    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    }
}

@Serializable
data class Freshness(val lastReadingAt: Long? = null, val libraryModifiedAt: Long? = null)

@Serializable
data class Profile(val maxHr: Double? = null)

@Serializable
data class Day(
    val date: String,
    val steps: Double? = null,
    val stepGoal: Double? = null,
    val distanceM: Double? = null,
    val calories: Double? = null,
    val activeCalories: Double? = null,
    val calorieGoal: Double? = null,
    val activeMinutes: Double? = null,
    val activeMinutesGoal: Double? = null,
    val restingHr: Double? = null,
    val deviceRestingHr: Double? = null,
    val maxHr: Double? = null,
    val sleepHrv: Double? = null,
    val sleepRhr: Double? = null,
    val respRate: Double? = null,
    val spo2NightScore: Double? = null,
    val spo2Odi: Double? = null,
    val spo2Minutes: Double? = null,
    val stress: Double? = null,
    val stressMin: Double? = null,
    val stressMax: Double? = null,
    val stressRelaxedPct: Double? = null,
    val stressNormalPct: Double? = null,
    val stressMediumPct: Double? = null,
    val stressHighPct: Double? = null,
    val pai: Double? = null,
    val paiWeek: Double? = null,
    val lowZoneMin: Double? = null,
    val moderateZoneMin: Double? = null,
    val vigorousZoneMin: Double? = null,
    val zeppReadiness: Double? = null,
    val zeppHrvScore: Double? = null,
    val zeppRhrScore: Double? = null,
    val zeppSkinTempScore: Double? = null,
    val physicalCharge: Double? = null,
    val mentalCharge: Double? = null,
    val charge: Double? = null,
    val trainingLoad: Double? = null,
    val vo2max: Double? = null,
) {
    val localDate: LocalDate get() = LocalDate.parse(date)
}

@Serializable
data class Sleep(
    val start: Long,
    val end: Long,
    val durationMin: Int? = null,
    val deepMin: Int? = null,
    val lightMin: Int? = null,
    val remMin: Int? = null,
    val awakeMin: Int? = null,
    val wakeCount: Int? = null,
    val score: Int? = null,
    /** [startEpoch, endEpoch, stage] where stage is deep | light | rem | awake. */
    val stages: List<List<String>>? = null,
) {
    /** Minutes actually asleep (time in bed minus awake), falling back to stage sums. */
    val asleepMin: Int
        get() = durationMin ?: listOfNotNull(deepMin, lightMin, remMin).sum()
}

/** Hourly aggregates: [epochHourStart, min, avg, max]. */
@Serializable
data class Hourly(
    val heartRate: List<List<Double>> = emptyList(),
    val stress: List<List<Double>> = emptyList(),
    val spo2: List<List<Double>> = emptyList(),
)

@Serializable
data class Workout(
    val id: String,
    val type: String = "workout",
    val start: Long,
    val end: Long? = null,
    val distanceM: Double? = null,
    val calories: Double? = null,
    val avgHr: Double? = null,
    val maxHr: Double? = null,
    val trainingLoad: Double? = null,
    val aerobicTe: Double? = null,
    val anaerobicTe: Double? = null,
    val vo2max: Double? = null,
    val steps: Double? = null,
    val elevationGainM: Double? = null,
    val zoneSeconds: List<Int>? = null,
) {
    val durationMin: Double? get() = end?.let { (it - start) / 60.0 }
}

@Serializable
data class Weight(val t: Long, val kg: Double)
