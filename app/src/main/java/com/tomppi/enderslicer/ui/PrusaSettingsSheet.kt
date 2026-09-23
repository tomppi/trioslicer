package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.PrusaSliceSettings

/**
 * PrusaSlicer slice settings sheet.
 *
 * Every label and grouping follows PrusaSlicer's own vocabulary so that a
 * PrusaSlicer user feels at home; the state edits [MainUiState.prusaSettings]
 * through the same update pattern as the Cura sheet.
 */
@Composable
internal fun PrusaSettingsSheet(
    state: MainUiState,
    onSettings: (String, (PrusaSliceSettings) -> PrusaSliceSettings) -> Unit,
    onImportConfig: () -> Unit = {},
    onOpenAllSettings: () -> Unit = {},
    onPrusaPrinter: (String) -> Unit = {},
    onPrusaPrint: (String) -> Unit = {},
    onPrusaFilament: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val settings = state.prusaSettings
    val selection = state.prusaPreset
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("PrusaSlicer settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Every option below maps 1:1 to a PrusaSlicer .ini key.",
            style = MaterialTheme.typography.bodySmall,
        )
        PresetPickerRow(
            label = "Printer",
            selectedLabel = state.prusaPresetPrinters
                .firstOrNull { it.id == selection.printerId }?.label
                ?: "not using presets",
            options = state.prusaPresetPrinters,
            onSelect = onPrusaPrinter,
            emptyHint = "Reading the bundled PrusaSlicer presets…",
        )
        PresetPickerRow(
            label = "Quality",
            selectedLabel = selection.printName ?: "machine default",
            options = state.prusaPresetPrints,
            onSelect = onPrusaPrint,
            enabled = selection.isActive,
            emptyHint = "Pick a printer first",
        )
        PresetPickerRow(
            label = "Material",
            selectedLabel = selection.filamentName ?: "machine default",
            options = state.prusaPresetFilaments,
            onSelect = onPrusaFilament,
            enabled = selection.isActive,
            emptyHint = "Pick a printer first",
        )
        OutlinedButton(
            onClick = onImportConfig,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Import settings from PrusaSlicer (.ini)")
        }
        OutlinedButton(
            onClick = onOpenAllSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add more settings (all settings)")
        }
        OutlinedButton(
            onClick = {
                onSettings(PrusaSliceSettings.Keys.LAYER_HEIGHT) { current ->
                    current.copy(
                        layerHeightMm = PrusaSliceSettings().layerHeightMm,
                        firstLayerHeightMm = PrusaSliceSettings().firstLayerHeightMm,
                        perimeters = PrusaSliceSettings().perimeters,
                        topSolidLayers = PrusaSliceSettings().topSolidLayers,
                        bottomSolidLayers = PrusaSliceSettings().bottomSolidLayers,
                        thinWalls = PrusaSliceSettings().thinWalls,
                        externalPerimetersFirst = PrusaSliceSettings().externalPerimetersFirst,
                        fillDensityPercent = PrusaSliceSettings().fillDensityPercent,
                        fillPattern = PrusaSliceSettings().fillPattern,
                        skirtLoops = PrusaSliceSettings().skirtLoops,
                        skirtHeightLayers = PrusaSliceSettings().skirtHeightLayers,
                        skirtDistanceMm = PrusaSliceSettings().skirtDistanceMm,
                        brimWidthMm = PrusaSliceSettings().brimWidthMm,
                        overhangs = PrusaSliceSettings().overhangs,
                        supportMaterial = PrusaSliceSettings().supportMaterial,
                        supportThresholdAngleDegrees = PrusaSliceSettings().supportThresholdAngleDegrees,
                        supportPattern = PrusaSliceSettings().supportPattern,
                        supportInterface = PrusaSliceSettings().supportInterface,
                        supportInterfaceLayers = PrusaSliceSettings().supportInterfaceLayers,
                        printSpeedMmPerSecond = PrusaSliceSettings().printSpeedMmPerSecond,
                        externalPerimeterSpeedMmPerSecond = PrusaSliceSettings().externalPerimeterSpeedMmPerSecond,
                        infillSpeedMmPerSecond = PrusaSliceSettings().infillSpeedMmPerSecond,
                        firstLayerSpeedMmPerSecond = PrusaSliceSettings().firstLayerSpeedMmPerSecond,
                        travelSpeedMmPerSecond = PrusaSliceSettings().travelSpeedMmPerSecond,
                        nozzleTemperatureC = PrusaSliceSettings().nozzleTemperatureC,
                        firstLayerTemperatureC = PrusaSliceSettings().firstLayerTemperatureC,
                        bedTemperatureC = PrusaSliceSettings().bedTemperatureC,
                        firstLayerBedTemperatureC = PrusaSliceSettings().firstLayerBedTemperatureC,
                        fanSpeedPercent = PrusaSliceSettings().fanSpeedPercent,
                        retractionLengthMm = PrusaSliceSettings().retractionLengthMm,
                        retractionSpeedMmPerSecond = PrusaSliceSettings().retractionSpeedMmPerSecond,
                        retractionMinTravelMm = PrusaSliceSettings().retractionMinTravelMm,
                        retractLiftMm = PrusaSliceSettings().retractLiftMm,
                        useFirmwareRetraction = PrusaSliceSettings().useFirmwareRetraction,
                        extrusionMultiplierPercent = PrusaSliceSettings().extrusionMultiplierPercent,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset Prusa settings")
        }

        SettingsCategory("Quality", initiallyExpanded = true) {
            NumberField("Layer height", settings.layerHeightMm, "Prusa: layer_height", decimals = 3) {
                onSettings(PrusaSliceSettings.Keys.LAYER_HEIGHT) { current ->
                    current.copy(layerHeightMm = it.coerceIn(0.02, 2.0))
                }
            }
            NumberField("First layer height", settings.firstLayerHeightMm, "Prusa: first_layer_height", decimals = 3) {
                onSettings(PrusaSliceSettings.Keys.FIRST_LAYER_HEIGHT) { current ->
                    current.copy(firstLayerHeightMm = it.coerceIn(0.02, 2.0))
                }
            }
        }

        SettingsCategory("Perimeters & shells", initiallyExpanded = true) {
            NumberField("Perimeters", settings.perimeters.toDouble(), "Prusa: perimeters", decimals = 0) {
                onSettings(PrusaSliceSettings.Keys.PERIMETERS) { current -> current.copy(perimeters = it.toInt().coerceIn(0, 8)) }
            }
            NumberField("Top solid layers", settings.topSolidLayers.toDouble(), "Prusa: top_solid_layers", decimals = 0) {
                onSettings(PrusaSliceSettings.Keys.TOP_SOLID_LAYERS) { current -> current.copy(topSolidLayers = it.toInt().coerceIn(0, 12)) }
            }
            NumberField("Bottom solid layers", settings.bottomSolidLayers.toDouble(), "Prusa: bottom_solid_layers", decimals = 0) {
                onSettings(PrusaSliceSettings.Keys.BOTTOM_SOLID_LAYERS) { current -> current.copy(bottomSolidLayers = it.toInt().coerceIn(0, 12)) }
            }
            SwitchRow("Thin walls", settings.thinWalls, "Prusa: thin_walls") {
                onSettings(PrusaSliceSettings.Keys.THIN_WALLS) { current -> current.copy(thinWalls = it) }
            }
            SwitchRow("External perimeters first", settings.externalPerimetersFirst, "Prusa: external_perimeters_first") {
                onSettings(PrusaSliceSettings.Keys.EXTERNAL_PERIMETERS_FIRST) { current -> current.copy(externalPerimetersFirst = it) }
            }
        }

        SettingsCategory("Infill", initiallyExpanded = true) {
            NumberField("Fill density", settings.fillDensityPercent, "Prusa: fill_density", decimals = 1) {
                onSettings(PrusaSliceSettings.Keys.FILL_DENSITY) { current ->
                    current.copy(fillDensityPercent = it.coerceIn(0.0, 100.0))
                }
            }
            OptionField(
                "Fill pattern",
                settings.fillPattern,
                PrusaSliceSettings.FILL_PATTERNS.map { it to it.replaceFirstChar(Char::uppercase) },
                "Prusa: fill_pattern",
            ) {
                onSettings(PrusaSliceSettings.Keys.FILL_PATTERN) { current -> current.copy(fillPattern = it) }
            }
        }

        SettingsCategory("Skirt & brim", initiallyExpanded = false) {
            NumberField("Skirt loops", settings.skirtLoops.toDouble(), "Prusa: skirts", decimals = 0) {
                onSettings(PrusaSliceSettings.Keys.SKIRT_LOOPS) { current -> current.copy(skirtLoops = it.toInt().coerceIn(0, 10)) }
            }
            NumberField("Skirt distance", settings.skirtDistanceMm, "Prusa: skirt_distance", decimals = 1) {
                onSettings(PrusaSliceSettings.Keys.SKIRT_DISTANCE) { current -> current.copy(skirtDistanceMm = it.coerceIn(0.0, 20.0)) }
            }
            NumberField("Brim width", settings.brimWidthMm, "Prusa: brim_width", decimals = 1) {
                onSettings(PrusaSliceSettings.Keys.BRIM_WIDTH) { current -> current.copy(brimWidthMm = it.coerceIn(0.0, 30.0)) }
            }
        }

        SettingsCategory("Support material", initiallyExpanded = false) {
            SwitchRow("Enable supports", settings.supportMaterial, "Prusa: support_material") {
                onSettings(PrusaSliceSettings.Keys.SUPPORT_MATERIAL) { current -> current.copy(supportMaterial = it) }
            }
            NumberField("Threshold angle", settings.supportThresholdAngleDegrees, "Prusa: support_material_threshold_angle", decimals = 1) {
                onSettings(PrusaSliceSettings.Keys.SUPPORT_THRESHOLD_ANGLE) { current ->
                    current.copy(supportThresholdAngleDegrees = it.coerceIn(0.0, 90.0))
                }
            }
            OptionField(
                "Support pattern",
                settings.supportPattern,
                PrusaSliceSettings.SUPPORT_PATTERNS.map { it to it.replaceFirstChar(Char::uppercase) },
                "Prusa: support_material_pattern",
            ) {
                onSettings(PrusaSliceSettings.Keys.SUPPORT_PATTERN) { current -> current.copy(supportPattern = it) }
            }
            SwitchRow("Support interface", settings.supportInterface, "Prusa: support_material_interface") {
                onSettings(PrusaSliceSettings.Keys.SUPPORT_INTERFACE) { current -> current.copy(supportInterface = it) }
            }
            NumberField("Interface layers", settings.supportInterfaceLayers.toDouble(), "Prusa: support_material_interface_layers", decimals = 0) {
                onSettings(PrusaSliceSettings.Keys.SUPPORT_INTERFACE_LAYERS) { current ->
                    current.copy(supportInterfaceLayers = it.toInt().coerceIn(0, 8))
                }
            }
        }

        SettingsCategory("Speeds", initiallyExpanded = false) {
            NumberField("Print speed", settings.printSpeedMmPerSecond, "Prusa: print_speed", decimals = 1) {
                onSettings(PrusaSliceSettings.Keys.PRINT_SPEED) { current -> current.copy(printSpeedMmPerSecond = it.coerceIn(5.0, 400.0)) }
            }
            NumberField("External perimeter speed", settings.externalPerimeterSpeedMmPerSecond, "Prusa: external_perimeter_speed", decimals = 1) {
                onSettings(PrusaSliceSettings.Keys.EXTERNAL_PERIMETER_SPEED) { current ->
                    current.copy(externalPerimeterSpeedMmPerSecond = it.coerceIn(5.0, 400.0))
                }
            }
            NumberField("Infill speed", settings.infillSpeedMmPerSecond, "Prusa: infill_speed", decimals = 1) {
                onSettings(PrusaSliceSettings.Keys.INFILL_SPEED) { current -> current.copy(infillSpeedMmPerSecond = it.coerceIn(5.0, 400.0)) }
            }
            NumberField("First layer speed", settings.firstLayerSpeedMmPerSecond, "Prusa: first_layer_speed", decimals = 1) {
                onSettings(PrusaSliceSettings.Keys.FIRST_LAYER_SPEED) { current ->
                    current.copy(firstLayerSpeedMmPerSecond = it.coerceIn(5.0, 200.0))
                }
            }
            NumberField("Travel speed", settings.travelSpeedMmPerSecond, "Prusa: travel_speed", decimals = 1) {
                onSettings(PrusaSliceSettings.Keys.TRAVEL_SPEED) { current -> current.copy(travelSpeedMmPerSecond = it.coerceIn(10.0, 500.0)) }
            }
        }

        SettingsCategory("Temperature & fan", initiallyExpanded = false) {
            NumberField("Nozzle temperature", settings.nozzleTemperatureC.toDouble(), "Prusa: temperature", decimals = 0) {
                onSettings(PrusaSliceSettings.Keys.NOZZLE_TEMPERATURE) { current ->
                    current.copy(nozzleTemperatureC = it.toInt().coerceIn(150, 320))
                }
            }
            NumberField("First layer nozzle temperature", settings.firstLayerTemperatureC.toDouble(), "Prusa: first_layer_temperature", decimals = 0) {
                onSettings(PrusaSliceSettings.Keys.FIRST_LAYER_TEMPERATURE) { current ->
                    current.copy(firstLayerTemperatureC = it.toInt().coerceIn(150, 320))
                }
            }
            NumberField("Bed temperature", settings.bedTemperatureC.toDouble(), "Prusa: bed_temperature", decimals = 0) {
                onSettings(PrusaSliceSettings.Keys.BED_TEMPERATURE) { current ->
                    current.copy(bedTemperatureC = it.toInt().coerceIn(0, 200))
                }
            }
            NumberField("First layer bed temperature", settings.firstLayerBedTemperatureC.toDouble(), "Prusa: first_layer_bed_temperature", decimals = 0) {
                onSettings(PrusaSliceSettings.Keys.FIRST_LAYER_BED_TEMPERATURE) { current ->
                    current.copy(firstLayerBedTemperatureC = it.toInt().coerceIn(0, 200))
                }
            }
            NumberField("Fan speed", settings.fanSpeedPercent.toDouble(), "Prusa: fan_speed (0-100%)", decimals = 0) {
                onSettings(PrusaSliceSettings.Keys.FAN_SPEED) { current -> current.copy(fanSpeedPercent = it.toInt().coerceIn(0, 100)) }
            }
        }

        SettingsCategory("Extrusion & retraction", initiallyExpanded = false) {
            NumberField("Retraction length", settings.retractionLengthMm, "Prusa: retraction_length", decimals = 2) {
                onSettings(PrusaSliceSettings.Keys.RETRACTION_LENGTH) { current ->
                    current.copy(retractionLengthMm = it.coerceIn(0.0, 10.0))
                }
            }
            NumberField("Retraction speed", settings.retractionSpeedMmPerSecond, "Prusa: retraction_speed", decimals = 1) {
                onSettings(PrusaSliceSettings.Keys.RETRACTION_SPEED) { current ->
                    current.copy(retractionSpeedMmPerSecond = it.coerceIn(5.0, 120.0))
                }
            }
            NumberField("Retraction min travel", settings.retractionMinTravelMm, "Prusa: retraction_min_travel", decimals = 1) {
                onSettings(PrusaSliceSettings.Keys.RETRACTION_MIN_TRAVEL) { current ->
                    current.copy(retractionMinTravelMm = it.coerceIn(0.0, 50.0))
                }
            }
            NumberField("Z lift on retraction", settings.retractLiftMm, "Prusa: retract_lift", decimals = 2) {
                onSettings(PrusaSliceSettings.Keys.RETRACT_LIFT) { current -> current.copy(retractLiftMm = it.coerceIn(0.0, 2.0)) }
            }
            SwitchRow("Use firmware retraction", settings.useFirmwareRetraction, "Prusa: use_firmware_retraction") {
                onSettings(PrusaSliceSettings.Keys.USE_FIRMWARE_RETRACTION) { current -> current.copy(useFirmwareRetraction = it) }
            }
            NumberField("Extrusion multiplier", settings.extrusionMultiplierPercent, "Prusa: extrusion_multiplier (%)", decimals = 0) {
                onSettings(PrusaSliceSettings.Keys.EXTRUSION_MULTIPLIER) { current ->
                    current.copy(extrusionMultiplierPercent = it.coerceIn(50.0, 150.0))
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
