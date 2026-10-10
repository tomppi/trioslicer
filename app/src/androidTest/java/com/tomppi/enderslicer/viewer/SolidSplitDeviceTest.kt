package com.tomppi.enderslicer.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomppi.enderslicer.model.ModelPlacement
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The boolean split on the device, on the model the report came from and on a
 * cube whose grid planes are dense with vertices.
 *
 * The JVM tests stand a fake engine in for Manifold; whether the real engine
 * takes a real model, whether the halves really are closed solids, and whether
 * the two volumes really add up to the model's are questions only the phone
 * answers. It needs a device but no screen: it runs while the phone is locked.
 */
@RunWith(AndroidJUnit4::class)
class SolidSplitDeviceTest {
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The user's own model, cut on Y at its middle: the split that once reported
     * "xyzCalibration_cube.stl front is not watertight and could not be closed:
     * closed 1 hole (5 edges), left 1 open: a boundary that revisits a vertex".
     * The sliver weld has since closed that case, so the clipper's own half is
     * checked here as what it is now - a closed solid the repair has nothing to do
     * with - rather than as the open one that motivated the boolean path.
     */
    @Test
    fun theCalibrationCubeSplitsIntoTwoManifoldHalvesAtTheReportedPlane() {
        assertTrue("the Manifold engine must load", MeshBoolean.ensureLoaded())
        val model = calibrationCube()
        val volume = volumeOf(model)
        println(
            "PROOF cube tris=" + model.triangleCount + " volume=" + volume +
                " bounds=" + model.bounds.width + "x" + model.bounds.depth + "x" + model.bounds.height,
        )
        assertTrue("the shipped model is a closed solid", MeshBoolean.isManifold(model))

        val axis = ModelPlacement.Axis.Y
        val plane = (model.bounds.minY + model.bounds.maxY) * 0.5f

        // The clipper's own capped half, for the record. It used to come back open
        // here - "closed 1 hole (5 edges), left 1 open" - which is what the snap gate
        // ran into and what the boolean path was added for. The sliver weld has since
        // closed it, so what the device has to show now is a closed half the repair
        // finds nothing to do with; a device that disagrees is a real finding.
        val clipped = BedClipper.clipClosed(model, axis, plane, BedClipper.Half.LOW)
        val clippedReport = MeshRepair.repair(clipped).report
        println(
            "PROOF clipper-low tris=" + clipped.triangleCount + " manifold=" + MeshBoolean.isManifold(clipped) +
                " repair=[" + clippedReport.summary + "]",
        )
        assertTrue("the clipper's capped half is a closed solid", MeshBoolean.isManifold(clipped))
        assertEquals("and the repair finds nothing to close", "closed already", clippedReport.summary)

        val started = System.nanoTime()
        val split = SolidSplitter.split(model, axis, plane)
        val millis = (System.nanoTime() - started) / 1_000_000.0
        assertEquals("the boolean is the path on a closed model", SolidSplitter.Path.BOOLEAN, split.path)
        assertTrue("the low half is a closed solid", MeshBoolean.isManifold(split.low))
        assertTrue("the high half is a closed solid", MeshBoolean.isManifold(split.high))
        val lowVolume = volumeOf(split.low)
        val highVolume = volumeOf(split.high)
        println(
            "PROOF boolean split low_tris=" + split.low.triangleCount + " high_tris=" + split.high.triangleCount +
                " low_volume=" + lowVolume + " high_volume=" + highVolume + " sum=" + (lowVolume + highVolume) +
                " delta=" + Math.abs(volume - lowVolume - highVolume) + " wall_ms=" + millis,
        )
        assertEquals("the halves conserve the model's volume", volume, lowVolume + highVolume, volume * 1e-4)

        // The gate the report stopped at: both halves are taken with no repair.
        val lowReady = SnapFitGate.prepare(split.low, SnapFitGate.NativeEngine, "xyzCalibration_cube.stl front")
        if (lowReady is SnapFitGate.Result.Refused) fail("the front half was refused: " + lowReady.reason)
        val highReady = SnapFitGate.prepare(split.high, SnapFitGate.NativeEngine, "xyzCalibration_cube.stl back")
        if (highReady is SnapFitGate.Result.Refused) fail("the back half was refused: " + highReady.reason)
        val lowMesh = (lowReady as SnapFitGate.Result.Prepared).ready
        val highMesh = (highReady as SnapFitGate.Result.Prepared).ready
        assertNull("the front half needed no repair", lowMesh.note)
        assertNull("the back half needed no repair", highMesh.note)
        println("PROOF gate front=Prepared back=Prepared repair-note=null")

        // And the thing the gate exists for: a joint unions onto each half.
        // The generator lays the key out beside the anchor, so the anchor is
        // moved onto the middle of the joint the way the preview does it; a tap
        // in the middle of the face would hang part of the key off the edge.
        val direction = Vec3(0f, 1f, 0f)
        val faceCentre = Vec3(
            (split.low.bounds.minX + split.low.bounds.maxX) * 0.5f,
            plane,
            (split.low.bounds.minZ + split.low.bounds.maxZ) * 0.5f,
        )
        val lowHalf = SnapFitHalf.inPlace(split.low, plane)
        val highHalf = SnapFitHalf.inPlace(split.high, plane)
        val first = SnapFit.generate(direction, faceCentre, 1f, lowHalf, highHalf)
        assertNotNull("a joint fits the cut face", first)
        val local = localXOf(first!!.unionSolid, first.frame)
        val joint = SnapFit.generate(
            direction,
            faceCentre - first.frame.side * ((local.first + local.second) * 0.5f),
            1f,
            lowHalf,
            highHalf,
        )
        assertNotNull("and it fits once it sits on the face's middle", joint)
        println("PROOF rung=" + joint!!.rung + " reason=" + joint.rungReason)
        val union = MeshBoolean.union(split.low, joint.unionSolid)
        if (union is MeshBoolean.Result.Failure) fail("the union was refused: " + union.reason)
        val unioned = union as MeshBoolean.Result.Success
        assertTrue("the beam welds into a closed solid", unioned.closed)

        // The whole-seam registration step: a boss on this half, the matching
        // recess in the other, and material really moved both times.
        val registered = MeshBoolean.union(unioned.mesh, joint.registrationSolid)
        if (registered is MeshBoolean.Result.Failure) fail("the step was refused: " + registered.reason)
        val stepped = registered as MeshBoolean.Result.Success
        assertTrue("the boss added material: " + stepped.volumeMm3 + " over " + unioned.volumeMm3, stepped.volumeMm3 > unioned.volumeMm3)
        val socketed = MeshBoolean.subtract(split.high, joint.subtractSolid)
        if (socketed is MeshBoolean.Result.Failure) fail("the pocket was refused: " + socketed.reason)
        val pocketed = socketed as MeshBoolean.Result.Success
        assertTrue(
            "the beam's pocket removed material: " + pocketed.volumeMm3 + " under " + highVolume,
            pocketed.volumeMm3 < highVolume,
        )
        val recessed = MeshBoolean.subtract(pocketed.mesh, joint.registrationRecess)
        if (recessed is MeshBoolean.Result.Failure) fail("the recess was refused: " + recessed.reason)
        val recessedMesh = recessed as MeshBoolean.Result.Success
        assertTrue(
            "and the recess removed more: " + recessedMesh.volumeMm3 + " under " + pocketed.volumeMm3,
            recessedMesh.volumeMm3 < pocketed.volumeMm3,
        )
        assertTrue("the recessed half is still a closed solid", recessedMesh.closed)
        assertTrue(
            "the pocket is a feature on the face, not the face: " + pocketed.volumeMm3 + " of " + highVolume,
            highVolume - pocketed.volumeMm3 < 0.25 * highVolume,
        )
        println(
            "PROOF step boss_volume=" + stepped.volumeMm3 + " boss_added=" + (stepped.volumeMm3 - unioned.volumeMm3) +
                " pocket_removed=" + (highVolume - pocketed.volumeMm3) +
                " recess_removed=" + (pocketed.volumeMm3 - recessedMesh.volumeMm3) +
                " step_depth=" + joint.dimensions.stepDepthMm + " rim=" + joint.dimensions.stepRimMm +
                " key=" + joint.dimensions.keySizeMm + " beam=" + joint.dimensions.beamLengthMm +
                "x" + joint.dimensions.beamWidthMm + "x" + joint.dimensions.beamThicknessMm,
        )
        println(
            "PROOF joint union status=" + unioned.status + " closed=" + unioned.closed +
                " tris=" + unioned.mesh.triangleCount + " volume=" + unioned.volumeMm3 + " engine_ms=" + unioned.millis,
        )
    }

