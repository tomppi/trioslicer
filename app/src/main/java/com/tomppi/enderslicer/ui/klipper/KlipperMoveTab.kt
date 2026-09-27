package com.tomppi.enderslicer.ui.klipper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.printer.macros
import com.tomppi.enderslicer.printer.zOffset

/**
 * Moving the machine by hand.
 *
 * Jogging is done in relative moves inside a saved state, so the mode the printer is in
 * is whatever it was before the button was pressed: a machine left in relative mode by
 * a jog that was interrupted would make the next absolute move a relative one, which is
 * a head into the bed.
 *
 * The Z offset is the one number here that a user changes to fix a print rather than to
 * move the machine, so it is its own panel with its own history of small nudges.
 */
@Composable
internal fun KlipperMoveTab(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    var step by remember { mutableStateOf(10.0) }
    var feedrate by remember { mutableStateOf("3000") }
    // Only needed while homing: klippy refuses a move it cannot place, so a button
    // that cannot work is better disabled with the reason on screen.
    val ready = state.isReady
    val homed = state.isHomed

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KlipperCard(title = "Home", subtitle = if (homed) "Homed" else "Not homed") {
            KlipperButtons {
                KlipperButton("All", enabled = ready) { viewModel.home() }
                KlipperButton("X", enabled = ready) { viewModel.home("X") }
                KlipperButton("Y", enabled = ready) { viewModel.home("Y") }
                KlipperButton("Z", enabled = ready) { viewModel.home("Z") }
                KlipperButton("X and Y", enabled = ready) { viewModel.home("X Y") }
            }
            if (!ready) {
                Spacer(Modifier.height(4.dp))
                KlipperNote("The printer is not ready, so it will not move.")
            }
        }

        KlipperCard(title = "Jog", subtitle = "Step %.1f mm".format(step)) {
            KlipperButtons {
                STEPS.forEach { size ->
                    KlipperButton(
                        text = if (size < 1) "%.1f".format(size) else "%.0f".format(size),
                        onClick = { step = size },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            KlipperValue("Feedrate", "$feedrate mm/min")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KlipperNumberField(
                    label = "Feedrate",
                    value = feedrate,
                    onValueChange = { typed -> feedrate = typed.filter { it.isDigit() }.take(4) },
                    suffix = "mm/min",
                )
            }
            Spacer(Modifier.height(8.dp))
            val rate = feedrate.toIntOrNull() ?: 3000
            AXES.forEach { axis ->
                AxisRow(
                    axis = axis,
                    state = state,
                    enabled = ready && (axis == "Z" || homed),
                    onJog = { direction -> viewModel.jog(axis, direction * step) },
                    feedrate = rate,
                )
            }
            if (!homed) {
                Spacer(Modifier.height(4.dp))
                KlipperNote("X and Y move once the machine is homed; Z moves either way.")
            }
        }

        KlipperCard(title = "Z offset", subtitle = state.zOffset?.let { "%.3f mm".format(it) } ?: "-") {
            KlipperNote(
                "Nudges the whole print up or down without moving the machine's own zero. " +
                    "The printer is moved to show the change when it is homed.",
            )
            Spacer(Modifier.height(8.dp))
            KlipperButtons {
                KlipperButton("−0.05", enabled = homed) { viewModel.adjustZOffset(-0.05, homed) }
                KlipperButton("−0.01", enabled = homed) { viewModel.adjustZOffset(-0.01, homed) }
                KlipperButton("+0.01", enabled = homed) { viewModel.adjustZOffset(0.01, homed) }
                KlipperButton("+0.05", enabled = homed) { viewModel.adjustZOffset(0.05, homed) }
                KlipperButton("Reset") { viewModel.resetZOffset() }
            }
        }

        KlipperCard(title = "Steppers") {
            KlipperNote("Motors off lets the head be moved by hand; the machine is not homed afterwards.")
            Spacer(Modifier.height(8.dp))
            KlipperButtons {
                KlipperButton("Motors off", enabled = ready) { viewModel.disableMotors() }
                KlipperButton("Motors on", enabled = ready) { viewModel.enableMotors() }
            }
        }

        val leveling = state.macros.filter { macro ->
            LEVELING.any { macro.name.uppercase().contains(it) }
        }
        if (leveling.isNotEmpty()) {
            KlipperCard(
                title = "This printer's own",
                subtitle = "Macros its configuration defines for leveling",
            ) {
                KlipperButtons {
                    leveling.forEach { macro ->
                        KlipperButton(macro.name, enabled = ready) { viewModel.runMacro(macro.name) }
                    }
                }
            }
        }
    }
}

/** One axis: where it is, and a button either way. */
@Composable
private fun AxisRow(
    axis: String,
    state: KlipperPrinterState,
    enabled: Boolean,
    feedrate: Int,
    onJog: (Double) -> Unit,
) {
    val index = AXES.indexOf(axis)
    val position = state.position.getOrNull(index)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = axis,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = position?.let { "%.1f".format(it) } ?: "-",
            style = MaterialTheme.typography.bodyMedium,
        )
        KlipperButton("−", enabled = enabled) { onJog(-1.0) }
        KlipperButton("+", enabled = enabled) { onJog(1.0) }
    }
}

/** The axes, in the order klippy reports a position. */
private val AXES = listOf("X", "Y", "Z")

/** The steps a jog is made in, from a nudge to a move across the bed. */
private val STEPS = listOf(0.1, 1.0, 10.0, 50.0)

/** What a macro name has to contain to be offered as a leveling one. */
private val LEVELING = listOf("LEVEL", "G29", "PROBE", "MESH", "TILT", "SCREW")
