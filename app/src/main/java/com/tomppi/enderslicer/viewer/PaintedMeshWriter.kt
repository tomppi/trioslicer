package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.supportpaint.SupportPaintState
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Stages the displayed mesh as a 3MF that carries painted support enforcers and
 * blockers, because an STL cannot express "this facet is an enforcer, that one
 * is a blocker": PrusaSlicer and OrcaSlicer read paint from the model file, so a
 * painted model handed over as STL arrives unpainted and the paint is silently
 * ignored.
 *
 * Paint travels as a `slic3rpe:custom_supports` attribute on each painted
 * triangle, holding that triangle's serialised TriangleSelector state. The
 * state of an unsplit triangle is four bits — two bits of split-side count
 * (always zero here) followed by two bits of state, with NONE = 0,
 * ENFORCER = 1 and BLOCKER = 2 — packed into one hex digit, so an enforcer is
 * `4` and a blocker is `8`. Only whole, unsplit triangles are ever painted in
 * this app, so every attribute is that single digit and the digit-reversal the
 * multi-nibble encoding uses does not come into play.
 *
 * Vertices are deduplicated so shared corners are shared in the file as well;
 * the comparison is on the exact float bits, so nothing is welded that the
 * mesh did not already have in common.
 */
object PaintedMeshWriter {
    /** The attribute PrusaSlicer and OrcaSlicer read painted supports from. */
    const val SUPPORT_PAINT_ATTRIBUTE = "slic3rpe:custom_supports"

    /** Serialised state of an unsplit support-enforcer triangle. */
    const val ENFORCER_CODE = "4"

    /** Serialised state of an unsplit support-blocker triangle. */
    const val BLOCKER_CODE = "8"

    private const val CONTENT_TYPES_PATH = "[Content_Types].xml"
    private const val RELATIONSHIPS_PATH = "_rels/.rels"
    private const val MODEL_PATH = "3D/3dmodel.model"
    private const val CORE_NAMESPACE = "http://schemas.microsoft.com/3dmanufacturing/core/2015/02"
    private const val SLIC3R_NAMESPACE = "http://schemas.slic3r.org/3mf/2017/06"
    /** Interleaved layout: three vertices of position xyz followed by normal xyz. */
    private const val FLOATS_PER_TRIANGLE = 18
    private const val FLOATS_PER_VERTEX = 6
    private const val FLOATS_PER_POSITION = 3
    private const val BUFFER_BYTES = 1 shl 16
    private const val BUFFER_CHARS = 1 shl 16

    private val CONTENT_TYPES = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
         <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
         <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
        </Types>
    """.trimIndent()

    private val RELATIONSHIPS = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
         <Relationship Target="/$MODEL_PATH" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
        </Relationships>
    """.trimIndent()

