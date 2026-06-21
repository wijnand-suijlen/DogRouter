package app.dogrouter.ui.billing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dogrouter.domain.dayplan.RouteEvent
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommittedDayDetailScreen(
    date: LocalDate,
    onBack: () -> Unit,
    viewModel: CommittedDayDetailViewModel = koinViewModel { parametersOf(date) },
) {
    val route by viewModel.route.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        date.format(
                            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()),
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        val events = route?.events
        if (events.isNullOrEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text("No saved plan for this committed day.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                itemsIndexed(events) { _, event ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = formatTime(event.timeSeconds),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(56.dp),
                        )
                        Text(text = eventTitle(event), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun formatTime(seconds: Int): String = "%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60)

private fun formatMinutes(seconds: Int): String = "${(seconds + 30) / 60} min"

private fun eventTitle(event: RouteEvent): String = when (event) {
    is RouteEvent.HomeStart -> "Start at home"
    is RouteEvent.HomeEnd -> "Return home"
    is RouteEvent.Pickup -> "Pickup ${event.dog.name}"
    is RouteEvent.Dropoff -> "Dropoff ${event.dog.name}"
    is RouteEvent.Walk -> "Walk ${event.dogs.joinToString { it.name }} · ${formatMinutes(event.durationSeconds)}"
    is RouteEvent.Break -> (if (event.atHome) "Lunch at home" else "Lunch break") + " · ${formatMinutes(event.durationSeconds)}"
    is RouteEvent.Appointment -> "${event.label} · ${formatMinutes(event.durationSeconds)}"
    is RouteEvent.FetchBike -> "Walk back to the bike"
}
