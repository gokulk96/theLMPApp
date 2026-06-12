package com.gokul.lmpapp.data

/** A source of live LMP and fuel mix data for one ISO/RTO. */
interface MarketDataSource {
    suspend fun fetchLmpSnapshot(): LmpSnapshot
    suspend fun fetchFuelMix(): FuelMix
}
