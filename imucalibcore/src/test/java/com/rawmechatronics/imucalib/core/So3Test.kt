package com.rawmechatronics.imucalib.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class So3Test {

    @Test
    fun expLogRoundtripSmallAndLargeAngles() {
        val rnd = Random(7)
        for (i in 0 until 200) {
            val w = doubleArrayOf(
                rnd.nextDouble(-1.5, 1.5),
                rnd.nextDouble(-1.5, 1.5),
                rnd.nextDouble(-1.5, 1.5),
            )
            val r = So3.exp(w)
            assertTrue("exp is a rotation", So3.isRotation(r))
            val w2 = So3.log(r)
            for (k in 0 until 3) assertEquals(w[k], w2[k], 1e-8)
        }
    }

    @Test
    fun expZeroIsIdentity() {
        val r = So3.exp(doubleArrayOf(0.0, 0.0, 0.0))
        for (i in 0 until 9) assertEquals(if (i % 4 == 0) 1.0 else 0.0, r[i], 1e-15)
    }

    @Test
    fun rotationOfPiIsHandled() {
        for (axis in listOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
            doubleArrayOf(1.0, 1.0, 0.0),
        )) {
            val n = kotlin.math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
            val w = doubleArrayOf(axis[0] / n * Math.PI, axis[1] / n * Math.PI, axis[2] / n * Math.PI)
            val r = So3.exp(w)
            val w2 = So3.log(r)
            assertTrue(abs(So3.angleDeg(So3.mul(So3.exp(w2), So3.transpose(r)))) < 1e-6)
        }
    }

    @Test
    fun skewMatchesCrossProduct() {
        val a = doubleArrayOf(1.0, -2.0, 3.0)
        val b = doubleArrayOf(0.5, 4.0, -1.0)
        val axb = doubleArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        )
        val got = So3.mulVec(So3.skew(a), b)
        for (k in 0 until 3) assertEquals(axb[k], got[k], 1e-12)
    }
}
