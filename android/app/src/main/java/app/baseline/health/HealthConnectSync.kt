package app.baseline.health

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import app.baseline.data.Repository
import app.baseline.data.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.reflect.KClass

/**
 * Copies Zepp's data (through the bridge) into Health Connect, so Google Health and every other app see Zepp's
 * readings as recorded, from one source. Turn off the Zepp app's own Health Connect access to make it the only one.
 *
 * Borrowed from ZeppSync (which does the same into Apple Health): every record has a fixed client id
 * (`baseline-hrv-<epoch>` …), so sending it again updates it instead of adding a duplicate; and a watermark
 * remembers how far readings have been copied. Sleep, resting heart rate and breathing wait for the night to
 * settle — see [HcPlanner].
 */
class HealthConnectSync(private val context: Context, private val repo: Repository, private val store: SecureStore) {

    data class Status(
        val running: Boolean = false,
        val lastRunAt: Long? = null,
        val lastError: String? = null,
        /** Records written in the last run, by kind. */
        val written: Map<String, Int> = emptyMap(),
        /** Readings copied up to here (epoch seconds). */
        val copiedUntil: Long? = null,
        val progress: String? = null,
    )

    @Serializable
    private data class State(
        val samplesUntil: Long = 0,
        val device: String? = null,
        /** client id -> content fingerprint of daily records, nights, workouts and weigh-ins already written. */
        val written: Map<String, String> = emptyMap(),
        /** client id -> [start, end] of nights written, to replace a night whose start Zepp moved. */
        val nights: Map<String, List<Long>> = emptyMap(),
        val lastRunAt: Long? = null,
        val lastError: String? = null,
        val lastWritten: Map<String, Int> = emptyMap(),
        /** How the HRV currently in Health Connect was written: "recorded" (true times) or "google" (moved per night). */
        val hrvMode: String = "recorded",
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateFile get() = File(context.filesDir, "health/connect_state.json")
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(load().let { Status(lastRunAt = it.lastRunAt, lastError = it.lastError, written = it.lastWritten, copiedUntil = it.samplesUntil.takeIf { u -> u > 0 }) })
    val status: StateFlow<Status> = _status.asStateFlow()

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasPermissions(): Boolean = available(context) &&
        runCatching { client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS) }.getOrDefault(false)

    /** Start a run in the background unless one ran recently ([minGapSec]) or Health Connect copying is off. */
    fun kick(minGapSec: Long = 15 * 60) {
        scope.launch {
            val last = _status.value.lastRunAt ?: 0
            if (!_status.value.running && Repository.nowSec() - last >= minGapSec) run()
        }
    }

    /** Copy whatever is new. Returns null on success (or when off), else a reason. */
    suspend fun run(): String? = mutex.withLock {
        val settings = store.settings.first()
        if (!settings.healthConnect || settings.demo || settings.serverUrl.isBlank()) return null
        _status.update { it.copy(running = true, progress = "Starting…") }
        val outcome = runCatching { withContext(Dispatchers.IO) { copy(settings.healthConnectDays, settings.healthConnectGoogleHrv) } }
        val error = outcome.exceptionOrNull()?.let { describe(it) }
        val st = load().copy(lastRunAt = Repository.nowSec(), lastError = error, lastWritten = outcome.getOrNull() ?: load().lastWritten)
        save(st)
        _status.value = Status(false, st.lastRunAt, error, st.lastWritten, st.samplesUntil.takeIf { it > 0 })
        error
    }