    fun write(mesh: StlMesh, paint: SupportPaintState, destination: File) {
        val triangleCount = mesh.triangleCount
        val vertices = mesh.interleavedVertices
        require(vertices.size == triangleCount * FLOATS_PER_TRIANGLE) {
            "The mesh vertex buffer does not match its triangle count"
        }
        require(paint.enforcerTriangles.all { it in 0 until triangleCount }) {
            "Painted support enforcers must address the staged mesh"
        }
        require(paint.blockerTriangles.all { it in 0 until triangleCount }) {
            "Painted support blockers must address the staged mesh"
        }
        for (index in 0 until vertices.size) {
            require(vertices[index].isFinite()) { "The mesh contains a non-finite coordinate" }
        }

        destination.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destination), BUFFER_BYTES)).use { zip ->
            writePart(zip, CONTENT_TYPES_PATH, CONTENT_TYPES)
            writePart(zip, RELATIONSHIPS_PATH, RELATIONSHIPS)
            zip.putNextEntry(ZipEntry(MODEL_PATH))
            val writer = OutputStreamWriter(zip, Charsets.UTF_8)
            writeModel(writer, mesh, paint)
            writer.flush()
            zip.closeEntry()
        }
    }

    private fun writePart(zip: ZipOutputStream, path: String, text: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun writeModel(writer: Writer, mesh: StlMesh, paint: SupportPaintState) {
        val vertices = mesh.interleavedVertices
        val triangleCount = mesh.triangleCount
        val indices = IntArray(triangleCount * 3)
        val known = HashMap<Long, Int>(triangleCount * 2)
        val unique = ArrayList<Float>(triangleCount * FLOATS_PER_POSITION)
        var vertexCount = 0

        val buffer = StringBuilder(BUFFER_CHARS)
        fun append(text: String) {
            buffer.append(text)
            if (buffer.length >= BUFFER_CHARS) {
                writer.append(buffer)
                buffer.setLength(0)
            }
        }

        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<model unit=\"millimeter\" xml:lang=\"en-US\" xmlns=\"$CORE_NAMESPACE\"")
        append(" xmlns:slic3rpe=\"$SLIC3R_NAMESPACE\">\n")
        append(" <metadata name=\"Application\">TrioSlicer</metadata>\n")
        append(" <resources>\n  <object id=\"1\" type=\"model\">\n   <mesh>\n    <vertices>\n")

        for (triangle in 0 until triangleCount) {
            val base = triangle * FLOATS_PER_TRIANGLE
            for (corner in 0..2) {
                val offset = base + corner * FLOATS_PER_VERTEX
                val x = vertices[offset]
                val y = vertices[offset + 1]
                val z = vertices[offset + 2]
                val hash = vertexHash(x, y, z)
                val candidate = known[hash]
                val index = if (candidate != null && sameVertex(unique, candidate, x, y, z)) {
                    candidate
                } else {
                    val created = vertexCount++
                    known[hash] = created
                    unique.add(x)
                    unique.add(y)
                    unique.add(z)
                    append("     <vertex x=\"")
                    append(formatCoordinate(x))
                    append("\" y=\"")
                    append(formatCoordinate(y))
                    append("\" z=\"")
                    append(formatCoordinate(z))
                    append("\"/>\n")
                    created
                }
                indices[triangle * 3 + corner] = index
            }
        }

        append("    </vertices>\n    <triangles>\n")
        for (triangle in 0 until triangleCount) {
            append("     <triangle v1=\"")
            append(indices[triangle * 3].toString())
            append("\" v2=\"")
            append(indices[triangle * 3 + 1].toString())
            append("\" v3=\"")
            append(indices[triangle * 3 + 2].toString())
            append("\"")
            // Unpainted triangles carry no attribute at all: the readers treat a
            // missing value as "not painted", and leaving them out keeps the file
            // (and its deflated size) proportional to the paint, not the model.
            when {
                triangle in paint.enforcerTriangles -> {
                    append(" $SUPPORT_PAINT_ATTRIBUTE=\"$ENFORCER_CODE\"")
                }

                triangle in paint.blockerTriangles -> {
                    append(" $SUPPORT_PAINT_ATTRIBUTE=\"$BLOCKER_CODE\"")
                }
            }
            append("/>\n")
        }
        append("    </triangles>\n   </mesh>\n  </object>\n </resources>\n")
        append(" <build><item objectid=\"1\"/></build>\n</model>\n")

        writer.append(buffer)
    }

    /** Round-trips through the float's own shortest representation. */
    private fun formatCoordinate(value: Float): String = String.format(Locale.ROOT, "%.5f", value)

    private fun sameVertex(unique: List<Float>, index: Int, x: Float, y: Float, z: Float): Boolean {
        val base = index * FLOATS_PER_POSITION
        return unique[base] == x && unique[base + 1] == y && unique[base + 2] == z
    }

    private fun vertexHash(x: Float, y: Float, z: Float): Long {
        var hash = java.lang.Float.floatToIntBits(x).toLong() * 31L
        hash = (hash + java.lang.Float.floatToIntBits(y)) * 31L
        hash = (hash + java.lang.Float.floatToIntBits(z)) * 31L
        hash = hash xor (hash ushr 29)
        return hash * -7046029254386353131L
    }
}
