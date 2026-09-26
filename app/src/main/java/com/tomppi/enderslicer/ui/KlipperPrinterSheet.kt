package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.printer.KlipperPrint
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperViewModel

/**
 * The printer this device is driving, over the Klipper host running inside the app.
 *
 * Deliberately the same shape as the rest of the app's printer UI, and deliberately
 * showing the things a user needs before a print: whether the host is up, whether the
 * machine is ready, what the hotend and bed are doing, and where the head is.
 */
@Composable
internal fun KlipperPrinterSheet(
    state: KlipperPrinterState,
    viewModel: KlipperViewModel,
    localGcodePath: String?,
    suggestedFileName: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(state, viewModel)
        PrintCard(state, viewModel, localGcodePath, suggestedFileName)
        TemperatureCard(state, viewModel)
        PositionCard(state)
        ActionsCard(state, viewModel)
        state.error?.let { error ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Printer (this device)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    !state.connected -> "Host not reachable"
                    state.state == "ready" -> "Ready"
                    state.state == "shutdown" -> "Shutdown"
                    else -> state.state.replaceFirstChar { it.uppercase() }
                },
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    state.isReady -> Color(0xFF2E7D32)
                    state.state == "shutdown" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (state.stateMessage.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.stateMessage.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.connected) {
                Spacer(Modifier.height(12.dp))
                // The host is normally started by the printer being plugged in; this
                // is for when it is already plugged in, or was started before the app.
                OutlinedButton(onClick = { viewModel.startHost() }) {
                    Text("Start the printer host")
                }
            }
        }
    }
}

/**
 * The print the printer is running, or the one it could run.
 *
 * A sliced file is handed over by copying it into the directory the host's virtual SD
 * card reads, which is inside this app's own storage - there is no upload step and
 * nothing to configure.
 */
@Composable
private fun PrintCard(
    state: KlipperPrinterState,
    viewModel: KlipperViewModel,
    localGcodePath: String?,
    suggestedFileName: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Print", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            val printing = state.printFileName != null &&
                (state.isPrinting || state.isPaused)
            if (printing) {
                Text(state.printFileName!!, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = state.printState.orEmpty().replaceFirstChar { it.uppercase() } +
                        (state.printDurationSeconds?.let { d -> " · %.0f min".format(d / 60) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.printProgress?.let { progress ->
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0.0, 1.0).toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "%.0f%%".format(progress * 100),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.isPaused) {
                        OutlinedButton(onClick = { viewModel.resumePrint() }) { Text("Resume") }
                    } else {
                        OutlinedButton(onClick = { viewModel.pausePrint() }) { Text("Pause") }
                    }
                    OutlinedButton(onClick = { viewModel.cancelPrint() }) { Text("Cancel") }
                }
            } else {
                val path = localGcodePath
                if (path == null) {
                    Text(
                        "Nothing sliced yet. Slice a model and it appears here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        KlipperPrint.fileName(suggestedFileName),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.printFile(path, suggestedFileName) },
                        enabled = state.isReady,
                    ) { Text("Print this file") }
                }
                state.printState?.takeIf { it.isNotBlank() }?.let { last ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Last print: $last",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TemperatureCard(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Temperatures", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            TemperatureRow("Hotend", state.extruderTemperature, state.extruderTarget) {
                viewModel.setExtruderTemperature(it)
            }
            Spacer(Modifier.height(4.dp))
            TemperatureRow("Bed", state.bedTemperature, state.bedTarget) {
                viewModel.setBedTemperature(it)
            }
        }
    }
}

@Composable
private fun TemperatureRow(
    label: String,
    current: Double?,
    target: Double?,
    onSet: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (current == null) "-" else "%.1f °C · target %.0f °C".format(current, target ?: 0.0),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Off, and the two presets that cover most of what this printer does.
            TextButton(onClick = { onSet(0) }) { Text("Off") }
            TextButton(onClick = { onSet(if (label == "Bed") 60 else 200) }) { Text("PLA") }
            TextButton(onClick = { onSet(if (label == "Bed") 80 else 240) }) { Text("PETG") }
        }
    }
}

@Composable
private fun PositionCard(state: KlipperPrinterState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Position", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            val position = state.position
            Text(
                text = if (position.size < 3) "-"
                else "X %.1f  Y %.1f  Z %.1f".format(position[0], position[1], position[2]),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (state.isHomed) "Homed" else "Not homed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionsCard(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Actions", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.home() },
                    enabled = state.isReady,
                ) { Text("Home") }
                OutlinedButton(
                    onClick = { viewModel.firmwareRestart() },
                    enabled = state.connected,
                ) { Text("Restart firmware") }
            }
        }
    }
}
