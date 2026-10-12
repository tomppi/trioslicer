package com.tomppi.enderslicer.viewer

import android.view.MotionEvent
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.floor
import kotlin.math.sqrt

/** The ribbon's speed ramp: hue follows the move's speed, at this saturation and value. */
internal const val RIBBON_SATURATION = 0.62f
internal const val RIBBON_VALUE = 0.92f

/**
 * The pieces the two nozzle-path previews share.
 *
 * CuraEngine's preview and the PrusaSlicer/OrcaSlicer one draw different ribbons
 * from different parsers, but they frame the camera, answer taps and talk to the
 * same three shader programs the same way. These were byte-identical private
 * methods in both views; keeping one copy is what stops the two previews from
 * drifting apart in the parts that are not engine-specific.
 */

/** The average x of every pointer down, which is what a two-finger gesture orbits around. */
internal fun pointerFocusX(event: MotionEvent): Float =
    (0 until event.pointerCount).sumOf { event.getX(it).toDouble() }.toFloat() / event.pointerCount

/** The average y of every pointer down. */
internal fun pointerFocusY(event: MotionEvent): Float =
    (0 until event.pointerCount).sumOf { event.getY(it).toDouble() }.toFloat() / event.pointerCount

/** Squared distance from (px, py) to the segment (ax, ay)-(bx, by), for tap picking. */
internal fun segmentDistanceSq(
    px: Float, py: Float,
    ax: Float, ay: Float,
    bx: Float, by: Float,
): Float {
    val vx = bx - ax
    val vy = by - ay
    val denominator = vx * vx + vy * vy
    if (denominator <= 1e-8f) {
        val dx = px - ax
        val dy = py - ay
        return dx * dx + dy * dy
    }
    val t = ((px - ax) * vx + (py - ay) * vy) / denominator
    val clamped = t.coerceIn(0f, 1f)
    val cx = ax + vx * clamped
    val cy = ay + vy * clamped
    val dx = px - cx
    val dy = py - cy
    return dx * dx + dy * dy
}

/** The plate grid's spacing for a plate [span] millimetres across. */
internal fun gridStep(span: Float): Float = when {
    span <= 40f -> 5f
    span <= 100f -> 10f
    span <= 250f -> 20f
    else -> 50f
}

/** [value] in degrees folded into -180..180, so orbiting never winds past a full turn. */
internal fun wrapDegrees(value: Float): Float {
    var result = value % 360f
    if (result < -180f) result += 360f
    if (result > 180f) result -= 360f
    return result
}

/** A direct float buffer the GL calls can be handed without a copy. */
internal fun allocate(floatCount: Int): FloatBuffer = ByteBuffer
    .allocateDirect(floatCount * Float.SIZE_BYTES)
    .order(ByteOrder.nativeOrder())
    .asFloatBuffer()

/**
 * [hue] in degrees as an RGBA float array at [saturation] and [value], which is
 * how both previews colour a move by its speed.
 */
internal fun hsv(hue: Float, saturation: Float, value: Float, alpha: Float): FloatArray {
    val h = ((hue % 360f) + 360f) % 360f / 60f
    val sector = floor(h).toInt()
    val fraction = h - sector
    val p = value * (1f - saturation)
    val q = value * (1f - saturation * fraction)
    val t = value * (1f - saturation * (1f - fraction))
    val rgb = when (sector) {
        0 -> floatArrayOf(value, t, p)
        1 -> floatArrayOf(q, value, p)
        2 -> floatArrayOf(p, value, t)
        3 -> floatArrayOf(p, q, value)
        4 -> floatArrayOf(t, p, value)
        else -> floatArrayOf(value, p, q)
    }
    return floatArrayOf(rgb[0], rgb[1], rgb[2], alpha)
}

/**
 * The widest line this device's GL implementation will draw, or 1 when it does
 * not answer: the ribbon falls back to the narrowest width rather than asking
 * for a width the driver would clamp or ignore.
 */
internal fun queryMaxLineWidth(): Float {
    val range = FloatArray(2)
    GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, range, 0)
    return range[1].takeIf { it.isFinite() && it > 0f } ?: 1f
}

/**
 * The nozzle path's geometry, in GPU memory.
 *
 * The ribbon costs about 800 bytes a move - 616 MB for the 778,626 extrusion
 * moves of the print that crashed the Path tab - and it used to be built into one
 * direct buffer per attribute, which Android charges against the Java heap. The
 * store is filled one bounded chunk at a time through [RibbonChunkSink] as the
 * geometry is built, so the CPU only ever holds a chunk and the whole path lives
 * in VRAM afterwards: where a preview of this size has to live, and what the user
 * assumed it was doing already.
 *
 * Six buffers, the same layout the draw calls always used:
 * 0/1/2/3 ribbon positions, normals, colors, ambient; 4/5 travel positions, colors.
 */
internal class RibbonVboStore : RibbonChunkSink {
    var ids = IntArray(0)
        private set

    private var offsets = IntArray(VBO_COUNT)

    /** Ribbon windows uploaded so far. */
    var ribbonWindows = 0
        private set

