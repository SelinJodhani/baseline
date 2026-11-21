package app.ojas.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.ojas.MainActivity
import app.ojas.OjasApp
import app.ojas.data.DemoData
import app.ojas.data.AppSettings
import app.ojas.insight.InsightEngine
import app.ojas.insight.Insights
import app.ojas.insight.Level
import kotlinx.coroutines.flow.first

/** Intent extra MainActivity reads to open a tab: 0 Today, 1 Sleep, 2 Trends, 3 Activity. */
const val EXTRA_TAB = "ojas.tab"

object Tab {
    const val TODAY = 0
    const val SLEEP = 1
    const val TRENDS = 2
    const val ACTIVITY = 3
}

/** Everything a widget renders, computed once per update. */
data class WidgetState(
    val insights: Insights?,
    /** Epoch seconds of the newest reading in the library, if known. */
    val lastReadingAt: Long?,
    val demo: Boolean,
) {
    val connected get() = insights != null
    /** Hours since the newest reading, when that's more than 12 h — widgets show a quiet "stale" hint. */
    val staleHours: Long?
        get() {
            val at = lastReadingAt ?: return null
            val h = (System.currentTimeMillis() / 1000 - at) / 3600
            return h.takeIf { it >= 12 }
        }
}

object WidgetData {
    suspend fun load(context: Context): WidgetState {
        val app = context.applicationContext as OjasApp
        if (app.repository.state.value.summary == null) app.repository.loadInitial()
        val summary = app.repository.state.value.summary
        val settings = app.store.settings.first()
        val insights = summary?.let { runCatching { InsightEngine(it, settings).compute() }.getOrNull() }
        return WidgetState(insights, summary?.freshness?.lastReadingAt, settings.demo)
    }

    /** A plausible, fictional state for launcher previews (never real data). */
    fun preview(): WidgetState {
        val s = DemoData.generate()
        val i = runCatching { InsightEngine(s, AppSettings()).compute() }.getOrNull()
        return WidgetState(i, System.currentTimeMillis() / 1000, true)
    }
}

// ------------------------------------------------------------------ colours

/** Semantic level colours, copied from ui/theme/Theme.kt (light / dark). */
object WColors {
    val great = ColorProvider(day = Color(0xFF14A37A), night = Color(0xFF5EE6B4))
    val good = ColorProvider(day = Color(0xFF6E9E1E), night = Color(0xFFB4E86A))
    val fair = ColorProvider(day = Color(0xFFD08A00), night = Color(0xFFFFC46B))
    val low = ColorProvider(day = Color(0xFFD9483B), night = Color(0xFFFF8A7A))
    val neutral = ColorProvider(day = Color(0xFF717975), night = Color(0xFF8A938E))

    fun level(l: Level): ColorProvider = when (l) {
        Level.Great -> great; Level.Good -> good; Level.Fair -> fair; Level.Low -> low; Level.Unknown -> neutral
    }

    // Mid-tone ARGB for bitmaps (a bitmap can't follow day/night, so these read on both).
    const val G_LOW = 0xFFF0705F.toInt()
    const val G_FAIR = 0xFFF2B33D.toInt()
    const val G_GOOD = 0xFF9CCB3B.toInt()
    const val G_GREAT = 0xFF2BC795.toInt()
    const val TRACK = 0x33808080
    const val STEPS = 0xFFE8A317.toInt()
    const val ACTIVE = 0xFF2BC795.toInt()
    const val DEEP = 0xFF5B68E8.toInt()
    const val LIGHT = 0xFF6FB0F0.toInt()
    const val REM = 0xFFB46FE8.toInt()
    const val AWAKE = 0xFFF0915F.toInt()
}

// ------------------------------------------------------------------ bitmaps