    private suspend fun copy(days: Int, googleHrv: Boolean): Map<String, Int> {
        if (!available(context)) throw IllegalStateException("Health Connect isn't available on this phone")
        if (!hasPermissions()) throw SecurityException("permissions")
        if (repo.state.value.summary == null) repo.loadInitial()
        val summary = repo.state.value.summary ?: throw IllegalStateException("No data from the bridge yet")
        if (!summary.capabilities.samples) throw IllegalStateException("Update the bridge to 0.6 or later to copy readings")
        val zone = ZoneId.systemDefault()
        val now = Repository.nowSec()
        val historyStart = now - days * 86_400L
        var st = load()
        val counts = linkedMapOf<String, Int>()
        fun count(kind: String, n: Int) { if (n > 0) counts[kind] = (counts[kind] ?: 0) + n }

        // 0. The HRV option changed: delete every HRV reading Baseline wrote and copy them again the other way.
        val hrvMode = if (googleHrv) "google" else "recorded"
        if (st.hrvMode != hrvMode) {
            _status.update { it.copy(progress = "Rewriting HRV…") }
            client.deleteRecords(HeartRateVariabilityRmssdRecord::class, TimeRangeFilter.between(Instant.EPOCH, Instant.now().plusSeconds(2 * 86_400L)))
            st = st.copy(hrvMode = hrvMode, samplesUntil = 0, written = st.written.filterKeys { !it.startsWith("baseline-hrvnight-") })
            save(st)
        }

        // 1. Readings, in week-long windows from the watermark (minus 2 h, hour-aligned, so late uploads and the
        //    hour in progress are re-sent whole).
        var cursor = HcPlanner.floorHour(if (st.samplesUntil > 0) maxOf(historyStart, st.samplesUntil - 7_200) else historyStart)
        var lastReading = st.samplesUntil.takeIf { it > 0 }
        while (cursor < now) {
            val to = minOf(now, cursor + 7 * 86_400L)
            _status.update { it.copy(progress = "Copying readings from ${Instant.ofEpochSecond(cursor).atZone(zone).toLocalDate()}…") }
            val s = repo.fetchSamples(cursor, to)
            st = st.copy(device = s.device ?: st.device)
            val dev = device(st.device)
            val version = Repository.nowSec()
            val hr = HcPlanner.hrHours(s.heartRate, now).mapNotNull { h ->
                build { HeartRateRecord(at(h.start), off(h.start, zone), at(h.end), off(h.end, zone),
                    h.samples.map { HeartRateRecord.Sample(at(it.t), it.v.toLong()) }, Metadata.autoRecorded(dev, "baseline-hr-${h.start}", version)) }
            }
            // With the Google Health option, HRV is written per settled night instead (step 2).
            val hrv = if (googleHrv) emptyList<HeartRateVariabilityRmssdRecord>() else HcPlanner.points(s.hrv, 1.0, 200.0).mapNotNull { p ->
                build { HeartRateVariabilityRmssdRecord(at(p.t), off(p.t, zone), p.v, Metadata.autoRecorded(dev, "baseline-hrv-${p.t}", version)) }
            }
            val spo2 = HcPlanner.points(s.spo2, 50.0, 100.0).mapNotNull { p ->
                build { OxygenSaturationRecord(at(p.t), off(p.t, zone), Percentage(p.v), Metadata.autoRecorded(dev, "baseline-spo2-${p.t}", version)) }
            }
            put(hr); put(hrv); put(spo2)
            count("heart rate", hr.sumOf { it.samples.size }); count("HRV", hrv.size); count("SpO₂", spo2.size)
            lastReading = listOfNotNull(lastReading, s.lastReadingAt).maxOrNull()
            st = st.copy(samplesUntil = lastReading ?: st.samplesUntil)
            save(st)
            cursor = to
        }

        // 2. Nights, days, vitals, workouts and weigh-ins: written when new or changed (fingerprints in state).
        _status.update { it.copy(progress = "Copying sleep and daily totals…") }
        val dev = device(st.device)
        val version = Repository.nowSec()
        val written = st.written.toMutableMap()
        val nightSpans = st.nights.toMutableMap()
        val sinceDate = Instant.ofEpochSecond(historyStart).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        val pending = mutableListOf<Pair<String, Record>>()
        fun add(id: String, fingerprint: String, record: Record?) {
            if (record != null && written[id] != fingerprint) { pending += id to record; written[id] = fingerprint }
        }

        val nights = HcPlanner.settledNights(summary, historyStart, lastReading, now)
        val replaced = mutableListOf<String>()
        val currentIds = nights.map { "baseline-sleep-${it.start}" }.toSet()
        for (n in nights) {
            val id = "baseline-sleep-${n.start}"
            add(id, "${n.start}|${n.end}|${n.stages.hashCode()}", build {
                SleepSessionRecord(at(n.start), off(n.start, zone), at(n.end), off(n.end, zone), Metadata.autoRecorded(dev, id, version),
                    null, n.score?.let { "Zepp sleep score $it" }, n.stages.map { SleepSessionRecord.Stage(at(it.start), at(it.end), stage(it.kind)) })
            })
            // A night we wrote earlier that Zepp no longer has, overlapping this one (Zepp moved its start, or the
            // merge now picks another session): drop our old copy so there's one night, not two.
            nightSpans.filter { (other, span) -> other !in currentIds && span.size == 2 && span[0] < n.end && span[1] > n.start }.keys.forEach { replaced += it }
            nightSpans[id] = listOf(n.start, n.end)
        }
        if (replaced.isNotEmpty()) {
            client.deleteRecords(SleepSessionRecord::class, emptyList(), replaced.distinct())
            replaced.forEach { nightSpans.remove(it); written.remove(it) }
        }
        if (googleHrv) for (n in nights) {
            // Each night's readings, moved so Google Health files the night under the wake-up day (HcPlanner).
            // Written once all moved times are in the past; a reading keeps its id, so switching modes moves it.
            val shift = HcPlanner.googleHrvShift(n, zone)
            val id = "baseline-hrvnight-${n.start}"
            val fingerprint = "${n.start}|${n.end}|$shift"
            if (now < n.end + shift || written[id] == fingerprint) continue
            _status.update { it.copy(progress = "Lining up HRV for ${Instant.ofEpochSecond(n.end).atZone(zone).toLocalDate()}…") }
            val s = repo.fetchSamples(n.start, n.end)
            val records = HcPlanner.googleHrv(n, HcPlanner.points(s.hrv, 1.0, 200.0), zone).mapNotNull { (orig, p) ->
                build { HeartRateVariabilityRmssdRecord(at(p.t), off(p.t, zone), p.v, Metadata.autoRecorded(dev, "baseline-hrv-$orig", version)) }
            }
            put(records)
            count("HRV", records.size)
            written[id] = fingerprint
        }
        val main = HcPlanner.mainNights(nights, zone)
        for (d in HcPlanner.dayTotals(summary, sinceDate, now, zone)) {
            val (s0, s1) = d.start to d.end
            d.steps?.let { v -> add("baseline-steps-${d.date}", "$s1|$v", build { StepsRecord(at(s0), off(s0, zone), at(s1), off(s1, zone), v, Metadata.autoRecorded(dev, "baseline-steps-${d.date}", version)) }) }
            d.distanceM?.let { v -> add("baseline-distance-${d.date}", "$s1|$v", build { DistanceRecord(at(s0), off(s0, zone), at(s1), off(s1, zone), Length.meters(v), Metadata.autoRecorded(dev, "baseline-distance-${d.date}", version)) }) }
            d.activeKcal?.let { v -> add("baseline-active-${d.date}", "$s1|$v", build { ActiveCaloriesBurnedRecord(at(s0), off(s0, zone), at(s1), off(s1, zone), Energy.kilocalories(v), Metadata.autoRecorded(dev, "baseline-active-${d.date}", version)) }) }
        }
        for (v in HcPlanner.restingHr(summary, sinceDate, main, today, zone)) {
            val id = "baseline-rhr-${v.date}"
            add(id, "${v.at}|${v.value}", build { RestingHeartRateRecord(at(v.at), off(v.at, zone), v.value.toLong(), Metadata.autoRecorded(dev, id, version)) })
        }
        for (v in HcPlanner.breathing(summary, sinceDate, main)) {
            val id = "baseline-breathing-${v.date}"
            add(id, "${v.at}|${v.value}", build { RespiratoryRateRecord(at(v.at), off(v.at, zone), v.value, Metadata.autoRecorded(dev, id, version)) })
        }
        for (w in HcPlanner.workouts(summary, historyStart, now)) {
            val id = "baseline-workout-${w.id}"
            add(id, "${w.start}|${w.end}|${w.type}", build {
                ExerciseSessionRecord(at(w.start), off(w.start, zone), at(w.end), off(w.end, zone), Metadata.activelyRecorded(dev, id, version), exerciseType(w.type), title(w.type))
            })
        }
        for (p in HcPlanner.weights(summary, historyStart, now)) {
            val id = "baseline-weight-${p.t}"
            add(id, "${p.v}", build { WeightRecord(at(p.t), off(p.t, zone), Mass.kilograms(p.v), Metadata.manualEntry(id, version)) })
        }
        put(pending.map { it.second })
        for ((_, r) in pending) count(kindOf(r), 1)
        save(st.copy(written = written, nights = nightSpans))
        return counts
    }

