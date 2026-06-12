package com.gokul.lmpapp.data

import java.time.LocalDateTime
import kotlin.math.abs

/** A pricing node from the bundled directory, with approximate coordinates. */
data class NodeInfo(
    val nodeId: String,
    val aliases: List<String>,
    val displayName: String,
    val type: NodeType,
    val lat: Double,
    val lon: Double,
) {
    /** All CPNode names this directory entry may appear under, uppercased. */
    val matchKeys: Set<String> = (aliases + nodeId).map { it.uppercase() }.toSet()
}

enum class NodeType { HUB, ZONE }

/**
 * Current price at one pricing node, in $/MWh. Components are normalized to
 * the additive convention lmp = energy + congestion + loss regardless of the
 * source market's published sign convention.
 */
data class NodePrice(
    val cpNodeName: String,
    val lmp: Double,
    val congestion: Double?,
    val loss: Double?,
) {
    val energy: Double?
        get() = if (congestion != null && loss != null) lmp - congestion - loss else null
}

/** Full snapshot of the consolidated LMP table. */
data class LmpSnapshot(
    val refId: String,
    val prices: Map<String, NodePrice>, // keyed by uppercased CPNode name
)

/** A directory node joined with its current price and distance from the user. */
data class NearbyNode(
    val node: NodeInfo,
    val price: NodePrice?,
    val distanceKm: Double,
)

data class FuelCategory(
    val name: String,
    val mw: Double,
)

/** One observed price for a zone at a 5-minute interval. */
data class PricePoint(val ts: LocalDateTime, val lmp: Double)

/** A price at a specific hour slot (0 = midnight today, 24 = midnight tomorrow). */
data class HourPrice(val hour: Int, val price: Double)

/** Today's accumulated 5-minute price series for one zone. */
data class ZoneSeries(val points: List<PricePoint>) {

    val isEmpty: Boolean get() = points.isEmpty()
    val lo: Double? get() = points.minOfOrNull { it.lmp }
    val hi: Double? get() = points.maxOfOrNull { it.lmp }

    /** Latest price minus the price observed closest to one hour earlier. */
    fun trendVsHourAgo(): Double? {
        val latest = points.maxByOrNull { it.ts } ?: return null
        val target = latest.ts.minusHours(1)
        val anchor = points
            .filter { it.ts <= target.plusMinutes(15) && it.ts != latest.ts }
            .minByOrNull { abs(java.time.Duration.between(it.ts, target).toMinutes()) }
            ?: return null
        return latest.lmp - anchor.lmp
    }

    /** Average price per hour-of-day, for the hours observed so far. */
    fun hourlyAverages(): List<HourPrice> =
        points.groupBy { it.ts.hour }
            .map { (hour, pts) -> HourPrice(hour, pts.sumOf { it.lmp } / pts.size) }
            .sortedBy { it.hour }
}

/** Real-time actual load for one zone, in MW. */
data class ZoneLoad(
    val zoneName: String,
    val mw: Double,
)

/** Latest real-time actual load by zone (NYISO "pal" feed). */
data class LoadSnapshot(
    val refId: String,
    val loads: Map<String, ZoneLoad>, // keyed by uppercased zone name
) {
    val totalMw: Double get() = loads.values.sumOf { it.mw }

    fun loadFor(node: NodeInfo): ZoneLoad? =
        node.matchKeys.firstNotNullOfOrNull { loads[it] }
}

data class FuelMix(
    val refId: String,
    val totalMw: Double,
    val categories: List<FuelCategory>, // sorted by MW descending
) {
    fun share(category: FuelCategory): Double =
        if (totalMw > 0) category.mw / totalMw else 0.0
}
