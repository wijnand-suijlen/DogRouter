package app.dogrouter.ui.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dogrouter.data.prefs.AppSettings
import app.dogrouter.data.prefs.BreakLocation
import app.dogrouter.data.remote.AddressSuggestion
import androidx.compose.runtime.LaunchedEffect
import org.koin.androidx.compose.koinViewModel
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanningScreen(
    pickedAddress: AddressSuggestion?,
    onBack: () -> Unit,
    onAddBreakLocation: () -> Unit,
    viewModel: PlanningViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // A returning address from the picker is added once (the nav layer clears
    // the key, so this fires a single time per pick).
    LaunchedEffect(pickedAddress) {
        pickedAddress?.let { viewModel.addLocation(it.label, it.latitude, it.longitude) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Planning") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        val s = settings
        if (s != null) {
            LunchBreakSection(
                settings = s,
                onWindowChange = viewModel::setBreakWindow,
                onDurationChange = viewModel::setBreakDuration,
                onAddLocation = onAddBreakLocation,
                onRemoveLocation = viewModel::removeLocation,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun LunchBreakSection(
    settings: AppSettings,
    onWindowChange: (LocalTime, LocalTime) -> Unit,
    onDurationChange: (Int) -> Unit,
    onAddLocation: () -> Unit,
    onRemoveLocation: (BreakLocation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Lunch break", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "A dog-free break the planner fits when you enable it on Today, " +
                "at the nearest break location within the window below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Break window.
        var editStart by remember { mutableStateOf(false) }
        var editEnd by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Window", modifier = Modifier.width(96.dp), style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = { editStart = true }) {
                Text(settings.breakWindowStart.format(timeFormatter))
            }
            Text(" – ", style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = { editEnd = true }) {
                Text(settings.breakWindowEnd.format(timeFormatter))
            }
        }
        if (editStart) {
            TimePickerDialog(
                initial = settings.breakWindowStart,
                onConfirm = { onWindowChange(it, settings.breakWindowEnd); editStart = false },
                onDismiss = { editStart = false },
            )
        }
        if (editEnd) {
            TimePickerDialog(
                initial = settings.breakWindowEnd,
                onConfirm = { onWindowChange(settings.breakWindowStart, it); editEnd = false },
                onDismiss = { editEnd = false },
            )
        }

        // Break duration (stepper, 5-minute steps).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Duration", modifier = Modifier.width(96.dp), style = MaterialTheme.typography.bodyLarge)
            IconButton(onClick = { onDurationChange(settings.breakDurationMinutes - 5) }) {
                Icon(Icons.Default.Remove, contentDescription = "Less")
            }
            Text(
                "${settings.breakDurationMinutes} min",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.width(64.dp),
            )
            IconButton(onClick = { onDurationChange(settings.breakDurationMinutes + 5) }) {
                Icon(Icons.Default.Add, contentDescription = "More")
            }
        }

        Spacer(Modifier.width(4.dp))
        Text("Break locations", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (settings.breakLocations.isEmpty()) {
            Text(
                "No places yet. Add a café or other dry spot where you can sit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        settings.breakLocations.forEach { location ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        location.label,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(onClick = { onRemoveLocation(location) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove ${location.label}")
                    }
                }
            }
        }
        FilledTonalButton(onClick = onAddLocation) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add break location")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a time") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
