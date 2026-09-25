package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import kotlin.math.sqrt
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Reads a 3MF model — mesh, build placement and painted support facets — so a
 * 3MF imports like an STL plus the paint the file already carries.
 *
 * Only the geometry a slicer needs is read: meshes, components, build items,
 * their affine transforms and the `slic3rpe:custom_supports` attribute this app
 * also writes. Painting that splits a facet (a stroke edge crossing it) is left
 * unpainted rather than approximated, which is what the engines show before the
 * split is resolved.
 */
object ThreeMfModelParser {
    data class Parsed(
        val mesh: StlMesh,
        val paint: SupportPaintState,
    )

    private const val DEFAULT_MODEL_PATH = "3D/3dmodel.model"
    private const val SUPPORT_PAINT_ATTRIBUTE = "slic3rpe:custom_supports"
    private const val MAX_COMPONENT_DEPTH = 32
    private const val POSITION_FLOATS = 3

    fun parse(
        file: File,
        displayName: String = file.name,
        maxTriangles: Int = MeshTriangleLimits.current(),
    ): Parsed {
        require(file.isFile && file.length() > 0L) { "The selected model is empty or unavailable" }
        val limit = MeshTriangleLimits.sanitize(maxTriangles)
        val documents = ZipFile(file).use { zip ->
            val entry = zip.getEntry(modelPart(zip))
                ?: zip.getEntry(DEFAULT_MODEL_PATH)
                ?: throw IllegalArgumentException("This 3MF has no 3D model part")
            zip.getInputStream(entry).use { input ->
                val handler = ModelHandler()
                SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser()
                    .parse(input, handler)
                handler.finish()
            }
        }

        val scale = unitScale(documents.unit)
        val positions = ArrayList<Float>()
        val enforcers = HashSet<Int>()
        val blockers = HashSet<Int>()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE

        fun emit(objectId: String, parent: DoubleArray, depth: Int) {
            require(depth <= MAX_COMPONENT_DEPTH) { "This 3MF nests components too deeply" }
            val model = documents.objects[objectId] ?: return
            for (component in model.components) {
                emit(component.objectId, multiply(parent, component.matrix), depth + 1)
            }
            val vertices = model.vertices
            val point = DoubleArray(3)
            for (triangle in model.triangles) {
                require(positions.size / (POSITION_FLOATS * 3) < limit) {
                    "The 3MF has more than " + MeshTriangleLimits.formatCount(limit) + " triangles at the current mesh triangle limit"
                }
                val index = positions.size / (POSITION_FLOATS * 3)
                for (corner in intArrayOf(triangle.v1, triangle.v2, triangle.v3)) {
                    val base = corner * POSITION_FLOATS
                    apply(parent, vertices[base].toDouble(), vertices[base + 1].toDouble(), vertices[base + 2].toDouble(), scale, point)
                    val x = point[0].toFloat()
                    val y = point[1].toFloat()
                    val z = point[2].toFloat()
                    require(x.isFinite() && y.isFinite() && z.isFinite()) {
                        "The 3MF places a vertex at a non-finite position"
                    }
                    positions.add(x)
                    positions.add(y)
                    positions.add(z)
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (z < minZ) minZ = z
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                    if (z > maxZ) maxZ = z
                }
                when (triangle.state) {
                    1 -> enforcers.add(index)
                    2 -> blockers.add(index)
                }
            }
        }

        val items = documents.items.ifEmpty {
            documents.objects.keys.map { ModelHandler.BuildItem(it, null) }
        }
        items.forEach { item -> emit(item.objectId, matrix(item.transform), 0) }
        require(positions.isNotEmpty()) { "This 3MF contains no triangles" }

        val normals = faceNormals(positions)
        val interleaved = FloatArray(positions.size / POSITION_FLOATS * 2 * POSITION_FLOATS)
        var source = 0
        var target = 0
        while (source < positions.size) {
            for (corner in 0..2) {
                interleaved[target] = positions[source]
                interleaved[target + 1] = positions[source + 1]
                interleaved[target + 2] = positions[source + 2]
                val normal = (source / (POSITION_FLOATS * 3)) * 3 + corner
                interleaved[target + 3] = normals[normal * 3]
                interleaved[target + 4] = normals[normal * 3 + 1]
                interleaved[target + 5] = normals[normal * 3 + 2]
                source += POSITION_FLOATS
                target += 6
            }
        }

        return Parsed(
            mesh = StlMesh(
                displayName = displayName,
                interleavedVertices = VertexData.fromArray(interleaved),
                triangleCount = positions.size / (POSITION_FLOATS * 3),
                bounds = MeshBounds(minX, minY, minZ, maxX, maxY, maxZ),
            ),
            paint = SupportPaintState(enforcerTriangles = enforcers, blockerTriangles = blockers),
        )
    }

