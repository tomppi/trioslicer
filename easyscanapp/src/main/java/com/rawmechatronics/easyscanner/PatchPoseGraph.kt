package com.rawmechatronics.easyscanner

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small SE(3) pose-graph solver for the live patch chain.
 *
 * Nodes are world->camera poses. Edges are camera-a -> camera-b relative
 * transforms. The first node is held by a strong prior; the rest are solved
 * with damped Gauss-Newton over a 6-DoF left perturbation.
 */
object PatchPoseGraph {
    data class Edge(val a: Int, val b: Int, val rel: RigidPose, val weight: Double)

    fun optimize(poses: List<RigidPose>, edges: List<Edge>, iterations: Int = 6): List<RigidPose> {
        if (poses.size < 2 || edges.isEmpty()) return poses
        val n = poses.size
        val x = DoubleArray(n * 6)
        var lambda = 1e-3
        var cost = Double.POSITIVE_INFINITY
        repeat(iterations) {
            val r = residuals(x, poses, edges)
            if (r.isEmpty()) return poses
            val m = n * 6
            val jac = Array(r.size) { DoubleArray(m) }
            for (k in 0 until m) {
                val h = if (k % 6 < 3) 1e-7 else 1e-3
                x[k] += h
                val r2 = residuals(x, poses, edges)
                x[k] -= h
                for (row in r.indices) jac[row][k] = (r2[row] - r[row]) / h
            }
            val jtj = Array(m) { DoubleArray(m) }
            val jtr = DoubleArray(m)
            for (row in r.indices) {
                val jr = jac[row]
                for (p in 0 until m) {
                    val jp = jr[p]
                    if (jp == 0.0) continue
                    jtr[p] += jp * r[row]
                    for (q in 0..p) jtj[p][q] += jp * jr[q]
                }
            }
            for (p in 0 until m) for (q in 0 until p) jtj[q][p] = jtj[p][q]
            for (p in 0 until m) jtj[p][p] = jtj[p][p] * (1.0 + lambda) + 1e-9
            val dx = solve(jtj, DoubleArray(m) { -jtr[it] }) ?: return poses
            for (k in 0 until m) x[k] += dx[k]
            val c2 = norm(residuals(x, poses, edges))
            if (c2 > cost && cost.isFinite()) {
                for (k in 0 until m) x[k] -= dx[k]
                lambda *= 7.0
            } else {
                cost = c2
                lambda = (lambda * 0.5).coerceAtLeast(1e-5)
            }
        }
        return Array(n) { i ->
            val d = doubleArrayOf(x[i*6], x[i*6+1], x[i*6+2], x[i*6+3], x[i*6+4], x[i*6+5])
            applyDelta(poses[i], d)
        }.toList()
    }

    private fun residuals(x: DoubleArray, poses: List<RigidPose>, edges: List<Edge>): DoubleArray {
        val n = poses.size
        val out = ArrayList<Double>()
        // Gauge prior on the first node.
        for (k in 0 until 6) out.add(1e6 * x[k])
        for (e in edges) {
            val pa = applyDelta(poses[e.a], dOf(x, e.a))
            val pb = applyDelta(poses[e.b], dOf(x, e.b))
            val predicted = LaserIcp.compose(pb, LaserIcp.invert(pa))
            val err = LaserIcp.compose(LaserIcp.invert(e.rel), predicted)
            val rv = logSO3(err.r)
            for (k in 0 until 3) out.add(e.weight * rv[k])
            for (k in 0 until 3) out.add(e.weight * err.t[k])
        }
        return out.toDoubleArray()
    }

    private fun dOf(x: DoubleArray, i: Int) = doubleArrayOf(x[i*6], x[i*6+1], x[i*6+2], x[i*6+3], x[i*6+4], x[i*6+5])

    private fun applyDelta(p: RigidPose, d: DoubleArray): RigidPose {
        val rd = expSO3(d[0], d[1], d[2])
        val r = mul3(rd, p.r)
        val t = doubleArrayOf(
            rd[0]*p.t[0] + rd[1]*p.t[1] + rd[2]*p.t[2] + d[3],
            rd[3]*p.t[0] + rd[4]*p.t[1] + rd[5]*p.t[2] + d[4],
            rd[6]*p.t[0] + rd[7]*p.t[1] + rd[8]*p.t[2] + d[5],
        )
        return RigidPose(r, t)
    }

    private fun expSO3(x: Double, y: Double, z: Double): DoubleArray {
        val th = sqrt(x*x + y*y + z*z)
        if (th < 1e-12) return doubleArrayOf(1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0)
        val nx = x/th; val ny = y/th; val nz = z/th
        val c = cos(th); val s = sin(th); val t = 1.0 - c
        return doubleArrayOf(
            c + nx*nx*t, nx*ny*t - nz*s, nx*nz*t + ny*s,
            ny*nx*t + nz*s, c + ny*ny*t, ny*nz*t - nx*s,
            nz*nx*t - ny*s, nz*ny*t + nx*s, c + nz*nz*t,
        )
    }

    private fun logSO3(m: DoubleArray): DoubleArray {
        val th = acos(((m[0] + m[4] + m[8] - 1.0) * 0.5).coerceIn(-1.0, 1.0))
        if (th < 1e-9) return doubleArrayOf(0.0, 0.0, 0.0)
        val s = sin(th)
        return doubleArrayOf((m[7]-m[5])/(2*s)*th, (m[2]-m[6])/(2*s)*th, (m[3]-m[1])/(2*s)*th)
    }

    private fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            var v = 0.0
            for (k in 0 until 3) v += a[r*3+k] * b[k*3+c]
            o[r*3+c] = v
        }
        return o
    }

    private fun norm(a: DoubleArray): Double { var s = 0.0; for (v in a) s += v*v; return sqrt(s) }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { a[it].copyOf() }
        val rhs = b.copyOf()
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r
            if (Math.abs(m[piv][col]) < 1e-14) return null
            if (piv != col) { val t = m[piv]; m[piv] = m[col]; m[col] = t; val s = rhs[piv]; rhs[piv] = rhs[col]; rhs[col] = s }
            val d = m[col][col]
            for (r in col + 1 until n) {
                val f = m[r][col] / d
                if (f == 0.0) continue
                for (c in col until n) m[r][c] -= f * m[col][c]
                rhs[r] -= f * rhs[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = rhs[r]
            for (c in r + 1 until n) s -= m[r][c] * x[c]
            x[r] = s / m[r][r]
        }
        return x
    }
}