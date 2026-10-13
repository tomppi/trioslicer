package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.ExtraSettingSpec

/**
 * The picker behind the sidebar's Add button: every setting that engine has, with
 * the ones the sidebar already shows marked, and the sidebar's own rows at the top
 * in their order so they can be moved or taken off.
 *
 * A whole screen rather than a dialog, the way "all settings" is: the Cura
 * catalogue alone holds 729 settings, and both the search field and the list want
 * the room. Search matches the key, the label and the description. A setting whose
 * row would have no editor says so here instead of being added as a dead row, and
 * a setting the app manages is named as that rather than refused at the first edit.
 */
@Composable
internal fun SessionSidebarPicker(
    engineLabel: String,
    specs: List<ExtraSettingSpec>,
    chosen: List<String>,
    values: Map<String, String>,
    blockedKeys: Set<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val byKey = remember(specs) { specs.associateBy { it.key.lowercase() } }
    val filtered = remember(query, specs) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            specs
        } else {
            specs.filter {
                it.key.lowercase().contains(needle) ||
                    it.label.lowercase().contains(needle) ||
                    it.description.lowercase().contains(needle)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Sidebar settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The plate's sidebar shows these, top to bottom. $engineLabel keeps its own list, " +
                "and every added row edits the same engine setting its settings row does.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("On the sidebar", style = MaterialTheme.typography.titleMedium)
        if (chosen.isEmpty()) {
            Text(
                "Nothing - the sidebar is empty.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        chosen.forEachIndexed { index, id ->
            val spec = byKey[id]
            val label = spec?.label?.ifBlank { null } ?: builtInSidebarLabel(id)
            val shown = spec?.let { values[it.key] ?: values[it.key.lowercase()] } ?: values[id]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        (spec?.key ?: "built-in row") + (shown?.let { " = $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onMove(id, -1) }, enabled = index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move " + label + " up")
                }
                IconButton(onClick = { onMove(id, 1) }, enabled = index < chosen.lastIndex) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move " + label + " down")
                }
                IconButton(onClick = { onRemove(id) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Take " + label + " off the sidebar")
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search " + engineLabel + " settings") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(filtered, key = { it.key }) { spec ->
                val id = spec.key.lowercase()
                val onSidebar = id in chosen
                val blocked = spec.key in blockedKeys
                val refusal = sidebarEditorRefusal(spec)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            spec.label.ifBlank { spec.key },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            spec.key + (spec.defaultValue?.let { " (default: $it)" } ?: "") +
                                if (onSidebar) " - on the sidebar" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        when {
                            blocked -> Text(
                                "the app manages this setting on this engine",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )

                            refusal != null -> Text(
                                refusal,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    when {
                        blocked -> Unit
                        onSidebar -> TextButton(onClick = { onRemove(id) }) { Text("Remove") }
                        refusal != null -> Unit
                        else -> TextButton(onClick = { onAdd(spec.key) }) { Text("Add") }
                    }
                }
            }
        }
    }
}
