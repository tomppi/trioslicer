package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import java.io.InputStream

object CuraProjectParser {
    private data class Container(
        val path: String,
        val baseName: String,
        val ini: CuraIni,
    )

    fun parse(
        input: InputStream,
        sourceName: String,
        baseSettings: SlicerSettings,
    ): ImportedCuraConfig {
        val entries = CuraArchive.readTextEntries(input, accept = { it.startsWith("Cura/") })
        require(entries.keys.any { it.startsWith("Cura/") }) {
            "This 3MF has no Cura project configuration. It may be a model-only 3MF export."
        }

        val containers = entries
            .filterKeys { it.startsWith("Cura/") && it.endsWith(".cfg") }
            .map { (path, text) ->
                Container(path, stripKnownSuffix(path.substringAfterLast('/')), CuraIniParser.parse(text))
            }

        val global = containers.firstOrNull { it.path.endsWith(".global.cfg") }
            ?: error("No Cura machine .global.cfg container was found")
        val extruder = containers.firstOrNull { it.path.endsWith(".extruder.cfg") }

        val warnings = mutableListOf<String>()
        val definitionFiles = entries
            .filterKeys { it.startsWith("Cura/") && it.endsWith(".def.json") }
            .mapKeys { (path, _) -> path.substringAfterLast('/') }
        val definitionIds = definitionFiles.keys.map { it.removeSuffix(".def.json") }.toSet()

        val globalValues = resolveStack(global, containers, definitionIds, warnings)
        val extruderStackValues = if (extruder != null) {
            resolveStack(extruder, containers, definitionIds, warnings)
        } else {
            warnings += "No extruder stack was found; only global settings were imported"
            emptyMap()
        }

        val machineDefinitionId = global.ini["containers"]
            .values
            .map(String::trim)
            .lastOrNull { it in definitionIds }
            ?: definitionIds.firstOrNull { it.contains("ender3", ignoreCase = true) }
        val extruderDefinitionId = extruder?.ini?.get("containers")
            ?.values
            ?.map(String::trim)
            ?.lastOrNull { it in definitionIds }
            ?: definitionIds.firstOrNull { it.contains("extruder", ignoreCase = true) }

        val materialContainerIds = extruder?.ini?.get("containers")
            ?.values
            ?.map(String::trim)
            ?.filter { id ->
                id.isNotBlank() &&
                    !id.startsWith("empty_", ignoreCase = true) &&
                    id !in definitionIds
            }
            .orEmpty()
        val materialValues = parseMaterialValues(
            entries = entries,
            machineDefinitionId = machineDefinitionId,
            referencedContainerIds = materialContainerIds,
            warnings = warnings,
        )
        val extruderValuesWithMaterial = linkedMapOf<String, String>().apply {
            putAll(materialValues)
            putAll(extruderStackValues)
        }
        val resolved = CuraExpressionResolver.resolve(globalValues, extruderValuesWithMaterial)

        // Raw engine values retain their real settings-stack separation and all
        // imported formulas. The small arithmetic resolver is used only for the
        // Android quick-settings UI and diagnostics. The complete definition
        // resolver runs at slice time after app edits have been applied.
        val merged = linkedMapOf<String, String>().apply {
            putAll(resolved.globalValues)
            putAll(resolved.extruderValues)
        }

        // The Android quick-settings model contains both global and extruder
        // controls. Populate it with extruder/material values first, then let
        // Cura's global stack win for global controls such as bed temperature,
        // supports, layer height and adhesion.
        val uiValues = linkedMapOf<String, String>().apply {
            putAll(resolved.extruderValues)
            putAll(resolved.globalValues)
        }

        val version = entries["Cura/version.ini"]
            ?.let(CuraIniParser::parse)
            ?.value("versions", "cura_version")
        val machineName = global.ini.value("general", "name") ?: sourceName
        val settingVersion = global.ini.value("metadata", "setting_version")

        if (definitionFiles.isEmpty()) {
            warnings += "No flattened Cura machine definitions were embedded; bundled fallback definitions will be used"
        }
        if (resolved.unresolvedExpressions.isNotEmpty()) {
            warnings += "${resolved.unresolvedExpressions.size} imported formulas will be resolved with the complete Cura definitions at slice time"
        }

        val engineProfile = CuraEngineProfile(
            globalValues = resolved.globalValues,
            extruderValues = resolved.extruderValues,
            rawGlobalValues = globalValues,
            rawExtruderValues = extruderValuesWithMaterial,
            definitionFiles = definitionFiles,
            machineDefinitionFileName = machineDefinitionId?.let { "$it.def.json" },
            extruderDefinitionFileName = extruderDefinitionId?.let { "$it.def.json" },
            unresolvedExpressions = resolved.unresolvedExpressions,
        )

        return ImportedCuraConfig(
            name = machineName,
            source = "Cura project: $sourceName",
            curaVersion = version,
            settingVersion = settingVersion,
            rawValues = merged,
            mappedSettings = CuraSettingsMapper.apply(baseSettings, uiValues),
            startGcode = resolved.globalValues["machine_start_gcode"]
                ?: resolved.extruderValues["machine_start_gcode"],
            endGcode = resolved.globalValues["machine_end_gcode"]
                ?: resolved.extruderValues["machine_end_gcode"],
            engineProfile = engineProfile,
            warnings = warnings.distinct(),
        )
    }

