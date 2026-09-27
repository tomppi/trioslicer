package com.tomppi.enderslicer.ui.klipper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.printer.KlipperPrint
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.printer.displayMessage
import com.tomppi.enderslicer.printer.excludedObjects
import com.tomppi.enderslicer.printer.extrudeFactor
import com.tomppi.enderslicer.printer.fanSpeed
import com.tomppi.enderslicer.printer.heaters
import com.tomppi.enderslicer.printer.plateObjects
import com.tomppi.enderslicer.printer.printFilamentUsed
import com.tomppi.enderslicer.printer.printLayers
import com.tomppi.enderslicer.printer.speedFactor
import com.tomppi.enderslicer.printer.zOffset
import com.tomppi.enderslicer.ui.formatPrintTime
import kotlin.math.roundToInt

/**
 * What the machine is doing, and everything that changes it while it does.
 *
 * The screen a print is watched on: the job with its own numbers, the three factors a
 * user adjusts mid-print, the temperatures at a glance, where the head is, and the two
 * things that stop it. The panels that are about setting the machine up rather than
 * watching it have their own screens, and this one links to nothing - a dashboard that
 * is also a menu is how a user ends up hunting for the button they just used.
 */
@Composable
internal fun KlipperDashboardTab(
    state: KlipperPrinterState,
    viewModel: KlipperViewModel,
    localGcodePath: String?,
    suggestedFileName: String,
) {
    var confirmCancel by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmExclude by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HostCard(state, viewModel)
        JobCard(
            state = state,
            viewModel = viewModel,
            localGcodePath = localGcodePath,
            suggestedFileName = suggestedFileName,
            onCancel = { confirmCancel = true },
        )
        if (state.connected) {
            AdjustmentsCard(state, viewModel)
            TemperaturesCard(state, viewModel)
            ExcludedObjectsCard(state, onExclude = { confirmExclude = it })
        }
        PositionCard(state)
        TimingCard(state)
        DangerCard(state, onEmergencyStop = { confirmStop = true })
        state.error?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }

    if (confirmCancel) {
        KlipperConfirmDialog(
            title = "Stop this print?",
            text = "The printer stops where it is and the print cannot be resumed. " +
                "The head is parked and the heaters are left as they are.",
            confirmLabel = "Stop the print",
            destructive = true,
            onConfirm = { viewModel.cancelPrint() },
            onDismiss = { confirmCancel = false },
        )
    }
    if (confirmStop) {
        KlipperConfirmDialog(
            title = "Emergency stop?",
            text = "The micro-controller is halted immediately and the printer shuts down. " +
                "It needs a firmware restart before it will move again.",
            confirmLabel = "Emergency stop",
            destructive = true,
            onConfirm = { viewModel.emergencyStop() },
            onDismiss = { confirmStop = false },
        )
    }
    confirmExclude?.let { name ->
        KlipperConfirmDialog(
            title = "Skip $name?",
            text = "This object is left out of the print that is running. The rest of the " +
                "plate is printed as sliced.",
            confirmLabel = "Skip it",
            onConfirm = { viewModel.excludeObject(name) },
            onDismiss = { confirmExclude = null },
        )
    }
}

