package app.baseline.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.content.res.Configuration
import androidx.annotation.ColorRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider as DayNightColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.baseline.BaselineApp
import app.baseline.MainActivity
import app.baseline.R
import app.baseline.data.AppSettings
import app.baseline.data.showNumbers
import app.baseline.data.DemoData
import app.baseline.data.Journal
import app.baseline.data.Sleep
import app.baseline.insight.InsightEngine
import app.baseline.insight.Insights
import app.baseline.insight.Level
import kotlinx.coroutines.flow.first

/** Intent extra MainActivity reads to open a tab: 0 Today, 1 Sleep, 2 Body, 3 Move. */
const val EXTRA_TAB = "baseline.tab"

object Tab {
    const val TODAY = 0
    const val SLEEP = 1
    const val TRENDS = 2
    const val ACTIVITY = 3
}

/**
 * Whether widgets may show raw numbers (scores, durations, steps, HRV…). When false they show words
 * instead ("Slept well", "Almost there"). Calm mode will drive this; the lock screen always hides
 * numbers regardless (see [Look]).
 */
/** False in calm mode: widgets show words instead of numbers (the lock screen always does). */
suspend fun widgetShowNumbers(context: Context): Boolean =
    runCatching { (context.applicationContext as BaselineApp).store.settings.first().showNumbers() }.getOrDefault(true)

/** How a widget instance should present itself. */
data class Look(
    /** Raw health numbers allowed. False in calm mode and on the lock screen. */
    val numbers: Boolean,
    /** Hosted on the lock screen / hub (AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD). */
    val keyguard: Boolean,
    val is24h: Boolean,
)

/** Everything a widget renders, computed once per update. */
data class WidgetState(
    val insights: Insights?,
    /** Epoch seconds of the newest reading in the library, if known. */
    val lastReadingAt: Long?,
    val demo: Boolean,
    /** Asleep minutes for the 7 nights ending today (oldest first; null = no main sleep that night). */
    val nights7: List<Int?> = emptyList(),
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
        val app = context.applicationContext as BaselineApp
        if (app.repository.state.value.summary == null) app.repository.loadInitial()
        val summary = app.repository.state.value.summary
        val settings = app.store.settings.first()
        if (summary == null) return WidgetState(null, null, settings.demo)
        val journal = runCatching { app.journal.journal.first() }.getOrNull() ?: Journal()
        return compute(summary, settings, summary.freshness.lastReadingAt, settings.demo, journal)
    }

    /** A plausible, fictional state for launcher previews (never real data). */
    fun preview(): WidgetState = compute(DemoData.generate(), AppSettings(), System.currentTimeMillis() / 1000, true)

    private fun compute(
        summary: app.baseline.data.Summary, settings: AppSettings, lastAt: Long?, demo: Boolean,
        journal: Journal = Journal(),
    ): WidgetState =
        runCatching {
            val engine = InsightEngine(summary, settings, journal = journal)
            val insights = engine.compute()
            val nights = engine.dates(7).map { d -> engine.mainSleeps[d]?.asleepMin }
            WidgetState(insights, lastAt, demo, nights)
        }.getOrElse { WidgetState(null, lastAt, demo) }
}

// ------------------------------------------------------------------ colours

/**
 * Palette roles from widget_colors.xml (values, values-night, values-v31, values-night-v31). On Android 12+
 * those resources point at the system's dynamic (wallpaper) tonal palettes; below that they are
 * Baseline's own tones. Each role is resolved for both light and dark at render time and handed to Glance
 * as a day/night pair, so a light/dark switch follows live; a wallpaper change shows on the next update.
 */
class Palette(context: Context) {
    private val day = themed(context, Configuration.UI_MODE_NIGHT_NO)
    private val night = themed(context, Configuration.UI_MODE_NIGHT_YES)
    private fun c(@ColorRes id: Int): ColorProvider = DayNightColor(day = Color(day.getColor(id)), night = Color(night.getColor(id)))

