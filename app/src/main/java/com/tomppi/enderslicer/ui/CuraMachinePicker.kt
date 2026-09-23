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
import com.tomppi.enderslicer.model.CuraMachineCatalog

/**
 * Cura machine picker.
 *
 * The desktop app picks a printer and the engine resolves that machine's own definition chain;
 * this offers the same catalogue (636 machines in the bundled Cura 5.14.0-alpha.0 tree) instead
 * of slicing everything as an Ender 3. The chosen machine supplies the machine limits, extruder
 * train and definition defaults; the app's own printer envelope and settings still layer over
 * them, exactly as they do for an imported profile.
 *
 * The list is long enough that a filter is the difference between usable and not.
 */
@Composable
internal fun CuraMachinePicker(
    machines: List<CuraMachineCatalog.Machine>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val selectedName = machines.firstOrNull { it.id == selectedId }?.name
        ?: selectedId.removeSuffix(CuraMachineCatalog.DEFINITION_SUFFIX).replace('_', ' ')
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = { open = true },
            enabled = machines.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Printer: " + selectedName)
        }
        Text(
            if (machines.isEmpty()) {
                "Loading the Cura printer catalogue…"
            } else {
                machines.size.toString() + " machines from Cura 5.14.0-alpha.0; the engine " +
                    "resolves the chosen one's definition chain under your settings."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (!open) return

    var query by remember { mutableStateOf("") }
    val matches = remember(machines, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            machines
        } else {
            machines.filter { it.name.lowercase().contains(needle) || it.id.contains(needle) }
        }
    }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Cura printer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Filter by name or id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (matches.size == machines.size) {
                        machines.size.toString() + " machines"
                    } else {
                        matches.size.toString() + " of " + machines.size + " machines"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(matches, key = { it.id }) { machine ->
                        DropdownMenuItem(
                            text = {
                                Text(if (machine.id == selectedId) "✔ " + machine.name else machine.name)
                            },
                            onClick = {
                                onSelect(machine.id)
                                open = false
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { open = false }) { Text("Close") }
        },
    )
}
