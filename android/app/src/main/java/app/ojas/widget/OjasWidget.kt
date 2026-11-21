package app.ojas.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.PreviewSizeMode
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent

/**
 * Base for every Ojas widget: loads one [WidgetState], draws the shared surface + empty state,
 * and renders a fictional demo state for the launcher's generated preview (Android 15+).
 */
abstract class OjasWidget(private val tab: Int, sizes: Set<DpSize>) : GlanceAppWidget() {
    private val responsive = SizeMode.Responsive(sizes)
    override val sizeMode: SizeMode = responsive
    override val previewSizeMode: PreviewSizeMode = responsive

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetData.load(context)
        provideContent { Frame(context, state) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = WidgetData.preview()
        provideContent { Frame(context, state) }
    }

    @Composable
    private fun Frame(context: Context, state: WidgetState) {
        GlanceTheme {
            val size = LocalSize.current
            val compact = size.width < 140.dp || size.height < 100.dp
            WidgetSurface(context, tab, padding = if (compact) 10.dp else 14.dp) {
                val i = state.insights
                if (i == null) EmptyState(compact) else Content(context, state, size)
            }
        }
    }

    @Composable
    protected abstract fun Content(context: Context, state: WidgetState, size: DpSize)
}
