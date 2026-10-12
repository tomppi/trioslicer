package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.engine.GcodeNozzlePath
import com.tomppi.enderslicer.engine.GcodeNozzlePathParser
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the Cura ribbon's vertex stream to the moves it stands for.
 *
 * The ribbon is built a chunk at a time into the GPU ([RibbonChunkStaging] into
 * [RibbonVboStore]), so window i has to be vertex i * 18 and every window has to be the quad of
 * one drawn move: a window built from a neighbour's vertices, or one split across a chunk
 * boundary, draws a long thin triangle bridging two unrelated moves - the slivers in the report.
 * The file below is past [RibbonChunkStaging.DEFAULT_CHUNK_WINDOWS] windows, so the boundaries
 * the staging draws are real ones.
 */
class CuraRibbonLayoutConsistencyTest {

    private fun serpentine(layers: Int = 60, lines: Int = 30, steps: Int = 60): File {
        val file = File.createTempFile("cura-ribbon-serpentine", ".gcode").also { it.deleteOnExit() }
        file.bufferedWriter().use { out ->
            out.append(";FLAVOR:Marlin\n;Layer height: 0.2\nM82\nG92 E0\nG28\nG0 X0 Y0 Z0.2\n")
            var e = 0L
            var y = 0
            for (layer in 0 until layers) {
                if (layer > 0) {
                    out.append("G0 Z").append(String.format(Locale.US, "%.2f", layer * 0.2 + 0.2)).append(" F600\n")
                }
                for (line in 0 until lines) {
                    y++
                    val from = if (line % 2 == 0) 0 else steps
                    val to = if (line % 2 == 0) steps else 0
                    out.append("G0 X" + from + " Y" + y + " F6000\n")
                    for (step in 1..steps) {
                        e += 100
                        out.append("G1 X" + (from + (to - from) * step / steps) + " Y" + y)
                        out.append(" E").append(String.format(Locale.US, "%.3f", e / 1000.0)).append("\n")
                    }
                }
            }
        }
        return file
    }

    @Test
    fun windowStreamsStayInLockstepWithPrefixes() {
        val path = GcodeNozzlePathParser.parse(serpentine(), maxMoves = 24_000)
        val expectedWindows = RibbonPathGeometry.countKind(
            path.moves,
            path.moveCount,
            GcodeNozzlePath.Kind.EXTRUSION.code,
        )
        assertTrue(
            "the test file has to cross the staging's chunk boundaries",
            expectedWindows > RibbonChunkStaging.DEFAULT_CHUNK_WINDOWS,
        )

        // The recorder checks every chunk's shape as it arrives; the counts below check that
        // nothing was dropped, duplicated or reordered between the moves and the windows.
        val recorder = RibbonChunkRecorder(expectedWindows = expectedWindows)
        val builder = CuraRibbonBuilder(path, LAYER_HEIGHT_MM, LINE_WIDTH_MM, FILAMENT_AREA_MM2)
        val staging = RibbonChunkStaging(recorder)
        builder.build(staging)
        staging.finish()

        assertEquals("one window per drawn extrusion move", expectedWindows, recorder.windows)
        assertEquals("build and sink disagree", expectedWindows, builder.extrusionWindows)
        assertEquals("travel lines", builder.travelSegments, recorder.travelSegments)
        assertEquals("final extrusion prefix", expectedWindows, builder.extrusionPrefix[path.moveCount])
        assertEquals("final travel prefix", recorder.travelSegments, builder.travelPrefix[path.moveCount])
        var previous = 0
        for (move in 1..path.moveCount) {
            val current = builder.extrusionPrefix[move]
            assertTrue("prefix " + move + " regressed: " + current + " < " + previous, current >= previous)
            assertTrue("prefix " + move + " exceeds the windows built", current <= expectedWindows)
            previous = current
        }
    }

