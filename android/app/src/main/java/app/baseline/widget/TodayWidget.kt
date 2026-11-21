package app.baseline.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
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
import androidx.glance.layout.width
import app.baseline.insight.Insights
import kotlin.math.roundToInt

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

/** The one-glance day: readiness + headline, today's focus, and sleep / steps / HRV. */
class TodayWidget : BaselineWidget(Tab.TODAY, setOf(NARROW, WIDE, TALL)) {
    companion object {
        val NARROW = DpSize(180.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)
        val TALL = DpSize(250.dp, 180.dp)

        /** Refreshes every Baseline widget. The app and RefreshWorker call this after each data refresh. */
        suspend fun updateAll(context: Context) {
            runCatching { TodayWidget().updateAll(context) }
            runCatching { ReadinessWidget().updateAll(context) }
            runCatching { SleepWidget().updateAll(context) }
            runCatching { MoveWidget().updateAll(context) }
        }

        /** Asks the launcher to regenerate widget-picker previews (Android 15+, rate-limited by the system). */
        suspend fun publishPreviews(context: Context) {
            if (Build.VERSION.SDK_INT < 35) return
            val m = GlanceAppWidgetManager(context)
            runCatching { m.setWidgetPreviews(TodayWidgetReceiver::class) }
            runCatching { m.setWidgetPreviews(ReadinessWidgetReceiver::class) }
            runCatching { m.setWidgetPreviews(SleepWidgetReceiver::class) }
            runCatching { m.setWidgetPreviews(MoveWidgetReceiver::class) }
        }
    }

    @Composable
    override fun Content(context: Context, state: WidgetState, size: DpSize) {
        val i = state.insights ?: return
        val tall = size.height >= TALL.height
        val gauge = when { tall -> 112.dp; size.width >= WIDE.width -> 92.dp; else -> 80.dp }
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GaugeWithScore(context, i, gauge, if (tall) 10.dp else 8.dp, if (tall) 32.sp else 26.sp, showCaption = false)
                Value(headline(i), if (tall) 15.sp else 13.sp, WColors.level(i.readiness.level))
            }
            Spacer(GlanceModifier.width(if (tall) 16.dp else 12.dp))
            Column(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                Label("TODAY", 10.sp)
                Value(i.focus.title, if (tall) 17.sp else 14.sp)
                if (tall) {
                    Spacer(GlanceModifier.height(2.dp))
                    Label(i.focus.body, 11.sp, maxLines = 2)
                }
                Spacer(GlanceModifier.height(if (tall) 12.dp else 8.dp))
                Stats(i, compact = size.width < WIDE.width)
                StaleHint(state)
            }
        }
    }

    @Composable
    private fun Stats(i: Insights, compact: Boolean) {
        val v = if (compact) 14.sp else 16.sp
        Row(GlanceModifier.fillMaxWidth()) {
            MiniStat("Sleep", fmtDuration(i.sleep.asleepMin), i.sleep.level, GlanceModifier.defaultWeight(), v)
            MiniStat("Steps", fmtSteps(i.activity.stepsToday), null, GlanceModifier.defaultWeight(), v)
            if (!compact) MiniStat("HRV", i.hrv.today?.roundToInt()?.toString() ?: "—", i.hrv.level, GlanceModifier.defaultWeight(), v)
        }
    }
}
