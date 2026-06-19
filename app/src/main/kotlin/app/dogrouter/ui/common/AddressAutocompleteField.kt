package app.dogrouter.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.dogrouter.data.remote.AddressSuggestion

/**
 * Text field with live BAN-autocomplete suggestions and a validation
 * checkmark when the current value carries coordinates. Used by both
 * the Dog editor (per-stop address) and the Settings screen (home
 * address) — keep this composable strictly UI-only, never reach into
 * either feature's ViewModel from here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressAutocompleteField(
    value: String,
    isValidated: Boolean,
    suggestions: List<AddressSuggestion>,
    label: String,
    onValueChange: (String) -> Unit,
    onPick: (AddressSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded && suggestions.isNotEmpty(),
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label) },
            trailingIcon = {
                if (isValidated) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Address validated",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = expanded && suggestions.isNotEmpty(),
                    )
                }
            },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded && suggestions.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion.label, maxLines = 2) },
                    onClick = {
                        onPick(suggestion)
                        expanded = false
                    },
                )
            }
        }
    }
}
