package com.gokul.lmpapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gokul.lmpapp.data.FuelMix
import com.gokul.lmpapp.data.HourPrice
import com.gokul.lmpapp.data.LoadSnapshot
import com.gokul.lmpapp.data.NearbyNode
import com.gokul.lmpapp.ui.theme.Sky
import com.gokul.lmpapp.ui.theme.fuelColor
import com.gokul.lmpapp.ui.theme.isCarbonFree
import java.util.Locale
import kotlin.math.roundToInt

/** NYISO statewide summer capability, MW — scale for the load bar. */
private const val NY_SUMMER_CAPACITY_MW = 32_000.0

private val GlassShape = RoundedCornerShape(22.dp)
private val GlassBg = Color.White.copy(alpha = 0.15f)
private val GlassBorder = Color.White.copy(alpha = 0.22f)

private fun Modifier.glass(): Modifier =
    background(GlassBg, GlassShape).border(1.dp, GlassBorder, GlassShape)

@Composable
fun LmpScreen(
    viewModel: LmpViewModel,
    onRequestPermission: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val price = state.nearest?.price?.lmp
    val sky = Sky.forPrice(price ?: 40.0)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to sky.top,
                    0.52f to sky.mid,
                    1.0f to sky.bot,
                )
            ),
    ) {
        // soft sun glow, top-right
        Box(
            modifier = Modifier
                .size(240.dp)
                .offset(x = 220.dp, y = (-60).dp)
                .background(
                    Brush.radialGradient(
                        0.0f to sky.glow.copy(alpha = 0.5f),
                        0.7f to Color.Transparent,
                    ),
                    CircleShape,
                ),
        )

        // refresh control overlay
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isLoadingLmp || state.isLoadingFuelMix) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            }
            IconButton(onClick = viewModel::refresh) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = Color.White.copy(alpha = 0.9f),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 56.dp, bottom = 42.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!state.permissionGranted) {
                item { PermissionCard(onRequestPermission) }
            } else {
                state.lmpError?.let { item { ErrorCard(it, viewModel::refresh) } }

                state.nearest?.let { nearest ->
                    item { Header(nearest) }
                    item { GiantPrice(nearest, state.trend, sky.verdict, sky.condition) }
                    if (state.nextHours.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(18.dp))
                            NextHoursCard(state.nextHours)
                        }
                    }
                    item {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LoadTile(state.loadSnapshot, Modifier.weight(1f))
                            RangeTile(state.todayLo, state.todayHi, nearest.price?.lmp, Modifier.weight(1f))
                        }
                    }
                }
                state.fuelMixError?.let {
                    item {
                        Spacer(Modifier.height(10.dp))
                        ErrorCard(it, viewModel::refresh)
                    }
                }
                state.fuelMix?.let { mix ->
                    item {
                        Spacer(Modifier.height(10.dp))
                        MixCard(mix)
                    }
                }
                state.lmpRefId.takeIf { it.isNotBlank() }?.let { refId ->
                    item {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "NYISO · $refId",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

// ── header + giant price ─────────────────────────────────────────────────

@Composable
private fun Header(nearest: NearbyNode) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(4.dp))
        Text(
            nearest.node.displayName.substringBefore(" (").trim(),
            fontSize = 30.sp,
            fontWeight = FontWeight.Normal,
            color = Color.White,
        )
        Text(
            zoneSubtitle(nearest),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

private fun zoneSubtitle(nearest: NearbyNode): String {
    val zoneLetter = Regex("Zone ([A-K])").find(nearest.node.displayName)?.groupValues?.get(1)
    val name = nearest.price?.cpNodeName ?: nearest.node.nodeId
    return if (zoneLetter != null) "Zone $zoneLetter · $name" else name
}

@Composable
private fun GiantPrice(nearest: NearbyNode, trend: Double?, verdict: String, condition: String) {
    val lmp = nearest.price?.lmp ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.padding(top = 10.dp)) {
            Text(
                "$",
                fontSize = 52.sp,
                fontWeight = FontWeight.Light,
                color = Color.White,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "${lmp.roundToInt()}",
                fontSize = 132.sp,
                fontWeight = FontWeight.ExtraLight,
                letterSpacing = (-4).sp,
                color = Color.White,
                lineHeight = 132.sp,
            )
        }
        Text(
            "/MWh · real-time LMP",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.92f),
        )
        Text(
            verdict,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        val trendText = trend?.let {
            val arrow = if (it >= 0) "▲" else "▼"
            "$arrow $%.0f in the last hour · ".format(Locale.US, kotlin.math.abs(it))
        } ?: ""
        Text(
            "$trendText$condition",
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// ── next hours (day-ahead) ───────────────────────────────────────────────

@Composable
private fun NextHoursCard(hours: List<HourPrice>) {
    val lo = hours.minOf { it.price }
    val hi = hours.maxOf { it.price }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass()
            .padding(top = 12.dp, bottom = 14.dp, start = 6.dp, end = 6.dp),
    ) {
        Text(
            "NEXT HOURS · \$/MWH · DAY-AHEAD",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
            color = Color.White.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 12.dp, bottom = 10.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            hours.forEachIndexed { k, h ->
                val tier = Sky.forPrice(h.price)
                val frac = if (hi > lo) (h.price - lo) / (hi - lo) else 0.5
                val barH = (14 + frac * 46).dp
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (k == 0) "Now" else hourLabel(h.hour),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Box(
                        modifier = Modifier
                            .width(9.dp)
                            .height(barH)
                            .background(tier.bot, RoundedCornerShape(5.dp)),
                    )
                    Text(
                        "${h.price.roundToInt()}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

private fun hourLabel(hour: Int) = when (val h = ((hour % 24) + 24) % 24) {
    0 -> "12AM"; 12 -> "12PM"
    in 1..11 -> "${h}AM"
    else -> "${h - 12}PM"
}

// ── load + range tiles ───────────────────────────────────────────────────

@Composable
private fun LoadTile(loads: LoadSnapshot?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.glass().padding(14.dp)) {
        TileLabel("LOAD")
        val total = loads?.totalMw
        Text(
            total?.let { formatMw(it) } ?: "—",
            fontSize = 26.sp,
            fontWeight = FontWeight.Normal,
            color = Color.White,
            modifier = Modifier.padding(top = 8.dp),
        )
        val frac = total?.let { (it / NY_SUMMER_CAPACITY_MW).coerceIn(0.0, 1.0) } ?: 0.0
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(5.dp)
                .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(frac.toFloat())
                    .height(5.dp)
                    .background(Color.White, RoundedCornerShape(3.dp)),
            )
        }
        Text(
            total?.let { "${(frac * 100).roundToInt()}% of summer capacity" } ?: "loading…",
            fontSize = 11.5.sp,
            color = Color.White.copy(alpha = 0.82f),
            modifier = Modifier.padding(top = 7.dp),
        )
    }
}

@Composable
private fun RangeTile(lo: Double?, hi: Double?, current: Double?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.glass().padding(14.dp)) {
        TileLabel("TODAY'S RANGE")
        Text(
            if (lo != null && hi != null) "$${lo.roundToInt()} – $${hi.roundToInt()}" else "—",
            fontSize = 26.sp,
            fontWeight = FontWeight.Normal,
            color = Color.White,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Text("low", fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.8f))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .background(
                        Brush.horizontalGradient(Sky.rangeGradient),
                        RoundedCornerShape(3.dp),
                    ),
            )
            Text("high", fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.8f))
        }
        val position = if (lo != null && hi != null && current != null && hi > lo) {
            when {
                current <= lo + (hi - lo) * 0.33 -> "You're near the low end"
                current >= lo + (hi - lo) * 0.66 -> "You're near the high end"
                else -> "You're mid-range"
            }
        } else "today so far"
        Text(
            position,
            fontSize = 11.5.sp,
            color = Color.White.copy(alpha = 0.82f),
            modifier = Modifier.padding(top = 7.dp),
        )
    }
}

