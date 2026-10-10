package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.viewer.BedClipper.Half
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The device test's damage-and-repair step, replayed on the JVM with the host engine.
 *
 * [MeshBooleanDeviceTest] knocks a two-ring patch out of a real unioned half and expects
 * [MeshRepair] to close it. No device test had run since the tooth geometry changed, and the
 * numbers the test carried were recorded from a mesh that no longer exists: the claim was a
 * repair that left ten loops open. That does not reproduce. The same damage now closes ONE
 * EIGHT-EDGE hole and leaves nothing open, and the engine takes the repaired mesh again - so
 * that is what is pinned here, and what the device test asserts.
 *
 * The host Manifold build (scripts/build-manifold-host.sh) is what makes this replay possible;
 * without it the test skips, the way the other host-engine tests do.
 */
class MeshRepairReplayTest {

    @Test
    fun theDeviceTestsDamageIsClosedIntoAManifoldMeshTheEngineTakesAgain() {
        assumeTrue(
            "the host Manifold build is not on java.library.path; run scripts/build-manifold-host.sh",
            MeshBoolean.ensureLoaded(),
        )
        val source = asset("prusa/resources/lua/com.prusa3d.slicer.calibration/temp_tower-step.stl")
        val model = StlParser.parse(source, source.name)
        assertEquals("the shipped model is the one the device test uses", 2584, model.triangleCount)
        assertTrue("and it is a closed solid", MeshBoolean.isManifold(model))

        // The device test's own path up to the mesh it damages: split, fit a joint, union the
        // beam onto the low half - the real mesh a patch is then knocked out of.
        val plane = (model.bounds.minX + model.bounds.maxX) * 0.5f
        val low = BedClipper.clipClosed(model, ModelPlacement.Axis.X, plane, Half.LOW)
        val high = BedClipper.clipClosed(model, ModelPlacement.Axis.X, plane, Half.HIGH)
        val direction = Vec3(1f, 0f, 0f)
        val faceCentre = Vec3(
            plane,
            (low.bounds.minY + low.bounds.maxY) * 0.5f,
            (low.bounds.minZ + low.bounds.maxZ) * 0.5f,
        )
        val lowHalf = SnapFitHalf.inPlace(low, plane)
        val highHalf = SnapFitHalf.inPlace(high, plane)
        val first = SnapFit.generate(direction, faceCentre, 1f, lowHalf, highHalf)
        assertNotNull("a joint fits this cut face", first)
        val local = localXOf(first!!.unionSolid, first.frame)
        val anchor = faceCentre - first.frame.side * ((local.first + local.second) * 0.5f)
        val joint = SnapFit.generate(direction, anchor, 1f, lowHalf, highHalf)
        assertNotNull("and it fits once it sits on the face's middle", joint)
        val union = MeshBoolean.union(low, joint!!.unionSolid)
        if (union is MeshBoolean.Result.Failure) error("the union was refused: " + union.reason)
        val unioned = union as MeshBoolean.Result.Success
        assertTrue("the beam welds into a closed solid", unioned.closed)

        val damaged = knockOutPatch(unioned.mesh, 2)
        assertTrue("a patch really was removed", damaged.triangleCount < unioned.mesh.triangleCount)
        assertFalse("the engine will not take an open mesh", MeshBoolean.isManifold(damaged))

        val repaired = MeshRepair.repair(damaged)

        assertEquals("one hole was found", 1, repaired.report.loopsFound)
        assertEquals("and filled", 1, repaired.report.loopsFilled)
        assertEquals("its eight boundary edges were closed", 8, repaired.report.edgesClosed)
        assertEquals("nothing was left open", 0, repaired.report.loopsLeftOpen)
        assertEquals("closed 1 hole (8 edges)", repaired.report.summary)
        assertTrue("and the repair is a closed solid the engine accepts", MeshBoolean.isManifold(repaired.mesh))

        val afterRepair = MeshBoolean.union(repaired.mesh, joint.unionSolid)
        if (afterRepair is MeshBoolean.Result.Failure) error("the repaired model was still refused: " + afterRepair.reason)
        val healed = afterRepair as MeshBoolean.Result.Success
        assertTrue("and the union onto it comes out closed", healed.closed)
    }

