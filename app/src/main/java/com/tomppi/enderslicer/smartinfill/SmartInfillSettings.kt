package com.tomppi.enderslicer.smartinfill

import com.tomppi.enderslicer.model.SlicerSettings

/**
 * filaSim optimizes against a specific wall, layer and infill model. The Cura
 * slice must use those same assumptions or the optimized density field no
 * longer represents the printed part that was analyzed.
 */
fun SmartInfillPackage.applyTo(settings: SlicerSettings): SlicerSettings {
    val curaPattern = SmartInfillCuraContract.basePattern(this)
    val wallThickness = perimeters * lineWidthMm
    val shellThickness = topBottomLayers * layerHeightMm
    return settings.copy(
        adaptiveLayerHeightEnabled = false,
        layerHeightMm = layerHeightMm,
        lineWidthMm = lineWidthMm,
        wallLineCount = perimeters,
        wallThicknessMm = wallThickness,
        topLayers = topBottomLayers,
        bottomLayers = topBottomLayers,
        initialBottomLayers = topBottomLayers,
        topBottomThicknessMm = shellThickness,
        infillDensityPercent = baseDensityPercent,
        infillPattern = curaPattern,
        overriddenSettingKeys = settings.overriddenSettingKeys + setOf(
            SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_ENABLED,
            SlicerSettings.Keys.LAYER_HEIGHT,
            SlicerSettings.Keys.LINE_WIDTH,
            SlicerSettings.Keys.WALL_LINE_COUNT,
            SlicerSettings.Keys.WALL_THICKNESS,
            SlicerSettings.Keys.TOP_LAYERS,
            SlicerSettings.Keys.BOTTOM_LAYERS,
            SlicerSettings.Keys.INITIAL_BOTTOM_LAYERS,
            SlicerSettings.Keys.TOP_BOTTOM_THICKNESS,
            SlicerSettings.Keys.INFILL_DENSITY,
            SlicerSettings.Keys.INFILL_PATTERN,
        ),
    )
}
