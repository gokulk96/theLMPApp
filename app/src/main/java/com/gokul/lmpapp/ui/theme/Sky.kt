package com.gokul.lmpapp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Price → sky color engine from the design handoff. Four hand-tuned skies:
 * cheap+clean = green dawn, fair = blue day, elevated = golden hour,
 * peak = red sunset. Hex values are the design's OKLCH palette converted
 * to sRGB.
 */
data class SkyPalette(
    val name: String,
    val top: Color,
    val mid: Color,
    val bot: Color,
    val glow: Color,
    val verdict: String,
    val condition: String,
)

object Sky {

    private val CLEAN = SkyPalette(
        name = "clean",
        top = Color(0xFF16674F), mid = Color(0xFF469974),
        bot = Color(0xFFA9E1B4), glow = Color(0xFF83D494),
        verdict = "Great time to charge & run appliances",
        condition = "Cheap & clean power right now",
    )
    private val FAIR = SkyPalette(
        name = "fair",
        top = Color(0xFF194F81), mid = Color(0xFF3082B5),
        bot = Color(0xFF96CDEA), glow = Color(0xFF7AC8F5),
        verdict = "Fair time to use power",
        condition = "Typical conditions",
    )
    private val HIGH = SkyPalette(
        name = "high",
        top = Color(0xFF8A5619), mid = Color(0xFFD2833B),
        bot = Color(0xFFF5CE87), glow = Color(0xFFFFBA59),
        verdict = "Elevated — consider deferring big loads",
        condition = "Prices are running above normal",
    )
    private val PEAK = SkyPalette(
        name = "peak",
        top = Color(0xFF6F181E), mid = Color(0xFFC33029),
        bot = Color(0xFFFE843D), glow = Color(0xFFFF7041),
        verdict = "Expensive — hold off on big loads",
        condition = "Peak demand on the grid",
    )

    /** Tier thresholds in $/MWh, from the design: <22, <55, <95, else peak. */
    fun forPrice(price: Double): SkyPalette = when {
        price < 22 -> CLEAN
        price < 55 -> FAIR
        price < 95 -> HIGH
        else -> PEAK
    }

    /** Low→high gradient used by the daily-range bar. */
    val rangeGradient = listOf(
        Color(0xFF7CCD8E), Color(0xFF74C2EE), Color(0xFFFF7F55),
    )
}

/** Fuel category → color, mapped to NYISO's real-time fuel mix names. */
fun fuelColor(category: String): Color = when {
    category.contains("dual", ignoreCase = true) -> Color(0xFFAE6E55)
    category.contains("gas", ignoreCase = true) -> Color(0xFFD58A55)
    category.contains("nuclear", ignoreCase = true) -> Color(0xFF9970C4)
    category.contains("hydro", ignoreCase = true) -> Color(0xFF2F9ECF)
    category.contains("wind", ignoreCase = true) -> Color(0xFF45C2AB)
    category.contains("other renewable", ignoreCase = true) -> Color(0xFFECD065)
    category.contains("solar", ignoreCase = true) -> Color(0xFFECD065)
    else -> Color(0xFF8C939B) // Other Fossil Fuels, Other
}

/** Categories NYISO reports that count as carbon-free generation. */
fun isCarbonFree(category: String): Boolean =
    listOf("nuclear", "hydro", "wind", "other renewable", "solar")
        .any { category.contains(it, ignoreCase = true) }