    /**
     * A cube whose faces are a 4 x 4 grid, cut at the three places a plane can
     * meet a surface: through the grid's own vertices (10), through the middle
     * of a cell of a face (2.5), and through the interior of the cube's own
     * vertical edges, halfway between two of their vertices (7.5). Every one
     * gives two closed solids whose volumes add up, and a scan across the whole
     * model does too.
     */
    @Test
    fun aSubdividedCubeSplitsManifoldAtAVertexAFaceCentreAndAnEdge() {
        assertTrue("the Manifold engine must load", MeshBoolean.ensureLoaded())
        val cube = gridCube(20f, 4)
        val volume = volumeOf(cube)
        assertEquals("the fixture is a closed 20 mm cube", 8000.0, volume, 1e-3)
        assertTrue("and the engine takes it", MeshBoolean.isManifold(cube))

        val planes = listOf(
            10f to "through vertices",
            2.5f to "through the middle of a face's cells",
            7.5f to "through the interior of the cube's vertical edges",
        )
        for ((plane, what) in planes) {
            for (axis in ModelPlacement.Axis.values()) {
                val split = SolidSplitter.split(cube, axis, plane)
                assertEquals(axis.name + " at " + plane + " (" + what + "): the boolean ran", SolidSplitter.Path.BOOLEAN, split.path)
                assertTrue(axis.name + " at " + plane + ": the low half is manifold", MeshBoolean.isManifold(split.low))
                assertTrue(axis.name + " at " + plane + ": the high half is manifold", MeshBoolean.isManifold(split.high))
                val sum = volumeOf(split.low) + volumeOf(split.high)
                assertEquals(axis.name + " at " + plane + " (" + what + "): volumes add up", volume, sum, 1e-3)
                println(
                    "PROOF grid axis=" + axis.name + " plane=" + plane + " (" + what + ") low_tris=" +
                        split.low.triangleCount + " high_tris=" + split.high.triangleCount +
                        " low_volume=" + volumeOf(split.low) + " sum=" + sum,
                )
            }
        }

        // Every plane on a quarter-millimetre, none of them special.
        var checked = 0
        var plane = 0.25f
        while (plane < 20f) {
            val split = SolidSplitter.split(cube, ModelPlacement.Axis.Y, plane)
            assertEquals("plane " + plane + ": the boolean ran", SolidSplitter.Path.BOOLEAN, split.path)
            assertTrue("plane " + plane + ": the low half is manifold", MeshBoolean.isManifold(split.low))
            assertTrue("plane " + plane + ": the high half is manifold", MeshBoolean.isManifold(split.high))
            assertEquals("plane " + plane + ": volumes add up", volume, volumeOf(split.low) + volumeOf(split.high), 1e-2)
            checked++
            plane += 0.25f
        }
        println("PROOF grid scan planes=" + checked + " all manifold with the volume conserved")
    }

