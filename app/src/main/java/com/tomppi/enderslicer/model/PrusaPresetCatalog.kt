package com.tomppi.enderslicer.model

/**
 * The machine, quality and material presets the shipped PrusaSlicer repository offers.
 *
 * A printer is one of the bundle's printer_config documents: the hardware model plus the tool,
 * sheet and feeder it was configured with (32 of them, e.g. "Prusa MK4" with a 0.4 nozzle). That
 * configuration is what the repository's 138 conditions are evaluated against, so it is also what
 * decides which quality profiles and materials apply.
 *
 * Resolution follows PrusaSlicer's own evaluator: a preset starts from the documents it inherits
 * (in order), then applies the branches of its variant tree whose conditions hold, in order, and
 * a branch's nested branches after its own values. A branch list is first-match - it stops after
 * the first branch that carries a condition and holds - unless the document declares
 * `match_mode: all_matches`, which the XL, XL+ and CORE One INDX quality trees do. Later values
 * win, which is how a model branch overrides its family's values and a named quality profile
 * overrides the shared commons.
 *
 * Some machines keep their real quality settings in a *tool print*: the print profile only sets
 * `layer_height` and inherits a `default_tool_print` pointer, and the tool print's own tree
 * offers the settings per layer height. Resolving such a profile also applies the tool print its
 * layer height selects, so the picker's choice reaches the slice.
 *
 * [PrusaPresetCatalogSnapshotTest] proves the fidelity: resolving the MK4 0.4 configuration
 * reproduces the values of the prusa3-base.json snapshot that ships with the app, key for key.
 */
/** One entry of a picker: the id to store and the label to show. */
data class PrusaPresetOption(val id: String, val label: String)

/**
 * What the user picked from the bundle. An empty selection leaves the slice exactly as it was
 * before presets existed, which is what an install that never opened the picker does.
 */
data class PrusaPresetSelection(
    val printerId: String? = null,
    val printName: String? = null,
    val filamentName: String? = null,
) {
    val isActive: Boolean get() = !printerId.isNullOrBlank()

    companion object {
        val NONE = PrusaPresetSelection()
    }
}

class PrusaPresetCatalog(private val repository: PrusaPresetRepository) {

    /** One selectable machine: a printer_config of the bundle. */
    data class Printer(
        val id: String,
        val name: String,
        val config: PrusaPresetRepository.Document,
        val printerDocument: PrusaPresetRepository.Document?,
        val baseModel: String,
        val model: String,
        val tool: PrusaPresetRepository.Tool,
        val toolCount: Int,
    ) {
        /** The name the desktop app shows, e.g. "Prusa MK4 0.4" or "Prusa XL 2T 0.4". */
        val label: String
            get() {
                val diameter = tool.diameterMm?.let { trimNumber(it) }.orEmpty()
                return if (toolCount > 1) name + " (" + toolCount + " tools) " + diameter
                else name + " " + diameter
            }
    }

    /** A named quality profile, with the branch chain that resolves it. */
    private class PrintProfile(
        val document: PrusaPresetRepository.Document,
        val name: String,
        val chain: List<PrusaPresetRepository.Variant>,
    )

    private class FilamentPreset(
        val document: PrusaPresetRepository.Document,
        val name: String,
    )

    /** A named tool print, with the branch chain that resolves it. */
    private class ToolPrintProfile(
        val document: PrusaPresetRepository.Document,
        val name: String,
        val chain: List<PrusaPresetRepository.Variant>,
    )

    private val printers: List<Printer> by lazy {
        repository.ofKind("printer_config").mapNotNull { config ->
            val id = config.id ?: return@mapNotNull null
            val printerDocument = config.printer?.let(repository::byId)
            val tool = config.tools.firstOrNull() ?: return@mapNotNull null
            Printer(
                id = id,
                name = config.name ?: id,
                config = config,
                printerDocument = printerDocument,
                baseModel = printerDocument?.baseModel ?: printerDocument?.model ?: id,
                model = printerDocument?.model ?: id,
                tool = tool,
                toolCount = config.toolCount ?: printerDocument?.toolCount ?: config.tools.size,
            )
        }.sortedBy { it.label.lowercase() }
    }

    /** Print and filament lists are per printer, so they are computed once per selection. */
    private val printProfiles = HashMap<String, List<PrintProfile>>()
    private val filamentPresets = HashMap<String, List<FilamentPreset>>()
    private val printerValues = HashMap<String, Map<String, Any?>>()

