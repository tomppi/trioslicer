package com.tomppi.enderslicer.viewer

import java.nio.FloatBuffer
import kotlin.math.max

/**
 * A test's [RibbonChunkSink]: it checks every chunk against the window contract
 * (an 18-vertex window per move, 3/3/4/1 floats a vertex for the ribbon, two
 * 7-float vertices per travel) and, when asked, keeps the vertex stream so the
 * geometry can be read back the way the shaders would have seen it.
 */
internal class RibbonChunkRecorder(
    private val collect: Boolean = false,
    expectedWindows: Int = 0,
    expectedTravels: Int = 0,
) : RibbonChunkSink {
    private val ribbonPositions = DirectFloatSink(if (collect) max(expectedWindows * WINDOW * 3, 16) else 16)
    private val ribbonNormals = DirectFloatSink(if (collect) max(expectedWindows * WINDOW * 3, 16) else 16)
    private val ribbonColors = DirectFloatSink(if (collect) max(expectedWindows * WINDOW * 4, 16) else 16)
    private val ribbonAmbient = DirectFloatSink(if (collect) max(expectedWindows * WINDOW, 16) else 16)
    private val travelPositions = DirectFloatSink(if (collect) max(expectedTravels * 2 * 3, 16) else 16)
    private val travelColors = DirectFloatSink(if (collect) max(expectedTravels * 2 * 4, 16) else 16)

    /** Ribbon windows received. */
    var windows = 0
        private set

    /** Travel lines received. */
    var travelSegments = 0
        private set

    override fun ribbonChunk(
        positions: FloatBuffer,
        normals: FloatBuffer,
        colors: FloatBuffer,
        ambient: FloatBuffer,
        windows: Int,
    ) {
        val vertices = windows * WINDOW
        check(positions.remaining() == vertices * 3) { "positions chunk is not one window per move" }
        check(normals.remaining() == vertices * 3) { "normals chunk is not one window per move" }
        check(colors.remaining() == vertices * 4) { "colors chunk is not one window per move" }
        check(ambient.remaining() == vertices) { "ambient chunk is not one window per move" }
        if (collect) {
            append(ribbonPositions, positions, vertices * 3)
            append(ribbonNormals, normals, vertices * 3)
            append(ribbonColors, colors, vertices * 4)
            append(ribbonAmbient, ambient, vertices)
        }
        this.windows += windows
    }

    override fun travelChunk(positions: FloatBuffer, colors: FloatBuffer, segments: Int) {
        check(positions.remaining() == segments * 2 * 3) { "travel positions chunk is not two vertices a line" }
        check(colors.remaining() == segments * 2 * 4) { "travel colors chunk is not two vertices a line" }
        if (collect) {
            append(travelPositions, positions, segments * 2 * 3)
            append(travelColors, colors, segments * 2 * 4)
        }
        travelSegments += segments
    }

    fun ribbonPositionArray(): FloatArray = ribbonPositions.toFloatArray()

    fun ribbonNormalArray(): FloatArray = ribbonNormals.toFloatArray()

    fun ribbonColorArray(): FloatArray = ribbonColors.toFloatArray()

    fun ribbonAmbientArray(): FloatArray = ribbonAmbient.toFloatArray()

    private fun append(sink: DirectFloatSink, data: FloatBuffer, count: Int) {
        for (index in 0 until count) sink += data.get(index)
    }

    private companion object {
        const val WINDOW = RibbonPathGeometry.WINDOW_VERTICES
    }
}
