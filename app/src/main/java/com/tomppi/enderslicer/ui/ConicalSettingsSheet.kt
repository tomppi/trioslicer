package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.conical.ConeType
import com.tomppi.enderslicer.conical.ConicalSettings
import com.tomppi.enderslicer.conical.ConicalSettingsStore

@Composable
internal fun ConicalSettingsSheet(
    initial: ConicalSettings,
    onSave: (ConicalSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var enabled by rememberSaveable(initial.enabled) { mutableStateOf(initial.enabled) }
    var coneAngle by rememberSaveable(initial.coneAngleDegrees) {
        mutableStateOf(initial.coneAngleDegrees.toString())
    }
    var refinement by rememberSaveable(initial.refinementIterations) {
        mutableStateOf(initial.refinementIterations.toString())
    }
    var firstLayerHeight by rememberSaveable(initial.firstLayerHeightMm) {
        mutableStateOf(initial.firstLayerHeightMm.toString())
    }
    var xShift by rememberSaveable(initial.xShiftMm) { mutableStateOf(initial.xShiftMm.toString()) }
    var yShift by rememberSaveable(initial.yShiftMm) { mutableStateOf(initial.yShiftMm.toString()) }
    var pauseAfterProbe by rememberSaveable(initial.pauseAfterProbe) { mutableStateOf(initial.pauseAfterProbe) }

    val parsedConeAngle = parseDecimal(
        coneAngle,
        ConicalSettings.MIN_CONE_ANGLE_DEGREES,
        ConicalSettings.MAX_CONE_ANGLE_DEGREES,
    )
    val parsedRefinement = parseInteger(
        refinement,
        ConicalSettings.MIN_REFINEMENT_ITERATIONS,
        ConicalSettings.MAX_REFINEMENT_ITERATIONS,
    )
    val parsedFirstLayerHeight = parseDecimal(
        firstLayerHeight,
        ConicalSettings.MIN_FIRST_LAYER_HEIGHT_MM,
        ConicalSettings.MAX_FIRST_LAYER_HEIGHT_MM,
    )
    val parsedXShift = parseDecimal(xShift, ConicalSettings.MIN_SHIFT_MM, ConicalSettings.MAX_SHIFT_MM)
    val parsedYShift = parseDecimal(yShift, ConicalSettings.MIN_SHIFT_MM, ConicalSettings.MAX_SHIFT_MM)

    val draft = if (
        parsedConeAngle != null && parsedRefinement != null && parsedFirstLayerHeight != null &&
        parsedXShift != null && parsedYShift != null
    ) {
        ConicalSettings(
            enabled = enabled,
            coneAngleDegrees = parsedConeAngle,
            refinementIterations = parsedRefinement,
            coneType = ConeType.OUTWARD,
            firstLayerHeightMm = parsedFirstLayerHeight,
            xShiftMm = parsedXShift,
            yShiftMm = parsedYShift,
            pauseAfterProbe = pauseAfterProbe,
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
        Text("Conical slicing", style = MaterialTheme.typography.headlineSmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(ConicalSettingsStore.BACKEND_NAME, style = MaterialTheme.typography.titleMedium)
                Text("Ready · fully offline · Android ARM64", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "TrioSlicer warps the displayed STL around its vertical axis with the EasyConical cone transform, " +
                        "slices the warped solid with CuraEngine, then restores every G-code move to the original " +
                        "geometry. Conical layers let a tilted-nozzle 4-axis printer build steep overhangs without " +
                        "supports.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SettingSwitch(
            title = "Enable conical slicing",
            description = "Warp the model into a cone for the next slice",
            checked = enabled,
            onChecked = { enabled = it },
        )
        SettingSwitch(
            title = "Pause after bed probing",
            description = "Insert M117 + M0 after the last G29 so you can raise a deployable probe before printing",
            checked = pauseAfterProbe,
            onChecked = { pauseAfterProbe = it },
        )
        DecimalSettingField(
            "Cone angle (degrees)",
            coneAngle,
            ConicalSettings.MIN_CONE_ANGLE_DEGREES,
            ConicalSettings.MAX_CONE_ANGLE_DEGREES,
            onText = { coneAngle = it },
        )
        IntegerSettingField(
            "Refinement iterations",
            refinement,
            ConicalSettings.MIN_REFINEMENT_ITERATIONS,
            ConicalSettings.MAX_REFINEMENT_ITERATIONS,
            onText = { refinement = it },
        )
        DecimalSettingField(
            "First layer height (mm)",
            firstLayerHeight,
            ConicalSettings.MIN_FIRST_LAYER_HEIGHT_MM,
            ConicalSettings.MAX_FIRST_LAYER_HEIGHT_MM,
            onText = { firstLayerHeight = it },
        )
        DecimalSettingField(
            "X shift (mm)",
            xShift,
            ConicalSettings.MIN_SHIFT_MM,
            ConicalSettings.MAX_SHIFT_MM,
            onText = { xShift = it },
        )
        DecimalSettingField(
            "Y shift (mm)",
            yShift,
            ConicalSettings.MIN_SHIFT_MM,
            ConicalSettings.MAX_SHIFT_MM,
            onText = { yShift = it },
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Printer safety", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Conical slicing is intended for a 4-axis printer with a 45° tilted nozzle. The final G-code is " +
                        "rejected if any move leaves the configured machine envelope. Skirts, brims and nozzle priming " +
                        "lines are disabled automatically for conical slices.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Preview the complete Path view before printing. This mode is mutually exclusive with non-planar printing.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Button(
            onClick = { onSave(requireNotNull(draft)) },
            enabled = draft != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save conical options")
        }
    }
}

