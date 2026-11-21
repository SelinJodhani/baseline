package app.baseline.widget

import app.baseline.insight.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

class WidgetWordsTest {
    @Test
    fun numberFormats() {
        assertEquals("7h 05m", WidgetWords.duration(425))
        assertEquals("45m", WidgetWords.duration(45))
        assertEquals("—", WidgetWords.duration(null))
        assertEquals("8h", WidgetWords.durationShort(480))
        assertEquals("7h 5m", WidgetWords.durationShort(425))
        assertEquals("6,240", WidgetWords.steps(6240))
        assertEquals("12.4k", WidgetWords.steps(12_400))
        assertEquals("10k", WidgetWords.steps(10_000))
        assertEquals("14.2", WidgetWords.signalValue(SignalKind.Breathing, 14.24))
        assertEquals("48", WidgetWords.signalValue(SignalKind.Hrv, 47.6))
        assertEquals("22:45", WidgetWords.clock(LocalTime.of(22, 45), is24h = true))
        assertEquals("10:45 pm", WidgetWords.clock(LocalTime.of(22, 45), is24h = false))
        assertEquals("12:05 am", WidgetWords.clock(LocalTime.of(0, 5), is24h = false))
    }

    @Test
    fun wordsReplaceNumbers() {
        assertEquals("No sleep yet", WidgetWords.sleep(Level.Unknown, null))
        assertEquals("Short night", WidgetWords.sleep(Level.Low, 300))
        assertEquals("Not yet", WidgetWords.steps(0, 8000))
        assertEquals("Goal met", WidgetWords.steps(8000, 8000))
        assertEquals("Almost there", WidgetWords.steps(6500, 8000))
        assertEquals("On target", WidgetWords.active(150, 150))
        assertEquals("Building", WidgetWords.active(80, 150))
        assertEquals("Normal", WidgetWords.signal(Level.Good))
    }

    @Test
    fun signalsHeadline() {
        val normal = listOf(Level.Good, Level.Great, Level.Fair)
        assertFalse(WidgetWords.signalsOff(false, normal))
        assertEquals("Signals normal", WidgetWords.signalsHeadline(false, normal))
        assertTrue(WidgetWords.signalsOff(true, normal))
        assertEquals("Signals off", WidgetWords.signalsHeadline(false, listOf(Level.Good, Level.Low, Level.Good)))
        assertEquals("Learning", WidgetWords.signalsHeadline(false, List(3) { Level.Unknown }))
        assertEquals("HRV is low, breathing is up", WidgetWords.signalsDetail(false, Level.Fair, Level.Good, Level.Low))
    }

    @Test
    fun nightLabelsEndToday() {
        val labels = WidgetWords.nightLabels(LocalDate.of(2026, 11, 21), Locale.US) // a Saturday
        assertEquals(7, labels.size)
        assertEquals("S", labels.last())
        assertEquals("S", labels.first()) // Sunday the 15th
    }

    @Test
    fun heroFitShrinksLongWords() {
        val short = WidgetWords.fitDp("Push", 80f, 28f, 9f)
        val long = WidgetWords.fitDp("Calibrating", 80f, 28f, 9f)
        assertEquals(28f, short, 0.01f)
        assertTrue(long < short && long >= 9f)
    }
}
