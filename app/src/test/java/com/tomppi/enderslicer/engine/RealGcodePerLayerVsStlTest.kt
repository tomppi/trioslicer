package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Per-Z slice compare: printed extrusion extents vs STL cross-section extents. */
class RealGcodePerLayerVsStlTest {
    private val gcode = File("C:/Users/FREDRIK/Documents/PrintShare/print_20260903_232908_247.gcode")
    private val stl = File("C:/Users/FREDRIK/Documents/PrintShare/3DBenchy.stl")

    @Test
    fun perLayer() {
        assumeTrue("gcode/stl not present", gcode.isFile && stl.isFile)
        // STL triangles with z-range
        val bytes = java.nio.file.Files.readAllBytes(stl.toPath())
        val tris = ArrayList<DoubleArray>()
        var i = 84
        while (i + 48 <= bytes.size) {
            val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val x1 = bb.getFloat(i + 12).toDouble(); val y1 = bb.getFloat(i + 16).toDouble(); val z1 = bb.getFloat(i + 20).toDouble()
            val x2 = bb.getFloat(i + 24).toDouble(); val y2 = bb.getFloat(i + 28).toDouble(); val z2 = bb.getFloat(i + 32).toDouble()
            val x3 = bb.getFloat(i + 36).toDouble(); val y3 = bb.getFloat(i + 40).toDouble(); val z3 = bb.getFloat(i + 44).toDouble()
            tris.add(doubleArrayOf(x1, y1, z1, x2, y2, z2, x3, y3, z3))
            i += 50
        }
        println("STL tris=" + tris.size)

        // Gcode extrusion moves grouped into 2mm Z bands
        val path = GcodeNozzlePathParser.parse(gcode, 2_000_000, GcodeDialect.PRUSA)
        val moves = path.moves
        val n = path.moveCount
        val bandSize = 2.0
        val bands = sortedMapOf<Int, DoubleArray>() // zBand -> {minX,maxX,minY,maxY}
        for (m in 0 until n) {
            val o = m * GcodeNozzlePath.VALUES_PER_MOVE
            if (moves[o + GcodeNozzlePath.KIND] == GcodeNozzlePath.Kind.EXTRUSION.code) {
                val sz = moves[o + GcodeNozzlePath.Z1].toDouble()
                val band = (sz / bandSize).toInt()
                val arr = bands.getOrPut(band) { doubleArrayOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY) }
                val sx = moves[o + GcodeNozzlePath.X1].toDouble(); val sy = moves[o + GcodeNozzlePath.Y1].toDouble()
                val ex = moves[o + GcodeNozzlePath.X2].toDouble(); val ey = moves[o + GcodeNozzlePath.Y2].toDouble()
                arr[0] = Math.min(arr[0], Math.min(sx, ex)); arr[1] = Math.max(arr[1], Math.max(sx, ex))
                arr[2] = Math.min(arr[2], Math.min(sy, ey)); arr[3] = Math.max(arr[3], Math.max(sy, ey))
            }
        }
        // compare bands: for each printed band, expected STL extent at that z
        var worst = 0.0
        var worstBand = -1
        for ((band, printed) in bands) {
            val zLow = band * bandSize
            val zHigh = zLow + bandSize
            var sMinX = Double.POSITIVE_INFINITY; var sMaxX = Double.NEGATIVE_INFINITY
            var sMinY = Double.POSITIVE_INFINITY; var sMaxY = Double.NEGATIVE_INFINITY
            for (t in tris) {
                val tz = doubleArrayOf(t[2], t[5], t[8])
                if (tz.minOrNull()!! <= zHigh && tz.maxOrNull()!! >= zLow) {
                    sMinX = Math.min(sMinX, Math.min(t[0], Math.min(t[3], t[6]))); sMaxX = Math.max(sMaxX, Math.max(t[0], Math.max(t[3], t[6])))
                    sMinY = Math.min(sMinY, Math.min(t[1], Math.min(t[4], t[7]))); sMaxY = Math.max(sMaxY, Math.max(t[1], Math.max(t[4], t[7])))
                }
            }
            if (sMinX == Double.POSITIVE_INFINITY) continue
            // printed is centered at 105,110; STL centered at its own bounds
            val pMinX = printed[0] - 105.0; val pMaxX = printed[1] - 105.0
            val pMinY = printed[2] - 110.0; val pMaxY = printed[3] - 110.0
            val dx = max(abs(pMinX - sMinX), abs(pMaxX - sMaxX))
            val dy = max(abs(pMinY - sMinY), abs(pMaxY - sMaxY))
            val dev = max(dx, dy)
            if (dev > worst) { worst = dev; worstBand = band }
            if (dev > 2.0) {
                println("DEV band z=" + zLow + "-" + zHigh +
                    " printed X=[" + "%.2f".format(pMinX) + "," + "%.2f".format(pMaxX) + "] Y=[" + "%.2f".format(pMinY) + "," + "%.2f".format(pMaxY) + "]" +
                    " stl X=[" + "%.2f".format(sMinX) + "," + "%.2f".format(sMaxX) + "] Y=[" + "%.2f".format(sMinY) + "," + "%.2f".format(sMaxY) + "]" +
                    " dev=" + "%.2f".format(dev))
            }
        }
        println("WORST dev=" + "%.2f".format(worst) + "mm at band " + worstBand)
    }
}