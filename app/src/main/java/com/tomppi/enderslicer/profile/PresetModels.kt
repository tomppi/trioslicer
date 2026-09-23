package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerEngine
import org.json.JSONObject

enum class PresetKind(val label: String, val pluralLabel: String) {
    PRINT("Print profile", "Print profiles"),
    FILAMENT("Filament", "Filaments"),
}

/**
 * A named user preset. [engine] scopes it: a preset is captured from, listed for and applied to
 * exactly one engine's settings model, because the three models do not share a vocabulary.
 */
data class UserPreset(
    val id: String,
    val engine: SlicerEngine,
    val kind: PresetKind,
    val name: String,
    val valuesJson: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    fun values(): JSONObject = JSONObject(valuesJson)
}

/**
 * The saved preset library. Names are unique within an engine and a kind, and each engine keeps
 * its own active print and filament preset, so switching engines neither hides nor re-points the
 * other engine's selection.
 */
data class PresetLibrary(
    val presets: List<UserPreset> = emptyList(),
    val activePrintPresetIds: Map<SlicerEngine, String> = emptyMap(),
    val activeFilamentPresetIds: Map<SlicerEngine, String> = emptyMap(),
) {
    fun presets(engine: SlicerEngine, kind: PresetKind): List<UserPreset> = presets
        .asSequence()
        .filter { it.engine == engine && it.kind == kind }
        .sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
        .toList()

    fun activeId(engine: SlicerEngine, kind: PresetKind): String? = when (kind) {
        PresetKind.PRINT -> activePrintPresetIds[engine]
        PresetKind.FILAMENT -> activeFilamentPresetIds[engine]
    }

    fun active(engine: SlicerEngine, kind: PresetKind): UserPreset? {
        val id = activeId(engine, kind) ?: return null
        return presets.firstOrNull { it.id == id && it.kind == kind && it.engine == engine }
    }

    fun withPreset(preset: UserPreset): PresetLibrary = copy(
        presets = presets.filterNot { it.id == preset.id } + preset,
    )

    fun withActive(engine: SlicerEngine, kind: PresetKind, id: String?): PresetLibrary = when (kind) {
        PresetKind.PRINT -> copy(
            activePrintPresetIds = activePrintPresetIds.toMutableMap().apply {
                if (id == null) remove(engine) else put(engine, id)
            },
        )

        PresetKind.FILAMENT -> copy(
            activeFilamentPresetIds = activeFilamentPresetIds.toMutableMap().apply {
                if (id == null) remove(engine) else put(engine, id)
            },
        )
    }

    /** Every engine's active marker for one preset id, so deletion clears all of them. */
    fun withoutActive(id: String): PresetLibrary = copy(
        activePrintPresetIds = activePrintPresetIds.filterValues { it != id },
        activeFilamentPresetIds = activeFilamentPresetIds.filterValues { it != id },
    )
}
