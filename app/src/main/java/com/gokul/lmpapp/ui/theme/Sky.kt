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

    // Deep near-black navy used between 22:00 and 05:00 ET.
    private val NIGHT = SkyPalette(
        name = "night",
        top = Color(0xFF070711), mid = Color(0xFF0C1829),
        bot = Color(0xFF142040), glow = Color(0xFF1A2B4A),
        verdict = "Overnight rates",
        condition = "Grid demand is low overnight",
    )

    /** Tier thresholds in $/MWh, from the design: <22, <55, <95, else peak. */
    fun forPrice(price: Double): SkyPalette = when {
        price < 22 -> CLEAN
        price < 55 -> FAIR
        price < 95 -> HIGH
        else -> PEAK
    }

    /**
     * Returns a sky palette that reflects both price tier and time of day.
     * During nighttime hours (22:00–05:00 ET) the sky fades to near-black,
     * mirroring how Apple Weather darkens at night. Dawn (05–07) and dusk
     * (20–22) are smooth linear transitions; verdicts always reflect price.
     */
    fun forPriceAndTime(price: Double, hourET: Int): SkyPalette {
        val day = forPrice(price)
        val t = dayFactor(hourET)
        if (t >= 1f) return day
        return day.copy(
            name = if (t < 0.5f) "night" else day.name,
            top = blendColor(NIGHT.top, day.top, t),
            mid = blendColor(NIGHT.mid, day.mid, t),
            bot = blendColor(NIGHT.bot, day.bot, t),
            glow = blendColor(NIGHT.glow, day.glow, t),
        )
    }

    /**
     * 0.0 = full night, 1.0 = full day.
     * Dawn 05:00–07:00 and dusk 20:00–22:00 ramp linearly.
     */
    private fun dayFactor(hourET: Int): Float = when {
        hourET in 7..19 -> 1f
        hourET == 5 || hourET == 6 -> (hourET - 5) / 2f
        hourET == 20 || hourET == 21 -> (22 - hourET) / 2f
        else -> 0f
    }

    private fun blendColor(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f,
    )

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
