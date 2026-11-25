package app.baseline.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.baseline.insight.Level
import app.baseline.ui.theme.Numeric
import app.baseline.ui.theme.Brand

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = container)
    val shape = MaterialTheme.shapes.large
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, colors = colors) {
            Column(Modifier.padding(Space.card), content = content)
        }
    } else {
        Card(modifier = modifier.fillMaxWidth(), shape = shape, colors = colors) {
            Column(Modifier.padding(Space.card), content = content)
        }
    }
}

@Composable
fun CardHeader(title: String, icon: ImageVector, tint: Color, trailing: String? = null, chevron: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(tint.copy(alpha = .16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (chevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun BigValue(value: String, unit: String? = null, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.displaySmall.merge(Numeric), color = color, fontWeight = FontWeight.Bold)
        if (unit != null) {
            Spacer(Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
fun LevelPill(text: String, level: Level, modifier: Modifier = Modifier) {
    val c = Brand.health.of(level)
    Surface(shape = CircleShape, color = c.copy(alpha = .16f), modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** ▲ / ▼ / – with a value, coloured by whether the direction is good. */
@Composable
fun Delta(diff: Double?, text: String, higherIsBetter: Boolean, neutralBand: Double = 0.0) {
    if (diff == null) return
    val h = Brand.health
    val (icon, color) = when {
        kotlin.math.abs(diff) <= neutralBand -> Icons.Rounded.Remove to MaterialTheme.colorScheme.onSurfaceVariant
        (diff > 0) == higherIsBetter -> (if (diff > 0) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward) to h.great
        else -> (if (diff > 0) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward) to h.fair
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(2.dp))
        Text(text, style = MaterialTheme.typography.labelMedium.merge(Numeric), color = color)
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    caption: String? = null,
    level: Level = Level.Unknown,
    onClick: (() -> Unit)? = null,
    graphic: @Composable (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(24.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.clip(shape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (level != Level.Unknown) Box(Modifier.size(8.dp).clip(CircleShape).background(Brand.health.of(level)))
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.headlineMedium.merge(Numeric), fontWeight = FontWeight.Bold)
                if (unit != null) {
                    Spacer(Modifier.width(3.dp))
                    Text(unit, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            if (graphic != null) {
                Spacer(Modifier.height(8.dp))
                graphic()
            }
            if (caption != null) {
                Spacer(Modifier.height(6.dp))
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun KeyValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge.merge(Numeric), color = valueColor, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Explainer(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One spacing system for every screen: page margins, gaps between cards, padding inside cards. */
object Space {
    val page = 16.dp
    val gap = 12.dp
    val card = 20.dp
}

/** A small label above a group of cards or rows (same everywhere: Body, Settings, Healthspan, Labs). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 8.dp, top = 8.dp),
    )
}

/** The header of every inner page: a round back button, then the title (and an optional line) below it. */
@Composable
fun PageHeader(title: String, onBack: () -> Unit, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        androidx.compose.material3.FilledTonalIconButton(onClick = onBack) {
            Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, "Back")
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 4.dp))
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
    }
}
