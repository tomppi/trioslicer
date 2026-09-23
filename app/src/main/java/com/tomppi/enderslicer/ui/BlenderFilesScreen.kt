package com.tomppi.enderslicer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.storage.BlenderOutputStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Blender handoff directory, with the ability to clear it.
 *
 * A full-screen destination rather than a sheet, because it is somewhere the
 * user browses and decides rather than a momentary picker. Every delete asks
 * first: these are the only copies of models the engine produced, and the app
 * has no way to regenerate them.
 */
@Composable
fun BlenderFilesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { BlenderOutputStore(context.applicationContext) }

    var entries by remember { mutableStateOf<List<BlenderOutputStore.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var pendingDelete by remember { mutableStateOf<BlenderOutputStore.Entry?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        entries = withContext(Dispatchers.IO) { store.list() }
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }

    fun delete(selected: List<BlenderOutputStore.Entry>) {
        scope.launch {
            val removed = withContext(Dispatchers.IO) { store.delete(selected) }
            status = if (removed == selected.size) {
                "Deleted " + removed + " file" + if (removed == 1) "" else "s"
            } else {
                "Deleted " + removed + " of " + selected.size + " - the rest are in use"
            }
            refresh()
        }
    }

    val totalBytes = store.totalBytes(entries)
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
                Text("Blender files", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = if (entries.isEmpty()) {
                        "Nothing exported yet"
                    } else {
                        entries.size.toString() + " file" + (if (entries.size == 1) "" else "s") +
                            "  ·  " + formatBytes(totalBytes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { confirmDeleteAll = true },
                enabled = entries.isNotEmpty(),
            ) { Text("Delete all") }
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
            text = "Every model the Blender engine exports is written here and picked " +
                "up once. Nothing removes them afterwards, so this is where they " +
                "accumulate.",
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
                text = "The export folder is empty.",
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
                items(entries, key = { it.file.absolutePath }) { entry ->
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
                                    text = formatBytes(entry.sizeBytes) + "  ·  " +
                                        dateFormat.format(Date(entry.modifiedAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { pendingDelete = entry }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Delete " + entry.name,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this file?") },
            text = {
                Text(
                    entry.name + " (" + formatBytes(entry.sizeBytes) + ") will be removed " +
                        "from the export folder. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    delete(listOf(entry))
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all exports?") },
            text = {
                Text(
                    "All " + entries.size + " files (" + formatBytes(totalBytes) + ") in the " +
                        "export folder will be removed. Models already loaded on the plate " +
                        "are not affected. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    delete(entries)
                }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") }
            },
        )
    }
}

/** Byte counts in the units a phone user thinks in. */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.0f kB".format(bytes / 1024.0)
    else -> bytes.toString() + " B"
}