/** Whether the host is up, and what it said if it is not. */
@Composable
private fun HostCard(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    KlipperCard(title = "Printer", subtitle = "The Klipper host running in this app") {
        Text(
            text = when {
                !state.connected -> "Host not reachable"
                state.state == "ready" -> "Ready"
                state.state == "shutdown" -> "Shutdown"
                else -> state.state.replaceFirstChar { it.uppercase() }
            },
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                state.isReady -> READY_GREEN
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
            KlipperButtons {
                // The host is normally started by the printer being plugged in; this is
                // for when it is already plugged in, or was started before the app.
                KlipperButton("Start the printer host") { viewModel.startHost() }
            }
            state.hostLogTail?.let { tail ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "The host stopped with:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                KlipperMono(tail, color = MaterialTheme.colorScheme.error)
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
private fun JobCard(
    state: KlipperPrinterState,
    viewModel: KlipperViewModel,
    localGcodePath: String?,
    suggestedFileName: String,
    onCancel: () -> Unit,
) {
    val printing = state.printFileName != null && (state.isPrinting || state.isPaused)
    KlipperCard(
        title = "Print",
        subtitle = if (printing) state.printFileName else null,
    ) {
        if (printing) {
            Text(
                text = state.printState.orEmpty().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.isPaused) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.displayMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            state.printProgress?.let { progress ->
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                KlipperValue("Done", "%.1f%%".format(progress * 100))
            }
            val seconds = state.printDurationSeconds
            if (seconds != null) {
                KlipperValue("Printing for", formatPrintTime(seconds.roundToInt()))
                // Only worth showing once there is enough of the print to extrapolate
                // from: a minute in, the estimate is the noise in the progress figure.
                val progress = state.printProgress
                if (progress != null && progress > 0.02 && state.isPrinting) {
                    val remaining = seconds / progress - seconds
                    KlipperValue("Left", formatPrintTime(remaining.roundToInt()))
                }
            }
            state.printLayers?.let { layers ->
                KlipperValue("Layer", "${layers.current} of ${layers.total}")
            }
            filamentMetres(state)?.let { metres ->
                KlipperValue("Filament", "%.2f m".format(metres))
            }
            Spacer(Modifier.height(8.dp))
            KlipperButtons {
                if (state.isPaused) {
                    KlipperButton("Resume") { viewModel.resumePrint() }
                } else {
                    KlipperButton("Pause", enabled = state.isPrinting) { viewModel.pausePrint() }
                }
                KlipperButton("Stop", onClick = onCancel)
            }
        } else {
            val path = localGcodePath
            if (path == null) {
                KlipperNote("Nothing sliced yet. Slice a model and it appears here.")
            } else {
                KlipperValue("Ready to print", KlipperPrint.fileName(suggestedFileName))
                Spacer(Modifier.height(8.dp))
                KlipperButtons {
                    KlipperButton(
                        text = "Print this file",
                        enabled = state.isReady,
                        onClick = { viewModel.printFile(path, suggestedFileName) },
                    )
                }
                if (!state.isReady) {
                    Spacer(Modifier.height(4.dp))
                    KlipperNote("The printer is not ready, so this is held back until it is.")
                }
            }
            state.printState?.takeIf { it.isNotBlank() && it != "standby" }?.let { last ->
                Spacer(Modifier.height(4.dp))
                KlipperNote("Last print: $last")
            }
        }
    }
}

/** The three factors a print is adjusted by while it runs. */
@Composable
private fun AdjustmentsCard(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    KlipperCard(title = "Adjustments") {
        KlipperSlider(
            label = "Part fan",
            value = ((state.fanSpeed ?: 0.0) * 100).toFloat().coerceIn(0f, 100f),
            range = 0f..100f,
            valueText = "%.0f%%".format((state.fanSpeed ?: 0.0) * 100),
            onSet = { viewModel.setFan(it.roundToInt()) },
        )
        KlipperSlider(
            label = "Speed",
            value = ((state.speedFactor ?: 1.0) * 100).toFloat().coerceIn(SPEED_RANGE),
            range = SPEED_RANGE,
            valueText = "%.0f%%".format((state.speedFactor ?: 1.0) * 100),
            steps = 17,
            onSet = { viewModel.setSpeedFactor(it.roundToInt()) },
        )
        KlipperSlider(
            label = "Flow",
            value = ((state.extrudeFactor ?: 1.0) * 100).toFloat().coerceIn(FLOW_RANGE),
            range = FLOW_RANGE,
            valueText = "%.0f%%".format((state.extrudeFactor ?: 1.0) * 100),
            steps = 9,
            onSet = { viewModel.setExtrudeFactor(it.roundToInt()) },
        )
        Spacer(Modifier.height(4.dp))
        KlipperButtons {
            KlipperButton("Reset to 100%") {
                viewModel.setSpeedFactor(100)
                viewModel.setExtrudeFactor(100)
            }
            KlipperButton("Fan off") { viewModel.setFan(0) }
        }
    }
}

/** The temperatures, with the two presets most prints use. */
@Composable
private fun TemperaturesCard(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    KlipperCard(
        title = "Temperatures",
        trailing = { KlipperButton("Cool down") { viewModel.coolDown() } },
    ) {
        if (state.heaters.isEmpty()) {
            KlipperNote("The printer has not reported a temperature yet.")
            return@KlipperCard
        }
        state.heaters.forEach { heater ->
            HeaterReadoutRow(heater)
            if (heater.isHeater) {
                KlipperButtons {
                    KlipperButton("Off") { viewModel.setHeaterTemperature(heater.name, 0) }
                    KlipperButton("PLA") {
                        viewModel.setHeaterTemperature(heater.name, if (heater.name == "heater_bed") 60 else 200)
                    }
                    KlipperButton("PETG") {
                        viewModel.setHeaterTemperature(heater.name, if (heater.name == "heater_bed") 80 else 240)
                    }
                }
            }
        }
    }
}

/**
 * What can be left out of the print that is running.
 *
 * Only during a print with [exclude_object] configured, and only for the objects the
 * file declared: klippy refuses a name it has never been told about, which is a
 * mistake worth not being able to make.
 */
@Composable
private fun ExcludedObjectsCard(state: KlipperPrinterState, onExclude: (String) -> Unit) {
    val objects = state.plateObjects
    if (objects.isEmpty() || !state.isPrinting) return
    val excluded = state.excludedObjects
    KlipperCard(
        title = "Objects on the plate",
        subtitle = "Tap one to leave it out of this print",
    ) {
        KlipperButtons {
            objects.forEach { name ->
                val skipped = name in excluded
                KlipperButton(
                    text = if (skipped) "\u2717 $name" else name,
                    enabled = !skipped,
                    onClick = { onExclude(name) },
                )
            }
        }
    }
}

/** Where the head is, and whether it knows. */
@Composable
private fun PositionCard(state: KlipperPrinterState) {
    KlipperCard(title = "Position") {
        val position = state.position
        KlipperValue(
            "X Y Z",
            if (position.size < 3) "-"
            else "%.1f  %.1f  %.1f".format(position[0], position[1], position[2]),
        )
        KlipperValue("Homing", if (state.isHomed) "Homed" else "Not homed")
        state.zOffset?.let { offset -> KlipperValue("Z offset", "%.3f mm".format(offset)) }
    }
}

/**
 * The host link's margins, which is what decides whether this device can drive the
 * printer rather than the printer waiting on it.
 *
 * Shown only once klippy has written a stats line, which needs a micro-controller on
 * the other end: before that there is nothing to report and a row of dashes would be
 * worse than an absent card.
 */
@Composable
private fun TimingCard(state: KlipperPrinterState) {
    val timing = state.timing ?: return
    KlipperCard(title = "Host link") {
        // klippy's own buffer_time, against the marks it acts on.
        state.lookaheadSeconds?.let { lookahead ->
            KlipperValue(
                label = "Lookahead",
                value = "%.2f s".format(lookahead),
                // Judged by the state, which knows an idle printer has a negative
                // lookahead by construction and is not starving. Comparing the raw
                // figure here painted a ready machine red.
                valueColor = if (state.lookaheadIsHealthy) Color.Unspecified
                else MaterialTheme.colorScheme.error,
            )
        }
        val stalls = state.printStalls ?: 0
        if (stalls > 0) {
            KlipperValue("Stalled", "$stalls times", valueColor = MaterialTheme.colorScheme.error)
        }
        timing.roundTripSeconds?.let { KlipperValue("Round trip", "%.1f ms".format(it * 1000)) }
        timing.jitterSeconds?.let { KlipperValue("Jitter", "±%.1f ms".format(it * 1000)) }
        timing.retransmitTimeoutSeconds?.let { KlipperValue("Resend after", "%.0f ms".format(it * 1000)) }
        timing.headroom?.let { KlipperValue("Headroom", "%.0f×".format(it)) }
        timing.mcuAwake?.let { KlipperValue("Board busy", "%.0f%%".format(it * 100)) }
        val retransmits = timing.retransmittedBytes ?: 0
        if (retransmits > 0) {
            KlipperValue(
                "Retransmitted",
                "$retransmits bytes",
                valueColor = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The one action that has to be asked about first. */
@Composable
private fun DangerCard(state: KlipperPrinterState, onEmergencyStop: () -> Unit) {
    KlipperCard(title = "If something is wrong") {
        KlipperNote(
            "An emergency stop halts the micro-controller at once. Use it when the " +
                "machine is doing something it should not, not to end a print.",
        )
        Spacer(Modifier.height(8.dp))
        KlipperButtons {
            KlipperButton("Emergency stop", onClick = onEmergencyStop)
        }
    }
}

/** Filament used, in metres, which is how a spool is measured. */
private fun filamentMetres(state: KlipperPrinterState): Double? =
    state.printFilamentUsed?.div(1000.0)?.takeIf { it > 0.0 }

/** The two factors' own limits, which are Mainsail's. */
private val SPEED_RANGE = 20f..200f
private val FLOW_RANGE = 50f..150f

/** The green the app already uses for "this is fine". */
private val READY_GREEN = Color(0xFF2E7D32)
