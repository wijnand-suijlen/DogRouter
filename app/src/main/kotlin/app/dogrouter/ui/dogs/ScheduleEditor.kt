package app.dogrouter.ui.dogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.dogrouter.domain.billing.Pricing
import app.dogrouter.domain.billing.euroText
import app.dogrouter.domain.billing.parseEuroToCents
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private data class PendingTimePick(
    val ruleId: String,
    val field: Field,
    val current: LocalTime?,
) {
    enum class Field { EarliestStart, LatestStart, LatestEnd }
}

@Composable
fun ScheduleEditor(
    rules: List<ScheduleRuleDraft>,
    onAddRule: () -> Unit,
    onRemoveRule: (ruleId: String) -> Unit,
    onToggleWeekday: (ruleId: String, day: DayOfWeek) -> Unit,
    onEarliestStartChange: (ruleId: String, time: LocalTime?) -> Unit,
    onLatestStartChange: (ruleId: String, time: LocalTime?) -> Unit,
    onLatestEndChange: (ruleId: String, time: LocalTime?) -> Unit,
    onDurationChange: (ruleId: String, minutes: Int) -> Unit,
    onToggleAlternative: (ruleId: String, value: Boolean) -> Unit,
    onPriceChange: (ruleId: String, priceCents: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pending: PendingTimePick? by remember { mutableStateOf(null) }
    val alternativeCount = rules.count { it.isAlternative }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (alternativeCount >= 1) {
            Text(
                text = if (alternativeCount >= 2) {
                    "Of the $alternativeCount rules marked “either/or”, the planner " +
                        "walks exactly one each day."
                } else {
                    "Mark a second rule “either/or” to make the planner choose between them."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rules.forEach { rule ->
            RuleCard(
                rule = rule,
                onRemove = { onRemoveRule(rule.id) },
                onToggleWeekday = { day -> onToggleWeekday(rule.id, day) },
                onEarliestStartTap = { pending = PendingTimePick(rule.id, PendingTimePick.Field.EarliestStart, rule.earliestStart) },
                onEarliestStartClear = { onEarliestStartChange(rule.id, null) },
                onLatestStartTap = { pending = PendingTimePick(rule.id, PendingTimePick.Field.LatestStart, rule.latestStart) },
                onLatestStartClear = { onLatestStartChange(rule.id, null) },
                onLatestEndTap = { pending = PendingTimePick(rule.id, PendingTimePick.Field.LatestEnd, rule.latestEnd) },
                onLatestEndClear = { onLatestEndChange(rule.id, null) },
                onDurationChange = { minutes -> onDurationChange(rule.id, minutes) },
                onToggleAlternative = { value -> onToggleAlternative(rule.id, value) },
                onPriceChange = { cents -> onPriceChange(rule.id, cents) },
            )
        }
        OutlinedButton(onClick = onAddRule, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("  Add walk rule")
        }
    }

    pending?.let { request ->
        TimePickerDialog(
            initial = request.current,
            onDismiss = { pending = null },
            onConfirm = { time ->
                when (request.field) {
                    PendingTimePick.Field.EarliestStart -> onEarliestStartChange(request.ruleId, time)
                    PendingTimePick.Field.LatestStart -> onLatestStartChange(request.ruleId, time)
                    PendingTimePick.Field.LatestEnd -> onLatestEndChange(request.ruleId, time)
                }
                pending = null
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RuleCard(
    rule: ScheduleRuleDraft,
    onRemove: () -> Unit,
    onToggleWeekday: (DayOfWeek) -> Unit,
    onEarliestStartTap: () -> Unit,
    onEarliestStartClear: () -> Unit,
    onLatestStartTap: () -> Unit,
    onLatestStartClear: () -> Unit,
    onLatestEndTap: () -> Unit,
    onLatestEndClear: () -> Unit,
    onDurationChange: (Int) -> Unit,
    onToggleAlternative: (Boolean) -> Unit,
    onPriceChange: (Int?) -> Unit,
) {
    Card(colors = CardDefaults.outlinedCardColors()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = day in rule.weekdays,
                        onClick = { onToggleWeekday(day) },
                        label = {
                            Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault()))
                        },
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TimeFieldChip(
                    label = "Start from",
                    time = rule.earliestStart,
                    onClick = onEarliestStartTap,
                    onClear = onEarliestStartClear,
                )
                TimeFieldChip(
                    label = "Start by",
                    time = rule.latestStart,
                    onClick = onLatestStartTap,
                    onClear = onLatestStartClear,
                )
                TimeFieldChip(
                    label = "Home by",
                    time = rule.latestEnd,
                    onClick = onLatestEndTap,
                    onClear = onLatestEndClear,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = rule.durationMinutes.toString(),
                    onValueChange = { raw ->
                        val parsed = raw.filter(Char::isDigit).toIntOrNull() ?: 0
                        onDurationChange(parsed)
                    },
                    label = { Text("Duration") },
                    suffix = { Text("min") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(140.dp),
                )
                var priceText by remember(rule.id) {
                    mutableStateOf(
                        euroText(rule.priceCents ?: Pricing.defaultPriceCents(rule.durationMinutes)),
                    )
                }
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { raw ->
                        priceText = raw
                        onPriceChange(parseEuroToCents(raw))
                    },
                    label = { Text("Price") },
                    prefix = { Text("€") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.width(120.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Text(" Delete rule")
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(checked = rule.isAlternative, onCheckedChange = onToggleAlternative)
                Text(
                    text = "Either/or (walk only one of these)",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (rule.weekdays.isEmpty()) {
                Text(
                    text = "Pick at least one weekday.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeFieldChip(
    label: String,
    time: LocalTime?,
    onClick: () -> Unit,
    onClear: () -> Unit,
) {
    InputChip(
        selected = time != null,
        onClick = onClick,
        label = {
            Text(time?.let { "$label ${it.format(timeFormatter)}" } ?: "$label any")
        },
        trailingIcon = if (time != null) {
            {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Close, contentDescription = "Clear $label")
                }
            }
        } else {
            null
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initial: LocalTime?,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initial?.hour ?: 9,
        initialMinute = initial?.minute ?: 0,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a time") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
