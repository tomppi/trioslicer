package com.tomppi.enderslicer.smartinfill

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The overlay decides what the model tints: supports, loads, the armed pick. */
class SmartInfillOverlayTest {

    private fun condition(id: Long, value: FilaSimBoundaryCondition) =
        SmartInfillCondition(id = id, condition = value)

    @Test
    fun supportsAndLoadsAreSeparatedByKind() {
        val overlay = SmartInfillOverlay.of(
            listOf(
                condition(1, FilaSimBoundaryCondition.Fixed(intArrayOf(0, 1))),
                condition(2, FilaSimBoundaryCondition.Pressure(intArrayOf(2), 1.0)),
                condition(3, FilaSimBoundaryCondition.Elastic(intArrayOf(3), 50.0)),
                condition(4, FilaSimBoundaryCondition.Moment(intArrayOf(4), listOf(0.0, 0.0, 10.0))),
                condition(
                    5,
                    FilaSimBoundaryCondition.Displacement(intArrayOf(5), listOf(true, false, false)),
                ),
            ),
            pickingConditionId = null,
        )!!
        assertArrayEquals(intArrayOf(0, 1, 3, 5), overlay.supports)
        assertArrayEquals(intArrayOf(2, 4), overlay.loads)
        assertTrue(overlay.active.isEmpty())
        assertFalse(overlay.isEmpty)
    }

    @Test
    fun theArmedConditionIsMarkedActive() {
        val overlay = SmartInfillOverlay.of(
            listOf(
                condition(
                    7,
                    FilaSimBoundaryCondition.Force(intArrayOf(3, 4), listOf(0.0, 0.0, -100.0)),
                ),
            ),
            pickingConditionId = 7,
        )!!
        assertArrayEquals(intArrayOf(3, 4), overlay.loads)
        assertArrayEquals(intArrayOf(3, 4), overlay.active)
    }

    @Test
    fun nothingToDrawMeansNoOverlay() {
        assertNull(SmartInfillOverlay.of(emptyList(), null))
        assertNull(
            "a condition with no surface yet has nothing to tint",
            SmartInfillOverlay.of(
                listOf(condition(1, FilaSimBoundaryCondition.Fixed(IntArray(0)))),
                null,
            ),
        )
    }

    @Test
    fun theResultTintTravelsWithTheConditions() {
        val overlay = SmartInfillOverlay.of(
            conditions = listOf(condition(1, FilaSimBoundaryCondition.Fixed(intArrayOf(0)))),
            pickingConditionId = null,
            surfaceBins = intArrayOf(0, 1, -1, 1),
            binDensities = doubleArrayOf(0.10, 0.42),
        )!!
        assertArrayEquals(intArrayOf(0, 1, -1, 1), overlay.regions)
        assertArrayEquals(doubleArrayOf(0.10, 0.42), overlay.binDensities, 1e-9)
        assertArrayEquals(intArrayOf(0), overlay.supports)
    }

    @Test
    fun aTintWithNothingTintedIsNotAnOverlay() {
        assertNull(
            "no bin anywhere means nothing to draw",
            SmartInfillOverlay.of(
                conditions = emptyList(),
                pickingConditionId = null,
                surfaceBins = intArrayOf(-1, -1),
                binDensities = doubleArrayOf(0.10),
            ),
        )
        assertNull(
            "bins without densities cannot be coloured",
            SmartInfillOverlay.of(
                conditions = emptyList(),
                pickingConditionId = null,
                surfaceBins = intArrayOf(0, 1),
                binDensities = DoubleArray(0),
            ),
        )
        assertNotNull(
            "but a tint alone is an overlay",
            SmartInfillOverlay.of(emptyList(), null, intArrayOf(0, 1), doubleArrayOf(0.3)),
        )
    }

    @Test
    fun equalityIsByContentSoTheViewerSkipsRedraws() {
        val first = SmartInfillOverlay(intArrayOf(1), intArrayOf(2), intArrayOf(2))
        val same = SmartInfillOverlay(intArrayOf(1), intArrayOf(2), intArrayOf(2))
        val other = SmartInfillOverlay(intArrayOf(1), intArrayOf(2), intArrayOf(3))
        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertFalse(first == other)
    }

    @Test
    fun theOptimizedVolumesTravelWithTheOverlay() {
        val volume = FilaSimRegion(
            densityPercent = 26.0,
            positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
            indices = intArrayOf(0, 1, 2),
        )

        val overlay = SmartInfillOverlay.of(
            conditions = emptyList(),
            pickingConditionId = null,
            volumes = listOf(volume),
        )

        assertNotNull("a finished run has shells to draw even with no picks", overlay)
        assertEquals(1, overlay!!.volumes.size)
        assertTrue("the engine mesh itself, not a copy", overlay.volumes.single() === volume)
    }

    @Test
    fun anOverlayWithNothingAtAllToDrawStaysNull() {
        assertNull(SmartInfillOverlay.of(emptyList(), null, IntArray(0), DoubleArray(0), emptyList()))
    }
}