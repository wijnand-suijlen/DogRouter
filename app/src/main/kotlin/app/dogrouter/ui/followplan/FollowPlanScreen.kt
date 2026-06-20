package app.dogrouter.ui.followplan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dogrouter.domain.dayplan.DayRoute
import app.dogrouter.domain.dayplan.RouteEvent
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.ui.common.CyclingLegMap
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Full-screen on-the-bike execution of one day's plan: the current stop
 * dominates, the next couple are listed small below, and a single large
 * button advances to the next stop. Launched from Today via "Start trip".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowPlanScreen(
    date: LocalDate,
    onExit: () -> Unit,
    onOpenLegMap: (from: GeoPoint, to: GeoPoint) -> Unit,
    viewModel: FollowPlanViewModel = koinViewModel { parametersOf(date) },
) {
    BackHandler(onBack = onExit)
    val dayRoute by viewModel.dayRoute.collectAsStateWithLifecycle()
    val currentStep by viewModel.currentStep.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Follow plan")
                        Text(
                            text = date.format(dateFormatter()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Default.Close, contentDescription = "Exit to Today")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val route = dayRoute
            when {
                route == null -> LoadingState()
                route.events.isEmpty() -> EmptyState()
                else -> RunningState(
                    route = route,
                    currentStep = currentStep,
                    onOpenLegMap = onOpenLegMap,
                    onDone = viewModel::advance,
                    onBack = viewModel::goBack,
                    onExit = onExit,
                )
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No walks scheduled for this day.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RunningState(
    route: DayRoute,
    currentStep: Int,
    onOpenLegMap: (from: GeoPoint, to: GeoPoint) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    onExit: () -> Unit,
) {
    val events = route.events
    val done = currentStep >= events.size

    Column(modifier = Modifier.fillMaxSize()) {
        LinearProgressIndicator(
            progress = { currentStep.toFloat() / events.size },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = if (done) "All stops done" else "Stop ${currentStep + 1} of ${events.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (done) {
            TripComplete(onExit = onExit, modifier = Modifier.weight(1f))
        } else {
            CurrentStop(
                event = events[currentStep],
                arrivedFrom = events.getOrNull(currentStep - 1),
                onOpenLegMap = onOpenLegMap,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            NextStops(events = events, fromIndex = currentStep + 1)
            ActionBar(isLast = currentStep == events.size - 1, onDone = onDone, onBack = onBack)
        }
    }
}

@Composable
private fun CurrentStop(
    event: RouteEvent,
    arrivedFrom: RouteEvent?,
    onOpenLegMap: (from: GeoPoint, to: GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val step = event.toStepView()
    // Show the leg the walker travels to reach this stop, unless it happens
    // in place (day start, walk, or a stop sharing the previous address).
    val legFrom = arrivedFrom?.location?.takeIf {
        it != event.location && event.incomingTravelSeconds > 0
    }
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(
                text = step.timeLabel,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = step.icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (step.address != null) {
                Spacer(Modifier.height(12.dp))
                Text(text = step.address, style = MaterialTheme.typography.titleLarge)
            }
            if (step.detail != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = step.detail,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (legFrom != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "${if (event.arrivedByFoot) "On foot" else "Cycling"} route here · tap to open map",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                CyclingLegMap(
                    from = legFrom,
                    to = event.location,
                    onOpenFullscreen = onOpenLegMap,
                )
            }
            if (step.quirks != null) {
                Spacer(Modifier.height(16.dp))
                QuirksNote(step.quirks)
            }
            if (step.phone != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Owner: ${step.phone}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QuirksNote(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun NextStops(events: List<RouteEvent>, fromIndex: Int) {
    val upcoming = events.drop(fromIndex).take(2)
    if (upcoming.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Next",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        upcoming.forEach { event ->
            val step = event.toStepView()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = step.timeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(56.dp),
                )
                Icon(
                    imageVector = step.icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(text = step.title, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ActionBar(isLast: Boolean, onDone: () -> Unit, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.height(64.dp),
        ) {
            Text("Back")
        }
        Button(
            onClick = onDone,
            modifier = Modifier
                .weight(1f)
                .height(64.dp),
        ) {
            Text(
                text = if (isLast) "Finish trip" else "Done — next stop",
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun TripComplete(onExit: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Trip complete",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onExit) {
            Text("Back to Today", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Flattened presentation of a [RouteEvent] for the execution screen. */
private data class StepView(
    val icon: ImageVector,
    val title: String,
    val timeLabel: String,
    val address: String?,
    val detail: String?,
    val quirks: String?,
    val phone: String?,
)

private fun RouteEvent.toStepView(): StepView = when (this) {
    is RouteEvent.HomeStart -> StepView(
        icon = Icons.Default.Home,
        title = "Start at home",
        timeLabel = formatClock(timeSeconds),
        address = null, detail = null, quirks = null, phone = null,
    )
    is RouteEvent.HomeEnd -> StepView(
        icon = Icons.Default.Home,
        title = "Return home",
        timeLabel = formatClock(timeSeconds),
        address = null, detail = null, quirks = null, phone = null,
    )
    is RouteEvent.Pickup -> StepView(
        icon = Icons.Default.ArrowDownward,
        title = "Pickup ${dog.name}",
        timeLabel = formatClock(timeSeconds),
        address = dog.address.ifBlank { null },
        detail = null,
        quirks = dog.stopNotes?.ifBlank { null },
        phone = dog.ownerPhone?.ifBlank { null },
    )
    is RouteEvent.Dropoff -> StepView(
        icon = Icons.Default.ArrowUpward,
        title = "Dropoff ${dog.name}",
        timeLabel = formatClock(timeSeconds),
        address = dog.address.ifBlank { null },
        detail = null,
        quirks = dog.stopNotes?.ifBlank { null },
        phone = dog.ownerPhone?.ifBlank { null },
    )
    is RouteEvent.Walk -> StepView(
        icon = Icons.AutoMirrored.Filled.DirectionsWalk,
        title = "Walk ${dogs.joinToString { it.name }}",
        timeLabel = formatClock(timeSeconds),
        address = null,
        detail = "${formatMinutes(durationSeconds)} walk",
        quirks = null,
        phone = null,
    )
    is RouteEvent.FetchBike -> StepView(
        icon = Icons.AutoMirrored.Filled.DirectionsWalk,
        title = "Walk back to your bike",
        timeLabel = formatClock(timeSeconds),
        address = null,
        detail = "${formatMinutes(incomingTravelSeconds)} on foot to the parked bike",
        quirks = null,
        phone = null,
    )
}

private fun dateFormatter(): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault())

private fun formatClock(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return "%02d:%02d".format(h, m)
}

private fun formatMinutes(totalSeconds: Int): String {
    val minutes = (totalSeconds + 30) / 60
    return when {
        minutes >= 60 -> "%d h %02d min".format(minutes / 60, minutes % 60)
        minutes >= 1 -> "$minutes min"
        else -> "<1 min"
    }
}
