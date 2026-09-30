package com.tomppi.enderslicer.printer

import org.json.JSONArray
import com.tomppi.enderslicer.data.KlipperMacroLibrary
import org.json.JSONObject

/**
 * The merged status, read the way the tab screens need it.
 *
 * Every value here comes from an object klippy publishes, read by the name Klipper's
 * own Status_Reference documents. Nothing is inferred and nothing is defaulted into
 * existence: a tab that shows a fan at 0% because the fan object has not arrived yet
 * would be describing a machine that is not there.
 *
 * A [JSONObject] per object rather than a field per value, because what exists is the
 * printer's configuration: extra sensors, a second micro-controller and every saved
 * mesh profile are sections somebody put in a config file. A screen that can only show
 * the objects the app was compiled against can never show those.
 */

/** klippy itself: what it is, where it runs, and which files it is using. */
data class KlipperHost(
    val softwareVersion: String = "",
    val hostname: String = "",
    val cpuInfo: String = "",
    val pythonPath: String = "",
    val configFile: String = "",
    val logFile: String = "",
    val processId: Int = 0,
) {
    companion object {
        /** From klippy's own info reply, whose keys are all strings. */
        fun from(info: JSONObject) = KlipperHost(
            softwareVersion = info.optString("software_version"),
            hostname = info.optString("hostname"),
            cpuInfo = info.optString("cpu_info"),
            pythonPath = info.optString("python_path"),
            configFile = info.optString("config_file"),
            logFile = info.optString("log_file"),
            processId = info.optInt("process_id"),
        )
    }
}

/** A macro the printer's configuration defines. */
data class KlipperMacro(
    /** The name as it is typed, without the "gcode_macro " prefix. */
    val name: String,
    val description: String = "",
    val gcode: String = "",
)

/** A heater or a sensor: anything with a temperature. */
data class KlipperHeater(
    /** klippy's object name, which is also how a command addresses it. */
    val name: String,
    /** What it is called on screen: the part after the section type. */
    val label: String,
    val temperature: Double?,
    val target: Double?,
    val power: Double? = null,
) {
    /** True for anything that can be given a target, false for a plain sensor. */
    val isHeater: Boolean get() = name == "extruder" || name == "heater_bed" || target != null
}

/**
 * One micro-controller, with the timing klippy measures against it.
 *
 * The same numbers as the host link card, per board: a printer with a toolhead board
 * and a host micro-controller has a link to each, and one of them being slow is not
 * visible in a figure that has already been averaged with the other.
 */
data class KlipperMcu(
    val name: String,
    val version: String = "",
    val load: Double? = null,
    val awake: Double? = null,
    val frequency: Double? = null,
    val roundTripSeconds: Double? = null,
    val jitterSeconds: Double? = null,
    val timeoutSeconds: Double? = null,
    val retransmits: Int? = null,
    val lastStats: JSONObject? = null,
)

/** The bed mesh as it stands: the grid that was probed and the profiles saved. */
data class KlipperMesh(
    val profileName: String = "",
    val minX: Double? = null,
    val minY: Double? = null,
    val maxX: Double? = null,
    val maxY: Double? = null,
    /** Rows of probed points, as probed rather than as interpolated. */
    val points: List<List<Double>> = emptyList(),
    /** The saved profiles, by the name the printer knows them under. */
    val profiles: List<String> = emptyList(),
) {
    // A row with no points is what klippy reports before anything has been probed
    // (probed_matrix [[]]), so "there are rows" is not the question - "is there a point" is.
    // The old test made an unprobed bed look like a loaded mesh of one row by zero points,
    // and enabled a profile save that klippy then refused.
    val isLoaded: Boolean get() = points.any { it.isNotEmpty() }
    val minimum: Double? get() = points.flatten().minOrNull()
    val maximum: Double? get() = points.flatten().maxOrNull()
    /** Row count by column count, for a caption that says what was probed. */
    val shape: Pair<Int, Int> get() = points.size to (points.firstOrNull()?.size ?: 0)

    companion object {
        fun from(mesh: JSONObject?): KlipperMesh? {
            if (mesh == null) return null
            val min = mesh.optJSONArray("mesh_min")
            val max = mesh.optJSONArray("mesh_max")
            val probed = matrix(mesh.optJSONArray("probed_matrix"))
            // klippy sends a dict keyed by profile name (bed_mesh.py's get_profiles), and an
            // array is what the older fixture said - so both are read, the dict first. With
            // only the array test, the keys answered null and the Mesh screen said "None saved
            // yet" for profiles that were sitting on the printer.
            val profiles = mesh.optJSONObject("profiles")?.let { dictionary ->
                dictionary.keys().asSequence().toList()
            } ?: mesh.optJSONArray("profiles")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    when (val entry = array.opt(index)) {
                        is String -> entry
                        is JSONObject -> entry.optString("name").takeIf(String::isNotBlank)
                        else -> null
                    }
                }
            }.orEmpty()
            return KlipperMesh(
                profileName = mesh.optString("profile_name"),
                minX = min?.getOrNull(0),
                minY = min?.getOrNull(1),
                maxX = max?.getOrNull(0),
                maxY = max?.getOrNull(1),
                points = probed,
                profiles = profiles,
            )
        }

        private fun matrix(array: JSONArray?): List<List<Double>> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { row ->
                val values = array.optJSONArray(row) ?: return@mapNotNull null
                (0 until values.length()).map { values.optDouble(it) }
            }
        }
    }
}

