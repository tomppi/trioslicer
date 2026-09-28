package com.tomppi.enderslicer.ui.klipper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.printer.configSections
import com.tomppi.enderslicer.printer.configuredProbeOffset
import com.tomppi.enderslicer.printer.lastProbeQuery
import com.tomppi.enderslicer.printer.lastProbeResult
import com.tomppi.enderslicer.printer.manualProbeActive
import com.tomppi.enderslicer.printer.manualProbeZ
import com.tomppi.enderslicer.printer.probeName
import com.tomppi.enderslicer.printer.probeSection
import com.tomppi.enderslicer.printer.saveConfigPending
import com.tomppi.enderslicer.printer.zOffset

/**
 * The probe, and the two numbers it has.
 *
 * The first layer is set by the probe's own offset - `z_offset` in the printer's
 * configuration - and there is no way to calculate it: the probe finds the bed, and then
 * somebody brings the nozzle down onto a piece of paper and says where it stopped. That is
 * the whole calibration, and it is the one thing on a printer with a probe that cannot be
 * done without a hand on it.
 *
 * The second number is the live one: an offset applied on top, for correcting a print that
 * is already running. The two are easy to confuse - both are "the Z offset" - so this
 * screen shows them apart, with the one that persists first.
 */
@Composable
internal fun KlipperZProbeTab(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    var confirmCalibrate by remember { mutableStateOf(false) }
    val calibrating = state.manualProbeActive

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.configSections != null && state.probeSection == null) {
            KlipperCard(title = "Z probe") {
                KlipperNote(
                    "This printer's configuration has no [bltouch] or [probe] section, so " +
                        "it has no probe and nothing to calibrate here.",
                )
            }
            return@Column
        }

        KlipperCard(title = "The probe", subtitle = state.probeName ?: state.probeSection) {
            KlipperValue(
                label = "Probe offset",
                value = state.configuredProbeOffset?.let { "%.3f mm".format(it) } ?: "-",
            )
            KlipperNote(
                "What the printer's configuration has, and what a first layer is decided by.",
            )
            Spacer(Modifier.height(8.dp))
            KlipperValue(
                label = "Z offset in use now",
                value = "%.3f mm".format(state.zOffset ?: 0.0),
            )
            KlipperNote("Applied on top while printing, and forgotten when the host restarts.")
            state.lastProbeResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                KlipperValue("Last probe height", "%.3f mm".format(result))
            }
            state.lastProbeQuery?.let { query -> KlipperValue("Probe state", query) }
        }

        if (calibrating) {
            PaperCard(state, viewModel)
        } else {
            StartCalibrationCard(
                state = state,
                onHeat = { hotend, bed -> viewModel.setExtruderTemperature(hotend); viewModel.setBedTemperature(bed) },
                onHome = { viewModel.home() },
                onStart = { confirmCalibrate = true },
            )
        }

        if (state.saveConfigPending) {
            KlipperCard(
                title = "Waiting to be saved",
                subtitle = "klippy has a new value and cannot apply it itself",
            ) {
                KlipperNote(
                    "Saving writes it into printer.cfg and restarts the host. Nothing is " +
                        "lost: the configuration keeps what the printer has already saved.",
                )
                Spacer(Modifier.height(8.dp))
                KlipperButtons {
                    KlipperButton("Save and restart") { viewModel.saveConfig() }
                }
            }
        }

        KlipperCard(
            title = "Correcting a print",
            subtitle = "The live offset, for a first layer going down now",
        ) {
            KlipperNote(
                "This nudges everything up or down without touching the probe's own offset: " +
                    "too high - lines rounded and not sticking - is a smaller number, too low " +
                    "- flat and scraping - is a larger one.",
            )
            Spacer(Modifier.height(8.dp))
            KlipperButtons {
                KlipperButton("−0.05", enabled = state.isHomed) { viewModel.adjustZOffset(-0.05, true) }
                KlipperButton("−0.01", enabled = state.isHomed) { viewModel.adjustZOffset(-0.01, true) }
                KlipperButton("+0.01", enabled = state.isHomed) { viewModel.adjustZOffset(0.01, true) }
                KlipperButton("+0.05", enabled = state.isHomed) { viewModel.adjustZOffset(0.05, true) }
                KlipperButton("Reset") { viewModel.resetZOffset() }
            }
            Spacer(Modifier.height(8.dp))
            KlipperNote(
                "After calibrating the probe, reset this, or the correction from an earlier " +
                    "print is still in it.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmCalibrate) {
        KlipperConfirmDialog(
            title = "Calibrate the probe?",
            text = "The probe touches the bed, then stops above it so the nozzle can be " +
                "brought down onto a piece of paper. Have the paper ready, and heat the " +
                "nozzle and bed first: both grow when hot, and a cold calibration is out by " +
                "a tenth of a millimetre or more.",
            confirmLabel = "Start",
            onConfirm = { viewModel.calibrateProbe() },
            onDismiss = { confirmCalibrate = false },
        )
    }
}

