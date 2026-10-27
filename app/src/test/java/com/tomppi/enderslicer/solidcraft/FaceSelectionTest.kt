package com.tomppi.enderslicer.solidcraft

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.MeshPicker
import com.tomppi.enderslicer.viewer.PlateModes
import com.tomppi.enderslicer.viewer.SelectionTintBuffer
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.VertexData
import com.tomppi.enderslicer.viewer.ViewerOrientation
import com.tomppi.enderslicer.supportpaint.SupportPaintMode
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tap-to-face: the plate's tap resolved to the engine's own face, in the document's coordinates.
 *
 * The fixture is a 40 x 30 x 12 box in the document's own millimetres, with the face list shaped
 * exactly as engine 0.24.0's `model.faces` reports it (`index, name, exact, area, centroid,
 * plane_normal, point`). The plate placement is a real one - a 90 degree turn about Z and a move
 * on the bed - so a mapping that forgot to undo it would answer with the wrong triangle or the
 * wrong face, which is what this pins.
 */
class FaceSelectionTest {

    // --- fixtures ------------------------------------------------------------------------

    private val boxLength = 40.0
    private val boxWidth = 30.0
    private val boxHeight = 12.0

    /** The box as the engine's STL export carries it: outward normals, two triangles a face. */
    private fun boxMesh(
        length: Double = boxLength,
        width: Double = boxWidth,
        height: Double = boxHeight,
    ): StlMesh {
        val vertices = ArrayList<Float>()
        fun face(a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray, normal: DoubleArray) {
            fun put(point: DoubleArray) {
                vertices.add(point[0].toFloat()); vertices.add(point[1].toFloat()); vertices.add(point[2].toFloat())
                vertices.add(normal[0].toFloat()); vertices.add(normal[1].toFloat()); vertices.add(normal[2].toFloat())
            }
            put(a); put(b); put(c)
            put(a); put(c); put(d)
        }
        val l = length
        val w = width
        val h = height
        // -z (the bed face), -y, +x, +y, -x, +z: six faces, two triangles each.
        face(
            doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(l, 0.0, 0.0),
            doubleArrayOf(l, w, 0.0), doubleArrayOf(0.0, w, 0.0), doubleArrayOf(0.0, 0.0, -1.0),
        )
        face(
            doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(l, 0.0, 0.0),
            doubleArrayOf(l, 0.0, h), doubleArrayOf(0.0, 0.0, h), doubleArrayOf(0.0, -1.0, 0.0),
        )
        face(
            doubleArrayOf(l, 0.0, 0.0), doubleArrayOf(l, w, 0.0),
            doubleArrayOf(l, w, h), doubleArrayOf(l, 0.0, h), doubleArrayOf(1.0, 0.0, 0.0),
        )
        face(
            doubleArrayOf(0.0, w, 0.0), doubleArrayOf(l, w, 0.0),
            doubleArrayOf(l, w, h), doubleArrayOf(0.0, w, h), doubleArrayOf(0.0, 1.0, 0.0),
        )
        face(
            doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, w, 0.0),
            doubleArrayOf(0.0, w, h), doubleArrayOf(0.0, 0.0, h), doubleArrayOf(-1.0, 0.0, 0.0),
        )
        face(
            doubleArrayOf(0.0, 0.0, h), doubleArrayOf(l, 0.0, h),
            doubleArrayOf(l, w, h), doubleArrayOf(0.0, w, h), doubleArrayOf(0.0, 0.0, 1.0),
        )
        val values = FloatArray(vertices.size) { vertices[it] }
        return StlMesh(
            displayName = "box",
            interleavedVertices = VertexData.fromArray(values),
            triangleCount = values.size / 18,
            bounds = MeshBounds(0f, 0f, 0f, l.toFloat(), w.toFloat(), h.toFloat()),
        )
    }

    /** The box's faces exactly as `model.faces {body:"Body1"}` answered on engine 0.24.0. */
    private fun boxFaces(
        length: Double = boxLength,
        width: Double = boxWidth,
        height: Double = boxHeight,
    ): List<EngineFace> = listOf(
        EngineFace(0, "F1:-z", true, length * width, FacePoint(length / 2, width / 2, 0.0), FacePoint(0.0, 0.0, -1.0), FacePoint(length * 3 / 4, width / 3, 0.0), null),
        EngineFace(1, "F1:-y", true, length * height, FacePoint(length / 2, 0.0, height / 2), FacePoint(0.0, -1.0, 0.0), FacePoint(length * 0.87, 0.0, height * 0.43), null),
        EngineFace(2, "F1:+x", true, width * height, FacePoint(length, width / 2, height / 2), FacePoint(1.0, 0.0, 0.0), FacePoint(length, width * 0.84, height * 0.4), null),
        EngineFace(3, "F1:+y", true, length * height, FacePoint(length / 2, width, height / 2), FacePoint(0.0, 1.0, 0.0), FacePoint(length * 0.13, width, height * 0.43), null),
        EngineFace(4, "F1:-x", true, width * height, FacePoint(0.0, width / 2, height / 2), FacePoint(-1.0, 0.0, 0.0), FacePoint(0.0, width * 0.16, height * 0.4), null),
        EngineFace(5, "F1:+z", true, length * width, FacePoint(length / 2, width / 2, height), FacePoint(0.0, 0.0, 1.0), FacePoint(length * 3 / 4, width / 3, height), null),
    )

    /** A real plate placement: turned a quarter turn and moved onto the bed. */
    private fun turnedPlacement(): ModelPlacement = ModelPlacement(
        linear = ModelPlacement.IDENTITY.let { listOf(0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0) },
        centerXmm = 110.0,
        centerYmm = 110.0,
        baseZmm = 0.0,
        source = "Turned and placed",
    )

    private class FakeQuery(
        private val faces: List<EngineFace>,
        private val agree: Boolean = true,
    ) : FaceQuery {
        var selectCalls = 0
        var selectedBody: String? = null
        var selectedIndex: Int? = null
        var selectedPoint: FacePoint? = null
        var clearCalls = 0

        override fun faces(body: String): List<EngineFace> = faces

        override fun select(body: String, index: Int, point: FacePoint): Boolean {
            selectCalls++
            selectedBody = body
            selectedIndex = index
            selectedPoint = point
            return agree
        }

        override fun clear() {
            clearCalls++
        }
    }

    private fun plateHit(mesh: StlMesh, placement: ModelPlacement, docX: Double, docY: Double, docZ: Double): MeshPicker.Hit {
        val plate = placement.documentPlacement(mesh).toPlate(docX, docY, docZ)
        return MeshPicker.Hit(
            triangleIndex = 2,
            x = plate[0].toFloat(),
            y = plate[1].toFloat(),
            z = plate[2].toFloat(),
        )
    }

    // --- the tap resolves to the engine face, in document coordinates ---------------------

    @Test
    fun aTapOnATurnedPlacedBoxResolvesToTheFaceUnderIt() {
        val mesh = boxMesh()
        val placement = turnedPlacement()
        val query = FakeQuery(boxFaces())
        val hit = plateHit(mesh, placement, 20.0, 0.0, 6.0)

        val resolved = FaceSelectionFlow.select(
            query = query,
            body = "Body1",
            bodyMesh = mesh,
            displayMesh = mesh,
            placement = placement,
            hit = hit,
            localTriangleIndex = 2,
            orientation = ViewerOrientation(0f, 0f),
        )
        assertNotNull("the tap must resolve to a face", resolved)
        val face = resolved!!.face
        assertEquals("F1:-y", face.name)
        assertEquals(1, face.index)
        assertEquals("Body1", face.body)
        assertEquals(480.0, face.areaMm2, 0.001)
        // The centroid is the engine's, in the document's own coordinates: not the plate's.
        assertEquals(20.0, face.centroid.x, 0.001)
        assertEquals(0.0, face.centroid.y, 0.001)
        assertEquals(6.0, face.centroid.z, 0.001)
        // The outward normal comes from the picked triangle, in document coordinates.
        assertEquals(0.0, face.normal.x, 0.001)
        assertEquals(-1.0, face.normal.y, 0.001)
        assertEquals(0.0, face.normal.z, 0.001)
        // The part was turned a quarter turn: the face whose DOCUMENT normal is -Y is the one the
        // user now sees on their right, and the words must describe the plate, not the document.
        assertEquals("the face on your right", face.viewWords)
        assertTrue("the engine's own selection is set with the same face", resolved.engineAgreed)

        // select.set was called with the face the engine addresses: body, index and point.
        assertEquals(1, query.selectCalls)
        assertEquals("Body1", query.selectedBody)
        assertEquals(1, query.selectedIndex)
        assertNotNull(query.selectedPoint)
        // The point is the tap in the document's coordinates, on the face's plane.
        assertEquals(0.0, query.selectedPoint!!.y, 0.001)
    }

    @Test
    fun theDocumentMappingUndoesThePlacementExactly() {
        val mesh = boxMesh()
        val placement = turnedPlacement()
        val map = placement.documentPlacement(mesh)
        for (point in listOf(
            doubleArrayOf(0.0, 0.0, 0.0),
            doubleArrayOf(40.0, 30.0, 12.0),
            doubleArrayOf(20.0, 0.0, 6.0),
        )) {
            val plate = map.toPlate(point[0], point[1], point[2])!!
            val back = map.toDocument(plate[0], plate[1], plate[2])!!
            assertEquals(point[0], back[0], 1e-9)
            assertEquals(point[1], back[1], 1e-9)
            assertEquals(point[2], back[2], 1e-9)
        }
    }

    @Test
    fun aTapOnTheTopFaceResolvesToTheTopFaceWithTheTopWords() {
        val mesh = boxMesh()
        val placement = ModelPlacement(centerXmm = 110.0, centerYmm = 110.0, baseZmm = 0.0)
        val hit = plateHit(mesh, placement, 20.0, 15.0, 12.0)
        val resolved = FaceSelectionFlow.select(
            query = FakeQuery(boxFaces()),
            body = "Body1",
            bodyMesh = mesh,
            displayMesh = mesh,
            placement = placement,
            hit = hit,
            localTriangleIndex = 10,
            orientation = ViewerOrientation(0f, 0f),
        )!!
        assertEquals("F1:+z", resolved.face.name)
        assertEquals("top", resolved.face.viewWords)
    }

    // --- the tint covers exactly the face ----------------------------------------------

    @Test
    fun theTintCoversTheFacesTrianglesAndNoOthers() {
        val mesh = boxMesh()
        val faces = boxFaces()
        val top = faces.first { it.name == "F1:+z" }
        val triangles = FacePicker.selectedTriangles(mesh, faces, top)
        assertEquals("the top face is the last two triangles of the fixture", listOf(10, 11), triangles.toList())

        // As the renderer receives it: scene indices, with an object offset in front of the body.
        val sceneOffset = 4
        val tint = SelectionTintBuffer(triangleCount = mesh.triangleCount + sceneOffset)
        tint.select(IntArray(triangles.size) { sceneOffset + triangles[it] })
        assertEquals(2, tint.tintedTriangles)
        assertTrue(tint.isTinted(sceneOffset + 10))
        assertTrue(tint.isTinted(sceneOffset + 11))
        for (triangle in 0 until mesh.triangleCount) {
            if (triangle in listOf(10, 11)) continue
            assertFalse("triangle " + triangle + " is not the top face", tint.isTinted(sceneOffset + triangle))
        }
        // Out-of-scene indices are ignored, never wrapped onto another object's triangle.
        tint.select(intArrayOf(sceneOffset + 10, -1, 9999))
        assertEquals(1, tint.tintedTriangles)
        tint.clear()
        assertEquals(0, tint.tintedTriangles)
        assertFalse(tint.hasSelection)
    }

    @Test
    fun theTintBufferIsNotThePaintBuffer() {
        val mesh = boxMesh()
        val palette = Array(6) { index -> floatArrayOf(0.1f * index, 0.2f, 0.3f) }
        val paint = com.tomppi.enderslicer.viewer.PaintColorBuffer(mesh.triangleCount, palette)
        val state = SupportPaintState(enforcerTriangles = setOf(0))
        paint.resync(state)
        val paintedBefore = (0 until mesh.triangleCount * 9).map { paint.buffer.get(it) }

        val tint = SelectionTintBuffer(mesh.triangleCount)
        tint.select(FacePicker.selectedTriangles(mesh, boxFaces(), boxFaces().first { it.name == "F1:+z" }))

        val paintedAfter = (0 until mesh.triangleCount * 9).map { paint.buffer.get(it) }
        assertEquals("selecting a face must not write the paint buffer", paintedBefore, paintedAfter)
        assertTrue("the selection is in its own buffer", tint.hasSelection)
        assertEquals("the paint state is untouched", 1, state.enforcerTriangles.size)
    }

    // --- the prompt block ----------------------------------------------------------------

    @Test
    fun thePromptBlockCarriesEveryNumberTheAgentNeeds() {
        val resolved = FaceSelectionFlow.select(
            query = FakeQuery(boxFaces()),
            body = "Body1",
            bodyMesh = boxMesh(),
            displayMesh = boxMesh(),
            placement = ModelPlacement(centerXmm = 110.0, centerYmm = 110.0, baseZmm = 0.0),
            hit = plateHit(boxMesh(), ModelPlacement(centerXmm = 110.0, centerYmm = 110.0, baseZmm = 0.0), 20.0, 0.0, 6.0),
            localTriangleIndex = 2,
            orientation = ViewerOrientation(0f, 0f),
        )!!
        val block = resolved.face.promptBlock()
        assertTrue(block.startsWith("SELECTED FACE"))
        assertTrue(block.contains("body: Body1"))
        assertTrue(block.contains("face: F1:-y (index 1)"))
        assertTrue(block.contains("surface: planar"))
        assertTrue(block.contains("area: 480 mm²"))
        assertTrue(block.contains("centroid: (20, 0, 6) mm"))
        assertTrue(block.contains("normal: (0, -1, 0)"))
        assertTrue(block.contains("point on the face: ("))
        assertTrue(block.contains("on screen: facing you"))
        // It ends with a blank line, so the user's words follow it unaltered.
        assertTrue(block.endsWith("\n\n"))
    }

    @Test
    fun theHudLineNamesTheFaceAndItsSurface() {
        val resolved = FaceSelectionFlow.select(
            query = FakeQuery(boxFaces()),
            body = "Body1",
            bodyMesh = boxMesh(),
            displayMesh = boxMesh(),
            placement = ModelPlacement(centerXmm = 110.0, centerYmm = 110.0, baseZmm = 0.0),
            hit = plateHit(boxMesh(), ModelPlacement(centerXmm = 110.0, centerYmm = 110.0, baseZmm = 0.0), 20.0, 0.0, 6.0),
            localTriangleIndex = 2,
            orientation = ViewerOrientation(0f, 0f),
        )!!
        val line = resolved.face.hudLine()
        assertTrue(line, line.contains("Body1"))
        assertTrue(line, line.contains("F1:-y (face 1)"))
        assertTrue(line, line.contains("planar"))
        assertTrue(line, line.contains("480 mm²"))
        assertTrue(line, line.contains("facing you"))
    }

    // --- the view-relative words --------------------------------------------------------

    @Test
    fun theWordsFollowTheCameraTheUserIsLookingThrough() {
        val identity = ViewerOrientation(0f, 0f)
        assertEquals("top", FaceViewWords.describe(FacePoint(0.0, 0.0, 1.0), identity))
        assertEquals("bottom", FaceViewWords.describe(FacePoint(0.0, 0.0, -1.0), identity))
        assertEquals("facing you", FaceViewWords.describe(FacePoint(0.0, -1.0, 0.0), identity))
        assertEquals("facing away", FaceViewWords.describe(FacePoint(0.0, 1.0, 0.0), identity))
        assertEquals("the face on your right", FaceViewWords.describe(FacePoint(1.0, 0.0, 0.0), identity))
        assertEquals("the face on your left", FaceViewWords.describe(FacePoint(-1.0, 0.0, 0.0), identity))
    }

    // --- the selection across a repaint -------------------------------------------------

    @Test
    fun aFaceWhoseNameSurvivesIsRetintedAfterTheEdit() {
        val before = FaceSelectionFlow.select(
            query = FakeQuery(boxFaces()),
            body = "Body1",
            bodyMesh = boxMesh(),
            displayMesh = boxMesh(),
            placement = ModelPlacement(centerXmm = 110.0, centerYmm = 110.0, baseZmm = 0.0),
            hit = plateHit(boxMesh(), ModelPlacement(centerXmm = 110.0, centerYmm = 110.0, baseZmm = 0.0), 20.0, 0.0, 6.0),
            localTriangleIndex = 2,
            orientation = ViewerOrientation(0f, 0f),
        )!!
        // The agent made the box taller: the same face name, a bigger face, new triangles.
        val tallerMesh = boxMesh(height = 20.0)
        val tallerFaces = boxFaces(height = 20.0)
        val outcome = FaceSelectionKeeper.reapply(
            previous = before.face,
            body = "Body1",
            bodyMesh = tallerMesh,
            faces = tallerFaces,
            map = ModelPlacement(centerXmm = 110.0, centerYmm = 110.0, baseZmm = 0.0)
                .documentPlacement(tallerMesh),
            orientation = ViewerOrientation(0f, 0f),
        )
        assertTrue("the selection must survive a rename-free rebuild", outcome is FaceReapply.Retinted)
        val retinted = outcome as FaceReapply.Retinted
        assertEquals("F1:-y", retinted.face.name)
        assertEquals("the face's new area is the engine's", 40.0 * 20.0, retinted.face.areaMm2, 0.001)
        assertEquals("the -y face is still the middle two triangles", listOf(2, 3), retinted.triangles.toList())
        assertEquals("facing you", retinted.face.viewWords)
    }

    @Test
    fun aFaceTheEditRemovedIsDroppedWithItsNameInTheSentence() {
        val before = FaceSelectionFlow.select(
            query = FakeQuery(boxFaces()),
            body = "Body1",
            bodyMesh = boxMesh(),
            displayMesh = boxMesh(),
            placement = ModelPlacement(centerXmm = 110.0, centerYmm = 110.0, baseZmm = 0.0),
            hit = plateHit(boxMesh(), ModelPlacement(centerXmm = 110.0, centerYmm = 110.0, baseZmm = 0.0), 20.0, 0.0, 6.0),
            localTriangleIndex = 2,
            orientation = ViewerOrientation(0f, 0f),
        )!!
        val withoutTheFace = boxFaces().filterNot { it.name == "F1:-y" }
        val outcome = FaceSelectionKeeper.reapply(
            previous = before.face,
            body = "Body1",
            bodyMesh = boxMesh(),
            faces = withoutTheFace,
            map = ModelPlacement(centerXmm = 0.0, centerYmm = 0.0, baseZmm = 0.0).documentPlacement(boxMesh()),
            orientation = null,
        )
        assertTrue("a face that is gone must not be kept", outcome is FaceReapply.Dropped)
        val sentence = (outcome as FaceReapply.Dropped).sentence
        assertTrue(sentence, sentence.contains("Face selection cleared"))
        assertTrue("it names the face", sentence.contains("F1:-y"))
        assertTrue("it names the body", sentence.contains("Body1"))
        assertTrue("it says why", sentence.contains("no longer in the document"))
    }

    @Test
    fun aBodyThatWentTakesItsSelectionWithIt() {
        val face = SelectedFace(
            body = "Body1",
            index = 5,
            name = "F1:+z",
            surface = FaceSurface.PLANAR,
            exact = true,
            areaMm2 = 1200.0,
            centroid = FacePoint(20.0, 15.0, 12.0),
            normal = FacePoint(0.0, 0.0, 1.0),
            pickPoint = FacePoint(30.0, 10.0, 12.0),
            cylinderRadiusMm = null,
            viewWords = "top",
        )
        val outcome = FaceSelectionKeeper.reapply(
            previous = face,
            body = "Body2",
            bodyMesh = boxMesh(),
            faces = boxFaces(),
            map = ModelPlacement(centerXmm = 0.0, centerYmm = 0.0, baseZmm = 0.0).documentPlacement(boxMesh()),
            orientation = null,
        )
        assertTrue(outcome is FaceReapply.Dropped)
        assertTrue((outcome as FaceReapply.Dropped).sentence.contains("F1:+z"))
    }

    // --- one tool at a time -------------------------------------------------------------

    @Test
    fun selectModeCannotPaintAndPaintModeCannotSelect() {
        val select = PlateModes().withFaceSelect(true)
        assertTrue(select.picksFaces)
        assertFalse("a selection must never paint", select.paints)

        val paint = select.withPaint(SupportPaintMode.ENFORCER)
        assertFalse("arming paint ends face selection", paint.faceSelect)
        assertTrue(paint.paints)
        assertFalse(paint.picksFaces)

        val backToSelect = paint.withFaceSelect(true)
        assertTrue(backToSelect.picksFaces)
        assertFalse("arming select ends paint", backToSelect.paints)
        assertEquals(SupportPaintMode.NONE, backToSelect.paint)
    }

    // --- the engine's face parsing ------------------------------------------------------

    @Test
    fun theEnginesFaceReportIsParsedWithItsCylinder() {
        val json = org.json.JSONObject(
            """
            {"body":"Body1","faces":[
              {"area":1200.0,"centroid":[20.0,15.0,0.0],"exact":true,"index":0,
               "name":"F1:-z","plane_normal":[0.0,0.0,-1.0],"point":[30.0,10.0,0.0]},
              {"area":313.59,"centroid":[1.82,-2.59,10.0],"exact":true,"index":1,
               "name":"F1:side#1","plane_normal":null,
               "point":[4.83,1.28,1.87],
               "cylinder":{"axis":[0.0,0.0,1.0],"origin":[0.0,0.0,0.0],"radius":5.0}}
            ]}
            """.trimIndent(),
        )
        val faces = EngineFaces.parse(json)
        assertEquals(2, faces.size)
        assertEquals(FaceSurface.PLANAR, faces[0].surface)
        assertEquals(FaceSurface.CYLINDRICAL, faces[1].surface)
        assertEquals(5.0, faces[1].cylinder!!.radiusMm, 0.001)
        // A point on the cylinder's surface matches the cylindrical face, not the plane under it.
        val matched = FacePicker.match(faces, FacePoint(5.0, 0.0, 10.0))
        assertEquals("F1:side#1", matched?.name)
    }

    @Test
    fun aTapBeneathNoFaceIsNotAFace() {
        val mesh = boxMesh()
        val plates = ModelPlacement(centerXmm = 0.0, centerYmm = 0.0, baseZmm = 0.0)
        val hit = MeshPicker.Hit(0, 500f, 500f, 500f)
        val resolved = FaceSelectionFlow.select(
            query = FakeQuery(emptyList()),
            body = "Body1",
            bodyMesh = mesh,
            displayMesh = mesh,
            placement = plates,
            hit = hit,
            localTriangleIndex = 0,
            orientation = null,
        )
        assertNull("no faces means no selection", resolved)
    }
}
