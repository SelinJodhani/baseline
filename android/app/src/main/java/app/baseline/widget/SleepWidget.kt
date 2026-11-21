package app.baseline.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import app.baseline.insight.InsightEngine
import app.baseline.insight.Level

class SleepWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SleepWidget()
}

/** Last night against your need, the stage split, and what to do tonight. */
class SleepWidget : BaselineWidget(Tab.SLEEP, setOf(SQUARE, WIDE)) {
    companion object {
        val SQUARE = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)
    }

    @Composable
    override fun Content(context: Context, state: WidgetState, size: DpSize) {
        val s = state.insights?.sleep ?: return
        val wide = size.width >= WIDE.width
        val last = s.last
        val barW = (size.width - 28.dp).coerceAtLeast(60.dp)
        if (wide) {
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Header(s.level)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Value(fmtDuration(s.asleepMin), 28.sp)
                        Spacer(GlanceModifier.width(6.dp))
                        Label("of ${fmtDuration(s.needMin)}", 12.sp)
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    StageBar(context, last, (barW * 0.55f))
                    StaleHint(state)
                }
                Spacer(GlanceModifier.width(14.dp))
                Column {
                    MiniStat("Tonight", s.suggestedBedtime?.let { InsightEngine.fmtTime(it) } ?: "—", null, valueSize = 15.sp)
                    Spacer(GlanceModifier.height(8.dp))
                    MiniStat("Debt · 7d", if (s.debt7Min > 0) fmtDuration(s.debt7Min) else "None", debtLevel(s.debt7Min), valueSize = 15.sp)
                }
            }
        } else {
            Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Header(s.level)
                Value(fmtDuration(s.asleepMin), if (size.width < 140.dp) 20.sp else 24.sp)
                Label("of ${fmtDuration(s.needMin)} need", 10.sp)
                Spacer(GlanceModifier.height(8.dp))
                StageBar(context, last, barW)
                Spacer(GlanceModifier.height(6.dp))
                if (s.debt7Min >= 60) Label("${fmtDuration(s.debt7Min)} debt", 10.sp, WColors.level(debtLevel(s.debt7Min)))
                else s.suggestedBedtime?.let { Label("Bed by ${InsightEngine.fmtTime(it)}", 10.sp) }
                StaleHint(state)
            }
        }
    }

    private fun debtLevel(min: Int) = when { min >= 180 -> Level.Fair; min > 60 -> Level.Good; else -> Level.Great }

    @Composable
    private fun Header(level: Level) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LevelDot(level, 6.dp)
            Spacer(GlanceModifier.width(5.dp))
            Label("LAST NIGHT", 10.sp)
        }
    }

    @Composable
    private fun StageBar(context: Context, last: app.baseline.data.Sleep?, width: androidx.compose.ui.unit.Dp) {
        val parts = listOf(
            (last?.deepMin ?: 0).toFloat() to WColors.DEEP,
            (last?.lightMin ?: 0).toFloat() to WColors.LIGHT,
            (last?.remMin ?: 0).toFloat() to WColors.REM,
            (last?.awakeMin ?: 0).toFloat() to WColors.AWAKE,
        )
        Image(
            ImageProvider(WidgetArt.stackBar(context, width, 8.dp, parts)),
            contentDescription = "Sleep stages",
            modifier = GlanceModifier.fillMaxWidth().height(8.dp),
        )
    }
}
