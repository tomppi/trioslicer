package com.tomppi.enderslicer.ui.klipper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.data.KampPreference
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.printer.obj

/**
 * KAMP, the adaptive meshing and purging macros the app's Klipper host runs.
 *
 * KAMP is a settings macro plus two behaviours: it replaces BED_MESH_CALIBRATE so the mesh
 * covers the ground the objects stand on instead of the whole bed, and it offers LINE_PURGE,
 * which purges beside the print rather than at a fixed corner. Both read the variables this
 * screen shows, and both are configured here rather than in the app because that is where
 * they live - on the printer, as part of its configuration.
 *
 * Two things belong to the file rather than the printer, and the screen says so instead of
 * offering them: whether a slice asks for a mesh at all (the slicer's own
 * "Adaptive meshing (Klipper)" switch), and whether the start script calls LINE_PURGE. A
 * value set here lasts until the host restarts; the defaults it starts with are in
 * KAMP_Settings.cfg beside the configuration.
 */
@Composable
internal fun KlipperKampTab(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    val settings = state.obj(KAMP_OBJECT)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Klipper Adaptive Meshing & Purging", style = MaterialTheme.typography.titleMedium)
                if (settings == null) {
                    // Two different situations read the same way if this only says "no KAMP": a
                    // printer whose configuration has none, and a host that has not answered yet
                    // - an MCU it cannot reach, a klippy still starting. The second is common
                    // enough (the printer is on one host's USB, not the other's) that saying so
                    // is worth the two lines.
                    Text(
                        if (state.state != "ready") {
                            "KAMP's settings are unread while klippy is ${state.state}: " +
                                (state.stateMessage?.lineSequence()?.firstOrNull()?.trim()
                                    ?: "no answer from the host yet") +
                                " A host that is not ready publishes no objects, so nothing on " +
                                "this screen can be read from it."
                        } else {
                            "This printer has no KAMP: its configuration does not define " +
                                "_KAMP_Settings. The app's own Klipper host installs KAMP and " +
                                "includes it at every start, so this screen fills in when that " +
                                "host is the one connected."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "Connected, and running with the values below. The phone host meshes " +
                            "adaptively only when a slice asks for a mesh.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // The switch that writes the mesh call lives with the setting it edits:
        // Machine settings -> Adaptive meshing (Klipper). This screen is the printer's
        // own KAMP values, read from the host that is connected.

        if (settings == null) return@Column

        KampCard("Adaptive mesh", "The mesh KAMP measures, fitted to the objects with this much room around them.") {
            KampNumber("Mesh margin", "mm added around the objects", settings.opt("mesh_margin"), "mesh_margin", viewModel)
            KampNumber("Fuzz amount", "mm of random spread, for nozzle probes", settings.opt("fuzz_amount"), "fuzz_amount", viewModel)
        }

        KampCard(
            "Adaptive purge",
            "What LINE_PURGE extrudes, beside the print. The start script has to call it, and " +
                "[extruder] max_extrude_cross_section must be 5 or more or KAMP skips the purge.",
        ) {
            KampNumber("Purge amount", "mm of filament", settings.opt("purge_amount"), "purge_amount", viewModel)
            KampNumber("Purge margin", "mm between purge and print", settings.opt("purge_margin"), "purge_margin", viewModel)
            KampNumber("Purge height", "mm above the bed", settings.opt("purge_height"), "purge_height", viewModel)
            KampNumber("Flow rate", "mm^3/s while purging", settings.opt("flow_rate"), "flow_rate", viewModel)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Verbose", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "KAMP reports what it adapted, and waits five seconds when a file " +
                            "declared no objects.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.optBoolean("verbose_enable"),
                    onCheckedChange = { enabled ->
                        viewModel.sendCommand(
                            "SET_GCODE_VARIABLE MACRO=$KAMP_MACRO VARIABLE=verbose_enable VALUE=" +
                                if (enabled) "True" else "False",
                        )
                    },
                )
            }
        }

        Text(
            "Values set here apply to the running host. The defaults it starts with are in " +
                "KAMP_Settings.cfg beside its printer.cfg.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * KAMP's settings, under both of the names the two protocols want.
 *
 * The object klippy publishes is `gcode_macro _KAMP_Settings`, which is what the state is
 * keyed by; `SET_GCODE_VARIABLE` takes the bare macro name instead. Reading the state with
 * the bare name answered null on every printer, healthy or not - which is how this screen
 * came to say "no KAMP" about a host that was running it.
 */
internal const val KAMP_OBJECT = "gcode_macro _KAMP_Settings"
private const val KAMP_MACRO = "_KAMP_Settings"

@Composable
private fun KampCard(title: String, explanation: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(explanation, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

/**
 * One KAMP variable: what it is now, and a field that sets it.
 *
 * The field starts empty rather than holding the current value: this is a variable on a
 * printer that is probably printing, and a field pre-filled with the running value is one
 * stray keystroke away from setting something nobody meant to change.
 */
@Composable
private fun KampNumber(
    label: String,
    hint: String,
    current: Any?,
    variable: String,
    viewModel: KlipperViewModel,
) {
    var text by rememberSaveable(variable) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                current?.toString() ?: "-",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    viewModel.sendCommand(
                        "SET_GCODE_VARIABLE MACRO=$KAMP_MACRO VARIABLE=$variable VALUE=$text",
                    )
                    text = ""
                },
                enabled = text.isNotBlank(),
            ) {
                Text("Set")
            }
        }
    }
}
