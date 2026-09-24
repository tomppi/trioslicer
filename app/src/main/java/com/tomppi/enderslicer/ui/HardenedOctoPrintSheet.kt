package com.tomppi.enderslicer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tomppi.enderslicer.octoprint.OctoPrintFileEntry
import com.tomppi.enderslicer.octoprint.OctoPrintUiState
import com.tomppi.enderslicer.octoprint.OctoPrintUploadAction
import com.tomppi.enderslicer.octoprint.OctoPrintViewModel
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class HardenedOctoPrintPage(val label: String) {
    STATUS("Status"),
    FILES("Files"),
    CONTROL("Control"),
    SETUP("Setup"),
}

@Composable
fun HardenedOctoPrintSheet(
    state: OctoPrintUiState,
    localGcodePath: String?,
    suggestedFileName: String,
    viewModel: OctoPrintViewModel,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable {
        mutableStateOf(if (state.isReady) HardenedOctoPrintPage.STATUS else HardenedOctoPrintPage.SETUP)
    }
    var pendingUploadPrintDirectory by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmStart by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<OctoPrintFileEntry?>(null) }
    var pendingPrint by remember { mutableStateOf<OctoPrintFileEntry?>(null) }

    DisposableEffect(page) {
        viewModel.setWebcamVisible(page == HardenedOctoPrintPage.STATUS)
        onDispose { viewModel.setWebcamVisible(false) }
    }

    LaunchedEffect(state.isReady) {
        if (!state.isReady) page = HardenedOctoPrintPage.SETUP
    }

    Column(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        HardenedOctoPrintHeader(state, viewModel::refresh)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HardenedOctoPrintPage.entries.forEach { candidate ->
                if (candidate == page) {
                    Button(onClick = { page = candidate }, modifier = Modifier.weight(1f)) {
                        Text(candidate.label)
                    }
                } else {
                    OutlinedButton(onClick = { page = candidate }, modifier = Modifier.weight(1f)) {
                        Text(candidate.label)
                    }
                }
            }
        }
        HorizontalDivider()

        when (page) {
            HardenedOctoPrintPage.STATUS -> HardenedStatusPage(
                state = state,
                localGcodePath = localGcodePath,
                suggestedFileName = suggestedFileName,
                viewModel = viewModel,
                onConfirmUploadPrint = { pendingUploadPrintDirectory = it },
                onConfirmStart = { confirmStart = true },
                onConfirmRestart = { confirmRestart = true },
                onConfirmCancel = { confirmCancel = true },
                modifier = Modifier.weight(1f),
            )
            HardenedOctoPrintPage.FILES -> HardenedFilesPage(
                state = state,
                viewModel = viewModel,
                onDelete = { pendingDelete = it },
                onPrint = { pendingPrint = it },
                modifier = Modifier.weight(1f),
            )
            HardenedOctoPrintPage.CONTROL -> HardenedControlPage(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
            HardenedOctoPrintPage.SETUP -> HardenedSetupPage(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
        }
    }

    pendingUploadPrintDirectory?.let { remoteDirectory ->
        HardenedConfirmDialog(
            title = "Upload and start printing?",
            text = buildString {
                append("The current validated G-code will be uploaded to OctoPrint")
                if (remoteDirectory.isNotBlank()) append(" in folder ‘${remoteDirectory.trim('/')}’")
                append(". Printing will start only if OctoPrint accepts the request and the printer is ready.")
            },
            confirmLabel = "Upload & print",
            onDismiss = { pendingUploadPrintDirectory = null },
            onConfirm = {
                pendingUploadPrintDirectory = null
                viewModel.uploadGcode(
                    localGcodePath,
                    suggestedFileName,
                    remoteDirectory,
                    OctoPrintUploadAction.UPLOAD_AND_PRINT,
                )
            },
        )
    }

    if (confirmStart) {
        HardenedConfirmDialog(
            title = "Start the selected print?",
            text = "OctoPrint will start the selected G-code immediately. Verify the printer, bed, filament and first layer.",
            confirmLabel = "Start print",
            onDismiss = { confirmStart = false },
            onConfirm = {
                confirmStart = false
                viewModel.startJob()
            },
        )
    }

    if (confirmRestart) {
        HardenedConfirmDialog(
            title = "Restart the current print?",
            text = "OctoPrint will restart the paused job from the beginning.",
            confirmLabel = "Restart print",
            onDismiss = { confirmRestart = false },
            onConfirm = {
                confirmRestart = false
                viewModel.restartJob()
            },
        )
    }

    if (confirmCancel) {
        HardenedConfirmDialog(
            title = "Cancel the active print?",
            text = "OctoPrint will stop the current print job. This cannot be resumed.",
            confirmLabel = "Cancel print",
            onDismiss = { confirmCancel = false },
            onConfirm = {
                confirmCancel = false
                viewModel.cancelJob()
            },
        )
    }

    pendingDelete?.let { entry ->
        HardenedConfirmDialog(
            title = "Delete ${entry.name}?",
            text = if (entry.isFolder) {
                "OctoPrint can delete the folder only when it is empty."
            } else {
                "This permanently removes the file from OctoPrint storage."
            },
            confirmLabel = "Delete",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                viewModel.deleteFile(entry.path)
            },
        )
    }

    pendingPrint?.let { entry ->
        HardenedConfirmDialog(
            title = "Print ${entry.name}?",
            text = "OctoPrint will select this file and start printing immediately.",
            confirmLabel = "Start print",
            onDismiss = { pendingPrint = null },
            onConfirm = {
                pendingPrint = null
                viewModel.selectFile(entry.path, print = true)
            },
        )
    }
}

