package com.tomppi.enderslicer.viewer

import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The little XYZ marker that sits on the build plate's front-left corner.
 *
 * It is drawn *in* the scene rather than floating over it: three arrows on the
 * bed, in the colours the plate's directions are drawn with, so orbiting shows
 * which way X and Y point the same way the grid does, and the model hides them
 * when the corner is behind the part.
 *
 * The geometry is pure, so a JVM test can pin it down; [ModelSurfaceView] only
 * uploads it and draws it.
 */
internal object AxisTriad {

    /** X, Y and Z, the colours the plate's own directions are drawn with. */
    val COLORS = arrayOf(
        floatArrayOf(0.90f, 0.45f, 0.45f),
        floatArrayOf(0.51f, 0.78f, 0.52f),
        floatArrayOf(0.39f, 0.71f, 0.96f),
    )

    /**
     * Vertices per axis: the shaft, the two lines of its head, then its letter -
     * three lines, the last of which is empty for a letter that only needs two.
     */
    const val VERTICES_PER_AXIS = 12

    /** The head's length and half-width, as fractions of the arm. */
    private const val HEAD_LENGTH = 0.30f
    private const val HEAD_HALF_WIDTH = 0.16f

    /**
     * How far past the tip the letter sits, and how big it is, in arm lengths.
     * The gap clears the head, which reaches [HEAD_LENGTH] of the arm back from
     * the tip: any closer and the letter and the head read as one smudge.
     */
    private const val LABEL_GAP = 0.55f
    private const val LABEL_HALF_HEIGHT = 0.20f

    /**
     * X, Y and Z as three lines each, in a box that runs from -1 to 1 with y up:
     * the letters the marker used to carry as text, drawn into the scene instead
     * so they cannot drift off the arrows they belong to. Read them here the way
     * they have to read on the plate - a Y whose arms start at the bottom is an
     * upside-down Y once it is drawn. X needs two lines, so its third is a
     * zero-length one - GL draws nothing for it.
     */
    internal val LETTERS = arrayOf(
        // X: (-1,-1) to (1,1), then (-1,1) to (1,-1).
        floatArrayOf(-1f, -1f, 1f, 1f, -1f, 1f, 1f, -1f, 0f, 0f, 0f, 0f),
        // Y: both arms at the top, meeting in the middle, stem down.
        floatArrayOf(-1f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, -1f),
        // Z: bar along the top, diagonal, bar along the bottom.
        floatArrayOf(-1f, 1f, 1f, 1f, 1f, 1f, -1f, -1f, -1f, -1f, 1f, -1f),
    )