    fun printers(): List<Printer> = printers

    /** The printer [id] names, or null when a stored choice is no longer in the bundle. */
    fun printer(id: String): Printer? = printers.firstOrNull { it.id == id }

    /**
     * Why this catalogue cannot offer presets, or null when it can.
     *
     * PrusaEngineRunner logs this reason, and a stale stored printer id, before falling back to
     * the base configuration; a caller that can log should ask this (and [printer]) rather than
     * treating the empty result as "the user selected nothing".
     */
    val unavailableReason: String?
        get() = when {
            repository.documents.isEmpty() ->
                "the bundled " + PrusaPresetRepository.ASSET + " carries no presets"
            repository.ofKind("printer_config").isEmpty() ->
                "the bundled " + PrusaPresetRepository.ASSET + " carries no printer configurations"
            printers().isEmpty() ->
                "the bundled " + PrusaPresetRepository.ASSET + " offers no selectable printers"
            else -> null
        }

    /** The quality profiles that apply to [printer], by display name. */
    fun prints(printer: Printer): List<String> =
        profilesFor(printer).map { it.name }.distinct()

    /** True when [name] is still one of the quality profiles [printer] offers. */
    fun hasPrint(printer: Printer, name: String): Boolean =
        profilesFor(printer).any { it.name == name }

    /** The materials that apply to [printer], by display name. */
    fun filaments(printer: Printer): List<String> =
        filamentsFor(printer).map { it.name }.distinct()

    /** True when [name] is still one of the materials [printer] offers. */
    fun hasFilament(printer: Printer, name: String): Boolean =
        filamentsFor(printer).any { it.name == name }

    /** The pickers' entries, ready for the sheet. */
    fun printerOptions(): List<PrusaPresetOption> =
        printers().map { PrusaPresetOption(it.id, it.label) }

    fun printOptions(printer: Printer): List<PrusaPresetOption> =
        prints(printer).map { PrusaPresetOption(it, it) }

    fun filamentOptions(printer: Printer): List<PrusaPresetOption> =
        filaments(printer).map { PrusaPresetOption(it, it) }

    /**
     * The quality profile the bundle picks for [printer], e.g. "0.20mm SPEED @MK4 0.4".
     *
     * The bundle states the choice as `default_print` in the printer preset, or - for the
     * machines whose profiles only set a layer height (XL, XL+, MINI, MK3.5) - as
     * `default_tool_print` in the print chain. Either way the machine's own profile is applied;
     * when neither names an offered profile the first one is used, so "machine default" can
     * never leave the shipped MK4 configuration in place.
     */
    fun defaultPrint(printer: Printer): String? {
        val profiles = profilesFor(printer)
        if (profiles.isEmpty()) return null
        val wanted = valuesFor(printer)["default_print"] as? String
        if (wanted != null) {
            profiles.firstOrNull { it.name == wanted }?.let { return it.name }
            profiles.firstOrNull { it.name.startsWith(wanted + " @") }?.let { return it.name }
        }
        val toolPrint = toolPrintPointer(printer)
        if (toolPrint != null) {
            // "0.20mm SPEED @XL 0.4" starts with the "0.20mm" profile the picker offers.
            profiles.firstOrNull { it.name == toolPrint.substringBefore(' ') }?.let { return it.name }
        }
        return profiles.first().name
    }

    /** The material the bundle picks for [printer], from the quality profile's own defaults. */
    fun defaultFilament(printer: Printer): String? {
        val wanted = valuesFor(printer)["default_material"] as? String
            ?: resolvedPrinterValuesWithPrintDefaults(printer)["default_material"] as? String
            ?: return null
        val available = filamentsFor(printer)
        return available.firstOrNull { it.name == wanted }?.name
            ?: available.firstOrNull { it.name.startsWith(wanted) }?.name
    }

    /** The machine settings of [printer]: its printer preset, or the printer document alone. */
    fun resolvePrinter(printer: Printer): Map<String, Any?> = valuesFor(printer)

