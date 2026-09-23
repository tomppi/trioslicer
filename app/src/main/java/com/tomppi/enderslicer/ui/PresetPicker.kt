package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.PrusaPresetOption

/**
 * One entry of a preset picker: a button that names the current choice and a filtered list.
 *
 * The PrusaSlicer bundle ships 288 materials and a few dozen quality profiles per machine, so
 * the list needs a filter to be usable on a phone; entries carry an id to store and a label to
 * show, which are the same string except for the machines.
 */
@Composable
internal fun PresetPickerRow(
    label: String,
    selectedLabel: String,
    options: List<PrusaPresetOption>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emptyHint: String = "Nothing to choose yet",
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled && options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label + ": " + selectedLabel)
        }
        if (options.isEmpty() && enabled) {
            Text(emptyHint, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (!open) return

    var query by remember { mutableStateOf("") }
    val matches = remember(options, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) options
        else options.filter { it.label.lowercase().contains(needle) || it.id.lowercase().contains(needle) }
    }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Filter") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (matches.size == options.size) options.size.toString() + " entries"
                    else matches.size.toString() + " of " + options.size,
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                    items(matches, key = { it.id }) { option ->
                        DropdownMenuItem(
                            text = { Text(if (option.label == selectedLabel) "✔ " + option.label else option.label) },
                            onClick = {
                                onSelect(option.id)
                                open = false
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
    )
}
