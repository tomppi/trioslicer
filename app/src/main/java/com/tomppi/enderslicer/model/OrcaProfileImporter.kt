package com.tomppi.enderslicer.model

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses an OrcaSlicer profile into the app's Orca settings and machine envelope.
 *
 * Two things can be imported, and they are what the desktop app itself accepts (its import dialog
 * filters on *.json, *.zip, *.orca_printer, *.orca_bundle and *.orca_filament):
 *
 *  - one preset exported by *Export Config*, which writes a single .json holding the preset's own
 *    keys plus the identifier Orca classifies it by - printer_settings_id, print_settings_id or
 *    filament_settings_id (PresetBundle.import_json_presets dispatches on exactly those three);
 *  - a preset bundle, a ZIP holding the same documents, sometimes a vendor's whole catalogue.
 *
 * The values are the engine's own vocabulary - the same keys [OrcaConfigWriter] writes - so a key
 * the app has an editor for lands on that field, and every other key the engine declares is
 * carried through as an override (the path the app's extraOrcaSettings already travels). Keys that
 * are metadata, that this engine does not declare, or that the app manages itself are dropped and
 * counted rather than guessed at: an option the engine does not know is not a smaller truth.
 *
 * Every G-code template is refused whatever the app does or does not write, exactly as the Prusa
 * preset importer refuses them - another machine's start G-code must not be spliced into this
 * printer's output. The one that matters here (machine_start_gcode/machine_end_gcode) is reported
 * in [Result.notes] rather than dropped in silence.
 */
object OrcaProfileImporter {

    /** Longest profile read; a vendor bundle is a few hundred kilobytes at most. */
    const val MAX_INPUT_BYTES = 8 * 1024 * 1024

    /** Caps on a bundle, so a hostile or corrupt ZIP cannot allocate unbounded memory. */
    private const val MAX_BUNDLE_ENTRIES = 64
    private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 16 * 1024 * 1024
    private const val MAX_PRESETS = 16

    /** Which of the three preset collections a document belongs to. */
    enum class Kind(val label: String, val catalogKind: OrcaPresetKind) {
        PRINTER("printer", OrcaPresetKind.MACHINE),
        PROCESS("process", OrcaPresetKind.PROCESS),
        FILAMENT("filament", OrcaPresetKind.FILAMENT),
    }

    /** One preset found in the file, its own values already reduced to engine key -> text. */
    data class Preset(
        val kind: Kind,
        val name: String?,
        val inherits: String?,
        val values: Map<String, String>,
    )

    /** The machine envelope a printer preset states; a null field is one it does not mention. */
    data class MachineValues(
        val widthMm: Double? = null,
        val depthMm: Double? = null,
        val heightMm: Double? = null,
        val originAtCenter: Boolean? = null,
        val nozzleSizeMm: Double? = null,
        val filamentDiameterMm: Double? = null,
        val gcodeFlavor: String? = null,
    ) {
        val isEmpty: Boolean
            get() = widthMm == null && depthMm == null && heightMm == null && originAtCenter == null &&
                nozzleSizeMm == null && filamentDiameterMm == null && gcodeFlavor == null
    }

    /**
     * What the file held, already filtered: [values] are the engine keys the app has fields for,
     * [extraKeys] the ones it carries as overrides, [machine] the envelope a printer preset states.
     */
    data class Result(
        val presets: List<Preset>,
        val skippedPresetCount: Int,
        val values: Map<String, String>,
        val machine: MachineValues,
        val extraKeys: Map<String, String>,
        val ignoredKeyCount: Int,
        val refusedKeys: List<String>,
        val notes: List<String>,
    ) {

        /** The imported values laid over [current]; a field the profile does not state is kept. */
        fun applyTo(current: OrcaSliceSettings): OrcaSliceSettings = current.copy(
            layerHeightMm = number("layer_height") ?: current.layerHeightMm,
            firstLayerHeightMm = number("initial_layer_print_height") ?: current.firstLayerHeightMm,
            wallLoops = integer("wall_loops") ?: current.wallLoops,
            topShellLayers = integer("top_shell_layers") ?: current.topShellLayers,
            bottomShellLayers = integer("bottom_shell_layers") ?: current.bottomShellLayers,
            sparseInfillDensityPercent = number("sparse_infill_density") ?: current.sparseInfillDensityPercent,
            sparseInfillPattern = text("sparse_infill_pattern") ?: current.sparseInfillPattern,
            skirtLoops = integer("skirt_loops") ?: current.skirtLoops,
            brimWidthMm = number("brim_width") ?: current.brimWidthMm,
            supportEnabled = flag("enable_support") ?: current.supportEnabled,
            supportThresholdAngleDegrees = integer("support_threshold_angle") ?: current.supportThresholdAngleDegrees,
            supportBasePattern = text("support_base_pattern") ?: current.supportBasePattern,
            supportInterfaceTopLayers = integer("support_interface_top_layers") ?: current.supportInterfaceTopLayers,
            innerWallSpeedMmPerSecond = number("inner_wall_speed") ?: current.innerWallSpeedMmPerSecond,
            outerWallSpeedMmPerSecond = number("outer_wall_speed") ?: current.outerWallSpeedMmPerSecond,
            initialLayerSpeedMmPerSecond = number("initial_layer_speed") ?: current.initialLayerSpeedMmPerSecond,
            sparseInfillSpeedMmPerSecond = number("sparse_infill_speed") ?: current.sparseInfillSpeedMmPerSecond,
            internalSolidInfillSpeedMmPerSecond = number("internal_solid_infill_speed")
                ?: current.internalSolidInfillSpeedMmPerSecond,
            travelSpeedMmPerSecond = number("travel_speed") ?: current.travelSpeedMmPerSecond,
            nozzleTemperatureC = integer("nozzle_temperature") ?: current.nozzleTemperatureC,
            initialLayerNozzleTemperatureC = integer("nozzle_temperature_initial_layer")
                ?: current.initialLayerNozzleTemperatureC,
            hotPlateTemperatureC = integer("hot_plate_temp") ?: current.hotPlateTemperatureC,
            initialLayerHotPlateTemperatureC = integer("hot_plate_temp_initial_layer")
                ?: current.initialLayerHotPlateTemperatureC,
            fanMaxSpeedPercent = integer("fan_max_speed") ?: current.fanMaxSpeedPercent,
            fanMinSpeedPercent = integer("fan_min_speed") ?: current.fanMinSpeedPercent,
            filamentType = text("filament_type") ?: current.filamentType,
            filamentFlowRatioPercent = flowRatioPercent() ?: current.filamentFlowRatioPercent,
            retractionLengthMm = number("retraction_length") ?: current.retractionLengthMm,
            retractionSpeedMmPerSecond = number("retraction_speed") ?: current.retractionSpeedMmPerSecond,
            zHopMm = number("z_hop") ?: current.zHopMm,
            useFirmwareRetraction = flag("use_firmware_retraction") ?: current.useFirmwareRetraction,
        )

        /**
         * The first slot of the key's value: a preset writes a per-extruder option as a list, and
         * this app prints with one extruder, so a second slot has nowhere to go.
         */
        private fun text(key: String): String? =
            values[key]?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }

        private fun number(key: String): Double? =
            text(key)?.removeSuffix("%")?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

        private fun integer(key: String): Int? = text(key)?.let { raw ->
            raw.toIntOrNull() ?: raw.toDoubleOrNull()
                ?.takeIf { it.isFinite() }
                ?.let { Math.round(it).toInt() }
        }

        private fun flag(key: String): Boolean? = text(key)?.lowercase()?.let { value ->
            when (value) {
                "1", "true", "yes" -> true
                "0", "false", "no" -> false
                else -> null
            }
        }

        /** filament_flow_ratio is a ratio; the app's field is a percentage. */
        private fun flowRatioPercent(): Double? {
            val raw = text("filament_flow_ratio") ?: return null
            val parsed = raw.removeSuffix("%").trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            return if (raw.endsWith("%")) parsed else parsed * 100.0
        }
    }

    fun parse(
        sourceName: String,
        bytes: ByteArray,
        catalog: List<ExtraSettingSpec> = emptyList(),
    ): Result {
        require(bytes.isNotEmpty()) { "$sourceName is empty" }
        require(bytes.size <= MAX_INPUT_BYTES) {
            "$sourceName is larger than the " + (MAX_INPUT_BYTES / (1024 * 1024)) + " MiB profile limit"
        }

        val documents = if (isZip(bytes)) readBundle(sourceName, bytes) else listOf(jsonDocument(sourceName, bytes))
        val presets = mutableListOf<Preset>()
        var ignored = 0

        for ((documentName, json) in documents) {
            val kind = presetKind(json) ?: throw IllegalArgumentException(
                "$documentName does not look like an OrcaSlicer preset: it names neither a print, " +
                    "filament nor printer settings id, and its keys do not identify one kind of preset",
            )
            val document = engineValues(json)
            ignored += document.metadataKeys
            presets += Preset(
                kind = kind,
                name = json.optString("name").trim().takeIf { it.isNotEmpty() },
                inherits = json.optString("inherits").trim().takeIf { it.isNotEmpty() },
                values = document.values,
            )
        }
        require(presets.isNotEmpty()) { "$sourceName holds no OrcaSlicer preset" }
        require(presets.size <= MAX_PRESETS) {
            "$sourceName holds " + presets.size + " presets; the limit is $MAX_PRESETS"
        }

        // Orca applies printer, then print, then filament, so a key stated twice takes the later
        // value. Within one kind only the first preset by name is applied: a vendor bundle can hold
        // hundreds, and applying every one of them in turn would leave whichever happened to be last.
        val applied = mutableListOf<Preset>()
        val values = linkedMapOf<String, String>()
        val extras = linkedMapOf<String, String>()
        var machine = MachineValues()
        val refused = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val specs = catalog.associateBy(ExtraSettingSpec::key)

        for (kind in Kind.entries) {
            val ofKind = presets.filter { it.kind == kind }.sortedBy { it.name.orEmpty() }
            if (ofKind.isEmpty()) continue
            applied += ofKind.first()
            skipped += ofKind.drop(1).mapNotNull { it.name }
            for ((key, value) in ofKind.first().values) {
                when {
                    key == "enable_arc_fitting" -> if (flagValue(value) == true) {
                        notes += "arc fitting stays off: the app's G-code pipeline models linear moves only"
                    }
                    key == "machine_start_gcode" || key == "machine_end_gcode" ->
                        notes += "the preset's machine G-code was not applied; the app writes its own"
                    key in ENVELOPE_KEYS -> machine = machineValues(key, value, machine)
                    key in FIELD_KEYS -> values[key] = value
                    key.contains("gcode") -> ignored++
                    // A preset that states nothing for a key has not asked for it to change.
                    value.isBlank() -> ignored++
                    key in AllSettingsCatalogs.ORCA_BLOCKED_KEYS -> ignored++
                    !specs.containsKey(key) -> ignored++
                    rejectReason(key, value, specs[key]) != null -> refused += key
                    else -> extras[key] = value
                }
            }
        }

        return Result(
            presets = applied,
            skippedPresetCount = skipped.size,
            values = values,
            machine = machine,
            extraKeys = extras,
            ignoredKeyCount = ignored,
            refusedKeys = refused.distinct().sorted(),
            notes = notes.distinct(),
        )
    }

    /**
     * Why [value] cannot be carried as an override for [key], or null when it can.
     *
     * The engine's own presets state some plain-float options as a percentage of another one
     * ("50%" for a speed), so a percentage is judged by the structural rules alone; everything else
     * is held to the type the engine's catalogue declares for the key, the same as a typed-in value.
     */
    private fun rejectReason(key: String, value: String, spec: ExtraSettingSpec?): String? =
        if (spec?.numeric == true && value.trim().endsWith("%")) {
            ExtraSettingValidation.rejectReason(key, value)
        } else {
            ExtraSettingValidation.rejectReason(key, value, spec)
        }

    /** The engine keys the app has an editor for; every other declared key becomes an override. */
    private val FIELD_KEYS: Set<String> = setOf(
        "layer_height", "initial_layer_print_height", "wall_loops", "top_shell_layers",
        "bottom_shell_layers", "sparse_infill_density", "sparse_infill_pattern", "skirt_loops",
        "brim_width", "enable_support", "support_threshold_angle", "support_base_pattern",
        "support_interface_top_layers", "inner_wall_speed", "outer_wall_speed",
        "initial_layer_speed", "sparse_infill_speed", "internal_solid_infill_speed", "travel_speed",
        "nozzle_temperature", "nozzle_temperature_initial_layer", "hot_plate_temp",
        "hot_plate_temp_initial_layer", "fan_max_speed", "fan_min_speed", "filament_type",
        "filament_flow_ratio", "retraction_length", "retraction_speed", "z_hop",
        "use_firmware_retraction",
    )

    /** The printer keys that describe the machine rather than a slice. */
    private val ENVELOPE_KEYS: Set<String> = setOf(
        "printable_area", "printable_height", "nozzle_diameter", "filament_diameter", "gcode_flavor",
    )

    private fun machineValues(key: String, value: String, current: MachineValues): MachineValues =
        when (key) {
            "printable_area" -> {
                val corners = points(value)
                if (corners.isEmpty()) {
                    current
                } else {
                    val xs = corners.map { it.first }
                    val ys = corners.map { it.second }
                    current.copy(
                        widthMm = xs.max() - xs.min(),
                        depthMm = ys.max() - ys.min(),
                        originAtCenter = xs.min() < 0.0 || ys.min() < 0.0,
                    )
                }
            }
            "printable_height" -> current.copy(heightMm = slot(value) ?: current.heightMm)
            "nozzle_diameter" -> current.copy(nozzleSizeMm = slot(value) ?: current.nozzleSizeMm)
            "filament_diameter" -> current.copy(filamentDiameterMm = slot(value) ?: current.filamentDiameterMm)
            "gcode_flavor" -> current.copy(gcodeFlavor = appGcodeFlavor(value) ?: current.gcodeFlavor)
            else -> current
        }

    /** The first slot of an option a preset may write per extruder. */
    private fun slot(value: String): Double? = value.substringBefore(',').trim().toDoubleOrNull()

    /** "0x0,220x220" as a corner list; a point that does not parse is dropped. */
    private fun points(value: String): List<Pair<Double, Double>> = value.split(',')
        .mapNotNull { part ->
            val x = part.substringBefore('x').trim().toDoubleOrNull()
            val y = part.substringAfter('x', "").trim().toDoubleOrNull()
            if (x == null || y == null) null else x to y
        }

    /** The engine's gcode_flavor spellings into the app's labels (inverse of OrcaConfigWriter). */
    private fun appGcodeFlavor(flavor: String): String? = when (flavor.trim().lowercase()) {
        "marlin", "marlin2", "ender3" -> "Marlin"
        "klipper" -> "Klipper"
        "reprap", "reprapfirmware" -> "RepRap"
        "repetier" -> "Repetier"
        else -> null
    }

    private fun flagValue(value: String): Boolean? = when (value.trim().lowercase()) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> null
    }

    /** A document's settings, and how many of its keys described the document itself. */
    private data class DocumentValues(val values: Map<String, String>, val metadataKeys: Int)

    /**
     * The preset's own settings, metadata removed. A vector keeps its slots joined: the envelope
     * points need all four, and a per-extruder option is reduced to its first slot later, when the
     * app's single-extruder fields are filled in.
     */
    private fun engineValues(json: JSONObject): DocumentValues {
        val values = linkedMapOf<String, String>()
        var metadata = 0
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in METADATA_KEYS || key.endsWith("_settings_id") || key.startsWith("default_")) {
                metadata++
                continue
            }
            val text = scalar(json.opt(key)) ?: continue
            values[key] = text
        }
        return DocumentValues(values, metadata)
    }

    /** One JSON value as the engine spells it; a vector is joined with commas. */
    private fun scalar(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value
        is Boolean -> if (value) "1" else "0"
        is Number -> text(value)
        is JSONArray -> (0 until value.length()).mapNotNull { scalar(value.opt(it)) }.joinToString(",")
        else -> value.toString()
    }

    /** A number without the trailing ".0" JSONObject would otherwise print for an integer. */
    private fun text(value: Number): String {
        val double = value.toDouble()
        return if (double.isFinite() && double == Math.floor(double) && Math.abs(double) < 1e15) {
            double.toLong().toString()
        } else {
            double.toString()
        }
    }

    /** Which collection the document belongs to, the way Orca's own importer decides it. */
    private fun presetKind(json: JSONObject): Kind? {
        if (json.has("printer_settings_id")) return Kind.PRINTER
        if (json.has("print_settings_id")) return Kind.PROCESS
        if (json.has("filament_settings_id")) return Kind.FILAMENT
        when (json.optString("type").trim().lowercase()) {
            "machine", "printer" -> return Kind.PRINTER
            "process", "print" -> return Kind.PROCESS
            "filament" -> return Kind.FILAMENT
        }
        // An exported preset that carries no identifier: the keys themselves have to name one kind.
        val markers = Kind.entries.filter { kind -> MARKER_KEYS.getValue(kind).any { json.has(it) } }
        return markers.singleOrNull()
    }

    private val MARKER_KEYS: Map<Kind, List<String>> = mapOf(
        Kind.PRINTER to listOf("printable_area", "nozzle_diameter", "printer_model", "machine_start_gcode"),
        Kind.FILAMENT to listOf("filament_type", "filament_flow_ratio", "hot_plate_temp", "filament_diameter"),
        Kind.PROCESS to listOf("layer_height", "wall_loops", "sparse_infill_density", "initial_layer_print_height"),
    )

    private val METADATA_KEYS: Set<String> = setOf(
        "name", "version", "from", "type", "inherits", "setting_id", "filament_id", "base_id",
        "update_time", "description", "instantiation", "is_custom_defined", "renamed_from",
        "compatible_printers", "compatible_prints", "compatible_printers_condition",
        "compatible_prints_condition", "printer_model", "printer_variant", "printer_technology",
        "default_print_profile", "default_filament_profile", "thumbnails", "bed_custom_model",
        "bed_custom_texture", "plugins",
    )

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    /**
     * The JSON documents inside a bundle, in entry order.
     *
     * Entries are read in memory and never written to a path built from their names, and the caps
     * stop a ZIP that claims to expand to gigabytes from being believed.
     */
    private fun readBundle(sourceName: String, bytes: ByteArray): List<Pair<String, JSONObject>> {
        val documents = mutableListOf<Pair<String, JSONObject>>()
        var entries = 0
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                entries++
                require(entries <= MAX_BUNDLE_ENTRIES) {
                    "$sourceName holds more than $MAX_BUNDLE_ENTRIES entries"
                }
                val name = entry.name.substringAfterLast('/')
                if (!entry.name.endsWith(".json", ignoreCase = true) || name in BUNDLE_METADATA_FILES) continue
                val content = readEntry(zip, sourceName, entry.name)
                total += content.size
                require(total <= MAX_TOTAL_BYTES) {
                    "$sourceName expands to more than the " + (MAX_TOTAL_BYTES / (1024 * 1024)) + " MiB limit"
                }
                runCatching { JSONObject(String(content, Charsets.UTF_8)) }
                    .onSuccess { documents += entry.name to it }
            }
        }
        require(documents.isNotEmpty()) { "$sourceName holds no JSON preset" }
        return documents
    }

    private fun readEntry(zip: ZipInputStream, sourceName: String, entryName: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            require(out.size() <= MAX_ENTRY_BYTES) {
                "$sourceName holds an entry larger than the " +
                    (MAX_ENTRY_BYTES / (1024 * 1024)) + " MiB limit: $entryName"
            }
        }
        return out.toByteArray()
    }

    private fun jsonDocument(sourceName: String, bytes: ByteArray): Pair<String, JSONObject> =
        sourceName to runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { error ->
            throw IllegalArgumentException(
                "$sourceName is not an OrcaSlicer preset: " + (error.message ?: "unreadable JSON"),
            )
        }

    /** Bundle bookkeeping Orca writes beside the presets, and macOS archive noise. */
    private val BUNDLE_METADATA_FILES: Set<String> = setOf(
        "bundle_structure.json", "bundle_metadata.json", ".DS_Store",
    )
}
