package app.baseline.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.width

class ReadinessWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ReadinessWidget()
}

/** Readiness at a glance: gauge + score; adds the headline (2×2) and today's focus (wide). */
class ReadinessWidget : BaselineWidget(Tab.TODAY, setOf(TINY, SQUARE, WIDE)) {
    companion object {
        val TINY = DpSize(60.dp, 60.dp)
        val SQUARE = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(200.dp, 100.dp)
    }

    @Composable
    override fun Content(context: Context, state: WidgetState, size: DpSize) {
        val i = state.insights ?: return
        val r = i.readiness
        when {
            size.width >= WIDE.width && size.height < 140.dp -> Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                GaugeWithScore(context, i, 84.dp, 8.dp, 26.sp, showCaption = true)
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Value(headline(i), 18.sp, WColors.level(r.level))
                    Spacer(GlanceModifier.height(2.dp))
                    Label(i.focus.title, 12.sp, maxLines = 2)
                    StaleHint(state)
                }
            }
            size.width >= SQUARE.width && size.height >= SQUARE.height -> Column(
                GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically,
            ) {
                val g = if (size.height >= 150.dp) 104.dp else 80.dp
                GaugeWithScore(context, i, g, if (g > 90.dp) 10.dp else 8.dp, if (g > 90.dp) 30.sp else 24.sp, showCaption = false)
                Value(headline(i), 14.sp, WColors.level(r.level))
                StaleHint(state)
            }
            else -> Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                val g = minOf(size.width, size.height) - 8.dp
                GaugeWithScore(context, i, g.coerceAtLeast(40.dp), 6.dp, 18.sp, showCaption = false)
            }
        }
    }
}
