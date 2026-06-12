package com.gokul.lmpapp.data

/** A source of live LMP, fuel mix, and load data for one ISO/RTO. */
interface MarketDataSource {
    suspend fun fetchLmpSnapshot(): LmpSnapshot
    suspend fun fetchFuelMix(): FuelMix
    suspend fun fetchZoneLoads(): LoadSnapshot
}
