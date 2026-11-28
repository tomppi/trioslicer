package com.tomppi.enderslicer.model

import android.content.res.AssetManager
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The three collections a preset can come from, with the vendor-index list and the directory each
 * one lives in. The list key is the index's own spelling, taken from the bundled vendor files.
 */
enum class OrcaPresetKind(val listKey: String, val subDir: String) {
    MACHINE("machine_list", "machine"),
    PROCESS("process_list", "process"),
    FILAMENT("filament_list", "filament"),
}

/** A bundled preset an imported profile names as the base it inherits from. */
data class OrcaBasePreset(
    val name: String,
    val vendorId: String,
    val subPath: String,
    /** The printer presets this base declares itself compatible with; empty when it declares none. */
    val compatiblePrinters: List<String>,
)

/**
 * The OrcaSlicer profile catalogue the app offers, read from the bundled vendor bundles.
 *
 * OrcaSlicer ships one index per vendor (`resources/profiles/Creality.json`) listing that
 * vendor's machines, processes and filaments, with each preset's full definition in a file
 * beside it. The app parses the index lazily - one vendor at a time, when a picker needs it -
 * because the largest vendor index alone is several megabytes.
 *
 * The process and filament a machine preselects are read from its own profile file rather than
 * guessed, which is how the desktop app presents a printer: pick the machine, and its default
 * process and filament come with it. The console does the same when the app names only a
 * printer.
 */
object OrcaPresetCatalog {

    /** One printer preset: the name the engine selects and the file holding its settings. */
    data class Machine(
        val name: String,
        val subPath: String,
    )

    /** What a machine profile preselects, in the engine's own vocabulary. */
    data class MachineDefaults(
        val processPreset: String,
        val filamentPreset: String,
    )

    /** Records a saved preset name that no longer exists in the bundled OrcaSlicer tree. */
    data class PresetFallback(
        val kind: OrcaPresetKind,
        val requested: String,
        val resolved: String,
    )

    /**
     * Preset names verified against the bundled OrcaSlicer profile tree before slicing.
     *
     * When an app update ships a newer OrcaSlicer whose vendor bundles renamed a preset (for
     * example `Creality Generic PLA` -> `CR-PLA @Ender-3 V2-all` in 2.5.2), [fallbacks] records
     * every substitution so the UI can tell the user explicitly rather than silently slicing with
     * a different profile.
     */
    data class ResolvedPresets(
        val printerPreset: String,
        val processPreset: String,
        val filamentPreset: String,
        val fallbacks: List<PresetFallback> = emptyList(),
    ) {
        fun notice(): String? {
            if (fallbacks.isEmpty()) return null
            return fallbacks.joinToString("; ") { fb ->
                val label = when (fb.kind) {
                    OrcaPresetKind.MACHINE -> "printer"
                    OrcaPresetKind.PROCESS -> "process"
                    OrcaPresetKind.FILAMENT -> "filament"
                }
                "Orca $label preset \"${fb.requested}\" is not in 2.5.2 — used \"${fb.resolved}\" instead"
            }
        }
    }

    /**
     * Machine names in a vendor index, in file order; empty when the index cannot be read.
     *
     * [isInstantiable] receives each candidate and decides whether the engine can slice it on
     * its own; see [machines].
     */
    fun machinesFromVendorIndex(
        indexJson: String,
        isInstantiable: (Machine) -> Boolean = { true },
    ): List<Machine> = runCatching {
        parseMachines(indexJson, isInstantiable)
    }.getOrDefault(emptyList())

