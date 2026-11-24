package app.baseline.ui.common

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.pill
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import app.baseline.insight.Verb
import app.baseline.ui.theme.Motion
import app.baseline.ui.theme.Numeric
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt

// ------------------------------------------------------------------ shapes with meaning

/** Material shapes, one per readiness verb, so the shape itself says how the day looks. */
enum class Blob { Cookie, Petal, Soft, Pill, Circle }

fun blobFor(verb: Verb?): Blob = when (verb) {
    Verb.Push -> Blob.Cookie
    Verb.Ready -> Blob.Petal
    Verb.Easy -> Blob.Soft
    Verb.Recover -> Blob.Pill
    null -> Blob.Circle
}

private val polygons: Map<Blob, RoundedPolygon> by lazy {
    mapOf(
        Blob.Cookie to RoundedPolygon.star(12, radius = 1f, innerRadius = .82f, rounding = CornerRounding(.18f, .6f)),
        Blob.Petal to RoundedPolygon.star(8, radius = 1f, innerRadius = .72f, rounding = CornerRounding(.32f, .8f)),
        Blob.Soft to RoundedPolygon.rectangle(width = 2f, height = 2f, rounding = CornerRounding(.62f, .9f)),
        Blob.Pill to RoundedPolygon.pill(width = 2f, height = 1.55f),
        Blob.Circle to RoundedPolygon.circle(12),
    ).mapValues { it.value.normalized() }
}

private val morphs = HashMap<Pair<Blob, Blob>, Morph>()
private fun morph(a: Blob, b: Blob): Morph = morphs.getOrPut(a to b) { Morph(polygons.getValue(a), polygons.getValue(b)) }

/**
 * A filled Material shape that morphs (with a spatial spring) whenever [blob] changes, grows in on first show
 * and turns very slowly. Content sits upright in the middle.
 */
@Composable
fun MorphBlob(
    blob: Blob,
    color: Color,
    modifier: Modifier = Modifier,
    spin: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    var from by remember { mutableStateOf(blob) }
    var to by remember { mutableStateOf(blob) }
    val progress = remember { Animatable(1f) }
    val grow = remember { Animatable(.72f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, Motion.slowSpatial()) }
    LaunchedEffect(blob) {
        if (blob != to) {
            from = to; to = blob
            progress.snapTo(0f)
            progress.animateTo(1f, Motion.slowSpatial())
        }
    }
    val turn = if (spin) rememberInfiniteTransition(label = "turn").animateFloat(
        0f, 360f, infiniteRepeatable(tween(48_000, easing = LinearEasing)), label = "angle",
    ) else null
    val fill by animateColorAsState(color, Motion.slowEffects(), label = "fill")
    val path = remember { android.graphics.Path() }
    val matrix = remember { android.graphics.Matrix() }
    Box(
        modifier
            .graphicsLayer { scaleX = grow.value; scaleY = grow.value }
            .drawBehind {
                path.rewind()
                morph(from, to).toPath(progress.value.coerceIn(0f, 1.08f), path)
                matrix.setScale(size.width, size.height)
                path.transform(matrix)
                rotate(turn?.value ?: 0f) { drawPath(path.asComposePath(), fill) }
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Shape-morphing loading indicator (Material 3 Expressive style), for waits longer than a moment. */
@Composable
fun ExpressiveLoading(modifier: Modifier = Modifier, size: Dp = 56.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val seq = remember { listOf(Blob.Cookie, Blob.Petal, Blob.Pill, Blob.Soft, Blob.Circle) }
    val t = rememberInfiniteTransition(label = "loading")
    val step by t.animateFloat(0f, seq.size.toFloat(), infiniteRepeatable(tween(seq.size * 700, easing = LinearEasing)), label = "step")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2_600, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    val path = remember { android.graphics.Path() }
    val matrix = remember { android.graphics.Matrix() }
    Box(
        modifier
            .size(size)
            .semantics { contentDescription = "Loading" }
            .drawBehind {
                val i = floor(step).toInt() % seq.size
                val f = step - floor(step)
                val eased = (f * f * (3 - 2 * f)) // smoothstep: hold, morph, hold
                path.rewind()
                morph(seq[i], seq[(i + 1) % seq.size]).toPath(eased, path)
                matrix.setScale(this.size.width, this.size.height)
                path.transform(matrix)
                rotate(spin) { drawPath(path.asComposePath(), color) }
            },
    )
}

// ------------------------------------------------------------------ numbers

/** An integer that counts up to its value on first show and glides between values after that. */
@Composable
fun CountUp(value: Int?, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified, placeholder: String = "—") {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(value) { value?.let { anim.animateTo(it.toFloat(), Motion.count()) } }
    Text(
        if (value == null) placeholder else anim.value.roundToInt().toString(),
        style = style.merge(Numeric), color = color, modifier = modifier,
    )
}

// ------------------------------------------------------------------ entrance choreography

/** When the current page appeared (uptime ms); items animate in only during the first moment after that. */
val LocalEnterClock = compositionLocalOf<Long?> { null }

@Composable
fun ProvideEntrance(content: @Composable () -> Unit) {
    val start = remember { SystemClock.uptimeMillis() }
    CompositionLocalProvider(LocalEnterClock provides start, content = content)
}

/** Rise-and-fade in, staggered by [index], only while the page is first appearing (never on scroll-back). */
fun Modifier.enter(index: Int): Modifier = composed {
    val start = LocalEnterClock.current
    val age = remember { start?.let { SystemClock.uptimeMillis() - it } ?: Long.MAX_VALUE }
    if (age > 700 || index > 9) return@composed this
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index * 55L - age).coerceAtLeast(0))
        p.animateTo(1f, Motion.spatial())
    }
    graphicsLayer {
        alpha = p.value.coerceIn(0f, 1f)
        translationY = (1f - p.value) * 56.dp.toPx()
    }
}

