package app.baseline.health

import android.content.Context
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
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.baseline.data.Summary
import app.baseline.health.HcCheck.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Reads Health Connect back (only for the check screen, only while it's open) to show who wrote what, and whether
 * what other apps read matches Zepp. Nothing read here is stored or sent anywhere.
 */
class HcInspector(private val context: Context) {

    data class Report(
        val days: Int,
        val rows: List<Pair<Kind, List<Pair<String, Int>>>>,
        val others: Map<String, List<Kind>>,
        val lastNight: HcCheck.NightCheck?,
        /** Today's steps as any app reading Health Connect gets them (merged across apps by priority). */
        val stepsToday: Long?,
        val zeppStepsToday: Long?,
        val readAt: Long,
    )

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun canRead(): Boolean = HealthConnectSync.available(context) &&
        runCatching { client.permissionController.getGrantedPermissions().containsAll(READ) }.getOrDefault(false)

    suspend fun inspect(summary: Summary?, googleHrv: Boolean = false, days: Int = 30): Report = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val range = TimeRangeFilter.between(now.minus(Duration.ofDays(days.toLong())), now)
        val recs = mutableListOf<HcCheck.Rec>()
        val nights = mutableListOf<HcCheck.Night>()
        val hrv = mutableListOf<Pair<Long, Double>>()

        suspend fun <T : Record> each(type: KClass<T>, kind: Kind, readings: (T) -> Int = { 1 }, also: (T) -> Unit = {}) {
            var token: String? = null
            do {
                val page = client.readRecords(ReadRecordsRequest(type, range, pageSize = 5000, pageToken = token))
                for (r in page.records) {
                    recs += HcCheck.Rec(kind, r.metadata.dataOrigin.packageName, readings(r))
                    also(r)
                }
                token = page.pageToken
            } while (token != null)
        }

        each(HeartRateRecord::class, Kind.HEART_RATE, readings = { it.samples.size })
        each(HeartRateVariabilityRmssdRecord::class, Kind.HRV) { hrv += it.time.epochSecond to it.heartRateVariabilityMillis }
        each(OxygenSaturationRecord::class, Kind.SPO2)
        each(SleepSessionRecord::class, Kind.SLEEP) { s ->
            val minutes = s.stages.groupBy { stageName(it.stage) }.mapValues { (_, st) -> st.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toInt() }
            nights += HcCheck.Night(s.startTime.epochSecond, s.endTime.epochSecond, s.metadata.dataOrigin.packageName, minutes)
        }
        each(RestingHeartRateRecord::class, Kind.RESTING_HR)
        each(RespiratoryRateRecord::class, Kind.BREATHING)
        each(StepsRecord::class, Kind.STEPS)
        each(DistanceRecord::class, Kind.DISTANCE)
        each(ActiveCaloriesBurnedRecord::class, Kind.ACTIVE_CALORIES)
        each(ExerciseSessionRecord::class, Kind.WORKOUTS)
        each(WeightRecord::class, Kind.WEIGHT)

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val steps = runCatching {
            client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(today.atStartOfDay(zone).toInstant(), now)))[StepsRecord.COUNT_TOTAL]
        }.getOrNull()
        val rows = HcCheck.byApp(recs, context.packageName)
        Report(
            days = days,
            rows = rows,
            others = HcCheck.otherWriters(rows, context.packageName),
            // Since v0.7.5 a night's HRV stays inside the night even with the Google Health option (HcPlanner.googleNight),
            // so the night is read as it is; [googleHrv] no longer changes the window.
            lastNight = HcCheck.lastNight(nights, hrv, summary, zone),
            stepsToday = steps,
            zeppStepsToday = summary?.days?.firstOrNull { it.date == today.toString() }?.steps?.toLong(),
            readAt = now.epochSecond,
        )
    }

    fun isSelf(pkg: String): Boolean = pkg == context.packageName

    /** A person-friendly name for the app that wrote a record. */
    fun label(pkg: String): String = when {
        pkg == context.packageName -> "Baseline"
        pkg.startsWith("com.android.healthconnect.phone") -> "This phone"
        pkg == HealthConnectSync.ZEPP -> "Zepp"
        else -> runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    companion object {
        val READ: Set<String> = HealthConnectSync.TYPES.map { HealthPermission.getReadPermission(it) }.toSet()

        private fun stageName(type: Int): String = when (type) {
            SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
            SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
            SleepSessionRecord.STAGE_TYPE_REM -> "rem"
            SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "awake"
            else -> "asleep"
        }
    }
}
