package com.tomppi.enderslicer.ui.klipper

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.printer.KlipperMesh
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.printer.configSections
import com.tomppi.enderslicer.printer.mesh
import com.tomppi.enderslicer.printer.macros

/**
 * The bed, as the probe found it.
 *
 * A mesh is a grid of heights, and a grid of numbers is unreadable: the same values
 * drawn as a picture show a corner that is low, a bed that is tilted, or a probe that
 * failed in one place - which are the three things anyone looks at this for.
 *
 * The profiles are klippy's own saved meshes, so loading one is a command and saving one
 * is a name: nothing here is kept by the app.
 */
@Composable
internal fun KlipperMeshTab(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    var confirmCalibrate by remember { mutableStateOf(false) }
    var profileName by remember { mutableStateOf("") }
    val mesh = state.mesh
    val configured = state.configSections?.has("bed_mesh") == true

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!configured) {
            KlipperCard(title = "Bed mesh") {
                KlipperNote(
                    "This printer has no [bed_mesh] section, so it does not probe its bed " +
                        "and has no mesh to show.",
                )
            }
            return@Column
        }

        KlipperCard(
            title = "Bed mesh",
            subtitle = mesh?.profileName?.takeIf { it.isNotBlank() } ?: "No profile loaded",
        ) {
            if (mesh == null || !mesh.isLoaded) {
                KlipperNote(
                    "No mesh is loaded. Calibrating probes the bed and applies the result " +
                        "to this print; a saved profile can be loaded from here instead.",
                )
            } else {
                MeshPicture(mesh)
                Spacer(Modifier.height(8.dp))
                KlipperValue("Probed", "%d by %d points".format(mesh.shape.first, mesh.shape.second))
                val lowest = mesh.minimum
                val highest = mesh.maximum
                lowest?.let { KlipperValue("Lowest", "%.3f mm".format(it)) }
                highest?.let { KlipperValue("Highest", "%.3f mm".format(it)) }
                if (lowest != null && highest != null) {
                    KlipperValue("Range", "%.3f mm".format(highest - lowest))
                }
                KlipperValue(
                    "Area",
                    "%.0f,%.0f to %.0f,%.0f".format(
                        mesh.minX ?: 0.0, mesh.minY ?: 0.0, mesh.maxX ?: 0.0, mesh.maxY ?: 0.0,
                    ),
                )
            }
        }

        KlipperCard(title = "Calibrate") {
            KlipperNote(
                "The probe touches every point of the bed. The printer must be homed and " +
                    "the bed clear; it takes a few minutes.",
            )
            Spacer(Modifier.height(8.dp))
            KlipperButtons {
                KlipperButton("Home first", enabled = state.isReady) { viewModel.home() }
                KlipperButton("Probe the bed", enabled = state.isReady) { confirmCalibrate = true }
            }
        }

        KlipperCard(
            title = "Saved profiles",
            subtitle = (mesh?.profiles?.size ?: 0).toString() + " on the printer",
        ) {
            val profiles = mesh?.profiles.orEmpty()
            if (profiles.isEmpty()) {
                KlipperNote("None saved yet.")
            } else {
                profiles.forEach { name ->
                    KlipperValue(name, if (name == mesh?.profileName) "loaded" else "")
                }
                Spacer(Modifier.height(4.dp))
                KlipperButtons {
                    profiles.forEach { name ->
                        KlipperButton(name, enabled = state.isReady) { viewModel.meshProfile("LOAD", name) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                KlipperButtons {
                    profiles.forEach { name ->
                        KlipperButton("Remove " + name, enabled = state.isReady) {
                            viewModel.meshProfile("REMOVE", name)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KlipperTextField(
                    label = "Save as",
                    value = profileName,
                    onValueChange = { profileName = it },
                    enabled = state.isReady,
                )
                KlipperButton(
                    text = "Save",
                    enabled = state.isReady && profileName.isNotBlank() && mesh?.isLoaded == true,
                    onClick = { viewModel.meshProfile("SAVE", profileName.trim()) },
                )
            }
        }

        val meshMacros = state.macros.filter { macro ->
            LEVELING.any { macro.name.uppercase().contains(it) }
        }
        if (meshMacros.isNotEmpty()) {
            KlipperCard(
                title = "This printer's own",
                subtitle = "Its own leveling macros",
            ) {
                KlipperButtons {
                    meshMacros.forEach { macro ->
                        KlipperButton(macro.name, enabled = state.isReady) { viewModel.runMacro(macro.name) }
                    }
                }
            }
        }
    }

    if (confirmCalibrate) {
        KlipperConfirmDialog(
            title = "Probe the bed?",
            text = "The nozzle is moved around the bed and the probe touches it at every " +
                "point. Make sure nothing is on the plate and the printer is homed.",
            confirmLabel = "Probe",
            onConfirm = { viewModel.calibrateMesh() },
            onDismiss = { confirmCalibrate = false },
        )
    }
}

/**
 * The mesh as a picture.
 *
 * Scaled to the highest point rather than to a fixed range: a bed that varies by 0.05 mm
 * and one that varies by 2 mm both need to be readable, and the numbers underneath say
 * which of the two this is.
 */
@Composable
private fun MeshPicture(mesh: KlipperMesh) {
    val highest = mesh.maximum ?: return
    Canvas(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        val rows = mesh.points.size
        val columns = mesh.points.firstOrNull()?.size ?: return@Canvas
        if (rows == 0 || columns == 0) return@Canvas
        val cellWidth = size.width / columns
        val cellHeight = size.height / rows
        mesh.points.forEachIndexed { row, values ->
            values.forEachIndexed { column, value ->
                // The rows run front to back on the bed, the columns left to right; the
                // grid is drawn in that order so the picture matches the plate.
                drawRect(
                    color = meshColour(value, highest),
                    topLeft = Offset(column * cellWidth, row * cellHeight),
                    size = Size(cellWidth + 0.5f, cellHeight + 0.5f),
                )
            }
        }
    }
}

/** Low is blue, high is red, and the middle is where the nozzle wants to be. */
private fun meshColour(value: Double, highest: Double): Color {
    val fraction = (value / highest).coerceIn(-1.0, 1.0)
    return if (fraction >= 0) {
        Color(0xFF43A047).lerp(Color(0xFFE53935), fraction.toFloat())
    } else {
        Color(0xFF43A047).lerp(Color(0xFF1E88E5), (-fraction).toFloat())
    }
}

/** Blends two colours, which is all the mesh's colour scale needs. */
private fun Color.lerp(other: Color, fraction: Float): Color = Color(
    red = red + (other.red - red) * fraction,
    green = green + (other.green - green) * fraction,
    blue = blue + (other.blue - blue) * fraction,
    alpha = 1f,
)

/** What a macro name has to contain to be offered as a leveling one. */
private val LEVELING = listOf("LEVEL", "G29", "PROBE", "MESH", "TILT", "SCREW")