@Composable
private fun HardenedOctoPrintHeader(state: OctoPrintUiState, onRefresh: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isRefreshing || state.isUploading || state.authorizationPending) {
                CircularProgressIndicator(modifier = Modifier.height(28.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.serverInfo.displayText ?: state.config.baseUrl.ifBlank { "OctoPrint" },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(state.statusMessage, style = MaterialTheme.typography.bodySmall)
                state.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = onRefresh, enabled = state.isReady && !state.isRefreshing) {
                Text("Refresh")
            }
        }
        if (state.isUploading) {
            val progress = state.uploadProgress
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                buildString {
                    append(state.uploadFileName ?: "G-code")
                    progress?.let { append(" · ${(it * 100f).toInt()}%") }
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun HardenedStatusPage(
    state: OctoPrintUiState,
    localGcodePath: String?,
    suggestedFileName: String,
    viewModel: OctoPrintViewModel,
    onConfirmUploadPrint: (String) -> Unit,
    onConfirmStart: () -> Unit,
    onConfirmRestart: () -> Unit,
    onConfirmCancel: () -> Unit,
    modifier: Modifier,
) {
    var remoteDirectory by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!state.isReady) {
            Text("Configure OctoPrint on the Setup page.")
            return@Column
        }

        HardenedPowerCard(state, viewModel)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Printer", style = MaterialTheme.typography.titleMedium)
                Text("${state.printer.text} · serial ${state.connection.state}")
                state.connection.port?.let { port ->
                    Text(
                        "$port · ${state.connection.baudrate ?: 0} baud · ${state.connection.printerProfile.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.printer.tools.forEach { (name, temperature) ->
                    HardenedTemperatureLine(name.uppercase(Locale.US), temperature.actual, temperature.target)
                }
                state.printer.bed?.let { HardenedTemperatureLine("BED", it.actual, it.target) }
                state.printer.chamber?.let { HardenedTemperatureLine("CHAMBER", it.actual, it.target) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Current job", style = MaterialTheme.typography.titleMedium)
                Text(state.job.fileName ?: "No file selected")
                Text(state.job.state, style = MaterialTheme.typography.bodySmall)
                state.job.completionPercent?.let { completion ->
                    LinearProgressIndicator(
                        progress = { (completion / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${"%.1f".format(completion)}%", style = MaterialTheme.typography.labelSmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Elapsed ${hardenedDuration(state.job.elapsedSeconds)}")
                    Text("Left ${hardenedDuration(state.job.remainingSeconds)}")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        state.isPrinting -> Button(onClick = viewModel::pauseJob, modifier = Modifier.weight(1f)) {
                            Text("Pause")
                        }
                        state.isPaused -> Button(onClick = viewModel::resumeJob, modifier = Modifier.weight(1f)) {
                            Text("Resume")
                        }
                        state.job.fileName != null && !state.hasActiveJob && state.printer.operational -> Button(
                            onClick = onConfirmStart,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Start")
                        }
                    }
                    if (state.isPrinting || state.isPaused || state.printer.pausing) {
                        OutlinedButton(onClick = onConfirmCancel, modifier = Modifier.weight(1f)) {
                            Text("Cancel")
                        }
                    }
                    if (state.isPaused) {
                        OutlinedButton(onClick = onConfirmRestart, modifier = Modifier.weight(1f)) {
                            Text("Restart")
                        }
                    }
                }
                if (state.job.fileName != null && !state.hasActiveJob && !state.printer.operational) {
                    Text(
                        "Connect the printer before starting the file.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Send current slice", style = MaterialTheme.typography.titleMedium)
                Text(suggestedFileName, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = remoteDirectory,
                    onValueChange = { remoteDirectory = it },
                    label = { Text("OctoPrint folder (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            viewModel.uploadGcode(
                                localGcodePath,
                                suggestedFileName,
                                remoteDirectory,
                                OctoPrintUploadAction.UPLOAD,
                            )
                        },
                        enabled = localGcodePath != null && !state.isUploading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Upload")
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.uploadGcode(
                                localGcodePath,
                                suggestedFileName,
                                remoteDirectory,
                                OctoPrintUploadAction.UPLOAD_AND_SELECT,
                            )
                        },
                        enabled = localGcodePath != null && !state.isUploading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Select")
                    }
                    Button(
                        onClick = { onConfirmUploadPrint(remoteDirectory) },
                        enabled = localGcodePath != null && !state.isUploading && !state.hasActiveJob,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Print")
                    }
                }
                Text(
                    "Printing always requires confirmation. Verify the printer, bed, filament and first layer before starting remotely.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        HardenedWebcamCard(state)
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun HardenedPowerCard(state: OctoPrintUiState, viewModel: OctoPrintViewModel) {
    var confirmingPowerOff by remember { mutableStateOf(false) }
    val power = state.power
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Printer power", style = MaterialTheme.typography.titleMedium)
            if (!power.supported) {
                Text(
                    "The PSU Control plugin is not installed on this OctoPrint server, " +
                        "so the app cannot switch printer power.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }
            Text(
                when {
                    power.isPending -> "Switching…"
                    power.isOn == true -> "On"
                    power.isOn == false -> "Off"
                    else -> "Unknown"
                },
            )
            if (power.isOn == true && state.hasActiveJob) {
                Text(
                    "A print is running. Switching power off stops it immediately.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.setPrinterPower(true) },
                    enabled = !power.isPending && power.isOn != true,
                ) { Text("Turn on") }
                OutlinedButton(
                    onClick = { confirmingPowerOff = true },
                    enabled = !power.isPending && power.isOn != false,
                ) { Text("Turn off") }
            }
        }
    }
    if (confirmingPowerOff) {
        AlertDialog(
            onDismissRequest = { confirmingPowerOff = false },
            title = { Text("Cut printer power?") },
            text = {
                Text(
                    if (state.hasActiveJob) {
                        "A print is running. The printer stops immediately and the print cannot be resumed."
                    } else {
                        "The printer's mains power will be switched off."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingPowerOff = false
                        viewModel.setPrinterPower(false)
                    },
                ) { Text("Turn off") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingPowerOff = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun HardenedWebcamCard(state: OctoPrintUiState) {
    val bytes = state.webcamFrame
    var decodeFinished by remember(bytes) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = bytes) {
        if (bytes == null) {
            // produceState keeps its remembered value while the producer restarts, so
            // a cleared frame has to clear the image explicitly: otherwise the last
            // snapshot stays on screen under a state that says there is no frame.
            value = null
            decodeFinished = false
        } else {
            value = withContext(Dispatchers.Default) { decodeHardenedWebcamBitmap(bytes) }
            decodeFinished = true
        }
    }
    var fullscreen by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Webcam", style = MaterialTheme.typography.titleMedium)
            val currentBitmap = bitmap
            if (currentBitmap == null) {
                val webcamError = state.webcamError ?: if (bytes != null && decodeFinished) {
                    "Webcam snapshot could not be decoded as an image."
                } else {
                    null
                }
                if (webcamError != null) {
                    Text(
                        webcamError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        if (state.config.snapshotUrlOverride.isNotBlank() || state.webcam.snapshotUrl != null) {
                            "Waiting for a webcam snapshot…"
                        } else {
                            "No snapshot URL is available. Add one on the Setup page."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Image(
                    bitmap = currentBitmap.asImageBitmap(),
                    contentDescription = "OctoPrint webcam",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .graphicsLayer(
                            scaleX = if (state.webcam.flipHorizontal) -1f else 1f,
                            scaleY = if (state.webcam.flipVertical) -1f else 1f,
                            rotationZ = if (state.webcam.rotate90) 90f else 0f,
                        )
                        .clickable { fullscreen = true },
                )
            }
        }
    }
    if (fullscreen) {
        WebcamFullscreenDialog(
            bitmap = bitmap,
            webcam = state.webcam,
            onClose = { fullscreen = false },
        )
    }
}
/**
 * Fullscreen webcam viewer: pinch to zoom (1x to 6x), drag to pan and
 * double-tap to toggle between 1x and 2.5x. Live snapshots keep flowing
 * while the STATUS page (and thus the polling) stays active; flip and
 * rotation settings from OctoPrint are applied to the rendered image.
 */
@Composable
private fun WebcamFullscreenDialog(
    bitmap: Bitmap?,
    webcam: com.tomppi.enderslicer.octoprint.OctoPrintWebcamConfig,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        var zoom by remember { mutableStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        var viewport by remember { mutableStateOf(IntSize.Zero) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { viewport = it }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val newZoom = (zoom * zoomChange).coerceIn(1f, 6f)
                        if (newZoom != zoom) {
                            pan = (pan - centroid) * (newZoom / zoom) + centroid
                            zoom = newZoom
                        } else {
                            pan += panChange
                        }
                        pan = clampWebcamPan(
                            pan, zoom, viewport,
                            bitmap?.width ?: 0, bitmap?.height ?: 0,
                            webcam.rotate90,
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (zoom > 1.5f) {
                                zoom = 1f
                                pan = Offset.Zero
                            } else {
                                zoom = 2.5f
                                pan = Offset.Zero
                            }
                        },
                    )
                },
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "OctoPrint webcam (fullscreen)",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom * (if (webcam.flipHorizontal) -1f else 1f)
                            scaleY = zoom * (if (webcam.flipVertical) -1f else 1f)
                            rotationZ = if (webcam.rotate90) 90f else 0f
                            translationX = pan.x
                            translationY = pan.y
                        },
                )
            } else {
                Text(
                    "Waiting for a webcam snapshot...",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Text("Close", color = Color.White)
            }
            Text(
                "Pinch to zoom - drag to pan - double-tap to reset",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        }
    }
}

/**
 * Keeps the zoomed image inside the viewport: the pan is clamped to the
 * overflow on each axis for the image's fitted size (ContentScale.Fit),
 * accounting for a 90-degree rotation.
 */
private fun clampWebcamPan(
    pan: Offset,
    zoom: Float,
    viewport: IntSize,
    bitmapWidth: Int,
    bitmapHeight: Int,
    rotate90: Boolean,
): Offset {
    if (viewport.width <= 0 || viewport.height <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) {
        return Offset.Zero
    }
    val (drawnWidth, drawnHeight) = if (rotate90) {
        bitmapHeight.toFloat() to bitmapWidth.toFloat()
    } else {
        bitmapWidth.toFloat() to bitmapHeight.toFloat()
    }
    val fitScale = min(viewport.width / drawnWidth, viewport.height / drawnHeight)
    val maxX = ((zoom * drawnWidth * fitScale - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((zoom * drawnHeight * fitScale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}


@Composable
private fun HardenedFilesPage(
    state: OctoPrintUiState,
    viewModel: OctoPrintViewModel,
    onDelete: (OctoPrintFileEntry) -> Unit,
    onPrint: (OctoPrintFileEntry) -> Unit,
    modifier: Modifier,
) {
    var filter by rememberSaveable { mutableStateOf("") }
    var parentPath by rememberSaveable { mutableStateOf("") }
    var folderName by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<OctoPrintFileEntry?>(null) }
    var destination by rememberSaveable { mutableStateOf("") }
    val visibleFiles = state.files.filter {
        filter.isBlank() || it.name.contains(filter, true) || it.path.contains(filter, true)
    }

    LaunchedEffect(state.files) {
        if (selected != null && state.files.none { it.path == selected?.path }) selected = null
    }
    LaunchedEffect(state.isReady) {
        if (state.isReady && state.files.isEmpty()) viewModel.refreshFiles()
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("Filter files") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = viewModel::refreshFiles, enabled = state.isReady && !state.isFileListRefreshing) {
                Text("Reload")
            }
        }
        state.freeBytes?.let { Text("Free space: ${hardenedBytes(it)}", style = MaterialTheme.typography.bodySmall) }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            items(visibleFiles, key = { "${it.origin}:${it.path}" }) { entry ->
                Card(onClick = { selected = entry }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(9.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (entry.isFolder) "DIR" else "G", style = MaterialTheme.typography.labelMedium)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${"  ".repeat(entry.depth)}${entry.name}")
                            Text(
                                buildString {
                                    append(entry.path)
                                    entry.sizeBytes?.let { append(" · ${hardenedBytes(it)}") }
                                    entry.estimatedPrintSeconds?.let { append(" · ${hardenedDuration(it)}") }
                                    if (selected?.path == entry.path) append(" · selected")
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (!entry.isFolder) {
                            TextButton(onClick = { viewModel.selectFile(entry.path, false) }) { Text("Select") }
                            TextButton(onClick = { onPrint(entry) }, enabled = !state.hasActiveJob) { Text("Print") }
                        }
                        TextButton(onClick = { onDelete(entry) }) { Text("Delete") }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("File management", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = parentPath,
                        onValueChange = { parentPath = it },
                        label = { Text("Parent folder") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        label = { Text("New folder") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { viewModel.createFolder(parentPath, folderName) },
                        enabled = state.isReady && folderName.isNotBlank(),
                    ) { Text("Create") }
                }
                Text("Selected: ${selected?.path ?: "none"}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = destination,
                        onValueChange = { destination = it },
                        label = { Text("Destination folder") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { selected?.let { viewModel.copyFile(it.path, destination) } },
                        enabled = state.isReady && selected != null,
                    ) { Text("Copy") }
                    OutlinedButton(
                        onClick = { selected?.let { viewModel.moveFile(it.path, destination) } },
                        enabled = state.isReady && selected != null,
                    ) { Text("Move") }
                }
            }
        }
    }
}

@Composable
private fun HardenedControlPage(
    state: OctoPrintUiState,
    viewModel: OctoPrintViewModel,
    modifier: Modifier,
) {
    var port by rememberSaveable { mutableStateOf("") }
    var baudrate by rememberSaveable { mutableStateOf("") }
    var profile by rememberSaveable { mutableStateOf("") }
    var saveConnection by rememberSaveable { mutableStateOf(false) }
    var autoConnect by rememberSaveable { mutableStateOf(false) }
    var autoConnectEdited by rememberSaveable { mutableStateOf(false) }
    var jogStep by rememberSaveable { mutableStateOf(1.0) }
    var toolTarget by rememberSaveable { mutableStateOf("200") }
    var bedTarget by rememberSaveable { mutableStateOf("60") }
    var extrusion by rememberSaveable { mutableStateOf("5") }
    var feedRate by rememberSaveable { mutableStateOf("100") }
    var flowRate by rememberSaveable { mutableStateOf("100") }
    var command by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.connection) {
        if (port.isBlank()) port = state.connection.portPreference ?: state.connection.port.orEmpty()
        if (baudrate.isBlank()) baudrate = (state.connection.baudratePreference ?: state.connection.baudrate)?.toString().orEmpty()
        if (profile.isBlank()) profile = state.connection.printerProfilePreference ?: state.connection.printerProfile.orEmpty()
        if (!autoConnectEdited) autoConnect = state.connection.autoConnect
    }

    val operational = state.printer.operational
    val idle = operational && !state.hasActiveJob

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Serial connection", style = MaterialTheme.typography.titleMedium)
                Text("Available ports: ${state.connection.ports.joinToString().ifBlank { "not reported" }}", style = MaterialTheme.typography.labelSmall)
                Text("Available baud rates: ${state.connection.baudrates.joinToString().ifBlank { "not reported" }}", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(port, { port = it }, label = { Text("Port / AUTO") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        baudrate,
                        { baudrate = it.filter(Char::isDigit) },
                        label = { Text("Baud") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(profile, { profile = it }, label = { Text("Profile") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(saveConnection, { saveConnection = it })
                    Text("Save settings")
                    Checkbox(autoConnect, { autoConnect = it; autoConnectEdited = true })
                    Text("Auto-connect")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.connect(
                                port.takeIf(String::isNotBlank),
                                baudrate.toIntOrNull(),
                                profile.takeIf(String::isNotBlank),
                                saveConnection,
                                autoConnect,
                            )
                        },
                        enabled = state.isReady && !state.hasActiveJob,
                        modifier = Modifier.weight(1f),
                    ) { Text("Connect") }
                    OutlinedButton(
                        onClick = viewModel::disconnect,
                        enabled = state.isReady && !state.hasActiveJob,
                        modifier = Modifier.weight(1f),
                    ) { Text("Disconnect") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Motion", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.1, 1.0, 10.0).forEach { step ->
                        if (jogStep == step) Button(onClick = { jogStep = step }) { Text("$step mm") }
                        else OutlinedButton(onClick = { jogStep = step }) { Text("$step mm") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "X−" to { viewModel.jog(x = -jogStep) },
                        "X+" to { viewModel.jog(x = jogStep) },
                        "Y−" to { viewModel.jog(y = -jogStep) },
                        "Y+" to { viewModel.jog(y = jogStep) },
                        "Z−" to { viewModel.jog(z = -jogStep) },
                        "Z+" to { viewModel.jog(z = jogStep) },
                    ).forEach { (label, action) ->
                        OutlinedButton(onClick = action, enabled = idle) { Text(label) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { viewModel.home(setOf("x")) }, enabled = idle) { Text("Home X") }
                    OutlinedButton(onClick = { viewModel.home(setOf("y")) }, enabled = idle) { Text("Home Y") }
                    OutlinedButton(onClick = { viewModel.home(setOf("z")) }, enabled = idle) { Text("Home Z") }
                    Button(onClick = { viewModel.home(setOf("x", "y", "z")) }, enabled = idle) { Text("Home all") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Temperature and extrusion", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        toolTarget,
                        { toolTarget = it.filter { ch -> ch.isDigit() || ch == '-' } },
                        label = { Text("Tool 0 °C") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { toolTarget.toIntOrNull()?.let { viewModel.setToolTemperature("tool0", it) } }, enabled = operational) { Text("Set") }
                    OutlinedButton(onClick = { viewModel.setToolTemperature("tool0", 0) }, enabled = operational) { Text("Off") }
                    OutlinedTextField(
                        bedTarget,
                        { bedTarget = it.filter { ch -> ch.isDigit() || ch == '-' } },
                        label = { Text("Bed °C") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { bedTarget.toIntOrNull()?.let(viewModel::setBedTemperature) }, enabled = operational) { Text("Set") }
                    OutlinedButton(onClick = { viewModel.setBedTemperature(0) }, enabled = operational) { Text("Off") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        extrusion,
                        { extrusion = it.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' } },
                        label = { Text("Filament mm") },
                        singleLine = true,
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                    Button(
                        onClick = { extrusion.toDoubleOrNull()?.let { viewModel.extrude(kotlin.math.abs(it)) } },
                        enabled = idle,
                    ) { Text("Extrude") }
                    OutlinedButton(
                        onClick = { extrusion.toDoubleOrNull()?.let { viewModel.extrude(-kotlin.math.abs(it)) } },
                        enabled = idle,
                    ) { Text("Retract") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Live overrides", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(feedRate, { feedRate = it.filter(Char::isDigit) }, label = { Text("Feed %") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { feedRate.toIntOrNull()?.let(viewModel::setFeedRate) }, enabled = operational) { Text("Apply") }
                    OutlinedTextField(flowRate, { flowRate = it.filter(Char::isDigit) }, label = { Text("Flow %") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { flowRate.toIntOrNull()?.let(viewModel::setFlowRate) }, enabled = operational) { Text("Apply") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Terminal", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    command,
                    { if ('\n' !in it && '\r' !in it) command = it.take(256) },
                    label = { Text("Single G-code command") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { viewModel.sendGcode(command) },
                    enabled = command.isNotBlank() && idle,
                ) { Text("Send command") }
                Text(
                    "Raw commands can move axes, heat the printer, alter EEPROM or stop a print. Send only commands you understand.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun HardenedSetupPage(
    state: OctoPrintUiState,
    viewModel: OctoPrintViewModel,
    modifier: Modifier,
) {
    var baseUrl by rememberSaveable(state.config.baseUrl) { mutableStateOf(state.config.baseUrl) }
    var username by rememberSaveable(state.config.username) { mutableStateOf(state.config.username) }
    var apiKey by remember { mutableStateOf("") }
    var snapshotUrl by rememberSaveable(state.config.snapshotUrlOverride) { mutableStateOf(state.config.snapshotUrlOverride) }
    var pollSeconds by rememberSaveable(state.config.pollIntervalSeconds) { mutableStateOf(state.config.pollIntervalSeconds.toString()) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(state.config) {
        baseUrl = state.config.baseUrl
        username = state.config.username
        snapshotUrl = state.config.snapshotUrlOverride
        pollSeconds = state.config.pollIntervalSeconds.toString()
        apiKey = ""
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text("OctoPrint server", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            baseUrl,
            { value ->
                if (value != baseUrl) apiKey = ""
                baseUrl = value
            },
            label = { Text("Server URL or IP address") },
            placeholder = { Text("http://octopi.local") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(username, { username = it }, label = { Text("OctoPrint username (recommended)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            apiKey,
            { apiKey = it.trim() },
            label = { Text("User API key (manual fallback)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            snapshotUrl,
            { snapshotUrl = it },
            label = { Text("Webcam snapshot URL override (optional)") },
            placeholder = { Text("/webcam/?action=snapshot") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            pollSeconds,
            { pollSeconds = it.filter(Char::isDigit).take(2) },
            label = { Text("Idle refresh interval, seconds") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(240.dp),
        )
        Text(
            "Use Application Keys when possible. A replacement authorization does not remove the currently working key unless the new request succeeds.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (baseUrl.trim().startsWith("http://", true) || (baseUrl.isNotBlank() && "://" !in baseUrl)) {
            Text(
                "HTTP is unencrypted. Use it only on a trusted local network, or configure HTTPS for remote access.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { viewModel.testConnection(baseUrl, apiKey.takeIf(String::isNotBlank)) },
                enabled = baseUrl.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("Test server") }
            Button(
                onClick = {
                    viewModel.beginApplicationAuthorization(
                        baseUrl,
                        username,
                        snapshotUrl,
                        pollSeconds.toIntOrNull() ?: 3,
                    )
                },
                enabled = baseUrl.isNotBlank() && !state.authorizationPending,
                modifier = Modifier.weight(1f),
            ) { Text("Authorize app") }
            OutlinedButton(
                onClick = {
                    viewModel.saveManualConfiguration(
                        baseUrl,
                        username,
                        apiKey,
                        snapshotUrl,
                        pollSeconds.toIntOrNull() ?: 3,
                    )
                    apiKey = ""
                },
                enabled = baseUrl.isNotBlank() && apiKey.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("Save API key") }
        }
        if (state.authorizationPending) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp))
                Text("Waiting for approval in OctoPrint…")
                TextButton(onClick = viewModel::reopenAuthorizationDialog) { Text("Open approval page") }
                TextButton(onClick = viewModel::cancelAuthorization) { Text("Cancel") }
            }
        }
        if (state.isReady) {
            HorizontalDivider()
            Text("Connected configuration", style = MaterialTheme.typography.titleMedium)
            Text("Server: ${state.config.baseUrl}")
            Text("User: ${state.serverInfo.userName ?: state.config.username.ifBlank { "unknown" }}")
            Text("Server version: ${state.serverInfo.serverVersion ?: "unknown"}")
            Text(
                "API permissions: ${state.serverInfo.permissions.sorted().joinToString().ifBlank { "not reported" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { viewModel.saveSnapshotOverride(snapshotUrl) }) { Text("Save webcam override") }
            OutlinedButton(onClick = { confirmClear = true }) { Text("Remove OctoPrint configuration") }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }

    if (confirmClear) {
        HardenedConfirmDialog(
            title = "Remove OctoPrint configuration?",
            text = "The saved server details and encrypted API key will be deleted. Active OctoPrint requests will be disconnected.",
            confirmLabel = "Remove",
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                apiKey = ""
                viewModel.clearConfiguration()
            },
        )
    }
}

@Composable
private fun HardenedTemperatureLine(label: String, actual: Double?, target: Double?) {
    Text(
        "$label ${actual?.let { "%.1f".format(it) } ?: "—"} °C / ${target?.let { "%.0f".format(it) } ?: "—"} °C",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun HardenedConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back") } },
    )
}

private fun decodeHardenedWebcamBitmap(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null
    var sampleSize = 1
    while (
        width / sampleSize > HARDENED_WEBCAM_MAX_WIDTH ||
        height / sampleSize > HARDENED_WEBCAM_MAX_HEIGHT ||
        (width.toLong() / sampleSize) * (height.toLong() / sampleSize) > HARDENED_WEBCAM_MAX_PIXELS
    ) {
        if (sampleSize >= 128) return null
        sampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}

private fun hardenedDuration(seconds: Int?): String =
    if (seconds == null || seconds < 0) "—" else formatPrintTime(seconds)

private fun hardenedBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private const val HARDENED_WEBCAM_MAX_WIDTH = 1920
private const val HARDENED_WEBCAM_MAX_HEIGHT = 1080
private const val HARDENED_WEBCAM_MAX_PIXELS = 2_500_000L