/** The host machine's own load, from Klipper's statistics module. */
data class KlipperSystemStats(
    /** Load average, the same figure the host's own tools report. */
    val load: Double? = null,
    /** Seconds of CPU time this klippy has used. */
    val cpuTime: Double? = null,
    /** Bytes of memory available to it. */
    val memoryAvailable: Double? = null,
)

/** How far through the file the print is, in layers, when the slicer said so. */
data class KlipperLayers(val current: Int, val total: Int)

/** The object status klippy last reported, or null if it has never mentioned it. */
internal fun KlipperPrinterState.obj(name: String): JSONObject? = objects[name]

/**
 * The section that configures the Z probe, if the printer has one.
 *
 * [bltouch] covers the BLTouch and its clones - a CR-Touch is one - so this is the
 * section a probe calibration reads and the one SAVE_CONFIG writes to.
 */
internal val KlipperPrinterState.probeSection: String?
    get() = listOf("bltouch", "probe").firstOrNull { configSections?.has(it) == true }

/** The probe's own offset from the printer's configuration, in millimetres. */
internal val KlipperPrinterState.configuredProbeOffset: Double?
    get() {
        val section = probeSection ?: return null
        val raw = configSections?.optJSONObject(section)?.opt("z_offset")?.toString() ?: return null
        return raw.toDoubleOrNull()
    }

/** What the probe calls itself: "bltouch", "probe", or nothing if there is none. */
internal val KlipperPrinterState.probeName: String?
    get() = obj("probe")?.optString("name")?.takeIf { it.isNotBlank() }

/** Where the probe last stopped, in millimetres of Z. */
internal val KlipperPrinterState.lastProbeResult: Double?
    get() = obj("probe")?.number("last_z_result")

/** What the probe reported the last time it was queried, if it can be. */
internal val KlipperPrinterState.lastProbeQuery: String?
    get() = obj("probe")?.let { probe ->
        if (!probe.has("last_query")) return@let null
        // A JSON boolean, and Klipper's own words for it are these two: "true" on a screen
        // next to the console's TRIGGERED reads like a bug, because it is one.
        triggerWord(probe.opt("last_query"))
    }

/**
 * True while a calibration is waiting for the paper.
 *
 * klippy publishes this itself - manual_probe.is_active - which is what lets a screen
 * offer the nudges that only make sense in that state, rather than guessing at it from
 * the last command it sent.
 */
internal val KlipperPrinterState.manualProbeActive: Boolean
    get() = obj("manual_probe")?.optBoolean("is_active") ?: false

/** Where the nozzle is during that calibration, in millimetres. */
internal val KlipperPrinterState.manualProbeZ: Double?
    get() = obj("manual_probe")?.number("z_position")

/**
 * One axis of input shaping, as the printer's configuration has it.
 *
 * klippy publishes no status for the input_shaper object at all - queried on a running
 * printer, its status is an empty object - so the configured values are read from
 * configfile, which is also the thing that decides them at the next start. What is
 * *in use* right now is whatever SET_INPUT_SHAPER last set, and the only way to see it
 * is to ask the printer, which answers in the console.
 */
data class KlipperShaper(
    /** x or y. */
    val axis: String,
    val type: String,
    val frequency: Double?,
    val dampingRatio: Double?,
)

