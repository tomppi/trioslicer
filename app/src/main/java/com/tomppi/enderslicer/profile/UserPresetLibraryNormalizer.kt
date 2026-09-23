package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerEngine
import java.util.Locale

internal object UserPresetLibraryNormalizer {
    fun normalize(
        library: PresetLibrary,
        maxPerKind: Int,
    ): PresetLibrary {
        require(maxPerKind > 0) { "Preset limit must be positive" }
        val normalizedPresets = normalizePresets(library.presets, maxPerKind)
        val activePrint = library.activePrintPresetIds.filterValues { id ->
            normalizedPresets.any { it.id == id && it.kind == PresetKind.PRINT }
        }
        val activeFilament = library.activeFilamentPresetIds.filterValues { id ->
            normalizedPresets.any { it.id == id && it.kind == PresetKind.FILAMENT }
        }
        return PresetLibrary(normalizedPresets, activePrint, activeFilament)
    }

    fun normalizePresets(
        presets: List<UserPreset>,
        maxPerKind: Int,
    ): List<UserPreset> {
        require(maxPerKind > 0) { "Preset limit must be positive" }
        val ranked = presets.sortedWith(
            compareByDescending<UserPreset> { it.updatedAtEpochMillis }
                .thenByDescending { it.createdAtEpochMillis }
                .thenBy { it.id },
        )
        val seenIds = hashSetOf<String>()
        val seenNames = hashSetOf<String>()
        val counts = LinkedHashMap<Pair<SlicerEngine, PresetKind>, Int>()
        val selected = ArrayList<UserPreset>(minOf(ranked.size, maxPerKind * PresetKind.entries.size))

        ranked.forEach { preset ->
            if (preset.id.isBlank() || preset.name.isBlank()) return@forEach
            if (preset.id in seenIds) return@forEach
            val nameKey = "${preset.engine.name}\u0000${preset.kind.name}\u0000${preset.name.lowercase(Locale.ROOT)}"
            if (nameKey in seenNames) return@forEach
            val bucket = preset.engine to preset.kind
            if (counts.getOrDefault(bucket, 0) >= maxPerKind) return@forEach

            seenIds += preset.id
            seenNames += nameKey
            counts[bucket] = counts.getOrDefault(bucket, 0) + 1
            selected += preset
        }

        return selected.sortedWith(
            compareBy<UserPreset> { it.engine.name }
                .thenBy { it.kind.name }
                .thenBy { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.id },
        )
    }
}
