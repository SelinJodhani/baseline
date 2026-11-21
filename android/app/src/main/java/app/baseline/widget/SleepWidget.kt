package app.baseline.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
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
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
import app.baseline.R
import app.baseline.insight.Insights

class SleepWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SleepWidget()
}

/**
 * Sleep as a SQUIRCLE (2×2, 4×2) in the tertiary container: last night against your need, the stage
 * bar, tonight's bedtime, and (when there's room) the last seven nights as capsule bars against a dashed
 * sleep-need line.
 */
class SleepWidget : BaselineWidget(setOf(DpSize(150.dp, 170.dp), DpSize(300.dp, 150.dp))) {

    @Composable
    override fun Content(context: Context, state: WidgetState, look: Look, size: DpSize) {
        val wide = size.width >= 250.dp && size.width >= size.height * 1.5f
        val shape = WidgetArt.squircle(context, size.width, size.height, radiusFraction = if (wide) 0.34f else 0.42f)
        val pad = if (minOf(size.width, size.height) < 130.dp) 14.dp else 18.dp
        ShapeBox(
            ImageProvider(shape), WC.a3Container,
            GlanceModifier.fillMaxSize().appWidgetBackground().clickable(openTab(context, Tab.SLEEP)).padding(pad),
            contentAlignment = Alignment.CenterStart,
        ) {
            val i = state.insights
            if (i == null) {
                EmptyContent(compact = size.height < 110.dp, onColor = WC.onA3Container, mutedColor = WC.onA3ContainerVar)
                return@ShapeBox
            }
            val w = size.width - pad * 2
            val h = size.height - pad * 2
            if (wide) Wide(context, state, i, look, w, h) else Square(context, state, i, look, w, h)
        }
    }

    @Composable
    private fun Square(context: Context, state: WidgetState, i: Insights, look: Look, w: Dp, h: Dp) {
        val small = w < 110.dp
        Column(GlanceModifier.fillMaxSize()) {
            Header()
            VSpace(2.dp)
            Hero(i, look, if (small) 22f else 26f)
            Spacer(GlanceModifier.defaultWeight())
            StageBar(context, i.sleep.last, w, 8.dp, WC.a3Track)
            if (h >= 140.dp && state.nights7.isNotEmpty()) {
                VSpace(10.dp)
                Nights(context, state, i, w, 26.dp, labels = false)
            }
            VSpace(8.dp)
            Footer(state, i, look)
        }
    }

    @Composable
    private fun Wide(context: Context, state: WidgetState, i: Insights, look: Look, w: Dp, h: Dp) {
        val gap = 20.dp
        val col = (w - gap) / 2
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.width(col).fillMaxHeight()) {
                Header()
                VSpace(2.dp)
                Hero(i, look, 28f)
                Spacer(GlanceModifier.defaultWeight())
                StageBar(context, i.sleep.last, col, 8.dp, WC.a3Track)
                VSpace(8.dp)
                Footer(state, i, look)
            }
            HSpace(gap)
            Column(GlanceModifier.width(col).fillMaxHeight()) {
                T("7 nights", 12.sp, WC.onA3ContainerVar, FontWeight.Medium)
                Spacer(GlanceModifier.defaultWeight())
                if (state.nights7.isNotEmpty()) Nights(context, state, i, col, (h - 40.dp).coerceIn(28.dp, 84.dp), labels = true)
            }
        }
    }

    @Composable
    private fun Header() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.drawable.ic_w_moon), null, GlanceModifier.size(13.dp), colorFilter = ColorFilter.tint(WC.onA3Container))
            HSpace(5.dp)
            T("Last night", 12.sp, WC.onA3ContainerVar, FontWeight.Medium)
        }
    }

    @Composable
    private fun Hero(i: Insights, look: Look, sizeSp: Float) {
        val s = i.sleep
        // One child for the parent column (Glance containers hold at most 10 children).
        Column {
            if (look.numbers) {
                T(WidgetWords.duration(s.asleepMin), sizeSp.sp, WC.onA3Container, FontWeight.Bold)
                T("of ${WidgetWords.durationShort(s.needMin)} need", 12.sp, WC.onA3ContainerVar)
            } else {
                T(WidgetWords.sleep(s.level, s.asleepMin), (sizeSp - 4f).sp, WC.onA3Container, FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun Footer(state: WidgetState, i: Insights, look: Look) {
        if (state.staleHours != null) { StaleHint(state, WC.onA3ContainerVar, 11.sp); return }
        val bed = i.sleep.suggestedBedtime ?: return
        T("Bed by ${WidgetWords.clock(bed, look.is24h)}", 12.sp, WC.onA3Container, FontWeight.Medium)
    }

    /** The last 7 nights: earlier nights soft, last night strong, dashed line at your need. */
    @Composable
    private fun Nights(context: Context, state: WidgetState, i: Insights, w: Dp, h: Dp, labels: Boolean) {
        val bars = WidgetArt.nightBars(context, w, h, state.nights7, i.sleep.needMin)
        val m = GlanceModifier.width(w).height(h)
        Column {
            Box(m) {
                Mask(bars.need, WC.onA3ContainerVar, m)
                Mask(bars.earlier, WC.a3Mid, m)
                Mask(bars.last, WC.a3, m, "Last 7 nights of sleep against your need")
            }
            if (labels) {
                VSpace(3.dp)
                Row(GlanceModifier.width(w)) {
                    WidgetWords.nightLabels(i.today).forEach { d ->
                        T(d, 10.sp, WC.onA3ContainerVar, FontWeight.Medium, align = TextAlign.Center, modifier = GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }
}