    val surface = c(R.color.w_surface)
    val onSurface = c(R.color.w_on_surface)
    val onSurfaceVar = c(R.color.w_on_surface_var)
    val track = c(R.color.w_track)
    val chip = c(R.color.w_chip)
    val onChip = c(R.color.w_on_chip)
    val a1 = c(R.color.w_a1)
    val a1Container = c(R.color.w_a1_container)
    val onA1Container = c(R.color.w_on_a1_container)
    val onA1ContainerVar = c(R.color.w_on_a1_container_var)
    val a1Track = c(R.color.w_a1_track)
    val a2Container = c(R.color.w_a2_container)
    val onA2Container = c(R.color.w_on_a2_container)
    val onA2ContainerVar = c(R.color.w_on_a2_container_var)
    val a2Track = c(R.color.w_a2_track)
    val a3 = c(R.color.w_a3)
    val a3Mid = c(R.color.w_a3_mid)
    val a3Container = c(R.color.w_a3_container)
    val onA3Container = c(R.color.w_on_a3_container)
    val onA3ContainerVar = c(R.color.w_on_a3_container_var)
    val a3Track = c(R.color.w_a3_track)
    val deep = c(R.color.w_stage_deep)
    val rem = c(R.color.w_stage_rem)
    val light = c(R.color.w_stage_light)
    val awake = c(R.color.w_stage_awake)
    val alert = c(R.color.w_alert_container)
    val onAlert = c(R.color.w_on_alert_container)

    /** Status colours, harmonised toward the wallpaper's primary (like the app), for day and night. */
    private fun h(@ColorRes id: Int): ColorProvider = DayNightColor(
        day = app.baseline.ui.theme.Harmonize.toward(Color(day.getColor(id)), Color(day.getColor(R.color.w_a1))),
        night = app.baseline.ui.theme.Harmonize.toward(Color(night.getColor(id)), Color(night.getColor(R.color.w_a1))),
    )

    private val levels = mapOf(
        Level.Great to h(R.color.w_level_great), Level.Good to h(R.color.w_level_good), Level.Fair to h(R.color.w_level_fair),
        Level.Low to h(R.color.w_level_low), Level.Unknown to c(R.color.w_level_unknown),
    )

    fun level(l: Level): ColorProvider = levels.getValue(l)

    private companion object {
        fun themed(context: Context, nightBits: Int): Context {
            val cfg = Configuration(context.resources.configuration)
            cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightBits
            return context.createConfigurationContext(cfg)
        }
    }
}

val LocalPalette = staticCompositionLocalOf<Palette> { error("Palette not provided") }

/** The widget palette for the current composition (provided by [BaselineWidget]). */
val WC: Palette
    @Composable @ReadOnlyComposable get() = LocalPalette.current

// ------------------------------------------------------------------ actions

fun openTab(context: Context, tab: Int): Action =
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_TAB, tab),
    )

// ------------------------------------------------------------------ shapes

