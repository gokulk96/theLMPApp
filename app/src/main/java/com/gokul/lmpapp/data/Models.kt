package com.gokul.lmpapp.data

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

/** One row of the MISO 5-minute consolidated LMP table. All values $/MWh. */
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

data class FuelMix(
    val refId: String,
    val totalMw: Double,
    val categories: List<FuelCategory>, // sorted by MW descending
) {
    fun share(category: FuelCategory): Double =
        if (totalMw > 0) category.mw / totalMw else 0.0
}
