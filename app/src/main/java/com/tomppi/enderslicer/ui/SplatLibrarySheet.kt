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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tomppi.enderslicer.splat.PointCloudToMeshConverter
import com.tomppi.enderslicer.splat.SplatLibrary
import com.tomppi.enderslicer.splat.SplatViewerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The Splat/PLY library: trained Gaussian splat scenes, EasyScanner laser/fused PLY point clouds,
 * and imported models, all opening in the Aholo viewer with on-device Convert to mesh (STL).
 */
@Composable
internal fun SplatLibrarySheet(
    onDismiss: () -> Unit,
    onSendToPlate: (File) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<SplatLibrary.Entry?>(null) }
    var meshTarget by remember { mutableStateOf<SplatLibrary.Entry?>(null) }
    var meshDetail by remember { mutableStateOf(PointCloudToMeshConverter.DetailPreset.MEDIUM) }
    var meshCleanFloaters by remember { mutableStateOf(true) }
    var meshing by remember { mutableStateOf(false) }
    var meshProgress by remember { mutableFloatStateOf(0f) }
    var meshStage by remember { mutableStateOf("") }
    var meshResult by remember { mutableStateOf<PointCloudToMeshConverter.Result?>(null) }
    var meshError by remember { mutableStateOf<String?>(null) }

    val entries = remember(revision) { SplatLibrary.listEntries(context) }
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
                    message = it.message ?: "The file could not be imported."
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
                Text("Splat/PLY files", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Trained Gaussian splats and EasyScanner PLY point clouds",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDismiss) { Text("Done") }
        }
        HorizontalDivider()
        if (entries.isEmpty()) {
            Text(
                "No splat or PLY files yet. Capture a scan in EasyScan or import a .sog/.ply " +
                    "file from Downloads to view it offline on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            entries.forEach { entry ->
                SplatRow(
                    entry = entry,
                    canMesh = PointCloudToMeshConverter.canConvert(entry.file),
                    onOpen = { openSplat(context, entry) },
                    onConvertToMesh = {
                        meshTarget = entry
                        meshResult = null
                        meshError = null
                        meshProgress = 0f
                        meshStage = ""
                    },
                    onDelete = { pendingDelete = entry },
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
            ) { Text("Import splat / PLY") }
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
            "Aholo renderer (MIT), offline: every splat and PLY stays on the phone.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        meshTarget?.let { entry ->
            AlertDialog(
                onDismissRequest = { if (!meshing) meshTarget = null },
                title = { Text("Convert to mesh (STL)") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8)) {
                        Text(
                            "${entry.displayName} [${entry.badge}]",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (meshing) {
                            LinearProgressIndicator(
                                progress = { meshProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                meshStage.ifEmpty { "Converting to STL…" },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else if (meshResult != null) {
                            val r = meshResult!!
                            val dims = String.format(
                                Locale.US,
                                "%.1f × %.1f × %.1f mm",
                                r.sizeMm.getOrElse(0) { 0f },
                                r.sizeMm.getOrElse(1) { 0f },
                                r.sizeMm.getOrElse(2) { 0f },
                            )
                            Text(
                                if (r.directMeshCopy) {
                                    "Mesh ready (${r.sourceNote}): ${r.triangles} triangles · $dims · ${formatSize(r.stlFile.length())}"
                                } else {
                                    "Mesh ready (${r.sourceNote})\n" +
                                        "${r.triangles} triangles (${String.format(Locale.US, "%.2f", r.voxelMm)} mm voxels)\n" +
                                        "Points used: ${r.keptPoints} / ${r.inputPoints}\n" +
                                        "Size: $dims · ${formatSize(r.stlFile.length())}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (entry.isGaussianSplat) {
                                Text(
                                    "Note: Gaussian splats use SfM/VIO scale — verify dimensions on the build plate before slicing.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Text("Detail level", style = MaterialTheme.typography.labelMedium)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space4),
                            ) {
                                PointCloudToMeshConverter.DetailPreset.entries.forEach { preset ->
                                    if (meshDetail == preset) {
                                        Button(
                                            onClick = { meshDetail = preset },
                                            modifier = Modifier.weight(1f),
                                        ) { Text(preset.label) }
                                    } else {
                                        OutlinedButton(
                                            onClick = { meshDetail = preset },
                                            modifier = Modifier.weight(1f),
                                        ) { Text(preset.label) }
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Remove floaters & outliers", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (entry.isGaussianSplat) {
                                            "Filters low-opacity & oversized background splats before TSDF meshing"
                                        } else {
                                            "Drops isolated noise specks before TSDF Dual Contouring"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = meshCleanFloaters,
                                    onCheckedChange = { meshCleanFloaters = it },
                                )
                            }
                            meshError?.let { err ->
                                Text(
                                    err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    val ready = meshResult
                    when {
                        meshing -> TextButton(onClick = {}, enabled = false) { Text("Working…") }
                        ready != null -> TextButton(onClick = {
                            meshTarget = null
                            onSendToPlate(ready.stlFile)
                        }) { Text("Send to Plate") }
                        else -> TextButton(onClick = {
                            meshing = true
                            meshError = null
                            meshProgress = 0.05f
                            meshStage = "Starting…"
                            val opts = PointCloudToMeshConverter.Options(
                                detail = meshDetail,
                                cleanFloaters = meshCleanFloaters,
                            )
                            scope.launch(Dispatchers.Default) {
                                val res = runCatching {
                                    PointCloudToMeshConverter.convert(
                                        context = context,
                                        source = entry.file,
                                        displayName = entry.displayName,
                                        options = opts,
                                    ) { frac, stage ->
                                        scope.launch(Dispatchers.Main) {
                                            meshProgress = frac
                                            meshStage = stage
                                        }
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    meshing = false
                                    res.onSuccess { out ->
                                        meshResult = out
                                        message = "Converted ${entry.displayName} -> ${out.stlFile.name} (${out.triangles} tris)"
                                    }.onFailure { err ->
                                        meshError = err.message ?: err.javaClass.simpleName
                                    }
                                }
                            }
                        }) { Text("Convert") }
                    }
                },
                dismissButton = {
                    if (!meshing) {
                        TextButton(onClick = { meshTarget = null }) {
                            Text(if (meshResult != null) "Close" else "Cancel")
                        }
                    }
                },
            )
        }

        pendingDelete?.let { entry ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Delete this splat?") },
                text = {
                    Text(
                        entry.displayName + " will be removed from app storage. " +
                            "Any copy you saved in Downloads is not touched.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDelete = null
                        val removed = SplatLibrary.delete(context, entry.file)
                        revision++
                        message = if (removed) {
                            "Deleted " + entry.displayName
                        } else {
                            "That file could not be deleted."
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
private fun SplatRow(
    entry: SplatLibrary.Entry,
    canMesh: Boolean,
    onOpen: () -> Unit,
    onConvertToMesh: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = EnderSlicerDimens.Space4),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                "[${entry.badge}] · " + formatSize(entry.file.length()) + " · " + DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM,
                    DateFormat.SHORT,
                ).format(Date(entry.file.lastModified())),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpen) { Text("View") }
        if (canMesh) {
            TextButton(onClick = onConvertToMesh) { Text("Mesh") }
        }
        if (entry.canDelete) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Delete " + entry.displayName,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun openSplat(context: Context, entry: SplatLibrary.Entry) {
    val file = entry.file
    context.startActivity(
        Intent(context, SplatViewerActivity::class.java)
            .putExtra(SplatViewerActivity.EXTRA_FILE, file.name)
            .putExtra(SplatViewerActivity.EXTRA_PATH, file.absolutePath)
            .putExtra(SplatViewerActivity.EXTRA_TITLE, entry.displayName.substringBeforeLast('.')),
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