    /** Travel lines uploaded so far. */
    var travelSegments = 0
        private set

    val ready: Boolean get() = ids.size == VBO_COUNT && ids[0] != 0

    val ribbonVertexCount: Int get() = ribbonWindows * RibbonPathGeometry.WINDOW_VERTICES

    val travelVertexCount: Int get() = travelSegments * 2

    /**
     * Allocates the six buffers at their exact final size and empties them. The
     * parsed path states how many windows and travel lines it will emit, so
     * nothing here ever has to grow - and a refusal is reported rather than
     * drawn as a truncated path.
     */
    fun allocate(ribbonWindows: Int, travelSegments: Int): Boolean {
        release()
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            // A stale error from earlier in the frame must not be read as this
            // allocation's failure.
        }
        val generated = IntArray(VBO_COUNT)
        GLES20.glGenBuffers(VBO_COUNT, generated, 0)
        for (index in 0 until VBO_COUNT) {
            if (generated[index] == 0) {
                GLES20.glDeleteBuffers(VBO_COUNT, generated, 0)
                return false
            }
            val floats = floatsFor(index, ribbonWindows, travelSegments)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, generated[index])
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                floats * Float.SIZE_BYTES,
                null,
                GLES20.GL_STATIC_DRAW,
            )
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            GLES20.glDeleteBuffers(VBO_COUNT, generated, 0)
            return false
        }
        ids = generated
        offsets = IntArray(VBO_COUNT)
        this.ribbonWindows = 0
        this.travelSegments = 0
        return true
    }

    override fun ribbonChunk(
        positions: FloatBuffer,
        normals: FloatBuffer,
        colors: FloatBuffer,
        ambient: FloatBuffer,
        windows: Int,
    ) {
        val vertices = windows * RibbonPathGeometry.WINDOW_VERTICES
        upload(0, positions, vertices * 3)
        upload(1, normals, vertices * 3)
        upload(2, colors, vertices * 4)
        upload(3, ambient, vertices)
        ribbonWindows += windows
    }

    override fun travelChunk(positions: FloatBuffer, colors: FloatBuffer, segments: Int) {
        upload(4, positions, segments * 2 * 3)
        upload(5, colors, segments * 2 * 4)
        travelSegments += segments
    }

    /**
     * Empties the counters so the buffers can take another band.
     *
     * Banded previews keep the buffers allocated at their capacity and overwrite them from
     * offset zero for each new band; the GPU allocation happens once per path, not per scrub.
     */
    fun reuse() {
        offsets = IntArray(VBO_COUNT)
        ribbonWindows = 0
        travelSegments = 0
    }

    /** Drops the ids of a context that is gone, without touching the new one. */
    fun forget() {
        ids = IntArray(0)
        offsets = IntArray(VBO_COUNT)
        ribbonWindows = 0
        travelSegments = 0
    }

    fun release() {
        if (ids.isNotEmpty()) GLES20.glDeleteBuffers(ids.size, ids, 0)
        forget()
    }

    private fun upload(index: Int, data: FloatBuffer, floats: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[index])
        GLES20.glBufferSubData(
            GLES20.GL_ARRAY_BUFFER,
            offsets[index] * Float.SIZE_BYTES,
            floats * Float.SIZE_BYTES,
            data,
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        offsets[index] += floats
    }

    private fun floatsFor(index: Int, ribbonWindows: Int, travelSegments: Int): Int {
        val vertices = ribbonWindows * RibbonPathGeometry.WINDOW_VERTICES
        return when (index) {
            0, 1 -> vertices * 3
            2 -> vertices * 4
            3 -> vertices
            4 -> travelSegments * 2 * 3
            else -> travelSegments * 2 * 4
        }
    }

    private companion object {
        const val VBO_COUNT = 6
    }
}

/**
 * The drawing half of a nozzle-path preview.
 *
 * CuraEngine's ribbon and the PrusaSlicer/OrcaSlicer one are built differently, but
 * they are handed to the same three shader programs through the same matrices, and
 * every call that does it was byte-identical in the two renderers. What differs -
 * how the ribbon is built, how the camera is framed, how a tap picks a move - stays
 * in the subclass.
 */
internal abstract class NozzlePathRendererBase : GLSurfaceView.Renderer {
    protected var litProgram = 0
    protected var colorProgram = 0
    protected var solidProgram = 0
    protected val pathGeometry = RibbonVboStore()
    protected var maxLineWidth = 1f
    protected val scene = FloatArray(16)
    protected val mvp = FloatArray(16)
    protected val pickIn = FloatArray(4)
    protected val pickOut = FloatArray(4)

    protected val KEY_LIGHT = normalize3(0.52f, -0.58f, 0.63f)
    protected val FILL_LIGHT = normalize3(-0.62f, 0.30f, 0.55f)
    protected val VIEW_EYE_Y = -0.85f
    protected val VIEW_EYE_Z = 0.53f

    /** The largest band this process has managed to put in the GPU, remembered for the session. */
    private var sessionCeiling = Int.MAX_VALUE