/** The shaping this printer is configured with, per axis, if it has any. */
internal val KlipperPrinterState.shapers: List<KlipperShaper>
    get() {
        val section = configSections?.optJSONObject("input_shaper") ?: return emptyList()
        return listOf("x", "y").map { axis ->
            KlipperShaper(
                axis = axis,
                type = section.optString("shaper_type_$axis").takeIf { it.isNotBlank() }
                    ?: section.optString("shaper_type").takeIf { it.isNotBlank() }.orEmpty(),
                frequency = section.opt("shaper_freq_$axis")?.toString()?.toDoubleOrNull(),
                dampingRatio = section.opt("damping_ratio_$axis")?.toString()?.toDoubleOrNull(),
            )
        }
    }

/**
 * True when this printer can measure its own resonances.
 *
 * SHAPER_CALIBRATE is registered by [resonance_tester], and it needs an accelerometer
 * to read the vibrations it excites. Without both, the command does not exist at all -
 * a macro that calls it fails with "Unknown command", which is worth saying rather than
 * offering a button that cannot work.
 */
internal val KlipperPrinterState.canMeasureResonances: Boolean
    get() = configSections?.has("resonance_tester") == true

/**
 * The extruder's rotation distance, as the configuration has it.
 *
 * Read from the configuration rather than from klippy because klippy does not publish it:
 * the extruder's status carries pressure advance and the motion queue, and nothing about how
 * far one turn of the motor moves the filament. This is the value the calibration replaces,
 * and the value the app needs in order to compute the new one.
 */
internal val KlipperPrinterState.rotationDistance: Double?
    get() = configSections?.optJSONObject("extruder")?.opt("rotation_distance")
        ?.toString()
        ?.toDoubleOrNull()

/** The objects klippy publishes, by name - what a screen may ask for. */
internal val KlipperPrinterState.objectNames: Set<String> get() = objects.keys

/** The part cooling fan, 0..1. */
internal val KlipperPrinterState.fanSpeed: Double? get() = obj("fan")?.number("speed")

/** M220's factor, where 1.0 is the speed the file asked for. */
internal val KlipperPrinterState.speedFactor: Double? get() = obj("gcode_move")?.number("speed_factor")

/** M221's factor, where 1.0 is the flow the file asked for. */
internal val KlipperPrinterState.extrudeFactor: Double? get() = obj("gcode_move")?.number("extrude_factor")

/** The Z offset applied by SET_GCODE_OFFSET, in millimetres. */
internal val KlipperPrinterState.zOffset: Double?
    get() = obj("gcode_move")?.optJSONArray("homing_origin")?.getOrNull(2)

/** Filament used by this print, in millimetres of filament. */
internal val KlipperPrinterState.printFilamentUsed: Double?
    get() = obj("print_stats")?.number("filament_used")

/** Layers done and layers in the file, when the slicer wrote SET_PRINT_STATS_INFO. */
internal val KlipperPrinterState.printLayers: KlipperLayers?
    get() {
        val info = obj("print_stats")?.optJSONObject("info") ?: return null
        val current = info.int("current_layer") ?: return null
        val total = info.int("total_layer") ?: return null
        return KlipperLayers(current, total)
    }

/** The message a macro last set with M117, if any. */
internal val KlipperPrinterState.displayMessage: String?
    get() = obj("display_status")?.optString("message")?.takeIf { it.isNotBlank() && it != "null" }

/**
 * The fans the configuration named itself, and their speeds.
 *
 * [fan_generic] is the class that takes SET_FAN_SPEED; a heater_fan or controller_fan is
 * driven by its own conditions and has no such command, so those are deliberately left out.
 * The prefix is how klippy names these objects - "fan_generic extruder_partfan" - and the
 * name after it is what the command takes.
 */
internal val KlipperPrinterState.genericFans: List<Pair<String, Double?>>
    get() = objects.keys
        .filter { it.startsWith("fan_generic ") }
        .map { it.removePrefix("fan_generic ") to obj(it)?.number("speed") }
        .sortedBy { it.first }

/** Klipper's own state for the machine: Idle, Printing, Ready. */
internal val KlipperPrinterState.idleState: String? get() = obj("idle_timeout")?.optString("state")

internal val KlipperPrinterState.isPausedByKlipper: Boolean
    get() = obj("pause_resume")?.optBoolean("is_paused") ?: false

