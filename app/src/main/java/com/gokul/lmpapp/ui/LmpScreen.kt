package com.gokul.lmpapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gokul.lmpapp.data.FuelMix
import com.gokul.lmpapp.data.NearbyNode
import com.gokul.lmpapp.data.NodeType
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LmpScreen(
    viewModel: LmpViewModel,
    onRequestPermission: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LMP Near Me — MISO") },
                actions = {
                    if (state.isLoadingLmp || state.isLoadingFuelMix) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp), strokeWidth = 2.dp
                        )
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!state.permissionGranted) {
                item { PermissionCard(onRequestPermission) }
            } else {
                state.lmpError?.let { item { ErrorCard(it, viewModel::refresh) } }
                state.nearest?.let { nearest ->
                    item { NearestNodeCard(nearest, state.lmpRefId) }
                }
                val others = state.nearbyNodes
                    .filter { it != state.nearest }
                    .take(6)
                if (others.isNotEmpty()) {
                    item {
                        Text(
                            "Other nearby pricing points",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(others) { NearbyNodeRow(it) }
                }
            }

            item {
                Text("MISO generation mix", style = MaterialTheme.typography.titleMedium)
            }
            state.fuelMixError?.let { item { ErrorCard(it, viewModel::refresh) } }
            state.fuelMix?.let { mix ->
                item { FuelMixCard(mix) }
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequestPermission: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "This app finds the MISO pricing node nearest to you and shows " +
                    "its current locational marginal price.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequestPermission) { Text("Use my location") }
        }
    }
}

@Composable
private fun NearestNodeCard(nearest: NearbyNode, refId: String) {
    val price = nearest.price ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(8.dp))
                Column {
                    Text(
                        nearest.node.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${price.cpNodeName} · ${nearest.node.type.label()} · " +
                            "%.0f km away".format(Locale.US, nearest.distanceKm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "$%.2f".format(Locale.US, price.lmp),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = lmpColor(price.lmp),
            )
            Text("per MWh", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ComponentStat("Energy", price.energy)
                ComponentStat("Congestion", price.congestion)
                ComponentStat("Loss", price.loss)
            }
            if (refId.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    refId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ComponentStat(label: String, value: Double?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value?.let { "$%.2f".format(Locale.US, it) } ?: "—",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NearbyNodeRow(item: NearbyNode) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.node.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${item.node.nodeId} · ${item.node.type.label()} · " +
                        "%.0f km".format(Locale.US, item.distanceKm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                item.price?.let { "$%.2f".format(Locale.US, it.lmp) } ?: "n/a",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = item.price?.let { lmpColor(it.lmp) }
                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FuelMixCard(mix: FuelMix) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Total generation: %,.0f MW".format(Locale.US, mix.totalMw),
                style = MaterialTheme.typography.titleSmall,
            )
            if (mix.refId.isNotBlank()) {
                Text(
                    mix.refId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            mix.categories.forEach { category ->
                val share = mix.share(category)
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(category.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "%,.0f MW · %.1f%%".format(Locale.US, category.mw, share * 100),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    ShareBar(share, fuelColor(category.name))
                }
            }
        }
    }
}

@Composable
private fun ShareBar(share: Double, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(share.toFloat().coerceIn(0f, 1f))
                .height(8.dp)
                .background(color, shape = RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun lmpColor(lmp: Double): Color = when {
    lmp < 0 -> MaterialTheme.colorScheme.error
    lmp > 100 -> Color(0xFFE65100)
    else -> MaterialTheme.colorScheme.primary
}

private fun fuelColor(category: String): Color = when {
    category.contains("coal", ignoreCase = true) -> Color(0xFF5D4037)
    category.contains("gas", ignoreCase = true) -> Color(0xFFFB8C00)
    category.contains("nuclear", ignoreCase = true) -> Color(0xFF7B1FA2)
    category.contains("wind", ignoreCase = true) -> Color(0xFF2E7D32)
    category.contains("solar", ignoreCase = true) -> Color(0xFFFDD835)
    category.contains("hydro", ignoreCase = true) -> Color(0xFF1565C0)
    category.contains("storage", ignoreCase = true) -> Color(0xFF00838F)
    else -> Color(0xFF757575)
}

private fun NodeType.label(): String = when (this) {
    NodeType.HUB -> "Hub"
    NodeType.ZONE -> "Load zone"
}
