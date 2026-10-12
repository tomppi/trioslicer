package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.engine.GcodeNozzlePath
import com.tomppi.enderslicer.engine.GcodeNozzlePathParser
import com.tomppi.enderslicer.engine.PrusaNozzlePath
import com.tomppi.enderslicer.engine.PrusaNozzlePathParser
import java.io.File
import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Path tab is allowed to cost the Java heap.
 *
 * The screen died on a big print with "Failed to allocate a 168183235 byte
 * allocation": one direct buffer of the ribbon geometry, which Android charges
 * against the Java heap - 168,183,235 is ART's 19 bytes of overhead plus
 * 778,626 extrusion moves x 18 vertices x 3 floats, the first of four buffers
 * that together needed 616 MB. A test that only builds a small path cannot see
 * that, so this one builds a 900,000-move file and measures: the live heap is
 * settled and read around the parse and the build, the direct buffers are read
 * from the JVM's own buffer-pool bean on every chunk the geometry sink receives,
 * and the staging area's size is a stated fact rather than a hope.
 *
 * The prose rules this protects are in AGENTS.md: every move is drawn, the
 * parser's budget merges rather than drops, and the ribbon is never held whole.
 */
class NozzlePathMemoryTest {

    @Test
    fun nineHundredThousandMovesParseAndBuildInsideTheHeapFigure() {
        val file = curaGcode(CURA_EXTRUSIONS)
        settle()
        val baseHeap = usedHeapBytes()
        val baseDirect = directBytes()

        val path = GcodeNozzlePathParser.parse(file)
        settle()
        val liveAfterParse = usedHeapBytes() - baseHeap
        assertTrue("the synthetic file has to be past the budget", path.truncated)
        assertTrue("the budget has to merge something", path.moveCount < path.sourceMoveCount)

        val meter = MeteringRibbonSink(baseHeap, baseDirect)
        val staging = RibbonChunkStaging(meter)
        val builder = CuraRibbonBuilder(path, LAYER_HEIGHT_MM, LINE_WIDTH_MM, FILAMENT_AREA_MM2)
        builder.build(staging)
        staging.finish()
        settle()
        val liveAfterBuild = usedHeapBytes() - baseHeap

        // Every retained move is one window: the budget merges the moves it
        // cannot hold into their neighbours, and nothing else is allowed to
        // disappear between the parser and the geometry sink.
        val storedExtrusions = RibbonPathGeometry.countKind(
            path.moves,
            path.moveCount,
            GcodeNozzlePath.Kind.EXTRUSION.code,
        )
        assertTrue("a merged move carries the extrusion it folded in", path.extrusionMoveCount <= storedExtrusions)
        assertEquals("one window per retained extrusion move", storedExtrusions, builder.extrusionWindows)
        assertEquals("the sink saw every window", builder.extrusionWindows, meter.windows)
        assertTrue("no chunk may exceed the staging area", meter.largestChunkBytes <= staging.bufferBytes)

        report("cura 900k", path.sourceMoveCount, path.moveCount, path.extrusionMoveCount, liveAfterParse, liveAfterBuild, meter, staging)
        assertMemories(meter, maxOf(liveAfterParse, liveAfterBuild))
    }

    @Test
    fun aFileUnderTheBudgetKeepsEveryMove() {
        val file = curaGcode(UNDER_BUDGET_EXTRUSIONS)
        val path = GcodeNozzlePathParser.parse(file)
        assertTrue("a file under the budget is not truncated", !path.truncated)
        assertEquals("nothing may be merged away", path.sourceMoveCount, path.moveCount)

        val meter = MeteringRibbonSink(usedHeapBytes(), directBytes())
        val staging = RibbonChunkStaging(meter)
        CuraRibbonBuilder(path, LAYER_HEIGHT_MM, LINE_WIDTH_MM, FILAMENT_AREA_MM2).build(staging)
        staging.finish()
        assertEquals("one window per move", path.extrusionMoveCount, meter.windows)
        assertEquals("every move arrived", path.moveCount, path.extrusionMoveCount + path.travelMoveCount)
    }

