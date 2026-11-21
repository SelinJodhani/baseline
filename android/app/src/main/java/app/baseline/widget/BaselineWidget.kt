package app.baseline.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceId
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LocalAppWidgetOptions
import androidx.glance.appwidget.PreviewSizeMode
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent

/**
 * Base for every Baseline widget. Loads one [WidgetState] per update and renders at the exact size the
 * launcher gives (SizeMode.Exact), so each widget can tune its shape and layout to the real cell size.
 *
 * Generated previews (Android 15+) render the same composables with fictional demo data at a few
 * representative sizes; the lock-screen preview uses the lock-screen look.
 */
abstract class BaselineWidget(previewSizes: Set<DpSize>) : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val previewSizeMode: PreviewSizeMode = SizeMode.Responsive(previewSizes)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetData.load(context)
        val numbers = widgetShowNumbers(context)
        val palette = Palette(context)
        provideContent { Root(context, state, palette, numbers, previewCategory = null) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = WidgetData.preview()
        val palette = Palette(context)
        provideContent { Root(context, state, palette, numbers = true, previewCategory = widgetCategory) }
    }

    @Composable
    private fun Root(context: Context, state: WidgetState, palette: Palette, numbers: Boolean, previewCategory: Int?) {
        val category = previewCategory
            ?: LocalAppWidgetOptions.current.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
        val keyguard = category and AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD != 0
        val look = Look(numbers = numbers && !keyguard, keyguard = keyguard, is24h = DateFormat.is24HourFormat(context))
        CompositionLocalProvider(LocalPalette provides palette) { Content(context, state, look, LocalSize.current) }
    }

    /** Draw the widget (including its own shape and the not-connected state, `state.insights == null`). */
    @Composable
    protected abstract fun Content(context: Context, state: WidgetState, look: Look, size: DpSize)
}
