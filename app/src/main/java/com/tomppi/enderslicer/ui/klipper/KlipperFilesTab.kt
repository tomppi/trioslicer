package com.tomppi.enderslicer.ui.klipper

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.printer.KlipperGcodeFile
import com.tomppi.enderslicer.printer.KlipperPrint
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.ui.formatPrintTime
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the printer can print, and what each file says about itself.
 *
 * The files are the app's own, in the directory the host's virtual SD card reads: there
 * is no upload step, so this list is the printer's list. What each row shows is read out
 * of the file's own header - the slicer that wrote it, the time it estimated, the
 * filament and the layer height - because a directory of names is not enough to choose
 * between two versions of the same model.
 */
@Composable
internal fun KlipperFilesTab(
    state: KlipperPrinterState,
    viewModel: KlipperViewModel,
    localGcodePath: String?,
    suggestedFileName: String,
) {
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<KlipperGcodeFile>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    var confirmPrint by remember { mutableStateOf<KlipperGcodeFile?>(null) }
    var confirmDelete by remember { mutableStateOf<KlipperGcodeFile?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    suspend fun refresh() {
        refreshing = true
        files = viewModel.listGcodeFiles()
        refreshing = false
    }

    LaunchedEffect(Unit) { refresh() }

    // The thumbnail of whichever file is open, decoded once for that file: this is the
    // only place a full image is ever held, so a directory of a hundred files costs
    // nothing to list.
    LaunchedEffect(selected) {
        thumbnail = selected?.let { name -> viewModel.thumbnailFor(name) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val sliced = localGcodePath
        if (sliced != null) {
            KlipperCard(title = "Just sliced", subtitle = "Not printed yet") {
                KlipperValue("File", KlipperPrint.fileName(suggestedFileName))
                Spacer(Modifier.height(8.dp))
                KlipperButtons {
                    KlipperButton("Print this file", enabled = state.isReady) {
                        viewModel.printFile(sliced, suggestedFileName)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (files.isEmpty()) "No files yet" else files.size.toString() + " files",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KlipperButtons {
                KlipperButton(if (refreshing) "Reading…" else "Refresh", enabled = !refreshing) {
                    scope.launch { refresh() }
                }
            }
        }

        if (files.isEmpty()) {
            Text(
                text = "Slice a model and print it once; it is copied into the printer's " +
                    "own directory and stays here afterwards.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(files, key = { it.name }) { file ->
                FileCard(
                    file = file,
                    open = selected == file.name,
                    thumbnail = if (selected == file.name) thumbnail else null,
                    printable = state.isReady,
                    onOpen = { selected = if (selected == file.name) null else file.name },
                    onPrint = { confirmPrint = file },
                    onDelete = { confirmDelete = file },
                )
            }
        }
    }

    confirmPrint?.let { file ->
        KlipperConfirmDialog(
            title = "Print " + file.name + "?",
            text = "The printer starts on this file now" +
                (state.printFileName?.takeIf { state.isPrinting }?.let { " and stops printing $it" } ?: "") + ".",
            confirmLabel = "Print",
            onConfirm = {
                scope.launch { viewModel.printGcodeFile(file.name) }
                selected = null
            },
            onDismiss = { confirmPrint = null },
        )
    }
    confirmDelete?.let { file ->
        KlipperConfirmDialog(
            title = "Delete " + file.name + "?",
            text = "The file is removed from this device. It cannot be recovered.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                scope.launch {
                    if (viewModel.deleteGcodeFile(file.name)) refresh()
                }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

/** One file: what it is, what it says about itself, and what can be done with it. */
@Composable
private fun FileCard(
    file: KlipperGcodeFile,
    open: Boolean,
    thumbnail: Bitmap?,
    printable: Boolean,
    onOpen: () -> Unit,
    onPrint: () -> Unit,
    onDelete: () -> Unit,
) {
    KlipperCard(
        title = file.name,
        subtitle = "%.1f MB · %s".format(
            file.sizeBytes / 1024.0 / 1024.0,
            DATE_FORMAT.format(Date(file.modifiedAtMillis)),
        ),
        trailing = { KlipperButton(if (open) "Close" else "Open", onClick = onOpen) },
    ) {
        file.slicer.takeIf { it.isNotBlank() }?.let { KlipperValue("Sliced by", it) }
        file.estimatedSeconds?.let { KlipperValue("Estimated", formatPrintTime(it)) }
        file.filamentGrams?.let { KlipperValue("Filament", "%.1f g".format(it)) }
        file.filamentMillimetres?.takeIf { file.filamentGrams == null }?.let {
            KlipperValue("Filament", "%.2f m".format(it / 1000))
        }
        file.layerHeight?.let { KlipperValue("Layer height", "%.2f mm".format(it)) }
        file.layerCount?.let { KlipperValue("Layers", it.toString()) }
        if (open && thumbnail != null) {
            Spacer(Modifier.height(8.dp))
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = "What this file looks like",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        KlipperButtons {
            KlipperButton("Print", enabled = printable, onClick = onPrint)
            KlipperButton("Delete", onClick = onDelete)
        }
    }
}

/** Short and unambiguous: a file list is read at a glance. */
private val DATE_FORMAT = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
