package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.SlicerSettings

/** App-owned settings consumed by the TrioSlicer CuraEngine wave patch. */
internal object WaveOverhangEngineSettings {
    const val ENABLED = "enderslicer_wave_overhang_enabled"
    const val PATTERN = "enderslicer_wave_overhang_pattern"
    const val LINE_SPACING = "enderslicer_wave_overhang_line_spacing"
    const val FLOW_MM3_PER_MM = "enderslicer_wave_overhang_flow_mm3_per_mm"
    const val SPEED = "enderslicer_wave_overhang_speed"
    const val FAN_SPEED = "enderslicer_wave_overhang_fan_speed"
    const val PERIMETER_OVERLAP = "enderslicer_wave_overhang_perimeter_overlap"
    const val MINIMUM_WIDTH = "enderslicer_wave_overhang_minimum_width"
    const val MAX_ITERATIONS = "enderslicer_wave_overhang_max_iterations"
    const val REVERSE_ODD_LAYERS = "enderslicer_wave_overhang_reverse_odd_layers"

    fun values(settings: SlicerSettings): LinkedHashMap<String, String> {
        validate(settings)
        return linkedMapOf(
            ENABLED to settings.waveOverhangEnabled.toString(),
            PATTERN to settings.waveOverhangPattern,
            LINE_SPACING to settings.waveOverhangLineSpacingMm.toString(),
            FLOW_MM3_PER_MM to settings.waveOverhangFlowMm3PerMm.toString(),
            SPEED to settings.waveOverhangSpeedMmPerSecond.toString(),
            FAN_SPEED to settings.waveOverhangFanSpeedPercent.toString(),
            PERIMETER_OVERLAP to settings.waveOverhangPerimeterOverlapMm.toString(),
            MINIMUM_WIDTH to settings.waveOverhangMinimumWidthMm.toString(),
            MAX_ITERATIONS to settings.waveOverhangMaxIterations.toString(),
            REVERSE_ODD_LAYERS to settings.waveOverhangReverseOddLayers.toString(),
        )
    }

    fun validate(settings: SlicerSettings) {
        require(!(settings.arcOverhangEnabled && settings.waveOverhangEnabled)) {
            "Arc overhangs and wave overhangs are alternative path strategies; enable only one"
        }
        require(settings.waveOverhangPattern in setOf("smart", "monotonic", "zigzag")) {
            "Wave-overhang pattern must be Smart, Monotonic, or Zigzag"
        }
        require(settings.waveOverhangLineSpacingMm in 0.1..2.0) {
            "Wave-overhang line spacing must be between 0.1 and 2.0 mm"
        }
        require(settings.waveOverhangFlowMm3PerMm in 0.02..1.5) {
            "Wave-overhang flow must be between 0.02 and 1.5 mm³/mm"
        }
        require(settings.waveOverhangSpeedMmPerSecond in 0.5..50.0) {
            "Wave-overhang speed must be between 0.5 and 50 mm/s"
        }
        require(settings.waveOverhangFanSpeedPercent in 0.0..100.0) {
            "Wave-overhang fan speed must be between 0% and 100%"
        }
        require(settings.waveOverhangPerimeterOverlapMm in 0.0..2.0) {
            "Wave-overhang perimeter overlap must be between 0 and 2 mm"
        }
        require(settings.waveOverhangMinimumWidthMm in 0.0..10.0) {
            "Wave-overhang minimum width must be between 0 and 10 mm"
        }
        require(settings.waveOverhangMaxIterations in 1..2000) {
            "Wave-overhang maximum iterations must be between 1 and 2000"
        }
    }
}