    /**
     * The ladder on a real, awkward model. The Benchy's hull is a thin curved
     * wall, and a cut through it gives several loops rather than one disc -
     * which is exactly the shape a cube cannot stand in for. Whatever rung the
     * wall can carry, the union has to share volume with the half it is rooted
     * in and the pocket has to share volume with the mate; closedness alone
     * would pass a floating block.
     */
    @Test
    fun theJointReallyLandsInTheBenchyWhereverItIsCut() {
        assertTrue("the Manifold engine must load", MeshBoolean.ensureLoaded())
        val model = stl(MODEL_BENCHY)
        assertTrue("the shipped Benchy is a closed solid", MeshBoolean.isManifold(model))

        for (axis in listOf(ModelPlacement.Axis.Y, ModelPlacement.Axis.Z)) {
            val span = model.bounds.spanAlong(axis)
            val plane = span.start + (span.endInclusive - span.start) * 0.5f
            val split = SolidSplitter.split(model, axis, plane)
            assertEquals(axis.name + ": the boolean ran", SolidSplitter.Path.BOOLEAN, split.path)
            assertTrue(axis.name + ": the low half is closed", MeshBoolean.isManifold(split.low))
            assertTrue(axis.name + ": the high half is closed", MeshBoolean.isManifold(split.high))

            val anchor = hullAnchor(split.low, axis, plane)
            val built = SnapJoint.build(
                axis = axis,
                anchorMm = anchor,
                scale = 1f,
                lowHalf = SnapFitHalf.inPlace(split.low, plane),
                highHalf = SnapFitHalf.inPlace(split.high, plane),
                beamHalf = SnapJoint.JointHalf.LOW,
            )
            val placement = when (built) {
                is SnapJoint.Either.Placed -> built.placement
                is SnapJoint.Either.Flipped -> built.placement
                is SnapJoint.Either.Failed -> {
                    fail(axis.name + ": no joint at all: " + built.failure.summary)
                    return
                }
            }
            val joint = placement.joint
            if (joint.rung != SnapFitRung.FULL) {
                assertNotNull(axis.name + ": a lighter rung says why", joint.rungReason)
            }
            val seat = sharedVolume(joint.unionSolid, placement.beamMesh)
            val pocket = sharedVolume(joint.subtractSolid, placement.socketMesh)
            assertTrue(axis.name + ": the beam shares volume with its half: " + seat, seat > 0.0)
            assertTrue(axis.name + ": the pocket shares volume with the mate: " + pocket, pocket > 0.0)
            println(
                "PROOF benchy axis=" + axis.name + " plane=" + plane + " rung=" + joint.rung +
                    " reason=" + joint.rungReason + " seat=" + seat + " pocket=" + pocket +
                    " beam=" + joint.dimensions.beamLengthMm + "x" + joint.dimensions.beamWidthMm +
                    "x" + joint.dimensions.beamThicknessMm + " root=" + joint.dimensions.beamRootMm +
                    " anchor=" + anchor,
            )
        }
    }

