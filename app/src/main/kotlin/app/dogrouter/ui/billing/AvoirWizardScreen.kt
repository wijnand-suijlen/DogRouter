package app.dogrouter.ui.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dogrouter.domain.billing.euroText
import app.dogrouter.domain.billing.formatEuros
import app.dogrouter.domain.billing.parseEuroToCents
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvoirWizardScreen(
    ownerId: String,
    serviceId: String,
    onDone: () -> Unit,
    viewModel: AvoirWizardViewModel = koinViewModel { parametersOf(ownerId, serviceId) },
) {
    val original by viewModel.original.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var amountText by remember(original) { mutableStateOf(original?.let { euroText(it.amountCents) } ?: "") }
    val amountCents = parseEuroToCents(amountText)

    LaunchedEffect(viewModel) {
        viewModel.result.collect { file ->
            sharePdf(context, file)
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Credit note (avoir)") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Step ${step + 1} of 3", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

            when (step) {
                0 -> {
                    Text("What is a credit note?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "A walk you have already been paid for cannot simply be deleted — the " +
                            "payment really happened. To correct a mistake you issue a credit note " +
                            "(facture d'avoir): a definitive invoice for a negative amount that cancels " +
                            "(part of) the original. It lowers your turnover in the quarter you make it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Use this only for a genuine correction; it cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Next") }
                }

                1 -> {
                    Text("What are you correcting?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val o = original
                    Card {
                        Column(modifier = Modifier.padding(16.dp)) {
                            if (o == null) {
                                Text("Service not found.")
                            } else {
                                Text(o.description, fontWeight = FontWeight.Medium)
                                Text(
                                    "${o.date} · paid · ${formatEuros(o.amountCents)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Text(
                        "Amount to credit. Defaults to the full original; lower it for a partial correction.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount to credit") },
                        prefix = { Text("€") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = amountCents == null || amountCents <= 0,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { step = 0 }, modifier = Modifier.weight(1f)) { Text("Back") }
                        Button(
                            onClick = { step = 2 },
                            enabled = amountCents != null && amountCents > 0,
                            modifier = Modifier.weight(1f),
                        ) { Text("Next") }
                    }
                }

                else -> {
                    Text("Confirm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val credited = amountCents ?: 0
                    Text(
                        "A definitive credit note for −${formatEuros(credited)} will be created and a PDF " +
                            "generated to share. This reduces this quarter's turnover.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { step = 1 }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Back") }
                        Button(
                            onClick = { viewModel.create(credited) },
                            enabled = !busy && credited > 0,
                            modifier = Modifier.weight(1f),
                        ) { Text("Create credit note") }
                    }
                }
            }
        }
    }
}