@Composable
private fun TileLabel(text: String) {
    Text(
        text,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
        color = Color.White.copy(alpha = 0.7f),
        fontFamily = FontFamily.Monospace,
    )
}

// ── resource mix ─────────────────────────────────────────────────────────

@Composable
private fun MixCard(mix: FuelMix) {
    val carbonFreePct = if (mix.totalMw > 0) {
        mix.categories.filter { isCarbonFree(it.name) }.sumOf { it.mw } / mix.totalMw * 100
    } else 0.0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass()
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            TileLabel("RESOURCE MIX")
            Text(
                "${carbonFreePct.roundToInt()}% carbon-free",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFEAFFF4),
            )
        }
        // stacked bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 11.dp)
                .height(14.dp)
                .background(Color.Transparent, RoundedCornerShape(7.dp)),
        ) {
            mix.categories.forEach { category ->
                val share = mix.share(category).toFloat()
                if (share > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(share)
                            .fillMaxSize()
                            .background(fuelColor(category.name)),
                    )
                }
            }
        }
        // legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            mix.categories.take(3).forEach { LegendChip(it.name, mix.share(it) * 100) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            mix.categories.drop(3).take(3).forEach { LegendChip(it.name, mix.share(it) * 100) }
        }
        if (mix.refId.isNotBlank()) {
            Text(
                mix.refId,
                fontSize = 10.5.sp,
                color = Color.White.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun LegendChip(name: String, pct: Double) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(fuelColor(name), RoundedCornerShape(3.dp)),
        )
        Text(name, fontSize = 12.sp, color = Color.White.copy(alpha = 0.92f))
        Text(
            "${pct.roundToInt()}%",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

// ── permission + error ───────────────────────────────────────────────────

@Composable
private fun PermissionCard(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 120.dp)
            .glass()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.LocationOn,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = Color.White,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Weather, but for the price of electricity. " +
                "This app reads your closest NYISO zone and shows its real-time " +
                "LMP — the sky shifts color with the price.",
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            color = Color.White,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color(0xFF194F81),
            ),
        ) { Text("Use my location") }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().glass().padding(16.dp)) {
        Text(message, fontSize = 14.sp, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.9f),
                contentColor = Color(0xFF1C1C1A),
            ),
        ) { Text("Retry") }
    }
}

private fun formatMw(mw: Double): String =
    if (mw >= 1000) "%.1f GW".format(Locale.US, mw / 1000) else "${mw.roundToInt()} MW"
