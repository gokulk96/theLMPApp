package com.gokul.lmpapp.data

import android.content.Context
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Loads the bundled MISO node directory and answers nearest-node queries. */
class NodeDirectory(private val nodes: List<NodeInfo>) {

    /**
     * Joins the directory with a live price snapshot and returns nodes sorted
     * by distance from ([lat], [lon]). Directory entries that have no price in
     * the snapshot are kept (price = null) so the UI can say so explicitly.
     */
    fun nearestNodes(lat: Double, lon: Double, snapshot: LmpSnapshot?): List<NearbyNode> =
        nodes.map { node ->
            NearbyNode(
                node = node,
                price = snapshot?.let { findPrice(node, it) },
                distanceKm = haversineKm(lat, lon, node.lat, node.lon),
            )
        }.sortedBy { it.distanceKm }

    private fun findPrice(node: NodeInfo, snapshot: LmpSnapshot): NodePrice? =
        node.matchKeys.firstNotNullOfOrNull { snapshot.prices[it] }

    companion object {
        fun loadFromAssets(context: Context, assetFile: String): NodeDirectory =
            context.assets.open(assetFile).bufferedReader().useLines { lines ->
                NodeDirectory(lines.mapNotNull(::parseLine).toList())
            }

        internal fun parseLine(line: String): NodeInfo? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
            val parts = trimmed.split(",")
            if (parts.size != 6) return null
            return NodeInfo(
                nodeId = parts[0].trim(),
                aliases = parts[1].split("|").map { it.trim() }.filter { it.isNotEmpty() },
                displayName = parts[2].trim(),
                type = if (parts[3].trim().equals("HUB", ignoreCase = true)) {
                    NodeType.HUB
                } else {
                    NodeType.ZONE
                },
                lat = parts[4].trim().toDoubleOrNull() ?: return null,
                lon = parts[5].trim().toDoubleOrNull() ?: return null,
            )
        }

        internal fun haversineKm(
            lat1: Double, lon1: Double,
            lat2: Double, lon2: Double,
        ): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
        }

        private const val EARTH_RADIUS_KM = 6371.0
    }
}
