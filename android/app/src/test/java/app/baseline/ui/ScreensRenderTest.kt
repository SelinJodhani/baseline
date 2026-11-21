package app.baseline.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.baseline.Model
import app.baseline.data.AppSettings
import app.baseline.data.DataState
import app.baseline.data.DemoData
import app.baseline.data.Summary
import app.baseline.insight.InsightEngine
import app.baseline.ui.activity.ActivityScreen
import app.baseline.ui.body.BodyScreen
import app.baseline.ui.common.StatusBarScrim
import app.baseline.ui.sleep.SleepScreen
import app.baseline.ui.story.Metric
import app.baseline.ui.story.StoryScreen
import app.baseline.ui.theme.BaselineTheme
import app.baseline.ui.today.TodayScreen
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * Renders the main screens on the JVM (no emulator) and writes PNGs to app/build/screens for a human
 * to look at. Also guards the status-bar scrim layout bug that veiled every screen in v0.2.0.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = android.app.Application::class, qualifiers = "w400dp-h1400dp-night-xhdpi")
class ScreensRenderTest {
    @get:Rule val rule = createComposeRule()

    private val today = LocalDate.now()

    private fun model(summary: Summary): Model {
        val settings = AppSettings(demo = true, onboarded = true, theme = "dark", dynamicColor = false)
        val e = InsightEngine(summary, settings)
        return Model(e, e.compute(), DataState(summary = summary), settings)
    }

    private val demo by lazy { model(DemoData.generate(today)) }

    /** Like a new watch: HRV only for the last 9 nights. */
    private val sparse by lazy {
        val s = DemoData.generate(today)
        val cut = today.minusDays(9).toString()
        model(s.copy(days = s.days.map { if (it.date < cut) it.copy(sleepHrv = null) else it }))
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        rule.setContent { BaselineTheme(mode = "dark", dynamic = false) { content() } }
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun scrimStaysAtStatusBarHeightInsideSurface() {
        rule.setContent {
            Surface(Modifier.fillMaxSize()) {
                Text("content")
                StatusBarScrim(top = 24.dp, modifier = Modifier.testTag("scrim"))
            }
        }
        rule.onNodeWithTag("scrim").assertHeightIsEqualTo(24.dp)
    }

    @Test fun plainHeightIsStretchedBySurface() {
        // Documents the v0.2.0 bug: Surface propagates min constraints, so height(24.dp) fills the page.
        rule.setContent {
            Surface(Modifier.fillMaxSize()) { Box(Modifier.testTag("old").fillMaxWidth().height(24.dp)) }
        }
        val h = rule.onNodeWithTag("old").fetchSemanticsNode().size.height
        assertNotEquals(with(rule.density) { 24.dp.roundToPx() }, h)
    }

    @Test fun storyHrv() = shoot("story-hrv") { StoryScreen(demo, Metric.Hrv) {} }
    @Test fun storyHrvSparse() = shoot("story-hrv-sparse") { StoryScreen(sparse, Metric.Hrv) {} }
    @Test fun storyReadinessCalibrating() = shoot("story-readiness-calibrating") { StoryScreen(sparse, Metric.Readiness) {} }
    @Test fun storyReadinessEmpty() = shoot("story-readiness-empty") {
        val s = sparse.data.summary!!
        StoryScreen(model(s.copy(days = s.days.map { it.copy(zeppReadiness = null) })), Metric.Readiness) {}
    }
    @Test fun storySteps() = shoot("story-steps") { StoryScreen(demo, Metric.Steps) {} }
    @Test fun storyRhr() = shoot("story-rhr") { StoryScreen(demo, Metric.RestingHr) {} }
    @Test fun today() = shoot("today") { Surface { TodayScreen(demo, {}, {}, {}, {}) } }
    @Test fun sleep() = shoot("sleep") { Surface { SleepScreen(demo, {}, {}) } }
    @Test fun body() = shoot("body") { Surface { BodyScreen(demo, {}, {}) } }
    @Test fun move() = shoot("move") { Surface { ActivityScreen(demo, {}, {}) } }
}
