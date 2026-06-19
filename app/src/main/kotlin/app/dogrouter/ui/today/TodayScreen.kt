package app.dogrouter.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dogrouter.domain.planner.PlannedWalk
import app.dogrouter.domain.planner.Trip
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    viewModel: TodayViewModel = koinViewModel(),
) {
    val dayPlan by viewModel.dayPlan.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Today") },
                actions = {
                    IconButton(onClick = viewModel::goToToday) {
                        Icon(Icons.Default.Today, contentDescription = "Jump to today")
                    }
                },
            )
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
            HorizontalDivider()
            if (dayPlan.trips.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(dayPlan.trips) { index, trip ->
                        TripCard(
                            tripIndex = index + 1,
                            trip = trip,
                            capacityKg = settings.bikeCapacityKg,
                        )
                    }
                }
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
        TextButton(
            onClick = onPickDate,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = date.format(formatter),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No walks scheduled for this day.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripCard(
    tripIndex: Int,
    trip: Trip,
    capacityKg: Float,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Trip $tripIndex",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${trip.walks.size} stop${if (trip.walks.size == 1) "" else "s"} · ${
                        trip.totalWeightKg.formatWeight()
                    } / ${capacityKg.formatWeight()} kg",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (trip.exceedsCapacity) {
                AssistChip(
                    onClick = {},
                    label = { Text("Exceeds capacity") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = MaterialTheme.colorScheme.error,
                        leadingIconContentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            trip.walks.sortedBy { it.dog.name.lowercase() }.forEachIndexed { index, walk ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                StopRow(walk = walk)
            }
        }
    }
}

@Composable
private fun StopRow(walk: PlannedWalk) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = walk.dog.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${walk.dog.weightKg.formatWeight()} kg",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = walk.dog.address.ifBlank { "(no address)" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = buildString {
                append(formatWindow(walk.rule.earliestStart, walk.rule.latestEnd))
                append(" · ")
                append("${walk.rule.durationMinutes} min walk")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

private fun Float.formatWeight(): String {
    val rounded = toInt().toFloat()
    return if (rounded == this) toInt().toString() else String.format(Locale.ROOT, "%.1f", this)
}

private fun formatWindow(earliest: LocalTime?, latest: LocalTime?): String {
    val from = earliest?.format(timeFormatter) ?: "any"
    val until = latest?.format(timeFormatter) ?: "any"
    return "$from – $until"
}

