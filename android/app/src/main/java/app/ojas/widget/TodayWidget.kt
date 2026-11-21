package app.ojas.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.ojas.MainActivity
import app.ojas.OjasApp
import app.ojas.insight.InsightEngine
import app.ojas.insight.Insights
import app.ojas.insight.Level
import app.ojas.ui.common.Fmt
import kotlinx.coroutines.flow.first

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as OjasApp
        if (app.repository.state.value.summary == null) app.repository.loadInitial()
        val summary = app.repository.state.value.summary
        val settings = app.store.settings.first()
        val insights = summary?.let { runCatching { InsightEngine(it, settings).compute() }.getOrNull() }
        provideContent { GlanceTheme { Content(insights) } }
    }

    @Composable
    private fun Content(i: Insights?) {
        val onSurface = GlanceTheme.colors.onSurface
        val muted = GlanceTheme.colors.onSurfaceVariant
        Column(
            GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(GlanceTheme.colors.widgetBackground)
                .padding(16.dp).clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (i == null) {
                Text("Open Ojas to connect", style = TextStyle(color = onSurface, fontSize = 14.sp))
                return@Column
            }
            val r = i.readiness
            val score = r.score ?: r.zeppScore
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(score?.toString() ?: "—", style = TextStyle(color = ColorProvider(levelColor(r.level)), fontSize = 40.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.width(10.dp))
                Column {
                    Text(if (r.score == null) "Zepp readiness" else r.headline, style = TextStyle(color = onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                    Text(i.focus.title, style = TextStyle(color = muted, fontSize = 12.sp), maxLines = 1)
                }
            }
            Spacer(GlanceModifier.height(10.dp))
            Row {
                Column(GlanceModifier.defaultWeight()) {
                    Text("Sleep", style = TextStyle(color = muted, fontSize = 11.sp))
                    Text(Fmt.duration(i.sleep.asleepMin), style = TextStyle(color = onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                }
                Column(GlanceModifier.defaultWeight()) {
                    Text("Steps", style = TextStyle(color = muted, fontSize = 11.sp))
                    Text(i.activity.stepsToday?.let { "%,d".format(it) } ?: "—", style = TextStyle(color = onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                }
                Column(GlanceModifier.defaultWeight()) {
                    Text("HRV", style = TextStyle(color = muted, fontSize = 11.sp))
                    Text(i.hrv.today?.let { "${it.toInt()}" } ?: "—", style = TextStyle(color = onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }

    private fun levelColor(l: Level) = when (l) {
        Level.Great -> Color(0xFF14A37A); Level.Good -> Color(0xFF6E9E1E); Level.Fair -> Color(0xFFD08A00)
        Level.Low -> Color(0xFFD9483B); Level.Unknown -> Color(0xFF6F7975)
    }

    companion object {
        suspend fun updateAll(context: Context) = runCatching { TodayWidget().updateAll(context) }
    }
}
