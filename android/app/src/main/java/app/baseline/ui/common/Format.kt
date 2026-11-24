package app.baseline.ui.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

object Fmt {
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val hour = DateTimeFormatter.ofPattern("ha", Locale.getDefault())
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val full = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())

    fun duration(min: Int?): String = when {
        min == null -> "—"
        min < 60 -> "${min}m"
        else -> "${min / 60}h ${"%02d".format(min % 60)}m"
    }

    fun hoursShort(min: Double): String = "%.1fh".format(min / 60)
    fun clock(epoch: Long, zone: ZoneId): String = Instant.ofEpochSecond(epoch).atZone(zone).format(time)
    fun hour(epoch: Long, zone: ZoneId): String = Instant.ofEpochSecond(epoch).atZone(zone).format(hour).lowercase()
    fun dayMonth(d: LocalDate): String = d.format(dayMonth)
    fun monthShort(d: LocalDate): String = d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    fun fullDate(d: LocalDate): String = d.format(full)
    fun weekday(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
    fun weekdayShort(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    fun int(v: Double?): String = v?.roundToInt()?.let { "%,d".format(it) } ?: "—"
    fun one(v: Double?): String = v?.let { "%.1f".format(it) } ?: "—"
    fun km(m: Double?): String = m?.let { "%.1f".format(it / 1000) } ?: "—"

    fun ago(epoch: Long?, now: Long = System.currentTimeMillis() / 1000): String {
        if (epoch == null) return "never"
        val s = (now - epoch).coerceAtLeast(0)
        return when {
            s < 90 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} d ago"
        }
    }

    fun workoutName(type: String): String = when {
        type.isBlank() || type.startsWith("unknown") -> "Workout"
        else -> type.replace('_', ' ').replace(':', ' ').trim().replaceFirstChar { it.uppercase() }
    }
}
