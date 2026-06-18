package app.dogrouter.ui.dogs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dogrouter.data.entity.Dog
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DogListScreen(
    viewModel: DogListViewModel = koinViewModel(),
) {
    val dogs by viewModel.dogs.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Dogs") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::addExampleDog) {
                Icon(Icons.Default.Add, contentDescription = "Add test dog")
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
                Text("No dogs yet. Tap + to add a test entry.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(dogs, key = { it.id }) { dog ->
                    DogRow(dog = dog, onDelete = { viewModel.deleteDog(dog) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun DogRow(
    dog: Dog,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        onClick = onDelete,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = dog.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${dog.weightKg} kg · ${dog.ownerName}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