    /** Resolves one quality profile of [printer], including the tool print it selects. */
    fun resolvePrint(printer: Printer, name: String): Map<String, Any?> {
        val profile = profilesFor(printer).firstOrNull { it.name == name } ?: return emptyMap()
        val values = LinkedHashMap(resolve(profile.document, printer, profile.chain))
        val toolPrint = toolPrintFor(printer, profile.name, values) ?: return values
        // The XL/XL+/CORE One INDX profiles state only layer_height; the tool print is where
        // their speeds, accelerations and retractions live. Keys the app or the writer owns are
        // dropped, so a preset can never shadow the envelope and the settings sheet.
        for ((key, value) in resolveToolPrint(toolPrint, printer, values)) {
            if (isToolPrintExcluded(key)) continue
            values[key] = value
        }
        return values
    }

    /** Resolves one material of [printer]. */
    fun resolveFilament(printer: Printer, name: String): Map<String, Any?> {
        val preset = filamentsFor(printer).firstOrNull { it.name == name } ?: return emptyMap()
        return resolve(preset.document, printer, emptyList())
    }

    // --- hardware context ------------------------------------------------------------------

    /**
     * The values a condition can see for [printer]: the model, its features, the tool, the sheet
     * and the feeder. Feature defaults come from the bundle, then the printer document's own
     * overrides, then the printer config's.
     */
    private fun hardware(printer: Printer): (String) -> Any? {
        val features = HashMap<String, MutableMap<String, Any?>>()
        for (domain in listOf("printer", "tool", "sheet", "feeder")) {
            val defaults = repository.vendor?.features?.get(domain).orEmpty()
            features[domain] = defaults.mapValuesTo(HashMap()) { it.value.default }
        }
        printer.printerDocument?.featureOverrides("printer")?.forEach { (key, value) ->
            features.getValue("printer")[key] = value
        }
        (printer.config.rawValue("features") as? Map<*, *>)?.forEach { (key, value) ->
            if (key is String) features.getValue("printer")[key] = value
        }
        val sheet = printer.config.sheet?.let(repository::byId)
        val feeder = printer.config.feeders.firstOrNull()?.let(repository::byId)
        val feederModel = feeder?.rawValue("model") as? Map<*, *>
        return { path ->
            when {
                path == "printer.model" -> printer.model
                path == "printer.base_model" -> printer.baseModel
                path == "printer.tool_count" -> printer.toolCount
                path == "tool.nozzle_diameter" -> printer.tool.diameterMm
                path == "tool.nozzle_high_flow" -> printer.tool.highFlow
                path == "sheet.type" -> sheet?.rawValue("type") ?: printer.config.sheet
                path == "feeder.base_model" -> feederModel?.get("base_model")
                path == "feeder.model" -> feederModel?.get("model")
                path.startsWith("printer.") || path.startsWith("tool.") ||
                    path.startsWith("sheet.") || path.startsWith("feeder.") -> {
                    val domain = path.substringBefore('.')
                    features[domain]?.get(path.substringAfter('.'))
                }
                else -> null
            }
        }
    }

    // --- resolution ------------------------------------------------------------------------

    private fun valuesFor(printer: Printer): Map<String, Any?> = printerValues.getOrPut(printer.id) {
        val document = printerPreset(printer)
        if (document == null) {
            // No preset covers this model: the printer document's own values are all we have.
            printer.printerDocument?.values.orEmpty()
        } else {
            resolve(document, printer, emptyList())
        }
    }

    /**
     * The printer preset that covers [printer]: the user-facing printer document whose own
     * branches hold for this hardware (the bundle's own star-named documents only supply the
     * defaults a preset inherits from).
     */
    private fun printerPreset(printer: Printer): PrusaPresetRepository.Document? {
        val lookup = hardware(printer)
        return repository.ofKind("printer").firstOrNull { document ->
            document.file.startsWith("preset-printer-") &&
                !document.id.orEmpty().startsWith("*") &&
                document.variants.any { branch -> matches(branch, lookup, emptyMap()) }
        }
    }

    private fun profilesFor(printer: Printer): List<PrintProfile> = printProfiles.getOrPut(printer.id) {
        val lookup = hardware(printer)
        val result = mutableListOf<PrintProfile>()
        for (document in repository.ofKind("print")) {
            if (!document.file.startsWith("preset-print-")) continue
            if (document.id.orEmpty().startsWith("*")) continue
            val inherited = mutableListOf<Map<String, Any?>>()
            for (parent in document.inherits) {
                repository.byId(parent)?.let { inherited.add(resolveChains(it, lookup, HashMap(), HashSet())) }
            }
            for ((name, chain) in namedVariants(document.variants, lookup, inherited, document.allMatches)) {
                result.add(PrintProfile(document, name, chain))
            }
        }
        result
    }

