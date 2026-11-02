package dev.kpetriv.sprout.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.text.Text

/**
 * Home-screen widget showing a kid's balance and chores due. Placeholder content; the receiver is
 * registered in the manifest when the widget ships (M7).
 */
class SproutWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val title = context.getString(R.string.widget_title)
        provideContent {
            Text(text = title)
        }
    }
}