    /**
     * A tap on the hull: the cut-face vertex furthest from the model's centre
     * in the plane, which is the boat's side wall rather than the cabin or the
     * hollow middle.
     */
    private fun hullAnchor(low: StlMesh, axis: ModelPlacement.Axis, plane: Float): Vec3 {
        val vertices = low.interleavedVertices
        val centre = low.bounds
        var best = Vec3(0f, 0f, 0f)
        var bestDistance = -1f
        for (vertex in 0 until low.triangleCount * 3) {
            val base = vertex * MESH_FLOATS_PER_VERTEX
            val point = Vec3(vertices[base], vertices[base + 1], vertices[base + 2])
            val along = when (axis) {
                ModelPlacement.Axis.X -> point.x
                ModelPlacement.Axis.Y -> point.y
                ModelPlacement.Axis.Z -> point.z
            }
            if (Math.abs(along - plane) > 0.05f) continue
            val across = when (axis) {
                ModelPlacement.Axis.X ->
                    Math.hypot((point.y - centre.centerY).toDouble(), (point.z - centre.centerZ).toDouble()).toFloat()
                ModelPlacement.Axis.Y ->
                    Math.hypot((point.x - centre.centerX).toDouble(), (point.z - centre.centerZ).toDouble()).toFloat()
                ModelPlacement.Axis.Z ->
                    Math.hypot((point.x - centre.centerX).toDouble(), (point.y - centre.centerY).toDouble()).toFloat()
            }
            if (across > bestDistance) {
                bestDistance = across
                best = point
            }
        }
        return when (axis) {
            ModelPlacement.Axis.X -> Vec3(plane, best.y, best.z)
            ModelPlacement.Axis.Y -> Vec3(best.x, plane, best.z)
            ModelPlacement.Axis.Z -> Vec3(best.x, best.y, plane)
        }
    }

    /** The volume a joint solid shares with the half it is built for. */
    private fun sharedVolume(solid: StlMesh, half: StlMesh): Double {
        if (solid.triangleCount == 0) return 0.0
        val shared = MeshBoolean.intersect(solid, half)
        if (shared is MeshBoolean.Result.Failure) fail("the intersection was refused: " + shared.reason)
        return (shared as MeshBoolean.Result.Success).volumeMm3
    }

