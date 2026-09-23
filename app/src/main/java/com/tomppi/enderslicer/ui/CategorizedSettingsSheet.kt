package com.tomppi.enderslicer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.CuraMachineCatalog
import com.tomppi.enderslicer.model.SlicerSettings

internal fun infillPatternLabel(pattern: String): String = when (pattern.lowercase()) {
    "grid" -> "Grid"
    "lines" -> "Lines"
    "triangles" -> "Triangles"
    "trihexagon" -> "Tri-hexagon"
    "cubic" -> "Cubic"
    "cubicsubdiv" -> "Cubic subdivision"
    "tetrahedral" -> "Octet"
    "quarter_cubic" -> "Quarter cubic"
    "concentric" -> "Concentric"
    "zigzag" -> "Zig zag"
    "cross" -> "Cross"
    "cross_3d" -> "Cross 3D"
    "gyroid" -> "Gyroid"
    "lightning" -> "Lightning"
    "rectilinear" -> "Rectilinear"
    "aligned rectilinear" -> "Aligned rectilinear"
    "alignedrectilinear" -> "Aligned rectilinear"
    "honeycomb" -> "Honeycomb"
    "stars" -> "Stars"
    "cross-hatch" -> "Cross hatch"
    "crosshatch" -> "Cross hatch"
    "snug" -> "Snug"
    else -> pattern.replaceFirstChar { it.uppercase() }
}

