package com.rawmechatronics.imucalib.core

/** Rotation fit by Horn's quaternion method: find R with R * source ≈ target. */
object RotationFit {

    fun fit(source: List<DoubleArray>, target: List<DoubleArray>): DoubleArray {
        require(source.size == target.size && source.size >= 3)
        val m = DoubleArray(9)
        for (i in source.indices) {
            val p = source[i]
            val q = target[i]
            for (r in 0 until 3) for (c in 0 until 3) m[r * 3 + c] += p[r] * q[c]
        }
        val n = DoubleArray(16)
        fun set(i: Int, j: Int, v: Double) { n[i * 4 + j] = v; n[j * 4 + i] = v }
        set(0, 0, m[0] + m[4] + m[8])
        set(0, 1, m[5] - m[7])
        set(0, 2, m[6] - m[2])
        set(0, 3, m[1] - m[3])
        set(1, 1, m[0] - m[4] - m[8])
        set(1, 2, m[1] + m[3])
        set(1, 3, m[6] + m[2])
        set(2, 2, -m[0] + m[4] - m[8])
        set(2, 3, m[5] + m[7])
        set(3, 3, -m[0] - m[4] + m[8])
        val eig = symEigen4(n)
        var best = 0
        for (i in 1 until 4) if (eig.values[i] > eig.values[best]) best = i
        // symEigen4 returns its eigenvectors as columns: component k of vector v is vectors[k*4+v]
        val q0 = eig.vectors[0 * 4 + best]
        val q1 = eig.vectors[1 * 4 + best]
        val q2 = eig.vectors[2 * 4 + best]
        val q3 = eig.vectors[3 * 4 + best]
        val nq = kotlin.math.sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        val w = q0 / nq; val x = q1 / nq; val y = q2 / nq; val z = q3 / nq
        return doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
        )
    }

    private data class Eigen(val values: DoubleArray, val vectors: DoubleArray)

    /** Jacobi eigen decomposition for a symmetric 4x4 matrix (row-major input). */
    private fun symEigen4(aIn: DoubleArray): Eigen {
        val a = aIn.copyOf()
        val v = doubleArrayOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        for (sweep in 0 until 60) {
            var off = 0.0
            for (p in 0 until 3) for (q in p + 1 until 4) off += a[p * 4 + q] * a[p * 4 + q]
            if (off < 1e-24) break
            for (p in 0 until 3) for (q in p + 1 until 4) {
                val apq = a[p * 4 + q]
                if (kotlin.math.abs(apq) < 1e-18) continue
                val app = a[p * 4 + p]
                val aqq = a[q * 4 + q]
                val theta = 0.5 * (aqq - app) / apq
                val t = if (theta >= 0) 1.0 / (theta + kotlin.math.sqrt(1 + theta * theta))
                        else 1.0 / (theta - kotlin.math.sqrt(1 + theta * theta))
                val c = 1.0 / kotlin.math.sqrt(1 + t * t)
                val s = c * t
                for (k in 0 until 4) {
                    val akp = a[k * 4 + p]; val akq = a[k * 4 + q]
                    a[k * 4 + p] = c * akp - s * akq
                    a[k * 4 + q] = s * akp + c * akq
                }
                for (k in 0 until 4) {
                    val apk = a[p * 4 + k]; val aqk = a[q * 4 + k]
                    a[p * 4 + k] = c * apk - s * aqk
                    a[q * 4 + k] = s * apk + c * aqk
                }
                for (k in 0 until 4) {
                    val vkp = v[k * 4 + p]; val vkq = v[k * 4 + q]
                    v[k * 4 + p] = c * vkp - s * vkq
                    v[k * 4 + q] = s * vkp + c * vkq
                }
            }
        }
        return Eigen(doubleArrayOf(a[0], a[5], a[10], a[15]), v)
    }
}