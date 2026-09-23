package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ramp is shared by the model tint and the panel legend, and it is the pinned
 * upstream viewer's own curve — so its fixed points are pinned here.
 */
class DensityRampTest {

    @Test
    fun theRampMatchesTheUpstreamCurve() {
        // Upstream: t < 0.33 → [0.15, 0.3 + 1.8 t, 0.9]
        val low = DensityRamp.ramp(0.0)
        assertEquals(0.15, low[0].toDouble(), 1e-6)
        assertEquals(0.30, low[1].toDouble(), 1e-6)
        assertEquals(0.90, low[2].toDouble(), 1e-6)

        val mid = DensityRamp.ramp(0.5)
        assertEquals(0.558, mid[0].toDouble(), 1e-3)
        assertEquals(0.90, mid[1].toDouble(), 1e-6)
        assertEquals(0.492, mid[2].toDouble(), 1e-3)

        val high = DensityRamp.ramp(1.0)
        assertEquals(0.95, high[0].toDouble(), 1e-6)
        assertEquals(0.084, high[1].toDouble(), 1e-3)
        assertEquals(0.10, high[2].toDouble(), 1e-6)
    }

    @Test
    fun densityIsScaledBeforeTheRamp() {
        // Upstream tints region density / 0.8, so 40 % lands at t = 0.5.
        val fromDensity = DensityRamp.color(0.40)
        val fromRamp = DensityRamp.ramp(0.5)
        assertEquals(fromRamp[0], fromDensity[0], 1e-6f)
        assertEquals(fromRamp[1], fromDensity[1], 1e-6f)
        assertEquals(fromRamp[2], fromDensity[2], 1e-6f)
    }

    @Test
    fun theRampIsClamped() {
        val over = DensityRamp.color(5.0)
        val top = DensityRamp.ramp(1.0)
        assertEquals(top[0], over[0], 1e-6f)
        assertEquals(top[1], over[1], 1e-6f)
        assertEquals(top[2], over[2], 1e-6f)
    }
}
