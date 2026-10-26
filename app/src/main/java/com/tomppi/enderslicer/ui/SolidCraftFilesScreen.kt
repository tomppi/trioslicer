package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import com.tomppi.enderslicer.solidcraft.CadDesignEntry
import com.tomppi.enderslicer.solidcraft.CadDesignLibrary
import com.tomppi.enderslicer.solidcraft.CadWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The SolidCraft workspace's designs, with the two things a human does to one: put it on the
 * plate, or remove it.
 *
 * A design is the engine's own .solidcraft document. It is not an export - it is the thing the
 * agent authors - so this screen does not load one into an engine scene the way the Blender
 * files screen loads a model back into Blender. "Load to plate" opens the design as the plate's
 * live document, which is the one thing on this screen that touches the plate at all; Delete
 * only ever removes files, and refuses the design the plate is currently showing.
 *
 * The part count comes from the sidecar the app writes when it publishes a design. A design it
 * has not written since - one copied in, or written by an older version - shows its file name
 * and does not invent a count: the folder is listed without starting the engine.
 *
 * @param plateDesignPath the design the plate is showing now, if any; Delete refuses it.
 * @param plateHasContent true when the plate holds models or a document, so Load asks first.
 */
@Composable
fun SolidCraftFilesScreen(
    workspace: CadWorkspace,
    plateDesignPath: String?,
    plateHasContent: Boolean,
    onBack: () -> Unit,
    onLoad: (CadDesignEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<CadDesignEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<CadDesignEntry?>(null) }
    var pendingLoad by remember { mutableStateOf<CadDesignEntry?>(null) }

    suspend fun refresh() {
        entries = withContext(Dispatchers.IO) { CadDesignLibrary.list(workspace) }
        loading = false
    }

    LaunchedEffect(workspace) { refresh() }

    fun delete(entry: CadDesignEntry) {
        if (CadDesignLibrary.isOnPlate(plateDesignPath, entry.designFile)) {
            // The file is the plate's document: removing it would take the source of truth out
            // from under the shape the user is looking at.
            status = CadDesignLibrary.ON_PLATE_REFUSAL
            return
        }
        scope.launch {
            val removed = withContext(Dispatchers.IO) {
                CadDesignLibrary.delete(workspace, entry.designFile)
            }
            status = if (removed) {
                "Deleted " + entry.designFile.name
            } else {
                "Could not delete " + entry.designFile.name
            }
            refresh()
        }
    }

    val dateFormat = remember { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = EnderSlicerDimens.Space4,
                    end = EnderSlicerDimens.Space12,
                    top = EnderSlicerDimens.Space4,
                    bottom = EnderSlicerDimens.Space4,
                ),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("SolidCraft files", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = if (entries.isEmpty()) {
                        "No designs"
                    } else {
                        entries.size.toString() + " design" + (if (entries.size == 1) "" else "s")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        status?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = EnderSlicerDimens.Space12),
            )
        }

        Text(
            text = "Every design the CAD agent has made lives here. Load one onto the plate, or " +
                "delete the ones you no longer need; deleting a design removes its file and " +
                "its display cache, never the models already on the plate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                horizontal = EnderSlicerDimens.Space12,
                vertical = EnderSlicerDimens.Space8,
            ),
        )

        if (loading) {
            Spacer(Modifier.height(EnderSlicerDimens.Space24))
        } else if (entries.isEmpty()) {
            Text(
                text = CadDesignLibrary.EMPTY_SENTENCE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(EnderSlicerDimens.Space12),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    horizontal = EnderSlicerDimens.Space12,
                    vertical = EnderSlicerDimens.Space4,
                ),
                verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space4),
            ) {
                items(entries, key = { it.designFile.absolutePath }) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(
                                start = EnderSlicerDimens.CardPadding,
                                end = EnderSlicerDimens.Space4,
                                top = EnderSlicerDimens.Space8,
                                bottom = EnderSlicerDimens.Space8,
                            ),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = partCountLabel(entry.partCount) + "  ·  " +
                                        dateFormat.format(Date(entry.modifiedAtMillis)) + "  ·  " +
                                        formatBytes(entry.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                if (plateHasContent || plateDesignPath != null) {
                                    pendingLoad = entry
                                } else {
                                    onLoad(entry)
                                }
                            }) { Text("Load to plate") }
                            TextButton(onClick = { pendingDelete = entry }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    pendingLoad?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingLoad = null },
            title = { Text("Load " + entry.designFile.name + " to the plate?") },
            text = {
                Text(
                    if (plateDesignPath != null) {
                        "The plate is showing a CAD document now; loading this design replaces " +
                            "it. The document stays open in the engine until then, and its " +
                            "design file is not deleted."
                    } else {
                        "The plate has models on it; this design opens as the plate's document " +
                            "beside them, one plate object per part."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingLoad = null
                    onLoad(entry)
                }) { Text("Load") }
            },
            dismissButton = {
                TextButton(onClick = { pendingLoad = null }) { Text("Cancel") }
            },
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete " + entry.designFile.name + "?") },
            text = {
                Text(
                    "The design file and its display cache will be removed. Models already on " +
                        "the plate are not affected. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    delete(entry)
                }) { Text("Delete design") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/** The part count line for one design, honest when the app has never written one. */
internal fun partCountLabel(partCount: Int?): String = when (partCount) {
    null -> "part count unknown"
    1 -> "1 part"
    else -> partCount.toString() + " parts"
}
