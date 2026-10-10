package com.tomppi.enderslicer.viewer

import android.content.ContentValues
import android.content.ContentUris
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.viewer.BedClipper.Half
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The JNI path, on the device, on a real model.
 *
 * The host can only compile the shim; whether Manifold really takes an app
 * mesh, unions a beam onto a real split half, refuses an open model and then
 * takes the same model after [MeshRepair] has closed it is a question only the
 * phone answers. This test walks that whole path with Prusa's temperature-tower
 * step out of the app's own assets - a real 2584-triangle model, not a test
 * cube - and leaves the resulting STLs in Downloads/dsh-agent.
 *
 * It needs a device but no screen: it runs while the phone is locked.
 */
@RunWith(AndroidJUnit4::class)
class MeshBooleanDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aRealSplitHalfTakesTheJointAndAnOpenModelIsRefusedThenRepaired() {
        assertTrue("the Manifold engine must load", MeshBoolean.ensureLoaded())
        assertNotNull("the engine reports a version", MeshBoolean.engineVersion)
        println("PROOF engine=libmanifold_jni version=" + MeshBoolean.engineVersion)

        // A real model out of the app's own assets: 80 x 10.5 x 10 mm, 2584
        // triangles, watertight as shipped.
        val directory = File(context.cacheDir, "snap-fit-device").apply { mkdirs() }
        val source = File(directory, "temp_tower-step.stl")
        context.assets.open(MODEL_ASSET).use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        val model = StlParser.parse(source, source.name)
        val modelVolume = volumeOf(model)
        println(
            "PROOF model tris=" + model.triangleCount +
                " size=" + model.bounds.width + "x" + model.bounds.depth + "x" + model.bounds.height +
                " volume=" + modelVolume,
        )

        // Phase one's split, on the middle of the model's long axis.
        val plane = (model.bounds.minX + model.bounds.maxX) * 0.5f
        val low = BedClipper.clipClosed(model, ModelPlacement.Axis.X, plane, Half.LOW)
        val high = BedClipper.clipClosed(model, ModelPlacement.Axis.X, plane, Half.HIGH)
        val lowVolume = volumeOf(low)
        val highVolume = volumeOf(high)
        println(
            "PROOF split plane_x=" + plane + " low_tris=" + low.triangleCount + " low_volume=" + lowVolume +
                " high_tris=" + high.triangleCount + " high_volume=" + highVolume +
                " delta=" + Math.abs(modelVolume - (lowVolume + highVolume)),
        )
        assertEquals("the capped halves conserve the model's volume", modelVolume, lowVolume + highVolume, 1e-2)

        val joint = fitJoint(low, high, plane)
        println(
            "PROOF joint beam=" + joint.dimensions.beamWidthMm + "x" + joint.dimensions.beamThicknessMm +
                "x" + joint.dimensions.beamLengthMm + " key=" + joint.dimensions.keySizeMm +
                " lip=" + joint.dimensions.lipDepthMm + " clearances=" +
                joint.dimensions.keyClearanceMm + "/" + joint.dimensions.lipClearanceMm + "/" +
                joint.dimensions.matingClearanceMm + " union_tris=" + joint.unionSolid.triangleCount +
                " socket_tris=" + joint.subtractSolid.triangleCount,
        )

        val startedAt = System.nanoTime()
        val union = MeshBoolean.union(low, joint.unionSolid)
        val unionWallMillis = (System.nanoTime() - startedAt) / 1_000_000.0
        if (union is MeshBoolean.Result.Failure) fail("the union was refused: " + union.reason)
        val unioned = union as MeshBoolean.Result.Success
        assertTrue("the union result is a closed solid", unioned.closed)
        assertTrue("the beam added material: " + unioned.volumeMm3 + " over " + lowVolume, unioned.volumeMm3 > lowVolume)
        println(
            "PROOF union status=" + unioned.status + " closed=" + unioned.closed +
                " tris=" + unioned.mesh.triangleCount + " volume=" + unioned.volumeMm3 +
                " engine_ms=" + unioned.millis + " wall_ms=" + unionWallMillis,
        )

