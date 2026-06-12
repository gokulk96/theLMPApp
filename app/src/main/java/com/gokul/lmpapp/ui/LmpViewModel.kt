package com.gokul.lmpapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gokul.lmpapp.data.FuelMix
import com.gokul.lmpapp.data.LmpRepository
import com.gokul.lmpapp.data.LoadSnapshot
import com.gokul.lmpapp.data.NearbyNode
import com.gokul.lmpapp.data.NodeDirectory
import com.gokul.lmpapp.data.NyisoApiClient
import com.gokul.lmpapp.location.LocationProvider
import com.gokul.lmpapp.location.UserLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

data class LmpUiState(
    val permissionGranted: Boolean = false,
    val location: UserLocation? = null,
    val lmpRefId: String = "",
    val nearbyNodes: List<NearbyNode> = emptyList(),
    val loadSnapshot: LoadSnapshot? = null,
    val fuelMix: FuelMix? = null,
    val isLoadingLmp: Boolean = false,
    val isLoadingFuelMix: Boolean = false,
    val lmpError: String? = null,
    val fuelMixError: String? = null,
) {
    val nearest: NearbyNode? get() = nearbyNodes.firstOrNull { it.price != null }
}

class LmpViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LmpRepository(
        api = NyisoApiClient(),
        directory = NodeDirectory.loadFromAssets(application, "nyiso_nodes.csv"),
    )
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
            val (refId, nodes) = repository.nearbyNodes(location.lat, location.lon)
            // Load data is supplementary — a failure shouldn't hide the LMP
            val loads = runCatching { repository.zoneLoads() }.getOrNull()
            _uiState.update {
                it.copy(
                    location = location,
                    lmpRefId = refId,
                    nearbyNodes = nodes,
                    loadSnapshot = loads ?: it.loadSnapshot,
                    isLoadingLmp = false,
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(isLoadingLmp = false, lmpError = e.message ?: "Failed to load LMP data")
            }
        }
    }

    private suspend fun refreshFuelMix() {
        _uiState.update { it.copy(isLoadingFuelMix = true, fuelMixError = null) }
        try {
            val mix = repository.fuelMix()
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
