package com.tomppi.enderslicer.ui.klipper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.printer.KlipperConfigFile
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperShaper
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.printer.canMeasureResonances
import com.tomppi.enderslicer.printer.shapers
import kotlinx.coroutines.launch

/**
 * Input shaping: the pattern the steppers are driven with, so that a machine's own ringing
 * cancels itself instead of appearing in the print.
 *
 * Two numbers per axis describe it - a shaper type and the frequency that type is tuned to -
 * and both are set by hand as often as they are measured: the numbers usually come from a
 * ringing test, from a calibration done elsewhere, or from the printer's own tuning, and the
 * effect of changing one is visible in the next print. An accelerometer makes finding them
 * easier and is not required to use them.
 *
 * What this screen cannot read is what the printer is *currently* using: klippy publishes no
 * status for the input shaper at all - its status object is empty - so the values shown here
 * are the ones in the printer's configuration, and the live ones have to be asked for, which
 * the printer answers in the console.
 */
@Composable
internal fun KlipperShapingTab(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    val scope = rememberCoroutineScope()
    val configured = state.shapers
    var x by remember(configured) { mutableStateOf(ShaperEdit.of(configured.firstOrNull { it.axis == "x" })) }
    var y by remember(configured) { mutableStateOf(ShaperEdit.of(configured.firstOrNull { it.axis == "y" })) }
    var confirmSave by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KlipperCard(title = "Input shaping", subtitle = "Why the printer is set up this way") {
            KlipperNote(
                "A move that stops at a corner rings, and the ringing prints as ripples beside " +
                    "the corner. Input shaping drives the steppers with a pattern that cancels " +
                    "that ring: a type, and the frequency to cancel. Wrong values make the " +
                    "ripples worse, so change one axis at a time and print something with " +
                    "corners.",
            )
        }

        ShaperCard(
            axis = "X",
            edit = x,
            enabled = state.isReady,
            onEdit = { x = it },
            onApply = { edit ->
                edit.frequency()?.let { frequency ->
                    viewModel.applyShaper("x", edit.type, frequency, edit.damping())
                }
            },
        )
        ShaperCard(
            axis = "Y",
            edit = y,
            enabled = state.isReady,
            onEdit = { y = it },
            onApply = { edit ->
                edit.frequency()?.let { frequency ->
                    viewModel.applyShaper("y", edit.type, frequency, edit.damping())
                }
            },
        )

        KlipperCard(title = "Saving it") {
            KlipperNote(
                "Applying changes what the printer is doing now; the host forgets it when it " +
                    "restarts. Saving writes both axes into [input_shaper] in printer.cfg, " +
                    "which is where a restart reads them from.",
            )
            Spacer(Modifier.height(8.dp))
            KlipperButtons {
                KlipperButton(
                    text = "Save and restart",
                    enabled = x.frequency() != null && y.frequency() != null,
                    onClick = { confirmSave = true },
                )
                KlipperButton("Ask the printer", enabled = state.connected) {
                    viewModel.reportShapers()
                }
            }
            Spacer(Modifier.height(8.dp))
            KlipperNote(
                "Ask the printer sends SET_INPUT_SHAPER with no values, which makes klippy " +
                    "report what it is using - including anything set since the last restart. " +
                    "The answer appears in the console.",
            )
        }

        if (!state.canMeasureResonances) {
            KlipperCard(title = "Finding the numbers", subtitle = "No accelerometer on this printer") {
                KlipperNote(
                    "Measuring the frequencies directly needs an accelerometer and a " +
                        "[resonance_tester] section, which this printer has neither of. The " +
                        "usual way without one is a ringing test: print a tall shape fast, " +
                        "measure the distance between the ripples it leaves, and divide the " +
                        "speed by that distance - the result is the frequency to put here. " +
                        "Klipper's own documentation has the test and the arithmetic.",
                )
            }
        }
    }

    if (confirmSave) {
        KlipperConfirmDialog(
            title = "Save input shaping?",
            text = "Both axes are written into printer.cfg and the host restarts to read " +
                "them. The printer is unavailable for a few seconds, and a print in progress " +
                "is lost.",
            confirmLabel = "Save and restart",
            onConfirm = {
                scope.launch {
                    val saved = viewModel.saveShapers(
                        listOf(
                            KlipperConfigFile.ShaperSetting("x", x.type, x.frequency() ?: 0.0, x.damping()),
                            KlipperConfigFile.ShaperSetting("y", y.type, y.frequency() ?: 0.0, y.damping()),
                        ),
                    )
                    if (saved) viewModel.restartHost()
                }
            },
            onDismiss = { confirmSave = false },
        )
    }
}

