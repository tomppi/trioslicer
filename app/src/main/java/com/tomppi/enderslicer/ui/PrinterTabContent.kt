package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.octoprint.OctoPrintUiState
import com.tomppi.enderslicer.octoprint.OctoPrintViewModel
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperViewModel

/**
 * The Print destination, which is where a printer is reached from.
 *
 * Two ways to reach one, and the built-in host is the default: it runs on this device
 * with nothing else installed, where OctoPrint means a server somewhere and an API
 * key. The choice is remembered so that a user who runs one of them does not have to
 * make it again every time.
 */
@Composable
internal fun PrinterTabContent(
    klipperState: KlipperPrinterState,
    klipperViewModel: KlipperViewModel,
    octoPrintState: OctoPrintUiState,
    octoPrintViewModel: OctoPrintViewModel,
    localGcodePath: String?,
    suggestedFileName: String,
    modifier: Modifier = Modifier,
) {
    var localPrinter by rememberSaveable { mutableStateOf(true) }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = { localPrinter = true }) {
                Text(if (localPrinter) "\u25B8 This device" else "This device")
            }
            TextButton(onClick = { localPrinter = false }) {
                Text(if (localPrinter) "OctoPrint" else "\u25B8 OctoPrint")
            }
        }
        if (localPrinter) {
            KlipperPrinterSheet(
                state = klipperState,
                viewModel = klipperViewModel,
                localGcodePath = localGcodePath,
                suggestedFileName = suggestedFileName,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            HardenedOctoPrintSheet(
                state = octoPrintState,
                localGcodePath = localGcodePath,
                suggestedFileName = suggestedFileName,
                viewModel = octoPrintViewModel,
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            )
        }
    }
}
