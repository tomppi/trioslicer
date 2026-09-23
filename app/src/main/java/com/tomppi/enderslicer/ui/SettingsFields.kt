package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType

/** Shared settings-sheet field components for the non-planar sheets. */
@Composable
internal fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
internal fun DecimalSettingField(
    label: String,
    text: String,
    minimum: Double,
    maximum: Double,
    onText: (String) -> Unit,
) {
    val valid = parseDecimal(text, minimum, maximum) != null
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        label = { Text(label) },
        supportingText = if (valid) null else ({ Text("Enter a value from " + minimum + " to " + maximum) }),
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun IntegerSettingField(
    label: String,
    text: String,
    minimum: Int,
    maximum: Int,
    onText: (String) -> Unit,
) {
    val valid = parseInteger(text, minimum, maximum) != null
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        label = { Text(label) },
        supportingText = if (valid) null else ({ Text("Enter a whole number from " + minimum + " to " + maximum) }),
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

internal fun parseDecimal(text: String, minimum: Double, maximum: Double): Double? {
    val normalized = text.trim().replace(',', '.')
    val value = normalized.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
    return value.takeIf { it in minimum..maximum }
}

internal fun parseInteger(text: String, minimum: Int, maximum: Int): Int? =
    text.trim().toIntOrNull()?.takeIf { it in minimum..maximum }

/** Source label shown under an editable field. */
@Composable
internal fun SettingSource(source: String) {
    Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun source(state: MainUiState, key: String): String = when {
    state.settings.isOverridden(key) -> "App override"
    state.engineProfile != null -> "Imported Cura value"
    else -> "Built-in default"
}

@Composable
internal fun NumberField(
    label: String,
    value: Double,
    source: String,
    decimals: Int = 2,
    onValue: (Double) -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    var text by rememberSaveable {
        mutableStateOf(if (decimals == 0) value.toInt().toString() else value.toString().trimEnd('0').trimEnd('.'))
    }
    LaunchedEffect(value, isFocused) {
        if (!isFocused) {
            text = if (decimals == 0) value.toInt().toString() else value.toString().trimEnd('0').trimEnd('.')
        }
    }
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                input.replace(',', '.').toDoubleOrNull()?.let(onValue)
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused },
        )
        SettingSource(source)
    }
}

@Composable
internal fun OptionField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    source: String,
    onValue: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.first == value }?.second ?: value
    Column {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(label + ": " + display)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (optionValue, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            expanded = false
                            onValue(optionValue)
                        },
                    )
                }
            }
        }
        SettingSource(source)
    }
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    source: String,
    onChecked: (Boolean) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChecked)
        }
        SettingSource(source)
    }
}
