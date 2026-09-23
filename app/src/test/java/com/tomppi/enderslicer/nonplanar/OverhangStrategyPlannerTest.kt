package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.viewer.StlMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverhangStrategyPlannerTest {

    @Test
    fun solidBlockOnBedIsNotAnArcCandidate() {
        val mesh = testMesh(*flatBoxTriangles(0f, 0f, 0f, 100f, 100f, 20f).toTypedArray())
        val plan = OverhangStrategyPlanner.plan(mesh, NonPlanarSettings(), 0.2, 0.4)

        assertFalse(plan.arcUseful)
        assertFalse(plan.nonPlanarUseful)
        assertEquals(OverhangRecommendation.NONE, plan.recommendation)
        assertEquals(0.0, plan.flatRoofAreaMm2, 1e-6)
    }

    @Test
    fun hoveringSlabBottomIsARoofForArcFill() {
        val mesh = testMesh(*flatBoxTriangles(0f, 0f, 10f, 100f, 100f, 11f).toTypedArray())
        val plan = OverhangStrategyPlanner.plan(mesh, NonPlanarSettings(), 0.2, 0.4)

        assertTrue("A raised slab bottom must be classified as a flat roof", plan.arcUseful)
        assertEquals(OverhangRecommendation.ARC_ONLY, plan.recommendation)
    }

    @Test
    fun tiltedPlaneRecommendsCurviOnly() {
        val mesh = testMesh(
            floatArrayOf(0f, 0f, 0f, 100f, 0f, 10f, 100f, 100f, 10f),
            floatArrayOf(0f, 0f, 0f, 100f, 100f, 10f, 0f, 100f, 0f),
        )
        val plan = OverhangStrategyPlanner.plan(
            mesh,
            NonPlanarSettings(enabled = true, maximumLiftMm = 15.0),
            0.2,
            0.4,
        )

        assertTrue(plan.nonPlanarUseful)
        assertFalse(plan.arcUseful)
        assertEquals(OverhangRecommendation.NON_PLANAR_ONLY, plan.recommendation)
    }

    @Test
    fun flatRoofWithSlopeRecommendsArcAndCurvi() {
        val triangles = mutableListOf<FloatArray>()
        triangles += flatBoxTriangles(0f, 0f, 0f, 100f, 100f, 1f)
        triangles += flatBoxTriangles(0f, 0f, 10f, 100f, 100f, 11f)
        triangles += listOf(
            floatArrayOf(0f, 0f, 11f, 30f, 0f, 11f, 30f, 30f, 14f),
            floatArrayOf(0f, 0f, 11f, 30f, 30f, 14f, 0f, 30f, 11f),
        )
        val mesh = testMesh(*triangles.toTypedArray())
        val plan = OverhangStrategyPlanner.plan(
            mesh,
            NonPlanarSettings(enabled = true),
            0.2,
            0.4,
        )

        assertTrue("A raised slab bottom must be an arc candidate", plan.arcUseful)
        assertTrue("The ramp must be a non-planar candidate", plan.nonPlanarUseful)
        assertTrue(plan.combinedSafe)
        assertEquals(OverhangRecommendation.ARC_AND_NON_PLANAR, plan.recommendation)
    }

    @Test
    fun roofUnderTallSurfaceStillCombinesSafely() {
        // The conformal shells only replace material near the surface, so the
        // arc paths on the roof layers below stay exactly as sliced: the
        // combination is always safe even with a tall curved feature above.
        val triangles = mutableListOf<FloatArray>()
        triangles += flatBoxTriangles(0f, 0f, 0f, 100f, 100f, 1f)
        triangles += listOf(
            floatArrayOf(20f, 20f, 5f, 80f, 80f, 5f, 80f, 20f, 5f),
            floatArrayOf(20f, 20f, 5f, 20f, 80f, 5f, 80f, 80f, 5f),
        )
        triangles += domeTriangles(20f, 20f, 5f, 80f, 80f, 12f)
        val mesh = testMesh(*triangles.toTypedArray())
        val plan = OverhangStrategyPlanner.plan(
            mesh,
            NonPlanarSettings(enabled = true),
            0.2,
            0.4,
        )

        assertTrue(plan.arcUseful)
        assertTrue(plan.nonPlanarUseful)
        assertTrue("A roof under a curved surface must still combine safely", plan.combinedSafe)
        assertEquals(1.0, plan.lowReliefRoofFraction, 1e-9)
        assertEquals(OverhangRecommendation.ARC_AND_NON_PLANAR, plan.recommendation)
    }

    @Test
    fun decideMapsAllEvidenceCombinations() {
        assertEquals(
            OverhangRecommendation.NONE,
            OverhangStrategyPlanner.decide(false, false, false),
        )
        assertEquals(
            OverhangRecommendation.ARC_ONLY,
            OverhangStrategyPlanner.decide(true, false, false),
        )
        assertEquals(
            OverhangRecommendation.NON_PLANAR_ONLY,
            OverhangStrategyPlanner.decide(false, true, false),
        )
        assertEquals(
            OverhangRecommendation.ARC_AND_NON_PLANAR,
            OverhangStrategyPlanner.decide(true, true, true),
        )
    }
}