/** Before the paper: heat it, home it, and start. */
@Composable
private fun StartCalibrationCard(
    state: KlipperPrinterState,
    onHeat: (Int, Int) -> Unit,
    onHome: () -> Unit,
    onStart: () -> Unit,
) {
    KlipperCard(title = "Calibrate the first layer") {
        KlipperNote(
            "The probe finds the bed; you bring the nozzle down onto a piece of paper and " +
                "say where it stopped. Heat first - the nozzle grows several hundredths of a " +
                "millimetre when it is at printing temperature.",
        )
        Spacer(Modifier.height(8.dp))
        KlipperButtons {
            KlipperButton("Heat for PLA", enabled = state.connected) { onHeat(200, 60) }
            KlipperButton("Heat for PETG", enabled = state.connected) { onHeat(240, 80) }
            KlipperButton("Cool down", enabled = state.connected) { onHeat(0, 0) }
        }
        Spacer(Modifier.height(4.dp))
        KlipperButtons {
            KlipperButton("Home", enabled = state.isReady, onClick = onHome)
            KlipperButton(
                text = "Start calibrating",
                enabled = state.isReady && state.isHomed,
                onClick = onStart,
            )
        }
        if (!state.isHomed) {
            Spacer(Modifier.height(4.dp))
            KlipperNote("The printer is not homed, and the probe cannot start from nowhere.")
        }
    }
}

/** During the calibration: the nozzle, the paper, and the four buttons that find it. */
@Composable
private fun PaperCard(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    KlipperCard(
        title = "Bring the nozzle to the paper",
        subtitle = "klippy is waiting for the nozzle to be set",
    ) {
        KlipperValue(
            label = "Nozzle height",
            value = state.manualProbeZ?.let { "%.3f mm".format(it) } ?: "-",
        )
        Spacer(Modifier.height(8.dp))
        KlipperNote(
            "Slide a sheet of paper under the nozzle and tap down until it drags with a " +
                "little resistance. Small steps near the end: a hundredth is the difference " +
                "between a first layer that sticks and one that does not.",
        )
        Spacer(Modifier.height(8.dp))
        KlipperButtons {
            KlipperButton("−0.05") { viewModel.testZ(-0.05) }
            KlipperButton("−0.01") { viewModel.testZ(-0.01) }
            KlipperButton("+0.01") { viewModel.testZ(0.01) }
            KlipperButton("+0.05") { viewModel.testZ(0.05) }
        }
        Spacer(Modifier.height(4.dp))
        KlipperButtons {
            KlipperButton("Accept") { viewModel.acceptProbeCalibration() }
            KlipperButton("Cancel") { viewModel.abortProbeCalibration() }
        }
        Spacer(Modifier.height(4.dp))
        KlipperNote(
            "Accept takes this position as the probe's offset; it is written to the " +
                "configuration when it is saved, and the printer needs a restart to use it.",
        )
    }
}
