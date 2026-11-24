package app.baseline.ui

import app.baseline.ui.charts.smoothStages
import org.junit.Assert.assertEquals
import org.junit.Test

class StageSmoothingTest {
    private fun t(a: Int, b: Int, s: String) = Triple(a * 60L, b * 60L, s)

    @Test fun shortBlipsJoinTheBlockBefore() {
        val night = listOf(t(0, 30, "light"), t(30, 32, "awake"), t(32, 60, "light"), t(60, 61, "rem"), t(61, 90, "deep"), t(90, 120, "rem"))
        assertEquals(listOf(t(0, 61, "light"), t(61, 90, "deep"), t(90, 120, "rem")), smoothStages(night))
    }

    @Test fun realStagesSurvive() {
        val night = listOf(t(0, 20, "light"), t(20, 26, "awake"), t(26, 60, "deep"))
        assertEquals(night, smoothStages(night))
    }
}