    /** How far along the joint's own side axis the generated solid reaches. */
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

    /** The model [name] out of the test APK's own assets. */
    private fun stl(name: String): StlMesh {
        // The app's own cache, the way the boolean device test stages its model:
        // the test package's cache directory is not created for an instrumentation
        // that never launches it as an app.
        val directory = File(targetContext.cacheDir, "solid-split-device").apply { mkdirs() }
        val file = File(directory, name)
        testContext.assets.open(name).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return StlParser.parse(file, name)
    }

    private fun calibrationCube(): StlMesh = stl(MODEL_ASSET)

    /**
     * A cube subdivided into a [divisions] x [divisions] grid on every face, so
     * that cutting on a grid line passes the plane exactly through the mesh's
     * own vertices - which is the case that broke the clipper's cap.
     */
    private fun gridCube(size: Float, divisions: Int): StlMesh {
        val builder = MeshSolidBuilder("grid-cube")
        fun point(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
        val faces = listOf(
            listOf(point(0f, 0f, 0f), point(0f, size, 0f), point(size, size, 0f), point(size, 0f, 0f)),
            listOf(point(0f, 0f, size), point(size, 0f, size), point(size, size, size), point(0f, size, size)),
            listOf(point(0f, 0f, 0f), point(size, 0f, 0f), point(size, 0f, size), point(0f, 0f, size)),
            listOf(point(size, 0f, 0f), point(size, size, 0f), point(size, size, size), point(size, 0f, size)),
            listOf(point(0f, size, 0f), point(0f, size, size), point(size, size, size), point(size, size, 0f)),
            listOf(point(0f, 0f, 0f), point(0f, 0f, size), point(0f, size, size), point(0f, size, 0f)),
        )
        for (face in faces) {
            val (a, b, c, d) = face
            fun at(u: Float, v: Float) = floatArrayOf(
                a[0] * (1f - u) * (1f - v) + b[0] * u * (1f - v) + c[0] * u * v + d[0] * (1f - u) * v,
                a[1] * (1f - u) * (1f - v) + b[1] * u * (1f - v) + c[1] * u * v + d[1] * (1f - u) * v,
                a[2] * (1f - u) * (1f - v) + b[2] * u * (1f - v) + c[2] * u * v + d[2] * (1f - u) * v,
            )
            for (i in 0 until divisions) {
                for (j in 0 until divisions) {
                    val u0 = i.toFloat() / divisions
                    val u1 = (i + 1).toFloat() / divisions
                    val v0 = j.toFloat() / divisions
                    val v1 = (j + 1).toFloat() / divisions
                    val p00 = at(u0, v0)
                    val p10 = at(u1, v0)
                    val p11 = at(u1, v1)
                    val p01 = at(u0, v1)
                    builder.addTriangle(p00[0], p00[1], p00[2], p10[0], p10[1], p10[2], p11[0], p11[1], p11[2])
                    builder.addTriangle(p00[0], p00[1], p00[2], p11[0], p11[1], p11[2], p01[0], p01[1], p01[2])
                }
            }
        }
        return builder.build()
    }

    /** The volume the surface bounds, positive for an outward-facing mesh. */
    private fun volumeOf(mesh: StlMesh): Double {
        var total = 0.0
        val vertices = mesh.interleavedVertices
        var offset = 0
        repeat(mesh.triangleCount) {
            val ax = vertices[offset].toDouble()
            val ay = vertices[offset + 1].toDouble()
            val az = vertices[offset + 2].toDouble()
            val bx = vertices[offset + 6].toDouble()
            val by = vertices[offset + 7].toDouble()
            val bz = vertices[offset + 8].toDouble()
            val cx = vertices[offset + 12].toDouble()
            val cy = vertices[offset + 13].toDouble()
            val cz = vertices[offset + 14].toDouble()
            total += (ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)) / 6.0
            offset += 18
        }
        return total
    }

    private companion object {
        const val MODEL_ASSET = "xyzCalibration_cube.stl"
        const val MODEL_BENCHY = "3dbenchy.stl"
        const val MESH_FLOATS_PER_VERTEX = 6
    }
}
