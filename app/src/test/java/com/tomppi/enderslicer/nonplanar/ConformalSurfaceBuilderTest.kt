package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConformalSurfaceBuilderTest {
    private fun settings(
        maximumLiftMm: Double = 15.0,
        maximumSlopeDegrees: Double = 30.0,
        nozzleClearanceAngleDegrees: Double = 45.0,
    ) = NonPlanarSettings(
        enabled = true,
        maximumLiftMm = maximumLiftMm,
        maximumSlopeDegrees = maximumSlopeDegrees,
        nozzleClearanceAngleDegrees = nozzleClearanceAngleDegrees,
    )

    private fun domeMesh(peakZ: Float = 12f, size: Float = 80f, zBase: Float = 0f): StlMesh {
        val triangles = domeTriangles(0f, 0f, zBase, size, size, peakZ).toTypedArray()
        return testMesh(*triangles, name = "dome.stl")
    }

    @Test
    fun gentleDomeBecomesOneConnectedRegion() {
        val surface = ConformalSurfaceBuilder.build(domeMesh(), settings())
        assertEquals(1, surface.regions.size)
        val region = surface.regions.single()
        assertEquals(0.0, region.minZ, 1e-6)
        assertEquals(12.0, region.maxZ, 1e-6)
        assertEquals(0.0, region.minX, 1e-6)
        assertEquals(80.0, region.maxX, 1e-6)
        assertTrue(region.areaMm2 > 20.0)
        // The apex ray-casts to the peak height and a base corner to the base.
        assertEquals(12.0, region.surfaceZ(40.0, 40.0)!!, 1e-3)
        assertEquals(0.0, region.surfaceZ(0.0, 0.0)!!, 1e-3)
        assertTrue(region.contains(40.0, 40.0))
        assertFalse(region.contains(120.0, 120.0))
        assertNull(region.surfaceZ(120.0, 120.0))
    }

    @Test
    fun steepFacetsAreExcludedByTheSlopeLimit() {
        // A 45-degree plane is steeper than the 30-degree printable angle.
        val plane = testMesh(floatArrayOf(0f, 0f, 0f, 100f, 0f, 100f, 100f, 100f, 100f), floatArrayOf(0f, 0f, 0f, 100f, 100f, 100f, 0f, 100f, 0f))
        val surface = ConformalSurfaceBuilder.build(plane, settings())
        assertTrue(surface.regions.isEmpty())
        assertTrue(surface.diagnostics.candidateTriangles == 0)
    }

    @Test
    fun tallSurfacesAreFilteredByMaximumLift() {
        // Span 12 mm exceeds the 5 mm lift budget.
        val surface = ConformalSurfaceBuilder.build(domeMesh(), settings(maximumLiftMm = 5.0))
        assertTrue(surface.regions.isEmpty())
        assertEquals(1, surface.diagnostics.regionsFilteredBySpan)
    }

    @Test
    fun tinySurfacesAreFilteredByArea() {
        // A 4x4 mm pyramid has far less than the 20 mm^2 minimum area.
        val tiny = testMesh(*domeTriangles(0f, 0f, 0f, 4f, 4f, 0.5f).toTypedArray(), name = "tiny.stl")
        val surface = ConformalSurfaceBuilder.build(tiny, settings())
        assertTrue(surface.regions.isEmpty())
        assertEquals(1, surface.diagnostics.regionsFilteredByArea)
    }

    @Test
    fun rayCastInterpolatesTheTrianglePlane() {
        // z = 1 + 0.2 * y over the triangle (0,0,1)-(10,0,1)-(0,10,3).
        val mesh = testMesh(floatArrayOf(0f, 0f, 1f, 10f, 0f, 1f, 0f, 10f, 3f))
        val surface = ConformalSurfaceBuilder.build(mesh, settings())
        assertEquals(1, surface.regions.size)
        val region = surface.regions.single()
        assertEquals(1.4, region.surfaceZ(2.0, 2.0)!!, 1e-4)
        assertEquals(3.0, region.surfaceZ(0.0, 10.0)!!, 1e-4)
        assertNull(region.surfaceZ(9.0, 9.0))
    }

    @Test
    fun vertexIdsStayDistinctAtNegativeCoordinates() {
        // The weld key packs quantised x, y and z into one long. Before the
        // fields were masked, a negative y (or z) sign-extended over the
        // fields above it, so these points - which differ only in X at
        // negative Y, or only in Y at negative Z - shared one id.
        val ids = setOf(
            ConformalSurfaceBuilder.packVertex(0.0, -30.0, 5.0),
            ConformalSurfaceBuilder.packVertex(50.0, -30.0, 5.0),
            ConformalSurfaceBuilder.packVertex(-50.0, -30.0, 5.0),
            ConformalSurfaceBuilder.packVertex(0.0, 30.0, -5.0),
            ConformalSurfaceBuilder.packVertex(0.0, -30.0, -5.0),
        )
        assertEquals(5, ids.size)
    }

    @Test
    fun disconnectedTopsAtNegativeYAreNotWeldedIntoOneRegion() {
        // Two 50 mm^2 triangles whose (y, z) pairs match but whose X differs.
        // Shared ids are the builder's only connectivity input, so the old
        // packing welded their matching edge and reported one region where the
        // mesh has two - the normal case for a mesh placed on a centred bed.
        val mesh = testMesh(
            floatArrayOf(0f, -30f, 5f, 10f, -30f, 5f, 0f, -20f, 5f),
            floatArrayOf(50f, -30f, 5f, 60f, -30f, 5f, 50f, -20f, 5f),
        )
        val surface = ConformalSurfaceBuilder.build(mesh, settings())
        assertEquals(2, surface.regions.size)
    }

    @Test
    fun disconnectedFlatTopsBecomeSeparateRegions() {
        val two = testMesh(
            floatArrayOf(0f, 0f, 5f, 10f, 0f, 5f, 0f, 10f, 5f),
            floatArrayOf(20f, 20f, 7f, 30f, 20f, 7f, 20f, 30f, 7f),
        )
        val surface = ConformalSurfaceBuilder.build(two, settings())
        assertEquals(2, surface.regions.size)
        val zValues = surface.regions.map { it.surfaceZ(5.0, 5.0) ?: it.surfaceZ(25.0, 25.0)!! }.toSet()
        assertEquals(setOf(5.0, 7.0), zValues)
    }
}