internal val KlipperPrinterState.systemStats: KlipperSystemStats?
    get() = obj("system_stats")?.let {
        KlipperSystemStats(
            load = it.number("sysload"),
            cpuTime = it.number("cputime"),
            memoryAvailable = it.number("memavail"),
        )
    }

internal val KlipperPrinterState.mesh: KlipperMesh? get() = KlipperMesh.from(obj("bed_mesh"))

/** Every micro-controller, in the order klippy lists them. */
internal val KlipperPrinterState.mcus: List<KlipperMcu>
    get() = objects.filterKeys { it == "mcu" || it.startsWith("mcu ") }
        .map { (name, status) ->
            val constants = status.optJSONObject("mcu_constants")
            val stats = status.optJSONObject("last_stats")
            KlipperMcu(
                name = name.removePrefix("mcu ").ifBlank { "mcu" },
                version = status.optString("mcu_version"),
                load = stats?.number("mcu_task_avg"),
                awake = stats?.number("mcu_awake"),
                frequency = constants?.number("CLOCK_FREQ"),
                roundTripSeconds = stats?.number("srtt"),
                jitterSeconds = stats?.number("rttvar"),
                timeoutSeconds = stats?.number("rto"),
                retransmits = stats?.int("bytes_retransmit"),
                lastStats = stats,
            )
        }

/** The endstops as last queried, by name, with their own words for their state. */
internal val KlipperPrinterState.endstops: Map<String, String>
    get() {
        val last = obj("query_endstops")?.optJSONObject("last_query") ?: return emptyMap()
        // klippy publishes these as booleans; its own words for them are "open" and
        // "TRIGGERED" (query_endstops.py's web request and console message both use them), so
        // the screen says what the console says rather than "true". A string is accepted too,
        // because that is what an older reading of this field looked like.
        return last.keys().asSequence().associateWith { name ->
            triggerWord(last.opt(name))
        }
    }

/**
 * What a trigger reads as: Klipper's own two words for it.
 *
 * query_endstops and probe publish a boolean, and say "open" or "TRIGGERED" themselves when
 * they report to the console or over a web request. A string that is not one of the two
 * truthy spellings is passed through, so nothing is invented.
 */
private fun triggerWord(value: Any?): String = when (value) {
    is Boolean -> if (value) "TRIGGERED" else "open"
    is String -> when (value.lowercase()) {
        "true" -> "TRIGGERED"
        "false" -> "open"
        else -> value
    }
    else -> "-"
}

/**
 * Klipper's starter macros this configuration does not define.
 *
 * Read from the section list rather than from [KlipperPrinterState.macros], which deliberately
 * hides the macros a screen should not offer to press - a macro that is present but hidden must
 * not be reported as missing, or the app would offer to add it twice.
 */
internal val KlipperPrinterState.missingStarterMacros: List<KlipperMacroLibrary.StarterMacro>
    get() {
        val sections = configSections ?: return emptyList()
        val defined = sections.keys().asSequence()
            .map { it.trim().uppercase() }
            .toSet()
        return KlipperMacroLibrary.all.filterNot {
            ("GCODE_MACRO " + it.name.uppercase()) in defined
        }
    }

/** True when klippy has changes waiting for a restart before they take effect. */
internal val KlipperPrinterState.saveConfigPending: Boolean
    get() = obj("configfile")?.optBoolean("save_config_pending") ?: false

/** Every section the printer's configuration defines, with its options. */
internal val KlipperPrinterState.configSections: JSONObject?
    get() = obj("configfile")?.optJSONObject("settings")

