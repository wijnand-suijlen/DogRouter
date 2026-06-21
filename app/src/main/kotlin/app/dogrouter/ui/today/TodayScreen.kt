package app.dogrouter.ui.today

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
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
import app.dogrouter.domain.dayplan.isDogGrouped
import app.dogrouter.domain.dayplan.reorderInfo
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.ui.common.AddressAutocompleteField
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
    var showDatePicker by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    // The walk whose duration is being edited: (event index, current minutes).
    var editingWalk by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // The pickup whose start time is being edited: (event index, current seconds).
    var editingStop by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showAddChooser by remember { mutableStateOf(false) }
    var showAddWalk by remember { mutableStateOf(false) }
    var showAddAppointment by remember { mutableStateOf(false) }
    // The dog being regrouped into another walk ("walk with…").
    var groupingDog by remember { mutableStateOf<String?>(null) }
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
                    if (readyRoute?.events?.isNotEmpty() == true) {
                        IconButton(onClick = { editMode = !editMode }) {
                            Icon(
                                if (editMode) Icons.Default.Done else Icons.Default.Edit,
                                contentDescription = if (editMode) "Done editing" else "Edit plan",
                            )
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
            BreakToggleRow(
                checked = breakRequested,
                onCheckedChange = viewModel::setBreakRequested,
            )
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
            DayRouteContent(
                planState, stopBufferSeconds, onOpenLegMap,
                editMode = editMode,
                onMarkDogNotToday = viewModel::markDogNotToday,
                onEditWalkDuration = { index, minutes -> editingWalk = index to minutes },
                onEditStopTime = { index, seconds -> editingStop = index to seconds },
                onMoveWalk = viewModel::moveWalk,
                onWalkAlone = viewModel::splitDogAlone,
                onWalkWith = { dogId -> groupingDog = dogId },
                removingDogIds = removingDogIds,
            )
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
            onConfirm = { dogId, minutes ->
                viewModel.addWalk(dogId, minutes)
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

    groupingDog?.let { dogId ->
        val candidates = readyRoute?.events.orEmpty().withIndex()
            .filter { (_, e) -> e is RouteEvent.Walk && e.dogs.none { it.id == dogId } }
            .map { (i, e) -> i to (e as RouteEvent.Walk) }
        WalkWithDialog(
            candidates = candidates,
            onPick = { walkIndex ->
                viewModel.groupDogWith(dogId, walkIndex)
                groupingDog = null
            },
            onDismiss = { groupingDog = null },
        )
    }
}

/** Pick which walk a dog should join ("walk with another dog"). */
@Composable
private fun WalkWithDialog(
    candidates: List<Pair<Int, RouteEvent.Walk>>,
    onPick: (walkIndex: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Walk together with") },
        text = {
            Column {
                if (candidates.isEmpty()) {
                    Text("No other walk to join.")
                } else {
                    candidates.forEach { (index, walk) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(index) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text("${formatTime(walk.timeSeconds)}  ${walk.dogs.joinToString { it.name }}")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Edit a pickup's earliest start time as HH:MM. */
@Composable
private fun StopTimeDialog(
    initialSeconds: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("%02d:%02d".format(initialSeconds / 3600, (initialSeconds % 3600) / 60)) }
    val parsed = parseHhMm(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Earliest start time") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("HH:MM") },
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

/** Parse "HH:MM" to seconds-of-day, or null if invalid. */
private fun parseHhMm(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 3600 + m * 60
}

/** Pick a dog and a duration for a hand-added walk. */
@Composable
private fun AddWalkDialog(
    dogs: List<Dog>,
    onConfirm: (dogId: String, minutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedId by remember { mutableStateOf(dogs.firstOrNull()?.id) }
    var minutesText by remember { mutableStateOf("60") }
    val minutes = minutesText.toIntOrNull()?.takeIf { it > 0 }
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
                onClick = { selectedId?.let { id -> minutes?.let { m -> onConfirm(id, m) } } },
                enabled = selectedId != null && minutes != null,
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
    var startText by remember { mutableStateOf("14:00") }
    var endText by remember { mutableStateOf("15:00") }
    val start = parseHhMm(startText)
    val end = parseHhMm(endText)
    val valid = label.isNotBlank() && addressValidated && start != null && end != null && end > start
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
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("From HH:MM") },
                        singleLine = true,
                        isError = start == null,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = { Text("To HH:MM") },
                        singleLine = true,
                        isError = end == null,
                        modifier = Modifier.weight(1f),
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
                onClick = { if (valid) onConfirm(label.trim(), start!!, end!!) },
                enabled = valid,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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

@Composable
private fun DayRouteContent(
    state: PlanState,
    stopBufferSeconds: Int,
    onOpenLegMap: (from: GeoPoint, to: GeoPoint) -> Unit,
    editMode: Boolean,
    onMarkDogNotToday: (String) -> Unit,
    onEditWalkDuration: (eventIndex: Int, currentMinutes: Int) -> Unit,
    onEditStopTime: (eventIndex: Int, currentSeconds: Int) -> Unit,
    onMoveWalk: (eventIndex: Int, earlier: Boolean) -> Unit,
    onWalkAlone: (dogId: String) -> Unit,
    onWalkWith: (dogId: String) -> Unit,
    removingDogIds: Set<String>,
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
                        TimelineRowView(
                            row, onOpenLegMap, editMode,
                            onMarkDogNotToday, onEditWalkDuration, onEditStopTime, onMoveWalk,
                            onWalkAlone, onWalkWith, removingDogIds,
                        )
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
    data class Event(
        val event: RouteEvent,
        val eventIndex: Int,
        val canMoveEarlier: Boolean = false,
        val canMoveLater: Boolean = false,
        val dogGrouped: Boolean = false,
    ) : TimelineRow
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
    val rows = mutableListOf<TimelineRow>(TimelineRow.Event(events[0], 0))
    for (i in 1 until events.size) {
        val prev = events[i - 1]
        val current = events[i]
        // The retimer already classified each leg (bike vs on foot) and its
        // travel time; just use it.
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
        // Any time beyond the previous stop's service and this leg's travel is
        // spent standing still waiting for a window — show it as its own row so
        // the timestamps add up and the leg is not read as slow travel.
        val arrival = prev.timeSeconds + prev.durationAtSeconds(stopBufferSeconds) + current.incomingTravelSeconds
        val wait = current.timeSeconds - arrival
        if (wait >= 60) rows.add(TimelineRow.Wait(wait))
        val reorder = if (current is RouteEvent.Walk) reorderInfo(events, i) else null
        rows.add(
            TimelineRow.Event(
                current, i,
                canMoveEarlier = reorder?.canMoveEarlier == true,
                canMoveLater = reorder?.canMoveLater == true,
                dogGrouped = current is RouteEvent.Pickup && isDogGrouped(events, current.dog.id),
            ),
        )
    }
    return rows
}

@Composable
private fun TimelineRowView(
    row: TimelineRow,
    onOpenLegMap: (from: GeoPoint, to: GeoPoint) -> Unit,
    editMode: Boolean,
    onMarkDogNotToday: (String) -> Unit,
    onEditWalkDuration: (eventIndex: Int, currentMinutes: Int) -> Unit,
    onEditStopTime: (eventIndex: Int, currentSeconds: Int) -> Unit,
    onMoveWalk: (eventIndex: Int, earlier: Boolean) -> Unit,
    onWalkAlone: (dogId: String) -> Unit,
    onWalkWith: (dogId: String) -> Unit,
    removingDogIds: Set<String>,
) {
    when (row) {
        is TimelineRow.Leg -> LegRow(row, onOpenLegMap)
        is TimelineRow.Event -> EventRow(
            row.event, editMode, onMarkDogNotToday, removingDogIds,
            onEditDuration = { minutes -> onEditWalkDuration(row.eventIndex, minutes) },
            onEditTime = { seconds -> onEditStopTime(row.eventIndex, seconds) },
            canMoveEarlier = row.canMoveEarlier,
            canMoveLater = row.canMoveLater,
            onMoveEarlier = { onMoveWalk(row.eventIndex, true) },
            onMoveLater = { onMoveWalk(row.eventIndex, false) },
            dogGrouped = row.dogGrouped,
            onWalkAlone = onWalkAlone,
            onWalkWith = onWalkWith,
        )
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
private fun EventRow(
    event: RouteEvent,
    editMode: Boolean = false,
    onMarkDogNotToday: (String) -> Unit = {},
    removingDogIds: Set<String> = emptySet(),
    onEditDuration: (minutes: Int) -> Unit = {},
    onEditTime: (seconds: Int) -> Unit = {},
    canMoveEarlier: Boolean = false,
    canMoveLater: Boolean = false,
    onMoveEarlier: () -> Unit = {},
    onMoveLater: () -> Unit = {},
    dogGrouped: Boolean = false,
    onWalkAlone: (dogId: String) -> Unit = {},
    onWalkWith: (dogId: String) -> Unit = {},
) {
    val icon = event.icon()
    val title = event.title()
    val subtitle = event.subtitle()
    // Whether this row's dog(s) are being removed: strike it through and dim it
    // right away, so the tap is acknowledged before the re-time lands.
    val removing = when (event) {
        is RouteEvent.Pickup -> event.dog.id in removingDogIds
        is RouteEvent.Dropoff -> event.dog.id in removingDogIds
        is RouteEvent.Walk -> event.dogs.any { it.id in removingDogIds }
        else -> false
    }
    // In edit mode a walk is tappable to change its duration; a pickup's
    // actions live in a "more" menu (set time / regroup / not today).
    val editableWalk = editMode && event is RouteEvent.Walk
    val rowClick: (() -> Unit)? =
        if (editableWalk) { { onEditDuration((event as RouteEvent.Walk).durationSeconds / 60) } } else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (removing) 0.4f else 1f)
            .then(if (rowClick != null) Modifier.clickable(onClick = rowClick) else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = formatTime(event.timeSeconds),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(56.dp),
        )
        Icon(
            imageVector = icon,
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
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textDecoration = if (removing) TextDecoration.LineThrough else null,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // In edit mode a pickup's actions live in a "more" menu; while the
        // re-time runs after "not today" it shows a spinner instead.
        if (event is RouteEvent.Pickup && removing) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.padding(12.dp).size(20.dp),
            )
        } else if (event is RouteEvent.Pickup && editMode) {
            PickupActionsMenu(
                dog = event.dog,
                grouped = dogGrouped,
                onSetTime = { onEditTime(event.rule.earliestStart?.toSecondOfDay() ?: event.timeSeconds) },
                onWalkAlone = { onWalkAlone(event.dog.id) },
                onWalkWith = { onWalkWith(event.dog.id) },
                onNotToday = { onMarkDogNotToday(event.dog.id) },
            )
        }
        // A walk shows an edit affordance in edit mode (the whole row is also
        // tappable) so it is clear the duration can be changed, plus up/down
        // arrows to move a standalone walk earlier/later in the day.
        if (editableWalk) {
            if (canMoveEarlier) {
                IconButton(onClick = onMoveEarlier) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Move walk earlier",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (canMoveLater) {
                IconButton(onClick = onMoveLater) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Move walk later",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            IconButton(onClick = { onEditDuration((event as RouteEvent.Walk).durationSeconds / 60) }) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit walk duration",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun PickupActionsMenu(
    dog: Dog,
    grouped: Boolean,
    onSetTime: () -> Unit,
    onWalkAlone: () -> Unit,
    onWalkWith: () -> Unit,
    onNotToday: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Edit ${dog.name}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Set start time") },
                onClick = { expanded = false; onSetTime() },
            )
            if (grouped) {
                DropdownMenuItem(
                    text = { Text("Walk alone") },
                    onClick = { expanded = false; onWalkAlone() },
                )
            }
            DropdownMenuItem(
                text = { Text("Walk with another dog…") },
                onClick = { expanded = false; onWalkWith() },
            )
            DropdownMenuItem(
                text = { Text("Not walked today") },
                onClick = { expanded = false; onNotToday() },
            )
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