private fun px(context: Context, dp: Dp) = (dp.value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

object WidgetArt {
    /** 270° gradient gauge (opening at the bottom), round caps. [fraction] null draws only the track. */
    fun gauge(context: Context, size: Dp, stroke: Dp, fraction: Float?): Bitmap {
        val s = px(context, size)
        val sw = px(context, stroke).toFloat()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = RectF(sw / 2, sw / 2, s - sw / 2, s - sw / 2)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND }
        paint.color = WColors.TRACK
        c.drawArc(r, 135f, 270f, false, paint)
        if (fraction != null && fraction > 0f) {
            val colors = intArrayOf(WColors.G_LOW, WColors.G_FAIR, WColors.G_GOOD, WColors.G_GREAT, WColors.G_GREAT, WColors.G_LOW)
            val pos = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 0.88f, 1f)
            paint.shader = SweepGradient(s / 2f, s / 2f, colors, pos).apply {
                setLocalMatrix(Matrix().apply { setRotate(135f, s / 2f, s / 2f) })
            }
            c.drawArc(r, 135f, 270f * fraction.coerceIn(0.01f, 1f), false, paint)
        }
        return bmp
    }

    /** Full progress ring; a second, thinner lap shows overshoot past 100%. */
    fun ring(context: Context, size: Dp, stroke: Dp, fraction: Float, color: Int): Bitmap {
        val s = px(context, size)
        val sw = px(context, stroke).toFloat()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = RectF(sw / 2, sw / 2, s - sw / 2, s - sw / 2)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND }
        paint.color = WColors.TRACK
        c.drawArc(r, 0f, 360f, false, paint)
        if (fraction > 0f) {
            paint.color = color
            c.drawArc(r, -90f, 360f * fraction.coerceAtMost(1f), false, paint)
            if (fraction > 1f) {
                paint.alpha = 140
                paint.strokeWidth = sw * 0.45f
                c.drawArc(r, -90f, 360f * (fraction - 1f).coerceAtMost(1f), false, paint)
            }
        }
        return bmp
    }

    /** Horizontal proportion bar of rounded segments (e.g. sleep stages). */
    fun stackBar(context: Context, width: Dp, height: Dp, parts: List<Pair<Float, Int>>): Bitmap {
        val w = px(context, width); val h = px(context, height)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val visible = parts.filter { it.first > 0f }
        val total = visible.sumOf { it.first.toDouble() }.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (total <= 0f) {
            paint.color = WColors.TRACK
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, paint)
            return bmp
        }
        val gap = px(context, 2.dp).toFloat()
        val usable = w - gap * (visible.size - 1)
        var x = 0f
        visible.forEach { (v, color) ->
            val sw = (usable * v / total).coerceAtLeast(h.toFloat())
            paint.color = color
            c.drawRoundRect(RectF(x, 0f, (x + sw).coerceAtMost(w.toFloat()), h.toFloat()), h / 2f, h / 2f, paint)
            x += sw + gap
        }
        return bmp
    }
}

// ------------------------------------------------------------------ shared UI

fun openTab(context: Context, tab: Int): Action =
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_TAB, tab),
    )

/** Rounded, dynamic-colour widget surface that opens [tab] on tap. */
@Composable
fun WidgetSurface(context: Context, tab: Int, padding: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(GlanceTheme.colors.widgetBackground)
            .padding(padding)
            .clickable(openTab(context, tab)),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun Label(text: String, size: TextUnit = 11.sp, color: ColorProvider = GlanceTheme.colors.onSurfaceVariant, maxLines: Int = 1) {
    Text(text, style = TextStyle(color = color, fontSize = size, fontWeight = FontWeight.Medium), maxLines = maxLines)
}

@Composable
fun Value(text: String, size: TextUnit = 22.sp, color: ColorProvider = GlanceTheme.colors.onSurface) {
    Text(text, style = TextStyle(color = color, fontSize = size, fontWeight = FontWeight.Bold), maxLines = 1)
}

@Composable
fun LevelDot(level: Level, size: Dp = 7.dp) {
    Box(GlanceModifier.size(size).cornerRadius(size / 2).background(WColors.level(level))) {}
}

/** Label over value, with an optional level dot. */
@Composable
fun MiniStat(label: String, value: String, level: Level?, modifier: GlanceModifier = GlanceModifier, valueSize: TextUnit = 16.sp) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (level != null && level != Level.Unknown) {
                LevelDot(level, 6.dp)
                Spacer(GlanceModifier.width(4.dp))
            }
            Label(label)
        }
        Value(value, valueSize)
    }
}

/** Gauge bitmap with the score and a small caption centred in it. */
@Composable
fun GaugeWithScore(context: Context, insights: Insights, size: Dp, stroke: Dp, scoreSize: TextUnit, showCaption: Boolean) {
    val r = insights.readiness
    val score = r.score ?: r.zeppScore
    Box(GlanceModifier.size(size), contentAlignment = Alignment.Center) {
        Image(ImageProvider(WidgetArt.gauge(context, size, stroke, score?.let { it / 100f })), contentDescription = "Readiness ${score ?: "unknown"}", modifier = GlanceModifier.size(size))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Value(score?.toString() ?: "—", scoreSize)
            if (showCaption) Label(if (r.score == null && r.zeppScore != null) "Zepp" else "Ready", 9.sp)
        }
    }
}

fun headline(i: Insights): String = i.readiness.let { r -> if (r.score == null) (if (r.zeppScore != null) "Zepp score" else r.headline) else r.headline }

@Composable
fun EmptyState(compact: Boolean) {
    Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
        Value("Ojas", if (compact) 16.sp else 20.sp, GlanceTheme.colors.primary)
        Label(if (compact) "Tap to connect" else "Open Ojas to connect", if (compact) 10.sp else 12.sp, maxLines = 2)
    }
}

@Composable
fun StaleHint(state: WidgetState) {
    val h = state.staleHours ?: return
    Label(if (h < 48) "Last reading $h h ago" else "Last reading ${h / 24} d ago", 10.sp, WColors.fair)
}

fun fmtDuration(min: Int?): String = when {
    min == null -> "—"
    min < 60 -> "${min}m"
    else -> "${min / 60}h ${"%02d".format(min % 60)}m"
}

fun fmtSteps(n: Int?): String = when {
    n == null -> "—"
    n >= 10_000 -> "%.1fk".format(n / 1000.0)
    else -> "%,d".format(n)
}
