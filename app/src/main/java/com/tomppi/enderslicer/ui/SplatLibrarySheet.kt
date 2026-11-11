package com.tomppi.enderslicer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tomppi.enderslicer.splat.SplatLibrary
import com.tomppi.enderslicer.splat.SplatViewerActivity
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The splat library: models imported into the app, and the door into their viewer.
 *
 * A splat model is a file, not a document on the plate - it is the training pipeline's output,
 * and the Aholo viewer is how the human checks what was reconstructed before anything is
 * meshed for printing. The list lives in the app's own storage (see [SplatLibrary]) and the
 * viewer is a WebGL page over the same folder.
 */
@Composable
internal fun SplatLibrarySheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    val files = remember(revision) { SplatLibrary.list(context) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val name = displayName(context, uri)
            runCatching { SplatLibrary.import(context, uri, name) }
                .onSuccess {
                    revision++
                    message = "Imported " + it.name
                }
                .onFailure {
                    message = it.message ?: "The splat could not be imported."
                }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = EnderSlicerDimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Splat viewer", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Gaussian splat scenes, rendered on the phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDismiss) { Text("Done") }
        }
        HorizontalDivider()
        if (files.isEmpty()) {
            Text(
                "No splats yet. Import a .sog or .ply written by the training pipeline; the " +
                    "viewer keeps it in app storage and renders it on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            files.forEach { file ->
                SplatRow(
                    file = file,
                    onOpen = { openSplat(context, file) },
                    onDelete = { pendingDelete = file },
                )
                HorizontalDivider()
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
            ) { Text("Import a splat") }
            Text(
                "from Downloads or a USB drive",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "Aholo renderer (MIT), offline: the model file never leaves the phone.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        pendingDelete?.let { file ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Delete this splat?") },
                text = {
                    Text(
                        file.name + " will be removed from the app's splat storage. " +
                            "The copy you imported from Downloads is not touched.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDelete = null
                        val removed = SplatLibrary.delete(context, file)
                        revision++
                        message = if (removed) {
                            "Deleted " + file.name
                        } else {
                            "That splat could not be deleted."
                        }
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
                },
            )
        }
        Spacer(Modifier.height(EnderSlicerDimens.Space8))
    }
}

@Composable
private fun SplatRow(file: File, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = EnderSlicerDimens.Space4),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                formatSize(file.length()) + " · " + DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM,
                    DateFormat.SHORT,
                ).format(Date(file.lastModified())),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpen) { Text("View") }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Delete " + file.name,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun openSplat(context: Context, file: File) {
    context.startActivity(
        Intent(context, SplatViewerActivity::class.java)
            .putExtra(SplatViewerActivity.EXTRA_FILE, file.name)
            .putExtra(SplatViewerActivity.EXTRA_TITLE, SplatLibrary.titleOf(file)),
    )
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.0f kB".format(bytes / 1024.0)
    else -> bytes.toString() + " B"
}
