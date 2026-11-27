package com.rawmechatronics.easyscanner

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PatchPoseGraphTest {
    private fun rotY(a: Double): DoubleArray {
        val c = cos(a); val s = sin(a)
        return doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c)
    }

    private fun pose(angle: Double, x: Double, y: Double, z: Double): RigidPose {
        val r = rotY(angle)
        val t = doubleArrayOf(-(r[0]*x + r[1]*y + r[2]*z), -(r[3]*x + r[4]*y + r[5]*z), -(r[6]*x + r[7]*y + r[8]*z))
        return RigidPose(r, t)
    }

    private fun chainError(poses: List<RigidPose>, truth: List<RigidPose>): Double {
        var s = 0.0
        for (i in poses.indices) {
            val a = poses[i].cameraCentre(); val b = truth[i].cameraCentre()
            s += (a[0]-b[0])*(a[0]-b[0]) + (a[1]-b[1])*(a[1]-b[1]) + (a[2]-b[2])*(a[2]-b[2])
        }
        return s
    }

    @Test
    fun loopEdgePullsDriftedChainBack() {
        val truth = List(6) { i -> pose(i * 0.05, i * 0.08, 0.0, 0.0) }
        // Add a deliberate drift to the initial chain.
        val initial = truth.mapIndexed { i, p ->
            val c = p.cameraCentre()
            pose(i * 0.05 + 0.02, c[0] + i * 0.006, c[1] + i * 0.004, c[2])
        }
        val edges = ArrayList<PatchPoseGraph.Edge>()
        for (i in 0 until truth.size - 1) {
            edges.add(PatchPoseGraph.Edge(i, i + 1, LaserIcp.compose(truth[i + 1], LaserIcp.invert(truth[i])), 1.0))
        }
        // Close the loop: node 5 is also tied back to node 0.
        edges.add(PatchPoseGraph.Edge(0, 5, LaserIcp.compose(truth[5], LaserIcp.invert(truth[0])), 1.0))
        val solved = PatchPoseGraph.optimize(initial, edges, iterations = 10)
        assertTrue(chainError(solved, truth) < chainError(initial, truth))
    }
}