package app.baseline.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.baseline.ui.theme.Harmonize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HarmonizeTest {
    private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    @Test fun statusColoursLeanTowardTheWallpaperButKeepTheirMeaning() {
        val brown = Color(0xFF7A5A3E); val blue = Color(0xFF415F91)
        val green = Color(0xFF14A37A); val amber = Color(0xFFD08A00); val red = Color(0xFFD9483B)
        for ((name, c) in listOf("green" to green, "amber" to amber, "red" to red)) {
            println("$name ${hex(c)} → brown ${hex(Harmonize.toward(c, brown))}  blue ${hex(Harmonize.toward(c, blue))}")
        }
        assertNotEquals(hex(green), hex(Harmonize.toward(green, brown)))
        // Greys stay put.
        assertEquals(hex(Color(0xFF808080)), hex(Harmonize.toward(Color(0xFF808080), brown)))
    }
}
