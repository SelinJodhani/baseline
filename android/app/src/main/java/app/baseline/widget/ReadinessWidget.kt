package app.baseline.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
import app.baseline.R

class ReadinessWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ReadinessWidget()
}

/**
 * Readiness as a CIRCLE (1×1 and 2×2): a primary-container disc, a ring in the primary accent filled to
 * the score, and today's verb in the middle. The score sits under the verb when numbers are shown
 * ("Zepp 72" while Baseline's own score is still calibrating).
 */
class ReadinessWidget : BaselineWidget(setOf(DpSize(60.dp, 60.dp), DpSize(130.dp, 130.dp))) {

    @Composable
    override fun Content(context: Context, state: WidgetState, look: Look, size: DpSize) {
        val d = minOf(size.width, size.height)
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ShapeBox(
                ImageProvider(R.drawable.wshape_circle), WC.a1Container,
                GlanceModifier.size(d).appWidgetBackground().clickable(openTab(context, Tab.TODAY)),
            ) {
                val i = state.insights
                if (i == null) {
                    EmptyContent(compact = d < 100.dp, onColor = WC.onA1Container, mutedColor = WC.onA1ContainerVar)
                    return@ShapeBox
                }
                val inset = d * 0.075f
                val ring = d - inset * 2
                val stroke = (d * 0.072f).coerceIn(4.dp, 11.dp)
                val verb = i.readiness.headline
                val caption = readinessCaption(i, look)
                val inner = (ring - stroke * 2 - 6.dp).value
                RingArt(
                    context, ring, stroke, readinessFraction(i), WC.a1, WC.a1Track,
                    description = "Readiness: $verb" + (caption?.let { ", $it" } ?: ""),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                        val tiny = d < 96.dp
                        // In a tiny circle a long word ("Calibrating") would be unreadable: show the score if allowed.
                        val word = if (tiny && verb.length > 8 && caption != null && look.numbers) caption else verb
                        // Multi-word phrases ("Take it easier") wrap to two lines, sized by their longest word.
                        val multi = word.contains(' ') && word.length > 9
                        val fitText = if (multi) word.split(' ').maxBy { it.length } + "  " else word
                        val heroMax = d.value * (if (tiny) 0.24f else if (multi) 0.15f else 0.2f)
                        T(
                            word, heroSp(context, fitText, inner * 0.94f, heroMax),
                            WC.onA1Container, FontWeight.Bold, maxLines = if (multi) 2 else 1, align = TextAlign.Center,
                        )
                        if (!tiny && caption != null) {
                            T(caption, (d.value * 0.092f).coerceIn(10f, 14f).sp, WC.onA1ContainerVar, FontWeight.Medium, align = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}
