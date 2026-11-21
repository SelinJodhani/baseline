package app.baseline.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
import androidx.glance.unit.ColorProvider
import app.baseline.R
import app.baseline.insight.ActivityInsight

class MoveWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MoveWidget()
}

/**
 * Move: two concentric rings, today's steps (outer, primary) and this week's active minutes (inner,
 * tertiary). Square sizes sit in a soft scalloped COOKIE shape; wide sizes become a capsule with the
 * numbers beside the rings.
 */
class MoveWidget : BaselineWidget(setOf(DpSize(150.dp, 150.dp), DpSize(300.dp, 140.dp))) {

    @Composable
    override fun Content(context: Context, state: WidgetState, look: Look, size: DpSize) {
        val wide = size.width >= size.height * 1.6f && size.width >= 180.dp
        if (wide) Capsule(context, state, look, size) else Cookie(context, state, look, size)
    }

    // ----------------------------------------------------------- square: cookie

    @Composable
    private fun Cookie(context: Context, state: WidgetState, look: Look, size: DpSize) {
        val d = minOf(size.width, size.height)
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ShapeBox(
                ImageProvider(WidgetArt.cookie(context, d)), WC.a2Container,
                GlanceModifier.size(d).appWidgetBackground().clickable(openTab(context, Tab.ACTIVITY)),
            ) {
                val a = state.insights?.activity
                if (a == null) {
                    EmptyContent(compact = d < 120.dp, onColor = WC.onA2Container, mutedColor = WC.onA2ContainerVar)
                    return@ShapeBox
                }
                val outer = d * 0.78f
                val stroke = (d * 0.07f).coerceIn(4.dp, 11.dp)
                val clear = (outer - stroke * 4 - stroke * 1.2f - 6.dp).value
                Rings(context, a, outer, stroke) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                        if (look.numbers) {
                            val v = WidgetWords.stepsFull(a.stepsToday)
                            T(v, heroSp(context, v, clear, d.value * 0.14f), WC.onA2Container, FontWeight.Bold, align = TextAlign.Center)
                            if (d >= 110.dp) T("${a.activeWeek} min", (d.value * 0.075f).coerceIn(9f, 12f).sp, WC.a3, FontWeight.Medium, align = TextAlign.Center)
                        } else {
                            val word = WidgetWords.steps(a.stepsToday, a.stepGoal)
                            val multi = word.contains(' ')
                            val fit = if (multi) word.split(' ').maxBy { it.length } + " " else word
                            T(word, heroSp(context, fit, clear, d.value * 0.12f), WC.onA2Container, FontWeight.Bold,
                                maxLines = if (multi) 2 else 1, align = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------- wide: capsule

    @Composable
    private fun Capsule(context: Context, state: WidgetState, look: Look, size: DpSize) {
        val h = minOf(size.height, 150.dp)
        val ringD = h * 0.76f
        val side = (h - ringD) / 2
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ShapeBox(
                ImageProvider(R.drawable.wshape_pill), WC.a2Container,
                GlanceModifier.fillMaxWidth().height(h).appWidgetBackground().clickable(openTab(context, Tab.ACTIVITY))
                    .padding(start = side, end = h * 0.22f),
                contentAlignment = Alignment.CenterStart,
            ) {
                val a = state.insights?.activity
                if (a == null) {
                    EmptyContent(compact = h < 100.dp, onColor = WC.onA2Container, mutedColor = WC.onA2ContainerVar)
                    return@ShapeBox
                }
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Rings(context, a, ringD, (ringD * 0.09f).coerceIn(4.dp, 10.dp))
                    HSpace(if (h < 90.dp) 12.dp else 18.dp)
                    if (h < 90.dp) {
                        // 4×1: one line per ring.
                        Column(GlanceModifier.defaultWeight()) {
                            StatLine(WC.a1, if (look.numbers) "${WidgetWords.steps(a.stepsToday)} steps" else WidgetWords.steps(a.stepsToday, a.stepGoal))
                            StatLine(WC.a3, if (look.numbers) "${a.activeWeek} active min" else "Active: ${WidgetWords.active(a.activeWeek, a.activeGoal).lowercase()}")
                        }
                    } else {
                        Column(GlanceModifier.defaultWeight()) {
                            Stat(WC.a1, "Steps today",
                                if (look.numbers) WidgetWords.stepsFull(a.stepsToday) else WidgetWords.steps(a.stepsToday, a.stepGoal),
                                if (look.numbers) "/ ${WidgetWords.stepsFull(a.stepGoal)}" else null)
                            VSpace(10.dp)
                            Stat(WC.a3, "Active this week",
                                if (look.numbers) "${a.activeWeek}" else WidgetWords.active(a.activeWeek, a.activeGoal),
                                if (look.numbers) "/ ${a.activeGoal} min" else null)
                            StaleHint(state, WC.onA2ContainerVar)
                        }
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------- pieces

    /** Steps (outer) and weekly active minutes (inner), each with an M3 gap between indicator and track. */
    @Composable
    private fun Rings(context: Context, a: ActivityInsight, outer: Dp, stroke: Dp, content: @Composable () -> Unit = {}) {
        val inner = outer - stroke * 2 - stroke * 1.2f
        val stepsF = WidgetWords.stepsFraction(a.stepsToday, a.stepGoal)
        val activeF = a.activeWeek / a.activeGoal.coerceAtLeast(1).toFloat()
        val o = WidgetArt.ring(context, outer, stroke, stepsF)
        val n = WidgetArt.ring(context, inner, stroke, activeF)
        Box(GlanceModifier.size(outer), contentAlignment = Alignment.Center) {
            Mask(o.track, WC.a2Track, GlanceModifier.size(outer))
            Mask(o.indicator, WC.a1, GlanceModifier.size(outer), "Steps ${a.stepsToday ?: 0} of ${a.stepGoal}")
            Mask(n.track, WC.a2Track, GlanceModifier.size(inner))
            Mask(n.indicator, WC.a3, GlanceModifier.size(inner), "Active minutes this week ${a.activeWeek} of ${a.activeGoal}")
            content()
        }
    }

    @Composable
    private fun Swatch(color: ColorProvider) {
        Box(GlanceModifier.size(width = 4.dp, height = 14.dp).background(ImageProvider(R.drawable.wshape_pill), ContentScale.FillBounds, ColorFilter.tint(color))) {}
    }

    @Composable
    private fun Stat(color: ColorProvider, label: String, value: String, suffix: String?) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Swatch(color)
            HSpace(8.dp)
            Column {
                T(label, 11.sp, WC.onA2ContainerVar, FontWeight.Medium)
                Row(verticalAlignment = Alignment.Bottom) {
                    T(value, 17.sp, WC.onA2Container, FontWeight.Bold)
                    if (suffix != null) {
                        HSpace(4.dp)
                        T(suffix, 11.sp, WC.onA2ContainerVar, modifier = GlanceModifier.padding(bottom = 2.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun StatLine(color: ColorProvider, text: String) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.padding(vertical = 1.dp)) {
            Swatch(color)
            HSpace(6.dp)
            T(text, 13.sp, WC.onA2Container, FontWeight.Medium)
        }
    }
}
