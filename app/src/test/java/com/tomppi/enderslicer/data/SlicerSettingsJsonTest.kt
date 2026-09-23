package com.tomppi.enderslicer.data

import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SlicerSettingsJsonTest {

    @Test
    fun allKeysMatchesEverySerializedField() {
        val serialized = SlicerSettingsJson.serialize(SlicerSettings())
        val serializedKeys = buildSet {
            val iterator = serialized.keys()
            while (iterator.hasNext()) add(iterator.next())
        }
        assertEquals(SlicerSettingsJson.allKeys, serializedKeys)
    }

    @Test
    fun fullyCustomizedSettingsRoundTripThroughSerializeAndApply() {
        val original = customizedSettings()
        val serialized = SlicerSettingsJson.serialize(original)
        val restored = SlicerSettingsJson.apply(SlicerSettings(), serialized, SlicerSettingsJson.allKeys)
        assertEquals(original, restored)
    }

    @Test
    fun applyWithEmptyKeySetLeavesBaseUnchanged() {
        val original = customizedSettings()
        val serialized = SlicerSettingsJson.serialize(original)
        val restored = SlicerSettingsJson.apply(SlicerSettings(), serialized, emptySet())
        assertEquals(SlicerSettings(), restored)
    }

    @Test
    fun applyOnlyMutatesRequestedKeys() {
        val base = customizedSettings()
        val serialized = SlicerSettingsJson.serialize(SlicerSettings())
        val restored = SlicerSettingsJson.apply(base, serialized, setOf(SlicerSettings.Keys.LAYER_HEIGHT))
        assertEquals(base.copy(layerHeightMm = SlicerSettings().layerHeightMm), restored)
    }

    private fun customizedSettings() = SlicerSettings(
        printerName = "Test Printer X",
        machineWidthMm = 111.0,
        machineDepthMm = 222.0,
        machineHeightMm = 333.0,
        buildPlateShape = "elliptic",
        originAtCenter = true,
        heatedBed = false,
        heatedBuildVolume = true,
        gcodeFlavor = "RepRap",
        nozzleSizeMm = 0.6,
        filamentDiameterMm = 2.85,
        printheadXMinMm = -11.0,
        printheadYMinMm = -12.0,
        printheadXMaxMm = 13.0,
        printheadYMaxMm = 14.0,
        gantryHeightMm = 26.0,
        customStartGcodeEnabled = true,
        customStartGcode = "G28\nG1 Z5 F600\n",
        customEndGcodeEnabled = true,
        customEndGcode = "M104 S0\nM84\n",
        layerHeightMm = 0.12,
        initialLayerHeightMm = 0.32,
        adaptiveLayerHeightEnabled = true,
        adaptiveLayerHeightVariationMm = 0.15,
        adaptiveLayerHeightVariationStepMm = 0.02,
        adaptiveLayerHeightThreshold = 0.3,
        lineWidthMm = 0.5,
        slicingTolerance = "exclusive",
        wallLineCount = 3,
        wallThicknessMm = 1.2,
        topLayers = 6,
        bottomLayers = 7,
        topBottomThicknessMm = 1.0,
        initialBottomLayers = 8,
        holeHorizontalExpansionMm = 0.05,
        initialLayerHorizontalExpansionMm = -0.1,
        zSeamType = "back",
        zSeamXmm = 12.0,
        zSeamYmm = 34.0,
        zSeamRelative = true,
        zSeamCorner = "z_seam_corner_outer",
        infillDensityPercent = 40.0,
        infillPattern = "gyroid",
        zigZagConnectInfill = false,
        thicknessAdaptiveWallsEnabled = true,
        thicknessAdaptiveWallsFlowPercent = 110.0,
        thicknessAdaptiveWallsBendRadiusMm = 40.0,
        thicknessAdaptiveWallsExtraWalls = 5,
        printSpeedMmPerSecond = 60.0,
        wallSpeedMmPerSecond = 30.0,
        outerWallSpeedMmPerSecond = 25.0,
        innerWallSpeedMmPerSecond = 35.0,
        infillSpeedMmPerSecond = 80.0,
        topBottomSpeedMmPerSecond = 40.0,
        travelSpeedMmPerSecond = 120.0,
        initialLayerSpeedMmPerSecond = 20.0,
        nozzleTemperatureC = 205,
        initialNozzleTemperatureC = 220,
        bedTemperatureC = 70,
        buildVolumeTemperatureC = 32.0,
        materialStandbyTemperatureC = 175.0,
        materialDensityGPerCm3 = 1.31,
        materialAdhesionTendency = 2,
        materialSurfaceEnergyPercent = 95,
        materialBrand = "Prusament",
        materialType = "PETG",
        materialGuid = "abc-123",
        enabledExtruderCount = 2,
        materialFlowPercent = 95.0,
        fanSpeedPercent = 50.0,
        initialFanSpeedPercent = 10.0,
        fanFullAtLayer = 6,
        supportsEnabled = false,
        supportPlacement = "touching_buildplate",
        supportStructure = "normal",
        supportAngleDegrees = 50.0,
        supportDensityPercent = 25.0,
        supportPattern = "grid",
        supportInterfaceEnabled = false,
        supportInterfaceDensityPercent = 80.0,
        supportInterfaceHeightMm = 0.6,
        supportZDistanceMm = 0.3,
        supportXyDistanceMm = 0.9,
        supportSpeedMmPerSecond = 60.0,
        supportInterfaceSpeedMmPerSecond = 50.0,
        retractionDistanceMm = 6.5,
        retractionSpeedMmPerSecond = 45.0,
        retractionMinimumTravelMm = 2.5,
        retractAtLayerChange = false,
        combingMode = "off",
        avoidPrintedParts = true,
        travelAvoidDistanceMm = 1.5,
        zHopEnabled = true,
        zHopHeightMm = 0.4,
        firmwareRetraction = false,
        coastingEnabled = true,
        coastingVolumeMm3 = 0.1,
        coastingMinimumVolumeMm3 = 1.0,
        coastingSpeedPercent = 85.0,
        adhesionType = "brim",
        skirtLineCount = 3,
        brimWidthMm = 5.0,
        raftMarginMm = 12.0,
        arcOverhangEnabled = true,
        arcOverhangSpeedMmPerSecond = 4.0,
        arcOverhangFlowPercent = 100.0,
        arcOverhangLineSpacingPercent = 90.0,
        arcOverhangMinRadiusMm = 0.8,
        arcOverhangMaxRadiusMm = 40.0,
        arcOverhangMaxAreaMm2 = 1500.0,
        arcOverhangResolutionMm = 0.2,
        arcOverhangFanSpeedPercent = 90.0,
        waveOverhangEnabled = true,
        waveOverhangPattern = "triangle",
        waveOverhangLineSpacingMm = 0.4,
        waveOverhangFlowMm3PerMm = 0.2,
        waveOverhangSpeedMmPerSecond = 4.0,
        waveOverhangFanSpeedPercent = 80.0,
        waveOverhangPerimeterOverlapMm = 0.15,
        waveOverhangMinimumWidthMm = 0.9,
        waveOverhangMaxIterations = 500,
        waveOverhangReverseOddLayers = false,
        smartOverhangStrategy = true,
        ironingEnabled = true,
        ironingOnlyHighestLayer = true,
        ironingFlowPercent = 15.0,
        ironingSpeedMmPerSecond = 25.0,
    )
}