    private fun filamentsFor(printer: Printer): List<FilamentPreset> = filamentPresets.getOrPut(printer.id) {
        val lookup = hardware(printer)
        repository.ofKind("filament").mapNotNull { document ->
            if (document.index != 0) return@mapNotNull null
            if (!matches(document.condition, lookup, emptyMap())) return@mapNotNull null
            val name = document.name ?: return@mapNotNull null
            FilamentPreset(document, name)
        }.sortedBy { it.name.lowercase() }
    }

    private fun resolveChains(
        document: PrusaPresetRepository.Document,
        lookup: (String) -> Any?,
        out: MutableMap<String, Any?>,
        visited: MutableSet<String>,
    ): Map<String, Any?> {
        val key = document.file + "#" + document.index
        if (!visited.add(key)) return out
        for (parent in document.inherits) {
            repository.byId(parent)?.let { resolveChains(it, lookup, out, visited) }
        }
        out.putAll(document.values)
        applyVariants(document.variants, lookup, out, document.allMatches)
        return out
    }

    private fun resolve(
        document: PrusaPresetRepository.Document,
        printer: Printer,
        chain: List<PrusaPresetRepository.Variant>,
    ): Map<String, Any?> {
        val lookup = hardware(printer)
        val out = LinkedHashMap<String, Any?>()
        resolveChains(document, lookup, out, HashSet())
        for (variant in chain) {
            out.putAll(variant.values)
        }
        return out
    }

    private fun resolvedPrinterValuesWithPrintDefaults(printer: Printer): Map<String, Any?> {
        val reference = defaultPrint(printer) ?: return emptyMap()
        return resolvePrint(printer, reference)
    }

    // --- tool prints -----------------------------------------------------------------------

    /**
     * The tool print the machine's print chain names, e.g. "0.20mm SPEED". It lives in the print
     * preset the machine matches, not in the printer preset, which is why the printer values
     * alone do not carry it.
     */
    private val toolPrintPointers = HashMap<String, String?>()

    private fun toolPrintPointer(printer: Printer): String? = toolPrintPointers.getOrPut(printer.id) {
        val lookup = hardware(printer)
        for (document in repository.ofKind("print")) {
            if (!document.file.startsWith("preset-print-")) continue
            if (document.id.orEmpty().startsWith("*")) continue
            val inherited = LinkedHashMap<String, Any?>()
            for (parent in document.inherits) {
                repository.byId(parent)?.let { resolveChains(it, lookup, inherited, HashSet()) }
            }
            (inherited["default_tool_print"] as? String)?.let { return@getOrPut it }
        }
        null
    }

    /**
     * The tool prints the bundle offers for [printer] in the context of [printValues]. Their
     * names are gated by `print.layer_height`, so the chosen quality profile's layer height
     * decides which of them exist.
     */
    private fun toolPrintProfiles(
        printer: Printer,
        printValues: Map<String, Any?>,
    ): List<ToolPrintProfile> {
        val lookup = hardware(printer)
        val result = mutableListOf<ToolPrintProfile>()
        for (document in repository.ofKind("tool_print")) {
            if (!document.file.startsWith("preset-tool-")) continue
            if (document.id.orEmpty().startsWith("*")) continue
            val inherited = mutableListOf(printValues)
            for (parent in document.inherits) {
                repository.byId(parent)?.let { inherited.add(resolveChains(it, lookup, HashMap(), HashSet())) }
            }
            for ((name, chain) in namedVariants(document.variants, lookup, inherited, document.allMatches)) {
                result.add(ToolPrintProfile(document, name, chain))
            }
        }
        return result
    }

    /**
     * The tool print [profileName] selects, or null when the bundle names none for it. The
     * pointer names the machine's default ("0.20mm SPEED @XL 0.4HF"); the profile's own layer
     * height is the pool, the pointer picks from it and the first entry is the fallback.
     */
    private fun toolPrintFor(
        printer: Printer,
        profileName: String,
        printValues: Map<String, Any?>,
    ): ToolPrintProfile? {
        val pointer = printValues["default_tool_print"] as? String ?: return null
        val candidates = toolPrintProfiles(printer, printValues)
        if (candidates.isEmpty()) return null
        val matching = candidates.filter { it.name == profileName || it.name.startsWith(profileName + " ") }
        val pool = matching.ifEmpty { candidates }
        return pool.firstOrNull { it.name == pointer || it.name.startsWith(pointer + " ") }
            ?: pool.first()
    }

