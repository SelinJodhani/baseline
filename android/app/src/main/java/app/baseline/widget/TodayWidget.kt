package app.baseline.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
import app.baseline.R
import app.baseline.insight.Insights
import app.baseline.insight.Level

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

/**
 * Today as a Material CARD (4×2, 4×3, 5×n): readiness ring + verb, today's focus, last night's sleep with
 * its stage bar, a steps ring, and three body-signal chips (HRV / resting HR / breathing, level dots).
 * When the illness flag is up the chips collapse into one "Signals off" chip.
 */
class TodayWidget : BaselineWidget(setOf(DpSize(300.dp, 150.dp), DpSize(300.dp, 230.dp))) {
    companion object {
        /** Refreshes every Baseline widget. The app and RefreshWorker call this after each data refresh. */
        suspend fun updateAll(context: Context) {
            runCatching { TodayWidget().updateAll(context) }
            runCatching { TodayPillWidget().updateAll(context) }
            runCatching { ReadinessWidget().updateAll(context) }
            runCatching { SleepWidget().updateAll(context) }
            runCatching { MoveWidget().updateAll(context) }
            runCatching { SignalsWidget().updateAll(context) }
        }

        /** Asks the launcher to regenerate widget-picker previews (Android 15+, rate-limited by the system). */
        suspend fun publishPreviews(context: Context) {
            if (Build.VERSION.SDK_INT < 35) return
            val m = GlanceAppWidgetManager(context)
            runCatching { m.setWidgetPreviews(TodayWidgetReceiver::class) }
            runCatching { m.setWidgetPreviews(TodayPillWidgetReceiver::class) }
            runCatching { m.setWidgetPreviews(ReadinessWidgetReceiver::class) }
            runCatching { m.setWidgetPreviews(SleepWidgetReceiver::class) }
            runCatching { m.setWidgetPreviews(MoveWidgetReceiver::class) }
            runCatching { m.setWidgetPreviews(SignalsWidgetReceiver::class) }
        }
    }

    @Composable
    override fun Content(context: Context, state: WidgetState, look: Look, size: DpSize) {
        val pad = if (size.height < 130.dp) 12.dp else 16.dp
        CardBox(context, Tab.TODAY, padding = pad) {
            val i = state.insights
            if (i == null) {
                EmptyContent(compact = size.height < 100.dp, onColor = WC.onSurface, mutedColor = WC.onSurfaceVar)
                return@CardBox
            }
            val w = size.width - pad * 2
            val h = size.height - pad * 2
            if (h >= 170.dp) Large(context, state, i, look, w, h) else Medium(context, state, i, look, w, h)
        }
    }

    // ----------------------------------------------------------- 4×3 and taller

