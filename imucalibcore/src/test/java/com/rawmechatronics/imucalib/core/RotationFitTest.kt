package com.rawmechatronics.imucalib.core

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class RotationFitTest {

    @Test
    fun recoversKnownRotation() {
        val rnd = Random(5)
        val truth = So3.exp(doubleArrayOf(0.3, -0.7, 1.1))
        val source = ArrayList<DoubleArray>()
        val target = ArrayList<DoubleArray>()
        for (i in 0 until 50) {
            val v = doubleArrayOf(rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0))
            source.add(v)
            target.add(So3.mulVec(truth, v))
        }
        val got = RotationFit.fit(source, target)
        val angle = So3.angleDeg(So3.mul(So3.transpose(got), truth))
        assertTrue("rotation fit error " + angle + " deg", angle < 0.01)
    }

    @Test
    fun recoversLargeRotation() {
        val rnd = Random(9)
        val truth = So3.exp(doubleArrayOf(2.2, 0.4, -1.3))
        val source = ArrayList<DoubleArray>()
        val target = ArrayList<DoubleArray>()
        for (i in 0 until 100) {
            val v = doubleArrayOf(rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0))
            source.add(v)
            target.add(So3.mulVec(truth, v))
        }
        val got = RotationFit.fit(source, target)
        assertTrue(So3.angleDeg(So3.mul(So3.transpose(got), truth)) < 0.01)
    }
}