    /** The model part named by the package relationships, which need not be the default. */
    private fun modelPart(zip: ZipFile): String {
        val relationships = zip.getEntry("_rels/.rels") ?: return DEFAULT_MODEL_PATH
        val target = zip.getInputStream(relationships).use { input ->
            val handler = RelationshipHandler()
            runCatching {
                SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser()
                    .parse(input, handler)
            }
            handler.modelTarget
        }
        return target?.removePrefix("/") ?: DEFAULT_MODEL_PATH
    }

    private fun unitScale(unit: String?): Double = when (unit?.lowercase()) {
        null, "", "millimeter" -> 1.0
        "micron" -> 0.001
        "centimeter" -> 10.0
        "inch" -> 25.4
        "foot" -> 304.8
        "meter" -> 1000.0
        else -> 1.0
    }

    /** 3MF writes a 4x3 row-major affine matrix; row vectors multiply from the left. */
    private fun matrix(values: DoubleArray?): DoubleArray {
        val result = DoubleArray(16)
        if (values == null) {
            result[0] = 1.0
            result[5] = 1.0
            result[10] = 1.0
            result[15] = 1.0
            return result
        }
        for (row in 0..2) {
            for (column in 0..2) result[row * 4 + column] = values[row * 3 + column]
        }
        for (column in 0..2) result[12 + column] = values[9 + column]
        result[15] = 1.0
        return result
    }

    /** [first] is applied before [second]. */
    private fun multiply(first: DoubleArray, second: DoubleArray): DoubleArray {
        val result = DoubleArray(16)
        for (row in 0..3) {
            for (column in 0..3) {
                var sum = 0.0
                for (step in 0..3) sum += first[row * 4 + step] * second[step * 4 + column]
                result[row * 4 + column] = sum
            }
        }
        return result
    }

    private fun apply(matrix: DoubleArray, x: Double, y: Double, z: Double, scale: Double, out: DoubleArray) {
        val sx = x * scale
        val sy = y * scale
        val sz = z * scale
        out[0] = sx * matrix[0] + sy * matrix[4] + sz * matrix[8] + matrix[12]
        out[1] = sx * matrix[1] + sy * matrix[5] + sz * matrix[9] + matrix[13]
        out[2] = sx * matrix[2] + sy * matrix[6] + sz * matrix[10] + matrix[14]
    }

    private fun faceNormals(positions: List<Float>): FloatArray {
        val normals = FloatArray(positions.size)
        var triangle = 0
        while (triangle * 9 < positions.size) {
            val base = triangle * 9
            val ax = positions[base + 3] - positions[base]
            val ay = positions[base + 4] - positions[base + 1]
            val az = positions[base + 5] - positions[base + 2]
            val bx = positions[base + 6] - positions[base]
            val by = positions[base + 7] - positions[base + 1]
            val bz = positions[base + 8] - positions[base + 2]
            var nx = ay * bz - az * by
            var ny = az * bx - ax * bz
            var nz = ax * by - ay * bx
            val length = sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
            if (length > 0f && length.isFinite()) {
                nx /= length
                ny /= length
                nz /= length
            } else {
                nx = 0f
                ny = 0f
                nz = 0f
            }
            for (corner in 0..2) {
                normals[base + corner * 3] = nx
                normals[base + corner * 3 + 1] = ny
                normals[base + corner * 3 + 2] = nz
            }
            triangle++
        }
        return normals
    }