    private fun parseMachines(indexJson: String, isInstantiable: (Machine) -> Boolean): List<Machine> {
        val root = JSONObject(indexJson)
        val list = root.optJSONArray("machine_list") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val name = item.optString("name")
                if (name.isBlank()) continue
                val machine = Machine(name = name, subPath = item.optString("sub_path"))
                if (!isInstantiable(machine)) continue
                add(machine)
            }
        }
    }

    /** A machine profile's own default process and filament; empty names when it declares none. */
    fun defaultsFromMachineProfile(profileJson: String): MachineDefaults = runCatching {
        val root = JSONObject(profileJson)
        MachineDefaults(
            processPreset = firstValue(root.opt("default_print_profile")),
            filamentPreset = firstValue(root.opt("default_filament_profile")),
        )
    }.getOrDefault(MachineDefaults("", ""))

    /**
     * The bundled profiles spell these two fields inconsistently: the process is a plain string
     * and the filament is a one-element array in the same file, so both forms are accepted.
     */
    private fun firstValue(value: Any?): String = when (value) {
        is String -> value.trim()
        is org.json.JSONArray -> if (value.length() == 0) "" else value.optString(0).trim()
        else -> ""
    }

    /** Vendor ids available in the bundled tree, without their (large) index files. */
    fun vendorIds(assets: AssetManager): List<String> = runCatching {
        assets.list(PROFILES_DIR).orEmpty()
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .sorted()
    }.getOrDefault(emptyList())

    /**
     * The machines one vendor ships, without the shared base profiles the index lists for
     * inheritance only. Those set `instantiation: false` in their own profile because they
     * exist to be inherited: they have no printable area, and the default process they name
     * is not in the bundle, so choosing one made every later Orca slice fail.
     */
    fun machines(assets: AssetManager, vendorId: String): List<Machine> = runCatching {
        machinesFromVendorIndex(readAsset(assets, PROFILES_DIR + "/" + vendorId + ".json")) { machine ->
            isInstantiableProfile(assets, vendorId, machine)
        }
    }.getOrDefault(emptyList())

    /**
     * Whether the engine can instantiate [machine] on its own.
     *
     * A profile that cannot be read keeps the entry: the index is the only file the picker
     * needs, and a machine wrongly offered is recoverable where one wrongly hidden is not.
     */
    private fun isInstantiableProfile(assets: AssetManager, vendorId: String, machine: Machine): Boolean =
        runCatching {
            val root = JSONObject(
                readAsset(assets, PROFILES_DIR + "/" + vendorId + "/" + machine.subPath),
            )
            !root.optString("instantiation").equals("false", ignoreCase = true)
        }.getOrDefault(true)

    /** The process and filament that machine preselects, or null when it names none. */
    fun defaultsFor(assets: AssetManager, vendorId: String, machine: Machine): MachineDefaults? =
        runCatching {
            val json = readAsset(
                assets,
                PROFILES_DIR + "/" + vendorId + "/" + machine.subPath,
            )
            defaultsFromMachineProfile(json).takeIf {
                it.processPreset.isNotEmpty() || it.filamentPreset.isNotEmpty()
            }
        }.getOrNull()

    /**
     * The bundled preset named [name] in [kind], or null when the tree carries none.
     *
     * A profile exported from the desktop app usually inherits from a system preset rather than
     * restating every option. Importing it means selecting that base as well, which is the only way
     * to reach a non-default process here: the sheet picks the process and filament a machine
     * preselects, it does not offer the vendor's whole process list. A profile that declares
     * `instantiation: false` is a template that exists only to be inherited - it has no printable
     * area of its own and the engine cannot slice it - so it is never offered as a base.
     */
    fun findInstantiableBase(assets: AssetManager, kind: OrcaPresetKind, name: String): OrcaBasePreset? =
        findInstantiableBase(
            read = { path -> runCatching { readAsset(assets, path) }.getOrNull() },
            vendorIds = vendorIds(assets),
            kind = kind,
            name = name,
        )

    /** The lookup over a plain reader, so it can be exercised against the real tree in a test. */
    internal fun findInstantiableBase(
        read: (String) -> String?,
        vendorIds: List<String>,
        kind: OrcaPresetKind,
        name: String,
    ): OrcaBasePreset? {
        val target = name.trim()
        if (target.isEmpty()) return null
        for (vendorId in vendorIds) {
            val index = read(PROFILES_DIR + "/" + vendorId + ".json")?.let(::jsonOrNull) ?: continue
            val list = index.optJSONArray(kind.listKey) ?: continue
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                if (item.optString("name").trim() != target) continue
                val subPath = item.optString("sub_path").trim()
                if (subPath.isEmpty()) continue
                val profile = read(PROFILES_DIR + "/" + vendorId + "/" + subPath)?.let(::jsonOrNull) ?: continue
                if (profile.optString("instantiation").equals("false", ignoreCase = true)) continue
                return OrcaBasePreset(
                    name = target,
                    vendorId = vendorId,
                    subPath = subPath,
                    compatiblePrinters = strings(profile.optJSONArray("compatible_printers")),
                )
            }
        }
        return null
    }

    /**
     * Verifies [printerPreset], [processPreset], and [filamentPreset] against the extracted
     * `<datadir>/profiles` directory on disk. Any preset name that no longer exists in the bundle
     * falls back to the resolved machine's bundled default (or the Creality Ender-3 V2 default if
     * the machine itself is missing), and every substitution is recorded in [ResolvedPresets.fallbacks].
     */
    fun resolveAvailablePresets(
        profilesDir: File,
        printerPreset: String,
        processPreset: String,
        filamentPreset: String,
    ): ResolvedPresets {
        val vendorIds = profilesDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()
        if (vendorIds.isEmpty()) {
            return ResolvedPresets(printerPreset, processPreset, filamentPreset)
        }
        return resolveAvailablePresets(
            read = { rel ->
                val stripped = rel.removePrefix("$PROFILES_DIR/")
                File(profilesDir, stripped).takeIf { it.isFile }?.readText()
            },
            vendorIds = vendorIds,
            printerPreset = printerPreset,
            processPreset = processPreset,
            filamentPreset = filamentPreset,
        )
    }

    internal fun resolveAvailablePresets(
        read: (String) -> String?,
        vendorIds: List<String>,
        printerPreset: String,
        processPreset: String,
        filamentPreset: String,
    ): ResolvedPresets {
        if (vendorIds.isEmpty()) {
            return ResolvedPresets(printerPreset, processPreset, filamentPreset)
        }
        val fallbacks = mutableListOf<PresetFallback>()
        val indexCache = HashMap<String, JSONObject?>()
        fun vendorIndex(vendorId: String): JSONObject? =
            indexCache.getOrPut(vendorId) {
                read("$PROFILES_DIR/$vendorId.json")?.let(::jsonOrNull)
            }

        fun findPreset(
            kind: OrcaPresetKind,
            name: String,
            preferredVendors: List<String> = emptyList(),
        ): Pair<String, JSONObject>? {
            val target = name.trim()
            if (target.isEmpty()) return null
            val ordered = if (preferredVendors.isEmpty()) {
                vendorIds
            } else {
                (preferredVendors.filter { it in vendorIds } + vendorIds).distinct()
            }
            for (vendorId in ordered) {
                val list = vendorIndex(vendorId)?.optJSONArray(kind.listKey) ?: continue
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    if (item.optString("name").trim() != target) continue
                    val subPath = item.optString("sub_path").trim()
                    if (subPath.isEmpty()) continue
                    val profile = read("$PROFILES_DIR/$vendorId/$subPath")?.let(::jsonOrNull) ?: continue
                    if (profile.optString("instantiation").equals("false", ignoreCase = true)) continue
                    return vendorId to profile
                }
            }
            return null
        }

        // 1. Printer preset
        val requestedPrinter = printerPreset.trim()
        val foundPrinter = if (requestedPrinter.isNotEmpty()) {
            findPreset(OrcaPresetKind.MACHINE, requestedPrinter)
        } else {
            null
        }
        val (resolvedPrinter, printerVendor, printerJson) = if (foundPrinter != null || requestedPrinter.isEmpty()) {
            Triple(requestedPrinter, foundPrinter?.first, foundPrinter?.second)
        } else {
            val fallbackName = OrcaSliceSettings.DEFAULT_PRINTER_PRESET
            val fallbackEntry = findPreset(OrcaPresetKind.MACHINE, fallbackName)
            fallbacks += PresetFallback(OrcaPresetKind.MACHINE, requestedPrinter, fallbackName)
            Triple(fallbackName, fallbackEntry?.first, fallbackEntry?.second)
        }

        val machineDefaults = printerJson?.let { defaultsFromMachineProfile(it.toString()) }
        val preferred = listOfNotNull(printerVendor)

        // 2. Process preset
        val requestedProcess = processPreset.trim()
        val resolvedProcess = if (requestedProcess.isEmpty() ||
            findPreset(OrcaPresetKind.PROCESS, requestedProcess, preferred) != null
        ) {
            requestedProcess
        } else {
            val fallbackName = machineDefaults?.processPreset?.takeIf { it.isNotEmpty() }
                ?: OrcaSliceSettings.DEFAULT_PROCESS_PRESET
            fallbacks += PresetFallback(OrcaPresetKind.PROCESS, requestedProcess, fallbackName)
            fallbackName
        }

        // 3. Filament preset
        val requestedFilament = filamentPreset.trim()
        val resolvedFilament = if (requestedFilament.isEmpty() ||
            findPreset(OrcaPresetKind.FILAMENT, requestedFilament, preferred) != null
        ) {
            requestedFilament
        } else {
            val fallbackName = machineDefaults?.filamentPreset?.takeIf { it.isNotEmpty() }
                ?: OrcaSliceSettings.DEFAULT_FILAMENT_PRESET
            fallbacks += PresetFallback(OrcaPresetKind.FILAMENT, requestedFilament, fallbackName)
            fallbackName
        }

        return ResolvedPresets(
            printerPreset = resolvedPrinter,
            processPreset = resolvedProcess,
            filamentPreset = resolvedFilament,
            fallbacks = fallbacks,
        )
    }

    private fun jsonOrNull(text: String): JSONObject? =
        runCatching { JSONObject(text) }.getOrNull()

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }

    private fun readAsset(assets: AssetManager, path: String): String =
        assets.open(path).bufferedReader().use { it.readText() }

    /** Where the staged profile tree lives inside the APK's assets. */
    const val PROFILES_DIR = "orca/resources/profiles"
}