    internal val AXES = arrayOf(
        floatArrayOf(1f, 0f, 0f),
        floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, 0f, 1f),
    )

    /**
     * The arm length in millimetres that covers [armPx] pixels [viewDepthMm] in
     * front of the camera, so the marker keeps its size on screen however far the
     * plate is zoomed - the bed's scale, not the screen's, decides everything
     * else about it.
     */
    fun armMm(
        viewDepthMm: Float,
        viewportHeightPx: Int,
        fieldOfViewDegrees: Float,
        armPx: Float,
    ): Float {
        val halfHeightMm = max(viewDepthMm, 0.001f) *
            tan(Math.toRadians(fieldOfViewDegrees / 2.0)).toFloat()
        return 2f * halfHeightMm * armPx / max(viewportHeightPx, 1)
    }

    /**
     * The marker's vertices in plate coordinates, seen from [eye].
     *
     * Each head is built across the plane that faces the eye, so it reads as an
     * arrow from wherever the plate is seen from. An axis pointing straight at
     * the eye has no such plane of its own, and borrows a perpendicular from
     * another axis rather than collapsing to a dot.
     *
     * Each axis is followed by its letter, in the same plane: drawn at the tip
     * and scaled with the arm, so it stays the size of the arrow it names.
     */
    fun vertices(
        origin: FloatArray,
        armMm: Float,
        eye: FloatArray,
        cameraUp: FloatArray,
    ): FloatArray {
        val out = FloatArray(AXES.size * VERTICES_PER_AXIS * 3)
        var at = 0
        AXES.forEachIndexed { index, axis ->
            val tip = FloatArray(3) { origin[it] + axis[it] * armMm }
            val perpendicular = facingPerpendicular(axis, tip, eye)
            at = put(out, at, origin)
            at = put(out, at, tip)
            for (side in floatArrayOf(-1f, 1f)) {
                val back = FloatArray(3) {
                    tip[it] -
                        axis[it] * armMm * HEAD_LENGTH +
                        perpendicular[it] * armMm * HEAD_HALF_WIDTH * side
                }
                at = put(out, at, tip)
                at = put(out, at, back)
            }
            at = putGlyph(out, at, LETTERS[index], axis, tip, armMm, eye, cameraUp)
        }
        return out
    }

    /**
     * Writes one letter's three lines past [tip], in the plane that faces [eye]:
     * the same trick the heads use, so a letter is never seen edge-on.
     */
    private fun putGlyph(
        into: FloatArray,
        at: Int,
        letter: FloatArray,
        axis: FloatArray,
        tip: FloatArray,
        armMm: Float,
        eye: FloatArray,
        cameraUp: FloatArray,
    ): Int {
        val centre = FloatArray(3) {
            tip[it] + axis[it] * armMm * (LABEL_GAP + LABEL_HALF_HEIGHT)
        }
        val (across, up) = letterBasis(centre, eye, cameraUp)
        val size = armMm * LABEL_HALF_HEIGHT

        var cursor = at
        var point = 0
        while (point < letter.size) {
            cursor =
                putGlyphPoint(into, cursor, letter[point], letter[point + 1], centre, across, up, size)
            point += 2
        }
        return cursor
    }

    /**
     * The plane one letter is written in: across and up, both facing [eye].
     *
     * The up direction is the *camera's*, projected into that plane - not the
     * plate's. Squaring the letters to the plate put them on their side whenever
     * the eye came close to straight above the bed corner, which is most of the
     * time: the plate's up and the view direction line up there, and the letter
     * plane needs a real up of its own.
     */
    internal fun letterBasis(
        centre: FloatArray,
        eye: FloatArray,
        cameraUp: FloatArray,
    ): Pair<FloatArray, FloatArray> {
        val toEye = unit(FloatArray(3) { eye[it] - centre[it] })
        var up = FloatArray(3) { cameraUp[it] - toEye[it] * dot(cameraUp, toEye) }
        if (length(up) < 1e-3f) up = cross(toEye, floatArrayOf(1f, 0f, 0f))
        val squaredUp = unit(up)
        return unit(cross(squaredUp, toEye)) to squaredUp
    }

    private fun putGlyphPoint(
        into: FloatArray,
        at: Int,
        x: Float,
        y: Float,
        centre: FloatArray,
        across: FloatArray,
        up: FloatArray,
        size: Float,
    ): Int = put(
        into,
        at,
        floatArrayOf(
            centre[0] + across[0] * x * size + up[0] * y * size,
            centre[1] + across[1] * x * size + up[1] * y * size,
            centre[2] + across[2] * x * size + up[2] * y * size,
        ),
    )

    /** A unit vector across [axis] and facing [eye], for the head's two points. */
    private fun facingPerpendicular(axis: FloatArray, tip: FloatArray, eye: FloatArray): FloatArray {
        val toEye = unit(FloatArray(3) { eye[it] - tip[it] })
        var perpendicular = cross(axis, toEye)
        if (length(perpendicular) < 1e-3f) perpendicular = cross(axis, floatArrayOf(0f, 0f, 1f))
        if (length(perpendicular) < 1e-3f) perpendicular = cross(axis, floatArrayOf(0f, 1f, 0f))
        return unit(perpendicular)
    }

    private fun put(into: FloatArray, at: Int, point: FloatArray): Int {
        into[at] = point[0]
        into[at + 1] = point[1]
        into[at + 2] = point[2]
        return at + 3
    }

    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun length(v: FloatArray): Float = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun unit(v: FloatArray): FloatArray {
        val l = length(v)
        if (l < 1e-6f) return floatArrayOf(0f, 0f, 1f)
        return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }
}
