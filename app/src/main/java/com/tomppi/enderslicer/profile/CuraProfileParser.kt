package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import java.io.InputStream

object CuraProfileParser {
    fun parse(
        input: InputStream,
        sourceName: String,
        baseSettings: SlicerSettings,
    ): ImportedCuraConfig {
        val entries = CuraArchive.readTextEntries(input)
        require(entries.isNotEmpty()) { "The Cura profile archive is empty" }

        data class Layer(val name: String, val position: Int?, val ini: CuraIni)

        val layers = entries.mapNotNull { (_, text) ->
            val ini = runCatching { CuraIniParser.parse(text) }.getOrNull() ?: return@mapNotNull null
            if (ini["values"].isEmpty()) return@mapNotNull null
            Layer(
                name = ini.value("general", "name") ?: sourceName,
                position = ini.value("metadata", "position")?.toIntOrNull(),
                ini = ini,
            )
        }

        require(layers.isNotEmpty()) { "No [values] sections were found in the Cura profile" }

        val merged = linkedMapOf<String, String>()
        layers.sortedWith(compareBy<Layer> { it.position ?: -1 }).forEach { layer ->
            merged.putAll(layer.ini["values"])
        }

        val resolved = CuraExpressionResolver.resolve(emptyMap(), merged)
        val concrete = resolved.extruderValues
        val primary = layers.first()
        val settingVersion = primary.ini.value("metadata", "setting_version")
        val warnings = buildList {
            if (resolved.unresolvedExpressions.isNotEmpty()) {
                add("${resolved.unresolvedExpressions.size} imported formulas require a compatible machine definition at slice time")
            }
        }

        return ImportedCuraConfig(
            name = primary.name,
            source = "Cura profile: $sourceName",
            settingVersion = settingVersion,
            rawValues = concrete,
            mappedSettings = CuraSettingsMapper.apply(baseSettings, concrete),
            engineProfile = CuraEngineProfile(
                extruderValues = concrete,
                rawExtruderValues = merged,
                unresolvedExpressions = resolved.unresolvedExpressions,
            ),
            warnings = warnings,
        )
    }
}
