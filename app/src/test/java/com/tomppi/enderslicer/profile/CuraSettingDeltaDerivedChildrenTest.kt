package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the parent/child key mismatch found in the standalone
 * (`-s`) transport: CuraEngine only ever reads resolved child keys
 * (cool_fan_speed_min, retraction_retract_speed, wall_0_material_flow,
 * support_roof_enable, ...), while the app's settings are Cura *parent* keys.
 * The engine never evaluates the definition formulas, so every un-emitted
 * child silently falls back to its definition `default_value` (e.g. 100% fan).
 *
 * The delta must mirror the definition formulas into the engine-read children.
 */
class CuraSettingDeltaDerivedChildrenTest {

    @Test
    fun fanSpeedMirrorsIntoRegularAndMaximumFanChildren() {
        val values = CuraSettingDelta.standaloneValues(
            SlicerSettings(fanSpeedPercent = 40.0),
        )

        assertEquals("40.0", values["cool_fan_speed"])
        assertEquals("40.0", values["cool_fan_speed_min"])
        assertEquals("40.0", values["cool_fan_speed_max"])
    }

    @Test
    fun fanChildrenOnlyEmittedWhenFanSpeedIsExplicitlyOverridden() {
        val untouched = CuraSettingDelta.explicitValues(SlicerSettings(fanSpeedPercent = 40.0))
        assertNull(untouched["cool_fan_speed"])
        assertNull(untouched["cool_fan_speed_min"])
        assertNull(untouched["cool_fan_speed_max"])

        val overridden = CuraSettingDelta.explicitValues(
            SlicerSettings(
                fanSpeedPercent = 40.0,
                overriddenSettingKeys = setOf(SlicerSettings.Keys.FAN_SPEED),
            ),
        )
        assertEquals("40.0", overridden["cool_fan_speed"])
        assertEquals("40.0", overridden["cool_fan_speed_min"])
        assertEquals("40.0", overridden["cool_fan_speed_max"])
    }

    @Test
    fun retractionSpeedMirrorsIntoRetractAndPrimeChildren() {
        val values = CuraSettingDelta.standaloneValues(
            SlicerSettings(retractionSpeedMmPerSecond = 120.0),
        )

        assertEquals("120.0", values["retraction_retract_speed"])
        assertEquals("120.0", values["retraction_prime_speed"])
    }

    @Test
    fun materialFlowMirrorsIntoEveryEngineReadFlowChild() {
        val values = CuraSettingDelta.standaloneValues(
            SlicerSettings(materialFlowPercent = 105.0),
        )

        listOf(
            "wall_0_material_flow",
            "wall_x_material_flow",
            "wall_0_material_flow_roofing",
            "wall_0_material_flow_flooring",
            "wall_x_material_flow_roofing",
            "wall_x_material_flow_flooring",
            "skin_material_flow",
            "roofing_material_flow",
            "flooring_material_flow",
            "infill_material_flow",
            "support_material_flow",
            "support_interface_material_flow",
            "support_roof_material_flow",
            "support_bottom_material_flow",
            "skirt_brim_material_flow",
        ).forEach { key -> assertEquals("Expected $key to mirror material flow", "105.0", values[key]) }
    }

    @Test
    fun supportInterfaceMirrorsIntoRoofBottomAndDistanceChildren() {
        val values = CuraSettingDelta.standaloneValues(
            SlicerSettings(
                supportsEnabled = true,
                supportPlacement = "everywhere",
                supportInterfaceEnabled = true,
                supportInterfaceDensityPercent = 33.333,
                supportInterfaceHeightMm = 0.8,
                supportZDistanceMm = 0.2,
            ),
        )

        assertEquals("true", values["support_roof_enable"])
        assertEquals("true", values["support_bottom_enable"])
        assertEquals("33.333", values["support_roof_density"])
        assertEquals("33.333", values["support_bottom_density"])
        assertEquals("0.8", values["support_roof_height"])
        assertEquals("0.8", values["support_bottom_height"])
        assertEquals("0.2", values["support_top_distance"])
        assertEquals("0.2", values["support_bottom_distance"])
    }

    @Test
    fun supportBottomDistanceIsZeroForTouchingBuildplate() {
        val values = CuraSettingDelta.standaloneValues(
            SlicerSettings(
                supportsEnabled = true,
                supportPlacement = "touching_buildplate",
                supportZDistanceMm = 0.6,
            ),
        )

        assertEquals("0.0", values["support_bottom_distance"])
    }

    @Test
    fun supportSpeedMirrorsIntoEngineReadInfillRoofBottomChildren() {
        val values = CuraSettingDelta.standaloneValues(
            SlicerSettings(
                supportSpeedMmPerSecond = 42.0,
                supportInterfaceSpeedMmPerSecond = 21.0,
            ),
        )

        assertEquals("42.0", values["speed_support_infill"])
        assertEquals("21.0", values["speed_support_roof"])
        assertEquals("21.0", values["speed_support_bottom"])
    }

