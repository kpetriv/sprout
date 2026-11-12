package dev.kpetriv.sprout.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * The Sprout home screen widget (M7 §4): 2×2, resizable to 4×2 and beyond (`res/xml/sprout_widget_info.xml`). On a
 * linked kid phone it shows the kid's name, balance, screen-time minutes and the chores still to do today; elsewhere
 * "Open Sprout" (D-M7-1). A tap opens the app.
 *
 * The state comes from [source] (the app's `WidgetEntryPoint`), collected while the widget's session runs, so it
 * follows every change then; the app's `WidgetRefresher` asks for an update after a sync, local ledger and chore
 * writes, a time bank stop and every 6 hours.
 */
class SproutWidget(private val source: (Context) -> WidgetStateSource) : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val states = source(context).observe().catch { emit(WidgetState.Blank) }
        val first = states.first()
        val open = openAppAction(context)
        provideContent {
            val state by states.collectAsState(first)
            SproutWidgetContent(state, open)
        }
    }

    companion object {
        /** The 2×2 layout (and anything narrower than [WIDE]). */
        val SMALL: DpSize = DpSize(110.dp, 110.dp)

        /** The 4×2 layout: the chores to do get their own column. */
        val WIDE: DpSize = DpSize(250.dp, 110.dp)

        /** Opens the app's launcher activity, or null when the package has none (never in practice). */
        fun openAppAction(context: Context): Action? =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { actionStartActivity(it) }
    }
}

/**
 * The widget's content for [state] at the current size ([LocalSize]); [open] runs on a tap anywhere. Public for the
 * Glance unit tests and the golden, which render it directly.
 */
@Composable
fun SproutWidgetContent(state: WidgetState, open: Action?) {
    val context = LocalContext.current
    GlanceTheme(colors = SproutWidgetColors) {
        var modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(SproutWidgetCard)
            .cornerRadius(CORNER_RADIUS.dp)
        if (open != null) modifier = modifier.clickable(open)
        when (state) {
            WidgetState.Blank -> Box(
                modifier.semantics { contentDescription = context.getString(R.string.widget_title) },
            ) {}

            WidgetState.OpenApp -> Box(modifier.padding(PADDING.dp), contentAlignment = Alignment.Center) {
                OpenAppContent()
            }

            is WidgetState.Kid -> Box(modifier.padding(PADDING.dp)) {
                if (LocalSize.current.width >= SproutWidget.WIDE.width) WideKid(state) else SmallKid(state)
            }
        }
    }
}

@Composable
private fun OpenAppContent() {
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = context.getString(R.string.widget_title),
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = TITLE_SIZE.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
        Spacer(GlanceModifier.height(SMALL_GAP.dp))
        Text(
            text = context.getString(R.string.widget_open_app),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = BODY_SIZE.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

@Composable
private fun SmallKid(state: WidgetState.Kid) {
    Column(GlanceModifier.fillMaxSize()) {
        KidHeader(state)
        Spacer(GlanceModifier.defaultWeight())
        Text(text = choresLine(state), style = bodyStyle(), maxLines = 2)
    }
}

@Composable
private fun WideKid(state: WidgetState.Kid) {
    Row(GlanceModifier.fillMaxSize()) {
        Column(GlanceModifier.defaultWeight()) { KidHeader(state) }
        Spacer(GlanceModifier.width(COLUMN_GAP.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(
                text = choresLine(state),
                style = bodyStyle().copy(fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface),
                maxLines = 2,
            )
            state.firstToDo.forEach { title ->
                Spacer(GlanceModifier.height(SMALL_GAP.dp))
                Text(text = "• $title", style = bodyStyle(), maxLines = 1)
            }
        }
    }
}

@Composable
private fun ColumnScope.KidHeader(state: WidgetState.Kid) {
    val context = LocalContext.current
    Text(
        text = state.name,
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = BODY_SIZE.sp,
            fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
    )
    state.balance?.let { balance ->
        Text(
            text = balance,
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = BALANCE_SIZE.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
    state.minutes?.let { minutes ->
        Text(text = context.getString(R.string.widget_minutes, minutes), style = bodyStyle(), maxLines = 2)
    }
}

@Composable
private fun choresLine(state: WidgetState.Kid): String {
    val resources = LocalContext.current.resources
    return when {
        state.toDo > 0 -> resources.getQuantityString(R.plurals.widget_chores_to_do, state.toDo, state.toDo)
        state.dueToday > 0 -> resources.getString(R.string.widget_chores_done)
        else -> resources.getString(R.string.widget_no_chores)
    }
}

@Composable
private fun bodyStyle(): TextStyle = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = BODY_SIZE.sp)

private const val CORNER_RADIUS = 16
private const val PADDING = 12
private const val SMALL_GAP = 4
private const val COLUMN_GAP = 12
private const val TITLE_SIZE = 18
private const val BODY_SIZE = 13
private const val BALANCE_SIZE = 24
