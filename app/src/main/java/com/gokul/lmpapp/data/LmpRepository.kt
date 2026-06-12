package com.gokul.lmpapp.data

/** Single entry point the UI layer uses for market data. */
class LmpRepository(
    private val api: MisoApiClient,
    private val directory: NodeDirectory,
) {
    suspend fun nearbyNodes(lat: Double, lon: Double): Pair<String, List<NearbyNode>> {
        val snapshot = api.fetchLmpSnapshot()
        return snapshot.refId to directory.nearestNodes(lat, lon, snapshot)
    }

    suspend fun fuelMix(): FuelMix = api.fetchFuelMix()
}
