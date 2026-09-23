package com.tomppi.enderslicer.annotation

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises locked annotation geometry for the modelling side.
 *
 * Three things travel together, and each earns its place:
 *
 *  - the **points**, in model millimetres, which is what lets a consumer act on
 *    the annotation (select those faces, move that region, honour that
 *    dimension) rather than guess at it;
 *  - the **camera**, so the exact viewpoint can be reproduced and any ambiguity
 *    about what a stroke meant can be resolved against the same view;
 *  - the **anchor** per point, so a surface point the geometry confirms is never
 *    confused with a plane point whose depth is only a convention.
 *
 * Screen coordinates are deliberately absent: they are presentation, not
 * geometry, and they are meaningless without the camera that produced them.
 */
object AnnotationCodec {

    const val VERSION = 1

    /** Bounding-box size in millimetres, so a consumer has the model's scale. */
    data class ModelSummary(
        val name: String,
        val triangleCount: Int,
        val sizeXMm: Float,
        val sizeYMm: Float,
        val sizeZMm: Float,
    )

    fun encode(
        state: AnnotationState,
        model: ModelSummary,
        camera: JSONObject? = null,
    ): JSONObject {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put(
            "model",
            JSONObject()
                .put("name", model.name)
                .put("triangles", model.triangleCount)
                .put(
                    "size_mm",
                    JSONArray().put(model.sizeXMm.toDouble())
                        .put(model.sizeYMm.toDouble())
                        .put(model.sizeZMm.toDouble()),
                ),
        )
        if (camera != null) root.put("camera", camera)

        val chains = JSONArray()
        for (chain in state.chains) {
            if (!chain.isComplete()) continue
            val points = JSONArray()
            for (point in chain.points) {
                val entry = JSONObject()
                    .put(
                        "p",
                        JSONArray().put(point.position.x.toDouble())
                            .put(point.position.y.toDouble())
                            .put(point.position.z.toDouble()),
                    )
                    .put("anchor", if (point.anchor == AnnotationAnchor.SURFACE) "surface" else "plane")
                point.faceIndex?.let { entry.put("face", it) }
                points.put(entry)
            }
            chains.put(
                JSONObject()
                    .put("id", chain.id)
                    .put("kind", chain.kind.name.lowercase())
                    .put("closed", chain.closed)
                    .put("length_mm", chain.lengthMm().toDouble())
                    .put("points", points),
            )
        }
        root.put("chains", chains)
        return root
    }

    /** Encodes the viewer's matrices so the receiving side can reproduce the view. */
    fun cameraBlock(
        view: FloatArray,
        projection: FloatArray,
        viewportWidth: Int,
        viewportHeight: Int,
    ): JSONObject {
        require(view.size == 16) { "View matrix must have 16 elements" }
        require(projection.size == 16) { "Projection matrix must have 16 elements" }
        return JSONObject()
            .put("view", toArray(view))
            .put("projection", toArray(projection))
            .put(
                "viewport",
                JSONArray().put(viewportWidth).put(viewportHeight),
            )
    }

    /**
     * Rebuilds locked geometry from an [encode] document.
     *
     * Incomplete chains are dropped on the way in as well as on the way out, so
     * a half-drawn region can never round-trip into something a consumer would
     * act on.
     */
    fun decode(document: JSONObject): AnnotationState {
        val state = AnnotationState()
        val chains = document.optJSONArray("chains") ?: return state
        val restored = mutableListOf<AnnotationChain>()
        for (index in 0 until chains.length()) {
            val entry = chains.optJSONObject(index) ?: continue
            val kind = when (entry.optString("kind")) {
                "measure" -> AnnotationKind.MEASURE
                "region" -> AnnotationKind.REGION
                else -> AnnotationKind.PATH
            }
            val pointsArray = entry.optJSONArray("points") ?: continue
            val points = mutableListOf<AnnotationPoint>()
            for (p in 0 until pointsArray.length()) {
                val item = pointsArray.optJSONObject(p) ?: continue
                val position = item.optJSONArray("p") ?: continue
                if (position.length() < 3) continue
                points += AnnotationPoint(
                    position = Point3(
                        position.getDouble(0).toFloat(),
                        position.getDouble(1).toFloat(),
                        position.getDouble(2).toFloat(),
                    ),
                    anchor = if (item.optString("anchor") == "surface") {
                        AnnotationAnchor.SURFACE
                    } else {
                        AnnotationAnchor.PLANE
                    },
                    faceIndex = if (item.has("face")) item.optInt("face") else null,
                )
            }
            if (points.isEmpty()) continue
            val chain = AnnotationChain(
                id = entry.optInt("id", index + 1),
                kind = kind,
                points = points,
                closed = entry.optBoolean("closed", false),
            )
            // Same rule as encode: geometry that cannot be acted on does not
            // travel, so a hand-written or older document cannot smuggle in a
            // half-drawn region either.
            if (!chain.isComplete()) continue
            restored += chain
        }
        state.restore(restored)
        return state
    }

    private fun toArray(values: FloatArray): JSONArray {
        val array = JSONArray()
        for (value in values) array.put(value.toDouble())
        return array
    }
}
