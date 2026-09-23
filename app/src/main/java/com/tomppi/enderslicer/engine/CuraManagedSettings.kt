package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.profile.CuraSettingDelta

/**
 * Keys the app already feeds CuraEngine every slice; the all-settings catalog
 * annotates these as already managed (an added extra overrides the app value
 * because it is applied last in the command line).
 */
object CuraManagedSettings {
    private val cached: Set<String> by lazy {
        val base = SlicerSettings()
        buildSet {
            addAll(CuraSettingDelta.standaloneValues(base).keys)
            addAll(ArcOverhangEngineSettings.values(base).keys)
            addAll(WaveOverhangEngineSettings.values(base).keys)
            addAll(BrickWallEngineSettings.values(base).keys)
            addAll(MasonryWallsEngineSettings.values(base).keys)
            addAll(
                setOf(
                    "machine_width", "machine_depth", "machine_height", "machine_center_is_zero",
                    "machine_heated_bed", "machine_nozzle_size", "machine_extruder_count",
                    "gantry_height", "machine_head_with_fans_polygon", "machine_nozzle_offset_x",
                    "machine_nozzle_offset_y", "machine_nozzle_tip_clearance", "machine_x_max", "machine_x_min",
                    "machine_y_max", "machine_y_min", "machine_z_max", "layer_height", "layer_height_0",
                ),
            )
        }
    }

    fun keys(): Set<String> = cached
}