    @Composable
    private fun Large(context: Context, state: WidgetState, i: Insights, look: Look, w: Dp, h: Dp) {
        val ring = minOf(108.dp, h * 0.48f, w * 0.36f)
        Column(GlanceModifier.fillMaxSize()) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ReadinessRing(context, i, look, ring)
                HSpace(16.dp)
                Column(GlanceModifier.defaultWeight()) {
                    T(i.focus.title, 17.sp, WC.onSurface, FontWeight.Bold, maxLines = 2)
                    VSpace(4.dp)
                    T(i.focus.body, 12.sp, WC.onSurfaceVar, maxLines = 3)
                    StaleHint(state)
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            val tile = (w - 16.dp) / 2
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                SleepTile(context, i, look, tile, GlanceModifier.defaultWeight())
                HSpace(16.dp)
                StepsTile(context, i, look, GlanceModifier.defaultWeight())
            }
            VSpace(12.dp)
            Signals(i, look, chipWidth = (w - 12.dp) / 3)
        }
    }

    // ----------------------------------------------------------- 4×2 / 3×2

    @Composable
    private fun Medium(context: Context, state: WidgetState, i: Insights, look: Look, w: Dp, h: Dp) {
        val ring = minOf(h, 100.dp, w * 0.36f)
        val right = w - ring - 14.dp
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            ReadinessRing(context, i, look, ring)
            HSpace(14.dp)
            Column(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                T(i.focus.title, 15.sp, WC.onSurface, FontWeight.Bold)
                if (state.staleHours != null) StaleHint(state)
                VSpace(8.dp)
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val sleepW = right * 0.56f
                    Column(GlanceModifier.defaultWeight()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(ImageProvider(R.drawable.ic_w_moon), null, GlanceModifier.size(12.dp), colorFilter = ColorFilter.tint(WC.a3))
                            HSpace(4.dp)
                            T(sleepText(i, look), 13.sp, WC.onSurface, FontWeight.Bold)
                        }
                        VSpace(5.dp)
                        StageBar(context, i.sleep.last, sleepW - 12.dp, 6.dp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val a = i.activity
                        RingArt(context, 24.dp, 4.dp, WidgetWords.stepsFraction(a.stepsToday, a.stepGoal), WC.a1, WC.track, "Steps")
                        HSpace(6.dp)
                        T(if (look.numbers) WidgetWords.steps(a.stepsToday) else WidgetWords.steps(a.stepsToday, a.stepGoal), 13.sp, WC.onSurface, FontWeight.Bold)
                    }
                }
                if (h >= 100.dp) {
                    VSpace(10.dp)
                    Signals(i, look, chipWidth = (right - 12.dp) / 3)
                }
            }
        }
    }

    // ----------------------------------------------------------- pieces

    @Composable
    private fun ReadinessRing(context: Context, i: Insights, look: Look, size: Dp) {
        val stroke = (size * 0.085f).coerceIn(6.dp, 10.dp)
        val inner = (size - stroke * 2 - 8.dp).value
        val verb = i.readiness.headline
        val caption = readinessCaption(i, look)
        val multi = verb.contains(' ') && verb.length > 9
        val fit = if (multi) verb.split(' ').maxBy { it.length } + "  " else verb
        RingArt(context, size, stroke, readinessFraction(i), WC.a1, WC.track, "Readiness: $verb" + (caption?.let { ", $it" } ?: "")) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                T(verb, heroSp(context, fit, inner * 0.94f, size.value * (if (multi) 0.15f else 0.2f)), WC.onSurface, FontWeight.Bold,
                    maxLines = if (multi) 2 else 1, align = TextAlign.Center)
                if (caption != null) T(caption, (size.value * 0.11f).coerceIn(10f, 13f).sp, WC.onSurfaceVar, FontWeight.Medium, align = TextAlign.Center)
            }
        }
    }

    private fun sleepText(i: Insights, look: Look) =
        if (look.numbers) WidgetWords.duration(i.sleep.asleepMin) else WidgetWords.sleep(i.sleep.level, i.sleep.asleepMin)

    @Composable
    private fun SleepTile(context: Context, i: Insights, look: Look, width: Dp, modifier: GlanceModifier) {
        Column(modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.ic_w_moon), null, GlanceModifier.size(12.dp), colorFilter = ColorFilter.tint(WC.a3))
                HSpace(4.dp)
                T("Last night", 11.sp, WC.onSurfaceVar, FontWeight.Medium)
            }
            VSpace(2.dp)
            Row(verticalAlignment = Alignment.Bottom) {
                T(sleepText(i, look), if (look.numbers) 18.sp else 16.sp, WC.onSurface, FontWeight.Bold)
                if (look.numbers) {
                    HSpace(4.dp)
                    T("/ ${WidgetWords.durationShort(i.sleep.needMin)}", 11.sp, WC.onSurfaceVar, modifier = GlanceModifier.padding(bottom = 3.dp))
                }
            }
            VSpace(6.dp)
            StageBar(context, i.sleep.last, width, 6.dp)
        }
    }

    @Composable
    private fun StepsTile(context: Context, i: Insights, look: Look, modifier: GlanceModifier) {
        val a = i.activity
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            RingArt(context, 42.dp, 5.dp, WidgetWords.stepsFraction(a.stepsToday, a.stepGoal), WC.a1, WC.track, "Steps")
            HSpace(10.dp)
            Column {
                T("Steps", 11.sp, WC.onSurfaceVar, FontWeight.Medium)
                VSpace(2.dp)
                if (look.numbers) T(WidgetWords.stepsFull(a.stepsToday), 18.sp, WC.onSurface, FontWeight.Bold)
                else T(WidgetWords.steps(a.stepsToday, a.stepGoal), 15.sp, WC.onSurface, FontWeight.Bold)
            }
        }
    }

    /** HRV / RHR / breathing chips, or one "Signals off" chip while the illness flag is up. */
    @Composable
    private fun Signals(i: Insights, look: Look, chipWidth: Dp) {
        if (i.illnessFlag) {
            ShapeBox(ImageProvider(R.drawable.wshape_pill), WC.alert, GlanceModifier.fillMaxWidth().height(26.dp).padding(horizontal = 10.dp)) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                    Dot(Level.Low, 6.dp)
                    HSpace(6.dp)
                    T("Signals off · take it easy", 11.sp, WC.onAlert, FontWeight.Bold)
                }
            }
            return
        }
        val withValues = look.numbers && chipWidth >= 74.dp
        Row(GlanceModifier.fillMaxWidth()) {
            SignalChip(SignalKind.Hrv.short, if (withValues) WidgetWords.signalValue(SignalKind.Hrv, i.hrv.today) else null, i.hrv.level, GlanceModifier.defaultWeight())
            HSpace(6.dp)
            SignalChip(SignalKind.Rhr.short, if (withValues) WidgetWords.signalValue(SignalKind.Rhr, i.rhr.today) else null, i.rhr.level, GlanceModifier.defaultWeight())
            HSpace(6.dp)
            SignalChip(SignalKind.Breathing.short, if (withValues) WidgetWords.signalValue(SignalKind.Breathing, i.resp.today) else null, i.resp.level, GlanceModifier.defaultWeight())
        }
    }
}
