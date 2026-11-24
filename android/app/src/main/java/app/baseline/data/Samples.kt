package app.baseline.data

import kotlinx.serialization.Serializable

/**
 * The `baseline.samples.v1` document (`GET /api/v1/samples?from=&to=`, bridge 0.6+): Zepp's raw readings for a
 * time window, as recorded. Each series is `[epochSeconds, value]`, sorted by time, one reading per timestamp.
 */
@Serializable
data class Samples(
    val schema: String = "baseline.samples.v1",
    val generatedAt: Long = 0,
    val from: Long = 0,
    val to: Long = 0,
    /** The watch or strap ZeppBridge knows about, e.g. "Amazfit Helio Strap". */
    val device: String? = null,
    /** Newest reading in the window across all series, or null when the window is empty. */
    val lastReadingAt: Long? = null,
    /** Per-minute heart rate, bpm. */
    val heartRate: List<List<Double>> = emptyList(),
    /** Per-minute HRV (RMSSD), ms. */
    val hrv: List<List<Double>> = emptyList(),
    /** SpO2 readings, %. */
    val spo2: List<List<Double>> = emptyList(),
)
