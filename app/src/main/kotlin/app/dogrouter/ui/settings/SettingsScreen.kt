package app.dogrouter.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dogrouter.data.remote.AddressSuggestion
import app.dogrouter.data.routing.SegmentDownloadState
import app.dogrouter.ui.common.AddressAutocompleteField
import app.dogrouter.ui.common.AddressMapPreview
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onPickHomeOnMap: (lat: Double?, lon: Double?) -> Unit = { _, _ -> },
    pickedAddress: AddressSuggestion? = null,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val testInProgress by viewModel.testInProgress.collectAsStateWithLifecycle()
    val homeSuggestions by viewModel.homeAddressSuggestions.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(pickedAddress) {
        if (pickedAddress != null) viewModel.applyPickedHomeAddress(pickedAddress)
    }

    LaunchedEffect(viewModel) {
        viewModel.routingTestEvents.collect { event ->
            val message = when (event) {
                is RoutingTestEvent.Result ->
                    "Test route: ${event.estimate.distanceMeters} m · " +
                        formatDuration(event.estimate.durationSeconds)
                is RoutingTestEvent.Failed -> "Routing test failed: ${event.message}"
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val state = form
        if (state == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            SettingsForm(
                state = state,
                downloadState = downloadState,
                testInProgress = testInProgress,
                homeSuggestions = homeSuggestions,
                onBikeCapacityChange = viewModel::onBikeCapacityTextChange,
                onStopBufferChange = viewModel::onStopBufferTextChange,
                onCyclingSpeedChange = viewModel::onCyclingSpeedTextChange,
                onHomeAddressTextChange = viewModel::onHomeAddressTextChange,
                onHomeAddressPick = viewModel::pickHomeAddressSuggestion,
                onOpenHomeMapPicker = {
                    onPickHomeOnMap(state.homeLatitude, state.homeLongitude)
                },
                onDownloadRoutingData = viewModel::downloadRoutingData,
                onDeleteRoutingData = viewModel::deleteRoutingData,
                onRunRoutingSelfTest = viewModel::runRoutingSelfTest,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

@Composable
private fun SettingsForm(
    state: SettingsFormState,
    downloadState: SegmentDownloadState,
    testInProgress: Boolean,
    homeSuggestions: List<AddressSuggestion>,
    onBikeCapacityChange: (String) -> Unit,
    onStopBufferChange: (String) -> Unit,
    onCyclingSpeedChange: (String) -> Unit,
    onHomeAddressTextChange: (String) -> Unit,
    onHomeAddressPick: (AddressSuggestion) -> Unit,
    onOpenHomeMapPicker: () -> Unit,
    onDownloadRoutingData: () -> Unit,
    onDeleteRoutingData: () -> Unit,
    onRunRoutingSelfTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Home base")
        AddressAutocompleteField(
            value = state.homeAddress,
            isValidated = state.homeLatitude != null,
            suggestions = homeSuggestions,
            label = "Home address",
            onValueChange = onHomeAddressTextChange,
            onPick = onHomeAddressPick,
        )
        OutlinedButton(
            onClick = onOpenHomeMapPicker,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Place, contentDescription = null)
            Text("  Pick on map")
        }
        val lat = state.homeLatitude
        val lon = state.homeLongitude
        if (lat != null && lon != null) {
            AddressMapPreview(latitude = lat, longitude = lon)
        }
        Text(
            text = "Each cargo-bike trip starts at and returns to this " +
                "address. Pick from autocomplete or drop a pin on the map.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        SectionTitle("Planning parameters")

        OutlinedTextField(
            value = state.bikeCapacityText,
            onValueChange = onBikeCapacityChange,
            label = { Text("Cargo bike weight capacity") },
            suffix = { Text("kg") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = !state.bikeCapacityValid,
            supportingText = if (!state.bikeCapacityValid) {
                { Text("Enter a positive number") }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.stopBufferText,
            onValueChange = onStopBufferChange,
            label = { Text("Default stop time buffer") },
            suffix = { Text("min") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = !state.stopBufferValid,
            supportingText = if (!state.stopBufferValid) {
                { Text("Enter zero or a positive whole number") }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.cyclingSpeedText,
            onValueChange = onCyclingSpeedChange,
            label = { Text("Average cycling speed") },
            suffix = { Text("km/h") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = !state.cyclingSpeedValid,
            supportingText = {
                Text(
                    if (!state.cyclingSpeedValid) "Enter a positive number"
                    else "BRouter picks the route; this speed turns its " +
                        "distances into the times you see in Today.",
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        SectionTitle("Routing data")
        RoutingDataSection(
            state = downloadState,
            testInProgress = testInProgress,
            onDownload = onDownloadRoutingData,
            onDelete = onDeleteRoutingData,
            onTest = onRunRoutingSelfTest,
        )
    }
}

@Composable
private fun RoutingDataSection(
    state: SegmentDownloadState,
    testInProgress: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    Text(
        text = "Île-de-France cycling map (~125 MB). Needed for offline " +
            "distance and time estimates between stops.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    when (state) {
        SegmentDownloadState.Idle -> {
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text("Download (~125 MB)")
            }
        }
        is SegmentDownloadState.Downloading -> {
            val progress = state.totalBytes?.takeIf { it > 0L }
                ?.let { state.bytesRead.toFloat() / it.toFloat() }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Downloading… ${formatMb(state.bytesRead)} / ${
                        state.totalBytes?.let { formatMb(it) } ?: "?"
                    }",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        SegmentDownloadState.Installed -> {
            Text(
                text = "Map downloaded ✓",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = onTest,
                    enabled = !testInProgress,
                    modifier = Modifier.weight(1f),
                ) {
                    if (testInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Test route")
                    }
                }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Text("Delete map")
                }
            }
        }
        is SegmentDownloadState.Failed -> {
            Text(
                text = "Download failed: ${state.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text("Try again")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun formatMb(bytes: Long): String =
    String.format(Locale.ROOT, "%.0f MB", bytes / (1024.0 * 1024.0))

internal fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val remSeconds = seconds % 60
    return when {
        minutes >= 60 -> {
            val hours = minutes / 60
            val mins = minutes % 60
            "%d h %02d min".format(hours, mins)
        }
        minutes > 0 -> "$minutes min ${remSeconds.toString().padStart(2, '0')} s"
        else -> "$seconds s"
    }
}
