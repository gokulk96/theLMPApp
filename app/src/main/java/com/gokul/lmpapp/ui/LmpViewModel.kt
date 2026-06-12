package com.gokul.lmpapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gokul.lmpapp.BuildConfig
import com.gokul.lmpapp.data.ErcotApiClient
import com.gokul.lmpapp.data.FuelMix
import com.gokul.lmpapp.data.HourPrice
import com.gokul.lmpapp.data.LmpRepository
import com.gokul.lmpapp.data.LoadSnapshot
import com.gokul.lmpapp.data.NearbyNode
import com.gokul.lmpapp.data.ZoneSeries
import java.time.LocalDateTime
import java.time.ZoneId
import com.gokul.lmpapp.data.NodeDirectory
import com.gokul.lmpapp.data.NyisoApiClient
import com.gokul.lmpapp.location.LocationProvider
import com.gokul.lmpapp.location.UserLocation
import com.gokul.lmpapp.widget.LmpWidget
import com.gokul.lmpapp.widget.WidgetState
import com.gokul.lmpapp.widget.WidgetStateStore
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

data class LmpUiState(
    val permissionGranted: Boolean = false,
    val market: String = "NYISO",
    val location: UserLocation? = null,
    val lmpRefId: String = "",
    val nearbyNodes: List<NearbyNode> = emptyList(),
    val loadSnapshot: LoadSnapshot? = null,
    val fuelMix: FuelMix? = null,
    val trend: Double? = null,
    val todayLo: Double? = null,
    val todayHi: Double? = null,
    val nextHours: List<HourPrice> = emptyList(),
    val isLoadingLmp: Boolean = false,
    val isLoadingFuelMix: Boolean = false,
    val lmpError: String? = null,
    val fuelMixError: String? = null,
) {
    val nearest: NearbyNode? get() = nearbyNodes.firstOrNull { it.price != null }
}

class LmpViewModel(application: Application) : AndroidViewModel(application) {

    private val nyisoRepo = LmpRepository(
        api = NyisoApiClient(),
        directory = NodeDirectory.loadFromAssets(application, "nyiso_nodes.csv"),
    )
    private val ercotRepo = LmpRepository(
        api = ErcotApiClient(BuildConfig.ERCOT_API_KEY),
        directory = NodeDirectory.loadFromAssets(application, "ercot_zones.csv"),
    )
    private val locationProvider = LocationProvider(application)

    // Texas bounding box — nearly all of Texas is ERCOT territory
    private fun isTexas(lat: Double, lon: Double) = lat in 25.5..36.5 && lon in -105.0..-93.5
    private fun repoFor(lat: Double, lon: Double) = if (isTexas(lat, lon)) ercotRepo else nyisoRepo
    private fun marketFor(lat: Double, lon: Double) = if (isTexas(lat, lon)) "ERCOT" else "NYISO"

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
            val repo = repoFor(location.lat, location.lon)
            val market = marketFor(location.lat, location.lon)
            val (refId, nodes) = repo.nearbyNodes(location.lat, location.lon)
            // Everything beyond the price is supplementary — failures shouldn't hide the LMP
            val loads = runCatching { repo.zoneLoads() }.getOrNull()
            val nearestNode = nodes.firstOrNull { it.price != null }?.node
            val series = nearestNode?.let {
                runCatching { repo.zoneSeries(it) }.getOrNull()
            } ?: ZoneSeries(emptyList())
            val marketTz = if (market == "ERCOT") ZoneId.of("America/Chicago") else ZoneId.of("America/New_York")
            val nowHour = LocalDateTime.now(marketTz).hour
            val nextHours = nearestNode?.let { node ->
                runCatching { repo.dayAheadCurve(node) }.getOrNull()
            }?.filter { it.hour >= nowHour }?.take(NEXT_HOURS_SHOWN).orEmpty()
            _uiState.update {
                it.copy(
                    market = market,
                    location = location,
                    lmpRefId = refId,
                    nearbyNodes = nodes,
                    loadSnapshot = loads ?: it.loadSnapshot,
                    trend = series.trendVsHourAgo(),
                    todayLo = series.lo,
                    todayHi = series.hi,
                    nextHours = nextHours.ifEmpty { it.nextHours },
                    isLoadingLmp = false,
                )
            }
            syncWidget(refId, nodes, market)
        } catch (e: Exception) {
            _uiState.update {
                it.copy(isLoadingLmp = false, lmpError = e.message ?: "Failed to load LMP data")
            }
        }
    }

    /** Keeps the home-screen widget showing the zone the app last resolved. */
    private suspend fun syncWidget(refId: String, nodes: List<NearbyNode>, market: String) {
        val nearest = nodes.firstOrNull { it.price != null } ?: return
        val current = _uiState.value
        runCatching {
            val previous = WidgetStateStore.load(getApplication())
            WidgetStateStore.save(
                getApplication(),
                previous.copy(
                    market = market,
                    zoneId = nearest.node.nodeId,
                    zoneName = nearest.node.displayName,
                    lmp = nearest.price?.lmp,
                    trend = current.trend ?: previous.trend,
                    refId = refId,
                    loadMw = current.loadSnapshot?.totalMw ?: previous.loadMw,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            LmpWidget().updateAll(getApplication())
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
        private const val NEXT_HOURS_SHOWN = 9
    }
}
