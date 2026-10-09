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
 * Writes a whole plate - every object on it - as one 3MF.
 *
 * Both engine consoles take exactly one input file and add every object inside
 * it; neither arranges the plate and neither accepts several positional files,
 * so several models can only reach them as one multi-object 3MF. The meshes
 * arrive already transformed into bed coordinates, so each is an object of its
 * own and each gets a build item: an object without an instance collapses to the
 * origin in both engines.
 *
 * The engines keep their per-object settings in different files, hence
 * [Dialect]:
 *
 * - [Dialect.ORCA] writes `Metadata/model_settings.config`, whose
 *   `<object id="N"><metadata key="..." value="..."/></object>` entries become
 *   that object's settings; the 1-based `extruder` key is how an object is
 *   assigned to a filament. The sidecar is written only when it carries
 *   something the model file cannot: object names ride the object's own `name`
 *   attribute, which both loaders read, so a plate without assigned filaments
 *   needs no sidecar at all.
 * - [Dialect.PRUSA_LEGACY] stamps `Application` with a PrusaSlicer version at
 *   or below 2.99.1, which is what routes PrusaSlicer's loader to its legacy
 *   dialect, and writes `Metadata/Slic3r_PE_model.config`. That loader takes
 *   each object's volumes from the file, so every object needs a
 *   `<volume firstid lastid>` covering its triangles or it loads with no
 *   geometry at all.
 *
 * The geometry, the paint attribute and the container are the ones
 * [PaintedMeshWriter] has always written and that both engines are known to
 * read; this writer is that structure with room for more than one object.
 * Output is deterministic: objects keep the caller's order and get 1-based ids,
 * and the archive's entry times are fixed.
 */
object PlateThreeMfWriter {
    /** Which engine dialect the file is written for. */
    enum class Dialect { ORCA, PRUSA_LEGACY }

    /** One object on the plate, already transformed into bed coordinates. */
    data class Entry(
        val name: String,
        val mesh: StlMesh,
        /** Support/enforcer and blocker/anti-overhang paint, when the object is painted. */
        val paint: SupportPaintState? = null,
        /** 1-based filament/extruder for this object, when the caller assigns one. */
        val extruder: Int? = null,
    )

    /**
     * The version stamp PrusaSlicer 3.0 reads as "written by an old PrusaSlicer"
     * and follows into its legacy loader. Any version up to 2.99.1 works; the one
     * this app ships understands 2.9.6.
     */
    private const val PRUSA_LEGACY_APPLICATION = "PrusaSlicer-2.9.6"
    private const val APPLICATION = "TrioSlicer"

    private const val CONTENT_TYPES_PATH = "[Content_Types].xml"
    private const val RELATIONSHIPS_PATH = "_rels/.rels"
    private const val MODEL_PATH = "3D/3dmodel.model"
    private const val ORCA_SETTINGS_PATH = "Metadata/model_settings.config"
    private const val PRUSA_SETTINGS_PATH = "Metadata/Slic3r_PE_model.config"
    private const val CORE_NAMESPACE = "http://schemas.microsoft.com/3dmanufacturing/core/2015/02"
    private const val SLIC3R_NAMESPACE = "http://schemas.slic3r.org/3mf/2017/06"
    private const val BUFFER_BYTES = 1 shl 16
    private const val BUFFER_CHARS = 1 shl 16
    /** 1980-01-01, the earliest time a zip can store, so two writes match byte for byte. */
    private const val ZIP_ENTRY_TIME_MILLIS = 315_532_800_000L

    /** Interleaved layout: three vertices of position xyz followed by normal xyz. */
    private const val FLOATS_PER_TRIANGLE = 18
    private const val FLOATS_PER_VERTEX = 6
    private const val FLOATS_PER_POSITION = 3

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