@Composable
internal fun CategorizedSettingsSheet(
    state: MainUiState,
    onSettings: (String, (SlicerSettings) -> SlicerSettings) -> Unit,
    onResetOverrides: () -> Unit,
    onImportProfile: () -> Unit = {},
    onImportProject: () -> Unit = {},
    onOpenAllSettings: () -> Unit = {},
    onCuraMachine: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Print settings", style = MaterialTheme.typography.headlineSmall)
        CuraMachinePicker(
            machines = state.curaMachines,
            selectedId = state.curaMachineId,
            onSelect = onCuraMachine,
        )
        OutlinedButton(
            onClick = onImportProfile,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Import settings from Cura profile (.curaprofile)")
        }
        OutlinedButton(
            onClick = onImportProject,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Import settings from Cura project (.3mf)")
        }
        Text(
            "A profile carries print and filament settings. A project carries the machine definition and its start/end G-code too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onOpenAllSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add more settings (all settings)")
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.profileName, style = MaterialTheme.typography.titleMedium)
                Text(state.profileSource, style = MaterialTheme.typography.bodySmall)
                if (state.importedRawSettingCount > 0) {
                    Text(
                        "${state.importedRawSettingCount} imported values · ${settings.overriddenSettingKeys.size} app overrides",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.warnings.forEach { warning ->
                    Text("Warning: $warning", color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(
                    onClick = onResetOverrides,
                    enabled = settings.overriddenSettingKeys.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reset all app overrides")
                }
            }
        }

        SettingsCategory("Quality", initiallyExpanded = true) {
            NumberField("Layer height (mm)", settings.layerHeightMm, source(state, SlicerSettings.Keys.LAYER_HEIGHT)) {
                onSettings(SlicerSettings.Keys.LAYER_HEIGHT) { current -> current.copy(layerHeightMm = it.coerceIn(0.01, 5.0)) }
            }
            NumberField("Initial layer height (mm)", settings.initialLayerHeightMm, source(state, SlicerSettings.Keys.INITIAL_LAYER_HEIGHT)) {
                onSettings(SlicerSettings.Keys.INITIAL_LAYER_HEIGHT) { current -> current.copy(initialLayerHeightMm = it.coerceIn(0.01, 5.0)) }
            }
            SwitchRow(
                "Adaptive layer height",
                settings.adaptiveLayerHeightEnabled,
                source(state, SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_ENABLED),
            ) {
                onSettings(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_ENABLED) { current ->
                    current.copy(adaptiveLayerHeightEnabled = it)
                }
            }
            if (settings.adaptiveLayerHeightEnabled) {
                val minimumHeight = (settings.layerHeightMm - settings.adaptiveLayerHeightVariationMm).coerceAtLeast(0.01)
                val maximumHeight = settings.layerHeightMm + settings.adaptiveLayerHeightVariationMm
                Text(
                    "Cura may vary layers from %.3f to %.3f mm around the nominal height.".format(minimumHeight, maximumHeight),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NumberField(
                    "Maximum variation (± mm)",
                    settings.adaptiveLayerHeightVariationMm,
                    source(state, SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION),
                    decimals = 3,
                ) {
                    onSettings(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION) { current ->
                        current.copy(adaptiveLayerHeightVariationMm = it.coerceIn(0.001, (current.layerHeightMm - 0.01).coerceAtLeast(0.001)))
                    }
                }
                NumberField(
                    "Variation step (mm)",
                    settings.adaptiveLayerHeightVariationStepMm,
                    source(state, SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION_STEP),
                    decimals = 3,
                ) {
                    onSettings(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION_STEP) { current ->
                        current.copy(adaptiveLayerHeightVariationStepMm = it.coerceIn(0.001, current.adaptiveLayerHeightVariationMm.coerceAtLeast(0.001)))
                    }
                }
                NumberField(
                    "Surface detail threshold",
                    settings.adaptiveLayerHeightThreshold,
                    source(state, SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_THRESHOLD),
                    decimals = 3,
                ) {
                    onSettings(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_THRESHOLD) { current ->
                        current.copy(adaptiveLayerHeightThreshold = it.coerceIn(0.0, 1.0))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            onSettings(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION) { it.copy(adaptiveLayerHeightVariationMm = 0.06) }
                            onSettings(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION_STEP) { it.copy(adaptiveLayerHeightVariationStepMm = 0.01) }
                            onSettings(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_THRESHOLD) { it.copy(adaptiveLayerHeightThreshold = 0.12) }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Fine") }
                    OutlinedButton(
                        onClick = {
                            onSettings(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION) { it.copy(adaptiveLayerHeightVariationMm = 0.10) }
                            onSettings(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION_STEP) { it.copy(adaptiveLayerHeightVariationStepMm = 0.01) }
                            onSettings(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_THRESHOLD) { it.copy(adaptiveLayerHeightThreshold = 0.20) }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Balanced") }
                    OutlinedButton(
                        onClick = {
                            onSettings(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION) { it.copy(adaptiveLayerHeightVariationMm = 0.14) }
                            onSettings(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION_STEP) { it.copy(adaptiveLayerHeightVariationStepMm = 0.02) }
                            onSettings(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_THRESHOLD) { it.copy(adaptiveLayerHeightThreshold = 0.30) }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Fast") }
                }
            }
            NumberField("Line width (mm)", settings.lineWidthMm, source(state, SlicerSettings.Keys.LINE_WIDTH)) {
                onSettings(SlicerSettings.Keys.LINE_WIDTH) { current -> current.copy(lineWidthMm = it.coerceIn(0.01, 5.0)) }
            }
            OptionField(
                label = "Slicing tolerance",
                value = settings.slicingTolerance,
                options = listOf(
                    "middle" to "Middle · closest to surface",
                    "exclusive" to "Exclusive · best dimensional fit",
                    "inclusive" to "Inclusive · retain small details",
                ),
                source = source(state, SlicerSettings.Keys.SLICING_TOLERANCE),
            ) {
                onSettings(SlicerSettings.Keys.SLICING_TOLERANCE) { current -> current.copy(slicingTolerance = it) }
            }
        }

        SettingsCategory("Walls and top/bottom") {
            CalculatedField("Wall thickness (mm)", settings.wallThicknessMm, "Wall line count × line width")
            NumberField("Wall line count", settings.wallLineCount.toDouble(), source(state, SlicerSettings.Keys.WALL_LINE_COUNT), decimals = 0) {
                onSettings(SlicerSettings.Keys.WALL_LINE_COUNT) { current -> current.copy(wallLineCount = it.toInt().coerceIn(0, 1000)) }
            }
            NumberField("Top/bottom thickness (mm)", settings.topBottomThicknessMm, source(state, SlicerSettings.Keys.TOP_BOTTOM_THICKNESS)) {
                onSettings(SlicerSettings.Keys.TOP_BOTTOM_THICKNESS) { current -> current.copy(topBottomThicknessMm = it.coerceIn(0.0, current.machineHeightMm)) }
            }
            Column {
                OutlinedTextField(
                    value = settings.topSkinAngles,
                    onValueChange = { raw ->
                        val cleaned = raw.filter { it.isDigit() || it == ',' || it == '.' || it == '-' }
                        onSettings(SlicerSettings.Keys.TOP_SKIN_ANGLES) { current -> current.copy(topSkinAngles = cleaned) }
                    },
                    label = { Text("Top skin line angles (degrees)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Comma-separated; every layer cycles through the list. 90 sweeps straight across a bump, 0 runs along it - with non-planar printing this changes how the curved Z motion shows.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OptionField(
                label = "Top skin pattern",
                value = settings.topBottomPattern,
                options = listOf(
                    "lines" to "Lines · straight sweeps",
                    "concentric" to "Concentric · follows the outline",
                    "zigzag" to "Zigzag · connected sweeps",
                ),
                source = source(state, SlicerSettings.Keys.TOP_BOTTOM_PATTERN),
            ) {
                onSettings(SlicerSettings.Keys.TOP_BOTTOM_PATTERN) { current -> current.copy(topBottomPattern = it) }
            }
            CalculatedField("Top layers", settings.topLayers.toDouble(), "Top/bottom thickness ÷ layer height")
            CalculatedField("Bottom layers", settings.bottomLayers.toDouble(), "Top/bottom thickness ÷ layer height")
            CalculatedField("Initial bottom layers", settings.initialBottomLayers.toDouble(), "Bottom layers")
            NumberField("Hole horizontal expansion (mm)", settings.holeHorizontalExpansionMm, source(state, SlicerSettings.Keys.HOLE_HORIZONTAL_EXPANSION), decimals = 3) {
                onSettings(SlicerSettings.Keys.HOLE_HORIZONTAL_EXPANSION) { current -> current.copy(holeHorizontalExpansionMm = it.coerceIn(-10.0, 10.0)) }
            }
            NumberField("Initial-layer horizontal expansion (mm)", settings.initialLayerHorizontalExpansionMm, source(state, SlicerSettings.Keys.INITIAL_LAYER_HORIZONTAL_EXPANSION), decimals = 3) {
                onSettings(SlicerSettings.Keys.INITIAL_LAYER_HORIZONTAL_EXPANSION) { current -> current.copy(initialLayerHorizontalExpansionMm = it.coerceIn(-10.0, 10.0)) }
            }
            OptionField(
                label = "Z seam alignment",
                value = settings.zSeamType,
                options = listOf(
                    "back" to "User specified",
                    "shortest" to "Shortest path",
                    "random" to "Random",
                    "sharpest_corner" to "Sharpest corner",
                ),
                source = source(state, SlicerSettings.Keys.Z_SEAM_TYPE),
            ) {
                onSettings(SlicerSettings.Keys.Z_SEAM_TYPE) { current -> current.copy(zSeamType = it) }
            }
            if (settings.zSeamType == "back") {
                NumberField("Z seam X (mm)", settings.zSeamXmm, source(state, SlicerSettings.Keys.Z_SEAM_X)) {
                    onSettings(SlicerSettings.Keys.Z_SEAM_X) { current -> current.copy(zSeamXmm = it.coerceIn(-2000.0, 2000.0)) }
                }
                NumberField("Z seam Y (mm)", settings.zSeamYmm, source(state, SlicerSettings.Keys.Z_SEAM_Y)) {
                    onSettings(SlicerSettings.Keys.Z_SEAM_Y) { current -> current.copy(zSeamYmm = it.coerceIn(-2000.0, 2000.0)) }
                }
                SwitchRow("Coordinates relative to each model", settings.zSeamRelative, source(state, SlicerSettings.Keys.Z_SEAM_RELATIVE)) {
                    onSettings(SlicerSettings.Keys.Z_SEAM_RELATIVE) { current -> current.copy(zSeamRelative = it) }
                }
            }
            if (settings.zSeamType != "random") {
                OptionField(
                    label = "Seam corner preference",
                    value = settings.zSeamCorner,
                    options = listOf(
                        "z_seam_corner_none" to "None",
                        "z_seam_corner_inner" to "Hide seam",
                        "z_seam_corner_outer" to "Expose seam",
                        "z_seam_corner_any" to "Hide or expose",
                        "z_seam_corner_weighted" to "Smart hiding",
                    ),
                    source = source(state, SlicerSettings.Keys.Z_SEAM_CORNER),
                ) {
                    onSettings(SlicerSettings.Keys.Z_SEAM_CORNER) { current -> current.copy(zSeamCorner = it) }
                }
            }
            OptionField(
                label = "First-layer wall order",
                value = settings.initialLayerInsetDirection,
                options = listOf(
                    "inside_out" to "Inside to outside",
                    "outside_in" to "Outside to inside",
                ),
                source = source(state, SlicerSettings.Keys.INITIAL_LAYER_INSET_DIRECTION),
            ) {
                onSettings(SlicerSettings.Keys.INITIAL_LAYER_INSET_DIRECTION) { current -> current.copy(initialLayerInsetDirection = it) }
            }
            NumberField("Roofing expansion (mm)", settings.roofingExpansionMm, source(state, SlicerSettings.Keys.ROOFING_EXPANSION), decimals = 3) {
                onSettings(SlicerSettings.Keys.ROOFING_EXPANSION) { current -> current.copy(roofingExpansionMm = it.coerceIn(0.0, 10.0)) }
            }
            NumberField("Skin merge distance (mm)", settings.topBottomSkinMergeDistanceMm, source(state, SlicerSettings.Keys.TOP_BOTTOM_SKIN_MERGE_DISTANCE), decimals = 3) {
                onSettings(SlicerSettings.Keys.TOP_BOTTOM_SKIN_MERGE_DISTANCE) { current -> current.copy(topBottomSkinMergeDistanceMm = it.coerceIn(0.0, 50.0)) }
            }
            SwitchRow("Skin support", settings.skinSupportEnabled, source(state, SlicerSettings.Keys.SKIN_SUPPORT_ENABLED)) {
                onSettings(SlicerSettings.Keys.SKIN_SUPPORT_ENABLED) { current -> current.copy(skinSupportEnabled = it) }
            }
            if (settings.skinSupportEnabled) {
                NumberField("Skin support density (%)", settings.skinSupportDensityPercent, source(state, SlicerSettings.Keys.SKIN_SUPPORT_DENSITY)) {
                    onSettings(SlicerSettings.Keys.SKIN_SUPPORT_DENSITY) { current -> current.copy(skinSupportDensityPercent = it.coerceIn(0.0, 100.0)) }
                }
                NumberField("Skin support speed (mm/s)", settings.skinSupportSpeedMmPerSecond, source(state, SlicerSettings.Keys.SKIN_SUPPORT_SPEED)) {
                    onSettings(SlicerSettings.Keys.SKIN_SUPPORT_SPEED) { current -> current.copy(skinSupportSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
                }
                NumberField("Skin support flow (%)", settings.skinSupportMaterialFlowPercent, source(state, SlicerSettings.Keys.SKIN_SUPPORT_MATERIAL_FLOW)) {
                    onSettings(SlicerSettings.Keys.SKIN_SUPPORT_MATERIAL_FLOW) { current -> current.copy(skinSupportMaterialFlowPercent = it.coerceIn(1.0, 300.0)) }
                }
                NumberField("Skin support fan speed (%)", settings.skinSupportFanSpeedPercent, source(state, SlicerSettings.Keys.SKIN_SUPPORT_FAN_SPEED), decimals = 0) {
                    onSettings(SlicerSettings.Keys.SKIN_SUPPORT_FAN_SPEED) { current -> current.copy(skinSupportFanSpeedPercent = it.coerceIn(0.0, 100.0)) }
                }
            }
            SwitchRow("Interlace bridge lines", settings.bridgeInterlaceLines, source(state, SlicerSettings.Keys.BRIDGE_INTERLACE_LINES)) {
                onSettings(SlicerSettings.Keys.BRIDGE_INTERLACE_LINES) { current -> current.copy(bridgeInterlaceLines = it) }
            }
        }

        SettingsCategory("Infill") {
            NumberField("Infill density (%)", settings.infillDensityPercent, source(state, SlicerSettings.Keys.INFILL_DENSITY)) {
                onSettings(SlicerSettings.Keys.INFILL_DENSITY) { current -> current.copy(infillDensityPercent = it.coerceIn(0.0, 100.0)) }
            }
            OptionField(
                label = "Infill pattern",
                value = settings.infillPattern,
                options = listOf(
                    "grid" to "Grid",
                    "lines" to "Lines",
                    "triangles" to "Triangles",
                    "trihexagon" to "Tri-hexagon",
                    "cubic" to "Cubic",
                    "cubicsubdiv" to "Cubic subdivision",
                    // Cura's enum value for the pattern shown as "Octet" is tetrahedral. "octet"
    // is not a value the engine knows, and it maps unknown patterns to no infill at
    // all while still reporting a successful slice.
    "tetrahedral" to "Octet",
                    "quarter_cubic" to "Quarter cubic",
                    "concentric" to "Concentric",
                    "zigzag" to "Zig zag",
                    "cross" to "Cross",
                    "cross_3d" to "Cross 3D",
                    "gyroid" to "Gyroid",
                    "lightning" to "Lightning",
                ),
                source = source(state, SlicerSettings.Keys.INFILL_PATTERN),
            ) {
                onSettings(SlicerSettings.Keys.INFILL_PATTERN) { current -> current.copy(infillPattern = it) }
            }
            SwitchRow(
                "Connect infill lines",
                settings.zigZagConnectInfill,
                source(state, SlicerSettings.Keys.ZIG_ZAG_CONNECT_INFILL),
            ) {
                onSettings(SlicerSettings.Keys.ZIG_ZAG_CONNECT_INFILL) { current -> current.copy(zigZagConnectInfill = it) }
            }
            SwitchRow(
                "Adaptive walls by thickness",
                settings.thicknessAdaptiveWallsEnabled,
                source(state, SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_ENABLED),
            ) {
                onSettings(SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_ENABLED) { current -> current.copy(thicknessAdaptiveWallsEnabled = it) }
            }
            if (settings.thicknessAdaptiveWallsEnabled) {
                NumberField(
                    "Extra wall flow (%)",
                    settings.thicknessAdaptiveWallsFlowPercent,
                    source(state, SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_FLOW),
                ) {
                    onSettings(SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_FLOW) { current ->
                        current.copy(thicknessAdaptiveWallsFlowPercent = it.coerceIn(100.0, 200.0))
                    }
                }
                NumberField(
                    "Bend radius (mm)",
                    settings.thicknessAdaptiveWallsBendRadiusMm,
                    source(state, SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_BEND_RADIUS),
                ) {
                    onSettings(SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_BEND_RADIUS) { current ->
                        current.copy(thicknessAdaptiveWallsBendRadiusMm = it.coerceIn(0.5, 100.0))
                    }
                }
                NumberField(
                    "Extra walls",
                    settings.thicknessAdaptiveWallsExtraWalls.toDouble(),
                    source(state, SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_EXTRA_WALLS),
                    decimals = 0,
                ) {
                    onSettings(SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_EXTRA_WALLS) { current ->
                        current.copy(thicknessAdaptiveWallsExtraWalls = it.toInt().coerceIn(0, 20))
                    }
                }
            }
            OptionField(
                label = "Infill start/end",
                value = settings.infillStartEndPreference,
                options = listOf(
                    "start_closest" to "Start at closest point",
                    "start_random" to "Start at random point",
                    "end_close_to_seam" to "End close to seam",
                ),
                source = source(state, SlicerSettings.Keys.INFILL_START_END_PREFERENCE),
            ) {
                onSettings(SlicerSettings.Keys.INFILL_START_END_PREFERENCE) { current -> current.copy(infillStartEndPreference = it) }
            }
            NumberField("Infill move inwards length (mm)", settings.infillMoveInwardsLengthMm, source(state, SlicerSettings.Keys.INFILL_MOVE_INWARDS_LENGTH), decimals = 3) {
                onSettings(SlicerSettings.Keys.INFILL_MOVE_INWARDS_LENGTH) { current -> current.copy(infillMoveInwardsLengthMm = it.coerceIn(0.0, 100.0)) }
            }
            NumberField("Minimum infill line length (mm)", settings.minimumInfillLineLengthMm, source(state, SlicerSettings.Keys.MINIMUM_INFILL_LINE_LENGTH), decimals = 3) {
                onSettings(SlicerSettings.Keys.MINIMUM_INFILL_LINE_LENGTH) { current -> current.copy(minimumInfillLineLengthMm = it.coerceIn(0.0, 100.0)) }
            }
        }

        SettingsCategory("Speed") {
            NumberField("Print speed (mm/s)", settings.printSpeedMmPerSecond, source(state, SlicerSettings.Keys.PRINT_SPEED)) {
                onSettings(SlicerSettings.Keys.PRINT_SPEED) { current -> current.copy(printSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
            }
            NumberField("Wall speed (mm/s)", settings.wallSpeedMmPerSecond, source(state, SlicerSettings.Keys.WALL_SPEED)) {
                onSettings(SlicerSettings.Keys.WALL_SPEED) { current -> current.copy(wallSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
            }
            NumberField("Outer wall speed (mm/s)", settings.outerWallSpeedMmPerSecond, source(state, SlicerSettings.Keys.OUTER_WALL_SPEED)) {
                onSettings(SlicerSettings.Keys.OUTER_WALL_SPEED) { current -> current.copy(outerWallSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
            }
            NumberField("Inner wall speed (mm/s)", settings.innerWallSpeedMmPerSecond, source(state, SlicerSettings.Keys.INNER_WALL_SPEED)) {
                onSettings(SlicerSettings.Keys.INNER_WALL_SPEED) { current -> current.copy(innerWallSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
            }
            NumberField("Infill speed (mm/s)", settings.infillSpeedMmPerSecond, source(state, SlicerSettings.Keys.INFILL_SPEED)) {
                onSettings(SlicerSettings.Keys.INFILL_SPEED) { current -> current.copy(infillSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
            }
            NumberField("Top/bottom speed (mm/s)", settings.topBottomSpeedMmPerSecond, source(state, SlicerSettings.Keys.TOP_BOTTOM_SPEED)) {
                onSettings(SlicerSettings.Keys.TOP_BOTTOM_SPEED) { current -> current.copy(topBottomSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
            }
            NumberField("Travel speed (mm/s)", settings.travelSpeedMmPerSecond, source(state, SlicerSettings.Keys.TRAVEL_SPEED)) {
                onSettings(SlicerSettings.Keys.TRAVEL_SPEED) { current -> current.copy(travelSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
            }
            NumberField("Initial layer speed (mm/s)", settings.initialLayerSpeedMmPerSecond, source(state, SlicerSettings.Keys.INITIAL_LAYER_SPEED)) {
                onSettings(SlicerSettings.Keys.INITIAL_LAYER_SPEED) { current -> current.copy(initialLayerSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
            }
        }

        SettingsCategory("Material") {
            Text(
                "${settings.materialBrand} ${settings.materialType} · density ${settings.materialDensityGPerCm3} g/cm³",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (settings.materialGuid.isNotBlank()) {
                Text("Material GUID: ${settings.materialGuid}", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                "Enabled extruders: ${settings.enabledExtruderCount} (TrioSlicer currently slices with extruder 1)",
                style = MaterialTheme.typography.labelSmall,
            )
            NumberField("Nozzle temperature (°C)", settings.nozzleTemperatureC.toDouble(), source(state, SlicerSettings.Keys.NOZZLE_TEMPERATURE), decimals = 0) {
                onSettings(SlicerSettings.Keys.NOZZLE_TEMPERATURE) { current -> current.copy(nozzleTemperatureC = it.toInt().coerceIn(150, 500)) }
            }
            NumberField("Initial nozzle temperature (°C)", settings.initialNozzleTemperatureC.toDouble(), source(state, SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE), decimals = 0) {
                onSettings(SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE) { current -> current.copy(initialNozzleTemperatureC = it.toInt().coerceIn(150, 500)) }
            }
            NumberField("Bed temperature (°C)", settings.bedTemperatureC.toDouble(), source(state, SlicerSettings.Keys.BED_TEMPERATURE), decimals = 0) {
                onSettings(SlicerSettings.Keys.BED_TEMPERATURE) { current -> current.copy(bedTemperatureC = it.toInt().coerceIn(0, 200)) }
            }
            NumberField("Build-volume temperature (°C)", settings.buildVolumeTemperatureC, source(state, SlicerSettings.Keys.BUILD_VOLUME_TEMPERATURE), decimals = 1) {
                onSettings(SlicerSettings.Keys.BUILD_VOLUME_TEMPERATURE) { current -> current.copy(buildVolumeTemperatureC = it.coerceIn(-273.15, 285.0)) }
            }
            NumberField("Standby temperature (°C)", settings.materialStandbyTemperatureC, source(state, SlicerSettings.Keys.MATERIAL_STANDBY_TEMPERATURE), decimals = 0) {
                onSettings(SlicerSettings.Keys.MATERIAL_STANDBY_TEMPERATURE) { current -> current.copy(materialStandbyTemperatureC = it.coerceIn(-273.15, 500.0)) }
            }
            NumberField("Material density (g/cm³)", settings.materialDensityGPerCm3, source(state, SlicerSettings.Keys.MATERIAL_DENSITY), decimals = 3) {
                onSettings(SlicerSettings.Keys.MATERIAL_DENSITY) { current -> current.copy(materialDensityGPerCm3 = it.coerceIn(0.01, 100.0)) }
            }
            NumberField("Adhesion tendency (0–10)", settings.materialAdhesionTendency.toDouble(), source(state, SlicerSettings.Keys.MATERIAL_ADHESION_TENDENCY), decimals = 0) {
                onSettings(SlicerSettings.Keys.MATERIAL_ADHESION_TENDENCY) { current -> current.copy(materialAdhesionTendency = it.toInt().coerceIn(0, 10)) }
            }
            NumberField("Surface energy (%)", settings.materialSurfaceEnergyPercent.toDouble(), source(state, SlicerSettings.Keys.MATERIAL_SURFACE_ENERGY), decimals = 0) {
                onSettings(SlicerSettings.Keys.MATERIAL_SURFACE_ENERGY) { current -> current.copy(materialSurfaceEnergyPercent = it.toInt().coerceIn(0, 100)) }
            }
            NumberField("Material flow (%)", settings.materialFlowPercent, source(state, SlicerSettings.Keys.MATERIAL_FLOW)) {
                onSettings(SlicerSettings.Keys.MATERIAL_FLOW) { current -> current.copy(materialFlowPercent = it.coerceIn(1.0, 300.0)) }
            }
        }

        SettingsCategory("Cooling") {
            NumberField("Regular fan speed (%)", settings.fanSpeedPercent, source(state, SlicerSettings.Keys.FAN_SPEED)) {
                onSettings(SlicerSettings.Keys.FAN_SPEED) { current -> current.copy(fanSpeedPercent = it.coerceIn(0.0, 100.0)) }
            }
            NumberField("Initial fan speed (%)", settings.initialFanSpeedPercent, source(state, SlicerSettings.Keys.INITIAL_FAN_SPEED)) {
                onSettings(SlicerSettings.Keys.INITIAL_FAN_SPEED) { current -> current.copy(initialFanSpeedPercent = it.coerceIn(0.0, 100.0)) }
            }
            NumberField("Regular fan at layer", settings.fanFullAtLayer.toDouble(), source(state, SlicerSettings.Keys.FAN_FULL_AT_LAYER), decimals = 0) {
                onSettings(SlicerSettings.Keys.FAN_FULL_AT_LAYER) { current -> current.copy(fanFullAtLayer = it.toInt().coerceIn(0, 1000000)) }
            }
        }

        SettingsCategory("Supports") {
            SwitchRow("Generate supports", settings.supportsEnabled, source(state, SlicerSettings.Keys.SUPPORTS_ENABLED)) {
                onSettings(SlicerSettings.Keys.SUPPORTS_ENABLED) { current -> current.copy(supportsEnabled = it) }
            }
            if (settings.supportsEnabled) {
                OptionField(
                    label = "Structure",
                    value = settings.supportStructure,
                    options = listOf("tree" to "Tree", "normal" to "Normal"),
                    source = source(state, SlicerSettings.Keys.SUPPORT_STRUCTURE),
                ) {
                    onSettings(SlicerSettings.Keys.SUPPORT_STRUCTURE) { current -> current.copy(supportStructure = it) }
                }
                OptionField(
                    label = "Placement",
                    value = settings.supportPlacement,
                    options = listOf("everywhere" to "Everywhere", "buildplate" to "Build plate only"),
                    source = source(state, SlicerSettings.Keys.SUPPORT_PLACEMENT),
                ) {
                    onSettings(SlicerSettings.Keys.SUPPORT_PLACEMENT) { current -> current.copy(supportPlacement = it) }
                }
                NumberField("Overhang angle (°)", settings.supportAngleDegrees, source(state, SlicerSettings.Keys.SUPPORT_ANGLE)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_ANGLE) { current -> current.copy(supportAngleDegrees = it.coerceIn(0.0, 90.0)) }
                }
                NumberField("Support density (%)", settings.supportDensityPercent, source(state, SlicerSettings.Keys.SUPPORT_DENSITY)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_DENSITY) { current -> current.copy(supportDensityPercent = it.coerceIn(0.0, 100.0)) }
                }
                OptionField(
                    label = "Support pattern",
                    value = settings.supportPattern,
                    options = listOf(
                        "zigzag" to "Zig zag",
                        "lines" to "Lines",
                        "grid" to "Grid",
                        "triangles" to "Triangles",
                        "concentric" to "Concentric",
                        "cross" to "Cross",
                        "gyroid" to "Gyroid",
                    ),
                    source = source(state, SlicerSettings.Keys.SUPPORT_PATTERN),
                ) {
                    onSettings(SlicerSettings.Keys.SUPPORT_PATTERN) { current -> current.copy(supportPattern = it) }
                }
                SwitchRow("Support interface", settings.supportInterfaceEnabled, source(state, SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED) { current -> current.copy(supportInterfaceEnabled = it) }
                }
                if (settings.supportInterfaceEnabled) {
                    NumberField("Interface density (%)", settings.supportInterfaceDensityPercent, source(state, SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY)) {
                        onSettings(SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY) { current -> current.copy(supportInterfaceDensityPercent = it.coerceIn(0.0, 100.0)) }
                    }
                    NumberField("Interface thickness (mm)", settings.supportInterfaceHeightMm, source(state, SlicerSettings.Keys.SUPPORT_INTERFACE_HEIGHT)) {
                        onSettings(SlicerSettings.Keys.SUPPORT_INTERFACE_HEIGHT) { current -> current.copy(supportInterfaceHeightMm = it.coerceIn(0.0, 100.0)) }
                    }
                    NumberField("Interface speed (mm/s)", settings.supportInterfaceSpeedMmPerSecond, source(state, SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED)) {
                        onSettings(SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED) { current -> current.copy(supportInterfaceSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
                    }
                }
                NumberField("Z distance (mm)", settings.supportZDistanceMm, source(state, SlicerSettings.Keys.SUPPORT_Z_DISTANCE)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_Z_DISTANCE) { current -> current.copy(supportZDistanceMm = it.coerceIn(0.0, 20.0)) }
                }
                NumberField("XY distance (mm)", settings.supportXyDistanceMm, source(state, SlicerSettings.Keys.SUPPORT_XY_DISTANCE)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_XY_DISTANCE) { current -> current.copy(supportXyDistanceMm = it.coerceIn(0.0, 20.0)) }
                }
                NumberField("Support speed (mm/s)", settings.supportSpeedMmPerSecond, source(state, SlicerSettings.Keys.SUPPORT_SPEED)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_SPEED) { current -> current.copy(supportSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
                }
                NumberField("Support infill multiplier", settings.supportInfillMultiplier.toDouble(), source(state, SlicerSettings.Keys.SUPPORT_INFILL_MULTIPLIER), decimals = 0) {
                    onSettings(SlicerSettings.Keys.SUPPORT_INFILL_MULTIPLIER) { current -> current.copy(supportInfillMultiplier = it.toInt().coerceIn(1, 100)) }
                }
                NumberField("Support brim minimum hole area (mm²)", settings.supportBrimMinimumHoleAreaMm2, source(state, SlicerSettings.Keys.SUPPORT_BRIM_MINIMUM_HOLE_AREA), decimals = 2) {
                    onSettings(SlicerSettings.Keys.SUPPORT_BRIM_MINIMUM_HOLE_AREA) { current -> current.copy(supportBrimMinimumHoleAreaMm2 = it.coerceIn(0.0, 10000.0)) }
                }
            }
        }

        SettingsCategory("Travel and retraction") {
            NumberField("Retraction distance (mm)", settings.retractionDistanceMm, source(state, SlicerSettings.Keys.RETRACTION_DISTANCE)) {
                onSettings(SlicerSettings.Keys.RETRACTION_DISTANCE) { current -> current.copy(retractionDistanceMm = it.coerceIn(0.0, 100.0)) }
            }
            NumberField("Retraction speed (mm/s)", settings.retractionSpeedMmPerSecond, source(state, SlicerSettings.Keys.RETRACTION_SPEED)) {
                onSettings(SlicerSettings.Keys.RETRACTION_SPEED) { current -> current.copy(retractionSpeedMmPerSecond = it.coerceIn(0.0, 1000.0)) }
            }
            NumberField("Minimum retraction travel (mm)", settings.retractionMinimumTravelMm, source(state, SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL)) {
                onSettings(SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL) { current -> current.copy(retractionMinimumTravelMm = it.coerceIn(0.0, 1000.0)) }
            }
            SwitchRow("Retract at layer change", settings.retractAtLayerChange, source(state, SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE)) {
                onSettings(SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE) { current -> current.copy(retractAtLayerChange = it) }
            }
            OptionField(
                label = "Combing mode",
                value = settings.combingMode,
                options = listOf(
                    "off" to "Off",
                    "all" to "All",
                    "noskin" to "Not in skin",
                    "infill" to "Within infill",
                ),
                source = source(state, SlicerSettings.Keys.COMBING_MODE),
            ) {
                onSettings(SlicerSettings.Keys.COMBING_MODE) { current -> current.copy(combingMode = it) }
            }
            OptionField(
                label = "Retract before outer wall",
                value = settings.travelRetractBeforeOuterWall,
                options = listOf(
                    "automatic" to "Automatic",
                    "force_retracted" to "Always retract",
                    "force_not_retracted" to "Never retract",
                    "force_not_retracted_from_infill" to "Unretracted from infill",
                ),
                source = source(state, SlicerSettings.Keys.TRAVEL_RETRACT_BEFORE_OUTER_WALL),
            ) {
                onSettings(SlicerSettings.Keys.TRAVEL_RETRACT_BEFORE_OUTER_WALL) { current -> current.copy(travelRetractBeforeOuterWall = it) }
            }
            SwitchRow("Avoid printed parts when travelling", settings.avoidPrintedParts, source(state, SlicerSettings.Keys.AVOID_PRINTED_PARTS)) {
                onSettings(SlicerSettings.Keys.AVOID_PRINTED_PARTS) { current -> current.copy(avoidPrintedParts = it) }
            }
            if (settings.avoidPrintedParts) {
                NumberField("Travel avoid distance (mm)", settings.travelAvoidDistanceMm, source(state, SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE)) {
                    onSettings(SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE) { current -> current.copy(travelAvoidDistanceMm = it.coerceIn(0.0, 100.0)) }
                }
            }
            SwitchRow("Z hop", settings.zHopEnabled, source(state, SlicerSettings.Keys.Z_HOP)) {
                onSettings(SlicerSettings.Keys.Z_HOP) { current -> current.copy(zHopEnabled = it) }
            }
            if (settings.zHopEnabled) {
                NumberField("Z hop height (mm)", settings.zHopHeightMm, source(state, SlicerSettings.Keys.Z_HOP_HEIGHT)) {
                    onSettings(SlicerSettings.Keys.Z_HOP_HEIGHT) { current -> current.copy(zHopHeightMm = it.coerceIn(0.0, 100.0)) }
                }
            }
            SwitchRow("Firmware retraction", settings.firmwareRetraction, source(state, SlicerSettings.Keys.FIRMWARE_RETRACTION)) {
                onSettings(SlicerSettings.Keys.FIRMWARE_RETRACTION) { current -> current.copy(firmwareRetraction = it) }
            }
            SwitchRow("Enable coasting", settings.coastingEnabled, source(state, SlicerSettings.Keys.COASTING_ENABLED)) {
                onSettings(SlicerSettings.Keys.COASTING_ENABLED) { current -> current.copy(coastingEnabled = it) }
            }
            if (settings.coastingEnabled) {
                NumberField("Coasting volume (mm³)", settings.coastingVolumeMm3, source(state, SlicerSettings.Keys.COASTING_VOLUME), decimals = 3) {
                    onSettings(SlicerSettings.Keys.COASTING_VOLUME) { current -> current.copy(coastingVolumeMm3 = it.coerceIn(0.0, 1000.0)) }
                }
                NumberField("Minimum volume before coasting (mm³)", settings.coastingMinimumVolumeMm3, source(state, SlicerSettings.Keys.COASTING_MINIMUM_VOLUME), decimals = 3) {
                    onSettings(SlicerSettings.Keys.COASTING_MINIMUM_VOLUME) { current -> current.copy(coastingMinimumVolumeMm3 = it.coerceIn(0.0, 100000.0)) }
                }
                NumberField("Coasting speed (%)", settings.coastingSpeedPercent, source(state, SlicerSettings.Keys.COASTING_SPEED)) {
                    onSettings(SlicerSettings.Keys.COASTING_SPEED) { current -> current.copy(coastingSpeedPercent = it.coerceIn(0.0001, 1000.0)) }
                }
            }
        }

        SettingsCategory("Build plate adhesion") {
            OptionField(
                label = "Adhesion type",
                value = settings.adhesionType,
                options = listOf("none" to "None", "skirt" to "Skirt", "brim" to "Brim", "raft" to "Raft"),
                source = source(state, SlicerSettings.Keys.ADHESION_TYPE),
            ) {
                onSettings(SlicerSettings.Keys.ADHESION_TYPE) { current -> current.copy(adhesionType = it) }
            }
            if (settings.adhesionType == "skirt") {
                NumberField("Skirt line count", settings.skirtLineCount.toDouble(), source(state, SlicerSettings.Keys.SKIRT_LINE_COUNT), decimals = 0) {
                    onSettings(SlicerSettings.Keys.SKIRT_LINE_COUNT) { current -> current.copy(skirtLineCount = it.toInt().coerceIn(0, 1000)) }
                }
            }
            if (settings.adhesionType == "brim") {
                NumberField("Brim width (mm)", settings.brimWidthMm, source(state, SlicerSettings.Keys.BRIM_WIDTH)) {
                    onSettings(SlicerSettings.Keys.BRIM_WIDTH) { current -> current.copy(brimWidthMm = it.coerceIn(0.0, 100.0)) }
                }
            }
            if (settings.adhesionType == "raft") {
                NumberField("Raft extra margin (mm)", settings.raftMarginMm, source(state, SlicerSettings.Keys.RAFT_MARGIN)) {
                    onSettings(SlicerSettings.Keys.RAFT_MARGIN) { current -> current.copy(raftMarginMm = it.coerceIn(0.0, 100.0)) }
                }
            }
        }

        SettingsCategory("Experimental") {
            NumberField("Time estimate factor (%)", settings.machineTimeEstimationFactorPercent, source(state, SlicerSettings.Keys.MACHINE_TIME_ESTIMATION_FACTOR)) {
                onSettings(SlicerSettings.Keys.MACHINE_TIME_ESTIMATION_FACTOR) { current -> current.copy(machineTimeEstimationFactorPercent = it.coerceIn(1.0, 1000.0)) }
            }
            SwitchRow(
                "Smart overhang strategy",
                settings.smartOverhangStrategy,
                source(state, SlicerSettings.Keys.SMART_OVERHANG_STRATEGY),
            ) {
                onSettings(SlicerSettings.Keys.SMART_OVERHANG_STRATEGY) { current ->
                    current.copy(smartOverhangStrategy = it)
                }
            }
            if (settings.smartOverhangStrategy) {
                Text(
                    "The slicer inspects the model before slicing and decides where to use arc fill and non-planar curved layers automatically, including a safe combined mode when non-planar printing is enabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SwitchRow(
                "Wave overhangs (experimental)",
                settings.waveOverhangEnabled,
                source(state, SlicerSettings.Keys.WAVE_OVERHANG_ENABLED),
            ) {
                onSettings(SlicerSettings.Keys.WAVE_OVERHANG_ENABLED) { current ->
                    current.copy(
                        waveOverhangEnabled = it,
                        arcOverhangEnabled = if (it) false else current.arcOverhangEnabled,
                        brickWallEnabled = if (it) false else current.brickWallEnabled,
                        masonryWallsEnabled = if (it) false else current.masonryWallsEnabled,
                    )
                }
            }
            if (settings.waveOverhangEnabled) {
                Text(
                    "Expanding wavefronts grow from model-supported material into open-air bottom skin. Use maximum cooling and verify the layer preview before printing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OptionField(
                    label = "Wave traversal",
                    value = settings.waveOverhangPattern,
                    options = listOf(
                        "smart" to "Smart · supported-first",
                        "monotonic" to "Monotonic · independent fronts",
                        "zigzag" to "Zigzag · alternating fronts",
                    ),
                    source = source(state, SlicerSettings.Keys.WAVE_OVERHANG_PATTERN),
                ) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_PATTERN) { current -> current.copy(waveOverhangPattern = it) }
                }
                NumberField("Wave spacing (mm)", settings.waveOverhangLineSpacingMm, source(state, SlicerSettings.Keys.WAVE_OVERHANG_LINE_SPACING), decimals = 3) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_LINE_SPACING) { current -> current.copy(waveOverhangLineSpacingMm = it.coerceIn(0.1, 2.0)) }
                }
                NumberField("Wave flow (mm³/mm)", settings.waveOverhangFlowMm3PerMm, source(state, SlicerSettings.Keys.WAVE_OVERHANG_FLOW), decimals = 3) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_FLOW) { current -> current.copy(waveOverhangFlowMm3PerMm = it.coerceIn(0.02, 1.5)) }
                }
                NumberField("Wave speed (mm/s)", settings.waveOverhangSpeedMmPerSecond, source(state, SlicerSettings.Keys.WAVE_OVERHANG_SPEED)) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_SPEED) { current -> current.copy(waveOverhangSpeedMmPerSecond = it.coerceIn(0.5, 50.0)) }
                }
                NumberField("Wave fan speed (%)", settings.waveOverhangFanSpeedPercent, source(state, SlicerSettings.Keys.WAVE_OVERHANG_FAN_SPEED), decimals = 0) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_FAN_SPEED) { current -> current.copy(waveOverhangFanSpeedPercent = it.coerceIn(0.0, 100.0)) }
                }
                NumberField("Perimeter overlap (mm)", settings.waveOverhangPerimeterOverlapMm, source(state, SlicerSettings.Keys.WAVE_OVERHANG_PERIMETER_OVERLAP), decimals = 3) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_PERIMETER_OVERLAP) { current -> current.copy(waveOverhangPerimeterOverlapMm = it.coerceIn(0.0, 2.0)) }
                }
                NumberField("Minimum wave width (mm)", settings.waveOverhangMinimumWidthMm, source(state, SlicerSettings.Keys.WAVE_OVERHANG_MINIMUM_WIDTH), decimals = 3) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_MINIMUM_WIDTH) { current -> current.copy(waveOverhangMinimumWidthMm = it.coerceIn(0.0, 10.0)) }
                }
                NumberField("Maximum wavefronts", settings.waveOverhangMaxIterations.toDouble(), source(state, SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS), decimals = 0) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS) { current -> current.copy(waveOverhangMaxIterations = it.toInt().coerceIn(1, 2000)) }
                }
                SwitchRow("Reverse wave direction on odd layers", settings.waveOverhangReverseOddLayers, source(state, SlicerSettings.Keys.WAVE_OVERHANG_REVERSE_ODD_LAYERS)) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_REVERSE_ODD_LAYERS) { current -> current.copy(waveOverhangReverseOddLayers = it) }
                }
            }

            SwitchRow(
                "Brick walls (experimental)",
                settings.brickWallEnabled,
                source(state, SlicerSettings.Keys.BRICK_WALL_ENABLED),
            ) {
                onSettings(SlicerSettings.Keys.BRICK_WALL_ENABLED) { current ->
                    current.copy(
                        brickWallEnabled = it,
                        arcOverhangEnabled = if (it) false else current.arcOverhangEnabled,
                        waveOverhangEnabled = if (it) false else current.waveOverhangEnabled,
                        masonryWallsEnabled = if (it) false else current.masonryWallsEnabled,
                    )
                }
            }
            if (settings.brickWallEnabled) {
                Text(
                    "Staggered, interlocking wall courses are laid down under step-out walls before the walls print. Steep overhang regions get extra courses based on the local angle, and regions that cannot be covered safely keep normal walls. Verify the layer preview and start with a printed overhang test before trusting it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NumberField("Brick course speed (mm/s)", settings.brickWallSpeedMmPerSecond, source(state, SlicerSettings.Keys.BRICK_WALL_SPEED)) {
                    onSettings(SlicerSettings.Keys.BRICK_WALL_SPEED) { current -> current.copy(brickWallSpeedMmPerSecond = it.coerceIn(0.5, 100.0)) }
                }
                NumberField("Brick course flow (%)", settings.brickWallFlowPercent, source(state, SlicerSettings.Keys.BRICK_WALL_FLOW), decimals = 0) {
                    onSettings(SlicerSettings.Keys.BRICK_WALL_FLOW) { current -> current.copy(brickWallFlowPercent = it.coerceIn(50.0, 200.0)) }
                }
                NumberField("Brick course fan (%)", settings.brickWallFanSpeedPercent, source(state, SlicerSettings.Keys.BRICK_WALL_FAN_SPEED), decimals = 0) {
                    onSettings(SlicerSettings.Keys.BRICK_WALL_FAN_SPEED) { current -> current.copy(brickWallFanSpeedPercent = it.coerceIn(0.0, 100.0)) }
                }
                NumberField("Maximum courses", settings.brickWallMaxIterations.toDouble(), source(state, SlicerSettings.Keys.BRICK_WALL_MAX_ITERATIONS), decimals = 0) {
                    onSettings(SlicerSettings.Keys.BRICK_WALL_MAX_ITERATIONS) { current -> current.copy(brickWallMaxIterations = it.toInt().coerceIn(2, 200)) }
                }
                NumberField("Brick length (mm)", settings.brickWallBrickLengthMm, source(state, SlicerSettings.Keys.BRICK_WALL_BRICK_LENGTH), decimals = 1) {
                    onSettings(SlicerSettings.Keys.BRICK_WALL_BRICK_LENGTH) { current -> current.copy(brickWallBrickLengthMm = it.coerceIn(0.5, 10.0)) }
                }
            }

            SwitchRow(
                "Masonry-bonded walls (experimental)",
                settings.masonryWallsEnabled,
                source(state, SlicerSettings.Keys.MASONRY_WALLS_ENABLED),
            ) {
                onSettings(SlicerSettings.Keys.MASONRY_WALLS_ENABLED) { current ->
                    current.copy(
                        masonryWallsEnabled = it,
                        arcOverhangEnabled = if (it) false else current.arcOverhangEnabled,
                        waveOverhangEnabled = if (it) false else current.waveOverhangEnabled,
                        brickWallEnabled = if (it) false else current.brickWallEnabled,
                    )
                }
            }
            if (settings.masonryWallsEnabled) {
                Text(
                    "Every wall leans alternately ± half a bead per layer, so each bead rests on the shoulder of the bead beneath instead of stacking flat; the outer surface gets a fine ±0.2 mm zigzag.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SwitchRow(
                "Arc overhangs (Multiplex, experimental)",
                settings.arcOverhangEnabled,
                source(state, SlicerSettings.Keys.ARC_OVERHANG_ENABLED),
            ) {
                onSettings(SlicerSettings.Keys.ARC_OVERHANG_ENABLED) { current ->
                    current.copy(
                        arcOverhangEnabled = it,
                        waveOverhangEnabled = if (it) false else current.waveOverhangEnabled,
                        brickWallEnabled = if (it) false else current.brickWallEnabled,
                        masonryWallsEnabled = if (it) false else current.masonryWallsEnabled,
                    )
                }
            }
            if (settings.arcOverhangEnabled) {
                Text(
                    "Native CuraEngine experiment. Unsupported bottom-skin regions are filled from one anchored centre using expanding clipped arcs. Cura's normal bridge lines are used automatically when an island has no safe anchor or exceeds the configured limits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.adaptiveLayerHeightEnabled) {
                    Text(
                        "Adaptive layers are accepted, but arc-overhang tuning is most predictable with a fixed layer height.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                NumberField(
                    "Arc speed (mm/s)",
                    settings.arcOverhangSpeedMmPerSecond,
                    source(state, SlicerSettings.Keys.ARC_OVERHANG_SPEED),
                ) {
                    onSettings(SlicerSettings.Keys.ARC_OVERHANG_SPEED) { current ->
                        current.copy(arcOverhangSpeedMmPerSecond = it.coerceIn(0.5, 50.0))
                    }
                }
                NumberField(
                    "Arc flow (%)",
                    settings.arcOverhangFlowPercent,
                    source(state, SlicerSettings.Keys.ARC_OVERHANG_FLOW),
                ) {
                    onSettings(SlicerSettings.Keys.ARC_OVERHANG_FLOW) { current ->
                        current.copy(arcOverhangFlowPercent = it.coerceIn(50.0, 200.0))
                    }
                }
                NumberField(
                    "Line spacing (% of bridge width)",
                    settings.arcOverhangLineSpacingPercent,
                    source(state, SlicerSettings.Keys.ARC_OVERHANG_LINE_SPACING),
                ) {
                    onSettings(SlicerSettings.Keys.ARC_OVERHANG_LINE_SPACING) { current ->
                        current.copy(arcOverhangLineSpacingPercent = it.coerceIn(50.0, 200.0))
                    }
                }
                NumberField(
                    "Minimum arc radius (mm)",
                    settings.arcOverhangMinRadiusMm,
                    source(state, SlicerSettings.Keys.ARC_OVERHANG_MIN_RADIUS),
                ) {
                    onSettings(SlicerSettings.Keys.ARC_OVERHANG_MIN_RADIUS) { current ->
                        val minimum = it.coerceIn(0.1, 20.0)
                        current.copy(
                            arcOverhangMinRadiusMm = minimum,
                            arcOverhangMaxRadiusMm = current.arcOverhangMaxRadiusMm.coerceAtLeast(minimum),
                        )
                    }
                }
                NumberField(
                    "Maximum arc radius (mm)",
                    settings.arcOverhangMaxRadiusMm,
                    source(state, SlicerSettings.Keys.ARC_OVERHANG_MAX_RADIUS),
                ) {
                    onSettings(SlicerSettings.Keys.ARC_OVERHANG_MAX_RADIUS) { current ->
                        current.copy(arcOverhangMaxRadiusMm = it.coerceIn(current.arcOverhangMinRadiusMm, 100.0))
                    }
                }
                NumberField(
                    "Maximum converted island area (mm²)",
                    settings.arcOverhangMaxAreaMm2,
                    source(state, SlicerSettings.Keys.ARC_OVERHANG_MAX_AREA),
                    decimals = 0,
                ) {
                    onSettings(SlicerSettings.Keys.ARC_OVERHANG_MAX_AREA) { current ->
                        current.copy(arcOverhangMaxAreaMm2 = it.coerceIn(1.0, 10_000.0))
                    }
                }
                NumberField(
                    "Arc chord tolerance (mm)",
                    settings.arcOverhangResolutionMm,
                    source(state, SlicerSettings.Keys.ARC_OVERHANG_RESOLUTION),
                    decimals = 3,
                ) {
                    onSettings(SlicerSettings.Keys.ARC_OVERHANG_RESOLUTION) { current ->
                        current.copy(arcOverhangResolutionMm = it.coerceIn(0.02, 1.0))
                    }
                }
                NumberField(
                    "Arc fan speed (%)",
                    settings.arcOverhangFanSpeedPercent,
                    source(state, SlicerSettings.Keys.ARC_OVERHANG_FAN_SPEED),
                ) {
                    onSettings(SlicerSettings.Keys.ARC_OVERHANG_FAN_SPEED) { current ->
                        current.copy(arcOverhangFanSpeedPercent = it.coerceIn(0.0, 100.0))
                    }
                }
                Text(
                    "This option evaluates bottom skin against the previous model layer, including one-sided cantilevers that Cura may not classify as conventional bridges. It does not automatically remove generated support; disable or limit supports when testing support-free overhangs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SwitchRow("Ironing", settings.ironingEnabled, source(state, SlicerSettings.Keys.IRONING_ENABLED)) {
                onSettings(SlicerSettings.Keys.IRONING_ENABLED) { current -> current.copy(ironingEnabled = it) }
            }
            if (settings.ironingEnabled) {
                SwitchRow(
                    "Iron only the highest layer",
                    settings.ironingOnlyHighestLayer,
                    source(state, SlicerSettings.Keys.IRONING_ONLY_HIGHEST_LAYER),
                ) {
                    onSettings(SlicerSettings.Keys.IRONING_ONLY_HIGHEST_LAYER) { current -> current.copy(ironingOnlyHighestLayer = it) }
                }
                NumberField("Ironing flow (%)", settings.ironingFlowPercent, source(state, SlicerSettings.Keys.IRONING_FLOW)) {
                    onSettings(SlicerSettings.Keys.IRONING_FLOW) { current -> current.copy(ironingFlowPercent = it.coerceIn(0.0, 100.0)) }
                }
                NumberField("Ironing speed (mm/s)", settings.ironingSpeedMmPerSecond, source(state, SlicerSettings.Keys.IRONING_SPEED)) {
                    onSettings(SlicerSettings.Keys.IRONING_SPEED) { current -> current.copy(ironingSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun SettingsCategory(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable Column.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(if (expanded) "Hide" else "Show", style = MaterialTheme.typography.labelLarge)
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
/** User-facing label for a Cura infill pattern value. */

private fun CalculatedField(
    label: String,
    value: Double,
    explanation: String,
) {
    Column {
        OutlinedTextField(
            value = "%.3f".format(value).trimEnd('0').trimEnd('.'),
            onValueChange = {},
            label = { Text(label) },
            singleLine = true,
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(explanation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