    /**
     * The print that crashed the tab was a PrusaSlicer one whose 851,862 moves
     * fit under that parser's million-move budget, so nothing was merged and the
     * ribbon needed 792 bytes a move - 616 MB of grid, held in direct buffers
     * that the Java heap paid for. It has to build now.
     */
    @Test
    fun thePrusaPathThatCrashedTheTabBuilds() {
        val file = prusaGcode(PRUSA_MOVES)
        settle()
        val baseHeap = usedHeapBytes()
        val baseDirect = directBytes()

        val path = PrusaNozzlePathParser.parse(file)
        settle()
        val liveAfterParse = usedHeapBytes() - baseHeap
        assertTrue("under the million-move budget nothing is merged", !path.truncated)
        assertEquals("every source move is kept", path.sourceMoveCount, path.moveCount)
        assertTrue("the print has to be the size that crashed", path.extrusionMoveCount > CURA_BUDGET)

        val meter = MeteringRibbonSink(baseHeap, baseDirect)
        val staging = RibbonChunkStaging(meter)
        val builder = PrusaRibbonBuilder(path)
        builder.build(staging)
        staging.finish()
        settle()
        val liveAfterBuild = usedHeapBytes() - baseHeap
        val storedExtrusions = RibbonPathGeometry.countKind(
            path.moves,
            path.moveCount,
            PrusaNozzlePath.Kind.EXTRUSION.code,
        )
        assertEquals("one window per extrusion move", storedExtrusions, builder.ribbonWindows)
        assertEquals("the sink saw every window", builder.ribbonWindows, meter.windows)

        report("prusa 850k", path.sourceMoveCount, path.moveCount, path.extrusionMoveCount, liveAfterParse, liveAfterBuild, meter, staging)
        assertMemories(meter, maxOf(liveAfterParse, liveAfterBuild))
    }

    /**
     * The figures the build has to stay inside. Both are a small fraction of the
     * 512 MB growth limit a large-heap Android app gets, and both are measured,
     * not assumed: see the test output for the numbers this run saw.
     */
    private fun assertMemories(meter: MeteringRibbonSink, liveBytes: Long) {
        assertTrue(
            "live Java heap " + mb(liveBytes) + " MB exceeds the " + LIVE_HEAP_FIGURE_MB + " MB figure",
            liveBytes <= MB * LIVE_HEAP_FIGURE_MB,
        )
        assertTrue(
            "peak direct memory " + mb(meter.peakDirectBytes) + " MB exceeds the " + DIRECT_FIGURE_MB + " MB figure",
            meter.peakDirectBytes <= MB * DIRECT_FIGURE_MB,
        )
        assertTrue(
            "a single chunk was " + mb(meter.largestChunkBytes) + " MB, more than the staging area",
            meter.largestChunkBytes <= STAGING_FIGURE_MB * MB,
        )
    }

    /** A CuraEngine print of [extrusions] one-millimetre moves in a serpentine. */
    private fun curaGcode(extrusions: Int): File {
        val file = File.createTempFile("nozzle-path-memory-cura", ".gcode").also { it.deleteOnExit() }
        file.bufferedWriter().use { out ->
            out.append(";FLAVOR:Marlin\n;Layer height: ").append(LAYER_HEIGHT_TEXT).append("\nM82\nG92 E0\nG28\n")
            var e = 0L
            for (index in 0 until extrusions) {
                val column = index % ROW_MOVES
                val row = index / ROW_MOVES
                if (column == 0) {
                    if (index > 0) {
                        out.append("G0 X0 Y" + row + " F6000\n")
                    }
                    if (index % MOVES_PER_LAYER == 0) {
                        out.append(";LAYER:" + index / MOVES_PER_LAYER + "\n")
                        out.append("G0 Z" + zText(index / MOVES_PER_LAYER + 1) + " F600\n")
                    }
                }
                e += E_UNITS_PER_MOVE
                out.append("G1 X" + (column + 1) + " Y" + row + " E" + eText(e) + "\n")
            }
        }
        return file
    }

    /**
     * A PrusaSlicer/OrcaSlicer print of [moves] moves: extrusions in a serpentine
     * with a travel between rows, and the marker-driven width and height the
     * Prusa preview reads.
     */
    private fun prusaGcode(moves: Int): File {
        val file = File.createTempFile("nozzle-path-memory-prusa", ".gcode").also { it.deleteOnExit() }
        file.bufferedWriter().use { out ->
            out.append("; prusaslicer_config = begin\n;LAYER_CHANGE\n;Z:").append(LAYER_HEIGHT_TEXT)
                .append("\n;HEIGHT:").append(LAYER_HEIGHT_TEXT).append("\n;WIDTH:").append(LINE_WIDTH_TEXT)
                .append("\nM82\nG92 E0\nG28\n")
            var e = 0L
            var emitted = 0
            var index = 0
            var y = 0
            while (emitted < moves) {
                val column = index % ROW_MOVES
                if (emitted > 0 && column == 0) {
                    y++
                    out.append("G0 X0 Y" + y + " F6000\n")
                    emitted++
                    if (emitted >= moves) break
                }
                if (emitted % MOVES_PER_LAYER == 0) {
                    out.append(";LAYER_CHANGE\n;Z:" + zText(emitted / MOVES_PER_LAYER + 1) +
                        "\n;HEIGHT:" + LAYER_HEIGHT_TEXT + "\n")
                }
                e += E_UNITS_PER_MOVE
                out.append("G1 X" + (column + 1) + " Y" + y + " E" + eText(e) + " F1800\n")
                emitted++
                index++
            }
        }
        return file
    }

    private fun zText(layer: Int): String {
        val hundredths = layer * 20
        return (hundredths / 100).toString() + "." + (hundredths % 100).toString().padStart(2, '0')
    }

