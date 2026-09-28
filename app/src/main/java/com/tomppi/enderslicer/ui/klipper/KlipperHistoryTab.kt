package com.tomppi.enderslicer.ui.klipper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomppi.enderslicer.printer.KlipperPrintRecord
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.ui.formatPrintTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What this printer has printed, as this app saw it.
 *
 * klippy's print_stats describes the print that is happening and then forgets it. The
 * records here are written by this app at the moment a print ends, which is the only
 * moment the numbers exist - so this is a history of prints started from this device,
 * and says so rather than pretending to be the printer's own.
 */
@Composable
internal fun KlipperHistoryTab(viewModel: KlipperViewModel) {
    val records by viewModel.history.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KlipperCard(title = "So far") {
            if (records.isEmpty()) {
                KlipperNote(
                    "Nothing yet. A print is written down here when it finishes, is " +
                        "stopped, or fails.",
                )
            } else {
                KlipperValue("Prints", records.size.toString())
                KlipperValue("Prints finished", records.count { it.outcome == "complete" }.toString())
                val seconds = records.sumOf { it.durationSeconds }
                KlipperValue("Total printing time", formatPrintTime(seconds.toInt()))
                val millimetres = records.sumOf { it.filamentMillimetres }
                if (millimetres > 0) {
                    KlipperValue("Filament used", "%.1f m".format(millimetres / 1000))
                }
            }
            Spacer(Modifier.height(8.dp))
            KlipperButtons {
                KlipperButton("Clear the list", enabled = records.isNotEmpty()) { confirmClear = true }
            }
        }

        records.forEach { record -> RecordCard(record) }
    }

    if (confirmClear) {
        KlipperConfirmDialog(
            title = "Clear the print history?",
            text = "The records are removed from this device. The files themselves are not " +
                "touched.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = { viewModel.clearHistory() },
            onDismiss = { confirmClear = false },
        )
    }
}

/** One finished print. */
@Composable
private fun RecordCard(record: KlipperPrintRecord) {
    KlipperCard(
        title = record.fileName.ifBlank { "A print with no file name" },
        subtitle = DATE_FORMAT.format(Date(record.startedAtMillis)),
    ) {
        KlipperValue("How it ended", record.outcome.replaceFirstChar { it.uppercase() })
        KlipperValue("Printing time", formatPrintTime(record.durationSeconds.toInt()))
        if (record.filamentMillimetres > 0) {
            KlipperValue("Filament used", "%.2f m".format(record.filamentMillimetres / 1000))
        }
        record.layers?.let { KlipperValue("Reached layer", it.toString()) }
    }
}

/** When a print was started, to the minute. */
private val DATE_FORMAT = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())
