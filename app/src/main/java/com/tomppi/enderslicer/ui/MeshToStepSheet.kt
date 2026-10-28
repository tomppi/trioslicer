package com.tomppi.enderslicer.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.mesh.MeshTriangleLimits

/**
 * The Mesh -> STEP (Mesh-Mix-S2C) sheet.
 *
 * It renders [MeshToStepState] and reports gestures through [MeshToStepActions]; it owns no
 * engine, no file and no conversion. The honesty is in the copy: the conversion runs on this
 * device, mechanical parts are seconds, organic meshes can be many minutes, and Cancel is live
 * from the first second because the state machine moves to [MeshToStepState.converting] before
 * the engine call starts.
 */
internal data class MeshToStepActions(
    val onClose: () -> Unit = {},
    val onPreset: (MeshToStepPreset) -> Unit = {},
    val onQuality: (Float) -> Unit = {},
    val onUnit: (MeshToStepUnit) -> Unit = {},
    val onStart: () -> Unit = {},
    val onCancel: () -> Unit = {},
    /** Opens the SAVE picker; the ViewModel copies the cache STEP to the chosen destination. */
    val onSaveStep: () -> Unit = {},
    /** Opens the saved STEP through the existing SolidCraft/Open-CAD route. */
    val onOpenAsCad: () -> Unit = {},
)

@Composable
internal fun MeshToStepSheet(
    state: MeshToStepState,
    actions: MeshToStepActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Mesh -> STEP", style = MaterialTheme.typography.titleLarge)
                Text(
                    state.sourceName + " · " + MeshTriangleLimits.formatCount(state.sourceTriangles) +
                        " triangles",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = actions.onClose) { Text("Done") }
        }
        Text(
            "Convert the selected plate object's mesh into an editable STEP file. The conversion " +
                "runs on this device - nothing is uploaded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        Text("Preset", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MeshToStepPreset.entries.forEach { preset ->
                FilterChip(
                    selected = state.preset == preset,
                    enabled = !state.converting && preset.selectable,
                    onClick = { actions.onPreset(preset) },
                    label = { Text(preset.label) },
                )
            }
        }
        state.unavailablePresetNote?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            state.presetMeaning,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.presetCaution?.let { caution ->
            Text(
                caution,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text("Quality", style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Coarse · fast",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                state.qualityLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "Fine · slow",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = state.quality,
            onValueChange = actions.onQuality,
            enabled = !state.converting,
            valueRange = 0f..1f,
        )
        Text(
            "Finer keeps more detail and takes longer.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Unit", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MeshToStepUnit.entries.forEach { unit ->
                FilterChip(
                    selected = state.unit == unit,
                    enabled = !state.converting,
                    onClick = { actions.onUnit(unit) },
                    label = { Text(unit.label) },
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                state.costLine,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }

        if (state.converting) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(state.progressLabel, style = MaterialTheme.typography.bodyMedium)
                    state.stageMessage?.takeIf { it.isNotBlank() }?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 4.dp),
                    )
                    OutlinedButton(
                        onClick = actions.onCancel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(MESH_TO_STEP_CANCEL_TAG),
                    ) { Text("Cancel conversion") }
                }
            }
        } else {
            Button(
                onClick = actions.onStart,
                enabled = state.canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(MESH_TO_STEP_START_TAG),
            ) { Text("Start Mesh -> STEP") }
            state.startBlockedReason?.let { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.error?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.result?.let { result ->
            HorizontalDivider()
            Text("STEP written", style = MaterialTheme.typography.titleSmall)
            Text(result.summaryLine, style = MaterialTheme.typography.bodyMedium)
            Text(
                "From a " + MeshTriangleLimits.formatCount(state.sourceTriangles) +
                    "-triangle mesh, converted on this device.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!result.valid) {
                Text(
                    "The engine finished, but its own check found mesh issues - read the warnings " +
                        "before using the STEP.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.resultWarningsLine?.let { warnings ->
                Text(
                    "Warnings: " + warnings,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.savedLine?.let { saved ->
                Text(
                    saved,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = actions.onSaveStep,
                    enabled = result.stepPath != null,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(MESH_TO_STEP_SAVE_TAG),
                ) {
                    Text(if (state.savedName == null) "Save STEP…" else "Save again…")
                }
                OutlinedButton(
                    onClick = actions.onOpenAsCad,
                    enabled = state.savedUri != null,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(MESH_TO_STEP_OPEN_CAD_TAG),
                ) { Text("Open as CAD") }
            }
            if (state.savedUri == null) {
                Text(
                    "Save the STEP first, then it can open as a CAD document.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** Tags the sheet's two state-dependent buttons, so a test presses a button, not a coordinate. */
internal const val MESH_TO_STEP_START_TAG = "mesh-to-step-start"
internal const val MESH_TO_STEP_CANCEL_TAG = "mesh-to-step-cancel"
internal const val MESH_TO_STEP_SAVE_TAG = "mesh-to-step-save"
internal const val MESH_TO_STEP_OPEN_CAD_TAG = "mesh-to-step-open-cad"
