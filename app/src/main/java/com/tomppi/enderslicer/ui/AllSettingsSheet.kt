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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.ExtraSettingSpec
import com.tomppi.enderslicer.profile.AllSettingsGroups

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
    /**
     * The settings actually in force, for judging Cura's `enabled` expressions. Empty means
     * nothing is known about them, and everything is then reported active - which is the honest
     * answer, and the only one available for Orca and Prusa, whose catalogues carry no
     * dependency information at all.
     */
    values: Map<String, String> = emptyMap(),
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

    // Grouped only when the catalogues can say something: an engine with no enabled
    // expressions gets one flat list rather than a heading that means nothing.
    val rows: List<AllSettingsRow> = remember(filtered, values) {
        if (filtered.none { it.enabledExpression != null }) {
            filtered.map { AllSettingsRow.Setting(it, null) }
        } else {
            val (active, inactive) = AllSettingsGroups.split(filtered, values)
            buildList {
                if (active.isNotEmpty()) {
                    add(AllSettingsRow.Header("Active (" + active.size + ")"))
                    active.forEach { add(AllSettingsRow.Setting(it, null)) }
                }
                if (inactive.isNotEmpty()) {
                    add(AllSettingsRow.Header("Not active (" + inactive.size + ")"))
                    inactive.forEach { add(AllSettingsRow.Setting(it.spec, it.reason)) }
                }
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
            items(rows, key = { it.key }) { row ->
                if (row is AllSettingsRow.Header) {
                    Text(
                        row.text,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    return@items
                }
                val spec = (row as AllSettingsRow.Setting).spec
                val inactiveReason = row.reason
                if (editingKey == spec.key) {
                    if (spec.boolean) {
                        // A boolean is chosen, not typed. The engine reads only "on", "yes",
                        // "true" or "True" as true and treats every other spelling - "TRUE",
                        // "ON", a typo - as false without a word to anyone, so the sheet does
                        // not offer a text field where a wrong answer is silent. The switch
                        // writes the same "true"/"false" the curated switches write.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Switch(
                                checked = editingValue.equals("true", ignoreCase = true),
                                onCheckedChange = { editingValue = it.toString() },
                            )
                            Text(
                                text = if (editingValue.equals("true", ignoreCase = true)) "true" else "false",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            text = spec.description.take(140),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedTextField(
                            value = editingValue,
                            onValueChange = { editingValue = it },
                            label = { Text(spec.key) },
                            supportingText = { Text(spec.description.take(140)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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
                  Column {
                    OutlinedButton(
                        onClick = {
                            editingKey = spec.key
                            // A boolean opens on the catalogue's own default, so the switch
                            // shows the state the setting is actually in instead of always
                            // starting off. Everything else opens on what is already stored.
                            editingValue = added[spec.key.lowercase()] ?: if (spec.boolean) {
                                if (spec.defaultValue?.lowercase() == "true") "true" else "false"
                            } else {
                                ""
                            }
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
                    // Why Cura will not read this setting as things stand. Shown on the row
                    // itself: a setting that silently does nothing is the whole reason this
                    // list is grouped, and a reason somewhere else would not be read.
                    if (inactiveReason != null) {
                        Text(
                            inactiveReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                  }
                }
            }
        }
    }
}

/** A heading or a setting in the All-settings list. */
private sealed interface AllSettingsRow {
    val key: String

    data class Header(val text: String) : AllSettingsRow {
        override val key: String get() = "header:" + text
    }

    data class Setting(val spec: ExtraSettingSpec, val reason: String?) : AllSettingsRow {
        override val key: String get() = "setting:" + spec.key
    }
}
