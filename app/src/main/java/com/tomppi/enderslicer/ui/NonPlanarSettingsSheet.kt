package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.model.SlicerEngine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.nonplanar.NonPlanarSettings
import com.tomppi.enderslicer.nonplanar.NonPlanarSettingsStore

@Composable
internal fun NonPlanarSettingsSheet(
    initial: NonPlanarSettings,
    layerHeightMm: Double,
    nozzleDiameterMm: Double,
    engine: SlicerEngine,
    onSave: (NonPlanarSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var enabled by rememberSaveable(initial.enabled) { mutableStateOf(initial.enabled) }
    var slope by rememberSaveable(initial.maximumSlopeDegrees) { mutableStateOf(initial.maximumSlopeDegrees.toString()) }
    var clearanceAngle by rememberSaveable(initial.nozzleClearanceAngleDegrees) {
        mutableStateOf(initial.nozzleClearanceAngleDegrees.toString())
    }
    var clearanceHeight by rememberSaveable(initial.nozzleClearanceHeightMm) {
        mutableStateOf(initial.nozzleClearanceHeightMm.toString())
    }
    var maximumLift by rememberSaveable(initial.maximumLiftMm) { mutableStateOf(initial.maximumLiftMm.toString()) }
    var nozzleAngle by rememberSaveable(initial.nozzleAngleDegrees) { mutableStateOf(initial.nozzleAngleDegrees.toString()) }
    var nozzleProtrusion by rememberSaveable(initial.nozzleProtrusionMm) {
        mutableStateOf(initial.nozzleProtrusionMm.toString())
    }
    var blockWidth by rememberSaveable(initial.heatingBlockWidthMm) {
        mutableStateOf(initial.heatingBlockWidthMm.toString())
    }
    var blockDepth by rememberSaveable(initial.heatingBlockDepthMm) {
        mutableStateOf(initial.heatingBlockDepthMm.toString())
    }
    var blockOffsetX by rememberSaveable(initial.heatingBlockOffsetXmm) {
        mutableStateOf(initial.heatingBlockOffsetXmm.toString())
    }
    var blockOffsetY by rememberSaveable(initial.heatingBlockOffsetYmm) {
        mutableStateOf(initial.heatingBlockOffsetYmm.toString())
    }
    var maximumZSpeed by rememberSaveable(initial.maximumZSpeedMmPerSecond) {
        mutableStateOf(initial.maximumZSpeedMmPerSecond.toString())
    }
    var pauseAfterProbe by rememberSaveable(initial.pauseAfterProbe) { mutableStateOf(initial.pauseAfterProbe) }
    var conformalShells by rememberSaveable(initial.conformalShellLayers) {
        mutableStateOf(initial.conformalShellLayers.toString())
    }

    val parsedSlope = parseDecimal(slope, NonPlanarSettings.MIN_SLOPE_DEGREES, NonPlanarSettings.MAX_SLOPE_DEGREES)
    val parsedClearanceAngle = parseDecimal(
        clearanceAngle,
        NonPlanarSettings.MIN_CLEARANCE_ANGLE_DEGREES,
        NonPlanarSettings.MAX_CLEARANCE_ANGLE_DEGREES,
    )
    val parsedClearanceHeight = parseDecimal(
        clearanceHeight,
        NonPlanarSettings.MIN_CLEARANCE_HEIGHT_MM,
        NonPlanarSettings.MAX_CLEARANCE_HEIGHT_MM,
    )
    val parsedMaximumLift = parseDecimal(
        maximumLift,
        NonPlanarSettings.MIN_MAXIMUM_LIFT_MM,
        NonPlanarSettings.MAX_MAXIMUM_LIFT_MM,
    )
    val parsedNozzleAngle = parseDecimal(
        nozzleAngle,
        NonPlanarSettings.MIN_NOZZLE_ANGLE_DEGREES,
        NonPlanarSettings.MAX_NOZZLE_ANGLE_DEGREES,
    )
    val parsedNozzleProtrusion = parseDecimal(
        nozzleProtrusion,
        NonPlanarSettings.MIN_NOZZLE_PROTRUSION_MM,
        NonPlanarSettings.MAX_NOZZLE_PROTRUSION_MM,
    )
    val parsedBlockWidth = parseDecimal(
        blockWidth,
        NonPlanarSettings.MIN_BLOCK_SIZE_MM,
        NonPlanarSettings.MAX_BLOCK_SIZE_MM,
    )
    val parsedBlockDepth = parseDecimal(
        blockDepth,
        NonPlanarSettings.MIN_BLOCK_SIZE_MM,
        NonPlanarSettings.MAX_BLOCK_SIZE_MM,
    )
    val parsedBlockOffsetX = parseDecimal(
        blockOffsetX,
        -NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
        NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
    )
    val parsedBlockOffsetY = parseDecimal(
        blockOffsetY,
        -NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
        NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
    )
    val parsedMaximumZSpeed = parseDecimal(
        maximumZSpeed,
        NonPlanarSettings.MIN_Z_SPEED_MM_PER_SECOND,
        NonPlanarSettings.MAX_Z_SPEED_MM_PER_SECOND,
    )
    val parsedConformalShells = parseInteger(
        conformalShells,
        NonPlanarSettings.MIN_CONFORMAL_SHELL_LAYERS,
        NonPlanarSettings.MAX_CONFORMAL_SHELL_LAYERS,
    )
    val draft = if (
        parsedSlope != null &&
        parsedClearanceAngle != null && parsedClearanceHeight != null && parsedMaximumLift != null &&
        parsedNozzleAngle != null && parsedNozzleProtrusion != null && parsedBlockWidth != null &&
        parsedBlockDepth != null && parsedBlockOffsetX != null && parsedBlockOffsetY != null &&
        parsedMaximumZSpeed != null && parsedConformalShells != null
    ) {
        NonPlanarSettings(
            enabled = enabled,
            maximumSlopeDegrees = parsedSlope,
            nozzleClearanceAngleDegrees = parsedClearanceAngle,
            nozzleClearanceHeightMm = parsedClearanceHeight,
            maximumLiftMm = parsedMaximumLift,
            nozzleAngleDegrees = parsedNozzleAngle,
            nozzleProtrusionMm = parsedNozzleProtrusion,
            heatingBlockWidthMm = parsedBlockWidth,
            heatingBlockDepthMm = parsedBlockDepth,
            heatingBlockOffsetXmm = parsedBlockOffsetX,
            heatingBlockOffsetYmm = parsedBlockOffsetY,
            maximumZSpeedMmPerSecond = parsedMaximumZSpeed,
            pauseAfterProbe = pauseAfterProbe,
            conformalShellLayers = parsedConformalShells,
        )
    } else {
        null
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Non Planar", style = MaterialTheme.typography.headlineSmall)
        // Which implementation will actually run depends on the active engine, and
        // that is not something the user should have to discover from the G-code.
        when (engine) {
            SlicerEngine.ORCA -> Text(
                "OrcaSlicer slices this itself: non-planar runs as Z-layer contouring " +
                    "(zaa_enabled), which varies Z inside a layer so top-facing surfaces follow " +
                    "the model. The relief-field and hot-end clearance options below belong to " +
                    "the CuraEngine path and are ignored here; Z contouring's own minimum Z and " +
                    "perimeter-height values stay at their OrcaSlicer defaults unless All " +
                    "settings overrides them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            SlicerEngine.PRUSA -> Text(
                "PrusaSlicer has no non-planar slicing. Slicing while this is enabled is " +
                    "refused instead of silently producing flat G-code; switch to OrcaSlicer or " +
                    "CuraEngine to use it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            SlicerEngine.CURA -> Unit
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(NonPlanarSettingsStore.BACKEND_NAME, style = MaterialTheme.typography.titleMedium)
                Text("Ready · fully offline · Android ARM64", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "The displayed STL is sliced exactly as shown, then the top toolpaths are projected straight down onto the real 3D surface: the nozzle follows it continuously, diving below the layer plane to the thinnest part of the model and climbing to the thickest. The stair steps those shells replace are removed from the planar layers, the measured hot-end volume is swept along the result, and Z speed is limited to keep the firmware in control.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Active Cura layer height: %.3f mm · nozzle: %.2f mm".format(layerHeightMm, nozzleDiameterMm),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        SettingSwitch(
            title = "Enable non-planar printing",
            description = "Project the top layers onto the real surface for the next slice",
            checked = enabled,
            onChecked = { enabled = it },
        )
        IntegerSettingField(
            "Conformal shells (top layers replaced)",
            conformalShells,
            NonPlanarSettings.MIN_CONFORMAL_SHELL_LAYERS,
            NonPlanarSettings.MAX_CONFORMAL_SHELL_LAYERS,
            onText = { conformalShells = it },
        )
        DecimalSettingField(
            "Maximum surface slope (degrees)",
            slope,
            NonPlanarSettings.MIN_SLOPE_DEGREES,
            NonPlanarSettings.MAX_SLOPE_DEGREES,
            onText = { slope = it },
        )
        DecimalSettingField(
            "Nozzle clearance angle from horizontal (degrees)",
            clearanceAngle,
            NonPlanarSettings.MIN_CLEARANCE_ANGLE_DEGREES,
            NonPlanarSettings.MAX_CLEARANCE_ANGLE_DEGREES,
            onText = { clearanceAngle = it },
        )
        DecimalSettingField(
            "Block cone height (mm)",
            clearanceHeight,
            NonPlanarSettings.MIN_CLEARANCE_HEIGHT_MM,
            NonPlanarSettings.MAX_CLEARANCE_HEIGHT_MM,
            onText = { clearanceHeight = it },
        )
        DecimalSettingField(
            "Maximum surface span (mm)",
            maximumLift,
            NonPlanarSettings.MIN_MAXIMUM_LIFT_MM,
            NonPlanarSettings.MAX_MAXIMUM_LIFT_MM,
            onText = { maximumLift = it },
        )
        Text(
            "The largest allowed rise between the thinnest and thickest point of a curved region. Surfaces taller than this stay planar.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DecimalSettingField(
            "Nozzle taper angle from horizontal (degrees)",
            nozzleAngle,
            NonPlanarSettings.MIN_NOZZLE_ANGLE_DEGREES,
            NonPlanarSettings.MAX_NOZZLE_ANGLE_DEGREES,
            onText = { nozzleAngle = it },
        )
        DecimalSettingField(
            "Nozzle protrusion (mm)",
            nozzleProtrusion,
            NonPlanarSettings.MIN_NOZZLE_PROTRUSION_MM,
            NonPlanarSettings.MAX_NOZZLE_PROTRUSION_MM,
            onText = { nozzleProtrusion = it },
        )
        DecimalSettingField(
            "Heating block width (mm)",
            blockWidth,
            NonPlanarSettings.MIN_BLOCK_SIZE_MM,
            NonPlanarSettings.MAX_BLOCK_SIZE_MM,
            onText = { blockWidth = it },
        )
        DecimalSettingField(
            "Heating block depth (mm)",
            blockDepth,
            NonPlanarSettings.MIN_BLOCK_SIZE_MM,
            NonPlanarSettings.MAX_BLOCK_SIZE_MM,
            onText = { blockDepth = it },
        )
        DecimalSettingField(
            "Nozzle offset from block centre X (mm)",
            blockOffsetX,
            -NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
            NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
            onText = { blockOffsetX = it },
        )
        DecimalSettingField(
            "Nozzle offset from block centre Y (mm)",
            blockOffsetY,
            -NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
            NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
            onText = { blockOffsetY = it },
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Measure your hot end", style = MaterialTheme.typography.titleSmall)
                Text(
                    "All measurements are taken once, on the printer, with the tip just touching the bed. They build the collision volume: the nozzle cone (taper angle × protrusion below the block), the heating block frustum (the block's X × Y footprint widening at the same measured clearance angle up to the holding-object height), and a flat no-go cutoff above the holding object that spans the whole build plate plus 30%. After slicing, TrioSlicer sweeps that volume along every move and warns if the printed surface pokes into it. Fan ducts and bed sensors are not modelled - keep them clear of the measured frustum.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Nozzle taper: angle of the nozzle's own cone from horizontal (V6 ≈ 60°; 75° = a thin cone). Protrusion: how far the tip sticks out below the block (≈ 4–6 mm) - the first cone's height. Block cone height: from the top of the nozzle up to the lowest holding structure - the second cone's height. Block: the X × Y footprint of the heater block (E3D ≈ 20 × 16 mm) and the nozzle axis offset from the block centre in X and Y. Clearance angle: from horizontal up to the nearest thing that could collide (90° = straight up) - the block's sides follow this same angle. All angles are measured with a triangle against the bed.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        var viewerOpen by rememberSaveable { mutableStateOf(false) }
        OutlinedButton(
            onClick = { viewerOpen = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("View hot-end model (3D)")
        }
        if (viewerOpen) {
            HotendVolumeDialog(
                nozzleAngleDegrees = parsedNozzleAngle,
                protrusionMm = parsedNozzleProtrusion,
                blockWidthMm = parsedBlockWidth,
                blockDepthMm = parsedBlockDepth,
                offsetXmm = parsedBlockOffsetX,
                offsetYmm = parsedBlockOffsetY,
                clearanceAngleDegrees = parsedClearanceAngle,
                holderHeightMm = parsedClearanceHeight?.plus(parsedNozzleProtrusion ?: 0.0),
                onDismiss = { viewerOpen = false },
            )
        }
        DecimalSettingField(
            "Maximum Z speed (mm/s)",
            maximumZSpeed,
            NonPlanarSettings.MIN_Z_SPEED_MM_PER_SECOND,
            NonPlanarSettings.MAX_Z_SPEED_MM_PER_SECOND,
            onText = { maximumZSpeed = it },
        )
        SettingSwitch(
            title = "Pause after bed probing",
            description = "Insert M117 + M0 after the last G29 so you can raise a deployable probe before printing",
            checked = pauseAfterProbe,
            onChecked = { pauseAfterProbe = it },
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Printer safety", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Surfaces steeper than the maximum slope or taller than the maximum span are excluded from the curved regions and stay planar. Final G-code is rejected if any move leaves the configured machine envelope, and the measured hot-end volume is swept along every move with a warning on collision.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val junctionLimit = draft?.blockJunctionSlopeLimitDegrees
                if (junctionLimit != null) {
                    Text(
                        "The measured block geometry clears surface climbs up to about " +
                            String.format(java.util.Locale.US, "%.1f", junctionLimit) +
                            "° toward the block's offset side; steeper climbs can trigger collision warnings.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    "Preview the complete Path view before printing. Physical fan ducts, probes and heater blocks can require a higher clearance angle (steeper from the bed) than the bare nozzle.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Button(
            onClick = { onSave(requireNotNull(draft)) },
            enabled = draft != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save non-planar options")
        }
    }
}
