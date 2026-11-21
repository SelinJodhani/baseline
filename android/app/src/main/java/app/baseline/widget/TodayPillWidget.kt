package app.baseline.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import app.baseline.R
import app.baseline.insight.Insights

class TodayPillWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayPillWidget()
}

/**
 * Today as a one-line CAPSULE (4×1 / 5×1): the readiness verb in a primary chip with a mini ring,
 * today's focus, last night's sleep and today's steps (a mini ring against the goal).
 */
class TodayPillWidget : BaselineWidget(setOf(DpSize(260.dp, 56.dp), DpSize(340.dp, 56.dp))) {

    @Composable
    override fun Content(context: Context, state: WidgetState, look: Look, size: DpSize) {
        val pillH = minOf(size.height, 60.dp)
        val padH = (pillH * 0.16f).coerceIn(6.dp, 10.dp)
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ShapeBox(
                ImageProvider(R.drawable.wshape_pill), WC.surface,
                GlanceModifier.fillMaxWidth().height(pillH).appWidgetBackground().clickable(openTab(context, Tab.TODAY)).padding(horizontal = padH),
            ) {
                val i = state.insights
                if (i == null) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                        T("Baseline", 14.sp, WC.onSurface, FontWeight.Bold)
                        HSpace(8.dp)
                        T("Tap to connect", 13.sp, WC.onSurfaceVar)
                    }
                } else Strip(context, i, look, size.width - padH * 2, pillH)
            }
        }
    }

    @Composable
    private fun Strip(context: Context, i: Insights, look: Look, width: androidx.compose.ui.unit.Dp, pillH: androidx.compose.ui.unit.Dp) {
        val roomy = width >= 280.dp
        val chipH = (pillH - 14.dp).coerceIn(26.dp, 40.dp)
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Verb chip
            ShapeBox(ImageProvider(R.drawable.wshape_pill), WC.a1Container, GlanceModifier.height(chipH).padding(start = if (roomy) 7.dp else 12.dp, end = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (roomy) {
                        RingArt(context, chipH - 12.dp, 3.dp, readinessFraction(i), WC.a1, WC.a1Track, "Readiness")
                        HSpace(6.dp)
                    }
                    T(i.readiness.headline, 14.sp, WC.onA1Container, FontWeight.Bold)
                }
            }
            HSpace(10.dp)
            if (roomy) {
                T(i.focus.title, 13.sp, WC.onSurface, FontWeight.Medium, modifier = GlanceModifier.defaultWeight())
                HSpace(10.dp)
            } else Spacer(GlanceModifier.defaultWeight())
            // Sleep (Glance rows take at most 10 children, so each stat is its own row)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.ic_w_moon), "Sleep", GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(WC.a3))
                HSpace(4.dp)
                if (look.numbers) T(WidgetWords.durationShort(i.sleep.asleepMin), 13.sp, WC.onSurface, FontWeight.Bold)
                else Dot(i.sleep.level, 7.dp)
            }
            HSpace(10.dp)
            // Steps
            val a = i.activity
            Row(verticalAlignment = Alignment.CenterVertically) {
                RingArt(context, 15.dp, 3.dp, WidgetWords.stepsFraction(a.stepsToday, a.stepGoal), WC.a1, WC.track, "Steps")
                if (look.numbers) {
                    HSpace(4.dp)
                    T(stepsK(a.stepsToday), 13.sp, WC.onSurface, FontWeight.Bold)
                }
            }
        }
    }

    private fun stepsK(n: Int?): String = when {
        n == null -> "—"
        n < 1000 -> n.toString()
        else -> "%.1fk".format(java.util.Locale.US, n / 1000.0).replace(".0k", "k")
    }
}