/** The macros this printer defines, in the order they are run: by name. */
internal val KlipperPrinterState.macros: List<KlipperMacro>
    get() {
        val sections = configSections ?: return emptyList()
        return sections.keys().asSequence()
            .filter { it.startsWith("gcode_macro ") }
            .mapNotNull { key ->
                val settings = sections.optJSONObject(key)
                val name = key.removePrefix("gcode_macro ")
                // A leading underscore marks a helper that other macros call, and
                // rename_existing marks a replacement for a command Klipper already
                // has: neither is something to press in a list of things to run.
                if (name.startsWith("_") || settings?.has("rename_existing") == true) {
                    return@mapNotNull null
                }
                KlipperMacro(
                    name = name,
                    description = settings?.optString("description").orEmpty(),
                    gcode = settings?.optString("gcode").orEmpty(),
                )
            }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

/**
 * Everything that reports a temperature, in the order a printer screen should show it.
 *
 * The hotend and the bed first because they are the two a user sets, then whatever else
 * the configuration defines - a chamber sensor, a second extruder, a host or board
 * sensor - in the order klippy lists them.
 */
internal val KlipperPrinterState.heaters: List<KlipperHeater>
    get() = objects.entries
        .filter { it.value.has("temperature") }
        .map { (name, status) ->
            KlipperHeater(
                name = name,
                label = label(name),
                temperature = status.number("temperature"),
                target = status.number("target"),
                power = status.number("power"),
            )
        }
        .sortedWith(compareBy({ order(it.name) }, { it.label.lowercase() }))

/** The objects a print says it contains, for excluding one of them. */
internal val KlipperPrinterState.plateObjects: List<String>
    get() {
        val array = obj("exclude_object")?.optJSONArray("objects") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            when (val entry = array.opt(index)) {
                is String -> entry
                is JSONObject -> entry.optString("name").takeIf(String::isNotBlank)
                else -> null
            }
        }
    }

internal val KlipperPrinterState.excludedObjects: List<String>
    get() {
        val array = obj("exclude_object")?.optJSONArray("excluded_objects") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }

internal val KlipperPrinterState.currentObject: String?
    get() = obj("exclude_object")?.optString("current_object")?.takeIf { it.isNotBlank() && it != "null" }

/** What a heater or a sensor is called on screen. */
private fun label(name: String): String = when (name) {
    "extruder" -> "Hotend"
    "heater_bed" -> "Bed"
    else -> name.substringAfter(' ', name).replace('_', ' ')
}

/** Hotend, then bed, then everything else. */
private fun order(name: String): Int = when (name) {
    "extruder" -> 0
    "heater_bed" -> 1
    else -> 2
}

/**
 * klippy's partial status merged into what is already held.
 *
 * klippy sends only what changed, per object and per field: a fan notification carries
 * nothing but the fan's speed, and a print_stats one nothing but the duration. An
 * object that is replaced outright loses the fields the notification did not mention -
 * which is how a temperature reading disappears from a screen while the heater is still
 * on - so each one is merged into what is already known.
 *
 * A map that would come out identical is returned as it was, so that the state can
 * still be compared with itself: every notification producing a new map would mean a
 * screen that redraws ten times a second for nothing.
 */
internal fun Map<String, JSONObject>.mergedWith(status: JSONObject): Map<String, JSONObject> {
    var merged: MutableMap<String, JSONObject>? = null
    for (name in status.keys()) {
        val partial = status.optJSONObject(name) ?: continue
        val existing = (merged ?: this)[name]
        val value = when {
            existing == null -> partial
            existing.toString() == partial.toString() -> existing
            else -> existing.mergedWith(partial)
        }
        if (value !== existing) {
            if (merged == null) merged = HashMap(this)
            merged[name] = value
        }
    }
    return merged ?: this
}

/** One object's fields, with the ones this update mentions replaced. */
private fun JSONObject.mergedWith(partial: JSONObject): JSONObject {
    val merged = JSONObject()
    for (key in keys()) merged.put(key, get(key))
    for (key in partial.keys()) merged.put(key, partial.get(key))
    return merged
}

/** A number klippy mentioned this time, or null when it did not mention it at all. */
internal fun JSONObject?.number(name: String): Double? =
    if (this != null && has(name) && !isNull(name)) optDouble(name) else null

/** An integer klippy mentioned this time, or null - "no layers yet" is not zero. */
internal fun JSONObject?.int(name: String): Int? =
    if (this != null && has(name) && !isNull(name)) optInt(name) else null

/** The element at [index], or null when the array is shorter than that. */
internal fun JSONArray.getOrNull(index: Int): Double? =
    if (index < length()) optDouble(index) else null

/**
 * True for the heaters a material preset means something for.
 *
 * A printer can publish any number of things with a temperature: a chamber heater, a
 * temperature fan, a second extruder. The presets carry hotend figures, and offering them
 * for everything with a target once meant commanding a chamber to 250 C.
 */
internal fun isMaterialHeater(name: String): Boolean =
    name == "heater_bed" || name == "extruder" ||
        (name.startsWith("extruder") && name.removePrefix("extruder").toIntOrNull() != null)