        val subtract = MeshBoolean.subtract(high, joint.subtractSolid)
        if (subtract is MeshBoolean.Result.Failure) fail("the subtract was refused: " + subtract.reason)
        val socketed = subtract as MeshBoolean.Result.Success
        assertTrue("the socket result is a closed solid", socketed.closed)
        assertTrue("the pocket removed material: " + socketed.volumeMm3 + " under " + highVolume, socketed.volumeMm3 < highVolume)
        println(
            "PROOF subtract status=" + socketed.status + " closed=" + socketed.closed +
                " tris=" + socketed.mesh.triangleCount + " volume=" + socketed.volumeMm3 +
                " engine_ms=" + socketed.millis,
        )
        publish("snap-fit-union.stl", unioned.mesh)
        publish("snap-fit-socket.stl", socketed.mesh)
        publish("snap-fit-beam.stl", joint.unionSolid)

        // Now the same real model with a patch knocked out of it - what a scan
        // or a repaired export looks like - and the two answers Manifold gives.
        val damaged = knockOutPatch(unioned.mesh, 2)
        assertTrue("a patch really was removed", damaged.triangleCount < unioned.mesh.triangleCount)
        assertFalse("the engine will not take an open mesh", MeshBoolean.isManifold(damaged))
        val refused = MeshBoolean.union(damaged, joint.unionSolid)
        assertTrue("so the union is refused, not mangled", refused is MeshBoolean.Result.Failure)
        val reason = (refused as MeshBoolean.Result.Failure).reason
        println("PROOF without-repair refused: " + reason)
        assertTrue("and the refusal is the engine's own: " + reason, reason.contains("NotManifold"))
        publish("snap-fit-open.stl", damaged)

        val repaired = MeshRepair.repair(damaged)
        println(
            "PROOF repair " + repaired.report.summary + " loops=" + repaired.report.loopsFound +
                " filled=" + repaired.report.loopsFilled + " edges=" + repaired.report.edgesClosed +
                " left_open=" + repaired.report.loopsLeftOpen,
        )
        // The recorded claim this test used to carry - a repair that left ten loops
        // open - no longer reproduces: a JVM replay of this same damage (see
        // MeshRepairReplayTest) closes ONE EIGHT-EDGE hole and leaves nothing open.
        // The device must show the same thing, and it is pinned here rather than
        // asserted loosely, so a change that silently stops closing the hole fails.
        assertEquals("one hole was found", 1, repaired.report.loopsFound)
        assertEquals("and filled", 1, repaired.report.loopsFilled)
        assertEquals("its eight boundary edges were closed", 8, repaired.report.edgesClosed)
        assertEquals("and nothing was left open", 0, repaired.report.loopsLeftOpen)
        publish("snap-fit-repaired.stl", repaired.mesh)

