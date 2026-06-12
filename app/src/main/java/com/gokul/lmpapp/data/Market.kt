package com.gokul.lmpapp.data

/** A source of live LMP and fuel mix data for one ISO/RTO. */
interface MarketDataSource {
    suspend fun fetchLmpSnapshot(): LmpSnapshot
    suspend fun fetchFuelMix(): FuelMix
}

/** The wholesale markets the app knows about. */
enum class Market(val displayName: String, val assetFile: String) {
    MISO("MISO", "miso_nodes.csv"),
    NYISO("NYISO", "nyiso_nodes.csv");

    fun createDataSource(): MarketDataSource = when (this) {
        MISO -> MisoApiClient()
        NYISO -> NyisoApiClient()
    }
}
