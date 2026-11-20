
package com.rawmechatronics.imucalib.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SessionCsvTest {

    @Test
    fun sessionRoundTripsThroughCsv() {
        val scenario = Synthetic.generate(seed = 2, durationS = 3.0, noise = false)
        val frames = SessionCsv.framesCsv(scenario.boardPoses)
        val imu = SessionCsv.imuCsv(scenario.imu)
        val parsed = CalibrationSession.fromCsv(frames, imu)
        assertNotNull(parsed)
        parsed!!
        assertEquals(scenario.boardPoses.size, parsed.boardPoses.size)
        assertEquals(scenario.imu.size, parsed.imu.size)
        val errRot = So3.angleDeg(
            So3.mul(So3.transpose(parsed.boardPoses[0].rWc), scenario.boardPoses[0].rWc),
        )
        assertEquals(0.0, errRot, 1e-4)
        assertEquals(scenario.boardPoses[0].pWc[0], parsed.boardPoses[0].pWc[0], 1e-6)
        val rawOffset = minOf(scenario.boardPoses[0].t, scenario.imu[0].t, scenario.imu[0].t)
        assertEquals(rawOffset, parsed.offsetSeconds, 1e-6)
        assertEquals(scenario.boardPoses[0].t - parsed.offsetSeconds, parsed.boardPoses[0].t, 1e-6)
    }
}
