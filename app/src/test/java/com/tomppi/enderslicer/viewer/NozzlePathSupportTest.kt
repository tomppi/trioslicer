package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The maths both nozzle-path previews share: an even sample of the print's shape
 * and a camera that never winds past a full turn. These used to be private copies
 * in each view, so the two previews could drift apart silently.
 */
class NozzlePathSupportTest {

    @Test
    fun theDistanceToASegmentIsZeroOnItAndItsEndpointDistanceOffIt() {
        assertEquals(0f, segmentDistanceSq(5f, 5f, 0f, 0f, 10f, 10f), 1e-4f)
        assertEquals(0f, segmentDistanceSq(0f, 0f, 0f, 0f, 10f, 10f), 1e-4f)
        // Perpendicular to the middle of the segment: 5 mm away.
        assertEquals(25f, segmentDistanceSq(5f, 5f, 0f, 0f, 10f, 0f), 1e-4f)
        // Past an endpoint the nearest point is the endpoint itself.
        assertEquals(100f, segmentDistanceSq(20f, 0f, 0f, 0f, 10f, 0f), 1e-4f)
    }

    @Test
    fun aDegenerateSegmentMeasuresToItsPoint() {
        assertEquals(9f, segmentDistanceSq(3f, 0f, 0f, 0f, 0f, 0f), 1e-4f)
    }

    @Test
    fun degreesWrapIntoHalfATurn() {
        assertEquals(0f, wrapDegrees(0f), 1e-4f)
        assertEquals(10f, wrapDegrees(370f), 1e-4f)
        assertEquals(-170f, wrapDegrees(190f), 1e-4f)
        assertEquals(170f, wrapDegrees(-190f), 1e-4f)
        assertEquals(-180f, wrapDegrees(-180f), 1e-4f)
        assertEquals(180f, wrapDegrees(180f), 1e-4f)
    }

    @Test
    fun theGridStepGrowsWithThePlate() {
        assertEquals(5f, gridStep(40f), 1e-4f)
        assertEquals(10f, gridStep(41f), 1e-4f)
        assertEquals(10f, gridStep(100f), 1e-4f)
        assertEquals(20f, gridStep(101f), 1e-4f)
        assertEquals(50f, gridStep(251f), 1e-4f)
    }
}