    /** Resolves [toolPrint] with the print profile's values as the `print.` condition context. */
    private fun resolveToolPrint(
        toolPrint: ToolPrintProfile,
        printer: Printer,
        printValues: Map<String, Any?>,
    ): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(printValues)
        resolveChains(toolPrint.document, hardware(printer), out, HashSet())
        for (variant in toolPrint.chain) {
            out.putAll(variant.values)
        }
        return out
    }

    /**
     * Keys a tool print must not shadow: the app's own settings (layer height, extrusion widths,
     * temperatures, the envelope), G-code templates, the bundle's pointers and the keys
     * [PrusaPresetValues] refuses.
     */
    private fun isToolPrintExcluded(key: String): Boolean =
        key.startsWith("default_") || key.startsWith("__") ||
            key.contains("gcode") || key in AllSettingsCatalogs.PRUSA_MANAGED_KEYS ||
            key == "post_process" || key == "printer_variant" ||
            key == "printer_technology" || key == "output_filename_format" || key == "thumbnails"

    // --- variant walking -------------------------------------------------------------------

    /**
     * Branch conditions are evaluated in order, each one seeing the values applied so far.
     *
     * PrusaSlicer's evaluator is first-match: it stops after the first branch that carries a
     * condition and holds, and only a document declaring `match_mode: all_matches` keeps
     * evaluating its siblings. Named leaves carry no condition, so one tree still offers every
     * quality profile it lists.
     */
    private fun applyVariants(
        variants: List<PrusaPresetRepository.Variant>,
        lookup: (String) -> Any?,
        out: MutableMap<String, Any?>,
        allMatches: Boolean,
    ) {
        for (variant in variants) {
            if (!matches(variant, lookup, out)) continue
            out.putAll(variant.values)
            applyVariants(variant.variants, lookup, out, allMatches)
            if (!allMatches && variant.condition != null) break
        }
    }

    private fun matches(
        variant: PrusaPresetRepository.Variant,
        lookup: (String) -> Any?,
        values: Map<String, Any?>,
    ): Boolean = matches(variant.condition, lookup, values)

    private fun matches(
        condition: String?,
        lookup: (String) -> Any?,
        values: Map<String, Any?>,
    ): Boolean {
        val expression = conditionCache.getOrPut(condition.orEmpty()) {
            PrusaPresetCondition.parse(condition)
        } ?: return true
        return expression.matches { path ->
            if (path.startsWith("print.")) {
                val key = path.substringAfter('.')
                if (values.containsKey(key)) return@matches values[key]
            }
            lookup(path)
        }
    }

    /** Every selectable branch reachable from [variants], with the chain that reaches it. */
    private fun namedVariants(
        variants: List<PrusaPresetRepository.Variant>,
        lookup: (String) -> Any?,
        inherited: List<Map<String, Any?>>,
        allMatches: Boolean,
    ): List<Pair<String, List<PrusaPresetRepository.Variant>>> {
        val result = mutableListOf<Pair<String, List<PrusaPresetRepository.Variant>>>()
        val values = LinkedHashMap<String, Any?>()
        inherited.forEach(values::putAll)
        walk(variants, lookup, values, emptyList(), result, allMatches)
        return result
    }

    private fun walk(
        variants: List<PrusaPresetRepository.Variant>,
        lookup: (String) -> Any?,
        values: MutableMap<String, Any?>,
        chain: List<PrusaPresetRepository.Variant>,
        result: MutableList<Pair<String, List<PrusaPresetRepository.Variant>>>,
        allMatches: Boolean,
    ) {
        for (variant in variants) {
            if (!matches(variant, lookup, values)) continue
            val before = LinkedHashMap(values)
            values.putAll(variant.values)
            val nextChain = chain + variant
            if (variant.isSelectable) {
                result.add(variant.name.orEmpty() to nextChain)
            }
            walk(variant.variants, lookup, values, nextChain, result, allMatches)
            values.clear()
            values.putAll(before)
            if (!allMatches && variant.condition != null) break
        }
    }

    private val conditionCache = HashMap<String, PrusaPresetCondition.Expression?>()

    private companion object {
        fun trimNumber(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }
}
