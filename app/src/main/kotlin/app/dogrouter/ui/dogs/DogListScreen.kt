package app.dogrouter.ui.dogs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogStatus
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DogListScreen(
    onAddClick: () -> Unit,
    onDogClick: (dogId: String) -> Unit,
    onManageOwners: () -> Unit,
    viewModel: DogListViewModel = koinViewModel(),
) {
    val dogs by viewModel.dogs.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dogs") },
                actions = {
                    IconButton(onClick = onManageOwners) {
                        Icon(Icons.Default.People, contentDescription = "Owners")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = "Add dog")
            }
        },
    ) { innerPadding ->
        if (dogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text("No dogs yet. Tap + to add one.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(dogs, key = { it.id }) { dog ->
                    DogRow(
                        dog = dog,
                        onClick = { onDogClick(dog.id) },
                        onSetStatus = { viewModel.setStatus(dog, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DogRow(
    dog: Dog,
    onClick: () -> Unit,
    onSetStatus: (DogStatus) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // OFF dogs are dimmed so the list reads at a glance.
            Column(modifier = Modifier
                .weight(1f)
                .alpha(if (dog.status == DogStatus.OFF) 0.45f else 1f),
            ) {
                Text(text = dog.name, style = MaterialTheme.typography.titleMedium)
                val subtitle = buildString {
                    dog.breed?.let { append(it).append(" · ") }
                    append("${dog.weightKg} kg")
                }
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
                Text(text = dog.ownerName, style = MaterialTheme.typography.bodySmall)
            }
            DogStatusSelector(status = dog.status, onSelect = onSetStatus)
        }
    }
}

/** Compact per-dog day-status picker: a button showing the current status that
 *  opens a menu of all five (Uit / Wandel / Ophaal / Logeer / Breng). */
@Composable
private fun DogStatusSelector(status: DogStatus, onSelect: (DogStatus) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = status.label(),
                fontWeight = if (status == DogStatus.WALK) FontWeight.Normal else FontWeight.SemiBold,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DogStatus.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** Dutch UI label for a status (the enum identifiers stay English). */
private fun DogStatus.label(): String = when (this) {
    DogStatus.OFF -> "Uit"
    DogStatus.WALK -> "Wandel"
    DogStatus.BOARD_ARRIVE -> "Ophaal"
    DogStatus.BOARD_STAY -> "Logeer"
    DogStatus.BOARD_LEAVE -> "Breng"
}
