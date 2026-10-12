package com.tomppi.enderslicer.viewer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Where a chunk of staged nozzle-path geometry goes: the GPU buffers of the
 * renderer, or a test's meter.
 *
 * The attribute buffers handed over are positioned at 0 and limited to the data
 * of this chunk, so an implementation can copy them with one bulk call and must
 * not keep a reference: the staging area reuses them for the next chunk.
 */
internal interface RibbonChunkSink {
    /** [windows] ribbon windows of [RibbonPathGeometry.WINDOW_VERTICES] vertices each. */
    fun ribbonChunk(
        positions: FloatBuffer,
        normals: FloatBuffer,
        colors: FloatBuffer,
        ambient: FloatBuffer,
        windows: Int,
    )

    /** [segments] two-vertex travel lines. */
    fun travelChunk(positions: FloatBuffer, colors: FloatBuffer, segments: Int)
}

/**
 * Bounded staging for the whole-path ribbon geometry.
 *
 * The preview draws one 18-vertex window per move - 792 bytes a move, 317 MB for
 * the Cura budget's 400,000 and 616 MB for the 778,626 extrusions of the print
 * that crashed the Path tab. Built into one buffer per attribute, that memory is
 * charged against the Java heap: **Android counts direct ByteBuffers there**, and
 * a 16 MB probe on the dev phone fails with the heap's own "target footprint /
 * growth limit" message. The app died on its first 160 MB allocation, which is
 * exactly `extrusionCount * 18 * 3` floats for 778,626 extrusions.
 *
 * So the geometry is staged a chunk at a time: [chunkWindows] windows of reusable
 * direct buffers, handed to the sink - which copies them into GPU memory - and
 * overwritten. Window i always lands at vertex i * 18, so the vertex buffers, the
 * draw calls, the per-move scrub prefixes and the picture are exactly what they
 * were; only the CPU's working set changed, from the whole path to one chunk.
 *
 * Never grow this into one buffer per path. See the note in AGENTS.md.
 */
internal class RibbonChunkStaging(
    private val sink: RibbonChunkSink,
    private val chunkWindows: Int = DEFAULT_CHUNK_WINDOWS,
    private val chunkTravels: Int = DEFAULT_CHUNK_TRAVELS,
) {
    private val positions = StagedFloats(chunkWindows * WINDOW_VERTEX_FLOATS * 3)
    private val normals = StagedFloats(chunkWindows * WINDOW_VERTEX_FLOATS * 3)
    private val colors = StagedFloats(chunkWindows * WINDOW_VERTEX_FLOATS * 4)
    private val ambient = StagedFloats(chunkWindows * WINDOW_VERTEX_FLOATS)
    private val travelPositions = StagedFloats(chunkTravels * 2 * 3)
    private val travelColors = StagedFloats(chunkTravels * 2 * 4)

    private var windows = 0
    private var travels = 0

    /** The bytes this staging area holds on the CPU, whatever the path's length. */
    val bufferBytes: Long = (
        chunkWindows.toLong() * WINDOW_VERTEX_FLOATS * (3 + 3 + 4 + 1) +
            chunkTravels.toLong() * 2 * (3 + 4)
        ) * Float.SIZE_BYTES

    /** Ribbon windows staged into the current chunk. */
    val stagedWindows: Int get() = windows

    /** Travel lines staged into the current chunk. */
    val stagedTravels: Int get() = travels

    /**
     * Adds one ribbon window through [emit], flushing a full chunk first so a
     * window is never split across two uploads.
     */
    fun window(emit: (FloatSink, FloatSink, FloatSink, FloatSink) -> Unit) {
        if (windows == chunkWindows) flushRibbon()
        emit(positions, normals, colors, ambient)
        windows++
    }

    /** Adds one travel line through [emit], flushing a full chunk first. */
    fun travel(emit: (FloatSink, FloatSink) -> Unit) {
        if (travels == chunkTravels) flushTravel()
        emit(travelPositions, travelColors)
        travels++
    }

    /** Hands the last partial chunks to the sink. Call once when the path is built. */
    fun finish() {
        flushRibbon()
        flushTravel()
    }

    private fun flushRibbon() {
        if (windows <= 0) return
        sink.ribbonChunk(
            positions.view(),
            normals.view(),
            colors.view(),
            ambient.view(),
            windows,
        )
        positions.clear()
        normals.clear()
        colors.clear()
        ambient.clear()
        windows = 0
    }

    private fun flushTravel() {
        if (travels <= 0) return
        sink.travelChunk(travelPositions.view(), travelColors.view(), travels)
        travelPositions.clear()
        travelColors.clear()
        travels = 0
    }

    /** A fixed direct buffer written by absolute index, so nothing has to rewind it. */
    private class StagedFloats(capacity: Int) : FloatSink {
        private val buffer: FloatBuffer = ByteBuffer
            .allocateDirect(capacity * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        private var count = 0

        override fun plusAssign(value: Float) {
            buffer.put(count, value)
            count++
        }

        fun view(): FloatBuffer {
            buffer.position(0)
            buffer.limit(count)
            return buffer
        }

        fun clear() {
            count = 0
        }
    }

    companion object {
        private const val WINDOW_VERTEX_FLOATS = RibbonPathGeometry.WINDOW_VERTICES

        /**
         * Windows staged at once: 8,192 windows are 6.5 MB of buffers, small
         * enough to be a rounding error against the heap limit and large enough
         * that a million-move path uploads in 122 chunks.
         */
        const val DEFAULT_CHUNK_WINDOWS = 8_192
        const val DEFAULT_CHUNK_TRAVELS = 8_192
    }
}