    /**
     * Allocates the six vertex buffers at [windows] windows, halving down to [floor] when the
     * driver refuses. Graphics memory is not free RAM and a driver may refuse a large
     * allocation, so the number asked for is a hope and the number granted is the fact; what
     * fitted is remembered so the next path starts there.
     *
     * Returns the number of windows granted, or 0 when even the floor was refused.
     */
    protected fun allocateAdaptiveWindows(windows: Int, travels: Int, floor: Int): Int {
        for (attempt in BandAllocator.attempts(windows, floor, sessionCeiling)) {
            if (pathGeometry.allocate(attempt, BandAllocator.travels(travels, windows, attempt))) {
                sessionCeiling = kotlin.math.min(sessionCeiling, attempt)
                return attempt
            }
        }
        return 0
    }

    /** The travel lines that come with [granted] of the [asked] windows, never none. */
    protected fun scaledTravels(travels: Int, asked: Int, granted: Int): Int =
        BandAllocator.travels(travels, asked, granted)

    protected fun lineWidth(width: Float) {
        GLES20.glLineWidth(width.coerceAtMost(maxLineWidth))
    }

    protected fun project(source: FloatArray, xOffset: Int, yOffset: Int, zOffset: Int) {
        pickIn[0] = source[xOffset]
        pickIn[1] = source[yOffset]
        pickIn[2] = source[zOffset]
        pickIn[3] = 1f
        Matrix.multiplyMV(pickOut, 0, mvp, 0, pickIn, 0)
    }

    protected fun drawLitTrianglesVbo(vertexCount: Int) {
        if (vertexCount <= 0) return
        GLES20.glUseProgram(litProgram)
        val position = GLES20.glGetAttribLocation(litProgram, "aPosition")
        val normal = GLES20.glGetAttribLocation(litProgram, "aNormal")
        val color = GLES20.glGetAttribLocation(litProgram, "aColor")
        val ambientLoc = GLES20.glGetAttribLocation(litProgram, "aAmbient")
        val matrix = GLES20.glGetUniformLocation(litProgram, "uMvpMatrix")
        val sceneMatrix = GLES20.glGetUniformLocation(litProgram, "uSceneMatrix")
        val keyDir = GLES20.glGetUniformLocation(litProgram, "uKeyDir")
        val fillDir = GLES20.glGetUniformLocation(litProgram, "uFillDir")
        val viewDir = GLES20.glGetUniformLocation(litProgram, "uViewDir")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pathGeometry.ids[0])
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pathGeometry.ids[1])
        GLES20.glEnableVertexAttribArray(normal)
        GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pathGeometry.ids[2])
        GLES20.glEnableVertexAttribArray(color)
        GLES20.glVertexAttribPointer(color, 4, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pathGeometry.ids[3])
        GLES20.glEnableVertexAttribArray(ambientLoc)
        GLES20.glVertexAttribPointer(ambientLoc, 1, GLES20.GL_FLOAT, false, 4, 0)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(sceneMatrix, 1, false, scene, 0)
        GLES20.glUniform3f(keyDir, KEY_LIGHT[0], KEY_LIGHT[1], KEY_LIGHT[2])
        GLES20.glUniform3f(fillDir, FILL_LIGHT[0], FILL_LIGHT[1], FILL_LIGHT[2])
        GLES20.glUniform3f(viewDir, 0f, -VIEW_EYE_Y, VIEW_EYE_Z)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(normal)
        GLES20.glDisableVertexAttribArray(color)
        GLES20.glDisableVertexAttribArray(ambientLoc)
    }

    protected fun drawColoredLinesVbo(vertexCount: Int, width: Float) {
        if (vertexCount <= 0) return
        GLES20.glUseProgram(colorProgram)
        val position = GLES20.glGetAttribLocation(colorProgram, "aPosition")
        val color = GLES20.glGetAttribLocation(colorProgram, "aColor")
        val matrix = GLES20.glGetUniformLocation(colorProgram, "uMvpMatrix")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pathGeometry.ids[4])
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pathGeometry.ids[5])
        GLES20.glEnableVertexAttribArray(color)
        GLES20.glVertexAttribPointer(color, 4, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        lineWidth(width)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount)
        GLES20.glLineWidth(1f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(color)
    }

    protected fun drawSolidLines(
        buffer: FloatBuffer?,
        vertexCount: Int,
        width: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ) {
        if (buffer == null || vertexCount <= 0) return
        GLES20.glUseProgram(solidProgram)
        val position = GLES20.glGetAttribLocation(solidProgram, "aPosition")
        val matrix = GLES20.glGetUniformLocation(solidProgram, "uMvpMatrix")
        val color = GLES20.glGetUniformLocation(solidProgram, "uColor")
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 12, buffer)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        GLES20.glUniform4f(color, red, green, blue, alpha)
        lineWidth(width)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount)
        GLES20.glLineWidth(1f)
        GLES20.glDisableVertexAttribArray(position)
    }
}

/** [x], [y], [z] scaled to unit length; the light directions and the eye vector. */
private fun normalize3(x: Float, y: Float, z: Float): FloatArray {
    val length = sqrt(x * x + y * y + z * z)
    return floatArrayOf(x / length, y / length, z / length)
}
