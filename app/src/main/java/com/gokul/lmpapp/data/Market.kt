package com.gokul.lmpapp.data

/** A source of live LMP, fuel mix, load, and curve data for one ISO/RTO. */
interface MarketDataSource {
    suspend fun fetchLmpSnapshot(): LmpSnapshot
    suspend fun fetchFuelMix(): FuelMix
    suspend fun fetchZoneLoads(): LoadSnapshot
    suspend fun fetchZoneSeries(matchKeys: Set<String>): ZoneSeries
    suspend fun fetchDayAheadCurve(matchKeys: Set<String>): List<HourPrice>
}
