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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dogrouter.data.entity.TransportState
import app.dogrouter.data.remote.AddressSuggestion
import app.dogrouter.ui.common.AddressAutocompleteField
import app.dogrouter.ui.common.AddressMapPreview
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DogEditScreen(
    dogId: String?,
    onDone: () -> Unit,
    onPickOnMap: (lat: Double?, lon: Double?) -> Unit,
    pickedAddress: AddressSuggestion? = null,
    viewModel: DogEditViewModel = koinViewModel { parametersOf(dogId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val addressSuggestions by viewModel.addressSuggestions.collectAsStateWithLifecycle()
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

    // Apply a picked address as soon as the navigation result arrives.
    LaunchedEffect(pickedAddress) {
        if (pickedAddress != null) viewModel.pickAddressSuggestion(pickedAddress)
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
                addressSuggestions = addressSuggestions,
                onChange = viewModel::update,
                onAddressTextChange = viewModel::onAddressTextChange,
                onAddressPick = viewModel::pickAddressSuggestion,
                onOpenMapPicker = {
                    onPickOnMap(state.addressLatitude, state.addressLongitude)
                },
                onAddScheduleRule = viewModel::addScheduleRule,
                onRemoveScheduleRule = viewModel::removeScheduleRule,
                onToggleWeekday = viewModel::toggleWeekday,
                onEarliestStartChange = viewModel::setEarliestStart,
                onLatestEndChange = viewModel::setLatestEnd,
                onDurationChange = viewModel::setDurationMinutes,
                onAllowLongerWalkChange = viewModel::setAllowLongerWalk,
                onToggleIncompatibility = viewModel::toggleIncompatibility,
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
            text = { Text("This removes the dog and its schedule rules. It cannot be undone.") },
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
    addressSuggestions: List<AddressSuggestion>,
    onChange: (DogFormState.() -> DogFormState) -> Unit,
    onAddressTextChange: (String) -> Unit,
    onAddressPick: (AddressSuggestion) -> Unit,
    onOpenMapPicker: () -> Unit,
    onAddScheduleRule: () -> Unit,
    onRemoveScheduleRule: (ruleId: String) -> Unit,
    onToggleWeekday: (ruleId: String, day: java.time.DayOfWeek) -> Unit,
    onEarliestStartChange: (ruleId: String, time: java.time.LocalTime?) -> Unit,
    onLatestEndChange: (ruleId: String, time: java.time.LocalTime?) -> Unit,
    onDurationChange: (ruleId: String, minutes: Int) -> Unit,
    onAllowLongerWalkChange: (Boolean) -> Unit,
    onToggleIncompatibility: (otherDogId: String) -> Unit,
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
        AddressAutocompleteField(
            value = state.address,
            isValidated = state.addressLatitude != null,
            suggestions = addressSuggestions,
            label = "Pickup / drop-off address",
            onValueChange = onAddressTextChange,
            onPick = onAddressPick,
        )
        OutlinedButton(
            onClick = onOpenMapPicker,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Place, contentDescription = null)
            Text("  Pick on map")
        }
        val lat = state.addressLatitude
        val lon = state.addressLongitude
        if (lat != null && lon != null) {
            AddressMapPreview(
                latitude = lat,
                longitude = lon,
                modifier = Modifier.clickable(onClick = onOpenMapPicker),
            )
        }
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
        SectionTitle("Walk constraints")
        AllowLongerWalkRow(
            value = state.allowLongerWalk,
            onChange = onAllowLongerWalkChange,
        )
        IncompatibilitiesSection(
            selectedIds = state.incompatibleDogIds,
            candidates = state.incompatibilityCandidates,
            onToggle = onToggleIncompatibility,
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

@Composable
private fun AllowLongerWalkRow(
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!value) },
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Checkbox(checked = value, onCheckedChange = onChange)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = "Allow longer walks",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Off for puppies and dogs that should not be walked " +
                    "more than the requested duration.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun IncompatibilitiesSection(
    selectedIds: Set<String>,
    candidates: List<IncompatibilityCandidate>,
    onToggle: (otherDogId: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Incompatible dogs",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (candidates.isEmpty()) {
            Text(
                text = "Add other dogs first to mark incompatible pairs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "Selected dogs will never share a trip with this one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                candidates.forEach { candidate ->
                    FilterChip(
                        selected = candidate.id in selectedIds,
                        onClick = { onToggle(candidate.id) },
                        label = { Text(candidate.name) },
                    )
                }
            }
        }
    }
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
