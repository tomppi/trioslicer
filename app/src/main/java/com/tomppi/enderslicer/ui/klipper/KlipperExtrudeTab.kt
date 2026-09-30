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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.printer.configSections
import com.tomppi.enderslicer.printer.heaters
import com.tomppi.enderslicer.printer.macros
import com.tomppi.enderslicer.printer.obj
import com.tomppi.enderslicer.printer.KlipperScripts
import com.tomppi.enderslicer.printer.orDash
import com.tomppi.enderslicer.printer.parseDecimal
import com.tomppi.enderslicer.printer.rotationDistance

/**
 * Feeding filament: by hand, and the two settings that change how it is fed.
 *
 * The printer refuses to extrude a cold hotend, which is a refusal worth reading rather
 * than avoiding: the error comes back on the console, and the temperature is one screen
 * away. What this screen does do is say which temperature it is at, so that the refusal
 * is not a surprise.
 */
/** A field that takes digits and a decimal mark and nothing else. */
private fun digits(typed: String, limit: Int): String =
    typed.filter { it.isDigit() || it == '.' || it == ',' }.take(limit)

@Composable
internal fun KlipperExtrudeTab(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    var length by remember { mutableStateOf("10") }
    var feedrate by remember { mutableStateOf("300") }
    var advance by remember { mutableStateOf("") }
    var calibrationLength by rememberSaveable { mutableStateOf("100") }
    var markDistance by rememberSaveable { mutableStateOf("120") }
    var leftAfter by rememberSaveable { mutableStateOf("") }
    var askedPrinter by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Extruding by hand during a print adds filament the file did not ask for; paused, it is
    // how a filament change is finished. The retraction settings and the calibration stay
    // available either way - neither moves the extruder on its own.
    val canExtrude = state.isReady && !state.isPrinting

    val hotend = state.heaters.firstOrNull { it.name == "extruder" }
    val extruder = state.obj("extruder")
    val currentAdvance = extruder?.optDouble("pressure_advance")
    val hasFirmwareRetraction = state.configSections?.has("firmware_retraction") == true

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KlipperCard(
            title = "Filament",
            subtitle = hotend?.temperature?.let { "Hotend at %.1f °C".format(it) },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KlipperNumberField(
                    label = "Filament length",
                    value = length,
                    onValueChange = { typed -> length = typed.filter { it.isDigit() || it == '.' }.take(5) },
                    suffix = "mm",
                )
                KlipperNumberField(
                    label = "Feedrate",
                    value = feedrate,
                    onValueChange = { typed -> feedrate = typed.filter { it.isDigit() }.take(4) },
                    suffix = "mm/min",
                )
            }
            Spacer(Modifier.height(8.dp))
            KlipperButtons {
                val amount = parseDecimal(length) ?: 0.0
                // The field was inert: it said 300 while the command carried the
                // repository's 120, and editing it changed nothing.
                val speed = parseDecimal(feedrate)?.toInt() ?: 300
                KlipperButton("Extrude", enabled = canExtrude && amount > 0) {
                    viewModel.extrude(amount, speed)
                }
                KlipperButton("Retract", enabled = canExtrude && amount > 0) {
                    viewModel.extrude(-amount, speed)
                }
            }
            Spacer(Modifier.height(4.dp))
            KlipperNote(
                "Relative, inside a saved state: the file's own absolute or relative " +
                    "mode is left exactly as it was.",
            )
        }

        KlipperCard(
            title = "Extruder calibration",
            subtitle = "How far one turn actually moves the filament",
        ) {
            KlipperNote(
                "Mark the filament " + markDistance + " mm above the extruder, push " +
                    calibrationLength + " mm through, then measure from the extruder up to the " +
                    "mark again. Hot, or it will slip. That measurement is what the printer " +
                    "actually delivered, and the figure below replaces the configured one.",
            )
            KlipperValue("Rotation distance", state.rotationDistance.orDash(3) + " mm per rotation")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KlipperNumberField(
                    label = "Length to push",
                    value = calibrationLength,
                    onValueChange = { typed -> calibrationLength = digits(typed, 4) },
                    suffix = "mm",
                    enabled = state.isReady,
                )
                KlipperNumberField(
                    label = "Mark before",
                    value = markDistance,
                    onValueChange = { typed -> markDistance = digits(typed, 4) },
                    suffix = "mm",
                    enabled = state.isReady,
                )
                KlipperNumberField(
                    label = "Mark after",
                    value = leftAfter,
                    onValueChange = { typed -> leftAfter = digits(typed, 5) },
                    suffix = "mm",
                    enabled = state.isReady,
                )
            }
            Spacer(Modifier.height(8.dp))
            val requested = parseDecimal(calibrationLength)
            val mark = parseDecimal(markDistance)
            val left = parseDecimal(leftAfter)
            val moved = if (mark != null && left != null) mark - left else null
            val configured = state.rotationDistance
            val corrected = if (configured != null && requested != null && moved != null) {
                KlipperScripts.correctedRotationDistance(configured, requested, moved)
            } else {
                null
            }
            KlipperButtons {
                KlipperButton(
                    text = "Push it through",
                    enabled = state.isReady && (requested ?: 0.0) > 0.0 && !state.isPrinting,
                ) {
                    requested?.let { viewModel.extrudeForCalibration(it) }
                }
                KlipperButton("Ask the printer", enabled = state.isReady) {
                    viewModel.askRotationDistance()
                    askedPrinter = true
                }
            }
            if (askedPrinter) {
                KlipperNote("The answer is in the console; the value above is the configuration's.")
            }
            if (moved != null && corrected != null) {
                Spacer(Modifier.height(8.dp))
                KlipperValue("Filament moved", "%.2f mm".format(moved))
                KlipperValue("New rotation distance", "%.3f mm per rotation".format(corrected))
                if (moved <= 0.0) {
                    KlipperNote(
                        "The mark cannot end up further away than it started - check the two " +
                            "numbers.",
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (kotlin.math.abs(moved - (requested ?: 0.0)) > 0.05 * (requested ?: 1.0)) {
                    KlipperNote(
                        "That is more than a few per cent out, so repeat the measurement before " +
                            "saving it: two marks and a ruler are easy to get a millimetre wrong, " +
                            "and this is the figure every print is extruded with.",
                    )
                }
                Spacer(Modifier.height(8.dp))
                KlipperButtons {
                    KlipperButton("Apply now", enabled = state.isReady && !state.isPrinting && moved > 0.0) {
                        viewModel.applyRotationDistance(corrected)
                    }
                    KlipperButton("Save to printer.cfg", enabled = state.isReady && !state.isPrinting && moved > 0.0) {
                        scope.launch { viewModel.saveRotationDistance(corrected) }
                    }
                }
                KlipperNote(
                    "Applying takes effect at once and lasts until the host restarts; saving " +
                        "writes it into the configuration so the restart keeps it. Push the " +
                        "same length again afterwards to confirm the measurement. On the PC route a " +
                        "save also restarts that host, which ends a print in progress, so neither " +
                        "button is offered while the printer is printing."
                )
            }
        }

        KlipperCard(
            title = "Pressure advance",
            subtitle = currentAdvance?.let { "In use: %.4f s".format(it) },
        ) {
            KlipperNote(
                "How much the extruder leads a corner to make up for the filament's own " +
                    "springiness. Worth calibrating per filament.",
            )
            //
            // Set is the tuning action - live, immediate, and normally done while a print is
            // running. Save is the other half, and it is separate because it is not free:
            // klippy offers no way to save this one, so it means writing printer.cfg and
            // restarting the host, which would end the print being tuned.
            //
            KlipperNote(
                "Set applies it now and lasts until the host restarts. klippy has no way to " +
                    "save this setting, so Save writes it into printer.cfg - which restarts " +
                    "the host, and so waits until the print is finished.",
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KlipperNumberField(
                    label = "Pressure advance",
                    value = advance,
                    onValueChange = { typed -> advance = typed.filter { it.isDigit() || it == '.' }.take(6) },
                    suffix = "s",
                    enabled = state.isReady,
                )
                KlipperButton(
                    text = "Set",
                    enabled = state.isReady && parseDecimal(advance) != null,
                    onClick = { parseDecimal(advance)?.let { viewModel.setPressureAdvance(it) } },
                )
                KlipperButton(
                    text = "Save",
                    enabled = state.isReady && !state.isPrinting && parseDecimal(advance) != null,
                    onClick = {
                        parseDecimal(advance)?.let { value ->
                            scope.launch { viewModel.savePressureAdvance(value) }
                        }
                    },
                )
            }
            val own = state.macros.filter { it.name.contains("Press_Advance", ignoreCase = true) }
            if (own.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                KlipperButtons {
                    own.forEach { macro ->
                        KlipperButton(macro.name, enabled = state.isReady) {
                            viewModel.runMacro(macro.name)
                        }
                    }
                }
            }
        }

        KlipperCard(title = "Retraction") {
            if (!hasFirmwareRetraction) {
                // Said rather than hidden: the setting exists in Klipper, this printer
                // just does not use it, and a screen that quietly omits a control is a
                // screen a user hunts through.
                KlipperNote(
                    "This printer has no [firmware_retraction] section, so retraction is " +
                        "whatever the slicer puts in the file. Add the section to its " +
                        "configuration to set it from here.",
                )
            } else {
                var retractLength by remember { mutableStateOf("1.0") }
                var retractSpeed by remember { mutableStateOf("35") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KlipperNumberField(
                        label = "Retraction length",
                        value = retractLength,
                        onValueChange = { typed -> retractLength = typed.filter { it.isDigit() || it == '.' }.take(5) },
                        suffix = "mm",
                    )
                    KlipperNumberField(
                        label = "Retraction speed",
                        value = retractSpeed,
                        onValueChange = { typed -> retractSpeed = typed.filter { it.isDigit() }.take(4) },
                        suffix = "mm/s",
                    )
                }
                Spacer(Modifier.height(8.dp))
                KlipperButtons {
                    KlipperButton("Apply", enabled = state.isReady) {
                        val mm = parseDecimal(retractLength)
                        val speed = parseDecimal(retractSpeed)
                        if (mm != null && speed != null) viewModel.setRetraction(mm, speed)
                    }
                }
            }
        }
    }
}
