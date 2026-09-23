package com.tomppi.enderslicer.smartinfill

/** Cura-side interpretation of the pinned filaSim print contract. */
internal object SmartInfillCuraContract {
    /** Line-width keys overridden with the Smart Infill line width in both transports. */
    val smartInfillWidthKeys: List<String> = listOf(
        "line_width",
        "wall_line_width",
        "wall_line_width_0",
        "wall_line_width_x",
        "skin_line_width",
        "infill_line_width",
    )

    /**
     * Shell settings belong to the printable mesh. A Smart Infill modifier only
     * partitions that mesh's sparse-infill volume and selects regional infill.
     */
    val modifierShellNeutralValues: Map<String, String> = linkedMapOf(
        "wall_line_count" to "0",
        "wall_thickness" to "0",
        "top_layers" to "0",
        "bottom_layers" to "0",
        "initial_bottom_layers" to "0",
        "top_bottom_thickness" to "0",
        "roofing_layer_count" to "0",
        "flooring_layer_count" to "0",
    )

    fun neutralizeModifierShell(values: Map<String, String>): Map<String, String> =
        LinkedHashMap(values).apply { putAll(modifierShellNeutralValues) }

    fun curaPattern(pattern: String): String = when (pattern.trim().lowercase()) {
        "cubic" -> "cubic"
        "gyroid" -> "gyroid"
        "grid" -> "grid"
        "rectilinear", "zig-zag", "zigzag" -> "zigzag"
        "concentric" -> "concentric"
        else -> error("filaSim returned an unsupported Cura infill pattern: $pattern")
    }

    fun basePattern(packageValue: SmartInfillPackage): String = curaPattern(packageValue.pattern)

    fun modifierPattern(packageValue: SmartInfillPackage, densityPercent: Int): String {
        require(packageValue.modifiers.any { it.densityPercent == densityPercent }) {
            "Smart Infill modifier density is not part of this package: $densityPercent"
        }
        return when {
            packageValue.mode == "binary" -> curaPattern(
                // Persisted/imported binary packages are validated to contain
                // this field. The fallback keeps direct unit fixtures source
                // compatible while their dedicated pattern tests opt in.
                packageValue.binarySolidPattern ?: packageValue.pattern,
            )
            densityPercent == 100 -> curaPattern(GRADED_FULL_DENSITY_PATTERN)
            else -> basePattern(packageValue)
        }
    }

    const val GRADED_FULL_DENSITY_PATTERN = "rectilinear"
}
