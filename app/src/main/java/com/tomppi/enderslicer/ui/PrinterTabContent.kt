package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.octoprint.OctoPrintUiState
import com.tomppi.enderslicer.printer.KlipperHostChoice
import com.tomppi.enderslicer.printer.KlipperHostChoiceStore
import com.tomppi.enderslicer.printer.KlipperHostMode
import com.tomppi.enderslicer.printer.MoonrakerTransport
import com.tomppi.enderslicer.octoprint.OctoPrintViewModel
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.ui.klipper.KlipperScreen

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
    val context = LocalContext.current
    val store = remember { KlipperHostChoiceStore(context) }
    val stored = remember { store.load() }
    var route by rememberSaveable {
        mutableStateOf(if (stored.isRemote) Route.PC.name else Route.DEVICE.name)
    }
    val selected = Route.valueOf(route)

    //
    // The tab is the choice: which host the app drives is decided here and read by the
    // repository when it opens its connection. Keeping the two in step is what makes the same
    // screens - the same KlipperScreen, unchanged - describe a printer on the other side of the
    // network.
    //
    LaunchedEffect(selected) {
        val wanted = if (selected == Route.PC) KlipperHostMode.PC else KlipperHostMode.DEVICE
        val choice = store.load()
        if (choice.mode != wanted) klipperViewModel.setHostChoice(choice.copy(mode = wanted))
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (option in Route.entries) {
                TextButton(onClick = { route = option.name }) {
                    Text(if (option == selected) "\u25B8 " + option.label else option.label)
                }
            }
        }
        when (selected) {
            Route.DEVICE -> KlipperScreen(
                state = klipperState,
                viewModel = klipperViewModel,
                localGcodePath = localGcodePath,
                suggestedFileName = suggestedFileName,
                modifier = Modifier.fillMaxSize(),
            )
            Route.PC -> Column(modifier = Modifier.fillMaxSize()) {
                PcHostCard(
                    state = klipperState,
                    stored = stored,
                    onConnect = { choice -> klipperViewModel.setHostChoice(choice) },
                )
                KlipperScreen(
                    state = klipperState,
                    viewModel = klipperViewModel,
                    localGcodePath = localGcodePath,
                    suggestedFileName = suggestedFileName,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Route.OCTOPRINT -> HardenedOctoPrintSheet(
                state = octoPrintState,
                localGcodePath = localGcodePath,
                suggestedFileName = suggestedFileName,
                viewModel = octoPrintViewModel,
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            )
        }
    }
}

/** The ways this app reaches a printer. */
private enum class Route(val label: String) {
    DEVICE("This device"),
    PC("PC Klipper"),
    OCTOPRINT("OctoPrint"),
}

/**
 * The computer running Klipper, and how to reach it.
 *
 * Shown above the same screens the device route uses, because they are the same screens: the
 * only thing that changes is the transport underneath. The printer has to be plugged into that
 * computer and this phone on the same network - the two are worth saying, because the failure
 * they prevent looks exactly like a broken app.
 */
@Composable
private fun PcHostCard(
    state: KlipperPrinterState,
    stored: KlipperHostChoice,
    onConnect: (KlipperHostChoice) -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(stored.host) }
    var port by rememberSaveable { mutableStateOf(stored.port.toString()) }
    var key by rememberSaveable { mutableStateOf(stored.apiKey) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(
            "Klipper on a computer, over Moonraker. The printer must be plugged into that " +
                "computer, and this phone on the same network.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.take(120) },
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { typed -> port = typed.filter { it.isDigit() }.take(5) },
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.take(120) },
            label = { Text("API key, if the host asks for one") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    onConnect(
                        KlipperHostChoice(
                            mode = KlipperHostMode.PC,
                            host = host.trim(),
                            port = port.toIntOrNull() ?: MoonrakerTransport.DEFAULT_PORT,
                            apiKey = key.trim(),
                        ),
                    )
                },
                enabled = host.isNotBlank(),
            ) {
                Text("Connect")
            }
            Text(
                text = when {
                    state.connected && state.remoteHost != null -> "Connected to " + state.remoteHost
                    state.connected -> "Connected to this device"
                    state.error != null -> state.error.orEmpty()
                    else -> "Not connected"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
