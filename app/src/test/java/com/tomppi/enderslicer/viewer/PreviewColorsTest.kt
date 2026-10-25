package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewColorsTest {
    private val slow = 7.435f
    private val fast = 800f

    private fun ramp(speed: Float) = PreviewColors.speed(speed, slow, fast)

    @Test
    fun `the slowest path is blue`() {
        val rgb = ramp(slow)
        assertTrue("blue: ${rgb.toList()}", rgb[2] > 0.9f)
        assertTrue("little red: ${rgb.toList()}", rgb[0] < 0.06f)
        assertTrue("little green: ${rgb.toList()}", rgb[1] < 0.06f)
    }

    @Test
    fun `the fastest path is red`() {
        val rgb = ramp(fast)
        assertTrue("red: ${rgb.toList()}", rgb[0] > 0.9f)
        assertTrue("little green: ${rgb.toList()}", rgb[1] < 0.06f)
        assertTrue("little blue: ${rgb.toList()}", rgb[2] < 0.06f)
    }

    @Test
    fun `the middle of the ramp is green`() {
        val rgb = ramp((slow + fast) / 2f)
        assertTrue("green leads: ${rgb.toList()}", rgb[1] > rgb[0] && rgb[1] > rgb[2])
    }

    @Test
    fun `a 200 millimetre per second path is cyan, not the support interface's magenta`() {
        // The regression: the 180-240 degree section used its neighbour's formula, so this
        // exact speed painted the whole model in the support interface's colour.
        val rgb = ramp(200f)
        assertTrue("cyan: ${rgb.toList()}", rgb[1] > 0.8f && rgb[2] > 0.8f)
        assertTrue("not magenta: ${rgb.toList()}", rgb[0] < 0.1f)
    }

    @Test
    fun `no speed in the ramp is painted magenta`() {
        var speed = slow
        while (speed <= fast) {
            val rgb = ramp(speed)
            val magenta = rgb[0] > 0.6f && rgb[2] > 0.6f && rgb[1] < 0.4f
            assertTrue("magenta at $speed: ${rgb.toList()}", !magenta)
            speed += (fast - slow) / 200f
        }
    }
}
