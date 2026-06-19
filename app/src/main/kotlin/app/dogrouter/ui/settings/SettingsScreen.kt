package app.dogrouter.ui.settings

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { innerPadding ->
        val state = form
        if (state == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            SettingsForm(
                state = state,
                onCyclingSpeedChange = viewModel::onCyclingSpeedTextChange,
                onBikeCapacityChange = viewModel::onBikeCapacityTextChange,
                onStopBufferChange = viewModel::onStopBufferTextChange,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

@Composable
private fun SettingsForm(
    state: SettingsFormState,
    onCyclingSpeedChange: (String) -> Unit,
    onBikeCapacityChange: (String) -> Unit,
    onStopBufferChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Planning parameters")

        OutlinedTextField(
            value = state.cyclingSpeedText,
            onValueChange = onCyclingSpeedChange,
            label = { Text("Average cycling speed") },
            suffix = { Text("km/h") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = !state.cyclingSpeedValid,
            supportingText = if (!state.cyclingSpeedValid) {
                { Text("Enter a positive number") }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.bikeCapacityText,
            onValueChange = onBikeCapacityChange,
            label = { Text("Cargo bike weight capacity") },
            suffix = { Text("kg") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = !state.bikeCapacityValid,
            supportingText = if (!state.bikeCapacityValid) {
                { Text("Enter a positive number") }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.stopBufferText,
            onValueChange = onStopBufferChange,
            label = { Text("Default stop time buffer") },
            suffix = { Text("min") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = !state.stopBufferValid,
            supportingText = if (!state.stopBufferValid) {
                { Text("Enter zero or a positive whole number") }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Changes are saved as you type.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