    private fun localXOf(mesh: StlMesh, frame: SnapFitFrame): Pair<Float, Float> {
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        val vertices = mesh.interleavedVertices
        for (vertex in 0 until mesh.triangleCount * 3) {
            val base = vertex * 6
            val x = frame.local(Vec3(vertices[base], vertices[base + 1], vertices[base + 2])).x
            if (x < low) low = x
            if (x > high) high = x
        }
        return low to high
    }

    private fun asset(relative: String): File {
        val candidates = listOf(
            File("src/main/assets/" + relative),
            File("app/src/main/assets/" + relative),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate asset " + relative + " from " + File(".").absolutePath)
    }

    /**
     * [mesh] with a patch of [rings] triangle rings knocked out of it, grown over shared edges
     * from the highest triangle: the device test's own damage, verbatim, so the replay repairs
     * the thing the phone repairs.
     */
    private fun knockOutPatch(mesh: StlMesh, rings: Int): StlMesh {
        val vertices = mesh.interleavedVertices
        val cornerCount = mesh.triangleCount * 3
        val ids = HashMap<Triple<Int, Int, Int>, Int>(cornerCount)
        val corners = IntArray(cornerCount)
        for (corner in 0 until cornerCount) {
            val base = corner * 6
            val key = Triple(
                Math.round(vertices[base] * 10_000f),
                Math.round(vertices[base + 1] * 10_000f),
                Math.round(vertices[base + 2] * 10_000f),
            )
            corners[corner] = ids.getOrPut(key) { ids.size }
        }
        val neighbours = Array(mesh.triangleCount) { ArrayList<Int>(4) }
        val owner = HashMap<Long, Int>(mesh.triangleCount * 3)
        for (triangle in 0 until mesh.triangleCount) {
            for (corner in 0 until 3) {
                val a = corners[triangle * 3 + corner]
                val b = corners[triangle * 3 + (corner + 1) % 3]
                if (a == b) continue
                val key = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
                val other = owner.put(key, triangle)
                if (other != null) {
                    neighbours[triangle].add(other)
                    neighbours[other].add(triangle)
                }
            }
        }
        var seed = 0
        var highest = Float.NEGATIVE_INFINITY
        for (triangle in 0 until mesh.triangleCount) {
            var centre = 0f
            for (corner in 0 until 3) centre += vertices[triangle * 18 + corner * 6 + 2]
            centre /= 3f
            if (centre > highest) {
                highest = centre
                seed = triangle
            }
        }
        val removed = BooleanArray(mesh.triangleCount)
        var frontier = listOf(seed)
        removed[seed] = true
        repeat(rings) {
            val next = ArrayList<Int>()
            for (triangle in frontier) {
                for (neighbour in neighbours[triangle]) {
                    if (removed[neighbour]) continue
                    removed[neighbour] = true
                    next.add(neighbour)
                }
            }
            frontier = next
        }
        var kept = 0
        for (triangle in 0 until mesh.triangleCount) if (!removed[triangle]) kept++
        val floats = FloatArray(kept * 18)
        var written = 0
        for (triangle in 0 until mesh.triangleCount) {
            if (removed[triangle]) continue
            for (index in 0 until 18) floats[written++] = vertices[triangle * 18 + index]
        }
        return StlMesh(
            displayName = mesh.displayName + " (hole)",
            interleavedVertices = VertexData.fromArray(floats),
            triangleCount = kept,
            bounds = boundsOf(floats, kept),
        )
    }

    private fun boundsOf(floats: FloatArray, triangleCount: Int): MeshBounds {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (vertex in 0 until triangleCount * 3) {
            val base = vertex * 6
            if (floats[base] < minX) minX = floats[base]
            if (floats[base + 1] < minY) minY = floats[base + 1]
            if (floats[base + 2] < minZ) minZ = floats[base + 2]
            if (floats[base] > maxX) maxX = floats[base]
            if (floats[base + 1] > maxY) maxY = floats[base + 1]
            if (floats[base + 2] > maxZ) maxZ = floats[base + 2]
        }
        return MeshBounds(minX, minY, minZ, maxX, maxY, maxZ)
    }
}
