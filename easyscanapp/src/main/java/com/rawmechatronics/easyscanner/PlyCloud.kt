package com.rawmechatronics.easyscanner

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * A point cloud read from a binary little-endian PLY, the way the engine
 * writes them (float x y z, uchar red green blue) but tolerant of other
 * property orders, types and extra properties. Larger clouds are thinned by a
 * stride to at most `maxPoints`, which is all the phone's viewer needs.
 */
class PlyCloud(val xyz: FloatArray, val rgb: ByteArray, val total: Int) {
    val count get() = xyz.size / 3

    /** A robust centre (per-axis median) and radius (90th-percentile distance), so stray stripes do not frame the view. */
    fun bounds(): Pair<FloatArray, Float> {
        if (count == 0) return floatArrayOf(0f, 0f, 0f) to 1f
        val step = maxOf(1, count / SAMPLE)
        val idx = (0 until count step step).toList()
        val centre = FloatArray(3) { a -> idx.map { xyz[3 * it + a] }.sorted()[idx.size / 2] }
        val dist = idx.map { i ->
            val dx = xyz[3 * i] - centre[0]; val dy = xyz[3 * i + 1] - centre[1]; val dz = xyz[3 * i + 2] - centre[2]
            sqrt(dx * dx + dy * dy + dz * dz)
        }.sorted()
        return centre to maxOf(dist[(dist.size * 9) / 10], 1e-3f)
    }

    companion object {
        const val SAMPLE = 20_000
        private val SIZES = mapOf(
            "char" to 1, "uchar" to 1, "int8" to 1, "uint8" to 1,
            "short" to 2, "ushort" to 2, "int16" to 2, "uint16" to 2,
            "int" to 4, "uint" to 4, "int32" to 4, "uint32" to 4, "float" to 4, "float32" to 4,
            "double" to 8, "float64" to 8,
        )

        private class Prop(val name: String, val type: String, val offset: Int)

        /** The vertex count from the header alone, or null when it is not a PLY. */
        fun vertexCount(file: File): Int? = runCatching {
            file.inputStream().use { s ->
                val bytes = ByteArray(4096)
                var got = 0
                while (got < bytes.size) {
                    val r = s.read(bytes, got, bytes.size - got)
                    if (r < 0) break
                    got += r
                }
                val head = String(bytes, 0, got, Charsets.ISO_8859_1).substringBefore("end_header")
                if (!head.startsWith("ply")) return null
                Regex("element vertex (\\d+)").find(head)?.groupValues?.get(1)?.toInt()
            }
        }.getOrNull()

        fun read(file: File, maxPoints: Int): PlyCloud =
            RandomAccessFile(file, "r").use { raf ->
                read(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()), maxPoints)
            }

        fun read(buf: ByteBuffer, maxPoints: Int): PlyCloud {
            val (header, bodyStart) = header(buf)
            val lines = header.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.firstOrNull() != "ply") throw IOException("not a PLY file")
            val format = lines.firstOrNull { it.startsWith("format ") }?.split(" ")?.getOrNull(1)
            if (format != "binary_little_endian") throw IOException("only binary little-endian PLY is supported (this one is $format)")
            // the vertex element must come first: other elements (faces) follow it in the body and are not read
            val first = lines.firstOrNull { it.startsWith("element ") }?.split(" ")
            if (first == null || first.getOrNull(1) != "vertex") throw IOException("no vertex element first")
            val n = first[2].toInt()
            val props = mutableListOf<Prop>()
            var stride = 0
            var inVertex = false
            for (l in lines) {
                val t = l.split(Regex("\\s+"))
                when (t[0]) {
                    "element" -> inVertex = t[1] == "vertex"
                    "property" -> if (inVertex) {
                        if (t[1] == "list") throw IOException("list properties on vertices are not supported")
                        val size = SIZES[t[1]] ?: throw IOException("unknown property type ${t[1]}")
                        props += Prop(t[2], t[1], stride)
                        stride += size
                    }
                }
            }
            fun prop(vararg names: String) = props.firstOrNull { it.name in names }
            val px = prop("x") ?: throw IOException("no x"); val py = prop("y") ?: throw IOException("no y")
            val pz = prop("z") ?: throw IOException("no z")
            val pr = prop("red", "r"); val pg = prop("green", "g"); val pb = prop("blue", "b")
            if (bodyStart.toLong() + n.toLong() * stride > buf.limit()) throw IOException("file is shorter than its $n vertices")

            val step = maxOf(1, ceil(n.toDouble() / maxPoints).toInt())
            val m = (n + step - 1) / step
            val xyz = FloatArray(3 * m)
            val rgb = ByteArray(3 * m) { GREY }
            val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until m) {
                val at = bodyStart + k * step * stride
                xyz[3 * k] = number(b, at + px.offset, px.type)
                xyz[3 * k + 1] = number(b, at + py.offset, py.type)
                xyz[3 * k + 2] = number(b, at + pz.offset, pz.type)
                if (pr != null && pg != null && pb != null) {
                    rgb[3 * k] = channel(b, at + pr.offset, pr.type)
                    rgb[3 * k + 1] = channel(b, at + pg.offset, pg.type)
                    rgb[3 * k + 2] = channel(b, at + pb.offset, pb.type)
                }
            }
            return PlyCloud(xyz, rgb, n)
        }

        private const val GREY = 0xB0.toByte()

        private fun header(buf: ByteBuffer): Pair<String, Int> {
            val end = "end_header"
            val limit = minOf(buf.limit(), 64 * 1024)
            val sb = StringBuilder()
            for (i in 0 until limit) {
                val c = buf.get(i).toInt().toChar()
                sb.append(c)
                if (c == '\n' && sb.endsWith("$end\n")) return sb.toString() to i + 1
                if (c == '\n' && sb.endsWith("$end\r\n")) return sb.toString() to i + 1
            }
            throw IOException("no end_header")
        }

        private fun number(b: ByteBuffer, at: Int, type: String): Float = when (type) {
            "float", "float32" -> b.getFloat(at)
            "double", "float64" -> b.getDouble(at).toFloat()
            "int", "int32" -> b.getInt(at).toFloat()
            "uint", "uint32" -> (b.getInt(at).toLong() and 0xFFFFFFFFL).toFloat()
            "short", "int16" -> b.getShort(at).toFloat()
            "ushort", "uint16" -> (b.getShort(at).toInt() and 0xFFFF).toFloat()
            "char", "int8" -> b.get(at).toFloat()
            else -> (b.get(at).toInt() and 0xFF).toFloat()
        }

        /** A colour channel as an unsigned byte; float channels are taken as 0..1. */
        private fun channel(b: ByteBuffer, at: Int, type: String): Byte = when (type) {
            "uchar", "uint8", "char", "int8" -> b.get(at)
            "float", "float32", "double", "float64" -> (number(b, at, type).coerceIn(0f, 1f) * 255f + .5f).toInt().toByte()
            else -> number(b, at, type).toInt().coerceIn(0, 255).toByte()
        }
    }
}
