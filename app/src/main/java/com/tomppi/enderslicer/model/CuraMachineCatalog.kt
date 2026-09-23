package com.tomppi.enderslicer.model

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * Cura's own printer catalogue, read from the bundled definition tree.
 *
 * The app used to resolve one hand-picked chain (fdmprinter -> creality_base -> creality_ender3)
 * and slice with that. The full tree ships instead - 636 machines across Creality, UltiMaker,
 * Anycubic, Sovol, Elegoo and the rest - so this decides which definitions are machines and
 * which files one machine needs.
 *
 * The rules are Cura's own, read off the data rather than guessed:
 *
 *  - a definition is a **machine** when its chain reaches `fdmprinter`, it is not an extruder
 *    train, it is not marked hidden, and its id does not name shared scaffolding
 *    (`*_base`, `*_common`) that other definitions inherit;
 *  - a machine's **closure** is itself plus its parents up to `fdmprinter`, plus the extruder
 *    trains those definitions declare and their parents up to `fdmextruder`; a closure that
 *    cannot be read in full resolves to null rather than to a stack the engine would reject.
 *
 * Visibility is Cura's own metadata, not the shape of the tree: a real printer is inherited by
 * its variants (UltiMaker S5 by the S7/S8, UltiMaker 3 by its variants), so "nothing inherits
 * from it" would silently drop working machines. Extruder trains are usually declared on a
 * *base* definition (the Ender 3 chain declares `creality_base_extruder_0` in
 * `creality_base`), which is why the closure walks the whole chain instead of reading one file.
 */
object CuraMachineCatalog {

    data class Machine(
        val id: String,
        val file: String,
        val name: String,
    )

    /** One machine's definition files: the machine and its parents, then its extruder trains. */
    data class Closure(
        val machineFile: String,
        val extruderFile: String,
        val files: List<String>,
    )

    /** Human-readable name of a definition, falling back to its identifier. */
    fun displayName(definitionJson: String, id: String): String {
        val name = runCatching { JSONObject(definitionJson).optString("name").trim() }.getOrDefault("")
        return name.ifEmpty { id.replace('_', ' ') }
    }

