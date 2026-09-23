package com.tomppi.enderslicer.model

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.OFF_HEAP_MIN_TRIANGLES
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlSliceTransform
import com.tomppi.enderslicer.viewer.VertexData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A model-space linear transform followed by placement using the transformed
 * XY bounds center and minimum Z. Transformations use streaming passes over the
 * interleaved source vertices so large meshes do not require a second full
 * DoubleArray beside the rendered output.
 */
data class ModelPlacement(
    val linear: List<Double> = IDENTITY,
    val centerXmm: Double,
    val centerYmm: Double,
    val baseZmm: Double,
    val source: String = "Centered on build plate",
) {
    init {
        require(linear.size == 9) { "Model transform must contain nine linear values" }
        require(linear.all(Double::isFinite)) { "Model transform contains a non-finite value" }
        require(centerXmm.isFinite() && centerYmm.isFinite() && baseZmm.isFinite()) {
            "Model placement contains a non-finite position"
        }
    }

    data class Affine3mf(
        val linear: List<Double>,
        val translationXmm: Double,
        val translationYmm: Double,
        val translationZmm: Double,
        val targetCenterXmm: Double? = null,
        val targetCenterYmm: Double? = null,
        val targetBaseZmm: Double? = null,
    ) {
        init {
            require(linear.size == 9) { "3MF transform must contain nine linear values" }
            require(linear.all(Double::isFinite)) { "3MF transform contains a non-finite linear value" }
            require(listOf(translationXmm, translationYmm, translationZmm).all(Double::isFinite)) {
                "3MF transform contains a non-finite translation"
            }
            require(listOfNotNull(targetCenterXmm, targetCenterYmm, targetBaseZmm).all(Double::isFinite)) {
                "3MF target bounds contain a non-finite value"
            }
        }
    }

    fun transformed(mesh: StlMesh): StlMesh {
        val rawBounds = boundsFor(mesh, linear)
        val dx = centerXmm - rawBounds.centerX
        val dy = centerYmm - rawBounds.centerY
        val dz = baseZmm - rawBounds.minZ
        // The placed display copy is written on every placement. Big meshes
        // keep it in a direct native buffer (the same rule the STL parser
        // uses) so a multi-million-triangle placed model does not sit on the
        // Java heap - the previous FloatArray copy could be 144-430 MB and is
        // what left no heap room for the nozzle-path view on big prints.
        val offHeap = mesh.triangleCount >= OFF_HEAP_MIN_TRIANGLES
        val outputFloatCount = Math.multiplyExact(mesh.triangleCount, 18)
        val arrayOutput: FloatArray? = if (offHeap) null else FloatArray(outputFloatCount)
        val directOutput: FloatBuffer? = if (offHeap) {
            ByteBuffer.allocateDirect(outputFloatCount * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        } else null
        fun setFloat(index: Int, value: Float) {
            if (directOutput != null) directOutput.put(index, value) else arrayOutput!![index] = value
        }
        val input = mesh.interleavedVertices
        var inputOffset = 0
        var outputOffset = 0
        val outputBounds = FloatBoundsAccumulator()

        repeat(mesh.triangleCount) {
            val x0 = transformX(linear, input[inputOffset].toDouble(), input[inputOffset + 1].toDouble(), input[inputOffset + 2].toDouble()) + dx
            val y0 = transformY(linear, input[inputOffset].toDouble(), input[inputOffset + 1].toDouble(), input[inputOffset + 2].toDouble()) + dy
            val z0 = transformZ(linear, input[inputOffset].toDouble(), input[inputOffset + 1].toDouble(), input[inputOffset + 2].toDouble()) + dz
            val x1 = transformX(linear, input[inputOffset + 6].toDouble(), input[inputOffset + 7].toDouble(), input[inputOffset + 8].toDouble()) + dx
            val y1 = transformY(linear, input[inputOffset + 6].toDouble(), input[inputOffset + 7].toDouble(), input[inputOffset + 8].toDouble()) + dy
            val z1 = transformZ(linear, input[inputOffset + 6].toDouble(), input[inputOffset + 7].toDouble(), input[inputOffset + 8].toDouble()) + dz
            val x2 = transformX(linear, input[inputOffset + 12].toDouble(), input[inputOffset + 13].toDouble(), input[inputOffset + 14].toDouble()) + dx
            val y2 = transformY(linear, input[inputOffset + 12].toDouble(), input[inputOffset + 13].toDouble(), input[inputOffset + 14].toDouble()) + dy
            val z2 = transformZ(linear, input[inputOffset + 12].toDouble(), input[inputOffset + 13].toDouble(), input[inputOffset + 14].toDouble()) + dz

            val ax = x1 - x0
            val ay = y1 - y0
            val az = z1 - z0
            val bx = x2 - x0
            val by = y2 - y0
            val bz = z2 - z0
            var nx = ay * bz - az * by
            var ny = az * bx - ax * bz
            var nz = ax * by - ay * bx
            val normalLength = sqrt(nx * nx + ny * ny + nz * nz)
            if (normalLength > 1e-12) {
                nx /= normalLength
                ny /= normalLength
                nz /= normalLength
            } else {
                nx = 0.0
                ny = 0.0
                nz = 0.0
            }

            fun writeVertex(x: Double, y: Double, z: Double) {
                val xf = x.toFloat()
                val yf = y.toFloat()
                val zf = z.toFloat()
                setFloat(outputOffset++, xf)
                setFloat(outputOffset++, yf)
                setFloat(outputOffset++, zf)
                setFloat(outputOffset++, nx.toFloat())
                setFloat(outputOffset++, ny.toFloat())
                setFloat(outputOffset++, nz.toFloat())
                outputBounds.include(xf, yf, zf)
            }
            writeVertex(x0, y0, z0)
            writeVertex(x1, y1, z1)
            writeVertex(x2, y2, z2)
            inputOffset += 18
        }

        return StlMesh(
            displayName = mesh.displayName,
            interleavedVertices = if (offHeap) VertexData.fromDirect(directOutput!!) else VertexData.fromArray(arrayOutput!!),
            triangleCount = mesh.triangleCount,
            bounds = outputBounds.finish(),
            slicingSourceInterleavedVertices = mesh.interleavedVertices,
            slicingTransform = StlSliceTransform(
                linear = linear.toList(),
                translationXmm = dx,
                translationYmm = dy,
                translationZmm = dz,
            ),
        )
    }

    fun moved(
        centerXmm: Double = this.centerXmm,
        centerYmm: Double = this.centerYmm,
        baseZmm: Double = this.baseZmm,
    ): ModelPlacement = copy(
        centerXmm = centerXmm,
        centerYmm = centerYmm,
        baseZmm = baseZmm,
        source = "Manual placement",
    )

    fun droppedToBed(): ModelPlacement = copy(baseZmm = 0.0, source = "Dropped to build plate")

    fun scaled(percent: Double): ModelPlacement {
        require(percent.isFinite() && percent > 0.0) { "Scale percentage must be positive and finite" }
        val factor = percent / 100.0
        val scale = listOf(factor, 0.0, 0.0, 0.0, factor, 0.0, 0.0, 0.0, factor)
        val label = if (percent == percent.toLong().toDouble()) {
            percent.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.3f", percent).trimEnd('0').trimEnd('.')
        }
        return copy(linear = multiply(scale, linear), source = "Scaled to $label%")
    }

    fun rotated(axis: Axis, degrees: Double): ModelPlacement {
        require(degrees.isFinite()) { "Rotation must be finite" }
        val radians = Math.toRadians(degrees)
        val c = cos(radians)
        val s = sin(radians)
        val rotation = when (axis) {
            Axis.X -> listOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)
            Axis.Y -> listOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c)
            Axis.Z -> listOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
        }
        return copy(linear = multiply(rotation, linear), source = "Manual rotation")
    }

    fun layFlat(mesh: StlMesh): ModelPlacement {
        val patch = PlanarPatchSelector.largest(mesh, linear)
        val candidates = listOf(
            alignVector(patch.normal, doubleArrayOf(0.0, 0.0, 1.0)),
            alignVector(patch.normal, doubleArrayOf(0.0, 0.0, -1.0)),
        )
        val selected = candidates.minBy { candidate ->
            val candidateLinear = multiply(candidate, linear)
            val candidateBounds = boundsFor(mesh, candidateLinear)
            val faceZ = transformZ(
                candidate,
                patch.centroid[0],
                patch.centroid[1],
                patch.centroid[2],
            )
            abs(faceZ - candidateBounds.minZ)
        }
        return copy(
            linear = multiply(selected, linear),
            baseZmm = 0.0,
            source = "Laid flat on largest planar face",
        )
    }

    enum class Axis { X, Y, Z }

    companion object {
        val IDENTITY = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        fun centeredOnBed(
            mesh: StlMesh,
            bedWidthMm: Double,
            bedDepthMm: Double,
            originAtCenter: Boolean = false,
        ): ModelPlacement {
            require(bedWidthMm.isFinite() && bedWidthMm > 0.0) { "Bed width must be positive" }
            require(bedDepthMm.isFinite() && bedDepthMm > 0.0) { "Bed depth must be positive" }
            return ModelPlacement(
                centerXmm = if (originAtCenter) 0.0 else bedWidthMm / 2.0,
                centerYmm = if (originAtCenter) 0.0 else bedDepthMm / 2.0,
                baseZmm = 0.0,
            )
        }

        fun from3mf(
            mesh: StlMesh,
            affine: Affine3mf,
            dropToBuildPlate: Boolean,
        ): ModelPlacement {
            require(affine.linear.size == 9)
            val transformedBounds = boundsFor(mesh, affine.linear)
            val transformedOriginX = transformX(
                affine.linear,
                mesh.sourceOriginXmm,
                mesh.sourceOriginYmm,
                mesh.sourceOriginZmm,
            )
            val transformedOriginY = transformY(
                affine.linear,
                mesh.sourceOriginXmm,
                mesh.sourceOriginYmm,
                mesh.sourceOriginZmm,
            )
            val transformedOriginZ = transformZ(
                affine.linear,
                mesh.sourceOriginXmm,
                mesh.sourceOriginYmm,
                mesh.sourceOriginZmm,
            )
            val targetCenterX = affine.targetCenterXmm
                ?: transformedBounds.centerX + transformedOriginX + affine.translationXmm
            val targetCenterY = affine.targetCenterYmm
                ?: transformedBounds.centerY + transformedOriginY + affine.translationYmm
            val targetBaseZ = affine.targetBaseZmm
                ?: transformedBounds.minZ + transformedOriginZ + affine.translationZmm
            return ModelPlacement(
                linear = affine.linear,
                centerXmm = targetCenterX,
                centerYmm = targetCenterY,
                baseZmm = if (dropToBuildPlate) 0.0 else targetBaseZ,
                source = if (dropToBuildPlate) {
                    "Imported Cura transform · drop to bed"
                } else {
                    "Imported Cura transform"
                },
            )
        }

        private fun boundsFor(mesh: StlMesh, matrix: List<Double>): BoundsDouble {
            val values = mesh.interleavedVertices
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var minZ = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            var maxZ = Double.NEGATIVE_INFINITY
            var offset = 0
            repeat(mesh.triangleCount * 3) {
                val x = values[offset].toDouble()
                val y = values[offset + 1].toDouble()
                val z = values[offset + 2].toDouble()
                val tx = transformX(matrix, x, y, z)
                val ty = transformY(matrix, x, y, z)
                val tz = transformZ(matrix, x, y, z)
                minX = minOf(minX, tx)
                maxX = maxOf(maxX, tx)
                minY = minOf(minY, ty)
                maxY = maxOf(maxY, ty)
                minZ = minOf(minZ, tz)
                maxZ = maxOf(maxZ, tz)
                offset += 6
            }
            // The same all-axis rule as the rendered accumulator below: a
            // Double overflow in Y or Z is just as unusable as one in X.
            require(
                minX.isFinite() && minY.isFinite() && minZ.isFinite() &&
                    maxX.isFinite() && maxY.isFinite() && maxZ.isFinite(),
            ) { "Model bounds could not be calculated" }
            return BoundsDouble(minX, minY, minZ, maxX, maxY, maxZ)
        }

        private fun transformX(matrix: List<Double>, x: Double, y: Double, z: Double): Double =
            matrix[0] * x + matrix[1] * y + matrix[2] * z

        private fun transformY(matrix: List<Double>, x: Double, y: Double, z: Double): Double =
            matrix[3] * x + matrix[4] * y + matrix[5] * z

        private fun transformZ(matrix: List<Double>, x: Double, y: Double, z: Double): Double =
            matrix[6] * x + matrix[7] * y + matrix[8] * z

        private fun multiply(a: List<Double>, b: List<Double>): List<Double> = List(9) { index ->
            val row = index / 3
            val column = index % 3
            a[row * 3] * b[column] +
                a[row * 3 + 1] * b[3 + column] +
                a[row * 3 + 2] * b[6 + column]
        }

        private fun alignVector(fromRaw: DoubleArray, toRaw: DoubleArray): List<Double> {
            val from = normalized(fromRaw)
            val to = normalized(toRaw)
            val vx = from[1] * to[2] - from[2] * to[1]
            val vy = from[2] * to[0] - from[0] * to[2]
            val vz = from[0] * to[1] - from[1] * to[0]
            val dot = (from[0] * to[0] + from[1] * to[1] + from[2] * to[2]).coerceIn(-1.0, 1.0)
            val crossLength = sqrt(vx * vx + vy * vy + vz * vz)
            if (crossLength < 1e-12) {
                if (dot > 0.0) return IDENTITY
                val axis = if (abs(from[0]) < 0.9) {
                    normalized(doubleArrayOf(0.0, -from[2], from[1]))
                } else {
                    normalized(doubleArrayOf(-from[1], from[0], 0.0))
                }
                return axisAngle(axis, Math.PI)
            }
            return axisAngle(
                doubleArrayOf(vx / crossLength, vy / crossLength, vz / crossLength),
                acos(dot),
            )
        }

        private fun axisAngle(axis: DoubleArray, angle: Double): List<Double> {
            val x = axis[0]
            val y = axis[1]
            val z = axis[2]
            val c = cos(angle)
            val s = sin(angle)
            val one = 1.0 - c
            return listOf(
                c + x * x * one, x * y * one - z * s, x * z * one + y * s,
                y * x * one + z * s, c + y * y * one, y * z * one - x * s,
                z * x * one - y * s, z * y * one + x * s, c + z * z * one,
            )
        }

        private fun normalized(value: DoubleArray): DoubleArray {
            val length = sqrt(value[0] * value[0] + value[1] * value[1] + value[2] * value[2])
            require(length > 1e-12) { "Cannot normalize a zero-length vector" }
            return doubleArrayOf(value[0] / length, value[1] / length, value[2] / length)
        }

        private data class BoundsDouble(
            val minX: Double,
            val minY: Double,
            val minZ: Double,
            val maxX: Double,
            val maxY: Double,
            val maxZ: Double,
        ) {
            val centerX: Double get() = (minX + maxX) / 2.0
            val centerY: Double get() = (minY + maxY) / 2.0
        }

        private class FloatBoundsAccumulator {
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
                // Every axis, not just X: a finite transform can still overflow
                // Float on one axis (a 1e300 Y scale does exactly that), and the
                // X-only check let Infinity bounds through to surface much later
                // as an STL-writer failure or a non-finite placement.
                require(
                    minX.isFinite() && minY.isFinite() && minZ.isFinite() &&
                        maxX.isFinite() && maxY.isFinite() && maxZ.isFinite(),
                ) { "Transformed model bounds could not be calculated" }
                return MeshBounds(minX, minY, minZ, maxX, maxY, maxZ)
            }
        }
    }
}
