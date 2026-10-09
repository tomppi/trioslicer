package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.PlateObject
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.VertexData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plate-level guard the engines do not implement: CuraEngine ignores its own head polygon,
 * the shipped PrusaSlicer has the collision check commented out, and the Orca console never
 * validates the print sequence.
 */
class SequentialPrintCheckTest {

    @Test
    fun separatedObjectsAreAllowed() {
        val first = plateObject("first", minX = 10f, maxX = 30f, minY = 10f, maxY = 30f)
        val second = plateObject("second", minX = 80f, maxX = 100f, minY = 80f, maxY = 100f)

        assertNull(SequentialPrintCheck.refuseReason(listOf(first, second), SlicerSettings()))
    }

    @Test
    fun objectsTooCloseForTheHeadAreRefused() {
        // 10 mm apart: the footprints do not touch, but the 32 mm head overhang reaches across.
        val first = plateObject("first", minX = 10f, maxX = 30f, minY = 10f, maxY = 30f)
        val second = plateObject("second", minX = 40f, maxX = 60f, minY = 10f, maxY = 30f)

        val reason = SequentialPrintCheck.refuseReason(listOf(first, second), SlicerSettings())

        assertEquals(
            "first and second are too close for the print head: while one is printed, the head " +
                "would sweep through the finished one. Move them further apart.",
            reason,
        )
    }

    @Test
    fun headClearanceIsCheckedFromBothObjects() {
        // A head reaching 100 mm left and 1 mm right: printing the first object sweeps clear of
        // the second, but printing the second sweeps the head through the first.
        val oneSidedHead = SlicerSettings(
            printheadXMinMm = -100.0,
            printheadYMinMm = -32.0,
            printheadXMaxMm = 1.0,
            printheadYMaxMm = 34.0,
        )
        val first = plateObject("first", minX = 10f, maxX = 30f, minY = 10f, maxY = 30f)
        val second = plateObject("second", minX = 40f, maxX = 60f, minY = 10f, maxY = 30f)

        val reason = SequentialPrintCheck.refuseReason(listOf(first, second), oneSidedHead)

        assertEquals(
            "first and second are too close for the print head: while one is printed, the head " +
                "would sweep through the finished one. Move them further apart.",
            reason,
        )
    }

    @Test
    fun objectTallerThanTheGantryNextToAnotherIsRefused() {
        val tall = plateObject("tall", minX = 10f, maxX = 30f, minY = 10f, maxY = 30f, maxZ = 40f)
        val short = plateObject("short", minX = 80f, maxX = 100f, minY = 80f, maxY = 100f, maxZ = 10f)

        val reason = SequentialPrintCheck.refuseReason(listOf(tall, short), SlicerSettings())

        assertEquals(
            "tall is 40.0 mm tall, above the 25.0 mm gantry height, so nothing can be printed " +
                "after it. Print it on its own.",
            reason,
        )
    }

    @Test
    fun singleObjectIsAlwaysAllowed() {
        val tall = plateObject("tall", minX = 10f, maxX = 30f, minY = 10f, maxY = 30f, maxZ = 200f)

        assertNull(SequentialPrintCheck.refuseReason(listOf(tall), SlicerSettings()))
        assertNull(SequentialPrintCheck.refuseReason(emptyList(), SlicerSettings()))
    }

    @Test
    fun exactlyTouchingFootprintsAreRefused() {
        val first = plateObject("first", minX = 10f, maxX = 30f, minY = 10f, maxY = 30f)
        val second = plateObject("second", minX = 30f, maxX = 50f, minY = 10f, maxY = 30f)

        val reason = SequentialPrintCheck.refuseReason(listOf(first, second), SlicerSettings())

        assertEquals(
            "first and second touch or overlap, so they cannot be printed one object at a " +
                "time. Move them apart.",
            reason,
        )
    }

    @Test
    fun touchingFootprintsAreRefusedWithAnUnsetHeadPolygon() {
        val noHead = SlicerSettings(
            printheadXMinMm = 0.0,
            printheadYMinMm = 0.0,
            printheadXMaxMm = 0.0,
            printheadYMaxMm = 0.0,
        )
        val first = plateObject("first", minX = 10f, maxX = 30f, minY = 10f, maxY = 30f)
        val second = plateObject("second", minX = 30f, maxX = 50f, minY = 10f, maxY = 30f)

        val reason = SequentialPrintCheck.refuseReason(listOf(first, second), noHead)

        assertEquals(
            "first and second touch or overlap, so they cannot be printed one object at a " +
                "time. Move them apart.",
            reason,
        )
    }

    @Test
    fun gantryReasonWinsOverAnArrangementProblem() {
        // Two reasons on one plate: the tall object is reported because moving objects cannot
        // make room for the gantry.
        val closeFirst = plateObject("close-first", minX = 10f, maxX = 30f, minY = 10f, maxY = 30f)
        val closeSecond = plateObject("close-second", minX = 40f, maxX = 60f, minY = 10f, maxY = 30f)
        val tall = plateObject("tall", minX = 80f, maxX = 100f, minY = 80f, maxY = 100f, maxZ = 40f)

        val reason = SequentialPrintCheck.refuseReason(
            listOf(closeFirst, closeSecond, tall),
            SlicerSettings(),
        )

        assertTrue(reason.orEmpty().startsWith("tall is 40.0 mm tall"))
    }

    @Test
    fun firstOffendingPairFollowsTheListOrder() {
        val first = plateObject("first", minX = 10f, maxX = 30f, minY = 10f, maxY = 30f)
        val second = plateObject("second", minX = 40f, maxX = 60f, minY = 10f, maxY = 30f)
        val third = plateObject("third", minX = 10f, maxX = 30f, minY = 80f, maxY = 100f)
        val fourth = plateObject("fourth", minX = 40f, maxX = 60f, minY = 80f, maxY = 100f)
        val models = listOf(first, second, third, fourth)

        val reason = SequentialPrintCheck.refuseReason(models, SlicerSettings())

        assertEquals(
            "first and second are too close for the print head: while one is printed, the head " +
                "would sweep through the finished one. Move them further apart.",
            reason,
        )
        // The same plate always produces the same message.
        assertEquals(reason, SequentialPrintCheck.refuseReason(models, SlicerSettings()))
    }

    /**
     * A placed model standing in for an imported one: one triangle whose bounds are exactly the
     * footprint and height the check is meant to read.
     */
    private fun plateObject(
        name: String,
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float,
        minZ: Float = 0f,
        maxZ: Float = 10f,
    ): PlateObject {
        val mesh = StlMesh(
            displayName = name,
            interleavedVertices = VertexData.fromArray(
                floatArrayOf(
                    minX, minY, minZ, 0f, 0f, 1f,
                    maxX, minY, minZ, 0f, 0f, 1f,
                    minX, maxY, maxZ, 0f, 0f, 1f,
                ),
            ),
            triangleCount = 1,
            bounds = MeshBounds(minX, minY, minZ, maxX, maxY, maxZ),
        )
        return PlateObject(
            id = name,
            name = name,
            sourceMesh = mesh,
            mesh = mesh,
            sourcePath = null,
            placement = ModelPlacement(
                centerXmm = mesh.bounds.centerX.toDouble(),
                centerYmm = mesh.bounds.centerY.toDouble(),
                baseZmm = minZ.toDouble(),
            ),
        )
    }
}
