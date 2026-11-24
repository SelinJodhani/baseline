package app.baseline.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.baseline.R
import app.baseline.insight.Level

// ------------------------------------------------------------------ colour
//
// Every colour in the app comes from the Material colour scheme: the wallpaper's dynamic scheme on Android 12+,
// otherwise a tonal-spot scheme generated (material-color-utilities) from Baseline's coral heart, #E8604C.
// Nothing is a fixed hue, so the app always looks like the phone it's on.

private val LightFallback = lightColorScheme(
    primary = Color(0xFF904B3F), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD4), onPrimaryContainer = Color(0xFF73342A), inversePrimary = Color(0xFFFFB4A7),
    secondary = Color(0xFF775651), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD4), onSecondaryContainer = Color(0xFF5D3F3A),
    tertiary = Color(0xFF6F5C2E), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBE0A6), onTertiaryContainer = Color(0xFF564419),
    background = Color(0xFFFFF8F6), onBackground = Color(0xFF231918),
    surface = Color(0xFFFFF8F6), onSurface = Color(0xFF231918),
    surfaceVariant = Color(0xFFF5DDD9), onSurfaceVariant = Color(0xFF534341), surfaceTint = Color(0xFF904B3F),
    inverseSurface = Color(0xFF392E2C), inverseOnSurface = Color(0xFFFFEDEA),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF857370), outlineVariant = Color(0xFFD8C2BE), scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F6), surfaceDim = Color(0xFFE8D6D3),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFF0EE), surfaceContainer = Color(0xFFFCEAE7),
    surfaceContainerHigh = Color(0xFFF7E4E1), surfaceContainerHighest = Color(0xFFF1DFDB),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFFFB4A7), onPrimary = Color(0xFF561E16),
    primaryContainer = Color(0xFF73342A), onPrimaryContainer = Color(0xFFFFDAD4), inversePrimary = Color(0xFF904B3F),
    secondary = Color(0xFFE7BDB6), onSecondary = Color(0xFF442925),
    secondaryContainer = Color(0xFF5D3F3A), onSecondaryContainer = Color(0xFFFFDAD4),
    tertiary = Color(0xFFDDC48C), onTertiary = Color(0xFF3E2E04),
    tertiaryContainer = Color(0xFF564419), onTertiaryContainer = Color(0xFFFBE0A6),
    background = Color(0xFF1A1110), onBackground = Color(0xFFF1DFDB),
    surface = Color(0xFF1A1110), onSurface = Color(0xFFF1DFDB),
    surfaceVariant = Color(0xFF534341), onSurfaceVariant = Color(0xFFD8C2BE), surfaceTint = Color(0xFFFFB4A7),
    inverseSurface = Color(0xFFF1DFDB), inverseOnSurface = Color(0xFF392E2C),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFFA08C89), outlineVariant = Color(0xFF534341), scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF423735), surfaceDim = Color(0xFF1A1110),
    surfaceContainerLowest = Color(0xFF140C0B), surfaceContainerLow = Color(0xFF231918), surfaceContainer = Color(0xFF271D1C),
    surfaceContainerHigh = Color(0xFF322826), surfaceContainerHighest = Color(0xFF3D3230),
)

/**
 * Health meanings mapped onto scheme roles.
 *
 * - Levels: great = primary, good = primary leaning to secondary, fair (caution) = tertiary, low (needs rest) = error.
 * - Sleep stages: deep = primary, light = a lighter primary, REM = tertiary, awake = a softened error.
 * - Metrics: one accent (primary); stress uses tertiary.
 *
 * `container(level)` / `onContainer(level)` give the tonal pair for a filled surface of that level.
 */
@Immutable
data class HealthColors(
    val great: Color, val good: Color, val fair: Color, val low: Color, val neutral: Color,
    val deep: Color, val light: Color, val rem: Color, val awake: Color,
    val heart: Color, val hrv: Color, val steps: Color, val sleep: Color, val stress: Color, val oxygen: Color,
    private val containers: Map<Level, Pair<Color, Color>>,
) {
    fun of(level: Level): Color = when (level) {
        Level.Great -> great; Level.Good -> good; Level.Fair -> fair; Level.Low -> low; Level.Unknown -> neutral
    }
    fun container(level: Level): Color = containers.getValue(level).first
    fun onContainer(level: Level): Color = containers.getValue(level).second
}

