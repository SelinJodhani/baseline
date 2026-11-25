package app.baseline.ui.theme

import android.app.Activity
import android.os.Build
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.baseline.insight.Level

// A warm, calm palette: deep forest-teal primary, mint→lime→amber→coral health ramp.
private val Light = lightColorScheme(
    primary = Color(0xFF156B58), onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2D8), onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4B635B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8DD), onSecondaryContainer = Color(0xFF07201A),
    tertiary = Color(0xFF8A5100), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCBC), onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFF8F5F2), onBackground = Color(0xFF191C1B),
    surface = Color(0xFFF8F5F2), onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDDE5E0), onSurfaceVariant = Color(0xFF414945),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF3F0EC),
    surfaceContainer = Color(0xFFEDEAE6), surfaceContainerHigh = Color(0xFFE7E4E0),
    surfaceContainerHighest = Color(0xFFE1DEDA),
    outline = Color(0xFF717975), outlineVariant = Color(0xFFC1C9C4),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8AD6BD), onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005141), onPrimaryContainer = Color(0xFFA6F2D8),
    secondary = Color(0xFFB2CCC2), onSecondary = Color(0xFF1D352E),
    secondaryContainer = Color(0xFF344C44), onSecondaryContainer = Color(0xFFCDE8DD),
    tertiary = Color(0xFFFFB86C), onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6A3C00), onTertiaryContainer = Color(0xFFFFDCBC),
    background = Color(0xFF0E1211), onBackground = Color(0xFFE1E3E0),
    surface = Color(0xFF0E1211), onSurface = Color(0xFFE1E3E0),
    surfaceVariant = Color(0xFF404945), onSurfaceVariant = Color(0xFFC0C9C3),
    surfaceContainerLowest = Color(0xFF090D0C), surfaceContainerLow = Color(0xFF161A19),
    surfaceContainer = Color(0xFF1A1E1D), surfaceContainerHigh = Color(0xFF242928),
    surfaceContainerHighest = Color(0xFF2F3432),
    outline = Color(0xFF8A938E), outlineVariant = Color(0xFF404945),
)

/**
 * Semantic health colours (green good, amber caution, red rest, indigo sleep, …), each harmonised toward the
 * wallpaper's primary colour the way Material does it: the hue turns up to 15° toward the primary, lightness and
 * colourfulness stay. So they still read as green / amber / red, but belong to a brown or blue phone.
 */
@Immutable
data class HealthColors(
    val great: Color, val good: Color, val fair: Color, val low: Color, val neutral: Color,
    val deep: Color, val light: Color, val rem: Color, val awake: Color,
    val heart: Color, val hrv: Color, val steps: Color, val sleep: Color, val stress: Color, val oxygen: Color,
) {
    fun of(level: Level): Color = when (level) {
        Level.Great -> great; Level.Good -> good; Level.Fair -> fair; Level.Low -> low; Level.Unknown -> neutral
    }
}

private fun healthColors(scheme: ColorScheme, dark: Boolean): HealthColors {
    fun h(c: Color) = Harmonize.toward(c, scheme.primary)
    return if (dark) HealthColors(
        great = h(Color(0xFF5EE6B4)), good = h(Color(0xFFB4E86A)), fair = h(Color(0xFFFFC46B)), low = h(Color(0xFFFF8A7A)),
        neutral = scheme.outline,
        deep = h(Color(0xFF7C8CFF)), light = h(Color(0xFF8FC7FF)), rem = h(Color(0xFFD59CFF)), awake = h(Color(0xFFFFB38A)),
        heart = h(Color(0xFFFF7E8B)), hrv = h(Color(0xFF6FE3C1)), steps = h(Color(0xFFFFC46B)), sleep = h(Color(0xFF9AA6FF)),
        stress = h(Color(0xFFFFA36C)), oxygen = h(Color(0xFF7FD4FF)),
    ) else HealthColors(
        great = h(Color(0xFF14A37A)), good = h(Color(0xFF6E9E1E)), fair = h(Color(0xFFD08A00)), low = h(Color(0xFFD9483B)),
        neutral = scheme.outline,
        deep = h(Color(0xFF4655D9)), light = h(Color(0xFF5B9EE8)), rem = h(Color(0xFFA35BD9)), awake = h(Color(0xFFE8834F)),
        heart = h(Color(0xFFE0475A)), hrv = h(Color(0xFF0E9E7E)), steps = h(Color(0xFFD08A00)), sleep = h(Color(0xFF5865D6)),
        stress = h(Color(0xFFE06A28)), oxygen = h(Color(0xFF1F8FCF)),
    )
}

val LocalHealthColors = staticCompositionLocalOf { healthColors(Light, false) }

object Brand {
    val health: HealthColors @Composable get() = LocalHealthColors.current
}

private val base = Typography()
private val BaselineTypography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** Tabular numerals so values don't jiggle as they change. */
val Numeric = TextStyle(fontFeatureSettings = "tnum")

private val BaselineShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun BaselineTheme(mode: String = "system", dynamic: Boolean = true, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    val health = androidx.compose.runtime.remember(scheme, dark) { healthColors(scheme, dark) }
    CompositionLocalProvider(LocalHealthColors provides health) {
        MaterialTheme(colorScheme = scheme, typography = BaselineTypography, shapes = BaselineShapes, content = content)
    }
}

/**
 * Material's colour harmonisation (as in material-color-utilities' Blend.harmonize), done in OKLCH: rotate the
 * hue toward [source] by half the difference, at most 15°, keeping lightness and chroma.
 */
internal object Harmonize {
    fun toward(design: Color, source: Color): Color {
        val (l, c, h) = toOklch(design)
        val (_, sc, sh) = toOklch(source)
        if (c < 0.02f || sc < 0.02f) return design // greys have no hue to move
        var diff = sh - h
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        val turn = kotlin.math.min(kotlin.math.abs(diff) * 0.5f, 15f) * kotlin.math.sign(diff)
        return fromOklch(l, c, (h + turn + 360f) % 360f, design.alpha)
    }

    private fun lin(v: Float) = if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    private fun gam(v: Float) = (if (v <= 0.0031308f) 12.92f * v else (1.055f * Math.pow(v.toDouble(), 1 / 2.4) - 0.055f).toFloat()).coerceIn(0f, 1f)

    private fun toOklch(c: Color): Triple<Float, Float, Float> {
        val r = lin(c.red); val g = lin(c.green); val b = lin(c.blue)
        val l = Math.cbrt((0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b).toDouble()).toFloat()
        val m = Math.cbrt((0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b).toDouble()).toFloat()
        val s = Math.cbrt((0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b).toDouble()).toFloat()
        val L = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        val A = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        val B = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
        val h = (Math.toDegrees(kotlin.math.atan2(B, A).toDouble()).toFloat() + 360f) % 360f
        return Triple(L, kotlin.math.sqrt(A * A + B * B), h)
    }

    private fun fromOklch(L: Float, C: Float, H: Float, alpha: Float): Color {
        val hr = Math.toRadians(H.toDouble())
        val A = (C * kotlin.math.cos(hr)).toFloat(); val B = (C * kotlin.math.sin(hr)).toFloat()
        val l = (L + 0.3963377774f * A + 0.2158037573f * B).let { it * it * it }
        val m = (L - 0.1055613458f * A - 0.0638541728f * B).let { it * it * it }
        val s = (L - 0.0894841775f * A - 1.2914855480f * B).let { it * it * it }
        val r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        val g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        val b = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
        return Color(gam(r), gam(g), gam(b), alpha)
    }
}
