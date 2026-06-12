package com.gokul.lmpapp.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.gokul.lmpapp.MainActivity
import com.gokul.lmpapp.ui.theme.Sky
import com.gokul.lmpapp.ui.theme.SkyPalette
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

private val SMALL = DpSize(110.dp, 110.dp)
private val MEDIUM = DpSize(250.dp, 110.dp)
private val LARGE = DpSize(330.dp, 150.dp)

/**
 * Home-screen widget from the design handoff: a sky-gradient tile whose color
 * tracks the live price. Small (2×2) shows price + zone + trend; medium (4×2)
 * adds the 24h curve and a fuel-mix strip.
 */
class LmpWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetStateStore.load(context)
        val hourET = LocalDateTime.now(ZoneId.of("America/New_York")).hour
        provideContent {
            val size = LocalSize.current
            val sky = Sky.forPriceAndTime(state.lmp ?: 40.0, hourET)
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .cornerRadius(30.dp)
                    .clickable(actionStartActivity<MainActivity>()),
            ) {
                Image(
                    provider = ImageProvider(skyBitmap(sky)),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = GlanceModifier.fillMaxSize(),
                )
                when {
                    size.width >= LARGE.width && size.height >= LARGE.height ->
                        MediumContent(state, big = true)
                    size.width >= MEDIUM.width -> MediumContent(state)
                    else -> SmallContent(state)
                }
            }
        }
    }
}

private val WHITE = ColorProvider(Color.White)
private val WHITE_DIM = ColorProvider(Color.White.copy(alpha = 0.92f))

