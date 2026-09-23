package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.SlicerSettings

/**
 * App-owned settings consumed by the enderslicercura CuraEngine patch.
 *
 * These keys intentionally do not belong to Cura's upstream definition tree.
 * They are appended to the temporary resolved slice snapshot after Cura has
 * evaluated its normal dependency graph.
 */
internal object ArcOverhangEngineSettings {
    const val ENABLED = "enderslicer_arc_overhang_enabled"
    const val SPEED = "enderslicer_arc_overhang_speed"
    const val FLOW = "enderslicer_arc_overhang_flow"
    const val LINE_SPACING = "enderslicer_arc_overhang_line_spacing"
    const val MIN_RADIUS = "enderslicer_arc_overhang_min_radius"
    const val MAX_RADIUS = "enderslicer_arc_overhang_max_radius"
    const val MAX_AREA = "enderslicer_arc_overhang_max_area"
    const val RESOLUTION = "enderslicer_arc_overhang_resolution"
    const val FAN_SPEED = "enderslicer_arc_overhang_fan_speed"

    fun values(settings: SlicerSettings): LinkedHashMap<String, String> {
        validate(settings)
        return linkedMapOf(
            ENABLED to settings.arcOverhangEnabled.toString(),
            SPEED to settings.arcOverhangSpeedMmPerSecond.toString(),
            FLOW to settings.arcOverhangFlowPercent.toString(),
            LINE_SPACING to settings.arcOverhangLineSpacingPercent.toString(),
            MIN_RADIUS to settings.arcOverhangMinRadiusMm.toString(),
            MAX_RADIUS to settings.arcOverhangMaxRadiusMm.toString(),
            MAX_AREA to settings.arcOverhangMaxAreaMm2.toString(),
            RESOLUTION to settings.arcOverhangResolutionMm.toString(),
            FAN_SPEED to settings.arcOverhangFanSpeedPercent.toString(),
        )
    }

    fun validate(settings: SlicerSettings) {
        require(settings.arcOverhangSpeedMmPerSecond in 0.5..50.0) {
            "Arc-overhang speed must be between 0.5 and 50 mm/s"
        }
        require(settings.arcOverhangFlowPercent in 50.0..200.0) {
            "Arc-overhang flow must be between 50% and 200%"
        }
        require(settings.arcOverhangLineSpacingPercent in 50.0..200.0) {
            "Arc-overhang line spacing must be between 50% and 200%"
        }
        require(settings.arcOverhangMinRadiusMm in 0.1..20.0) {
            "Arc-overhang minimum radius must be between 0.1 and 20 mm"
        }
        require(settings.arcOverhangMaxRadiusMm in settings.arcOverhangMinRadiusMm..100.0) {
            "Arc-overhang maximum radius must be at least the minimum radius and no more than 100 mm"
        }
        require(settings.arcOverhangMaxAreaMm2 in 1.0..10_000.0) {
            "Arc-overhang maximum area must be between 1 and 10,000 mm²"
        }
        require(settings.arcOverhangResolutionMm in 0.02..1.0) {
            "Arc-overhang resolution must be between 0.02 and 1.0 mm"
        }
        require(settings.arcOverhangFanSpeedPercent in 0.0..100.0) {
            "Arc-overhang fan speed must be between 0% and 100%"
        }
    }
}
