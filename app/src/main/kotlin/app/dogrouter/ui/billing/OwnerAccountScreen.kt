package app.dogrouter.ui.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import app.dogrouter.data.entity.BillableService
import app.dogrouter.domain.billing.formatEuros
import app.dogrouter.domain.billing.parseEuroToCents
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerAccountScreen(
    ownerId: String,
    onBack: () -> Unit,
    onOpenInvoices: () -> Unit,
    onCorrectService: (serviceId: String) -> Unit,
    viewModel: OwnerAccountViewModel = koinViewModel { parametersOf(ownerId) },
) {
    val owner by viewModel.owner.collectAsStateWithLifecycle()
    val services by viewModel.services.collectAsStateWithLifecycle()
    val balanceCents by viewModel.balanceCents.collectAsStateWithLifecycle()
    val monthlyMinutes by viewModel.monthlyMinutes.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var showAddItem by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.generatedPdf.collect { file -> sharePdf(context, file) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(owner?.displayName?.ifBlank { "Owner" } ?: "Owner") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenInvoices) {
                        Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = "Invoices")
                    }
                },
            )
        },
        floatingActionButton = {
            if (selected.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showAddItem = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add item") },
                )
            }
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${selected.size} selected",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            OutlinedButton(onClick = viewModel::makeInvoice, enabled = !busy) {
                                Text("Invoice")
                            }
                            Button(onClick = viewModel::registerPayment, enabled = !busy) {
                                Text("Register payment")
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                val isEmployer = owner?.isEmployer == true
                Card {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isEmployer) {
                            Text("Hours per month", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (monthlyMinutes.isEmpty()) {
                                Text("No walks yet.", style = MaterialTheme.typography.bodySmall)
                            } else {
                                monthlyMinutes.forEach { (month, minutes) ->
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        Text(monthLabel(month), modifier = Modifier.weight(1f))
                                        Text(formatHoursMinutes(minutes), fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        } else {
                            Text("Outstanding balance", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = formatEuros(balanceCents),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        // Read-only status; the TEST flag is changed on the
                        // owner edit screen (reached from the dog screen).
                        if (owner?.isTest == true) {
                            HorizontalDivider()
                            Text(
                                "TEST owner — excluded from the URSSAF turnover.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            item {
                HorizontalDivider()
                Text(
                    "Services",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (services.isEmpty()) {
                item { Text("No services yet.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(services, key = { it.id }) { service ->
                    ServiceRow(
                        service = service,
                        selected = service.id in selected,
                        onToggleSelected = { viewModel.toggleSelected(service.id) },
                        onDelete = { viewModel.removeService(service) },
                        onCorrect = { onCorrectService(service.id) },
                    )
                }
            }
        }
    }

    if (showAddItem) {
        AddItemDialog(
            onConfirm = { description, cents, date ->
                viewModel.addManualItem(description, cents, date)
                showAddItem = false
            },
            onDismiss = { showAddItem = false },
        )
    }
}

@Composable
private fun ServiceRow(
    service: BillableService,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onDelete: () -> Unit,
    onCorrect: () -> Unit,
) {
    Card(colors = CardDefaults.outlinedCardColors()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Unpaid services can be ticked for invoicing/payment.
            if (!service.paid) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelected() })
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(service.description, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dateLabel(service.date),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(if (service.paid) "PAID" else "UNPAID") },
                    )
                }
            }
            Text(
                text = formatEuros(service.amountCents),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (!service.paid) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove service", tint = MaterialTheme.colorScheme.error)
                }
            } else if (service.amountCents > 0) {
                // A paid service can only be corrected via a credit note (avoir).
                TextButton(onClick = onCorrect) { Text("Correct") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddItemDialog(
    onConfirm: (description: String, amountCents: Int, date: LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var description by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val amountCents = parseEuroToCents(amountText)
    val valid = description.isNotBlank() && amountCents != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    prefix = { Text("€") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = amountText.isNotBlank() && amountCents == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Date: ${dateLabel(date)}")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onConfirm(description.trim(), amountCents!!, date) },
                enabled = valid,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

private fun dateLabel(date: LocalDate): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))

private fun monthLabel(month: java.time.YearMonth): String =
    month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
