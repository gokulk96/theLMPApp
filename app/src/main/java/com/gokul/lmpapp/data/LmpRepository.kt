package com.gokul.lmpapp.data

/** Live data plus node directory for one market. */
class LmpRepository(
    private val api: MarketDataSource,
    val directory: NodeDirectory,
) {
    suspend fun nearbyNodes(lat: Double, lon: Double): Pair<String, List<NearbyNode>> {
        val snapshot = api.fetchLmpSnapshot()
        return snapshot.refId to directory.nearestNodes(lat, lon, snapshot)
    }

    suspend fun fuelMix(): FuelMix = api.fetchFuelMix()
}
