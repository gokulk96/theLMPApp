package com.gokul.lmpapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gokul.lmpapp.data.FuelMix
import com.gokul.lmpapp.data.LmpRepository
import com.gokul.lmpapp.data.Market
import com.gokul.lmpapp.data.NearbyNode
import com.gokul.lmpapp.data.NodeDirectory
import com.gokul.lmpapp.location.LocationProvider
import com.gokul.lmpapp.location.UserLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/** How the active market is chosen: nearest pricing point wins, or pinned. */
enum class MarketMode(val displayName: String) {
    AUTO("Auto (nearest)"),
    MISO("MISO"),
    NYISO("NYISO");

    val pinnedMarket: Market?
        get() = when (this) {
            AUTO -> null
            MISO -> Market.MISO
            NYISO -> Market.NYISO
        }
}

data class LmpUiState(
    val permissionGranted: Boolean = false,
    val location: UserLocation? = null,
    val marketMode: MarketMode = MarketMode.AUTO,
    val activeMarket: Market = Market.MISO,
    val lmpRefId: String = "",
    val nearbyNodes: List<NearbyNode> = emptyList(),
    val fuelMix: FuelMix? = null,
    val isLoadingLmp: Boolean = false,
    val isLoadingFuelMix: Boolean = false,
    val lmpError: String? = null,
    val fuelMixError: String? = null,
) {
    val nearest: NearbyNode? get() = nearbyNodes.firstOrNull { it.price != null }
}

class LmpViewModel(application: Application) : AndroidViewModel(application) {

    private val repositories: Map<Market, LmpRepository> = Market.entries.associateWith {
        LmpRepository(
            api = it.createDataSource(),
            directory = NodeDirectory.loadFromAssets(application, it.assetFile),
        )
    }
    private val locationProvider = LocationProvider(application)

    private val _uiState = MutableStateFlow(
        LmpUiState(permissionGranted = locationProvider.hasPermission())
    )
    val uiState: StateFlow<LmpUiState> = _uiState

    private var lmpLoop: Job? = null
    private var fuelMixLoop: Job? = null

    init {
        startFuelMixLoop()
        if (locationProvider.hasPermission()) onPermissionGranted()
    }

    fun onPermissionGranted() {
        _uiState.update { it.copy(permissionGranted = true) }
        startLmpLoop()
    }

    fun onPermissionDenied() {
        _uiState.update { it.copy(permissionGranted = false) }
    }

    fun setMarketMode(mode: MarketMode) {
        _uiState.update {
            it.copy(
                marketMode = mode,
                activeMarket = mode.pinnedMarket ?: it.activeMarket,
            )
        }
        refresh()
    }

    fun refresh() {
        startLmpLoop()
        startFuelMixLoop()
    }

    private fun startLmpLoop() {
        lmpLoop?.cancel()
        lmpLoop = viewModelScope.launch {
            while (true) {
                refreshLmp()
                delay(LMP_REFRESH_INTERVAL)
            }
        }
    }

    private fun startFuelMixLoop() {
        fuelMixLoop?.cancel()
        fuelMixLoop = viewModelScope.launch {
            while (true) {
                refreshFuelMix()
                delay(FUEL_MIX_REFRESH_INTERVAL)
            }
        }
    }

    private suspend fun refreshLmp() {
        if (!locationProvider.hasPermission()) return
        _uiState.update { it.copy(isLoadingLmp = true, lmpError = null) }
        try {
            val location = locationProvider.currentLocation()
                ?: throw IllegalStateException("Could not determine your location")
            val market = selectMarket(location)
            val marketChanged = market != _uiState.value.activeMarket
            val (refId, nodes) = repositories.getValue(market)
                .nearbyNodes(location.lat, location.lon)
            _uiState.update {
                it.copy(
                    location = location,
                    activeMarket = market,
                    lmpRefId = refId,
                    nearbyNodes = nodes,
                    isLoadingLmp = false,
                )
            }
            // The fuel mix is market-specific, so a market switch invalidates it
            if (marketChanged) startFuelMixLoop()
        } catch (e: Exception) {
            _uiState.update {
                it.copy(isLoadingLmp = false, lmpError = e.message ?: "Failed to load LMP data")
            }
        }
    }

    private fun selectMarket(location: UserLocation): Market =
        _uiState.value.marketMode.pinnedMarket
            ?: repositories.entries.minBy { (_, repo) ->
                repo.directory.nearestNodes(location.lat, location.lon, null)
                    .first().distanceKm
            }.key

    private suspend fun refreshFuelMix() {
        _uiState.update { it.copy(isLoadingFuelMix = true, fuelMixError = null) }
        try {
            val mix = repositories.getValue(_uiState.value.activeMarket).fuelMix()
            _uiState.update { it.copy(fuelMix = mix, isLoadingFuelMix = false) }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoadingFuelMix = false,
                    fuelMixError = e.message ?: "Failed to load fuel mix",
                )
            }
        }
    }

    companion object {
        private val LMP_REFRESH_INTERVAL = 5.minutes
        private val FUEL_MIX_REFRESH_INTERVAL = 60.minutes
    }
}
