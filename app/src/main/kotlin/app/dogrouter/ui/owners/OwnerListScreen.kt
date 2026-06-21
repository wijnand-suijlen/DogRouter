package app.dogrouter.ui.owners

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import app.dogrouter.data.entity.Owner
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerListScreen(
    onBack: () -> Unit,
    onAddClick: () -> Unit,
    onOwnerClick: (ownerId: String) -> Unit,
    viewModel: OwnerListViewModel = koinViewModel(),
) {
    val owners by viewModel.owners.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Owners") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = "Add owner")
            }
        },
    ) { innerPadding ->
        if (owners.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text("No owners yet. Tap + to add one.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(owners, key = { it.id }) { owner ->
                    OwnerRow(owner = owner, onClick = { onOwnerClick(owner.id) })
                }
            }
        }
    }
}

@Composable
private fun OwnerRow(owner: Owner, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = owner.displayName.ifBlank { "(no name)" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (owner.isEmployer) {
                    Spacer(Modifier.width(8.dp))
                    AssistChip(onClick = onClick, label = { Text("EMPLOYER") })
                }
                if (owner.isTest) {
                    Spacer(Modifier.width(8.dp))
                    AssistChip(onClick = onClick, label = { Text("TEST") })
                }
            }
            val subtitle = listOfNotNull(owner.email, owner.phone).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
