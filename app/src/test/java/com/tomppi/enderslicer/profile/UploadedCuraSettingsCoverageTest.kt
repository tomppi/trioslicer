package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Regression coverage is based on the uploaded Ender-3 v2 test.3mf profile.
class UploadedCuraSettingsCoverageTest {
    @Test
    fun uploadedProfileNoLongerReportsItsSettingsAsHidden() {
        val values = mapOf(
            "support_interface_height" to "=layer_height*4",
            "wall_thickness" to "=line_width*2",
            "top_bottom_thickness" to "=layer_height_0+layer_height*3",
            "hole_xy_offset" to "0",
            "xy_offset_layer_0" to "0",
            "zig_zaggify_infill" to "True",
            "build_volume_temperature" to "28",
            "extruders_enabled_count" to "1",
            "ironing_only_highest_layer" to "False",
            "material_adhesion_tendency" to "0",
            "material_brand" to "Custom",
            "material_density" to "1.24",
            "material_guid" to "7a406498-24a3-4db9-ac90-30dbe42bd3b8",
            "material_standby_temperature" to "180",
            "material_surface_energy" to "100",
            "material_type" to "PLA",
            "raft_margin" to "10.0",
            "initial_bottom_layers" to "=bottom_layers",
        )

        assertTrue(CuraProjectAudit.warnings(values).isEmpty())
    }

    @Test
    fun everyNewEditableSettingProducesTheExpectedCuraDelta() {
        val settings = SlicerSettings(
            wallThicknessMm = 1.2,
            topBottomThicknessMm = 1.1,
            initialBottomLayers = 4,
            holeHorizontalExpansionMm = -0.1,
            initialLayerHorizontalExpansionMm = 0.2,
            zigZagConnectInfill = false,
            buildVolumeTemperatureC = 32.0,
            materialStandbyTemperatureC = 170.0,
            materialDensityGPerCm3 = 1.3,
            materialAdhesionTendency = 3,
            materialSurfaceEnergyPercent = 85,
            supportInterfaceHeightMm = 0.6,
            raftMarginMm = 12.0,
            ironingOnlyHighestLayer = true,
            overriddenSettingKeys = setOf(
                SlicerSettings.Keys.WALL_THICKNESS,
                SlicerSettings.Keys.TOP_BOTTOM_THICKNESS,
                SlicerSettings.Keys.INITIAL_BOTTOM_LAYERS,
                SlicerSettings.Keys.HOLE_HORIZONTAL_EXPANSION,
                SlicerSettings.Keys.INITIAL_LAYER_HORIZONTAL_EXPANSION,
                SlicerSettings.Keys.ZIG_ZAG_CONNECT_INFILL,
                SlicerSettings.Keys.BUILD_VOLUME_TEMPERATURE,
                SlicerSettings.Keys.MATERIAL_STANDBY_TEMPERATURE,
                SlicerSettings.Keys.MATERIAL_DENSITY,
                SlicerSettings.Keys.MATERIAL_ADHESION_TENDENCY,
                SlicerSettings.Keys.MATERIAL_SURFACE_ENERGY,
                SlicerSettings.Keys.SUPPORT_INTERFACE_HEIGHT,
                SlicerSettings.Keys.RAFT_MARGIN,
                SlicerSettings.Keys.IRONING_ONLY_HIGHEST_LAYER,
            ),
        )

        val values = CuraSettingDelta.explicitValues(settings)
        assertEquals("1.2", values["wall_thickness"])
        assertEquals("1.1", values["top_bottom_thickness"])
        assertEquals("4", values["initial_bottom_layers"])
        assertEquals("-0.1", values["hole_xy_offset"])
        assertEquals("0.2", values["xy_offset_layer_0"])
        assertEquals("false", values["zig_zaggify_infill"])
        assertEquals("32.0", values["build_volume_temperature"])
        assertEquals("170.0", values["material_standby_temperature"])
        assertEquals("1.3", values["material_density"])
        assertEquals("3", values["material_adhesion_tendency"])
        assertEquals("85", values["material_surface_energy"])
        assertEquals("0.6", values["support_interface_height"])
        assertEquals("12.0", values["raft_margin"])
        assertEquals("true", values["ironing_only_highest_layer"])
    }
}
