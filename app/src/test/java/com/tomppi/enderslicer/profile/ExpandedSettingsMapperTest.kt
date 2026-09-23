package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpandedSettingsMapperTest {
    @Test
    fun importsExpandedCategorizedSettings() {
        val mapped = CuraSettingsMapper.apply(
            SlicerSettings(),
            mapOf(
                "adaptive_layer_height_enabled" to "true",
                "adaptive_layer_height_variation" to "0.08",
                "adaptive_layer_height_variation_step" to "0.01",
                "adaptive_layer_height_threshold" to "0.16",
                "slicing_tolerance" to "inclusive",
                "wall_line_count" to "4",
                "wall_thickness" to "1.6",
                "top_layers" to "7",
                "bottom_layers" to "6",
                "top_bottom_thickness" to "1.4",
                "initial_bottom_layers" to "3",
                "hole_xy_offset" to "-0.1",
                "xy_offset_layer_0" to "0.2",
                "z_seam_type" to "back",
                "z_seam_x" to "12.5",
                "z_seam_y" to "220.0",
                "z_seam_relative" to "true",
                "z_seam_corner" to "z_seam_corner_weighted",
                "infill_pattern" to "gyroid",
                "zig_zaggify_infill" to "false",
                "speed_wall" to "80",
                "speed_wall_0" to "45",
                "speed_wall_x" to "75",
                "speed_infill" to "140",
                "speed_topbottom" to "60",
                "speed_travel" to "220",
                "speed_layer_0" to "25",
                "build_volume_temperature" to "35",
                "material_standby_temperature" to "175",
                "material_density" to "1.31",
                "material_adhesion_tendency" to "4",
                "material_surface_energy" to "88",
                "material_brand" to "Custom",
                "material_type" to "PLA+",
                "material_guid" to "material-guid",
                "extruders_enabled_count" to "1",
                "cool_fan_speed_0" to "15",
                "cool_fan_full_layer" to "5",
                "retraction_min_travel" to "2.5",
                "retraction_combing" to "noskin",
                "travel_avoid_other_parts" to "true",
                "travel_avoid_distance" to "0.8",
                "retraction_hop" to "0.4",
                "coasting_enable" to "true",
                "coasting_volume" to "0.08",
                "coasting_min_volume" to "1.2",
                "coasting_speed" to "92",
                "support_interface_height" to "0.8",
                "skirt_line_count" to "3",
                "brim_width" to "10",
                "raft_margin" to "12",
                "ironing_enabled" to "true",
                "ironing_only_highest_layer" to "true",
                "ironing_flow" to "12",
                "speed_ironing" to "18",
            ),
        )

        assertTrue(mapped.adaptiveLayerHeightEnabled)
        assertEquals(0.08, mapped.adaptiveLayerHeightVariationMm, 0.0)
        assertEquals(0.01, mapped.adaptiveLayerHeightVariationStepMm, 0.0)
        assertEquals(0.16, mapped.adaptiveLayerHeightThreshold, 0.0)
        assertEquals("inclusive", mapped.slicingTolerance)
        assertEquals(4, mapped.wallLineCount)
        assertEquals(1.6, mapped.wallThicknessMm, 0.0)
        assertEquals(7, mapped.topLayers)
        assertEquals(6, mapped.bottomLayers)
        assertEquals(1.4, mapped.topBottomThicknessMm, 0.0)
        assertEquals(3, mapped.initialBottomLayers)
        assertEquals(-0.1, mapped.holeHorizontalExpansionMm, 0.0)
        assertEquals(0.2, mapped.initialLayerHorizontalExpansionMm, 0.0)
        assertEquals("back", mapped.zSeamType)
        assertEquals(12.5, mapped.zSeamXmm, 0.0)
        assertEquals(220.0, mapped.zSeamYmm, 0.0)
        assertTrue(mapped.zSeamRelative)
        assertEquals("z_seam_corner_weighted", mapped.zSeamCorner)
        assertEquals("gyroid", mapped.infillPattern)
        assertFalse(mapped.zigZagConnectInfill)
        assertEquals(80.0, mapped.wallSpeedMmPerSecond, 0.0)
        assertEquals(45.0, mapped.outerWallSpeedMmPerSecond, 0.0)
        assertEquals(75.0, mapped.innerWallSpeedMmPerSecond, 0.0)
        assertEquals(140.0, mapped.infillSpeedMmPerSecond, 0.0)
        assertEquals(60.0, mapped.topBottomSpeedMmPerSecond, 0.0)
        assertEquals(220.0, mapped.travelSpeedMmPerSecond, 0.0)
        assertEquals(25.0, mapped.initialLayerSpeedMmPerSecond, 0.0)
        assertEquals(35.0, mapped.buildVolumeTemperatureC, 0.0)
        assertEquals(175.0, mapped.materialStandbyTemperatureC, 0.0)
        assertEquals(1.31, mapped.materialDensityGPerCm3, 0.0)
        assertEquals(4, mapped.materialAdhesionTendency)
        assertEquals(88, mapped.materialSurfaceEnergyPercent)
        assertEquals("Custom", mapped.materialBrand)
        assertEquals("PLA+", mapped.materialType)
        assertEquals("material-guid", mapped.materialGuid)
        assertEquals(1, mapped.enabledExtruderCount)
        assertEquals(15.0, mapped.initialFanSpeedPercent, 0.0)
        assertEquals(5, mapped.fanFullAtLayer)
        assertEquals(2.5, mapped.retractionMinimumTravelMm, 0.0)
        assertEquals("noskin", mapped.combingMode)
        assertTrue(mapped.avoidPrintedParts)
        assertEquals(0.8, mapped.travelAvoidDistanceMm, 0.0)
        assertEquals(0.4, mapped.zHopHeightMm, 0.0)
        assertTrue(mapped.coastingEnabled)
        assertEquals(0.08, mapped.coastingVolumeMm3, 0.0)
        assertEquals(1.2, mapped.coastingMinimumVolumeMm3, 0.0)
        assertEquals(92.0, mapped.coastingSpeedPercent, 0.0)
        assertEquals(0.8, mapped.supportInterfaceHeightMm, 0.0)
        assertEquals(3, mapped.skirtLineCount)
        assertEquals(10.0, mapped.brimWidthMm, 0.0)
        assertEquals(12.0, mapped.raftMarginMm, 0.0)
        assertTrue(mapped.ironingEnabled)
        assertTrue(mapped.ironingOnlyHighestLayer)
        assertEquals(12.0, mapped.ironingFlowPercent, 0.0)
        assertEquals(18.0, mapped.ironingSpeedMmPerSecond, 0.0)
        assertTrue(mapped.overriddenSettingKeys.isEmpty())
        assertFalse(mapped.zHopEnabled)
    }
}
