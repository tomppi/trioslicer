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
import com.tomppi.enderslicer.printer.parseDecimal
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.printer.macros

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
    //
    // Moving the head during a print is not a nudge: the file's idea of where the tool is
    // stops matching the machine's, and the print carries on from the wrong place. So homing,
    // jogging and releasing the steppers wait for the print - pausing is what they are for -
    // while macros stay available, because a macro is how a user reaches their own printer.
    //
    val motionAllowed = ready && !state.isPrinting

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KlipperCard(title = "Home", subtitle = if (homed) "Homed" else "Not homed") {
            KlipperButtons {
                KlipperButton("All", enabled = motionAllowed) { viewModel.home() }
                KlipperButton("X", enabled = motionAllowed) { viewModel.home("X") }
                KlipperButton("Y", enabled = motionAllowed) { viewModel.home("Y") }
                KlipperButton("Z", enabled = motionAllowed) { viewModel.home("Z") }
                KlipperButton("X and Y", enabled = motionAllowed) { viewModel.home("X Y") }
            }
            if (!ready) {
                Spacer(Modifier.height(4.dp))
                KlipperNote("The printer is not ready, so it will not move.")
            } else if (state.isPrinting) {
                Spacer(Modifier.height(4.dp))
                KlipperNote(
                    "A print is running. Homing or jogging now would put the head out of step " +
                        "with the file - pause the print and the buttons come back.",
                )
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
                    // Per axis, because klippy reports homing per axis: after homing X and Y
                    // together - which this screen offers - the Z axis is the one that must
                    // not move, and the old all-or-nothing test had it backwards in both
                    // directions.
                    enabled = motionAllowed && state.homedAxes.contains(axis.lowercase()),
                    onJog = { direction ->
                        viewModel.jog(axis, direction * step, parseDecimal(feedrate)?.toInt() ?: 3000)
                    },
                )
            }
            if (!homed) {
                Spacer(Modifier.height(4.dp))
                KlipperNote(
                    "Each axis moves once it has been homed, and not before: X and Y can be " +
                        "homed together, Z on its own.",
                )
            }
        }


        KlipperCard(title = "The first layer") {
            KlipperNote(
                "The Z offset - both the probe's own and the live correction - is on the " +
                    "Z probe screen, with the calibration that sets it.",
            )
        }

        KlipperCard(title = "Steppers") {
            KlipperNote("Motors off lets the head be moved by hand; the machine is not homed afterwards.")
            Spacer(Modifier.height(8.dp))
            KlipperButtons {
                // Releasing the steppers mid-print lets the head be pushed out of position.
                KlipperButton("Motors off", enabled = motionAllowed) { viewModel.disableMotors() }
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
