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
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
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
 * (`baseline-hrv-<epoch>` …) and a watermark remembers how far readings have been copied. Unlike ZeppSync, readings
 * and daily totals are written once and never updated, and a rewrite uses new ids, because Google Health ignores or
 * drops records that change after it first sees them. Sleep, resting heart rate and breathing wait for the night to
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
        /** Newest reading written, per series (records are added once and never updated; see HcPlanner). */
        val hrUntil: Long = 0,
        val hrvUntil: Long = 0,
        val spo2Until: Long = 0,
        /** Daily totals written so far, by local date. */
        val days: Map<String, HcPlanner.DayProgress> = emptyMap(),
        /**
         * 5 = breathing rate stamped past midnight UTC on the wake-up date. 4 = SpO₂ follows the Google Health option,
         * HRV and SpO₂ rewritten once. 3 = fresh ids per rewrite, and heart-rate holes refilled. 2 = add-only records.
         * 0/1 = v0.6.0–0.6.1, which rewrote today's totals and the current hour's heart rate.
         */
        val scheme: Int = 0,
        /** Bumped by every delete-and-rewrite; readings and daily totals carry it in their ids (HcPlanner.recordId). */
        val gen: Int = 0,
        /** After a rewrite: read back Baseline's heart rate once and add any clock hours that never arrived. */
        val hrCheck: Boolean = false,
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
        _status.value = Status(false, st.lastRunAt, error, st.lastWritten, maxOf(st.hrUntil, st.hrvUntil, st.spo2Until).takeIf { it > 0 })
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

        val everything = TimeRangeFilter.between(Instant.EPOCH, Instant.now().plusSeconds(2 * 86_400L))
        // 0a. Records from v0.6.0–0.6.1 that were updated in place (heart rate by the hour, daily totals): Google
        //     Health drops those. Delete them once and write them again add-only.
        if (st.scheme < 2) {
            _status.update { it.copy(progress = "Rewriting heart rate and daily totals…") }
            for (type in listOf(HeartRateRecord::class, StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class)) client.deleteRecords(type, everything)
            st = st.copy(
                scheme = 2, gen = st.gen + 1, hrUntil = 0, days = emptyMap(), hrCheck = true,
                hrvUntil = maxOf(st.hrvUntil, st.samplesUntil), spo2Until = maxOf(st.spo2Until, st.samplesUntil),
                written = st.written.filterKeys { k -> listOf("baseline-steps-", "baseline-distance-", "baseline-active-").none { k.startsWith(it) } },
            )
            save(st)
        }
        // 0b. The HRV option changed, or an upgrade: delete every HRV reading Baseline wrote and copy them again under
        //     new ids. v0.6.2–0.7.1 rewrote HRV under its old ids, which Google Health took as edits and ignored; and
        //     Google Health only picks up what changes after it's given Health Connect access, so v0.7.3 writes it
        //     all once more (25 Nov 2026: access granted at 12:25, HRV rewritten at 00:31 never showed up). The
        //     v0.7.2 upgrade also checks heart rate once for hours that never arrived.
        val hrvMode = if (googleHrv) "google" else "recorded"
        val modeChanged = st.hrvMode != hrvMode
        if (modeChanged || st.scheme < 4) {
            _status.update { it.copy(progress = "Rewriting HRV…") }
            client.deleteRecords(HeartRateVariabilityRmssdRecord::class, everything)
            st = st.copy(
                hrvMode = hrvMode, hrvUntil = 0, gen = st.gen + 1,
                written = st.written.filterKeys { !it.startsWith("baseline-hrvnight-") },
                hrCheck = st.hrCheck || st.scheme < 3, scheme = maxOf(st.scheme, 3),
            )
            save(st)
        }
        // 0c. SpO₂ is a night value in Google Health too, filed a day early the same way (v0.7.3): it follows the same
        //     option. Delete Baseline's SpO₂ once (and on every option change) and copy it again under new ids.
        if (modeChanged || st.scheme < 4) {
            _status.update { it.copy(progress = "Rewriting SpO₂…") }
            client.deleteRecords(OxygenSaturationRecord::class, everything)
            st = st.copy(spo2Until = 0, gen = st.gen + 1, written = st.written.filterKeys { !it.startsWith("baseline-spo2night-") }, scheme = maxOf(st.scheme, 4))
            save(st)
        }
        // 0d. Breathing rate is re-stamped past midnight UTC on the wake-up date (v0.7.4, HcPlanner.nightStamp): delete
        //     Baseline's breathing records once and write them again under new ids.
        if (st.scheme < 5) {
            client.deleteRecords(RespiratoryRateRecord::class, everything)
            st = st.copy(gen = st.gen + 1, written = st.written.filterKeys { !it.startsWith("baseline-breathing-") }, scheme = 5)
            save(st)
        }

        // 1. Readings, in week-long windows from the oldest per-series watermark (minus 2 h for late uploads). Only
        //    readings newer than their series' watermark are written, so nothing is ever sent twice.
        val marks = listOf(st.hrUntil) + (if (googleHrv) emptyList() else listOf(st.spo2Until, st.hrvUntil))
        var cursor = HcPlanner.floorHour(if (marks.all { it > 0 }) maxOf(historyStart, marks.min() - 7_200) else historyStart)
        var lastReading = st.samplesUntil.takeIf { it > 0 }
        while (cursor < now) {
            val to = minOf(now, cursor + 7 * 86_400L)
            _status.update { it.copy(progress = "Copying readings from ${Instant.ofEpochSecond(cursor).atZone(zone).toLocalDate()}…") }
            val s = repo.fetchSamples(cursor, to)
            st = st.copy(device = s.device ?: st.device)
            val dev = device(st.device)
            val hrPts = HcPlanner.points(s.heartRate, 1.0, 300.0).filter { it.t <= now }
            val hr = HcPlanner.hrChunks(hrPts, st.hrUntil).mapNotNull { h ->
                build { HeartRateRecord(at(h.start), off(h.start, zone), at(h.end), off(h.end, zone),
                    h.samples.map { HeartRateRecord.Sample(at(it.t), it.v.toLong()) }, Metadata.autoRecorded(dev, HcPlanner.recordId("hrc", st.gen, h.start), 1)) }
            }
            // With the Google Health option, HRV is written per settled night instead (step 2).
            val hrvPts = if (googleHrv) emptyList() else HcPlanner.points(s.hrv, 1.0, 200.0).filter { it.t > st.hrvUntil }
            val hrv = hrvPts.mapNotNull { p ->
                build { HeartRateVariabilityRmssdRecord(at(p.t), off(p.t, zone), p.v, Metadata.autoRecorded(dev, HcPlanner.recordId("hrv", st.gen, p.t), 1)) }
            }
            // With the Google Health option, SpO₂ waits for its night to settle too (step 2).
            val spo2Pts = if (googleHrv) emptyList() else HcPlanner.points(s.spo2, 50.0, 100.0).filter { it.t > st.spo2Until }
            val spo2 = spo2Pts.mapNotNull { p ->
                build { OxygenSaturationRecord(at(p.t), off(p.t, zone), Percentage(p.v), Metadata.autoRecorded(dev, HcPlanner.recordId("spo2", st.gen, p.t), 1)) }
            }
            put(hr); put(hrv); put(spo2)
            count("heart rate", hr.sumOf { it.samples.size }); count("HRV", hrv.size); count("SpO₂", spo2.size)
            lastReading = listOfNotNull(lastReading, s.lastReadingAt).maxOrNull()
            st = st.copy(
                samplesUntil = lastReading ?: st.samplesUntil,
                hrUntil = maxOf(st.hrUntil, hr.maxOfOrNull { it.samples.last().time.epochSecond } ?: 0),
                hrvUntil = maxOf(st.hrvUntil, hrvPts.maxOfOrNull { it.t } ?: 0),
                spo2Until = maxOf(st.spo2Until, spo2Pts.maxOfOrNull { it.t } ?: 0),
            )
            save(st)
            cursor = to
        }

        // 1b. Once after each rewrite: add heart-rate hours that never arrived (HcPlanner.missingHours). It reads back
        //     Baseline's own heart rate, which needs the read access the "What's in Health Connect" page asks for;
        //     until that's granted (or if the bridge fails) it waits for a later run.
        if (st.hrCheck && st.hrUntil > 0) {
            runCatching { refillHeartRate(historyStart, st.hrUntil, zone, device(st.device), st.gen) }.onSuccess { n ->
                count("heart rate", n)
                st = st.copy(hrCheck = false)
                save(st)
            }
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
        if (googleHrv) {
            // SpO₂ outside the nights (daytime spot checks) keeps its real times. It's written up to the end of the
            // latest settled night, so a reading that turns out to belong to a night still in progress isn't written
            // twice (once as it happened, once moved).
            val settledEnd = nights.maxOfOrNull { it.end }
            if (settledEnd != null && settledEnd > st.spo2Until) {
                var c = HcPlanner.floorHour(maxOf(st.spo2Until, historyStart))
                while (c < settledEnd) {
                    val to = minOf(settledEnd, c + 7 * 86_400L)
                    _status.update { it.copy(progress = "Copying SpO₂ from ${Instant.ofEpochSecond(c).atZone(zone).toLocalDate()}…") }
                    val s = repo.fetchSamples(c, to)
                    val day = HcPlanner.outsideNights(HcPlanner.points(s.spo2, 50.0, 100.0), nights).filter { it.t > st.spo2Until && it.t <= settledEnd }
                    val records = day.mapNotNull { p ->
                        build { OxygenSaturationRecord(at(p.t), off(p.t, zone), Percentage(p.v), Metadata.autoRecorded(dev, HcPlanner.recordId("spo2", st.gen, p.t), 1)) }
                    }
                    put(records)
                    count("SpO₂", records.size)
                    c = to
                }
                st = st.copy(spo2Until = settledEnd)
                save(st)
            }
        }
        if (googleHrv) for (n in nights) {
            // Each night's HRV and SpO₂, placed so Google Health files the night under the wake-up day: the readings
            // from before midnight UTC line up just after it, the rest keep their times (HcPlanner.googleNight). Nothing
            // lands after the night ends, so they're written in the same run as the night itself. Each kind is tracked
            // on its own, and a night already written (v0.7.3 moved whole nights later) is never sent again.
            val fingerprint = "${n.start}|${n.end}|"
            val hrvKey = "baseline-hrvnight-${n.start}"
            val spo2Key = "baseline-spo2night-${n.start}"
            val hrvDone = written[hrvKey]?.startsWith(fingerprint) == true
            val spo2Done = written[spo2Key]?.startsWith(fingerprint) == true
            if (hrvDone && spo2Done) continue
            _status.update { it.copy(progress = "Lining up the night of ${Instant.ofEpochSecond(n.end).atZone(zone).toLocalDate()}…") }
            val s = repo.fetchSamples(n.start, n.end)
            fun place(pts: List<HcPlanner.Point>) = HcPlanner.googleNight(n, pts, zone).ifEmpty {
                pts.filter { it.t >= n.start && it.t < n.end }.map { it.t to it }
            }
            if (!hrvDone) {
                val records = place(HcPlanner.points(s.hrv, 1.0, 200.0)).mapNotNull { (orig, p) ->
                    build { HeartRateVariabilityRmssdRecord(at(p.t), off(p.t, zone), p.v, Metadata.autoRecorded(dev, HcPlanner.recordId("hrv", st.gen, orig), 1)) }
                }
                put(records)
                count("HRV", records.size)
                written[hrvKey] = fingerprint + "lined"
            }
            if (!spo2Done) {
                val records = place(HcPlanner.points(s.spo2, 50.0, 100.0)).mapNotNull { (orig, p) ->
                    build { OxygenSaturationRecord(at(p.t), off(p.t, zone), Percentage(p.v), Metadata.autoRecorded(dev, HcPlanner.recordId("spo2", st.gen, orig), 1)) }
                }
                put(records)
                count("SpO₂", records.size)
                written[spo2Key] = fingerprint + "lined"
            }
        }
        val main = HcPlanner.mainNights(nights, zone)
        // Daily totals grow by adding a record for what's new since the last one (HcPlanner.dayStep).
        val dayProgress = st.days.toMutableMap()
        for (d in HcPlanner.dayTotals(summary, sinceDate, now, zone)) {
            val step = HcPlanner.dayStep(d, dayProgress[d.date.toString()])
            step.full?.let { f ->
                // Zepp lowered this day: replace Baseline's records for it with one of the current totals.
                val day = TimeRangeFilter.between(at(f.start), at(f.start + 86_400L))
                for (type in listOf(StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class)) client.deleteRecords(type, day)
            }
            (step.full ?: step.add)?.let { a ->
                val (s0, s1) = a.start to a.end
                // A whole-day replacement starts where the day's first record did, so its end goes in the id too.
                val key = if (step.full != null) "${d.date}-$s0-$s1" else "${d.date}-$s0"
                val stepsId = HcPlanner.recordId("steps", st.gen, key)
                val distanceId = HcPlanner.recordId("distance", st.gen, key)
                val activeId = HcPlanner.recordId("active", st.gen, key)
                a.steps?.let { v -> add(stepsId, "$s1|$v", build { StepsRecord(at(s0), off(s0, zone), at(s1), off(s1, zone), v, Metadata.autoRecorded(dev, stepsId, 1)) }) }
                a.distanceM?.let { v -> add(distanceId, "$s1|$v", build { DistanceRecord(at(s0), off(s0, zone), at(s1), off(s1, zone), Length.meters(v), Metadata.autoRecorded(dev, distanceId, 1)) }) }
                a.activeKcal?.let { v -> add(activeId, "$s1|$v", build { ActiveCaloriesBurnedRecord(at(s0), off(s0, zone), at(s1), off(s1, zone), Energy.kilocalories(v), Metadata.autoRecorded(dev, activeId, 1)) }) }
            }
            dayProgress[d.date.toString()] = step.progress
        }
        for (v in HcPlanner.restingHr(summary, sinceDate, main, today, zone)) {
            val id = "baseline-rhr-${v.date}"
            add(id, "${v.at}|${v.value}", build { RestingHeartRateRecord(at(v.at), off(v.at, zone), v.value.toLong(), Metadata.autoRecorded(dev, id, version)) })
        }
        for (v in HcPlanner.breathing(summary, sinceDate, main, zone)) {
            val id = HcPlanner.recordId("breathing", st.gen, v.date)
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
        // Daily-total keys are one-off ids; keep the fingerprint map from growing with them.
        val dayKeys = listOf("baseline-steps-", "baseline-distance-", "baseline-active-")
        save(st.copy(written = written.filterKeys { k -> dayKeys.none { k.startsWith(it) } }, nights = nightSpans, days = dayProgress.filterKeys { it >= sinceDate.toString() }))
        return counts
    }

    /** Delete everything Baseline wrote (Health Connect only lets an app delete its own records), and start over. */
    suspend fun removeOurData(): String? = mutex.withLock {
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                val range = TimeRangeFilter.between(Instant.EPOCH, Instant.now().plusSeconds(86_400))
                for (type in TYPES) client.deleteRecords(type, range)
                // Start over, but never reuse an id: copies written later must look new to apps that sync.
                save(State(scheme = 5, gen = load().gen + 1))
            }
        }
        _status.value = Status()
        outcome.exceptionOrNull()?.let { describe(it) }
    }

    /** Forget what was written so the next run copies the whole history window again (records update in place). */
    suspend fun recopy(): String? {
        // Readings and daily totals are add-only, so they're deleted and written again rather than re-sent (Google
        // Health drops updated records). Nights, vitals and workouts keep their fingerprints: only new ones get added.
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val everything = TimeRangeFilter.between(Instant.EPOCH, Instant.now().plusSeconds(2 * 86_400L))
                for (type in listOf(HeartRateRecord::class, HeartRateVariabilityRmssdRecord::class, OxygenSaturationRecord::class,
                    StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class)) client.deleteRecords(type, everything)
                val st = load()
                save(st.copy(
                    hrUntil = 0, hrvUntil = 0, spo2Until = 0, days = emptyMap(), gen = st.gen + 1, hrCheck = true,
                    written = st.written.filterKeys { !it.startsWith("baseline-hrvnight-") },
                ))
            }
        }
        return run()
    }

    private suspend fun put(records: List<Record>) {
        for (chunk in records.chunked(500)) client.insertRecords(chunk)
    }

    /**
     * Adds the clock hours of heart rate in [from, until] that hold none of Baseline's readings although the bridge has
     * some. Returns how many readings were added. Throws if Baseline can't read its own heart rate back.
     */
    private suspend fun refillHeartRate(from: Long, until: Long, zone: ZoneId, dev: Device, gen: Int): Int {
        val covered = HashSet<Long>()
        var token: String? = null
        do {
            // From an hour early, so a record that starts before [from] still counts its hour as there.
            val page = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(at(from - 3600), at(until + 1)),
                dataOriginFilter = setOf(DataOrigin(context.packageName)), pageSize = 5000, pageToken = token))
            for (r in page.records) for (s in r.samples) covered += HcPlanner.floorHour(s.time.epochSecond)
            token = page.pageToken
        } while (token != null)
        var added = 0
        var cursor = HcPlanner.floorHour(from)
        while (cursor <= until) {
            val to = minOf(until + 1, cursor + 7 * 86_400L)
            // Only ask the bridge about weeks with an empty hour (most weeks have none, or only the watch's own gaps).
            if (generateSequence(cursor) { it + 3600 }.takeWhile { it < to }.any { it !in covered }) {
                _status.update { it.copy(progress = "Checking heart rate from ${Instant.ofEpochSecond(cursor).atZone(zone).toLocalDate()}…") }
                val s = repo.fetchSamples(cursor, to)
                val records = HcPlanner.missingHours(HcPlanner.points(s.heartRate, 1.0, 300.0), covered, cursor, to).mapNotNull { h ->
                    build { HeartRateRecord(at(h.start), off(h.start, zone), at(h.end), off(h.end, zone),
                        h.samples.map { HeartRateRecord.Sample(at(it.t), it.v.toLong()) }, Metadata.autoRecorded(dev, HcPlanner.recordId("hrf", gen, h.start), 1)) }
                }
                put(records)
                added += records.sumOf { it.samples.size }
            }
            cursor = to
        }
        return added
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
