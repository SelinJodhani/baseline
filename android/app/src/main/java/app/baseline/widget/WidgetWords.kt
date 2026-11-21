package app.baseline.widget

import app.baseline.insight.Level
import app.baseline.insight.Signal
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure text for widgets: number formats, and the words shown instead of numbers when numbers are
 * hidden (calm mode, or the lock screen). No Android dependencies, so it is unit-tested on the JVM.
 */
object WidgetWords {

    // ------------------------------------------------------------- numbers

    fun duration(min: Int?): String = when {
        min == null -> "—"
        min < 60 -> "${min}m"
        else -> "${min / 60}h ${"%02d".format(min % 60)}m"
    }

    /** Short duration for tight places: "7h 5m", "8h", "45m". */
    fun durationShort(min: Int?): String = when {
        min == null -> "—"
        min < 60 -> "${min}m"
        min % 60 == 0 -> "${min / 60}h"
        else -> "${min / 60}h ${min % 60}m"
    }

    fun steps(n: Int?): String = when {
        n == null -> "—"
        n >= 10_000 -> "%.1fk".format(Locale.US, n / 1000.0).replace(".0k", "k")
        else -> "%,d".format(Locale.US, n)
    }

    fun stepsFull(n: Int?): String = if (n == null) "—" else "%,d".format(Locale.US, n)

    /** "22:45" or "10:45 pm", following the phone's clock setting. */
    fun clock(t: LocalTime, is24h: Boolean): String =
        if (is24h) "%d:%02d".format(t.hour, t.minute)
        else "%d:%02d %s".format(if (t.hour % 12 == 0) 12 else t.hour % 12, t.minute, if (t.hour < 12) "am" else "pm")

    fun signalValue(kind: SignalKind, v: Double?): String = when {
        v == null -> "—"
        kind == SignalKind.Breathing -> "%.1f".format(Locale.US, v)
        else -> v.roundToInt().toString()
    }

    // ------------------------------------------------------------- words

    fun sleep(level: Level, asleepMin: Int?): String = when {
        asleepMin == null -> "No sleep yet"
        else -> when (level) {
            Level.Great -> "Full night"
            Level.Good -> "Slept well"
            Level.Fair -> "A bit short"
            Level.Low -> "Short night"
            Level.Unknown -> "Slept"
        }
    }

    fun stepsFraction(steps: Int?, goal: Int): Float = (steps ?: 0) / goal.coerceAtLeast(1).toFloat()

    fun steps(steps: Int?, goal: Int): String {
        val f = stepsFraction(steps, goal)
        return when {
            steps == null || steps == 0 -> "Not yet"
            f >= 1f -> "Goal met"
            f >= 0.75f -> "Almost there"
            f >= 0.4f -> "On the way"
            else -> "Getting going"
        }
    }

    fun activeLevel(week: Int, goal: Int): Level = when {
        week >= goal -> Level.Great
        week >= goal / 2 -> Level.Fair
        else -> Level.Low
    }

    fun active(week: Int, goal: Int): String = when (activeLevel(week, goal)) {
        Level.Great -> "On target"
        Level.Fair -> "Building"
        else -> if (week == 0) "Not yet" else "Low"
    }

    /** Signal levels are sign-corrected (Great = better than normal), so one word set fits all three. */
    fun signal(level: Level): String = when (level) {
        Level.Great -> "Better"
        Level.Good -> "Normal"
        Level.Fair -> "A bit off"
        Level.Low -> "Off"
        Level.Unknown -> "No data"
    }

    fun signalsOff(illness: Boolean, levels: List<Level>): Boolean = illness || levels.any { it == Level.Low }

    fun signalsHeadline(illness: Boolean, levels: List<Level>): String = when {
        signalsOff(illness, levels) -> "Signals off"
        levels.all { it == Level.Unknown } -> "Learning"
        else -> "Signals normal"
    }

    /** One quiet line under the headline (wide sizes). */
    fun signalsDetail(illness: Boolean, hrv: Level, rhr: Level, resp: Level): String {
        if (illness) return "Resting HR and breathing are up together"
        val off = buildList {
            if (hrv == Level.Low || hrv == Level.Fair) add("HRV is low")
            if (rhr == Level.Low || rhr == Level.Fair) add("resting HR is up")
            if (resp == Level.Low || resp == Level.Fair) add("breathing is up")
        }
        return when {
            listOf(hrv, rhr, resp).all { it == Level.Unknown } -> "Building your normal range"
            off.isEmpty() -> "All within your normal range"
            else -> off.joinToString(", ").replaceFirstChar { it.uppercase() }
        }
    }

    /** Single-letter weekday labels for the 7 nights ending [today] (oldest first). */
    fun nightLabels(today: LocalDate, locale: Locale = Locale.getDefault()): List<String> =
        (6 downTo 0).map { today.minusDays(it.toLong()).dayOfWeek.getDisplayName(TextStyle.NARROW, locale) }

    /** Fits a hero word into [widthDp]: a font size in dp, clamped to [minDp, maxDp]. */
    fun fitDp(text: String, widthDp: Float, maxDp: Float, minDp: Float, charWidth: Float = 0.58f): Float {
        val n = text.length.coerceAtLeast(1)
        return (widthDp / (n * charWidth)).coerceIn(minDp, maxDp)
    }
}

enum class SignalKind(val label: String, val short: String, val unit: String) {
    Hrv("HRV", "HRV", "ms"),
    Rhr("Resting HR", "RHR", "bpm"),
    Breathing("Breathing", "Breath", "/min"),
}

/** Last [n] readings of a signal's 30-day trend, gaps dropped. */
fun Signal.recent(n: Int): List<Double> = trend.takeLast(n).filterNotNull()