/** A full-bleed tinted shape behind [content]: drawable masks (circle, pill) or bitmap masks (squircle, cookie). */
@Composable
fun ShapeBox(
    shape: ImageProvider,
    color: ColorProvider,
    modifier: GlanceModifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.background(shape, ContentScale.FillBounds, ColorFilter.tint(color)),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** Standard Material widget card: system corner radius on Android 12+, a 20dp rounded mask below. */
@Composable
fun CardBox(context: Context, tab: Int, color: ColorProvider = WC.surface, padding: Dp = 16.dp, content: @Composable () -> Unit) {
    val base = GlanceModifier.fillMaxSize().appWidgetBackground()
    val shaped = if (Build.VERSION.SDK_INT >= 31) {
        base.cornerRadius(android.R.dimen.system_app_widget_background_radius).background(color)
    } else {
        base.background(ImageProvider(R.drawable.wshape_card), ContentScale.FillBounds, ColorFilter.tint(color))
    }
    Box(shaped.padding(padding).clickable(openTab(context, tab)), contentAlignment = Alignment.CenterStart, content = content)
}

/** A tinted mask layer; draws nothing when [bmp] is null. */
@Composable
fun Mask(bmp: Bitmap?, color: ColorProvider, modifier: GlanceModifier, description: String? = null) {
    if (bmp == null) return
    Image(ImageProvider(bmp), description, modifier, ContentScale.FillBounds, ColorFilter.tint(color))
}

// ------------------------------------------------------------------ text

@Composable
fun T(
    text: String,
    size: TextUnit,
    color: ColorProvider,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
    modifier: GlanceModifier = GlanceModifier,
) {
    Text(text, modifier, TextStyle(color = color, fontSize = size, fontWeight = weight, textAlign = align), maxLines)
}

/**
 * Size for hero text that must fit a fixed shape (a word inside a ring). Computed in dp and divided by
 * the font scale so large-font settings don't push the word out of its circle.
 */
fun heroSp(context: Context, text: String, widthDp: Float, maxDp: Float, minDp: Float = 9f): TextUnit {
    val scale = context.resources.configuration.fontScale.coerceAtLeast(1f)
    return (WidgetWords.fitDp(text, widthDp, maxDp, minDp) / scale).sp
}

// ------------------------------------------------------------------ shared pieces

@Composable
fun Dot(level: Level, size: Dp = 7.dp) {
    Box(GlanceModifier.size(size).background(ImageProvider(R.drawable.wshape_circle), ContentScale.FillBounds, ColorFilter.tint(WC.level(level)))) {}
}

/** Ring with an indicator in [indicator] and the rest in [track]; [content] sits in the middle. */
@Composable
fun RingArt(
    context: Context,
    size: Dp,
    stroke: Dp,
    fraction: Float?,
    indicator: ColorProvider,
    track: ColorProvider,
    description: String,
    content: @Composable () -> Unit = {},
) {
    val r = WidgetArt.ring(context, size, stroke, fraction)
    Box(GlanceModifier.size(size), contentAlignment = Alignment.Center) {
        Mask(r.track, track, GlanceModifier.size(size))
        Mask(r.indicator, indicator, GlanceModifier.size(size), description)
        content()
    }
}

/** Sleep stages as tonal capsule segments: deep, light, REM, awake. */
@Composable
fun StageBar(context: Context, last: Sleep?, width: Dp, height: Dp = 8.dp, track: ColorProvider = WC.track) {
    val parts = listOf(
        (last?.deepMin ?: 0).toFloat(), (last?.lightMin ?: 0).toFloat(),
        (last?.remMin ?: 0).toFloat(), (last?.awakeMin ?: 0).toFloat(),
    )
    val (masks, empty) = WidgetArt.segments(context, width, height, parts)
    val colors = listOf(WC.deep, WC.light, WC.rem, WC.awake)
    val m = GlanceModifier.width(width).height(height)
    Box(m) {
        Mask(empty, track, m)
        masks.forEachIndexed { i, b -> Mask(b, colors[i], m, if (i == 0) "Sleep stages" else null) }
    }
}

/** Readiness fraction for rings: our score, else Zepp's, else none. */
fun readinessFraction(i: Insights): Float? = (i.readiness.score ?: i.readiness.zeppScore)?.let { it / 100f }

fun usesZepp(i: Insights) = i.readiness.score == null && i.readiness.zeppScore != null

/** Small line under a readiness verb: the score (with "Zepp" when it's Zepp's), or a word when numbers are hidden. */
fun readinessCaption(i: Insights, look: Look): String? {
    val r = i.readiness
    return when {
        look.numbers && r.score != null -> r.score.toString()
        look.numbers && r.zeppScore != null -> "Zepp ${r.zeppScore}"
        usesZepp(i) -> "Zepp"
        else -> null
    }
}

@Composable
fun StaleHint(state: WidgetState, color: ColorProvider = WC.level(Level.Fair), size: TextUnit = 10.sp) {
    val h = state.staleHours ?: return
    T(if (h < 48) "Updated $h h ago" else "Updated ${h / 24} d ago", size, color, FontWeight.Medium)
}

/** Not connected yet: the brand word and a hint, centred in whatever shape the widget uses. */
@Composable
fun EmptyContent(compact: Boolean, onColor: ColorProvider, mutedColor: ColorProvider) {
    Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
        T("Baseline", if (compact) 14.sp else 18.sp, onColor, FontWeight.Bold, align = TextAlign.Center)
        if (!compact) {
            Spacer(GlanceModifier.height(2.dp))
            T("Tap to connect", 12.sp, mutedColor, align = TextAlign.Center)
        }
    }
}

/** Horizontal spacer / vertical spacer shorthands. */
@Composable fun HSpace(w: Dp) = Spacer(GlanceModifier.width(w))
@Composable fun VSpace(h: Dp) = Spacer(GlanceModifier.height(h))

/** A capsule chip: dot + label (+ value). */
@Composable
fun SignalChip(label: String, value: String?, level: Level, modifier: GlanceModifier = GlanceModifier, height: Dp = 26.dp) {
    ShapeBox(ImageProvider(R.drawable.wshape_pill), WC.chip, modifier.height(height).padding(horizontal = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally, modifier = GlanceModifier.fillMaxWidth()) {
            Dot(level, 6.dp)
            HSpace(5.dp)
            T(label, 11.sp, WC.onChip, FontWeight.Medium)
            if (value != null) {
                HSpace(4.dp)
                T(value, 11.sp, WC.onChip, FontWeight.Bold)
            }
        }
    }
}