    private fun parseMaterialValues(
        entries: Map<String, String>,
        machineDefinitionId: String?,
        referencedContainerIds: List<String>,
        warnings: MutableList<String>,
    ): Map<String, String> {
        val materials = entries.filterKeys { it.startsWith("Cura/") && it.endsWith(".fdm_material") }
        if (materials.isEmpty()) return emptyMap()

        val selected = materials.filterKeys { path ->
            if (referencedContainerIds.isEmpty()) return@filterKeys true
            val baseName = path.substringAfterLast('/')
                .removeSuffix(".xml.fdm_material")
                .removeSuffix(".fdm_material")
            referencedContainerIds.any { id -> id == baseName || id.startsWith("${baseName}_") }
        }.ifEmpty { materials }

        val merged = linkedMapOf<String, String>()
        selected.forEach { (path, xml) ->
            runCatching { CuraMaterialParser.parse(xml, machineDefinitionId) }
                .onSuccess(merged::putAll)
                .onFailure { warnings += "Unable to parse Cura material ${path.substringAfterLast('/')}: ${it.message}" }
        }
        return merged
    }

    private fun resolveStack(
        stack: Container,
        all: List<Container>,
        definitionIds: Set<String>,
        warnings: MutableList<String>,
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val listed = stack.ini["containers"]
            .mapNotNull { (index, id) -> index.toIntOrNull()?.let { it to id.trim() } }
            .sortedByDescending { it.first }

        for ((_, id) in listed) {
            if (id.startsWith("empty_")) continue
            val container = findContainer(id, all)
            if (container == null) {
                if (id !in definitionIds && !id.contains("material", ignoreCase = true)) {
                    warnings += "Missing referenced Cura container: $id"
                }
                continue
            }
            result.putAll(container.ini["values"])
        }
        return result
    }

    private fun findContainer(id: String, all: List<Container>): Container? {
        return all.firstOrNull { it.baseName == id }
            ?: all.firstOrNull { it.ini.value("general", "id") == id }
            ?: all.firstOrNull { it.ini.value("general", "name") == id }
    }

    private fun stripKnownSuffix(filename: String): String = when {
        filename.endsWith(".global.cfg") -> filename.removeSuffix(".global.cfg")
        filename.endsWith(".extruder.cfg") -> filename.removeSuffix(".extruder.cfg")
        filename.endsWith(".inst.cfg") -> filename.removeSuffix(".inst.cfg")
        filename.endsWith(".cfg") -> filename.removeSuffix(".cfg")
        else -> filename
    }
}