/** Wraps a LazyListScope so every item gets the staggered entrance automatically. */
class StaggeredListScope(private val d: LazyListScope) : LazyListScope by d {
    private var n = 0
    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        val i = n++
        d.item(key, contentType) { val s = this; Box(Modifier.enter(i)) { s.content() } }
    }
    override fun items(count: Int, key: ((Int) -> Any)?, contentType: (Int) -> Any?, itemContent: @Composable LazyItemScope.(Int) -> Unit) {
        val first = n; n += count
        d.items(count, key, contentType) { idx -> val s = this; Box(Modifier.enter(first + idx)) { s.itemContent(idx) } }
    }
}

// ------------------------------------------------------------------ segmented groups

/** Expressive list grouping: big radius on the group's outer corners, small radius between rows. */
fun segmentShape(index: Int, count: Int, outer: Dp = 28.dp, inner: Dp = 6.dp): Shape = when {
    count <= 1 -> RoundedCornerShape(outer)
    index == 0 -> RoundedCornerShape(outer, outer, inner, inner)
    index == count - 1 -> RoundedCornerShape(inner, inner, outer, outer)
    else -> RoundedCornerShape(inner)
}

/** A vertical group of rows with segmented corners and 3 dp gaps. */
@Composable
fun SegmentedColumn(count: Int, modifier: Modifier = Modifier, row: @Composable ColumnScope.(Int) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0 until count) row(i)
    }
}

/** One row of a segmented group. */
@Composable
fun SegmentRow(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = segmentShape(index, count)
    Surface(
        shape = shape, color = color,
        modifier = modifier.fillMaxWidth().clip(shape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) { Column(Modifier.padding(horizontal = padding, vertical = (padding - 2.dp).coerceAtLeast(0.dp)), content = content) }
}

// ------------------------------------------------------------------ connected button group

/**
 * Material 3 Expressive connected button group: segments touch with small inner corners, the selected one
 * morphs to fully round, fills with primary and widens a little.
 */
@Composable
fun <T> ConnectedButtonGroup(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector)? = null,
    height: Dp = 56.dp,
    style: TextStyle = MaterialTheme.typography.labelLarge,
) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEachIndexed { i, o ->
            val sel = o == selected
            val full = height / 2
            val innerR by animateDpAsState(if (sel) full else 8.dp, Motion.fastSpatial(), label = "r")
            val w by animateFloatAsState(if (sel) 1.18f else 1f, Motion.fastSpatial(), label = "w")
            val bg by animateColorAsState(if (sel) cs.primary else cs.surfaceContainerHighest, Motion.effects(), label = "bg")
            val fg by animateColorAsState(if (sel) cs.onPrimary else cs.onSurfaceVariant, Motion.effects(), label = "fg")
            val start = if (i == 0) full else innerR
            val end = if (i == options.lastIndex) full else innerR
            val shape = RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)
            Surface(
                shape = shape, color = bg,
                modifier = Modifier.weight(w).defaultMinSize(minHeight = height).clip(shape)
                    .clickable(role = Role.RadioButton) { onSelect(o) },
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    icon?.let { Icon(it(o), null, tint = fg, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)) }
                    Text(label(o), style = style, color = fg, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ page header for detail screens

/** Back button over a large, wrapping page title: the "large top app bar" look, scrolling with the page. */
@Composable
fun PageHeader(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
            }
            Spacer(Modifier.weight(1f))
            actions()
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(horizontal = 4.dp))
        subtitle?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

