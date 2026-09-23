package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.mesh.MeshTriangleLimits

@Composable
internal fun MeshTriangleLimitSheet(
    currentLimit: Int,
    currentModelTriangles: Int?,
    onSave: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var valueText by rememberSaveable(currentLimit) { mutableStateOf(currentLimit.toString()) }
    var presetMenu by rememberSaveable { mutableStateOf(false) }
    val parsed = valueText.filter(Char::isDigit).toIntOrNull()
    val valid = parsed?.takeIf { it in MeshTriangleLimits.MIN_TRIANGLES..MeshTriangleLimits.MAX_TRIANGLES }
    val selectedPreset = valid?.let { value -> MeshTriangleLimits.presets.firstOrNull { it.triangles == value } }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mesh triangle limit", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This limit applies to normal STL imports, BumpMesh's maximum-triangle control, and validation of textured STL exports.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { presetMenu = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(selectedPreset?.let { "${it.name} · ${MeshTriangleLimits.formatCount(it.triangles)}" } ?: "Custom limit")
            }
            DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                MeshTriangleLimits.presets.forEach { preset ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("${preset.name} · ${MeshTriangleLimits.formatCount(preset.triangles)}")
                                Text(preset.description, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = {
                            valueText = preset.triangles.toString()
                            presetMenu = false
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = valueText,
            onValueChange = { raw -> valueText = raw.filter(Char::isDigit).take(7) },
            label = { Text("Custom maximum triangles") },
            supportingText = {
                Text(
                    "Allowed: ${MeshTriangleLimits.formatCount(MeshTriangleLimits.MIN_TRIANGLES)}–${MeshTriangleLimits.formatCount(MeshTriangleLimits.MAX_TRIANGLES)}",
                )
            },
            isError = parsed != null && valid == null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (valid != null) {
            val binarySize = MeshTriangleLimits.binaryStlBytes(valid)
            val parsedSize = MeshTriangleLimits.parsedMeshBytes(valid)
            val workingEstimate = MeshTriangleLimits.estimatedWorkingSetBytes(valid)
            Text(
                "At this limit: binary STL ≈ ${MeshTriangleLimits.formatBytes(binarySize)} · one parsed mesh ≈ ${MeshTriangleLimits.formatBytes(parsedSize)} · rough multi-buffer working set ≈ ${MeshTriangleLimits.formatBytes(workingEstimate)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when {
                    valid > 5_000_000 -> "Extreme mode: subdivision, WebView geometry and rendering may still run out of memory even when Android reports free RAM."
                    valid > 3_000_000 -> "Very high detail: intended for high-memory devices. Processing and slicing may be substantially slower."
                    valid > MeshTriangleLimits.DEFAULT_TRIANGLES -> "High detail: monitor memory use when applying strong subdivision or displacement."
                    else -> "Compatible mode prioritizes predictable memory use."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (valid > 5_000_000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            if (currentModelTriangles != null && currentModelTriangles > valid) {
                Text(
                    "The currently loaded model has ${MeshTriangleLimits.formatCount(currentModelTriangles)} triangles. It remains loaded, but a new import or BumpMesh export above the saved limit will be rejected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { valid?.let(onSave) },
                enabled = valid != null,
            ) {
                Text("Save limit")
            }
        }
    }
}
