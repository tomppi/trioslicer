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
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.engine.GcodeLayerPreview
import com.tomppi.enderslicer.engine.LayerEvent
import com.tomppi.enderslicer.engine.LayerEventSource
import com.tomppi.enderslicer.engine.LayerEventType
import com.tomppi.enderslicer.model.SlicerSettings
import java.util.Locale

@Composable
internal fun LayerEventsSheet(
    layer: GcodeLayerPreview.Layer,
    events: List<LayerEvent>,
    settings: SlicerSettings,
    isBusy: Boolean,
    onAdd: (LayerEventType, Double?, Double?, String) -> Unit,
    onRemove: (String) -> Unit,
    onClearUserEvents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var type by rememberSaveable(layer.number) { mutableStateOf(LayerEventType.PAUSE) }
    var typeMenu by rememberSaveable(layer.number) { mutableStateOf(false) }
    var value by rememberSaveable(layer.number, type) { mutableStateOf(defaultValue(type, settings)) }
    var secondary by rememberSaveable(layer.number, type) { mutableStateOf(defaultSecondary(type, settings)) }
    var text by rememberSaveable(layer.number, type) { mutableStateOf("") }
    val layerEvents = events.filter { it.layerNumber == layer.number }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Layer events", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Layer ${layer.number} · Z %.3f mm · height %.3f mm".format(layer.z, layer.height),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Events are inserted after Cura's layer marker and can be changed without re-slicing.",
            style = MaterialTheme.typography.bodySmall,
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(eventTypeLabel(type))
            }
            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                LayerEventType.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(eventTypeLabel(option)) },
                        onClick = {
                            type = option
                            typeMenu = false
                        },
                    )
                }
            }
        }

        if (type.requiresValue()) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(valueLabel(type)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (type == LayerEventType.RETRACTION) {
            OutlinedTextField(
                value = secondary,
                onValueChange = { secondary = it },
                label = { Text("Retraction speed (mm/s)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (type == LayerEventType.MESSAGE || type == LayerEventType.CUSTOM_GCODE) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(if (type == LayerEventType.MESSAGE) "Printer display message" else "Custom G-code") },
                minLines = if (type == LayerEventType.CUSTOM_GCODE) 4 else 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val inputComplete = inputReady(type, value, secondary, text)
        Button(
            onClick = {
                onAdd(type, value.toNumber(), secondary.toNumber(), text)
            },
            enabled = !isBusy && inputComplete,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add to this layer") }
        // A disabled action says why in a sentence: the gate refuses a blank field
        // and a number that cannot become an event, and both name what is missing.
        val addDisabledReason = when {
            isBusy -> "Wait for the running operation, then add the event."
            !inputComplete -> inputMissingReason(type, value, secondary)
            else -> null
        }
        if (addDisabledReason != null) {
            Text(
                addDisabledReason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (layerEvents.isEmpty()) {
            Text("No events on this layer", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Events on this layer", style = MaterialTheme.typography.titleMedium)
            layerEvents.forEach { event ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(event.label.ifBlank(event::displayName), style = MaterialTheme.typography.bodyMedium)
                            Text("User event", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { onRemove(event.id) }, enabled = !isBusy) { Text("Remove") }
                    }
                }
            }
        }

        if (events.any { it.source == LayerEventSource.USER }) {
            OutlinedButton(onClick = onClearUserEvents, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                Text("Clear all user events")
            }
        }
    }
}

private fun LayerEventType.requiresValue(): Boolean = this in setOf(
    LayerEventType.NOZZLE_TEMPERATURE,
    LayerEventType.BED_TEMPERATURE,
    LayerEventType.FAN_SPEED,
    LayerEventType.SPEED_FACTOR,
    LayerEventType.FLOW_FACTOR,
    LayerEventType.RETRACTION,
    LayerEventType.PRESSURE_ADVANCE,
    LayerEventType.JUNCTION_DEVIATION,
)

private fun defaultValue(type: LayerEventType, settings: SlicerSettings): String = format(
    when (type) {
        LayerEventType.NOZZLE_TEMPERATURE -> settings.nozzleTemperatureC.toDouble()
        LayerEventType.BED_TEMPERATURE -> settings.bedTemperatureC.toDouble()
        LayerEventType.FAN_SPEED -> settings.fanSpeedPercent
        LayerEventType.SPEED_FACTOR, LayerEventType.FLOW_FACTOR -> 100.0
        LayerEventType.RETRACTION -> settings.retractionDistanceMm
        LayerEventType.PRESSURE_ADVANCE -> 0.0
        LayerEventType.JUNCTION_DEVIATION -> 0.013
        else -> 0.0
    },
)

private fun defaultSecondary(type: LayerEventType, settings: SlicerSettings): String =
    if (type == LayerEventType.RETRACTION) format(settings.retractionSpeedMmPerSecond) else ""

internal fun inputReady(type: LayerEventType, value: String, secondary: String, text: String): Boolean = when {
    type == LayerEventType.MESSAGE || type == LayerEventType.CUSTOM_GCODE -> text.isNotBlank()
    type == LayerEventType.RETRACTION -> value.toNumber() != null && secondary.toNumber() != null
    type.requiresValue() -> value.toNumber() != null
    else -> true
}

/** Names what the gate above refused, for the disabled button's reason sentence. */
internal fun inputMissingReason(
    type: LayerEventType,
    value: String,
    secondary: String,
): String = when {
    type == LayerEventType.MESSAGE -> "Type the printer display message first."
    type == LayerEventType.CUSTOM_GCODE -> "Type the custom G-code first."
    type == LayerEventType.RETRACTION && value.toNumber() == null ->
        "Retraction distance must be a finite number."
    type == LayerEventType.RETRACTION -> "Retraction speed must be a finite number."
    else -> valueLabel(type) + " must be a finite number."
}

// NaN, infinity and 1e400 parse as doubles but can never become a LayerEvent - its
// constructor requires finite values - so the input gate refuses them here first.
internal fun String.toNumber(): Double? = replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
private fun format(value: Double): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

private fun valueLabel(type: LayerEventType): String = when (type) {
    LayerEventType.NOZZLE_TEMPERATURE -> "Nozzle temperature (°C)"
    LayerEventType.BED_TEMPERATURE -> "Bed temperature (°C)"
    LayerEventType.FAN_SPEED -> "Fan speed (%)"
    LayerEventType.SPEED_FACTOR -> "Speed factor (%)"
    LayerEventType.FLOW_FACTOR -> "Flow factor (%)"
    LayerEventType.RETRACTION -> "Retraction distance (mm)"
    LayerEventType.PRESSURE_ADVANCE -> "Linear Advance K"
    LayerEventType.JUNCTION_DEVIATION -> "Junction deviation (mm)"
    else -> "Value"
}

private fun eventTypeLabel(type: LayerEventType): String = when (type) {
    LayerEventType.PAUSE -> "Pause print"
    LayerEventType.FILAMENT_CHANGE -> "Filament change (M600)"
    LayerEventType.NOZZLE_TEMPERATURE -> "Nozzle temperature"
    LayerEventType.BED_TEMPERATURE -> "Bed temperature"
    LayerEventType.FAN_SPEED -> "Fan speed"
    LayerEventType.SPEED_FACTOR -> "Speed factor"
    LayerEventType.FLOW_FACTOR -> "Flow factor"
    LayerEventType.RETRACTION -> "Firmware retraction (M207)"
    LayerEventType.PRESSURE_ADVANCE -> "Pressure advance (M900 K)"
    LayerEventType.JUNCTION_DEVIATION -> "Junction deviation (M205 J)"
    LayerEventType.CAMERA_TRIGGER -> "Camera trigger (M240)"
    LayerEventType.MESSAGE -> "Printer display message"
    LayerEventType.CUSTOM_GCODE -> "Custom G-code"
}
