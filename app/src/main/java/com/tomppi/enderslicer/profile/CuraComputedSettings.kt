package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings

data class CuraComputedValue(
    val key: String,
    val label: String,
    val value: String,
    val source: String,
)

data class CuraComputedSnapshot(
    val values: List<CuraComputedValue>,
    val expressionCount: Int,
    val passes: Int,
)

object CuraComputedSettings {
    private val displayedKeys = linkedMapOf(
        "wall_thickness" to "Wall thickness",
        "wall_line_width_0" to "Outer wall line width",
        "wall_line_width_x" to "Inner wall line width",
        "top_bottom_thickness" to "Top/bottom thickness",
        "top_thickness" to "Top thickness",
        "bottom_thickness" to "Bottom thickness",
        "infill_line_width" to "Infill line width",
        "infill_line_distance" to "Infill line spacing",
        "skin_line_width" to "Skin line width",
        "cool_fan_full_at_height" to "Full fan at height",
        "support_line_width" to "Support line width",
        "support_line_distance" to "Support line spacing",
        "support_interface_height" to "Support interface height",
        "support_roof_height" to "Support roof height",
        "support_bottom_height" to "Support floor height",
        "raft_total_thickness" to "Raft total thickness",
    )

    fun resolve(
        profile: CuraEngineProfile,
        printer: PrinterDefinition,
        settings: SlicerSettings,
        startGcode: String,
        endGcode: String,
    ): CuraComputedSnapshot? {
        if (!profile.usesProjectDefinitions) return null
        val result = CuraSliceSettingsResolver.resolve(
            profile = profile,
            printer = printer,
            settings = settings,
            startGcode = startGcode,
            endGcode = endGcode,
        )
        val formulaSources = linkedMapOf<String, String>().apply {
            putAll(profile.rawGlobalValues.filterValues { it.trim().startsWith("=") })
            putAll(profile.rawExtruderValues.filterValues { it.trim().startsWith("=") })
        }
        val values = displayedKeys.mapNotNull { (key, label) ->
            val value = result.extruderValues[key] ?: result.globalValues[key] ?: return@mapNotNull null
            CuraComputedValue(
                key = key,
                label = label,
                value = value,
                source = formulaSources[key]?.let { "Cura formula: $it" } ?: "Computed from Cura definitions",
            )
        }
        return CuraComputedSnapshot(
            values = values,
            expressionCount = result.expressionCount,
            passes = result.passes,
        )
    }
}
