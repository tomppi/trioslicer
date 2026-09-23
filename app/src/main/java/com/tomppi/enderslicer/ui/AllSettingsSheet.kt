package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.ExtraSettingSpec

/**
 * Searchable "all settings" catalog for one engine. Filter matches the key,
 * label and description; tapping a setting opens an inline value editor and
 * adds it to the engine's extra settings (shown at the top with remove).
 */
@Composable
internal fun AllSettingsSheet(
    engineLabel: String,
    specs: List<ExtraSettingSpec>,
    added: Map<String, String>,
    managedKeys: Set<String>,
    blockedKeys: Set<String>,
    /** Returns why the setting was refused, or null when it was added. */
    onAdd: (String, String) -> String?,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var editingKey by remember { mutableStateOf<String?>(null) }
    var editingValue by remember { mutableStateOf("") }
    // Kept next to the editor that caused it: the status line that carries the
    // same text is drawn on the Plate tab, not here.
    var addError by remember { mutableStateOf<String?>(null) }

    val filtered = remember(query, specs) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            specs
        } else {
            specs.filter {
                it.key.lowercase().contains(q) ||
                    it.label.lowercase().contains(q) ||
                    it.description.lowercase().contains(q)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text("All $engineLabel settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Search below and add any setting. Added settings are stored with the engine " +
                "and used by every slice.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (added.isNotEmpty()) {
            Text("Added settings", style = MaterialTheme.typography.titleMedium)
            added.toSortedMap().forEach { (key, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("$key = $value", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { onRemove(key) }) { Text("Remove") }
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search settings") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filtered, key = { it.key }) { spec ->
                if (editingKey == spec.key) {
                    OutlinedTextField(
                        value = editingValue,
                        onValueChange = { editingValue = it },
                        label = { Text(spec.key) },
                        supportingText = { Text(spec.description.take(140)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val reason = onAdd(spec.key, editingValue)
                                if (reason == null) {
                                    addError = null
                                    editingKey = null
                                } else {
                                    // A refused setting stays open with its reason:
                                    // closing the editor made the rejection look
                                    // like a setting that silently did not stick.
                                    addError = reason
                                }
                            },
                        ) {
                            Text("Add")
                        }
                        OutlinedButton(
                            onClick = {
                                addError = null
                                editingKey = null
                            },
                        ) { Text("Cancel") }
                    }
                    addError?.let { reason ->
                        Text(
                            reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            editingKey = spec.key
                            editingValue = added[spec.key.lowercase()] ?: ""
                            addError = null
                        },
                        enabled = spec.key !in blockedKeys,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val tag = when {
                            spec.key in blockedKeys -> " (blocked: app-managed, cannot add)"
                            spec.key in managedKeys -> " (managed by the app)"
                            else -> ""
                        }
                        Text(
                            spec.display + tag +
                                (added[spec.key.lowercase()]?.let { " = $it" } ?: ""),
                        )
                    }
                }
            }
        }
    }
}
