package com.gokul.lmpapp.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.gokul.lmpapp.MainActivity
import java.util.Locale

/** Home-screen widget showing the LBMP at the last resolved NYISO zone. */
class LmpWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetStateStore.load(context)
        provideContent {
            GlanceTheme {
                WidgetContent(state)
            }
        }
    }
}

@Composable
private fun WidgetContent(state: WidgetState) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.zoneName,
            style = TextStyle(
                color = GlanceTheme.colors.onBackground,
                fontSize = 12.sp,
            ),
            maxLines = 1,
        )
        Text(
            text = state.lmp?.let { "$%.2f".format(Locale.US, it) } ?: "—",
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            text = if (state.refId.isNotBlank()) "per MWh · ${state.refId}" else "per MWh",
            style = TextStyle(
                color = GlanceTheme.colors.onBackground,
                fontSize = 10.sp,
            ),
            maxLines = 1,
        )
    }
}

class LmpWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = LmpWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefreshWorker.cancel(context)
    }
}