    @Test
    fun supportLineDistanceMirrorsDefinitionsForNormalAndTreeSupport() {
        val normal = CuraSettingDelta.standaloneValues(
            SlicerSettings(
                supportsEnabled = true,
                supportStructure = "normal",
                supportDensityPercent = 20.0,
                supportPattern = "grid",
                lineWidthMm = 0.4,
            ),
        )
        // 0.4 * 100 / 20 * 2 (grid factor)
        assertEquals("4.0", normal["support_line_distance"])

        val tree = CuraSettingDelta.standaloneValues(
            SlicerSettings(
                supportsEnabled = true,
                supportStructure = "tree",
                supportDensityPercent = 20.0,
                supportPattern = "grid",
                lineWidthMm = 0.4,
            ),
        )
        assertEquals("0.0", tree["support_line_distance"])
    }

    @Test
    fun lineWidthMirrorsIntoEngineReadChildren() {
        val values = CuraSettingDelta.standaloneValues(
            SlicerSettings(lineWidthMm = 0.5),
        )

        assertEquals("0.5", values["wall_line_width_0"])
        assertEquals("0.5", values["wall_line_width_x"])
        assertEquals("0.5", values["skin_line_width"])
        assertEquals("0.5", values["infill_line_width"])
        assertEquals("0.5", values["support_line_width"])
        assertEquals("0.5", values["skirt_brim_line_width"])
        assertEquals("0.5", values["wall_transition_length"])
        assertEquals("2.0", values["brim_inside_margin"])
        assertEquals("0.5", values["raft_surface_line_width"])
        assertEquals("1.0", values["raft_interface_line_width"])
    }

    @Test
    fun brimWidthMirrorsIntoBrimLineCount() {
        val values = CuraSettingDelta.standaloneValues(
            SlicerSettings(brimWidthMm = 8.0, lineWidthMm = 0.4),
        )
        assertEquals("20", values["brim_line_count"])

        val wide = CuraSettingDelta.standaloneValues(
            SlicerSettings(brimWidthMm = 8.0, lineWidthMm = 1.0),
        )
        assertEquals("8", wide["brim_line_count"])
    }

    @Test
    fun raftMarginAndInfillMoveInwardsMirrorIntoChildren() {
        val values = CuraSettingDelta.standaloneValues(
            SlicerSettings(
                raftMarginMm = 12.0,
                infillMoveInwardsLengthMm = 1.5,
                topBottomPattern = "concentric",
            ),
        )

        assertEquals("12.0", values["raft_base_margin"])
        assertEquals("12.0", values["raft_interface_margin"])
        assertEquals("12.0", values["raft_surface_margin"])
        assertEquals("1.5", values["infill_start_move_inwards_length"])
        assertEquals("1.5", values["infill_end_move_inwards_length"])
        assertEquals("concentric", values["top_bottom_pattern_0"])
        assertEquals("concentric", values["roofing_pattern"])
        assertEquals("concentric", values["flooring_pattern"])
    }

    @Test
    fun speedChildrenMirrorAcrossWallsRoofingFlooringAndBrim() {
        val values = CuraSettingDelta.standaloneValues(
            SlicerSettings(
                outerWallSpeedMmPerSecond = 40.0,
                innerWallSpeedMmPerSecond = 50.0,
                topBottomSpeedMmPerSecond = 60.0,
                initialLayerSpeedMmPerSecond = 20.0,
                printSpeedMmPerSecond = 80.0,
                travelSpeedMmPerSecond = 100.0,
            ),
        )

        assertEquals("40.0", values["speed_wall_0_roofing"])
        assertEquals("40.0", values["speed_wall_0_flooring"])
        assertEquals("50.0", values["speed_wall_x_roofing"])
        assertEquals("50.0", values["speed_wall_x_flooring"])
        assertEquals("60.0", values["speed_roofing"])
        assertEquals("60.0", values["speed_flooring"])
        assertEquals("20.0", values["speed_print_layer_0"])
        assertEquals("20.0", values["skirt_brim_speed"])
        // definition: speed_travel_layer_0 = speed_layer_0 * speed_travel / speed_print
        assertEquals("25.0", values["speed_travel_layer_0"])
    }

    @Test
    fun derivedChildrenNeverLeakWithoutTheirParentOverrideInExplicitMode() {
        val values = CuraSettingDelta.explicitValues(
            SlicerSettings(
                retractionSpeedMmPerSecond = 120.0,
                materialFlowPercent = 105.0,
                supportInterfaceEnabled = true,
            ),
        )

        assertNull(values["retraction_retract_speed"])
        assertNull(values["wall_0_material_flow"])
        assertNull(values["support_roof_enable"])
        assertTrue(values.isEmpty())
    }
}
