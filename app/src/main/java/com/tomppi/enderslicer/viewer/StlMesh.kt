package com.tomppi.enderslicer.viewer

import java.nio.FloatBuffer

data class MeshBounds(
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float,
) {
    val width: Float get() = maxX - minX
    val depth: Float get() = maxY - minY
    val height: Float get() = maxZ - minZ
    val centerX: Float get() = (minX + maxX) * 0.5f
    val centerY: Float get() = (minY + maxY) * 0.5f
    val centerZ: Float get() = (minZ + maxZ) * 0.5f
}

/**
 * Linear model transform plus its final translation in normal build-plate
 * coordinates. CuraEngine can apply the linear part while loading the original
 * STL and the translation during MeshGroup finalization, avoiding an
 * intermediate transformed-Float STL.
 */
data class StlSliceTransform(
    val linear: List<Double>,
    val translationXmm: Double,
    val translationYmm: Double,
    val translationZmm: Double,
) {
    init {
        require(linear.size == 9) { "Slice transform must contain nine linear values" }
        require(linear.all(Double::isFinite)) { "Slice transform contains a non-finite linear value" }
        require(listOf(translationXmm, translationYmm, translationZmm).all(Double::isFinite)) {
            "Slice transform contains a non-finite translation"
        }
    }
}

/**
 * Meshes with at least this many triangles keep their vertex data in a direct
 * native FloatBuffer instead of the Java heap. 200k triangles is about 14 MB
 * of vertex floats; smaller meshes keep the fast array path.
 */
internal const val OFF_HEAP_MIN_TRIANGLES = 200_000

/**
 * Vertex storage that keeps large meshes off the Java heap.
 *
 * Small meshes keep the original FloatArray (fast indexed access, identical
 * behaviour); large meshes are parsed into a direct native FloatBuffer so a
 * multi-million-triangle model no longer counts against the app Java heap
 * cap. Both paths expose the same index/size API, so every consumer (viewer,
 * mesh picker, transforms, STL writer, envelope checks) treats them alike.
 */
class VertexData private constructor(
    private val array: FloatArray?,
    private val direct: FloatBuffer?,
) {
    val size: Int
        get() = array?.size ?: direct!!.capacity()

    operator fun get(index: Int): Float = array?.get(index) ?: direct!!.get(index)

    /** The heap-backed view when this instance is array-backed. */
    fun arrayOrNull(): FloatArray? = array

    /** The direct native buffer when this instance is off-heap. */
    fun directOrNull(): FloatBuffer? = direct

    /** Bytes held outside the Java heap (0 when array-backed). */
    val nativeBytes: Long
        get() = direct?.let { it.capacity().toLong() * Float.SIZE_BYTES } ?: 0L

    fun toFloatArray(): FloatArray = array ?: FloatArray(direct!!.capacity()) { direct!!.get(it) }

    companion object {
        fun fromArray(values: FloatArray): VertexData = VertexData(values, null)
        fun fromDirect(values: FloatBuffer): VertexData = VertexData(null, values)
    }
}

data class StlMesh(
    val displayName: String,
    val interleavedVertices: VertexData,
    val triangleCount: Int,
    val bounds: MeshBounds,
    /** Original untransformed STL vertices retained for precision slicing. */
    val slicingSourceInterleavedVertices: VertexData? = null,
    /** Transform that maps the original vertices to the displayed placement. */
    val slicingTransform: StlSliceTransform? = null,
    /**
     * Double-precision origin removed before ASCII coordinates are stored as
     * floats. Scene transforms add its linear image back into their translation.
     */
    val sourceOriginXmm: Double = 0.0,
    val sourceOriginYmm: Double = 0.0,
    val sourceOriginZmm: Double = 0.0,
) {
    init {
        require(listOf(sourceOriginXmm, sourceOriginYmm, sourceOriginZmm).all(Double::isFinite)) {
            "STL source origin contains a non-finite value"
        }
    }
}
