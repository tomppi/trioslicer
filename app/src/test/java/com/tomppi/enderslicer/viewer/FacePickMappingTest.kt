package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.solidcraft.CadPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A tap on the second object of a two-part plate reaches the body and the triangle the tap was on.
 *
 * The picker answers scene-wide (it is given the plate as one mesh); the viewer's callbacks answer
 * per object, because every tool works on one object's own mesh. The face pick is the consumer that
 * got this wrong by subtracting the scene offset a second time on top: a tap on the second part
 * landed on a triangle index far outside that body, so the tap was refused ("did not land on a
 * body") or read a neighbour's triangle. These are the two halves the viewer owns: the conversion
 * itself, and the contract that the callback carries the object-local index.
 */
class FacePickMappingTest {

    private val caseTriangles = 6
    private val lidTriangles = 4
    private val triangleOwners = IntArray(caseTriangles + lidTriangles) { if (it < caseTriangles) 0 else 1 }
    private val objectFirstTriangle = intArrayOf(0, caseTriangles)

    private fun sceneHit(sceneTriangle: Int) = MeshPicker.Hit(
        triangleIndex = sceneTriangle,
        x = 10f,
        y = 20f,
        z = 30f,
        objectIndex = 0,
    )

    @Test
    fun aTapOnTheSecondPartResolvesToItsOwnBodyAndTriangle() {
        // Scene triangles 6..9 belong to the lid; the tap hit the lid's second triangle.
        val local = MeshPicker.objectLocalHit(sceneHit(caseTriangles + 1), triangleOwners, objectFirstTriangle)
        assertEquals("the hit names the second plate object", 1, local.objectIndex)
        assertEquals("and the triangle inside that object's own mesh", 1, local.triangleIndex)

        val plateObjectIds = listOf("cad:Case", "cad:Lid")
        val bodies = mapOf("cad:Case" to "Case", "cad:Lid" to "Lid")
        val body = bodies[plateObjectIds[local.objectIndex]]
        assertEquals("the plate object resolves to the lid body", "Lid", body)
        assertTrue(
            "the local index is inside the body's own mesh",
            local.triangleIndex in 0 until lidTriangles,
        )
        // The face pick is documented to name the body from the object it hit, so the document's
        // own part mapping is what turns the object into a body.
        val documentParts = listOf(CadPart(body = "Case", objectId = "cad:Case"), CadPart(body = "Lid", objectId = "cad:Lid"))
        assertEquals("Lid", documentParts.first { it.objectId == plateObjectIds[local.objectIndex] }.body)
    }

    @Test
    fun aTapOnTheFirstPartKeepsItsOwnTriangle() {
        val local = MeshPicker.objectLocalHit(sceneHit(2), triangleOwners, objectFirstTriangle)
        assertEquals(0, local.objectIndex)
        assertEquals(2, local.triangleIndex)
    }

    @Test
    fun aSingleObjectPlateAnswersWithTheSceneIndex() {
        // The one-object scene has no owner table and starts at zero: the object-local index is
        // the scene index, which is the triangle its paint is stored by.
        val local = MeshPicker.objectLocalHit(
            sceneHit(5),
            triangleOwners = IntArray(0),
            objectFirstTriangle = intArrayOf(0),
        )
        assertEquals(0, local.objectIndex)
        assertEquals(5, local.triangleIndex)
    }

    @Test
    fun theViewerDeliversObjectLocalHitsAndDoesNotRebaseTwice() {
        val source = viewerSource()
        val hit = source.substringAfter("private fun objectHit(")
            .substringBefore("fun annotationGestureAt(")
        assertTrue(
            "the viewer converts through the picker's one implementation",
            hit.contains("MeshPicker.objectLocalHit("),
        )
        assertFalse(
            "the viewer does not subtract the scene offset itself",
            source.contains("hit.triangleIndex - objectFirstTriangle"),
        )
    }

    private fun viewerSource(): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("the app module was not found from " + File("").absolutePath)
        return File(root, "app/src/main/java/com/tomppi/enderslicer/viewer/ModelSurfaceView.kt")
            .readText()
    }
}
