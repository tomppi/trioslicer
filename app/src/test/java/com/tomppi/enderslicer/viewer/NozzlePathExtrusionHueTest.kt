package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class NozzlePathExtrusionHueTest {

    @Test
    fun speedRampGoesFromCyanSlowToOrangeFast() {
        assertEquals(200f, extrusionHue(0f), 1e-4f)
        assertEquals(110f, extrusionHue(0.5f), 1e-4f)
        assertEquals(20f, extrusionHue(1f), 1e-4f)
    }

    @Test
    fun ratiosAreClamped() {
        assertEquals(200f, extrusionHue(-1f), 1e-4f)
        assertEquals(20f, extrusionHue(2f), 1e-4f)
    }
}