    /** Delete everything Baseline wrote (Health Connect only lets an app delete its own records), and start over. */
    suspend fun removeOurData(): String? = mutex.withLock {
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                val range = TimeRangeFilter.between(Instant.EPOCH, Instant.now().plusSeconds(86_400))
                for (type in TYPES) client.deleteRecords(type, range)
                save(State())
            }
        }
        _status.value = Status()
        outcome.exceptionOrNull()?.let { describe(it) }
    }

    /** Forget what was written so the next run copies the whole history window again (records update in place). */
    suspend fun recopy(): String? {
        mutex.withLock { save(load().copy(samplesUntil = 0, written = emptyMap())) }
        return run()
    }

    private suspend fun put(records: List<Record>) {
        for (chunk in records.chunked(500)) client.insertRecords(chunk)
    }

    private fun load(): State = runCatching { json.decodeFromString(State.serializer(), stateFile.readText()) }.getOrDefault(State())

    private fun save(st: State) {
        stateFile.parentFile?.mkdirs()
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(json.encodeToString(State.serializer(), st))
        tmp.renameTo(stateFile)
    }

    companion object {
        const val ZEPP = "com.huami.watch.hmwatchmanager"

        val TYPES: List<KClass<out Record>> = listOf(
            HeartRateRecord::class, HeartRateVariabilityRmssdRecord::class, OxygenSaturationRecord::class,
            SleepSessionRecord::class, RestingHeartRateRecord::class, RespiratoryRateRecord::class,
            StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class,
            ExerciseSessionRecord::class, WeightRecord::class,
        )
        val PERMISSIONS: Set<String> = TYPES.map { HealthPermission.getWritePermission(it) }.toSet()

        fun available(ctx: Context): Boolean = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

        /** The Health Connect write permissions the Zepp app still holds (it competes with Baseline while it does). */
        fun zeppWrites(ctx: Context): List<String> = PERMISSIONS.filter {
            runCatching { ctx.packageManager.checkPermission(it, ZEPP) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        }

        /** Health Connect's page for one app's access, or its main settings where that page can't be opened. */
        fun manageIntent(ctx: Context, pkg: String?): Intent {
            if (pkg != null && Build.VERSION.SDK_INT >= 34) {
                val direct = Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
                if (direct.resolveActivity(ctx.packageManager) != null) return direct
            }
            return Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        }

        private fun at(t: Long): Instant = Instant.ofEpochSecond(t)
        private fun off(t: Long, zone: ZoneId): ZoneOffset = zone.rules.getOffset(at(t))

        /** Build a record, or skip it if Health Connect's own checks reject it (one bad reading mustn't sink a batch). */
        private inline fun <T> build(block: () -> T): T? = runCatching(block).getOrNull()

        /** "Amazfit Helio Strap" -> manufacturer Amazfit, model Helio Strap, type band. */
        private fun device(name: String?): Device {
            val n = name?.trim().orEmpty()
            val type = when {
                n.contains("ring", true) -> Device.TYPE_RING
                n.contains("strap", true) || n.contains("band", true) -> Device.TYPE_FITNESS_BAND
                else -> Device.TYPE_WATCH
            }
            val parts = n.split(' ', limit = 2)
            return Device(type = type, manufacturer = parts.getOrNull(0)?.ifBlank { null } ?: "Amazfit", model = parts.getOrNull(1)?.ifBlank { null })
        }

        private fun stage(kind: String): Int = when (kind.lowercase()) {
            "light" -> SleepSessionRecord.STAGE_TYPE_LIGHT
            "deep" -> SleepSessionRecord.STAGE_TYPE_DEEP
            "rem" -> SleepSessionRecord.STAGE_TYPE_REM
            "awake", "wake" -> SleepSessionRecord.STAGE_TYPE_AWAKE
            else -> SleepSessionRecord.STAGE_TYPE_SLEEPING
        }

        internal fun exerciseType(zepp: String): Int = when (zepp.lowercase().substringBefore(':')) {
            "strength", "strength_training", "weight_training" -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
            "walking", "walk", "indoor_walking" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
            "treadmill", "indoor_running" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
            "run", "running", "outdoor_running", "trail_running", "track_running" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            "pool_swimming" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
            "open_water_swimming" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER
            "cycling", "outdoor_cycling" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
            "indoor_cycling", "spinning" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
            "badminton" -> ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON
            "stair_climber", "stair_climbing" -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE
            "yoga" -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
            "pilates" -> ExerciseSessionRecord.EXERCISE_TYPE_PILATES
            "hiit" -> ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
            "elliptical" -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
            "rowing", "rowing_machine", "indoor_rowing" -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
            "hiking" -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
            "tennis" -> ExerciseSessionRecord.EXERCISE_TYPE_TENNIS
            "football", "soccer" -> ExerciseSessionRecord.EXERCISE_TYPE_SOCCER
            "basketball" -> ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL
            "cricket" -> ExerciseSessionRecord.EXERCISE_TYPE_CRICKET
            "dance", "dancing", "zumba" -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING
            else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
        }

        internal fun title(zepp: String): String = zepp.substringBefore(':').replace('_', ' ').trim()
            .replaceFirstChar { it.uppercase() }.ifBlank { "Workout" }.let { if (it == "Unknown") "Workout" else it }

        private fun kindOf(r: Record): String = when (r) {
            is SleepSessionRecord -> "nights"
            is StepsRecord, is DistanceRecord, is ActiveCaloriesBurnedRecord -> "daily totals"
            is RestingHeartRateRecord -> "resting HR"
            is RespiratoryRateRecord -> "breathing"
            is ExerciseSessionRecord -> "workouts"
            is WeightRecord -> "weigh-ins"
            else -> "other"
        }

        fun describe(t: Throwable): String = when {
            t is SecurityException -> "Baseline lost its Health Connect access — turn it on again"
            t is android.os.RemoteException || t.javaClass.simpleName.contains("RemoteException") ->
                "Health Connect is busy or rate-limited — it will try again in an hour"
            else -> Repository.friendly(t)
        }
    }
}
