package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

object StlParser {
    fun parse(
        file: File,
        displayName: String = file.name,
        maxTriangles: Int = MeshTriangleLimits.current(),
    ): StlMesh {
        require(file.isFile && file.length() > 0L) { "The selected STL is empty or unavailable" }
        val limit = MeshTriangleLimits.sanitize(maxTriangles)
        require(file.length() <= MeshTriangleLimits.maxInputFileBytes(limit)) {
            "STL is larger than ${MeshTriangleLimits.formatBytes(MeshTriangleLimits.maxInputFileBytes(limit))} for the ${MeshTriangleLimits.formatCount(limit)}-triangle limit"
        }

        val binaryCount = binaryTriangleCount(file)
        return if (binaryCount != null) {
            require(binaryCount in 1L..limit.toLong()) {
                "STL contains ${MeshTriangleLimits.formatCount(binaryCount)} triangles; the current limit is ${MeshTriangleLimits.formatCount(limit)}"
            }
            parseBinary(displayName, file, binaryCount.toInt())
        } else {
            parseAscii(displayName, file, limit)
        }
    }

    internal fun binaryTriangleCount(file: File): Long? {
        if (!file.isFile || file.length() < STL_HEADER_BYTES) return null
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                input.seek(80L)
                val countBytes = ByteArray(4)
                input.readFully(countBytes)
                val count = ByteBuffer.wrap(countBytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                    .toLong() and 0xffffffffL
                val expected = STL_HEADER_BYTES + count * STL_TRIANGLE_BYTES
                count.takeIf { it > 0L && expected == file.length() }
            }
        }.getOrNull()
    }

    /**
     * True when [file] is a whole STL rather than one still being written.
     *
     * A binary STL states its own length - an 84-byte header plus 50 bytes per
     * triangle, with the count at offset 80 - which makes [binaryTriangleCount]
     * a completeness proof rather than a guess: it returns null the moment the
     * length disagrees. An ASCII export has no such arithmetic, so it counts as
     * whole once it carries its `endsolid` terminator.
     *
     * **A size that held still is not a substitute.** A writer pausing for a
     * fraction of a second mid-file looks settled, and the reader then gets a
     * mesh cut off part-way through a triangle. That is worth a dedicated check
     * because the caller cannot tell a truncated mesh from a small one.
     */
    fun isComplete(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_STL_BYTES) return false
        if (binaryTriangleCount(file) != null) return true
        return asciiIsTerminated(file)
    }

    /** An ASCII STL ends with `endsolid`; a truncated one never does. */
    private fun asciiIsTerminated(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val start = maxOf(0L, file.length() - ASCII_TAIL_BYTES)
            input.seek(start)
            val tail = ByteArray((file.length() - start).toInt())
            input.readFully(tail)
            tail.toString(Charsets.UTF_8)
                .lineSequence()
                .map(String::trim)
                .lastOrNull(String::isNotEmpty)
                ?.startsWith("endsolid", ignoreCase = true) == true
        }
    }.getOrDefault(false)

    private fun parseBinary(name: String, file: File, triangleCount: Int): StlMesh {
        // Large meshes are parsed straight into a direct native buffer: the
        // vertex data stops counting against the app Java heap cap.
        val floatCount = Math.multiplyExact(triangleCount, FLOATS_PER_TRIANGLE)
        val direct = if (triangleCount >= OFF_HEAP_MIN_TRIANGLES) {
            ByteBuffer.allocateDirect(Math.multiplyExact(floatCount, Float.SIZE_BYTES))
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        } else null
        val floats = if (direct == null) FloatArray(floatCount) else null
        val bounds = BoundsAccumulator()
        var out = 0

        file.inputStream().channel.use { channel ->
            channel.position(STL_HEADER_BYTES)
            val buffer = ByteBuffer
                .allocateDirect(BINARY_BLOCK_TRIANGLES * STL_TRIANGLE_BYTES.toInt())
                .order(ByteOrder.LITTLE_ENDIAN)
            var remaining = triangleCount

            fun emit(value: Float) {
                if (direct != null) {
                    direct.put(value)
                } else {
                    floats!![out++] = value
                }
            }

            while (remaining > 0) {
                val records = minOf(remaining, BINARY_BLOCK_TRIANGLES)
                buffer.clear()
                buffer.limit(records * STL_TRIANGLE_BYTES.toInt())
                readFully(channel, buffer)
                buffer.flip()

                repeat(records) {
                    // STL facet normals are advisory and are frequently stale,
                    // unnormalised or unrelated to the final vertex winding.
                    // Consume them, but derive the renderer normal from geometry.
                    buffer.float
                    buffer.float
                    buffer.float
                    val x0 = buffer.float
                    val y0 = buffer.float
                    val z0 = buffer.float
                    val x1 = buffer.float
                    val y1 = buffer.float
                    val z1 = buffer.float
                    val x2 = buffer.float
                    val y2 = buffer.float
                    val z2 = buffer.float
                    buffer.short

                    require(
                        x0.isFinite() && y0.isFinite() && z0.isFinite() &&
                            x1.isFinite() && y1.isFinite() && z1.isFinite() &&
                            x2.isFinite() && y2.isFinite() && z2.isFinite(),
                    ) { "STL contains non-finite coordinates" }

                    val ax = x1 - x0
                    val ay = y1 - y0
                    val az = z1 - z0
                    val bx = x2 - x0
                    val by = y2 - y0
                    val bz = z2 - z0
                    var nx = ay * bz - az * by
                    var ny = az * bx - ax * bz
                    var nz = ax * by - ay * bx
                    val length = sqrt(nx * nx + ny * ny + nz * nz)
                    if (length > NORMAL_EPSILON) {
                        nx /= length
                        ny /= length
                        nz /= length
                    } else {
                        nx = 0f
                        ny = 0f
                        nz = 0f
                    }
                    require(nx.isFinite() && ny.isFinite() && nz.isFinite()) {
                        "STL triangle normal is non-finite"
                    }

                    fun writeVertex(x: Float, y: Float, z: Float) {
                        emit(x); emit(y); emit(z)
                        emit(nx); emit(ny); emit(nz)
                        bounds.include(x, y, z)
                    }
                    writeVertex(x0, y0, z0)
                    writeVertex(x1, y1, z1)
                    writeVertex(x2, y2, z2)
                }
                remaining -= records
            }
        }

        val vertices = if (direct != null) {
            direct.position(0)
            VertexData.fromDirect(direct)
        } else {
            VertexData.fromArray(requireNotNull(floats))
        }
        return StlMesh(name, vertices, triangleCount, bounds.finish())
    }

    private fun parseAscii(name: String, file: File, maxTriangles: Int): StlMesh {
        val source = scanAscii(file, maxTriangles, consumer = null)
        require(source.triangleCount > 0) { "No complete triangles were found in the STL" }
        val floats = FloatArray(Math.multiplyExact(source.triangleCount, FLOATS_PER_TRIANGLE))
        val bounds = BoundsAccumulator()
        var out = 0

        val parsed = scanAscii(
            file = file,
            maxTriangles = maxTriangles,
            consumer = object : TriangleConsumer {
                override fun accept(
                    x0: Double,
                    y0: Double,
                    z0: Double,
                    x1: Double,
                    y1: Double,
                    z1: Double,
                    x2: Double,
                    y2: Double,
                    z2: Double,
                ) {
                    val ax = x1 - x0
                    val ay = y1 - y0
                    val az = z1 - z0
                    val bx = x2 - x0
                    val by = y2 - y0
                    val bz = z2 - z0
                    var nx = ay * bz - az * by
                    var ny = az * bx - ax * bz
                    var nz = ax * by - ay * bx
                    val length = sqrt(nx * nx + ny * ny + nz * nz)
                    if (length > NORMAL_EPSILON_DOUBLE) {
                        nx /= length
                        ny /= length
                        nz /= length
                    } else {
                        nx = 0.0
                        ny = 0.0
                        nz = 0.0
                    }
                    val nxf = nx.toFloat()
                    val nyf = ny.toFloat()
                    val nzf = nz.toFloat()
                    require(nxf.isFinite() && nyf.isFinite() && nzf.isFinite()) {
                        "ASCII STL triangle normal is non-finite"
                    }

                    fun writeVertex(x: Double, y: Double, z: Double) {
                        val xf = (x - source.originX).toFloat()
                        val yf = (y - source.originY).toFloat()
                        val zf = (z - source.originZ).toFloat()
                        require(xf.isFinite() && yf.isFinite() && zf.isFinite()) {
                            "ASCII STL coordinate is outside the supported numeric range"
                        }
                        floats[out++] = xf
                        floats[out++] = yf
                        floats[out++] = zf
                        floats[out++] = nxf
                        floats[out++] = nyf
                        floats[out++] = nzf
                        bounds.include(xf, yf, zf)
                    }
                    writeVertex(x0, y0, z0)
                    writeVertex(x1, y1, z1)
                    writeVertex(x2, y2, z2)
                }
            },
        )
        check(parsed == source && out == floats.size) {
            "ASCII STL changed while it was being parsed"
        }
        return StlMesh(
            displayName = name,
            interleavedVertices = VertexData.fromArray(floats),
            triangleCount = source.triangleCount,
            bounds = bounds.finish(),
            sourceOriginXmm = source.originX,
            sourceOriginYmm = source.originY,
            sourceOriginZmm = source.originZ,
        )
    }

    /**
     * Parses the actual ASCII STL grammar instead of globally grouping every
     * three lines beginning with `vertex`. The optional consumer keeps the
     * counting and materialization passes on exactly the same validation path.
     */
    private fun scanAscii(file: File, maxTriangles: Int, consumer: TriangleConsumer?): AsciiScan {
        var state = AsciiState.EXPECT_FACET_OR_SOLID
        var wrapperOpen = false
        var wrapperClosed = false
        var sawFacet = false
        var triangleCount = 0
        var vertexCount = 0
        var x0 = 0.0
        var y0 = 0.0
        var z0 = 0.0
        var x1 = 0.0
        var y1 = 0.0
        var z1 = 0.0
        var x2 = 0.0
        var y2 = 0.0
        var z2 = 0.0
        var originX: Double? = null
        var originY: Double? = null
        var originZ: Double? = null

        file.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, raw ->
                val lineNumber = index + 1
                require(raw.length <= MAX_ASCII_LINE_LENGTH) {
                    "ASCII STL line $lineNumber exceeds the $MAX_ASCII_LINE_LENGTH character safety limit"
                }
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return@forEachIndexed
                require(!wrapperClosed) { "Unexpected content after endsolid at line $lineNumber" }
                val tokens = trimmed.split(WHITESPACE)
                val keyword = tokens.first().lowercase()

                when (keyword) {
                    "solid" -> {
                        require(state == AsciiState.EXPECT_FACET_OR_SOLID && !wrapperOpen && !sawFacet) {
                            "Unexpected solid at line $lineNumber"
                        }
                        wrapperOpen = true
                        state = AsciiState.EXPECT_FACET
                    }
                    "facet" -> {
                        require(
                            state == AsciiState.EXPECT_FACET_OR_SOLID ||
                                state == AsciiState.EXPECT_FACET,
                        ) { "Unexpected facet at line $lineNumber" }
                        require(tokens.size == 5 && tokens[1].equals("normal", ignoreCase = true)) {
                            "Malformed facet normal at line $lineNumber"
                        }
                        parseFinite(tokens[2], "facet normal", lineNumber)
                        parseFinite(tokens[3], "facet normal", lineNumber)
                        parseFinite(tokens[4], "facet normal", lineNumber)
                        sawFacet = true
                        vertexCount = 0
                        state = AsciiState.EXPECT_OUTER_LOOP
                    }
                    "outer" -> {
                        require(state == AsciiState.EXPECT_OUTER_LOOP) {
                            "Unexpected outer loop at line $lineNumber"
                        }
                        require(tokens.size == 2 && tokens[1].equals("loop", ignoreCase = true)) {
                            "Malformed outer loop at line $lineNumber"
                        }
                        state = AsciiState.EXPECT_VERTEX
                    }
                    "vertex" -> {
                        require(state == AsciiState.EXPECT_VERTEX && vertexCount < 3) {
                            "Vertex outside a three-vertex outer loop at line $lineNumber"
                        }
                        require(tokens.size == 4) { "Malformed vertex at line $lineNumber" }
                        val x = parseFinite(tokens[1], "vertex", lineNumber)
                        val y = parseFinite(tokens[2], "vertex", lineNumber)
                        val z = parseFinite(tokens[3], "vertex", lineNumber)
                        if (originX == null) {
                            originX = x
                            originY = y
                            originZ = z
                        }
                        when (vertexCount) {
                            0 -> { x0 = x; y0 = y; z0 = z }
                            1 -> { x1 = x; y1 = y; z1 = z }
                            2 -> { x2 = x; y2 = y; z2 = z }
                        }
                        vertexCount++
                        if (vertexCount == 3) state = AsciiState.EXPECT_END_LOOP
                    }
                    "endloop" -> {
                        require(state == AsciiState.EXPECT_END_LOOP && tokens.size == 1) {
                            "Facet must contain exactly three vertices before endloop at line $lineNumber"
                        }
                        state = AsciiState.EXPECT_END_FACET
                    }
                    "endfacet" -> {
                        require(state == AsciiState.EXPECT_END_FACET && tokens.size == 1) {
                            "Unexpected endfacet at line $lineNumber"
                        }
                        triangleCount++
                        require(triangleCount <= maxTriangles) {
                            "STL has more than ${MeshTriangleLimits.formatCount(maxTriangles)} triangles"
                        }
                        consumer?.accept(x0, y0, z0, x1, y1, z1, x2, y2, z2)
                        state = AsciiState.EXPECT_FACET
                    }
                    "endsolid" -> {
                        require(wrapperOpen && state == AsciiState.EXPECT_FACET) {
                            "Unexpected endsolid at line $lineNumber"
                        }
                        wrapperClosed = true
                    }
                    else -> error("Unsupported ASCII STL token '$keyword' at line $lineNumber")
                }
            }
        }

        require(
            state == AsciiState.EXPECT_FACET ||
                (!sawFacet && state == AsciiState.EXPECT_FACET_OR_SOLID),
        ) { "ASCII STL ended inside an incomplete facet" }
        require(!wrapperOpen || wrapperClosed) { "ASCII STL is missing endsolid" }
        return AsciiScan(
            triangleCount = triangleCount,
            originX = originX ?: 0.0,
            originY = originY ?: 0.0,
            originZ = originZ ?: 0.0,
        )
    }

    private fun parseFinite(token: String, subject: String, lineNumber: Int): Double {
        val value = token.toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid $subject number '$token' at line $lineNumber")
        require(value.isFinite()) { "Non-finite $subject at line $lineNumber" }
        return value
    }

    private fun readFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            check(channel.read(buffer) > 0) { "Binary STL ended unexpectedly" }
        }
    }

    private interface TriangleConsumer {
        fun accept(
            x0: Double,
            y0: Double,
            z0: Double,
            x1: Double,
            y1: Double,
            z1: Double,
            x2: Double,
            y2: Double,
            z2: Double,
        )
    }

    private data class AsciiScan(
        val triangleCount: Int,
        val originX: Double,
        val originY: Double,
        val originZ: Double,
    )

    private enum class AsciiState {
        EXPECT_FACET_OR_SOLID,
        EXPECT_FACET,
        EXPECT_VERTEX,
        EXPECT_OUTER_LOOP,
        EXPECT_END_LOOP,
        EXPECT_END_FACET,
    }

    private class BoundsAccumulator {
        private var minX = Float.POSITIVE_INFINITY
        private var minY = Float.POSITIVE_INFINITY
        private var minZ = Float.POSITIVE_INFINITY
        private var maxX = Float.NEGATIVE_INFINITY
        private var maxY = Float.NEGATIVE_INFINITY
        private var maxZ = Float.NEGATIVE_INFINITY

        fun include(x: Float, y: Float, z: Float) {
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            minZ = minOf(minZ, z)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
            maxZ = maxOf(maxZ, z)
        }

        fun finish(): MeshBounds {
            require(minX.isFinite()) { "STL bounds could not be calculated" }
            return MeshBounds(minX, minY, minZ, maxX, maxY, maxZ)
        }
    }

    private const val FLOATS_PER_TRIANGLE = 18
    private const val BINARY_BLOCK_TRIANGLES = 4_096
    private const val STL_HEADER_BYTES = 84L
    private const val STL_TRIANGLE_BYTES = 50L

    /** Shorter than this cannot be an STL at all: `solid x\nendsolid\n`. */
    private const val MIN_STL_BYTES = 15L

    /** How much of an ASCII file's tail is searched for its terminator. */
    private const val ASCII_TAIL_BYTES = 1024L
    private const val NORMAL_EPSILON = 1e-12f
    private const val NORMAL_EPSILON_DOUBLE = 1e-18
    private val WHITESPACE = Regex("\\s+")

    private const val MAX_ASCII_LINE_LENGTH = 16 * 1024
}
