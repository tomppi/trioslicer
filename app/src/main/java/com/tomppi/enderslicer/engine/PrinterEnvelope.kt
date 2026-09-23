package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlSliceTransform
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale
import org.json.JSONObject

/** Immutable build-volume and firmware identity used from preflight through publication. */
data class PrinterEnvelope(
    val widthMm: Double,
    val depthMm: Double,
    val heightMm: Double,
    val buildPlateShape: String,
    val originAtCenter: Boolean,
    val gcodeFlavor: String = DEFAULT_GCODE_FLAVOR,
) {
    init {
        require(widthMm.isFinite() && widthMm > 0.0) { "Machine width must be positive and finite" }
        require(depthMm.isFinite() && depthMm > 0.0) { "Machine depth must be positive and finite" }
        require(heightMm.isFinite() && heightMm > 0.0) { "Machine height must be positive and finite" }
        require(normalizedShape(buildPlateShape) in SUPPORTED_SHAPES) {
            "Unsupported build plate shape: $buildPlateShape"
        }
        require(gcodeFlavor.isNotBlank() && '\n' !in gcodeFlavor && '\r' !in gcodeFlavor) {
            "Machine G-code flavor is invalid"
        }
    }

    fun requireModelFits(mesh: StlMesh) {
        require(mesh.triangleCount > 0 && mesh.interleavedVertices.size == mesh.triangleCount * 18) {
            "Model geometry is incomplete"
        }
        var offset = 0
        var vertex = 1
        while (offset < mesh.interleavedVertices.size) {
            requirePoint(
                x = mesh.interleavedVertices[offset].toDouble(),
                y = mesh.interleavedVertices[offset + 1].toDouble(),
                z = mesh.interleavedVertices[offset + 2].toDouble(),
                context = "Model vertex $vertex",
            )
            offset += 6
            vertex++
        }
    }

    /** Streams the transformed binary STL staged for CuraEngine without a second mesh allocation. */
    fun requireBinaryStlFits(file: File, transform: StlSliceTransform? = null, label: String? = null) {
        require(file.isFile && file.length() >= STL_HEADER_BYTES) { "Staged model STL is unavailable" }
        file.inputStream().channel.use { channel ->
            val header = ByteBuffer.allocate(STL_HEADER_BYTES.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            readFully(channel, header)
            header.flip()
            header.position(80)
            val triangleCount = header.int.toLong() and 0xffffffffL
            require(triangleCount > 0L) { "Staged model STL is empty" }
            val expectedLength = Math.addExact(
                STL_HEADER_BYTES,
                Math.multiplyExact(triangleCount, STL_TRIANGLE_BYTES),
            )
            require(expectedLength == file.length()) { "Staged model STL has an invalid binary length" }

            val buffer = ByteBuffer
                .allocateDirect(BINARY_BLOCK_TRIANGLES * STL_TRIANGLE_BYTES.toInt())
                .order(ByteOrder.LITTLE_ENDIAN)
            var remaining = triangleCount
            var vertexNumber = 1L
            while (remaining > 0L) {
                val records = minOf(remaining, BINARY_BLOCK_TRIANGLES.toLong()).toInt()
                buffer.clear()
                buffer.limit(records * STL_TRIANGLE_BYTES.toInt())
                readFully(channel, buffer)
                buffer.flip()
                repeat(records) {
                    buffer.position(buffer.position() + NORMAL_BYTES)
                    repeat(3) {
                        val x = buffer.float.toDouble()
                        val y = buffer.float.toDouble()
                        val z = buffer.float.toDouble()
                        requirePoint(
                            x = transformedX(transform, x, y, z),
                            y = transformedY(transform, x, y, z),
                            z = transformedZ(transform, x, y, z),
                            context = if (label == null) {
                                "Model vertex $vertexNumber"
                            } else {
                                "$label vertex $vertexNumber"
                            },
                        )
                        vertexNumber++
                    }
                    buffer.short
                }
                remaining -= records
            }
        }
    }

    fun requireMotionMove(
        startX: Double,
        startY: Double,
        startZ: Double,
        endX: Double,
        endY: Double,
        endZ: Double,
        lineNumber: Int,
        layerNumber: Int?,
    ) {
        val location = layerNumber?.let { "line $lineNumber, layer $it" } ?: "line $lineNumber, startup/end G-code"
        requirePoint(startX, startY, startZ, "Motion start at $location")
        requirePoint(endX, endY, endZ, "Motion end at $location")
    }

    fun requireExtrusionMove(
        startX: Double,
        startY: Double,
        startZ: Double,
        endX: Double,
        endY: Double,
        endZ: Double,
        lineNumber: Int,
        layerNumber: Int?,
    ) {
        val location = layerNumber?.let { "line $lineNumber, layer $it" } ?: "line $lineNumber, startup"
        requirePoint(startX, startY, startZ, "Extrusion start at $location")
        requirePoint(endX, endY, endZ, "Extrusion end at $location")
    }

    fun contains(x: Double, y: Double, z: Double, toleranceMm: Double = DEFAULT_TOLERANCE_MM): Boolean {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false
        if (z < -toleranceMm || z > heightMm + toleranceMm) return false

        val centerX = if (originAtCenter) 0.0 else widthMm / 2.0
        val centerY = if (originAtCenter) 0.0 else depthMm / 2.0
        return when (normalizedShape(buildPlateShape)) {
            RECTANGULAR -> {
                val halfWidth = widthMm / 2.0 + toleranceMm
                val halfDepth = depthMm / 2.0 + toleranceMm
                x in (centerX - halfWidth)..(centerX + halfWidth) &&
                    y in (centerY - halfDepth)..(centerY + halfDepth)
            }
            ELLIPTIC -> {
                val radiusX = widthMm / 2.0 + toleranceMm
                val radiusY = depthMm / 2.0 + toleranceMm
                val normalizedX = (x - centerX) / radiusX
                val normalizedY = (y - centerY) / radiusY
                normalizedX * normalizedX + normalizedY * normalizedY <= 1.0
            }
            else -> false
        }
    }

    fun writeTo(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject()
                .put("version", FORMAT_VERSION)
                .put("widthMm", widthMm)
                .put("depthMm", depthMm)
                .put("heightMm", heightMm)
                .put("buildPlateShape", normalizedShape(buildPlateShape))
                .put("originAtCenter", originAtCenter)
                .put("gcodeFlavor", gcodeFlavor)
                .toString(),
        )
        check(file.isFile && file.length() > 0L) { "Unable to write the printer envelope" }
    }

    private fun transformedX(transform: StlSliceTransform?, x: Double, y: Double, z: Double): Double =
        transform?.let { it.linear[0] * x + it.linear[1] * y + it.linear[2] * z + it.translationXmm } ?: x

    private fun transformedY(transform: StlSliceTransform?, x: Double, y: Double, z: Double): Double =
        transform?.let { it.linear[3] * x + it.linear[4] * y + it.linear[5] * z + it.translationYmm } ?: y

    private fun transformedZ(transform: StlSliceTransform?, x: Double, y: Double, z: Double): Double =
        transform?.let { it.linear[6] * x + it.linear[7] * y + it.linear[8] * z + it.translationZmm } ?: z

    private fun requirePoint(x: Double, y: Double, z: Double, context: String) {
        if (contains(x, y, z)) return
        val origin = if (originAtCenter) "centered" else "front-left"
        throw OutsideBuildVolumeException(
            "$context is outside the ${format(widthMm)} x ${format(depthMm)} x ${format(heightMm)} mm " +
                "${normalizedShape(buildPlateShape)} build volume ($origin origin): " +
                "X=${format(x)}, Y=${format(y)}, Z=${format(z)}",
        )
    }

    private fun readFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            check(channel.read(buffer) > 0) { "Staged model STL ended unexpectedly" }
        }
    }

    class OutsideBuildVolumeException(message: String) : IllegalArgumentException(message)

    companion object {
        const val METADATA_FILE_NAME = "printer-envelope.json"
        const val DEFAULT_TOLERANCE_MM = 0.05
        const val DEFAULT_GCODE_FLAVOR = "Marlin"

        fun from(printer: PrinterDefinition): PrinterEnvelope = PrinterEnvelope(
            widthMm = printer.widthMm,
            depthMm = printer.depthMm,
            heightMm = printer.heightMm,
            buildPlateShape = normalizedShape(printer.buildPlateShape),
            originAtCenter = printer.originAtCenter,
            gcodeFlavor = printer.gcodeFlavor,
        )

        fun readFrom(file: File): PrinterEnvelope {
            require(file.isFile && file.length() > 0L) { "Published slice has no printer envelope" }
            val root = JSONObject(file.readText())
            require(root.getInt("version") == FORMAT_VERSION) { "Unsupported printer envelope version" }
            return PrinterEnvelope(
                widthMm = root.getDouble("widthMm"),
                depthMm = root.getDouble("depthMm"),
                heightMm = root.getDouble("heightMm"),
                buildPlateShape = root.getString("buildPlateShape"),
                originAtCenter = root.getBoolean("originAtCenter"),
                gcodeFlavor = root.optString("gcodeFlavor", DEFAULT_GCODE_FLAVOR)
                    .ifBlank { DEFAULT_GCODE_FLAVOR },
            )
        }

        private fun normalizedShape(value: String): String = when (value.trim().lowercase(Locale.US)) {
            "rectangular", "rectangle" -> RECTANGULAR
            "elliptic", "ellipse", "circular", "circle" -> ELLIPTIC
            else -> value.trim().lowercase(Locale.US)
        }

        private fun format(value: Double): String = formatDecimal(value, 5)

        private const val FORMAT_VERSION = 1
        private const val RECTANGULAR = "rectangular"
        private const val ELLIPTIC = "elliptic"
        private val SUPPORTED_SHAPES = setOf(RECTANGULAR, ELLIPTIC)
        private const val STL_HEADER_BYTES = 84L
        private const val STL_TRIANGLE_BYTES = 50L
        private const val NORMAL_BYTES = 12
        private const val BINARY_BLOCK_TRIANGLES = 4_096
    }
}
