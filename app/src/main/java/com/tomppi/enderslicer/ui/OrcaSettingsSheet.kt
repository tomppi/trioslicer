package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.AllSettingsCatalogs
import com.tomppi.enderslicer.model.OrcaPresetCatalog
import com.tomppi.enderslicer.model.OrcaSliceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OrcaSlicer slice settings sheet.
 *
 * OrcaSlicer is a PrusaSlicer fork with the Bambu option vocabulary, so the labels here follow
 * that vocabulary (walls, sparse infill) rather than PrusaSlicer's. The sheet edits
 * [MainUiState.orcaSettings]; the machine preset picker reads the bundled vendor bundles
 * through [OrcaPresetCatalog] and takes the process and filament the chosen machine preselects,
 * which is how the desktop app presents a printer.
 */
@Composable
internal fun OrcaSettingsSheet(
    state: MainUiState,
    onSettings: (String, (OrcaSliceSettings) -> OrcaSliceSettings) -> Unit,
    onOpenAllSettings: () -> Unit = {},
    onImportProfile: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val settings = state.orcaSettings
    val context = LocalContext.current
    // The engine's own min/max per key: an edit outside them is one the engine would
    // refuse. The catalogue is 200 KB of JSON, so it is parsed off the main thread.
    var ranges by remember { mutableStateOf<Map<String, ClosedFloatingPointRange<Double>>?>(null) }
    val liveSettings = rememberUpdatedState(settings)
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { AllSettingsCatalogs.orcaRanges(context.assets) }
        ranges = loaded
        // An edit made before the ranges arrived went in unclamped, so every numeric
        // field is brought inside them now rather than leaving a value the engine would
        // refuse. Read the settings as they are now: the effect's own copy is from when
        // the sheet opened, and re-applying that would undo an edit made since.
        val current = liveSettings.value
        val corrected = clampAllToEngine(current, loaded)
        if (corrected != current) onSettings("engine_limits") { corrected }
    }
    val engineRanges = ranges.orEmpty()
    var pickerOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("OrcaSlicer settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The engine slices the machine, process and filament presets you pick below; every " +
                "field here is layered over them, so nothing has to restate a whole profile.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Printer preset: " + settings.printerPreset)
        }
        Text("Process: " + settings.processPreset, style = MaterialTheme.typography.bodySmall)
        Text("Filament: " + settings.filamentPreset, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onImportProfile, modifier = Modifier.fillMaxWidth()) {
            Text("Import settings from OrcaSlicer profile (.json, .orca_filament, .zip)")
        }
        Text(
            "A profile's own values are layered over the presets above, the base preset it inherits " +
                "from is selected with it, and a printer profile also sets the machine envelope.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onOpenAllSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Add more settings (all settings)")
        }
        OutlinedButton(
            onClick = { onSettings(OrcaSliceSettings.Keys.LAYER_HEIGHT) { OrcaSliceSettings() } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset to engine defaults")
        }

        Text("Quality", style = MaterialTheme.typography.titleMedium)
        NumberField(
            label = "Layer height (mm)",
            value = settings.layerHeightMm,
            source = source(state, OrcaSliceSettings.Keys.LAYER_HEIGHT),
            onValue = { value ->
                onSettings(OrcaSliceSettings.Keys.LAYER_HEIGHT) {
                    it.copy(layerHeightMm = clampToEngine("layer_height", value, engineRanges))
                }
            },
        )
        NumberField(
            label = "First layer height (mm)",
            value = settings.firstLayerHeightMm,
            source = source(state, OrcaSliceSettings.Keys.FIRST_LAYER_HEIGHT),
            onValue = { value ->
                onSettings(OrcaSliceSettings.Keys.FIRST_LAYER_HEIGHT) {
                    it.copy(firstLayerHeightMm = clampToEngine("initial_layer_print_height", value, engineRanges))
                }
            },
        )

        Text("Walls and shells", style = MaterialTheme.typography.titleMedium)
        IntegerSettingField(
            label = "Wall loops",
            text = settings.wallLoops.toString(),
            minimum = 1,
            maximum = 12,
            onText = { text ->
                parseInteger(text, 1, 12)?.let { value ->
                    onSettings(OrcaSliceSettings.Keys.WALL_LOOPS) { it.copy(wallLoops = value) }
                }
            },
        )
        IntegerSettingField(
            label = "Top shell layers",
            text = settings.topShellLayers.toString(),
            minimum = 1,
            maximum = 20,
            onText = { text ->
                parseInteger(text, 1, 20)?.let { value ->
                    onSettings(OrcaSliceSettings.Keys.TOP_SHELL_LAYERS) { it.copy(topShellLayers = value) }
                }
            },
        )
        IntegerSettingField(
            label = "Bottom shell layers",
            text = settings.bottomShellLayers.toString(),
            minimum = 1,
            maximum = 20,
            onText = { text ->
                parseInteger(text, 1, 20)?.let { value ->
                    onSettings(OrcaSliceSettings.Keys.BOTTOM_SHELL_LAYERS) { it.copy(bottomShellLayers = value) }
                }
            },
        )

        Text("Infill", style = MaterialTheme.typography.titleMedium)
        NumberField(
            label = "Sparse infill density (%)",
            value = settings.sparseInfillDensityPercent,
            source = source(state, OrcaSliceSettings.Keys.SPARSE_INFILL_DENSITY),
            onValue = { value ->
                onSettings(OrcaSliceSettings.Keys.SPARSE_INFILL_DENSITY) {
                    it.copy(sparseInfillDensityPercent = clampToEngine("sparse_infill_density", value, engineRanges))
                }
            },
        )
        OptionField(
            label = "Sparse infill pattern",
            value = settings.sparseInfillPattern,
            options = OrcaSliceSettings.INFILL_PATTERNS.map { it to it },
            source = source(state, OrcaSliceSettings.Keys.SPARSE_INFILL_PATTERN),
            onValue = { value -> onSettings(OrcaSliceSettings.Keys.SPARSE_INFILL_PATTERN) { it.copy(sparseInfillPattern = value) } },
        )

        Text("Support", style = MaterialTheme.typography.titleMedium)
        SettingSwitch(
            title = "Generate support",
            description = "Support is generated everywhere the overhang angle is exceeded",
            checked = settings.supportEnabled,
            onChecked = { value -> onSettings(OrcaSliceSettings.Keys.SUPPORT_ENABLED) { it.copy(supportEnabled = value) } },
        )
        IntegerSettingField(
            label = "Support threshold angle (degrees)",
            text = settings.supportThresholdAngleDegrees.toString(),
            minimum = 0,
            maximum = 90,
            onText = { text ->
                parseInteger(text, 0, 90)?.let { value ->
                    onSettings(OrcaSliceSettings.Keys.SUPPORT_THRESHOLD_ANGLE) { it.copy(supportThresholdAngleDegrees = value) }
                }
            },
        )
        OptionField(
            label = "Support base pattern",
            value = settings.supportBasePattern,
            options = OrcaSliceSettings.SUPPORT_PATTERNS.map { it to it },
            source = source(state, OrcaSliceSettings.Keys.SUPPORT_BASE_PATTERN),
            onValue = { value -> onSettings(OrcaSliceSettings.Keys.SUPPORT_BASE_PATTERN) { it.copy(supportBasePattern = value) } },
        )
        IntegerSettingField(
            label = "Support interface top layers",
            text = settings.supportInterfaceTopLayers.toString(),
            minimum = 0,
            maximum = 8,
            onText = { text ->
                parseInteger(text, 0, 8)?.let { value ->
                    onSettings(OrcaSliceSettings.Keys.SUPPORT_INTERFACE_TOP_LAYERS) {
                        it.copy(supportInterfaceTopLayers = value)
                    }
                }
            },
        )

        Text("Skirt and brim", style = MaterialTheme.typography.titleMedium)
        IntegerSettingField(
            label = "Skirt loops",
            text = settings.skirtLoops.toString(),
            minimum = 0,
            maximum = 10,
            onText = { text ->
                parseInteger(text, 0, 10)?.let { value ->
                    onSettings(OrcaSliceSettings.Keys.SKIRT_LOOPS) { it.copy(skirtLoops = value) }
                }
            },
        )
        NumberField(
            label = "Brim width (mm)",
            value = settings.brimWidthMm,
            source = source(state, OrcaSliceSettings.Keys.BRIM_WIDTH),
            onValue = { value ->
                onSettings(OrcaSliceSettings.Keys.BRIM_WIDTH) {
                    it.copy(brimWidthMm = clampToEngine("brim_width", value, engineRanges))
                }
            },
        )

        Text("Speed", style = MaterialTheme.typography.titleMedium)
        NumberField(
            label = "Inner wall speed (mm/s)",
            value = settings.innerWallSpeedMmPerSecond,
            source = source(state, OrcaSliceSettings.Keys.INNER_WALL_SPEED),
            onValue = { value ->
                onSettings(OrcaSliceSettings.Keys.INNER_WALL_SPEED) {
                    it.copy(innerWallSpeedMmPerSecond = clampToEngine("inner_wall_speed", value, engineRanges))
                }
            },
        )
        NumberField(
            label = "Outer wall speed (mm/s)",
            value = settings.outerWallSpeedMmPerSecond,
            source = source(state, OrcaSliceSettings.Keys.OUTER_WALL_SPEED),
            onValue = { value ->
                onSettings(OrcaSliceSettings.Keys.OUTER_WALL_SPEED) {
                    it.copy(outerWallSpeedMmPerSecond = clampToEngine("outer_wall_speed", value, engineRanges))
                }
            },
        )
        NumberField(
            label = "Sparse infill speed (mm/s)",
            value = settings.sparseInfillSpeedMmPerSecond,
            source = source(state, OrcaSliceSettings.Keys.SPARSE_INFILL_SPEED),
            onValue = { value ->
                onSettings(OrcaSliceSettings.Keys.SPARSE_INFILL_SPEED) {
                    it.copy(sparseInfillSpeedMmPerSecond = clampToEngine("sparse_infill_speed", value, engineRanges))
                }
            },
        )
        NumberField(
            label = "Travel speed (mm/s)",
            value = settings.travelSpeedMmPerSecond,
            source = source(state, OrcaSliceSettings.Keys.TRAVEL_SPEED),
            onValue = { value ->
                onSettings(OrcaSliceSettings.Keys.TRAVEL_SPEED) {
                    it.copy(travelSpeedMmPerSecond = clampToEngine("travel_speed", value, engineRanges))
                }
            },
        )

        Text("Temperature and fan", style = MaterialTheme.typography.titleMedium)
        IntegerSettingField(
            label = "Nozzle temperature (C)",
            text = settings.nozzleTemperatureC.toString(),
            minimum = 0,
            maximum = 350,
            onText = { text ->
                parseInteger(text, 0, 350)?.let { value ->
                    onSettings(OrcaSliceSettings.Keys.NOZZLE_TEMPERATURE) { it.copy(nozzleTemperatureC = value) }
                }
            },
        )
        IntegerSettingField(
            label = "First layer nozzle temperature (C)",
            text = settings.initialLayerNozzleTemperatureC.toString(),
            minimum = 0,
            maximum = 350,
            onText = { text ->
                parseInteger(text, 0, 350)?.let { value ->
                    onSettings(OrcaSliceSettings.Keys.INITIAL_LAYER_NOZZLE_TEMPERATURE) { it.copy(initialLayerNozzleTemperatureC = value) }
                }
            },
        )
        IntegerSettingField(
            label = "Hot plate temperature (C)",
            text = settings.hotPlateTemperatureC.toString(),
            minimum = 0,
            maximum = 150,
            onText = { text ->
                parseInteger(text, 0, 150)?.let { value ->
                    onSettings(OrcaSliceSettings.Keys.HOT_PLATE_TEMPERATURE) { it.copy(hotPlateTemperatureC = value) }
                }
            },
        )
        IntegerSettingField(
            label = "First layer hot plate temperature (C)",
            text = settings.initialLayerHotPlateTemperatureC.toString(),
            minimum = 0,
            maximum = 150,
            onText = { text ->
                parseInteger(text, 0, 150)?.let { value ->
                    onSettings(OrcaSliceSettings.Keys.INITIAL_LAYER_HOT_PLATE_TEMPERATURE) { it.copy(initialLayerHotPlateTemperatureC = value) }
                }
            },
        )
        IntegerSettingField(
            label = "Maximum fan speed (%)",
            text = settings.fanMaxSpeedPercent.toString(),
            minimum = 0,
            maximum = 100,
            onText = { text ->
                parseInteger(text, 0, 100)?.let { value ->
                    onSettings(OrcaSliceSettings.Keys.FAN_MAX_SPEED) { it.copy(fanMaxSpeedPercent = value) }
                }
            },
        )
        IntegerSettingField(
            label = "Minimum fan speed (%)",
            text = settings.fanMinSpeedPercent.toString(),
            minimum = 0,
            maximum = 100,
            onText = { text ->
                parseInteger(text, 0, 100)?.let { value ->
                    onSettings(OrcaSliceSettings.Keys.FAN_MIN_SPEED) { it.copy(fanMinSpeedPercent = value) }
                }
            },
        )

        Text("Filament and retraction", style = MaterialTheme.typography.titleMedium)
        OptionField(
            label = "Filament type",
            value = settings.filamentType,
            options = ORCA_FILAMENT_TYPES.map { it to it },
            source = source(state, OrcaSliceSettings.Keys.FILAMENT_TYPE),
            onValue = { value ->
                onSettings(OrcaSliceSettings.Keys.FILAMENT_TYPE) { it.copy(filamentType = value) }
            },
        )
        NumberField(
            label = "Flow ratio (%)",
            value = settings.filamentFlowRatioPercent,
            source = source(state, OrcaSliceSettings.Keys.FILAMENT_FLOW_RATIO),
            onValue = { value -> onSettings(OrcaSliceSettings.Keys.FILAMENT_FLOW_RATIO) { it.copy(filamentFlowRatioPercent = value) } },
        )
        NumberField(
            label = "Retraction length (mm)",
            value = settings.retractionLengthMm,
            source = source(state, OrcaSliceSettings.Keys.RETRACTION_LENGTH),
            onValue = { value -> onSettings(OrcaSliceSettings.Keys.RETRACTION_LENGTH) { it.copy(retractionLengthMm = value) } },
        )
        NumberField(
            label = "Retraction speed (mm/s)",
            value = settings.retractionSpeedMmPerSecond,
            source = source(state, OrcaSliceSettings.Keys.RETRACTION_SPEED),
            onValue = { value -> onSettings(OrcaSliceSettings.Keys.RETRACTION_SPEED) { it.copy(retractionSpeedMmPerSecond = value) } },
        )
        NumberField(
            label = "Z hop (mm)",
            value = settings.zHopMm,
            source = source(state, OrcaSliceSettings.Keys.Z_HOP),
            onValue = { value ->
                onSettings(OrcaSliceSettings.Keys.Z_HOP) {
                    it.copy(zHopMm = clampToEngine("z_hop", value, engineRanges))
                }
            },
        )
        SwitchRow(
            label = "Use firmware retraction",
            checked = settings.useFirmwareRetraction,
            source = "Orca: use_firmware_retraction",
        ) { value ->
            onSettings(OrcaSliceSettings.Keys.USE_FIRMWARE_RETRACTION) {
                it.copy(useFirmwareRetraction = value)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pickerOpen) {
        OrcaPresetPicker(
            currentPrinter = settings.printerPreset,
            onDismiss = { pickerOpen = false },
            onPick = { printerPreset, processPreset, filamentPreset ->
                pickerOpen = false
                onSettings(OrcaSliceSettings.Keys.LAYER_HEIGHT) { current ->
                    current.copy(
                        printerPreset = printerPreset,
                        processPreset = processPreset.ifBlank { current.processPreset },
                        filamentPreset = filamentPreset.ifBlank { current.filamentPreset },
                    )
                }
            },
        )
    }
}

/**
 * Filament types the engine's own presets use, most common first. The engine stores the type
 * as a free string and a chosen preset may name one outside this list, which [OptionField]
 * shows unchanged rather than blanking it.
 */
private val ORCA_FILAMENT_TYPES = listOf(
    "PLA", "PETG", "ABS", "ASA", "TPU", "PC", "PA", "PLA-CF", "PETG-CF", "PA-CF", "PVA", "HIPS",
)

/** Clamps [value] to the engine's declared range for [key]; unchanged when it declares none. */
private fun clampToEngine(
    key: String,
    value: Double,
    ranges: Map<String, ClosedFloatingPointRange<Double>>,
): Double = ranges[key]?.let { range -> value.coerceIn(range) } ?: value

/** [settings] with every numeric field the engine declares a range for inside it. */
private fun clampAllToEngine(
    settings: OrcaSliceSettings,
    ranges: Map<String, ClosedFloatingPointRange<Double>>,
): OrcaSliceSettings = settings.copy(
    layerHeightMm = clampToEngine("layer_height", settings.layerHeightMm, ranges),
    firstLayerHeightMm = clampToEngine("initial_layer_print_height", settings.firstLayerHeightMm, ranges),
    sparseInfillDensityPercent = clampToEngine(
        "sparse_infill_density",
        settings.sparseInfillDensityPercent,
        ranges,
    ),
    brimWidthMm = clampToEngine("brim_width", settings.brimWidthMm, ranges),
    innerWallSpeedMmPerSecond = clampToEngine("inner_wall_speed", settings.innerWallSpeedMmPerSecond, ranges),
    outerWallSpeedMmPerSecond = clampToEngine("outer_wall_speed", settings.outerWallSpeedMmPerSecond, ranges),
    sparseInfillSpeedMmPerSecond = clampToEngine(
        "sparse_infill_speed",
        settings.sparseInfillSpeedMmPerSecond,
        ranges,
    ),
    travelSpeedMmPerSecond = clampToEngine("travel_speed", settings.travelSpeedMmPerSecond, ranges),
    zHopMm = clampToEngine("z_hop", settings.zHopMm, ranges),
)

/**
 * Picks a machine from the bundled vendor bundles.
 *
 * The process and filament a machine preselects come from its own profile file, so choosing a
 * printer here is one action rather than three - the same two fields the console falls back to
 * when the app names only a printer.
 */
@Composable
private fun OrcaPresetPicker(
    currentPrinter: String,
    onDismiss: () -> Unit,
    onPick: (printer: String, process: String, filament: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vendors = remember { OrcaPresetCatalog.vendorIds(context.assets) }
    var vendor by remember { mutableStateOf(vendors.firstOrNull { it == "Creality" } ?: vendors.firstOrNull().orEmpty()) }
    var machineMenu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    // A vendor's index is megabytes and every machine in it is checked against its
    // own profile, so it is read off the main thread: composing this dialog used to
    // freeze the sheet for as long as the largest vendor took to parse. Null means
    // still loading, which is what the dialog shows.
    val machines by produceState<List<OrcaPresetCatalog.Machine>?>(initialValue = null, vendor) {
        // produceState remembers its value across a key change while the new producer
        // runs, so it has to be cleared here: without this the previous vendor's
        // machines stayed listed under the new vendor's name, the "reading" state was
        // unreachable after the first load, and a tap in that window picked a machine
        // the new vendor does not have.
        value = null
        value = withContext(Dispatchers.IO) { OrcaPresetCatalog.machines(context.assets, vendor) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Printer preset") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Vendor: " + vendor, style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { machineMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Change vendor")
                }
                DropdownMenu(expanded = machineMenu, onDismissRequest = { machineMenu = false }) {
                    vendors.forEach { id ->
                        DropdownMenuItem(
                            text = { Text(id) },
                            onClick = {
                                machineMenu = false
                                vendor = id
                            },
                        )
                    }
                }
                val loaded = machines
                if (loaded == null) {
                    Text(
                        "Reading " + vendor + " presets…",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    CircularProgressIndicator(modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    Text(
                        "Machines (" + loaded.size + "), current: " + currentPrinter,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    loaded.forEach { machine ->
                        TextButton(
                            // One pick at a time: the profile read behind it is a file.
                            enabled = !picking,
                            onClick = {
                                picking = true
                                scope.launch {
                                    val defaults = withContext(Dispatchers.IO) {
                                        OrcaPresetCatalog.defaultsFor(context.assets, vendor, machine)
                                    }
                                    picking = false
                                    onPick(
                                        machine.name,
                                        defaults?.processPreset.orEmpty(),
                                        defaults?.filamentPreset.orEmpty(),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(machine.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
