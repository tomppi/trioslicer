package com.tomppi.enderslicer.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.profile.PresetKind
import com.tomppi.enderslicer.profile.PresetLibrary
import com.tomppi.enderslicer.profile.UserPreset
import com.tomppi.enderslicer.profile.UserPresetStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PresetNameAction { CREATE, RENAME }

@Composable
fun ProfileManagementSheet(
    state: MainUiState,
    viewModel: MainViewModel,
    engine: SlicerEngine,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember(context) { UserPresetStore(context) }
    val scope = rememberCoroutineScope()
    var library by remember { mutableStateOf(PresetLibrary()) }
    var kind by remember { mutableStateOf(PresetKind.PRINT) }
    var isLoading by remember { mutableStateOf(true) }
    var isMutating by remember { mutableStateOf(false) }
    var nameAction by remember { mutableStateOf<PresetNameAction?>(null) }
    var nameTarget by remember { mutableStateOf<UserPreset?>(null) }
    var nameText by remember { mutableStateOf("") }
    var pendingApply by remember { mutableStateOf<UserPreset?>(null) }
    var pendingDelete by remember { mutableStateOf<UserPreset?>(null) }

    fun showError(error: Throwable) {
        Toast.makeText(context, error.message ?: error::class.java.simpleName, Toast.LENGTH_LONG).show()
    }

    fun launchMutation(block: suspend () -> Unit) {
        if (isMutating) return
        isMutating = true
        scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showError(error)
            } finally {
                isMutating = false
            }
        }
    }

    fun refresh() {
        if (isLoading && library.presets.isNotEmpty()) return
        scope.launch {
            isLoading = true
            try {
                library = withContext(Dispatchers.IO) { store.load() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showError(error)
            } finally {
                isLoading = false
            }
        }
    }

    suspend fun selectAndApply(
        preset: UserPreset,
        sourceLibrary: PresetLibrary,
    ): PresetLibrary {
        val previousActiveId = sourceLibrary.activeId(engine, preset.kind)
        return try {
            val stagedLibrary = withContext(Dispatchers.IO) {
                store.setActive(engine, preset.kind, preset.id)
            }
            check(viewModel.applyPreset(engine, preset.kind, preset.valuesJson)) {
                "The preset could not be applied while another operation was active"
            }
            stagedLibrary
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { store.setActive(engine, preset.kind, previousActiveId) }
            }
            throw error
        }
    }

    fun saveCurrentAs(name: String) = launchMutation {
        val selectedKind = kind
        val values = viewModel.uiState.value.capturePreset(engine, selectedKind).toString()
        val saved = withContext(Dispatchers.IO) { store.create(engine, selectedKind, name, values) }
        library = saved
        nameAction = null
        nameTarget = null
        val savedName = saved.active(engine, selectedKind)?.name ?: name.trim()
        Toast.makeText(
            context,
            "Saved ${selectedKind.label.lowercase()} ‘$savedName’",
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun applyPreset(preset: UserPreset) = launchMutation {
        library = selectAndApply(preset, library)
    }

    fun updatePreset(preset: UserPreset, thenApply: UserPreset? = null) = launchMutation {
        val values = viewModel.uiState.value.capturePreset(preset.engine, preset.kind).toString()
        val updatedLibrary = withContext(Dispatchers.IO) { store.update(preset.id, values) }
        library = updatedLibrary
        if (thenApply != null) {
            val latestTarget = updatedLibrary.presets.firstOrNull { it.id == thenApply.id } ?: thenApply
            library = selectAndApply(latestTarget, updatedLibrary)
        } else {
            Toast.makeText(context, "Saved changes to ‘${preset.name}’", Toast.LENGTH_SHORT).show()
        }
    }

    fun renamePreset(preset: UserPreset, name: String) = launchMutation {
        library = withContext(Dispatchers.IO) { store.rename(preset.id, name) }
        nameAction = null
        nameTarget = null
    }

    fun deletePreset(preset: UserPreset) = launchMutation {
        library = withContext(Dispatchers.IO) { store.delete(preset.id) }
        pendingDelete = null
        Toast.makeText(context, "Deleted ‘${preset.name}’", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) { refresh() }

    val currentValues = remember(engine, kind, state.settings, state.prusaSettings, state.orcaSettings) {
        state.capturePreset(engine, kind)
    }
    val active = library.active(engine, kind)
    val activeDirty = active?.let { !matchesPresetValues(engine, kind, currentValues, it.values()) } ?: true
    val presets = library.presets(engine, kind)
    val actionsEnabled = !state.isBusy && !isLoading && !isMutating

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text("Profiles & filament · ${engine.label}", style = MaterialTheme.typography.titleLarge)
        Text(
            "Save the current ${engine.label} settings as named presets. A preset belongs to one engine, so this list is ${engine.label}'s alone and switching engines neither hides nor re-points it. Print profiles and filament profiles are independent, so switching filament will not replace quality, infill or support settings.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetKind.entries.forEach { candidate ->
                if (candidate == kind) {
                    Button(
                        onClick = { kind = candidate },
                        enabled = !isMutating,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(candidate.pluralLabel)
                    }
                } else {
                    OutlinedButton(
                        onClick = { kind = candidate },
                        enabled = !isMutating,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(candidate.pluralLabel)
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Current ${kind.label.lowercase()}", style = MaterialTheme.typography.titleMedium)
                Text(active?.name ?: "Custom settings — not linked to a saved preset")
                if (active != null) {
                    Text(
                        if (activeDirty) "Current settings differ from the saved preset" else "Current settings match the saved preset",
                        color = if (activeDirty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            nameAction = PresetNameAction.CREATE
                            nameTarget = null
                            nameText = ""
                        },
                        enabled = actionsEnabled,
                    ) {
                        Text("Save current as…")
                    }
                    OutlinedButton(
                        onClick = { active?.let { updatePreset(it) } },
                        enabled = active != null && activeDirty && actionsEnabled,
                    ) {
                        Text("Save changes")
                    }
                }
                Text(
                    when (engine) {
                        SlicerEngine.CURA -> when (kind) {
                            PresetKind.PRINT ->
                                "Includes quality, walls, infill, speed, supports, travel, adhesion, arc-overhang and ironing settings."

                            PresetKind.FILAMENT ->
                                "Includes diameter, temperatures, flow, cooling, retraction, Z-hop, firmware retraction and coasting."
                        }

                        SlicerEngine.PRUSA -> when (kind) {
                            PresetKind.PRINT ->
                                "Includes layer heights, extrusion widths, perimeters, infill, skirt and brim, support and print speeds."

                            PresetKind.FILAMENT ->
                                "Includes nozzle and bed temperatures, cooling, retraction and extrusion multiplier."
                        }

                        SlicerEngine.ORCA -> when (kind) {
                            PresetKind.PRINT ->
                                "Includes layer heights, walls, sparse infill, skirt and brim, support and print speeds."

                            PresetKind.FILAMENT ->
                                "Includes nozzle and plate temperatures, cooling, flow ratio, retraction and Z-hop."
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "Machine geometry and the selected engine presets are never part of a saved preset.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        HorizontalDivider()
        Text("Saved ${kind.pluralLabel.lowercase()}", style = MaterialTheme.typography.titleMedium)

        if (isLoading) {
            Text("Loading saved presets…", style = MaterialTheme.typography.bodySmall)
        } else if (presets.isEmpty()) {
            Text("No saved ${kind.pluralLabel.lowercase()} yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(presets, key = UserPreset::id) { preset ->
                    val isActive = preset.id == library.activeId(engine, kind)
                    val dirty = isActive && !matchesPresetValues(engine, kind, currentValues, preset.values())
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.name, style = MaterialTheme.typography.titleSmall)
                                    if (isActive) {
                                        Text(
                                            if (dirty) "Active · modified" else "Active",
                                            color = if (dirty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                                Button(
                                    onClick = {
                                        if (!(isActive && !dirty)) {
                                            if (activeDirty) pendingApply = preset else applyPreset(preset)
                                        }
                                    },
                                    enabled = actionsEnabled && !(isActive && !dirty),
                                ) {
                                    Text(if (isActive) "Reload" else "Apply")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (isActive) {
                                    OutlinedButton(
                                        onClick = { updatePreset(preset) },
                                        enabled = dirty && actionsEnabled,
                                    ) {
                                        Text("Save changes")
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        nameAction = PresetNameAction.RENAME
                                        nameTarget = preset
                                        nameText = preset.name
                                    },
                                    enabled = actionsEnabled,
                                ) {
                                    Text("Rename")
                                }
                                TextButton(
                                    onClick = { pendingDelete = preset },
                                    enabled = actionsEnabled,
                                ) {
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }

    if (nameAction != null) {
        AlertDialog(
            onDismissRequest = { if (!isMutating) { nameAction = null; nameTarget = null } },
            title = {
                Text(if (nameAction == PresetNameAction.CREATE) "Name the ${kind.label.lowercase()}" else "Rename preset")
            },
            text = {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it.take(60) },
                    enabled = !isMutating,
                    label = { Text("Preset name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        when (nameAction) {
                            PresetNameAction.CREATE -> saveCurrentAs(nameText)
                            PresetNameAction.RENAME -> nameTarget?.let { renamePreset(it, nameText) }
                            null -> Unit
                        }
                    },
                    enabled = nameText.isNotBlank() && !isMutating,
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { nameAction = null; nameTarget = null },
                    enabled = !isMutating,
                ) { Text("Cancel") }
            },
        )
    }

    pendingApply?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!isMutating) pendingApply = null },
            title = { Text("Replace current ${kind.label.lowercase()} settings?") },
            text = {
                Text(
                    if (active != null && activeDirty) {
                        "Current changes to ‘${active.name}’ have not been saved. Applying ‘${target.name}’ will replace those ${kind.label.lowercase()} settings."
                    } else {
                        "Applying ‘${target.name}’ will replace the current ${kind.label.lowercase()} settings."
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = { pendingApply = null; applyPreset(target) },
                    enabled = !isMutating,
                ) { Text("Discard & apply") }
            },
            dismissButton = {
                Row {
                    if (active != null && activeDirty) {
                        TextButton(
                            onClick = {
                                pendingApply = null
                                updatePreset(active, thenApply = target)
                            },
                            enabled = !isMutating,
                        ) { Text("Save & apply") }
                    }
                    TextButton(onClick = { pendingApply = null }, enabled = !isMutating) { Text("Cancel") }
                }
            },
        )
    }

    pendingDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { if (!isMutating) pendingDelete = null },
            title = { Text("Delete ‘${preset.name}’?") },
            text = {
                Text("The saved ${preset.kind.label.lowercase()} will be removed. Current slicer settings will not be changed.")
            },
            confirmButton = {
                Button(onClick = { deletePreset(preset) }, enabled = !isMutating) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }, enabled = !isMutating) { Text("Cancel") }
            },
        )
    }
}