    /**
     * The selectable machines in [definitionIds].
     *
     * @param load reads one definition by id (without the `.def.json` suffix); a definition it
     *   cannot read is skipped rather than failing the catalogue.
     */
    fun machinesFrom(definitionIds: Collection<String>, load: (String) -> String?): List<Machine> {
        val definitions = definitionIds
            .mapNotNull { id -> load(id)?.let { id to it } }
            .toMap()
        val inherited = definitions.values.mapNotNullTo(HashSet(), ::parentOf)
        return definitions.mapNotNull { (id, json) ->
            if (isSharedBase(id, inherited)) return@mapNotNull null
            if (EXTRUDER_SUFFIX.containsMatchIn(id)) return@mapNotNull null
            if (!reachesFdmPrinter(id, definitions::get)) return@mapNotNull null
            val metadata = runCatching { JSONObject(json).optJSONObject("metadata") }.getOrNull()
            if (metadata?.optBoolean("visible", true) == false) return@mapNotNull null
            if (closureOf(id, load) == null) return@mapNotNull null
            Machine(id = id, file = id + DEFINITION_SUFFIX, name = displayName(json, id))
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * The definition files [machineId] needs.
     *
     * @returns null when the machine itself cannot be read, so a stale stored choice falls back
     *   to the bundled default instead of slicing with a half-resolved stack.
     */
    fun closureOf(machineId: String, load: (String) -> String?): Closure? {
        if (load(machineId) == null) return null
        val files = LinkedHashSet<String>()
        val extruders = LinkedHashSet<String>()
        var complete = true

        var current: String? = machineId
        var guard = 0
        while (current != null && guard++ < MAX_INHERITANCE_DEPTH) {
            val json = load(current)
            if (json == null) {
                complete = false
                break
            }
            files.add(current + DEFINITION_SUFFIX)
            extruders.addAll(extruderTrainsOf(json))
            current = parentOf(json)
        }
        extruders.forEach { train ->
            if (!walkInheritance(train, load, files)) complete = false
        }
        if (!complete) return null

        val extruder = extruders.firstOrNull()?.plus(DEFINITION_SUFFIX) ?: DEFAULT_EXTRUDER_FILE
        return Closure(
            machineFile = machineId + DEFINITION_SUFFIX,
            extruderFile = extruder,
            files = files.toList(),
        )
    }

    /** Walks one extruder train up to `fdmextruder`; false when a file in the chain is missing. */
    private fun walkInheritance(root: String, load: (String) -> String?, into: MutableSet<String>): Boolean {
        var current: String? = root
        var guard = 0
        while (current != null && guard++ < MAX_INHERITANCE_DEPTH) {
            val json = load(current) ?: return false
            into.add(current + DEFINITION_SUFFIX)
            current = parentOf(json)
        }
        return true
    }

    /**
     * True for the visible scaffolding Cura keeps for vendors whose printers inherit it
     * (`elegoo_base`, `sovol_base_titan`, `sovol_base_planetary`, `lnl3d_base`,
     * `modix_v3_base`, `modix_v4_base`, `*_common`). They reach `fdmprinter` and are
     * selectable by every other test, but they are not printers. A base nothing inherits
     * (`sovol_base_bowden`) is a machine in its own right and stays.
     */
    private fun isSharedBase(id: String, inherited: Set<String>): Boolean =
        id in inherited && id.split('_').any { it == "base" || it == "common" }

    private fun reachesFdmPrinter(id: String, load: (String) -> String?): Boolean {
        var current: String? = id
        var guard = 0
        while (current != null && guard++ < MAX_INHERITANCE_DEPTH) {
            if (current == FDM_PRINTER) return true
            val json = load(current) ?: return false
            current = parentOf(json)
        }
        return false
    }

    private fun parentOf(definitionJson: String): String? =
        runCatching { JSONObject(definitionJson).optString("inherits").trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() && it != "null" }

    /**
     * The extruder trains a definition declares, by file name without the suffix and ordered by
     * slot, so the first entry is the train the app's single-extruder view slices with.
     */
    private fun extruderTrainsOf(definitionJson: String): List<String> =
        runCatching {
            val metadata = JSONObject(definitionJson).optJSONObject("metadata") ?: return emptyList()
            val trains = metadata.optJSONObject("machine_extruder_trains") ?: return emptyList()
            val slots = trains.keys().asSequence().toList()
            slots.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                .mapNotNull { slot ->
                    trains.optString(slot).trim()
                        .takeIf { it.isNotEmpty() }
                        ?.removeSuffix(DEFINITION_SUFFIX)
                }
        }.getOrDefault(emptyList())

    // --- asset-facing wrappers -------------------------------------------------------------

    /** Every selectable machine in the bundled tree. */
    fun machines(assets: AssetManager): List<Machine> = runCatching {
        val ids = assets.list(DEFINITIONS_DIR).orEmpty()
            .filter { it.endsWith(DEFINITION_SUFFIX) }
            .map { it.removeSuffix(DEFINITION_SUFFIX) }
        // Each machine's closure re-reads its parents, so the whole tree is read once and reused
        // rather than ~4000 times.
        val cache = HashMap<String, String?>()
        machinesFrom(ids) { id ->
            if (cache.containsKey(id)) {
                cache[id]
            } else {
                readDefinition(assets, id).also { cache[id] = it }
            }
        }
    }.getOrDefault(emptyList())

    /** The closure of one machine, or null when it is not in the bundled tree. */
    fun closure(assets: AssetManager, machineId: String): Closure? = runCatching {
        closureOf(machineId) { id -> readDefinition(assets, id) }
    }.getOrNull()

    private fun readDefinition(assets: AssetManager, id: String): String? = runCatching {
        assets.open(DEFINITIONS_DIR + "/" + id + DEFINITION_SUFFIX)
            .bufferedReader().use { it.readText() }
    }.getOrNull()

    /** The machine the app slices with when the user has not chosen another. */
    const val DEFAULT_MACHINE_ID = "creality_ender3"

    /** The extruder train used when a machine declares none anywhere in its chain. */
    const val DEFAULT_EXTRUDER_FILE = "fdmextruder.def.json"

    const val DEFINITION_SUFFIX = ".def.json"
    const val DEFINITIONS_DIR = "cura/definitions"
    private const val FDM_PRINTER = "fdmprinter"
    private const val MAX_INHERITANCE_DEPTH = 16
    private val EXTRUDER_SUFFIX = Regex("_extruder(_\\d+|_(left|right|\\d+))?$")
}
