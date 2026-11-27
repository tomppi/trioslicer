package com.rawmechatronics.easyscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionPreviewStatsTest {

    @Test
    fun tracksCapturedLaserCloudBoundsAndFraming() {
        val stats = FusionPreviewStats()
        // Empty fallback framing
        val empty = stats.computeFraming()
        assertEquals(0.35f, empty[3], 1e-4f)

        // Camera path alone frames around cameras until laser hits appear
        stats.recordCameraPosition(-0.20f, 0.0f, 0.10f, isKeyframe = true)
        stats.recordCameraPosition(0.20f, 0.0f, 0.10f, isKeyframe = true)
        assertEquals(2, stats.keyframeCount)
        val camFrame = stats.computeFraming()
        assertEquals(0.0f, camFrame[0], 1e-4f)
        assertEquals(0.10f, camFrame[2], 1e-4f)
        assertTrue("camera framing radius should cover span", camFrame[3] >= 0.22f)

        // Captured laser points take over framing around the scanned object
        val cloud = FloatArray(12 * 3)
        for (i in 0 until 12) {
            val t = i / 11f
            cloud[i * 3] = 0.10f + t * 0.20f     // [0.10..0.30]
            cloud[i * 3 + 1] = -0.05f + t * 0.10f // [-0.05..0.05]
            cloud[i * 3 + 2] = 0.40f + t * 0.10f  // [0.40..0.50]
        }
        stats.recordLaserPoints(cloud)
        assertEquals(12, stats.totalLaserPoints)
        val objFrame = stats.computeFraming()
        assertEquals(0.20f, objFrame[0], 1e-3f)
        assertEquals(0.00f, objFrame[1], 1e-3f)
        assertEquals(0.45f, objFrame[2], 1e-3f)
        assertTrue("object radius should be compact around 20cm object", objFrame[3] in 0.10f..0.25f)

        stats.reset()
        assertEquals(0, stats.totalLaserPoints)
        assertEquals(0, stats.keyframeCount)
    }
}