@Composable
private fun SmallContent(state: WidgetState) {
    Column(modifier = GlanceModifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                "NYISO LMP",
                style = TextStyle(color = WHITE_DIM, fontSize = 11.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.defaultWeight())
            state.trend?.let {
                Text(
                    "${if (it >= 0) "▲" else "▼"} ${kotlin.math.abs(it).roundToInt()}",
                    style = TextStyle(color = WHITE, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(
            state.lmp?.let { "$${it.roundToInt()}" } ?: "—",
            style = TextStyle(color = WHITE, fontSize = 52.sp, fontWeight = FontWeight.Normal),
        )
        Text(
            "/MWh · ${shortZone(state.zoneName)}",
            style = TextStyle(color = WHITE_DIM, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun MediumContent(state: WidgetState, big: Boolean = false) {
    val pad = if (big) 20.dp else 16.dp
    val priceSize = if (big) 58.sp else 46.sp
    val labelSize = if (big) 12.sp else 11.sp
    val chartHeight = if (big) 68.dp else 52.dp
    Row(modifier = GlanceModifier.fillMaxSize().padding(pad)) {
        // left — price block
        Column(modifier = GlanceModifier.width(if (big) 150.dp else 120.dp).fillMaxSize()) {
            Text(
                "NYISO · ${shortZone(state.zoneName).uppercase(Locale.US)}",
                style = TextStyle(color = WHITE_DIM, fontSize = labelSize, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                state.lmp?.let { "$${it.roundToInt()}" } ?: "—",
                style = TextStyle(color = WHITE, fontSize = priceSize, fontWeight = FontWeight.Normal),
            )
            Text(
                "/MWh · ${shortZone(state.zoneName)}",
                style = TextStyle(color = WHITE_DIM, fontSize = labelSize, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.width(14.dp))
        // right — curve + stats + mix strip
        Column(modifier = GlanceModifier.fillMaxSize()) {
            if (state.curve.size >= 2) {
                Image(
                    provider = ImageProvider(sparklineBitmap(state.curve)),
                    contentDescription = "Today's price curve",
                    contentScale = ContentScale.Fit,
                    modifier = GlanceModifier.fillMaxWidth().height(chartHeight),
                )
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                state.loadMw?.let {
                    Text(
                        "Load ${formatMw(it)}",
                        style = TextStyle(color = WHITE_DIM, fontSize = labelSize),
                    )
                }
                Spacer(GlanceModifier.defaultWeight())
                state.cleanPct?.let {
                    Text(
                        "${it.roundToInt()}% clean",
                        style = TextStyle(color = WHITE_DIM, fontSize = labelSize, fontWeight = FontWeight.Bold),
                    )
                }
            }
            if (state.mix.isNotEmpty()) {
                Spacer(GlanceModifier.height(7.dp))
                Image(
                    provider = ImageProvider(mixStripBitmap(state.mix)),
                    contentDescription = "Fuel mix",
                    contentScale = ContentScale.FillBounds,
                    modifier = GlanceModifier.fillMaxWidth().height(if (big) 10.dp else 8.dp),
                )
            }
        }
    }
}

private fun shortZone(zoneName: String): String = zoneName.substringBefore(" (").trim()

private fun formatMw(mw: Double): String =
    if (mw >= 1000) "%.1f GW".format(Locale.US, mw / 1000) else "${mw.roundToInt()} MW"

// ── bitmap renderers (Glance has no gradient/canvas primitives) ──────────

private fun skyBitmap(sky: SkyPalette, w: Int = 600, h: Int = 600): Bitmap {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply {
        shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(sky.top.toArgb(), sky.mid.toArgb(), sky.bot.toArgb()),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    return bitmap
}

private fun sparklineBitmap(curve: List<Double>, w: Int = 480, h: Int = 140): Bitmap {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val min = curve.min()
    val max = curve.max()
    val x = { i: Int -> i.toFloat() / (curve.size - 1) * w }
    val y = { v: Double ->
        (h - 12) - ((v - min) / (max - min).coerceAtLeast(0.01) * (h - 24)).toFloat()
    }
    val line = Path().apply {
        curve.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
    }
    val area = Path(line).apply {
        lineTo(w.toFloat(), h.toFloat()); lineTo(0f, h.toFloat()); close()
    }
    canvas.drawPath(area, Paint().apply { color = 0x2EFFFFFF; style = Paint.Style.FILL })
    canvas.drawPath(line, Paint().apply {
        color = android.graphics.Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    })
    // dot on the latest point
    canvas.drawCircle(x(curve.size - 1), y(curve.last()), 10f, Paint().apply {
        color = android.graphics.Color.WHITE; isAntiAlias = true
    })
    return bitmap
}

private fun mixStripBitmap(mix: List<WidgetMixEntry>, w: Int = 480, h: Int = 24): Bitmap {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val total = mix.sumOf { it.pct }.coerceAtLeast(0.01)
    var xPos = 0f
    val paint = Paint()
    mix.forEach { entry ->
        val width = (entry.pct / total * w).toFloat()
        paint.color = com.gokul.lmpapp.ui.theme.fuelColor(entry.name).toArgb()
        canvas.drawRect(RectF(xPos, 0f, xPos + width, h.toFloat()), paint)
        xPos += width
    }
    return bitmap
}

/** Shared receiver behavior for both widget sizes. */
abstract class BaseLmpWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = LmpWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshWorker.schedule(context)
        WidgetRefreshWorker.refreshNow(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: android.appwidget.AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Covers re-added widgets and launcher restarts where the saved
        // state may be empty or stale
        WidgetRefreshWorker.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // The periodic job is shared: cancel only when no widget of either
        // size remains on the home screen
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val remaining = listOf(
            LmpWidgetReceiver::class.java,
            LmpWideWidgetReceiver::class.java,
        ).sumOf {
            manager.getAppWidgetIds(android.content.ComponentName(context, it)).size
        }
        if (remaining == 0) WidgetRefreshWorker.cancel(context)
    }
}

/** 2×2 — price + zone + trend. */
class LmpWidgetReceiver : BaseLmpWidgetReceiver()

/** 4×2 — adds today's curve, load, % clean, and the fuel-mix strip. */
class LmpWideWidgetReceiver : BaseLmpWidgetReceiver()
