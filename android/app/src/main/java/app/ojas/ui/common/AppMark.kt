package app.ojas.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import app.ojas.R

/** The launcher icon, drawn in-app (background + foreground layers, zoomed past the adaptive safe zone). */
@Composable
fun AppMark(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(30))) {
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize().scale(1.45f))
    }
}