/** One axis: what it is set to, and the fields that change it. */
@Composable
private fun ShaperCard(
    axis: String,
    edit: ShaperEdit,
    enabled: Boolean,
    onEdit: (ShaperEdit) -> Unit,
    onApply: (ShaperEdit) -> Unit,
) {
    KlipperCard(title = "Axis $axis") {
        KlipperNote(
            "Each type has a lowest frequency that means anything: zv from 21 Hz, mzv from " +
                "23, zvd and ei from 29, 2hump_ei from 39, 3hump_ei from 48.",
        )
        Spacer(Modifier.height(8.dp))
        // The selected one is marked rather than styled: a chip that looks different is a
        // chip that has to be read anyway.
        KlipperButtons {
            SHAPER_TYPES.forEach { type ->
                KlipperButton(
                    text = if (type == edit.type) "• $type" else type,
                    onClick = { onEdit(edit.copy(type = type)) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KlipperNumberField(
                label = "Frequency",
                value = edit.frequencyText,
                onValueChange = { typed -> onEdit(edit.copy(frequencyText = typed.filter { it.isDigit() || it == '.' }.take(6))) },
                suffix = "Hz",
                enabled = enabled,
            )
            KlipperNumberField(
                label = "Damping",
                value = edit.dampingText,
                onValueChange = { typed -> onEdit(edit.copy(dampingText = typed.filter { it.isDigit() || it == '.' }.take(6))) },
                enabled = enabled,
            )
        }
        Spacer(Modifier.height(8.dp))
        KlipperButtons {
            KlipperButton(
                text = "Apply to axis $axis",
                enabled = enabled && edit.frequency() != null,
                onClick = { onApply(edit) },
            )
        }
        edit.frequency()?.let { frequency ->
            val minimum = SHAPER_MINIMUM[edit.type]
            if (minimum != null && frequency < minimum) {
                Spacer(Modifier.height(4.dp))
                KlipperNote(
                    "Below " + minimum + " Hz this shaper stops helping: it would push the " +
                        "ringing rather than cancel it.",
                )
            }
        }
    }
}

/** What a user is editing for one axis, before it is applied. */
private data class ShaperEdit(
    val type: String,
    val frequencyText: String,
    val dampingText: String,
) {
    fun frequency(): Double? = frequencyText.toDoubleOrNull()?.takeIf { it > 0.0 }

    fun damping(): Double? = dampingText.toDoubleOrNull()?.takeIf { it > 0.0 }

    companion object {
        fun of(shaper: KlipperShaper?): ShaperEdit = ShaperEdit(
            type = shaper?.type?.takeIf { it.isNotBlank() } ?: "mzv",
            frequencyText = shaper?.frequency?.let { "%.1f".format(it) }.orEmpty(),
            dampingText = shaper?.dampingRatio?.let { "%.3f".format(it) }.orEmpty(),
        )
    }
}

/** The shapers Klipper implements, from shaper_defs.py. */
private val SHAPER_TYPES = listOf("zv", "mzv", "zvd", "ei", "2hump_ei", "3hump_ei")

/** The frequency below which each of them stops being useful, also from shaper_defs.py. */
private val SHAPER_MINIMUM = mapOf(
    "zv" to 21,
    "mzv" to 23,
    "zvd" to 29,
    "ei" to 29,
    "2hump_ei" to 39,
    "3hump_ei" to 48,
)