    /** E in ten-thousandths of a millimetre, printed exactly: 0.0333 mm a move. */
    private fun eText(units: Long): String =
        (units / 10_000).toString() + "." + (units % 10_000).toString().padStart(4, '0')

    private inner class MeteringRibbonSink(
        private val baseHeapBytes: Long,
        private val baseDirectBytes: Long,
    ) : RibbonChunkSink {
        var windows = 0
            private set
        var travelSegments = 0
            private set
        var peakHeapBytes = 0L
            private set
        var peakDirectBytes = 0L
            private set
        var largestChunkBytes = 0L
            private set

        override fun ribbonChunk(
            positions: FloatBuffer,
            normals: FloatBuffer,
            colors: FloatBuffer,
            ambient: FloatBuffer,
            windows: Int,
        ) {
            sample(positions.remaining() + normals.remaining() + colors.remaining() + ambient.remaining())
            this.windows += windows
        }

        override fun travelChunk(positions: FloatBuffer, colors: FloatBuffer, segments: Int) {
            sample(positions.remaining() + colors.remaining())
            travelSegments += segments
        }

        private fun sample(chunkFloats: Int) {
            largestChunkBytes = maxOf(largestChunkBytes, chunkFloats.toLong() * Float.SIZE_BYTES)
            peakHeapBytes = maxOf(peakHeapBytes, usedHeapBytes() - baseHeapBytes)
            val direct = directBytes()
            if (direct >= 0L) peakDirectBytes = maxOf(peakDirectBytes, direct - baseDirectBytes)
        }
    }

    private fun report(
        label: String,
        sourceMoves: Int,
        moves: Int,
        extrusions: Int,
        liveAfterParse: Long,
        liveAfterBuild: Long,
        meter: MeteringRibbonSink,
        staging: RibbonChunkStaging,
    ) {
        println(
            label + ": sourceMoves=" + sourceMoves + " moves=" + moves +
                " extrusions=" + extrusions + " windows=" + meter.windows +
                " travels=" + meter.travelSegments +
                " liveAfterParse=" + mb(liveAfterParse) + "MB" +
                " liveAfterBuild=" + mb(liveAfterBuild) + "MB" +
                " sampledPeakHeap=" + mb(meter.peakHeapBytes) + "MB" +
                " peakDirect=" + mb(meter.peakDirectBytes) + "MB" +
                " largestChunk=" + mb(meter.largestChunkBytes) + "MB" +
                " staging=" + mb(staging.bufferBytes) + "MB",
        )
    }

    private fun mb(bytes: Long): Long = (bytes + MB / 2) / MB

    private companion object {
        const val MB = 1024L * 1024L

        /**
         * The live Java heap the parse and build of a 900,000-move file may hold:
         * the moves, their source indices and one chunk of staging. The whole-path
         * ribbon this replaced was 317 MB for that file and 616 MB for the Prusa
         * one below.
         */
        const val LIVE_HEAP_FIGURE_MB = 96L

        /** The staging area, the camera-fit bounds and nothing else. */
        const val DIRECT_FIGURE_MB = 24L

        /** RibbonChunkStaging.DEFAULT_CHUNK_WINDOWS x 18 vertices x 11 floats, x4 bytes. */
        const val STAGING_FIGURE_MB = 8L

        const val CURA_BUDGET = 400_000
        const val CURA_EXTRUSIONS = 900_000
        const val UNDER_BUDGET_EXTRUSIONS = 300_000
        const val PRUSA_MOVES = 851_862

        const val ROW_MOVES = 250
        const val MOVES_PER_LAYER = 2_000
        const val LAYER_HEIGHT_MM = 0.2f
        const val LINE_WIDTH_MM = 0.4f
        const val LAYER_HEIGHT_TEXT = "0.20"
        const val LINE_WIDTH_TEXT = "0.44"
        const val E_UNITS_PER_MOVE = 333L
        val FILAMENT_AREA_MM2 = (Math.PI * 0.875 * 0.875).toFloat()
    }

    private fun settle() {
        repeat(3) {
            System.gc()
            Thread.sleep(30)
        }
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /**
     * The live direct-buffer bytes, through the JVM's own buffer-pool bean. Android
     * charges these against the Java heap; the JVM counts them separately, so this
     * is the number that has to stay small. -1 when the bean is not there.
     */
    private fun directBytes(): Long = runCatching {
        val bean = directPool ?: return -1L
        val beanClass = Class.forName("java.lang.management.BufferPoolMXBean")
        beanClass.getMethod("getMemoryUsed").invoke(bean) as Long
    }.getOrDefault(-1L)

    private val directPool: Any? by lazy {
        runCatching {
            val beanClass = Class.forName("java.lang.management.BufferPoolMXBean")
            val factory = Class.forName("java.lang.management.ManagementFactory")
            val beans = factory.getMethod("getPlatformMXBeans", Class::class.java)
                .invoke(null, beanClass) as List<*>
            beans.firstOrNull { beanClass.getMethod("getName").invoke(it) == "direct" }
        }.getOrNull()
    }
}
