package com.tomppi.enderslicer.smartinfill

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartInfillCuraContractTest {
    @Test
    fun binaryUsesSparseBaseAndExplicitSolidPattern() {
        val packageValue = packageValue(
            mode = "binary",
            basePattern = "cubic",
            densities = listOf(100),
            binarySolidPattern = "concentric",
        )

        assertEquals("cubic", SmartInfillCuraContract.basePattern(packageValue))
        assertEquals("concentric", SmartInfillCuraContract.modifierPattern(packageValue, 100))
    }

    @Test
    fun gradedUsesSparsePatternExceptRectilinearAtOneHundredPercent() {
        val packageValue = packageValue(
            mode = "graded",
            basePattern = "gyroid",
            densities = listOf(35, 70, 100),
        )

        assertEquals("gyroid", SmartInfillCuraContract.basePattern(packageValue))
        assertEquals("gyroid", SmartInfillCuraContract.modifierPattern(packageValue, 35))
        assertEquals("gyroid", SmartInfillCuraContract.modifierPattern(packageValue, 70))
        assertEquals("zigzag", SmartInfillCuraContract.modifierPattern(packageValue, 100))
    }

    @Test
    fun rectilinearAliasesMapToCuraZigzag() {
        assertEquals("zigzag", SmartInfillCuraContract.curaPattern("rectilinear"))
        assertEquals("zigzag", SmartInfillCuraContract.curaPattern("zig-zag"))
        assertEquals("zigzag", SmartInfillCuraContract.curaPattern("zigzag"))
    }

    private fun packageValue(
        mode: String,
        basePattern: String,
        densities: List<Int>,
        binarySolidPattern: String? = null,
    ): SmartInfillPackage = SmartInfillPackage(
        id = "pattern-fixture",
        directory = File("."),
        sourceName = "fixture.stl",
        sourceSha256 = "0".repeat(64),
        baseDensityPercent = 10.0,
        pattern = basePattern,
        mode = mode,
        perimeters = 2,
        lineWidthMm = 0.4,
        topBottomLayers = 4,
        layerHeightMm = 0.2,
        upstreamCommit = SmartInfillActivity.FILASIM_COMMIT,
        modifiers = densities.map { density ->
            SmartInfillModifier(density, File("modifier-${density}pct.stl"))
        },
        binarySolidPattern = binarySolidPattern,
    )
}