    @Test
    fun everyWindowIsTheQuadOfItsOwnMoveAcrossChunkBoundaries() {
        val path = GcodeNozzlePathParser.parse(serpentine(), maxMoves = 24_000)
        val expectedWindows = RibbonPathGeometry.countKind(
            path.moves,
            path.moveCount,
            GcodeNozzlePath.Kind.EXTRUSION.code,
        )
        assertTrue(expectedWindows > RibbonChunkStaging.DEFAULT_CHUNK_WINDOWS)

        val recorder = RibbonChunkRecorder(collect = true, expectedWindows = expectedWindows)
        val staging = RibbonChunkStaging(recorder)
        CuraRibbonBuilder(path, LAYER_HEIGHT_MM, LINE_WIDTH_MM, FILAMENT_AREA_MM2).build(staging)
        staging.finish()
        val positions = recorder.ribbonPositionArray()
        assertEquals(expectedWindows * RibbonPathGeometry.WINDOW_VERTICES * 3, positions.size)

        var window = 0
        for (move in 0 until path.moveCount) {
            val offset = move * GcodeNozzlePath.VALUES_PER_MOVE
            if (path.moves[offset + GcodeNozzlePath.KIND] != GcodeNozzlePath.Kind.EXTRUSION.code) continue
            val base = window * RibbonPathGeometry.WINDOW_VERTICES * 3
            // Top-face corners: a/b are the start edge (vertices 0 and 1) and c/d the end edge
            // (vertices 2 and 5), so the move's own centre line comes back out of the stream.
            val sx = (positions[base] + positions[base + 3]) * 0.5f
            val sy = (positions[base + 1] + positions[base + 4]) * 0.5f
            val sz = positions[base + 2]
            val ex = (positions[base + 6] + positions[base + 15]) * 0.5f
            val ey = (positions[base + 7] + positions[base + 16]) * 0.5f
            val ez = positions[base + 8]
            assertEquals("window " + window + " start x", path.moves[offset + GcodeNozzlePath.X1], sx, 1e-3f)
            assertEquals("window " + window + " start y", path.moves[offset + GcodeNozzlePath.Y1], sy, 1e-3f)
            assertEquals("window " + window + " start z", path.moves[offset + GcodeNozzlePath.Z1], sz, 1e-3f)
            assertEquals("window " + window + " end x", path.moves[offset + GcodeNozzlePath.X2], ex, 1e-3f)
            assertEquals("window " + window + " end y", path.moves[offset + GcodeNozzlePath.Y2], ey, 1e-3f)
            assertEquals("window " + window + " end z", path.moves[offset + GcodeNozzlePath.Z2], ez, 1e-3f)
            // Every vertex of the window has to sit inside the drawn move's own footprint: a
            // corner vertex may miter outwards by the bead's half width, never further, and the
            // bead may not reach above the path Z or more than a layer below it.
            val lowZ = min(sz, ez) - MAX_BEAD_HEIGHT_MM - 1e-3f
            val highZ = max(sz, ez) + 1e-3f
            for (vertex in 0 until RibbonPathGeometry.WINDOW_VERTICES) {
                val v = base + vertex * 3
                val distance = segmentDistance2D(positions[v], positions[v + 1], sx, sy, ex, ey)
                assertTrue(
                    "window " + window + " vertex " + vertex + " sits " +
                        String.format(Locale.US, "%.3f", distance) + " mm off its move",
                    distance <= MAX_VERTEX_OFFSET_MM,
                )
                assertTrue(
                    "window " + window + " vertex " + vertex + " z " + positions[v + 2] + " is outside the bead",
                    positions[v + 2] >= lowZ && positions[v + 2] <= highZ,
                )
            }
            window++
        }
        assertEquals("every extrusion move has exactly one window", expectedWindows, window)
    }

    private fun segmentDistance2D(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax
        val vy = by - ay
        val denominator = vx * vx + vy * vy
        if (denominator <= 1e-9f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * vx + (py - ay) * vy) / denominator).coerceIn(0f, 1f)
        return hypot(px - (ax + vx * t), py - (ay + vy * t))
    }

    private companion object {
        const val LAYER_HEIGHT_MM = 0.2f
        const val LINE_WIDTH_MM = 0.4f

        /** The tallest bead the builder will draw, from the parser's layer-height ceiling. */
        const val MAX_BEAD_HEIGHT_MM = 2.0f

        /** Half the widest bead plus the sharpest miter the ribbon builder can produce. */
        const val MAX_VERTEX_OFFSET_MM = 1.0f

        val FILAMENT_AREA_MM2 = (Math.PI * 0.875 * 0.875).toFloat()
    }
}
