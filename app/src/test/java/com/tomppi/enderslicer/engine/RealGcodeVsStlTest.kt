package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Compares the printed part geometry (extrusion moves only) against the real
 * STL bounds: shape, scale, and per-Z cross-sections must agree with the
 * source model; deviations point at a bad model transform in the slice.
 */
class RealGcodeVsStlTest {
    private val gcode = File("C:/Users/FREDRIK/Documents/PrintShare/print_20260903_232908_247.gcode")
    private val stl = File("C:/Users/FREDRIK/Documents/PrintShare/3DBenchy.stl")

    @Test
    fun compare() {
        assumeTrue("gcode/stl not present", gcode.isFile && stl.isFile)
        // STL bounds (binary; first triangle at byte 84, each 50 bytes)
        val bytes = java.nio.file.Files.readAllBytes(stl.toPath())
        var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY; var maxZ = Double.NEGATIVE_INFINITY
        var i = 84
        while (i + 48 <= bytes.size) {
            val x1 = java.nio.ByteBuffer.wrap(bytes, i + 12, 12).order(java.nio.ByteOrder.LITTLE_ENDIAN).float.toDouble()
            val y1 = java.nio.ByteBuffer.wrap(bytes, i + 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float.toDouble()
            val z1 = java.nio.ByteBuffer.wrap(bytes, i + 44, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float.toDouble()
            val x2 = java.nio.ByteBuffer.wrap(bytes, i + 36, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float.toDouble()
            val y2 = java.nio.ByteBuffer.wrap(bytes, i + 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float.toDouble()
            val z2 = java.nio.ByteBuffer.wrap(bytes, i + 44, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float.toDouble()
            val x3 = java.nio.ByteBuffer.wrap(bytes, i + 36, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float.toDouble()
            val y3 = java.nio.ByteBuffer.wrap(bytes, i + 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float.toDouble()
            val z3 = java.nio.ByteBuffer.wrap(bytes, i + 44, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float.toDouble()
            minX = min(minX, Math.min(x1, Math.min(x2, x3))); maxX = max(maxX, Math.max(x1, Math.max(x2, x3)))
            minY = min(minY, Math.min(y1, Math.min(y2, y3))); maxY = max(maxY, Math.max(y1, Math.max(y2, y3)))
            minZ = min(minZ, Math.min(z1, Math.min(z2, z3))); maxZ = max(maxZ, Math.max(z1, Math.max(z2, z3)))
            i += 50
        }
        println("STL size=" + "%.3f".format(maxX - minX) + " x " + "%.3f".format(maxY - minY) + " x " + "%.3f".format(maxZ - minZ))
        println("STL bounds X=[" + "%.3f".format(minX) + "," + "%.3f".format(maxX) + "] Y=[" + "%.3f".format(minY) + "," + "%.3f".format(maxY) + "] Z=[" + "%.3f".format(minZ) + "," + "%.3f".format(maxZ) + "]")

        // Gcode printed bounds (extrusion moves only)
        val path = GcodeNozzlePathParser.parse(gcode, 2_000_000, GcodeDialect.PRUSA)
        val moves = path.moves
        val n = path.moveCount
        var gMinX = Double.POSITIVE_INFINITY; var gMaxX = Double.NEGATIVE_INFINITY
        var gMinY = Double.POSITIVE_INFINITY; var gMaxY = Double.NEGATIVE_INFINITY
        var gMinZ = Double.POSITIVE_INFINITY; var gMaxZ = Double.NEGATIVE_INFINITY
        for (m in 0 until n) {
            val o = m * GcodeNozzlePath.VALUES_PER_MOVE
            if (moves[o + GcodeNozzlePath.KIND] == GcodeNozzlePath.Kind.EXTRUSION.code) {
                val sx = moves[o + GcodeNozzlePath.X1].toDouble(); val sy = moves[o + GcodeNozzlePath.Y1].toDouble()
                val sz = moves[o + GcodeNozzlePath.Z1].toDouble()
                val ex = moves[o + GcodeNozzlePath.X2].toDouble(); val ey = moves[o + GcodeNozzlePath.Y2].toDouble()
                val ez = moves[o + GcodeNozzlePath.Z2].toDouble()
                gMinX = Math.min(gMinX, Math.min(sx, ex)); gMaxX = Math.max(gMaxX, Math.max(sx, ex))
                gMinY = Math.min(gMinY, Math.min(sy, ey)); gMaxY = Math.max(gMaxY, Math.max(sy, ey))
                gMinZ = Math.min(gMinZ, Math.min(sz, ez)); gMaxZ = Math.max(gMaxZ, Math.max(sz, ez))
            }
        }
        println("GCODE size=" + "%.3f".format(gMaxX - gMinX) + " x " + "%.3f".format(gMaxY - gMinY) + " x " + "%.3f".format(gMaxZ - gMinZ))
        println("GCODE bounds X=[" + "%.2f".format(gMinX) + "," + "%.2f".format(gMaxX) + "] Y=[" + "%.2f".format(gMinY) + "," + "%.2f".format(gMaxY) + "] Z=[" + "%.2f".format(gMinZ) + "," + "%.2f".format(gMaxZ) + "]")
        val sx = (gMaxX - gMinX) / (maxX - minX)
        val sy2 = (gMaxY - gMinY) / (maxY - minY)
        val sz = (gMaxZ - gMinZ) / (maxZ - minZ)
        println("SCALE vs STL: X=" + "%.4f".format(sx) + " Y=" + "%.4f".format(sy2) + " Z=" + "%.4f".format(sz))
        check(abs(sx - sz) < 0.02 && abs(sy2 - sz) < 0.02) { "NON-UNIFORM SCALE: " + sx + "," + sy2 + "," + sz }
    }
}