// ------------------------------------------------------------------ text that fits

/** One line of big text that steps its size down (to [min]) rather than wrapping or clipping in a narrow space. */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    min: androidx.compose.ui.unit.TextUnit = MaterialTheme.typography.titleLarge.fontSize,
) {
    Text(
        text, modifier = modifier, color = color, style = style.merge(Numeric), maxLines = 1, softWrap = false,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = min, maxFontSize = style.fontSize),
    )
}

// ------------------------------------------------------------------ paired big-number tiles

val LeftTileShape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp, topEnd = 8.dp, bottomEnd = 8.dp)
val RightTileShape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 28.dp, bottomEnd = 28.dp)

/** Two tiles side by side with mirrored corners (big outside, small where they meet), equal height. */
@Composable
fun PairedTiles(left: @Composable (Modifier, Shape) -> Unit, right: @Composable (Modifier, Shape) -> Unit) {
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        left(Modifier.weight(1f).fillMaxHeight(), LeftTileShape)
        right(Modifier.weight(1f).fillMaxHeight(), RightTileShape)
    }
}

/** A tile with a label, one big number (that fits its width) and a caption. */
@Composable
fun BigTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    unit: String? = null,
    caption: String? = null,
    dot: Color? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = shape, color = color, modifier = modifier.clip(shape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                dot?.let { Box(Modifier.padding(start = 6.dp).size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).drawBehind { drawRect(it) }) }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                FitText(value, MaterialTheme.typography.headlineLarge, Modifier.weight(1f, fill = false), min = 22.sp)
                unit?.let {
                    Spacer(Modifier.width(4.dp))
                    Text(it, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            caption?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

// ------------------------------------------------------------------ progress

/** A thick rounded progress bar that springs to its value; a small gap separates fill from track. */
@Composable
fun ThickProgress(fraction: Float, modifier: Modifier = Modifier, height: Dp = 12.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val p = remember { Animatable(0f) }
    LaunchedEffect(fraction) { p.animateTo(fraction.coerceIn(0f, 1f), Motion.slowSpatial()) }
    Box(
        modifier.fillMaxWidth().height(height).drawBehind {
            val f = p.value.coerceIn(0f, 1f)
            val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
            val gap = 4.dp.toPx()
            val fillW = size.width * f
            if (fillW < size.width - gap) {
                val start = if (f > 0f) fillW + gap else 0f
                drawRoundRect(track, topLeft = androidx.compose.ui.geometry.Offset(start, 0f), size = androidx.compose.ui.geometry.Size(size.width - start, size.height), cornerRadius = r)
            }
            if (f > 0f) drawRoundRect(color, size = androidx.compose.ui.geometry.Size(maxOf(fillW, size.height), size.height), cornerRadius = r)
        },
    )
}

// ------------------------------------------------------------------ navigation rows

/** A row that opens a page: tonal icon tile, title, a wrapping line, chevron. Use inside a segmented group. */
@Composable
fun EntryRow(icon: ImageVector, title: String, line: String, index: Int, count: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SegmentRow(index, count, onClick = onClick, padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).drawBehind { drawRect(cs.secondaryContainer) }, contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
        }
    }
}
