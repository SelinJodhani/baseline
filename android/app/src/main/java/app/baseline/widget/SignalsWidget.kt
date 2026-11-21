package app.baseline.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
import app.baseline.insight.Insights
import app.baseline.insight.Level
import app.baseline.insight.Signal

class SignalsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SignalsWidget()
}

/**
 * Body signals as a Material CARD (2×2, 4×2): "Signals normal" / "Signals off", then HRV, resting HR and
 * breathing, each with a level dot and its value (or a word). Wider sizes add a 14-day sparkline over
 * your normal band and a one-line explanation.
 */
class SignalsWidget : BaselineWidget(setOf(DpSize(150.dp, 150.dp), DpSize(300.dp, 150.dp))) {

    @Composable
    override fun Content(context: Context, state: WidgetState, look: Look, size: DpSize) {
        val pad = if (size.height < 130.dp) 12.dp else 16.dp
        CardBox(context, Tab.TRENDS, padding = pad) {
            val i = state.insights
            if (i == null) {
                EmptyContent(compact = size.height < 110.dp, onColor = WC.onSurface, mutedColor = WC.onSurfaceVar)
                return@CardBox
            }
            val w = size.width - pad * 2
            val h = size.height - pad * 2
            Body(context, state, i, look, w, h)
        }
    }

    @Composable
    private fun Body(context: Context, state: WidgetState, i: Insights, look: Look, w: Dp, h: Dp) {
        val levels = listOf(i.hrv.level, i.rhr.level, i.resp.level)
        val off = WidgetWords.signalsOff(i.illnessFlag, levels)
        val wide = w >= 220.dp
        val sparkRoom = w >= 150.dp
        Column(GlanceModifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (off) Level.Low else if (levels.all { it == Level.Unknown }) Level.Unknown else Level.Great, 8.dp)
                HSpace(7.dp)
                T(WidgetWords.signalsHeadline(i.illnessFlag, levels), if (wide) 16.sp else 14.sp, WC.onSurface, FontWeight.Bold)
            }
            if (wide && h >= 112.dp) {
                VSpace(1.dp)
                T(
                    if (state.staleHours != null) "Updated ${state.staleHours} h ago" else WidgetWords.signalsDetail(i.illnessFlag, i.hrv.level, i.rhr.level, i.resp.level),
                    12.sp, WC.onSurfaceVar, modifier = GlanceModifier.padding(start = 15.dp),
                )
            }
            Spacer(GlanceModifier.defaultWeight())
            val gap = if (h >= 112.dp) 6.dp else 3.dp
            SignalRow(context, SignalKind.Hrv, i.hrv, look, w, wide, sparkRoom)
            VSpace(gap)
            SignalRow(context, SignalKind.Rhr, i.rhr, look, w, wide, sparkRoom)
            VSpace(gap)
            SignalRow(context, SignalKind.Breathing, i.resp, look, w, wide, sparkRoom)
        }
    }

    @Composable
    private fun SignalRow(context: Context, kind: SignalKind, s: Signal, look: Look, w: Dp, wide: Boolean, sparkRoom: Boolean) {
        val labelW = if (wide) 78.dp else 46.dp
        val valueW = if (look.numbers) (if (wide) 66.dp else 40.dp) else (if (wide) 72.dp else 58.dp)
        val sparkW = w - 6.dp - 8.dp - labelW - 8.dp - valueW
        Row(GlanceModifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(s.level, 6.dp)
            HSpace(8.dp)
            T(if (wide) kind.label else kind.short, 12.sp, WC.onSurfaceVar, FontWeight.Medium, modifier = GlanceModifier.width(labelW))
            if (sparkRoom && sparkW >= 28.dp) {
                Spark(context, s, sparkW, 18.dp)
                HSpace(8.dp)
            } else Spacer(GlanceModifier.defaultWeight())
            Box(GlanceModifier.width(valueW), contentAlignment = Alignment.CenterEnd) {
                if (look.numbers) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        T(WidgetWords.signalValue(kind, s.today), 14.sp, WC.onSurface, FontWeight.Bold)
                        if (wide && s.today != null) {
                            HSpace(3.dp)
                            T(kind.unit, 10.sp, WC.onSurfaceVar, modifier = GlanceModifier.padding(bottom = 2.dp))
                        }
                    }
                } else {
                    T(WidgetWords.signal(s.level), 12.sp, WC.onSurface, FontWeight.Medium, align = TextAlign.End)
                }
            }
        }
    }

    /** 14-day sparkline over the normal band; the latest point takes the level colour. */
    @Composable
    private fun Spark(context: Context, s: Signal, w: Dp, h: Dp) {
        val m = GlanceModifier.width(w).height(h)
        val art = WidgetArt.spark(context, w, h, s.recent(14), s.baseline?.low, s.baseline?.high)
        Box(m) {
            if (art != null) {
                Mask(art.band, WC.track, m)
                Mask(art.line, WC.onSurfaceVar, m)
                Mask(art.dot, WC.level(s.level), m)
            }
        }
    }
}
