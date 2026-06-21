package app.dogrouter.ui.today

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dogrouter.domain.dayplan.DayRoute
import app.dogrouter.domain.dayplan.PlanConflict
import app.dogrouter.domain.dayplan.PlanPhase
import app.dogrouter.data.entity.Dog
import app.dogrouter.data.remote.AddressSuggestion
import app.dogrouter.domain.dayplan.PlanState
import app.dogrouter.domain.dayplan.RouteEvent
import app.dogrouter.domain.dayplan.durationAtSeconds
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.ui.common.AddressAutocompleteField
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The day's hard end (8 PM): a chip timed past it is shown in red — the most
 *  common "this can't be done" cue. PlanVerifier carries the exact reason. */
private const val DAY_END_SECONDS = 20 * 3600

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onStartTrip: (LocalDate) -> Unit,
    onOpenLegMap: (from: GeoPoint, to: GeoPoint) -> Unit,
    viewModel: TodayViewModel = koinViewModel(),
) {
    val planState by viewModel.planState.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val breakRequested by viewModel.breakRequested.collectAsStateWithLifecycle()
    val stopBufferSeconds by viewModel.stopBufferSeconds.collectAsStateWithLifecycle()
    val isPlanEdited by viewModel.isPlanEdited.collectAsStateWithLifecycle()
    val removingDogIds by viewModel.removingDogIds.collectAsStateWithLifecycle()
    val planWarnings by viewModel.planWarnings.collectAsStateWithLifecycle()
    val isApplyingEdit by viewModel.isApplyingEdit.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val editItems by viewModel.editItems.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }
    val editMode = editItems != null
    // The walk whose duration is being edited: (chip index, current minutes).
    var editingWalk by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // The pickup whose start time is being edited: (chip index, current seconds).
    var editingStop by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showAddChooser by remember { mutableStateOf(false) }
    var showAddWalk by remember { mutableStateOf(false) }
    var showAddAppointment by remember { mutableStateOf(false) }
    val readyRoute = (planState as? PlanState.Ready)?.route

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Today") },
                actions = {
                    if (editMode && canUndo) {
                        IconButton(onClick = viewModel::undo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo last edit")
                        }
                    }
                    if (editMode) {
                        IconButton(onClick = viewModel::endEdit) {
                            Icon(Icons.Default.Done, contentDescription = "Done editing")
                        }
                    } else if (readyRoute?.events?.isNotEmpty() == true) {
                        IconButton(onClick = viewModel::beginEdit) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit plan")
                        }
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Generate another plan")
                    }
                    IconButton(onClick = viewModel::goToToday) {
                        Icon(Icons.Default.Today, contentDescription = "Jump to today")
                    }
                },
            )
        },
        floatingActionButton = {
            if (editMode) {
                ExtendedFloatingActionButton(
                    onClick = { showAddChooser = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add") },
                )
            } else if (readyRoute?.events?.isNotEmpty() == true) {
                ExtendedFloatingActionButton(
                    onClick = { onStartTrip(selectedDate) },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    text = { Text("Start trip") },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            DateSelector(
                date = selectedDate,
                onPrevious = viewModel::goToPreviousDay,
                onNext = viewModel::goToNextDay,
                onPickDate = { showDatePicker = true },
            )
            if (!editMode) {
                BreakToggleRow(
                    checked = breakRequested,
                    onCheckedChange = viewModel::setBreakRequested,
                )
            }
            if (isPlanEdited) {
                EditedPlanBanner(onRevert = viewModel::revertPlan)
            }
            if (planWarnings.isNotEmpty()) {
                PlanWarningsPanel(planWarnings)
            }
            if (isApplyingEdit) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            HorizontalDivider()
            val items = editItems
            if (editMode && items != null) {
                EditableTimeline(
                    items = items,
                    removingDogIds = removingDogIds,
                    onMove = viewModel::onMove,
                    onReorderStart = viewModel::onReorderStart,
                    onReorderStop = viewModel::onReorderStop,
                    onTapWalk = { index, minutes -> editingWalk = index to minutes },
                    onTapPickupTime = { index, seconds -> editingStop = index to seconds },
                    onSplitMergeWalk = viewModel::splitOrMergeWalk,
                    onToggleLeg = viewModel::toggleLeg,
                    onNotToday = viewModel::markDogNotToday,
                )
            } else {
                DayRouteContent(planState, stopBufferSeconds, onOpenLegMap)
            }
        }
    }

    if (showDatePicker) {
        DatePickerSheet(
            initial = selectedDate,
            onPick = { viewModel.setDate(it); showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }

    editingWalk?.let { (index, minutes) ->
        WalkDurationDialog(
            initialMinutes = minutes,
            onConfirm = { newMinutes ->
                viewModel.setWalkDuration(index, newMinutes)
                editingWalk = null
            },
            onDismiss = { editingWalk = null },
        )
    }

    editingStop?.let { (index, seconds) ->
        StopTimeDialog(
            initialSeconds = seconds,
            onConfirm = { newSeconds ->
                viewModel.setStopTime(index, newSeconds)
                editingStop = null
            },
            onDismiss = { editingStop = null },
        )
    }

    if (showAddChooser) {
        AlertDialog(
            onDismissRequest = { showAddChooser = false },
            title = { Text("Add to the day") },
            text = {
                Column {
                    TextButton(onClick = { showAddChooser = false; showAddWalk = true }) { Text("Walk") }
                    TextButton(onClick = { showAddChooser = false; showAddAppointment = true }) { Text("Appointment") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAddChooser = false }) { Text("Cancel") } },
        )
    }

    if (showAddWalk) {
        val dogs by viewModel.addableDogs.collectAsStateWithLifecycle()
        AddWalkDialog(
            dogs = dogs,
            onConfirm = { dog, minutes ->
                viewModel.addWalk(dog, minutes)
                showAddWalk = false
            },
            onDismiss = { showAddWalk = false },
        )
    }

    if (showAddAppointment) {
        val addressText by viewModel.apptAddressText.collectAsStateWithLifecycle()
        val addressValidated by viewModel.apptAddressValidated.collectAsStateWithLifecycle()
        val suggestions by viewModel.apptAddressSuggestions.collectAsStateWithLifecycle()
        AddAppointmentDialog(
            addressText = addressText,
            addressValidated = addressValidated,
            suggestions = suggestions,
            onAddressChange = viewModel::onApptAddressChange,
            onAddressPick = viewModel::pickApptAddress,
            onConfirm = { label, startSeconds, endSeconds ->
                viewModel.addAppointment(label, startSeconds, endSeconds)
                showAddAppointment = false
            },
            onDismiss = { showAddAppointment = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Edit mode: drag-and-drop chips (position = execution order).
// ---------------------------------------------------------------------------

@Composable
private fun EditableTimeline(
    items: List<EditItem>,
    removingDogIds: Set<String>,
    onMove: (from: Int, to: Int) -> Unit,
    onReorderStart: () -> Unit,
    onReorderStop: () -> Unit,
    onTapWalk: (index: Int, currentMinutes: Int) -> Unit,
    onTapPickupTime: (index: Int, currentSeconds: Int) -> Unit,
    onSplitMergeWalk: (index: Int) -> Unit,
    onToggleLeg: (index: Int) -> Unit,
    onNotToday: (dogId: String) -> Unit,
) {
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onMove(from.index, to.index)
    }
    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items, key = { it.id }) { item ->
            val index = items.indexOfFirst { it.id == item.id }
            val event = item.event
            val draggable = event !is RouteEvent.HomeStart && event !is RouteEvent.HomeEnd
            val prev = items.getOrNull(index - 1)?.event
            val next = items.getOrNull(index + 1)?.event
            val inSharedRun = event is RouteEvent.Walk &&
                (prev is RouteEvent.Walk || next is RouteEvent.Walk)
            // Whether this walk has an adjacent walk of the SAME dog: then the
            // secondary action merges (else it splits).
            val mergeable = event is RouteEvent.Walk && run {
                val id = event.dogs.singleOrNull()?.id
                (prev as? RouteEvent.Walk)?.dogs?.singleOrNull()?.id == id ||
                    (next as? RouteEvent.Walk)?.dogs?.singleOrNull()?.id == id
            }
            ReorderableItem(reorderState, key = item.id) { isDragging ->
                val handle = if (draggable) {
                    Modifier.longPressDraggableHandle(
                        onDragStarted = { onReorderStart() },
                        onDragStopped = { onReorderStop() },
                    )
                } else {
                    Modifier
                }
                EditChip(
                    event = event,
                    isDragging = isDragging,
                    draggable = draggable,
                    inSharedRun = inSharedRun,
                    mergeable = mergeable,
                    handleModifier = handle,
                    removing = event.removesWith(removingDogIds),
                    onToggleLeg = if (index > 0 && event.incomingTravelSeconds > 0) {
                        { onToggleLeg(index) }
                    } else null,
                    onBodyTap = when (event) {
                        is RouteEvent.Walk -> { { onTapWalk(index, event.durationSeconds / 60) } }
                        is RouteEvent.Pickup -> {
                            { onTapPickupTime(index, event.rule.earliestStart?.toSecondOfDay() ?: event.timeSeconds) }
                        }
                        else -> null
                    },
                    onSecondary = when (event) {
                        is RouteEvent.Walk -> { { onSplitMergeWalk(index) } }
                        is RouteEvent.Pickup -> { { onNotToday(event.dog.id) } }
                        else -> null
                    },
                )
            }
        }
    }
}

private fun RouteEvent.removesWith(removingDogIds: Set<String>): Boolean = when (this) {
    is RouteEvent.Pickup -> dog.id in removingDogIds
    is RouteEvent.Dropoff -> dog.id in removingDogIds
    is RouteEvent.Walk -> dogs.any { it.id in removingDogIds }
    else -> false
}

@Composable
private fun EditChip(
    event: RouteEvent,
    isDragging: Boolean,
    draggable: Boolean,
    inSharedRun: Boolean,
    mergeable: Boolean,
    handleModifier: Modifier,
    removing: Boolean,
    onToggleLeg: (() -> Unit)?,
    onBodyTap: (() -> Unit)?,
    onSecondary: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (onToggleLeg != null) {
            LegToggleRow(event = event, onToggle = onToggleLeg)
        }
        val elevation by animateDpAsState(if (isDragging) 6.dp else 1.dp, label = "chipElevation")
        val container = if (inSharedRun) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (removing) 0.4f else 1f),
            colors = CardDefaults.elevatedCardColors(containerColor = container),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = elevation),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onBodyTap != null) Modifier.clickable(onClick = onBodyTap) else Modifier)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (draggable) {
                    // Dedicated drag handle (long-press here to reorder) — kept
                    // separate from the tappable body so the two gestures don't
                    // fight.
                    Icon(
                        imageVector = Icons.Default.DragIndicator,
                        contentDescription = "Hold to drag",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = handleModifier.size(28.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                val pastEnd = event.timeSeconds > DAY_END_SECONDS
                Text(
                    text = formatTime(event.timeSeconds),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (pastEnd) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(52.dp),
                )
                Icon(
                    imageVector = event.icon(),
                    contentDescription = null,
                    tint = event.tint(),
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        text = event.title(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (removing) TextDecoration.LineThrough else null,
                    )
                    event.subtitle()?.let { sub ->
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (removing) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else if (onSecondary != null) {
                    val (icon, desc) = when (event) {
                        is RouteEvent.Walk ->
                            (if (mergeable) Icons.Default.CallMerge else Icons.Default.CallSplit) to
                                (if (mergeable) "Merge with the next walk" else "Split into two walks")
                        else -> Icons.Default.Close to "Not walked today"
                    }
                    IconButton(onClick = onSecondary) {
                        Icon(icon, contentDescription = desc, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LegToggleRow(event: RouteEvent, onToggle: () -> Unit) {
    val forced = event.legMode != app.dogrouter.domain.dayplan.LegMode.AUTO
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 36.dp, top = 2.dp, bottom = 2.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (event.arrivedByFoot) Icons.AutoMirrored.Filled.DirectionsWalk else Icons.Default.DirectionsBike,
            contentDescription = null,
            tint = if (forced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${formatDuration(event.incomingTravelSeconds)} ${if (event.arrivedByFoot) "on foot" else "cycling"}" +
                if (forced) " (pinned — tap to switch)" else " (tap to switch)",
            style = MaterialTheme.typography.bodySmall,
            color = if (forced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Dialogs.
// ---------------------------------------------------------------------------

/** Edit a pickup's earliest start time on a clock dial. */
@Composable
private fun StopTimeDialog(
    initialSeconds: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    TimePickerDialog(
        initialSeconds = initialSeconds,
        title = "Earliest start time",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** A Material3 clock-dial time picker in a dialog; returns seconds-of-day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialSeconds: Int,
    title: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = (initialSeconds / 3600).coerceIn(0, 23),
        initialMinute = ((initialSeconds % 3600) / 60).coerceIn(0, 59),
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 3600 + state.minute * 60) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick a dog and a duration for a hand-added walk. */
@Composable
private fun AddWalkDialog(
    dogs: List<Dog>,
    onConfirm: (dog: Dog, minutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedId by remember { mutableStateOf(dogs.firstOrNull()?.id) }
    var minutesText by remember { mutableStateOf("60") }
    val minutes = minutesText.toIntOrNull()?.takeIf { it > 0 }
    val selectedDog = dogs.firstOrNull { it.id == selectedId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a walk") },
        text = {
            Column {
                if (dogs.isEmpty()) {
                    Text("No dogs with an address available.")
                } else {
                    dogs.forEach { dog ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedId = dog.id }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selectedId == dog.id, onClick = { selectedId = dog.id })
                            Spacer(Modifier.width(4.dp))
                            Text(dog.name)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { minutesText = it },
                        label = { Text("Minutes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = minutes == null,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedDog?.let { d -> minutes?.let { m -> onConfirm(d, m) } } },
                enabled = selectedDog != null && minutes != null,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Force a dog-free appointment: label, time window and a BAN address. */
@Composable
private fun AddAppointmentDialog(
    addressText: String,
    addressValidated: Boolean,
    suggestions: List<AddressSuggestion>,
    onAddressChange: (String) -> Unit,
    onAddressPick: (AddressSuggestion) -> Unit,
    onConfirm: (label: String, startSeconds: Int, endSeconds: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var startSeconds by remember { mutableStateOf(14 * 3600) }
    var endSeconds by remember { mutableStateOf(15 * 3600) }
    // Which time is being picked on the clock dial (null = none open).
    var picking by remember { mutableStateOf<String?>(null) }
    val valid = label.isNotBlank() && addressValidated && endSeconds > startSeconds
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add appointment") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (doctor, shop, lunch, …)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedButton(onClick = { picking = "start" }, modifier = Modifier.weight(1f)) {
                        Text("From ${formatTime(startSeconds)}")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { picking = "end" }, modifier = Modifier.weight(1f)) {
                        Text("To ${formatTime(endSeconds)}")
                    }
                }
                if (!valid && endSeconds <= startSeconds) {
                    Text(
                        text = "End must be after start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                AddressAutocompleteField(
                    value = addressText,
                    isValidated = addressValidated,
                    suggestions = suggestions,
                    label = "Address",
                    onValueChange = onAddressChange,
                    onPick = onAddressPick,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onConfirm(label.trim(), startSeconds, endSeconds) },
                enabled = valid,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    picking?.let { which ->
        TimePickerDialog(
            initialSeconds = if (which == "start") startSeconds else endSeconds,
            title = if (which == "start") "From" else "To",
            onConfirm = { secs ->
                if (which == "start") startSeconds = secs else endSeconds = secs
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun WalkDurationDialog(
    initialMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialMinutes.toString()) }
    val parsed = text.toIntOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Walk duration") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Minutes") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = parsed == null,
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PlanWarningsPanel(warnings: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Plan warnings (kept anyway)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        warnings.forEach { w ->
            Text(
                text = "• $w",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// View mode: read-only timeline.
// ---------------------------------------------------------------------------

@Composable
private fun DayRouteContent(
    state: PlanState,
    stopBufferSeconds: Int,
    onOpenLegMap: (from: GeoPoint, to: GeoPoint) -> Unit,
) {
    when (state) {
        is PlanState.Loading -> LoadingBox(state.fraction, state.phase)
        is PlanState.Ready -> {
            val route = state.route
            if (route.events.isEmpty() && route.conflicts.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item { Summary(route) }
                    if (route.conflicts.isNotEmpty()) {
                        item { ConflictPanel(route.conflicts) }
                    }
                    if (route.breakUnavailable) {
                        item { BreakUnavailablePanel() }
                    }
                    val timeline = buildTimelineRows(route.events, stopBufferSeconds)
                    items(timeline) { row ->
                        TimelineRowView(row, onOpenLegMap)
                    }
                }
            }
        }
    }
}

@Composable
private fun EditedPlanBanner(onRevert: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Hand-edited plan",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRevert) { Text("Revert to auto plan") }
    }
}

@Composable
private fun BreakToggleRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(4.dp))
        Text("Include a lunch break", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LoadingBox(fraction: Float, phase: PlanPhase) {
    val label = when (phase) {
        PlanPhase.ROUTING -> "Building routes…"
        PlanPhase.OPTIMISING -> "Optimising plan…"
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 48.dp),
        ) {
            Text(
                text = "$label  ${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "No walks scheduled for this day.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Summary(route: DayRoute) {
    val totalElapsed = if (route.events.size >= 2) {
        route.events.last().timeSeconds - route.events.first().timeSeconds
    } else 0
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "${formatDuration(totalElapsed)} on the clock",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${formatDuration(route.totalCyclingSeconds)} cycling · " +
                    "${formatDuration(route.totalWalkingSeconds)} walking",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConflictPanel(conflicts: List<PlanConflict>) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${conflicts.size} unscheduled",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            conflicts.forEach { conflict ->
                Text(
                    text = "• ${conflict.dog.name}: ${conflict.reason}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun BreakUnavailablePanel() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "No room for a lunch break today.",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** UI-only view-model rows: an event, a travel leg, or a wait row. */
private sealed interface TimelineRow {
    data class Event(val event: RouteEvent) : TimelineRow
    data class Leg(
        val seconds: Int,
        val from: GeoPoint,
        val to: GeoPoint,
        val byFoot: Boolean,
    ) : TimelineRow

    /** Idle time spent waiting at a stop for its window to open. */
    data class Wait(val seconds: Int) : TimelineRow
}

private fun buildTimelineRows(events: List<RouteEvent>, stopBufferSeconds: Int): List<TimelineRow> {
    if (events.isEmpty()) return emptyList()
    val rows = mutableListOf<TimelineRow>(TimelineRow.Event(events[0]))
    for (i in 1 until events.size) {
        val prev = events[i - 1]
        val current = events[i]
        if (current.incomingTravelSeconds > 0) {
            rows.add(
                TimelineRow.Leg(
                    seconds = current.incomingTravelSeconds,
                    from = prev.location,
                    to = current.location,
                    byFoot = current.arrivedByFoot,
                ),
            )
        }
        val arrival = prev.timeSeconds + prev.durationAtSeconds(stopBufferSeconds) + current.incomingTravelSeconds
        val wait = current.timeSeconds - arrival
        if (wait >= 60) rows.add(TimelineRow.Wait(wait))
        rows.add(TimelineRow.Event(current))
    }
    return rows
}

@Composable
private fun TimelineRowView(
    row: TimelineRow,
    onOpenLegMap: (from: GeoPoint, to: GeoPoint) -> Unit,
) {
    when (row) {
        is TimelineRow.Leg -> LegRow(row, onOpenLegMap)
        is TimelineRow.Event -> EventRow(row.event)
        is TimelineRow.Wait -> WaitRow(row)
    }
}

@Composable
private fun WaitRow(wait: TimelineRow.Wait) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, top = 2.dp, bottom = 2.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${formatDuration(wait.seconds)} waiting",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegRow(
    leg: TimelineRow.Leg,
    onOpenLegMap: (from: GeoPoint, to: GeoPoint) -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable { onOpenLegMap(leg.from, leg.to) }
            .fillMaxWidth()
            .padding(start = 28.dp, top = 2.dp, bottom = 2.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "↓ ${formatDuration(leg.seconds)} ${if (leg.byFoot) "on foot" else "cycling"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Outlined.Map,
            contentDescription = "Show route on map",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun EventRow(event: RouteEvent) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = formatTime(event.timeSeconds),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(56.dp),
        )
        Icon(
            imageVector = event.icon(),
            contentDescription = null,
            tint = event.tint(),
            modifier = Modifier.padding(top = 2.dp).size(20.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(
                text = event.title(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            event.subtitle()?.let { sub ->
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RouteEvent.tint() = when (this) {
    is RouteEvent.HomeStart, is RouteEvent.HomeEnd -> MaterialTheme.colorScheme.primary
    is RouteEvent.Pickup -> MaterialTheme.colorScheme.secondary
    is RouteEvent.Dropoff -> MaterialTheme.colorScheme.tertiary
    is RouteEvent.Walk -> MaterialTheme.colorScheme.primary
    is RouteEvent.Break -> MaterialTheme.colorScheme.secondary
    is RouteEvent.Appointment -> MaterialTheme.colorScheme.tertiary
    is RouteEvent.FetchBike -> MaterialTheme.colorScheme.primary
}

private fun RouteEvent.icon(): ImageVector = when (this) {
    is RouteEvent.HomeStart, is RouteEvent.HomeEnd -> Icons.Default.Home
    is RouteEvent.Pickup -> Icons.Default.ArrowDownward
    is RouteEvent.Dropoff -> Icons.Default.ArrowUpward
    is RouteEvent.Walk -> Icons.AutoMirrored.Filled.DirectionsWalk
    is RouteEvent.Break -> Icons.Default.Place
    is RouteEvent.Appointment -> Icons.Default.Today
    is RouteEvent.FetchBike -> Icons.AutoMirrored.Filled.DirectionsWalk
}

private fun RouteEvent.title(): String = when (this) {
    is RouteEvent.HomeStart -> "Start at home"
    is RouteEvent.HomeEnd -> "Return home"
    is RouteEvent.Pickup -> "Pickup ${dog.name}"
    is RouteEvent.Dropoff -> "Dropoff ${dog.name}"
    is RouteEvent.Walk -> "Walk ${dogs.joinToString { it.name }} · ${formatDuration(durationSeconds)}"
    is RouteEvent.Break ->
        (if (atHome) "Lunch at home" else "Lunch break") + " · ${formatDuration(durationSeconds)}"
    is RouteEvent.Appointment -> "$label · ${formatDuration(durationSeconds)}"
    is RouteEvent.FetchBike -> "Walk back to the bike"
}

private fun RouteEvent.subtitle(): String? = when (this) {
    is RouteEvent.Pickup -> dog.address.ifBlank { null }
    is RouteEvent.Dropoff -> dog.address.ifBlank { null }
    else -> null
}

@Composable
private fun DateSelector(
    date: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPickDate: () -> Unit,
) {
    val locale = Locale.getDefault()
    val formatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
        }
        TextButton(onClick = onPickDate, modifier = Modifier.weight(1f)) {
            Text(text = date.format(formatter), style = MaterialTheme.typography.titleMedium)
        }
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis ?: return@TextButton
                val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                onPick(date)
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DatePicker(state = state)
    }
}

private fun formatTime(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return "%02d:%02d".format(h, m)
}

private fun formatDuration(totalSeconds: Int): String {
    val minutes = (totalSeconds + 30) / 60
    return when {
        minutes >= 60 -> "%d h %02d min".format(minutes / 60, minutes % 60)
        minutes >= 1 -> "$minutes min"
        totalSeconds > 0 -> "<1 min"
        else -> "0 min"
    }
}