    private class ModelHandler : DefaultHandler() {
        class BuildItem(val objectId: String, val transform: DoubleArray?)
        class Component(val objectId: String, val matrix: DoubleArray)
        class Triangle(val v1: Int, val v2: Int, val v3: Int, val state: Int)
        class Object3mf {
            val vertices = ArrayList<Float>()
            val triangles = ArrayList<Triangle>()
            val components = ArrayList<Component>()
        }

        val objects = LinkedHashMap<String, Object3mf>()
        val items = ArrayList<BuildItem>()
        var unit: String? = null
        private var current: Object3mf? = null
        private var buildItemId: String? = null
        private var buildItemTransform: DoubleArray? = null

        fun finish(): ModelHandler = this

        override fun startElement(uri: String?, localName: String, name: String, attributes: Attributes) {
            when (name.substringAfter(':')) {
                "model" -> unit = attributes.getValue("unit")
                "object" -> {
                    val id = attributes.getValue("id")
                    if (id != null) {
                        current = Object3mf()
                        objects[id] = current!!
                    }
                }
                "vertex" -> current?.vertices?.addAll(
                    listOf(
                        attributes.getValue("x").toFloat(),
                        attributes.getValue("y").toFloat(),
                        attributes.getValue("z").toFloat(),
                    ),
                )
                "triangle" -> current?.triangles?.add(
                    Triangle(
                        v1 = attributes.getValue("v1").toInt(),
                        v2 = attributes.getValue("v2").toInt(),
                        v3 = attributes.getValue("v3").toInt(),
                        state = paintState(attributes.getValue(SUPPORT_PAINT_ATTRIBUTE)),
                    ),
                )
                "component" -> {
                    val id = attributes.getValue("objectid")
                    if (id != null) current?.components?.add(Component(id, matrix(attributes.transform())))
                }
                "item" -> {
                    buildItemId = attributes.getValue("objectid")
                    buildItemTransform = attributes.transform()
                }
            }
        }

        override fun endElement(uri: String?, localName: String, name: String) {
            when (name.substringAfter(':')) {
                "object" -> current = null
                "item" -> {
                    buildItemId?.let { id -> items.add(BuildItem(id, buildItemTransform)) }
                    buildItemId = null
                    buildItemTransform = null
                }
            }
        }

        /**
         * The paint attribute is the facet's serialised selector state: four bits,
         * two of split-side count then two of state, as one hex digit. A facet the
         * paint splits has more digits, which this reader leaves unpainted.
         */
        private fun paintState(value: String?): Int {
            val text = value?.trim().orEmpty()
            if (text.length != 1) return 0
            val code = Character.digit(text[0], 16)
            if (code < 0 || code and 0b11 != 0) return 0
            return code shr 2
        }

        private fun Attributes.transform(): DoubleArray? {
            val text = getValue("transform")?.trim().orEmpty()
            if (text.isEmpty()) return null
            val values = text.split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
            require(values.size == 12) { "A 3MF transform must contain 12 values" }
            return values.toDoubleArray()
        }
    }

    private class RelationshipHandler : DefaultHandler() {
        var modelTarget: String? = null

        override fun startElement(uri: String?, localName: String, name: String, attributes: Attributes) {
            if (name.substringAfter(':') != "Relationship") return
            val type = attributes.getValue("Type").orEmpty()
            if (type.endsWith("/3dmodel")) modelTarget = attributes.getValue("Target")
        }
    }
}
