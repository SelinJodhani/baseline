package app.baseline.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import app.baseline.insight.ActivityInsight
import app.baseline.insight.Level

class MoveWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MoveWidget()
}

/** Steps ring against the daily goal + active minutes against the weekly target. */
class MoveWidget : BaselineWidget(Tab.ACTIVITY, setOf(SQUARE, WIDE)) {
    companion object {
        val SQUARE = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(200.dp, 100.dp)
    }

    @Composable
    override fun Content(context: Context, state: WidgetState, size: DpSize) {
        val a = state.insights?.activity ?: return
        if (size.width >= WIDE.width) {
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                StepsRing(context, a, 86.dp)
                Spacer(GlanceModifier.width(14.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Label("STEPS", 10.sp)
                    Value("%,d".format(a.stepsToday ?: 0), 22.sp)
                    Label("of %,d".format(a.stepGoal), 11.sp)
                    Spacer(GlanceModifier.height(8.dp))
                    MiniStat("Active · 7d", "${a.activeWeek} / ${a.activeGoal} min", activeLevel(a), valueSize = 14.sp)
                    StaleHint(state)
                }
            }
        } else {
            Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                val ring = (minOf(size.width, size.height) - 48.dp).coerceIn(56.dp, 140.dp)
                StepsRing(context, a, ring, showLabel = ring >= 72.dp)
                Spacer(GlanceModifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LevelDot(activeLevel(a), 6.dp)
                    Spacer(GlanceModifier.width(4.dp))
                    Label("${a.activeWeek}/${a.activeGoal} active min", 10.sp)
                }
            }
        }
    }

    /** Evidence bands: < 75 low, 75–149 building, 150–300 on target, > 300 extra. */
    private fun activeLevel(a: ActivityInsight) = when {
        a.activeWeek >= a.activeGoal -> Level.Great
        a.activeWeek >= a.activeGoal / 2 -> Level.Fair
        else -> Level.Low
    }

    @Composable
    private fun StepsRing(context: Context, a: ActivityInsight, size: Dp, showLabel: Boolean = false) {
        val frac = (a.stepsToday ?: 0) / a.stepGoal.toFloat().coerceAtLeast(1f)
        Box(GlanceModifier.size(size), contentAlignment = Alignment.Center) {
            Image(ImageProvider(WidgetArt.ring(context, size, if (size > 80.dp) 9.dp else 7.dp, frac, WColors.STEPS)), "Steps ${a.stepsToday ?: 0} of ${a.stepGoal}", GlanceModifier.size(size))
            if (showLabel) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Value(fmtSteps(a.stepsToday), if (size > 90.dp) 18.sp else 15.sp)
                Label("steps", 9.sp)
            } else Value(fmtSteps(a.stepsToday), 12.sp)
        }
    }
}
