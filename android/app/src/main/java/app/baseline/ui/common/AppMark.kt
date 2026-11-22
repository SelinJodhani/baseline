package app.baseline.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import app.baseline.R

/** The launcher icon, drawn in-app (background + foreground layers, zoomed to the central 72dp a launcher shows). */
@Composable
fun AppMark(modifier: Modifier = Modifier) {
    // The icon's background is white, so give it a hairline edge to stay visible on light surfaces.
    Box(modifier.clip(RoundedCornerShape(30)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(30))) {
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize().scale(1.5f))
    }
}
