package com.tomppi.enderslicer.annotation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationCodecTest {

    private val summary = AnnotationCodec.ModelSummary(
        name = "lab-1.stl",
        triangleCount = 280_344,
        sizeXMm = 69.8f,
        sizeYMm = 110.6f,
        sizeZMm = 117.8f,
    )

    private fun measuredState(): AnnotationState {
        val state = AnnotationState()
        state.kind = AnnotationKind.MEASURE
        state.tap(Point3(0f, 0f, 0f), AnnotationAnchor.SURFACE, faceIndex = 5)
        state.tap(Point3(30f, 40f, 0f), AnnotationAnchor.PLANE)
        state.lockSeries()
        return state
    }

    @Test
    fun roundTripsLockedGeometry() {
        val document = AnnotationCodec.encode(measuredState(), summary)
        assertEquals(AnnotationCodec.VERSION, document.getInt("version"))
        assertEquals("lab-1.stl", document.getJSONObject("model").getString("name"))
        assertEquals(280_344, document.getJSONObject("model").getInt("triangles"))

        val decoded = AnnotationCodec.decode(document)
        assertEquals(1, decoded.chains.size)
        val chain = decoded.chains[0]
        assertEquals(AnnotationKind.MEASURE, chain.kind)
        assertEquals(2, chain.points.size)
        assertEquals(50f, chain.lengthMm(), 1e-2f)

        // Anchor fidelity matters: a plane point must not come back as surface.
        val first = chain.points[0]
        assertEquals(AnnotationAnchor.SURFACE, first.anchor)
        assertEquals(5, first.faceIndex)
        assertEquals(0f, first.position.x, 1e-4f)

        val second = chain.points[1]
        assertEquals(AnnotationAnchor.PLANE, second.anchor)
        assertNull(second.faceIndex)
        assertEquals(30f, second.position.x, 1e-4f)
        assertEquals(40f, second.position.y, 1e-4f)
    }

    @Test
    fun incompleteChainsAreNotEncoded() {
        val state = AnnotationState()
        state.tap(Point3(1f, 2f, 3f), AnnotationAnchor.PLANE)
        // A single-point series cannot mean anything, so it must not travel.
        val document = AnnotationCodec.encode(state, summary)
        assertEquals(0, document.getJSONArray("chains").length())
    }

    @Test
    fun incompleteChainsAreNotDecodedEither() {
        val document = JSONObject()
            .put("version", 1)
            .put(
                "chains",
                JSONArray().put(
                    JSONObject()
                        .put("id", 1)
                        .put("kind", "path")
                        .put("points", JSONArray().put(JSONObject().put("p", JSONArray().put(1).put(2).put(3)))),
                ),
            )
        assertTrue(AnnotationCodec.decode(document).chains.isEmpty())
    }

    @Test
    fun cameraBlockCarriesBothMatricesAndTheViewport() {
        val view = FloatArray(16) { it.toFloat() }
        val projection = FloatArray(16) { (it * 2).toFloat() }
        val block = AnnotationCodec.cameraBlock(view, projection, 1080, 2340)

        assertEquals(16, block.getJSONArray("view").length())
        assertEquals(16, block.getJSONArray("projection").length())
        assertEquals(1080, block.getJSONArray("viewport").getInt(0))
        assertEquals(2340, block.getJSONArray("viewport").getInt(1))
        assertEquals(5.0, block.getJSONArray("view").getDouble(5), 1e-9)

        val document = AnnotationCodec.encode(measuredState(), summary, block)
        assertTrue(document.has("camera"))
        val decodedChains = AnnotationCodec.decode(document)
        assertEquals("the camera block must not disturb the chains", 1, decodedChains.chains.size)
        assertEquals(16, document.getJSONObject("camera").getJSONArray("view").length())
    }

    @Test
    fun malformedEntriesAreSkippedRatherThanThrowing() {
        val good = JSONObject()
            .put("p", JSONArray().put(0).put(0).put(0))
            .put("anchor", "surface")
            .put("face", 2)
        val shortPosition = JSONObject().put("p", JSONArray().put(1).put(2))
        val document = JSONObject()
            .put("version", 1)
            .put(
                "chains",
                JSONArray().put(
                    JSONObject()
                        .put("id", 1)
                        .put("kind", "path")
                        .put("points", JSONArray().put(good).put(shortPosition).put(good)),
                ),
            )
        val decoded = AnnotationCodec.decode(document)
        assertEquals(1, decoded.chains.size)
        assertEquals("the two-element point is dropped", 2, decoded.chains[0].points.size)
    }

    @Test
    fun emptyStateEncodesAnEmptyChainList() {
        val document = AnnotationCodec.encode(AnnotationState(), summary)
        assertEquals(0, document.getJSONArray("chains").length())
        assertTrue(AnnotationCodec.decode(document).chains.isEmpty())
    }
}
