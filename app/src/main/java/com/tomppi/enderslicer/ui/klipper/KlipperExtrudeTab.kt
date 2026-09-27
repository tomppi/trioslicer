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
import androidx.compose.runtime.remember
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

/**
 * Feeding filament: by hand, and the two settings that change how it is fed.
 *
 * The printer refuses to extrude a cold hotend, which is a refusal worth reading rather
 * than avoiding: the error comes back on the console, and the temperature is one screen
 * away. What this screen does do is say which temperature it is at, so that the refusal
 * is not a surprise.
 */
@Composable
internal fun KlipperExtrudeTab(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    var length by remember { mutableStateOf("10") }
    var feedrate by remember { mutableStateOf("300") }
    var advance by remember { mutableStateOf("") }

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
                    label = "Length",
                    value = length,
                    onValueChange = { typed -> length = typed.filter { it.isDigit() || it == '.' }.take(5) },
                    suffix = "mm",
                )
                KlipperNumberField(
                    label = "Speed",
                    value = feedrate,
                    onValueChange = { typed -> feedrate = typed.filter { it.isDigit() }.take(4) },
                    suffix = "mm/min",
                )
            }
            Spacer(Modifier.height(8.dp))
            KlipperButtons {
                val amount = length.toDoubleOrNull() ?: 0.0
                KlipperButton("Extrude", enabled = state.isReady && amount > 0) {
                    viewModel.extrude(amount)
                }
                KlipperButton("Retract", enabled = state.isReady && amount > 0) {
                    viewModel.extrude(-amount)
                }
            }
            Spacer(Modifier.height(4.dp))
            KlipperNote(
                "Relative, inside a saved state: the file's own absolute or relative " +
                    "mode is left exactly as it was.",
            )
        }

        KlipperCard(
            title = "Pressure advance",
            subtitle = currentAdvance?.let { "%.4f".format(it) },
        ) {
            KlipperNote(
                "How much the extruder leads a corner to make up for the filament's own " +
                    "springiness. Worth calibrating per filament.",
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KlipperNumberField(
                    label = "Advance",
                    value = advance,
                    onValueChange = { typed -> advance = typed.filter { it.isDigit() || it == '.' }.take(6) },
                    enabled = state.isReady,
                )
                KlipperButton(
                    text = "Set",
                    enabled = state.isReady && advance.toDoubleOrNull() != null,
                    onClick = { advance.toDoubleOrNull()?.let { viewModel.setPressureAdvance(it) } },
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
                        label = "Length",
                        value = retractLength,
                        onValueChange = { typed -> retractLength = typed.filter { it.isDigit() || it == '.' }.take(5) },
                        suffix = "mm",
                    )
                    KlipperNumberField(
                        label = "Speed",
                        value = retractSpeed,
                        onValueChange = { typed -> retractSpeed = typed.filter { it.isDigit() }.take(4) },
                        suffix = "mm/s",
                    )
                }
                Spacer(Modifier.height(8.dp))
                KlipperButtons {
                    KlipperButton("Apply", enabled = state.isReady) {
                        val mm = retractLength.toDoubleOrNull()
                        val speed = retractSpeed.toDoubleOrNull()
                        if (mm != null && speed != null) viewModel.setRetraction(mm, speed)
                    }
                }
            }
        }
    }
}