        val afterRepair = MeshBoolean.union(repaired.mesh, joint.unionSolid)
        if (afterRepair is MeshBoolean.Result.Failure) fail("the repaired model was still refused: " + afterRepair.reason)
        val healed = afterRepair as MeshBoolean.Result.Success
        assertTrue("and comes out closed", healed.closed)
        println(
            "PROOF with-repair status=" + healed.status + " closed=" + healed.closed +
                " tris=" + healed.mesh.triangleCount + " volume=" + healed.volumeMm3 +
                " engine_ms=" + healed.millis,
        )
    }


    /**
     * The shim's box entry point: the engine builds the primitive itself, which
     * is what a caller reaches for when it wants a pocket without describing it
     * in Kotlin. Read back and measured here so the whole path is exercised.
     */
    @Test
    fun theEngineBuildsABox() {
        assertTrue("the Manifold engine must load", MeshBoolean.ensureLoaded())
        val box = MeshBoolean.nativeBox(floatArrayOf(0f, 0f, 0f), floatArrayOf(10f, 20f, 30f))
        assertTrue("the engine built a box", box != 0L)
        try {
            assertEquals("NoError", MeshBoolean.nativeStatus(box))
            assertTrue("a box is a closed solid", MeshBoolean.nativeIsClosed(box))
            assertEquals("10 x 20 x 30", 6000.0, MeshBoolean.nativeVolume(box), 1e-3)
            val soup = MeshBoolean.nativeReadMesh(box)
            assertNotNull("its geometry reads back", soup)
            assertEquals("twelve triangles of nine floats", 12 * 9, soup!!.size)
            println("PROOF box volume=" + MeshBoolean.nativeVolume(box) + " tris=" + (soup.size / 9))
        } finally {
            MeshBoolean.nativeRelease(box)
        }
        assertEquals("and nothing was left behind", 0, MeshBoolean.nativeLiveHandles())
    }

    /**
     * The joint, moved so the whole thing sits on the cut face: the generator
     * lays the key out beside the anchor, so a tap in the face's middle would
     * hang part of the joint off the edge.
     */
    private fun fitJoint(low: StlMesh, high: StlMesh, plane: Float): SnapFitJoint {
        // The generator takes each half as placed, carrying its own mating face; a bare
        // StlMesh has no face, and the joint would be measured from nowhere.
        val lowHalf = SnapFitHalf.inPlace(low, plane)
        val highHalf = SnapFitHalf.inPlace(high, plane)
        val direction = Vec3(1f, 0f, 0f)
        val faceCentre = Vec3(
            plane,
            (low.bounds.minY + low.bounds.maxY) * 0.5f,
            (low.bounds.minZ + low.bounds.maxZ) * 0.5f,
        )
        val first = SnapFit.generate(direction, faceCentre, 1f, lowHalf, highHalf)
        assertNotNull("the joint must fit this cut face", first)
        val local = localXOf(first!!.unionSolid, first.frame)
        val anchor = faceCentre - first.frame.side * ((local.first + local.second) * 0.5f)
        val joint = SnapFit.generate(direction, anchor, 1f, lowHalf, highHalf)
        assertNotNull("the joint must fit this cut face", joint)
        return joint!!
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

    /**
     * [mesh] with a patch of [rings] triangle rings knocked out of it, grown
     * over shared edges from the highest triangle: a real model deliberately
     * opened, so the repair has a genuine hole to close rather than a fixture.
     * Growing the patch over shared edges is what makes its boundary one clean
     * loop, the way a missing face in a scan is one hole.
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

    /**
     * Writes the mesh as a binary STL and puts it where the phone can find it.
     *
     * Publishing is a convenience - the STLs are left in Downloads/dsh-agent for a human to
     * look at - so a MediaStore that will not take the row must not fail the run that did the
     * engineering. A name a previous run already published is overwritten in place rather
     * than inserted again (a second insert of an existing path violates the unique _data
     * constraint, which is what used to fail this test before it reached its last phase), and
     * anything MediaStore still refuses is reported with the local path instead.
     */
    private fun publish(name: String, mesh: StlMesh) {
        val directory = File(context.cacheDir, "snap-fit-device").apply { mkdirs() }
        val file = File(directory, name)
        StlMeshWriter.writeBinary(mesh, file)
        assertTrue("the STL was written", file.length() > 84L)

        val published = runCatching { publishToDownloads(name, file) }.getOrNull()
        if (published == null) {
            println("PROOF published-local " + file.absolutePath + " bytes=" + file.length())
        } else {
            println("PROOF published " + published + " bytes=" + file.length())
        }
    }

    /** The Downloads row for [name], or null when MediaStore would not take it this run. */
    private fun publishToDownloads(name: String, file: File): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val existing = runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                arrayOf(name),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        }.getOrNull()
        if (existing != null) {
            // The row is already there: write the new bytes over it. No insert, no pending
            // transition, and no second row for one path.
            resolver.openOutputStream(ContentUris.withAppendedId(collection, existing), "wt")?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }
            return ContentUris.withAppendedId(collection, existing)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + PUBLISH_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }.onFailure { error ->
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        return uri
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
        const val MODEL_ASSET = "prusa/resources/lua/com.prusa3d.slicer.calibration/temp_tower-step.stl"
        const val PUBLISH_DIR = "dsh-agent"
    }
}