fun healthColors(s: ColorScheme): HealthColors = HealthColors(
    great = s.primary, good = lerp(s.primary, s.secondary, .3f), fair = s.tertiary, low = s.error, neutral = s.outline,
    deep = s.primary, light = lerp(s.primary, s.surfaceContainerLow, .55f), rem = s.tertiary, awake = lerp(s.error, s.surfaceContainerLow, .4f),
    heart = s.primary, hrv = s.primary, steps = s.primary, sleep = s.primary, stress = s.tertiary, oxygen = s.primary,
    containers = mapOf(
        Level.Great to (s.primaryContainer to s.onPrimaryContainer),
        Level.Good to (s.secondaryContainer to s.onSecondaryContainer),
        Level.Fair to (s.tertiaryContainer to s.onTertiaryContainer),
        Level.Low to (s.errorContainer to s.onErrorContainer),
        Level.Unknown to (s.surfaceContainerHigh to s.onSurface),
    ),
)

val LocalHealthColors = staticCompositionLocalOf { healthColors(LightFallback) }

object Brand {
    val health: HealthColors @Composable get() = LocalHealthColors.current
}

// ------------------------------------------------------------------ type
//
// Roboto Flex (variable: weight, width, optical size), bundled so the first frame is right.
// Text uses the normal width; display and headline styles are a touch wider with a large optical size,
// which gives big numbers their confident, rounded look.

private fun flex(weight: Int, width: Float, opsz: Float) = Font(
    R.font.roboto_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight), FontVariation.width(width), FontVariation.Setting("opsz", opsz),
    ),
)

private fun family(width: Float, opsz: Float) =
    FontFamily(listOf(400, 500, 600, 700, 800, 900).map { flex(it, width, opsz) })

/** Body and labels. */
val FlexText = family(width = 100f, opsz = 14f)
/** Titles. */
val FlexTitle = family(width = 104f, opsz = 24f)
/** Headlines and display numbers. */
val FlexDisplay = family(width = 112f, opsz = 96f)

private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(f: FontFamily, size: TextUnit, line: TextUnit, w: Int, track: TextUnit = 0.sp) =
    TextStyle(fontFamily = f, fontSize = size, lineHeight = line, fontWeight = FontWeight(w), letterSpacing = track, lineHeightStyle = trim)

private val BaselineTypography = Typography(
    displayLarge = style(FlexDisplay, 84.sp, 88.sp, 760, (-2.5).sp),
    displayMedium = style(FlexDisplay, 60.sp, 64.sp, 720, (-1.5).sp),
    displaySmall = style(FlexDisplay, 44.sp, 50.sp, 700, (-1).sp),
    headlineLarge = style(FlexDisplay, 36.sp, 42.sp, 680, (-.75).sp),
    headlineMedium = style(FlexDisplay, 29.sp, 36.sp, 660, (-.5).sp),
    headlineSmall = style(FlexDisplay, 24.sp, 30.sp, 640, (-.25).sp),
    titleLarge = style(FlexTitle, 22.sp, 28.sp, 620),
    titleMedium = style(FlexTitle, 18.sp, 24.sp, 600),
    titleSmall = style(FlexTitle, 16.sp, 22.sp, 600),
    bodyLarge = style(FlexText, 18.sp, 27.sp, 420),
    bodyMedium = style(FlexText, 16.sp, 24.sp, 420),
    bodySmall = style(FlexText, 14.sp, 20.sp, 430),
    labelLarge = style(FlexText, 15.sp, 20.sp, 600, .1.sp),
    labelMedium = style(FlexText, 13.sp, 18.sp, 600, .2.sp),
    labelSmall = style(FlexText, 12.sp, 16.sp, 600, .3.sp),
)

/** Tabular numerals so values don't jiggle as they change. */
val Numeric = TextStyle(fontFeatureSettings = "tnum")

// ------------------------------------------------------------------ shape

private val BaselineShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(40.dp),
)

// ------------------------------------------------------------------ motion
//
// Material 3 Expressive spring tokens. Spatial springs (position, size, shape) overshoot a little;
// effects springs (colour, alpha) don't. material3 1.4.0 keeps MotionScheme internal, so these are used directly.

object Motion {
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = .6f, stiffness = 800f)
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = .8f, stiffness = 380f)
    fun <T> slowSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = .8f, stiffness = 200f)
    fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)
    fun <T> effects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
    fun <T> slowEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 800f)
    /** For numbers counting up: settles without overshoot. */
    fun <T> count(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 60f)
}

// ------------------------------------------------------------------ theme

@Composable
fun BaselineTheme(mode: String = "system", dynamic: Boolean = true, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkFallback
        else -> LightFallback
    }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    val health = remember(scheme) { healthColors(scheme) }
    CompositionLocalProvider(LocalHealthColors provides health) {
        MaterialTheme(colorScheme = scheme, shapes = BaselineShapes, typography = BaselineTypography, content = content)
    }
}