    /** Writes [entries] as one 3MF for [dialect]. Throws IOException on failure. */
    fun write(file: File, entries: List<Entry>, dialect: Dialect) {
        require(entries.isNotEmpty()) { "A plate must carry at least one object" }
        entries.forEach(::validate)

        file.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(file), BUFFER_BYTES)).use { zip ->
            writePart(zip, CONTENT_TYPES_PATH, CONTENT_TYPES)
            writePart(zip, RELATIONSHIPS_PATH, RELATIONSHIPS)
            zip.putNextEntry(part(MODEL_PATH))
            val writer = OutputStreamWriter(zip, Charsets.UTF_8)
            writeModel(writer, entries, dialect)
            writer.flush()
            zip.closeEntry()

            // PrusaSlicer's legacy loader needs the sidecar for the volumes of
            // every object; OrcaSlicer only needs it when an object gets a
            // setting the model file cannot carry.
            when (dialect) {
                Dialect.ORCA -> if (entries.any { it.extruder != null }) {
                    writePart(zip, ORCA_SETTINGS_PATH, writeOrcaSettings(entries))
                }

                Dialect.PRUSA_LEGACY -> writePart(zip, PRUSA_SETTINGS_PATH, writePrusaSettings(entries))
            }
        }
    }

    /** An entry that every run writes with the same name and timestamp. */
    private fun part(path: String): ZipEntry = ZipEntry(path).apply { time = ZIP_ENTRY_TIME_MILLIS }

    private fun writePart(zip: ZipOutputStream, path: String, text: String) {
        zip.putNextEntry(part(path))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun validate(entry: Entry) {
        val triangleCount = entry.mesh.triangleCount
        require(triangleCount > 0) { "The plate carries an object without triangles: " + entry.name }
        val vertices = entry.mesh.interleavedVertices
        require(vertices.size == triangleCount * FLOATS_PER_TRIANGLE) {
            "The mesh vertex buffer does not match its triangle count"
        }
        for (index in 0 until vertices.size) {
            require(vertices[index].isFinite()) { "The mesh contains a non-finite coordinate" }
        }
        val paint = entry.paint
        if (paint != null) {
            require(paint.enforcerTriangles.all { it in 0 until triangleCount }) {
                "Painted support enforcers must address the staged mesh"
            }
            require(paint.blockerTriangles.all { it in 0 until triangleCount }) {
                "Painted support blockers must address the staged mesh"
            }
        }
        require(entry.extruder == null || entry.extruder >= 1) { "An extruder is 1-based" }
    }

    private fun writeModel(writer: Writer, entries: List<Entry>, dialect: Dialect) {
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
        append(" <metadata name=\"Application\">")
        append(if (dialect == Dialect.PRUSA_LEGACY) PRUSA_LEGACY_APPLICATION else APPLICATION)
        append("</metadata>\n")
        append(" <resources>\n")
        for ((index, entry) in entries.withIndex()) {
            writeObject(::append, index + 1, entry, dialect)
        }
        // The transform is omitted because the meshes already sit on the bed: a
        // missing transform is the identity in both readers. Every object still
        // needs its item, or it would have no instance at all.
        append(" </resources>\n <build>")
        for (index in entries.indices) {
            append("<item objectid=\"")
            append((index + 1).toString())
            append("\"/>")
        }
        append("</build>\n</model>\n")

        writer.append(buffer)
    }

    /** One object: its mesh, its name and the paint on its triangles. */
    private fun writeObject(append: (String) -> Unit, id: Int, entry: Entry, dialect: Dialect) {
        val mesh = entry.mesh
        val paint = entry.paint
        val paintAttribute = paintAttribute(dialect)
        val vertices = mesh.interleavedVertices
        val triangleCount = mesh.triangleCount
        val indices = IntArray(triangleCount * 3)
        val known = HashMap<Long, Int>(triangleCount * 2)
        val unique = ArrayList<Float>(triangleCount * FLOATS_PER_POSITION)
        var vertexCount = 0

        append("  <object id=\"")
        append(id.toString())
        append("\" name=\"")
        append(escape(entry.name))
        append("\" type=\"model\">\n   <mesh>\n    <vertices>\n")

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
                paint != null && triangle in paint.enforcerTriangles -> {
                    append(" $paintAttribute=\"${PaintedMeshWriter.ENFORCER_CODE}\"")
                }

                paint != null && triangle in paint.blockerTriangles -> {
                    append(" $paintAttribute=\"${PaintedMeshWriter.BLOCKER_CODE}\"")
                }
            }
            append("/>\n")
        }
        append("    </triangles>\n   </mesh>\n  </object>\n")
    }

    /**
     * The triangle attribute each reader takes painted supports from.
     *
     * OrcaSlicer keeps PrusaSlicer's name only in the reader its GUI uses; its console loads a
     * 3MF through the Bambu reader, which looks for "paint_supports" and ignores the Prusa name
     * outright - the shipped console binary carries no "slic3rpe:custom_supports" string at all,
     * so a file named the Prusa way arrives unpainted. The values are the same encoding.
     */
    private fun paintAttribute(dialect: Dialect): String = when (dialect) {
        Dialect.PRUSA_LEGACY -> "slic3rpe:custom_supports"
        Dialect.ORCA -> "paint_supports"
    }

    /** OrcaSlicer's per-object settings, in the order the plate lists its objects. */
    private fun writeOrcaSettings(entries: List<Entry>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<config>\n")
        for ((index, entry) in entries.withIndex()) {
            append(" <object id=\"")
            append((index + 1).toString())
            append("\">\n  <metadata key=\"name\" value=\"")
            append(escape(entry.name))
            append("\"/>\n")
            if (entry.extruder != null) {
                append("  <metadata key=\"extruder\" value=\"")
                append(entry.extruder.toString())
                append("\"/>\n")
            }
            append(" </object>\n")
        }
        append("</config>\n")
    }

    /** PrusaSlicer's per-object settings, one volume covering each object's whole mesh. */
    private fun writePrusaSettings(entries: List<Entry>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<config>\n")
        for ((index, entry) in entries.withIndex()) {
            val name = escape(entry.name)
            append(" <object id=\"")
            append((index + 1).toString())
            append("\" instancescount=\"1\">\n")
            append("  <metadata type=\"object\" key=\"name\" value=\"$name\"/>\n")
            if (entry.extruder != null) {
                append("  <metadata type=\"object\" key=\"extruder\" value=\"${entry.extruder}\"/>\n")
            }
            append("  <volume firstid=\"0\" lastid=\"${entry.mesh.triangleCount - 1}\">\n")
            append("   <metadata type=\"volume\" key=\"name\" value=\"$name\"/>\n")
            append("   <metadata type=\"volume\" key=\"volume_type\" value=\"ModelPart\"/>\n")
            append("  </volume>\n </object>\n")
        }
        append("</config>\n")
    }

    /** Escapes what may not appear literally in XML text or attributes. */
    private fun escape(text: String): String = buildString(text.length) {
        for (character in text) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\"' -> append("&quot;")
                '\'' -> append("&apos;")
                // XML 1.0 has no escape for the C0 controls; dropping one keeps
                // the whole file well formed rather than failing the export.
                in '\u0000'..'\u001f' -> Unit
                else -> append(character)
            }
        }
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
