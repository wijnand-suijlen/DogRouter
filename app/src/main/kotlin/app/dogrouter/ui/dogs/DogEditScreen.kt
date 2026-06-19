package app.dogrouter.ui.dogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dogrouter.data.entity.TransportState
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DogEditScreen(
    dogId: String?,
    onDone: () -> Unit,
    viewModel: DogEditViewModel = koinViewModel { parametersOf(dogId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                DogEditEvent.Closed -> onDone()
                is DogEditEvent.ValidationError -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isNew) "New dog" else "Edit dog") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!viewModel.isNew) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                    IconButton(onClick = viewModel::save) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            DogForm(
                state = state,
                onChange = viewModel::update,
                onAddScheduleRule = viewModel::addScheduleRule,
                onRemoveScheduleRule = viewModel::removeScheduleRule,
                onToggleWeekday = viewModel::toggleWeekday,
                onEarliestStartChange = viewModel::setEarliestStart,
                onLatestEndChange = viewModel::setLatestEnd,
                onDurationChange = viewModel::setDurationMinutes,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this dog?") },
            text = { Text("This removes the dog and its schedule entries. It cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.delete()
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DogForm(
    state: DogFormState,
    onChange: (DogFormState.() -> DogFormState) -> Unit,
    onAddScheduleRule: () -> Unit,
    onRemoveScheduleRule: (ruleId: String) -> Unit,
    onToggleWeekday: (ruleId: String, day: java.time.DayOfWeek) -> Unit,
    onEarliestStartChange: (ruleId: String, time: java.time.LocalTime?) -> Unit,
    onLatestEndChange: (ruleId: String, time: java.time.LocalTime?) -> Unit,
    onDurationChange: (ruleId: String, minutes: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Dog")
        OutlinedTextField(
            value = state.name,
            onValueChange = { v -> onChange { copy(name = v) } },
            label = { Text("Name *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.breed,
            onValueChange = { v -> onChange { copy(breed = v) } },
            label = { Text("Breed") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.weightKg,
            onValueChange = { v -> onChange { copy(weightKg = v) } },
            label = { Text("Weight (kg) *") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        SectionTitle("Owner")
        OutlinedTextField(
            value = state.ownerName,
            onValueChange = { v -> onChange { copy(ownerName = v) } },
            label = { Text("Owner name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.ownerPhone,
            onValueChange = { v -> onChange { copy(ownerPhone = v) } },
            label = { Text("Phone") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        SectionTitle("Stop")
        OutlinedTextField(
            value = state.address,
            onValueChange = { v -> onChange { copy(address = v) } },
            label = { Text("Pickup / drop-off address") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.stopNotes,
            onValueChange = { v -> onChange { copy(stopNotes = v) } },
            label = { Text("Stop notes (e.g. ring bell, wait ~3 min)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.stopAdjustmentMinutes,
            onValueChange = { v -> onChange { copy(stopAdjustmentMinutes = v) } },
            label = { Text("Stop time adjustment (minutes, +/-)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        SectionTitle("Transport")
        Text("Cargo bike", style = MaterialTheme.typography.bodyMedium)
        TransportStateChooser(
            value = state.inCargoBike,
            onChange = { v -> onChange { copy(inCargoBike = v) } },
        )
        Text("Backpack", style = MaterialTheme.typography.bodyMedium)
        TransportStateChooser(
            value = state.inBackpack,
            onChange = { v -> onChange { copy(inBackpack = v) } },
        )

        Spacer(Modifier.height(8.dp))
        SectionTitle("Schedule")
        ScheduleEditor(
            rules = state.scheduleRules,
            onAddRule = onAddScheduleRule,
            onRemoveRule = onRemoveScheduleRule,
            onToggleWeekday = onToggleWeekday,
            onEarliestStartChange = onEarliestStartChange,
            onLatestEndChange = onLatestEndChange,
            onDurationChange = onDurationChange,
        )

        Spacer(Modifier.height(8.dp))
        SectionTitle("Notes")
        OutlinedTextField(
            value = state.notes,
            onValueChange = { v -> onChange { copy(notes = v) } },
            label = { Text("General notes") },
            modifier = Modifier.fillMaxWidth(),
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransportStateChooser(
    value: TransportState,
    onChange: (TransportState) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val options = TransportState.entries
        options.forEachIndexed { index, state ->
            SegmentedButton(
                selected = value == state,
                onClick = { onChange(state) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(
                    when (state) {
                        TransportState.Yes -> "Yes"
                        TransportState.No -> "No"
                        TransportState.NotTested -> "Not tested"
                    },
                )
            }
        }
    }
}
