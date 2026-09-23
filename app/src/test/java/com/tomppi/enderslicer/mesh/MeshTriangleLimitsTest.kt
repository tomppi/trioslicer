package com.tomppi.enderslicer.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshTriangleLimitsTest {
    @Test
    fun limitsAreClampedToSupportedRange() {
        assertEquals(MeshTriangleLimits.MIN_TRIANGLES, MeshTriangleLimits.sanitize(1))
        assertEquals(5_000_000, MeshTriangleLimits.sanitize(5_000_000))
        assertEquals(MeshTriangleLimits.MAX_TRIANGLES, MeshTriangleLimits.sanitize(Int.MAX_VALUE))
    }

    @Test
    fun binaryAndParsedMemoryEstimatesScaleWithTriangleCount() {
        val triangles = 5_000_000
        assertEquals(84L + triangles * 50L, MeshTriangleLimits.binaryStlBytes(triangles))
        assertEquals(triangles * 18L * 4L, MeshTriangleLimits.parsedMeshBytes(triangles))
        assertTrue(MeshTriangleLimits.estimatedWorkingSetBytes(triangles) > MeshTriangleLimits.parsedMeshBytes(triangles))
    }

    @Test
    fun presetsAreUniqueAndWithinHardLimits() {
        assertEquals(MeshTriangleLimits.presets.size, MeshTriangleLimits.presets.map { it.triangles }.distinct().size)
        assertTrue(MeshTriangleLimits.presets.all { it.triangles in MeshTriangleLimits.MIN_TRIANGLES..MeshTriangleLimits.MAX_TRIANGLES })
        assertTrue(MeshTriangleLimits.presets.any { it.triangles == 5_000_000 })
    }
}
