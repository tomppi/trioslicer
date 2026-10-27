package com.tomppi.enderslicer.solidcraft

import com.tomppi.enderslicer.model.DocumentPlacementMap
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.viewer.MeshPicker
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.ViewerOrientation
import com.tomppi.enderslicer.viewer.ViewerOrientationMath
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One face picked on the plate, as the engine's own B-rep reports it.
 *
 * A SolidCraft face can be addressed three ways, and this is all three: by NAME (the engine's
 * `model.faces` answer, stable across rebuilds where the name survives), by INDEX (what
 * `select.set` takes together with the body), and by a POINT ON the face (what every face
 * command such as `solid.offset_face` and `solid.shell` accepts). The centroid is reported
 * for the prompt and the HUD, never as a pick: `model.faces` itself warns that a centroid is
 * not always on the face.
 */
data class FacePoint(val x: Double, val y: Double, val z: Double) {
    operator fun minus(other: FacePoint): FacePoint = FacePoint(x - other.x, y - other.y, z - other.z)

    operator fun plus(other: FacePoint): FacePoint = FacePoint(x + other.x, y + other.y, z + other.z)

    operator fun times(factor: Double): FacePoint = FacePoint(x * factor, y * factor, z * factor)

    fun dot(other: FacePoint): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: FacePoint): FacePoint = FacePoint(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    val length: Double get() = sqrt(x * x + y * y + z * z)

    fun normalized(): FacePoint {
        val size = length
        return if (size <= 1e-12) this else FacePoint(x / size, y / size, z / size)
    }

    fun distanceTo(other: FacePoint): Double = (this - other).length

    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    /** "20, 0, 6" - the form the prompt block and the HUD use, in millimetres. */
    fun format(): String = mmAlone(x) + ", " + mmAlone(y) + ", " + mmAlone(z)

    companion object {
        val ZERO = FacePoint(0.0, 0.0, 0.0)

        fun of(array: JSONArray?): FacePoint? {
            if (array == null || array.length() < 3) return null
            val point = FacePoint(array.optDouble(0), array.optDouble(1), array.optDouble(2))
            return point.takeIf { it.isFinite() }
        }
    }
}

/** One number with up to two decimals, and no trailing zeroes: 480, 12.5, 0.05. */
fun mmAlone(value: Double): String {
    val rounded = Math.round(value * 100.0) / 100.0
    if (rounded == rounded.toLong().toDouble()) return rounded.toLong().toString()
    return String.format(Locale.US, "%.2f", rounded).trimEnd('0').trimEnd('.')
}

/** The axis of a cylindrical engine face: a point on it, its direction, and its radius. */
data class FaceCylinder(val origin: FacePoint, val axis: FacePoint, val radiusMm: Double)

/** How a face's surface is shaped, in the engine's own terms. */
enum class FaceSurface(val label: String) {
    /** A plane: the engine reports a plane normal. */
    PLANAR("planar"),

    /** A cylinder: the engine reports its axis and radius. */
    CYLINDRICAL("cylindrical"),

    /** Anything else - a fillet, a B-spline, a cone, a sphere. */
    CURVED("curved"),
}

/**
 * One face exactly as `model.faces {body}` (and `body_topology`) reports it.
 *
 * Verified against engine 0.24.0 on the phone: a box's face is
 * `{index, name, exact, area, centroid, plane_normal, point}` and a cylinder's side carries
 * `cylinder: {origin, axis, radius}` with `plane_normal: null`.
 */
data class EngineFace(
    val index: Int,
    val name: String,
    val exact: Boolean,
    val areaMm2: Double,
    val centroid: FacePoint,
    /** The plane normal, present exactly for a planar face. */
    val planeNormal: FacePoint?,
    /** A point on the face that picks it - what the face commands call a face point. */
    val pickPoint: FacePoint,
    /** The axis and radius, present exactly for a cylindrical face. */
    val cylinder: FaceCylinder?,
) {
    val surface: FaceSurface
        get() = when {
            planeNormal != null -> FaceSurface.PLANAR
            cylinder != null -> FaceSurface.CYLINDRICAL
            else -> FaceSurface.CURVED
        }
}

/** Reads the engine's face list out of its `model.faces` (or `body_topology`) answer. */
object EngineFaces {
    fun parse(json: JSONObject): List<EngineFace> {
        val faces = json.optJSONArray("faces") ?: return emptyList()
        val parsed = ArrayList<EngineFace>(faces.length())
        for (index in 0 until faces.length()) {
            val face = faces.optJSONObject(index) ?: continue
            val centroid = FacePoint.of(face.optJSONArray("centroid")) ?: continue
            val normal = FacePoint.of(face.optJSONArray("plane_normal"))
            val pick = FacePoint.of(face.optJSONArray("point")) ?: centroid
            val cylinderJson = face.optJSONObject("cylinder")
            val cylinder = cylinderJson?.let { source ->
                val origin = FacePoint.of(source.optJSONArray("origin"))
                val axis = FacePoint.of(source.optJSONArray("axis"))
                if (origin == null || axis == null) null else FaceCylinder(
                    origin = origin,
                    axis = axis.normalized(),
                    radiusMm = source.optDouble("radius", 0.0),
                )
            }
            parsed += EngineFace(
                index = face.optInt("index", index),
                name = face.optString("name").takeIf { it.isNotBlank() } ?: ("face " + index),
                exact = face.optBoolean("exact", false),
                areaMm2 = face.optDouble("area", 0.0),
                centroid = centroid,
                planeNormal = normal,
                pickPoint = pick,
                cylinder = cylinder,
            )
        }
        return parsed
    }
}

/** The face the user picked, in the words the HUD and the prompt block use. */
data class SelectedFace(
    val body: String,
    val index: Int,
    val name: String,
    val surface: FaceSurface,
    val exact: Boolean,
    val areaMm2: Double,
    /** The face's area centre, in the document's own millimetres. */
    val centroid: FacePoint,
    /** The outward normal at the picked triangle, in the document's own coordinates. */
    val normal: FacePoint,
    /** The picked point itself, in the document's own coordinates: what a face command takes. */
    val pickPoint: FacePoint,
    /** A cylindrical face's radius, null for every other surface. */
    val cylinderRadiusMm: Double?,
    /** Which way it faces from where the user is standing: "top", "the face facing you". */
    val viewWords: String,
) {
    /** "Body1 · F1:-y (face 1) · planar · 480 mm² · the face facing you". */
    fun hudLine(): String =
        body + " · " + name + " (face " + index + ") · " + surfaceLabel() + " · " +
            mmAlone(areaMm2) + " mm² · " + viewWords

    /** The surface in one word, with the cylinder's radius when it is one. */
    fun surfaceLabel(): String {
        val base = surface.label
        return if (exact) base else base + " (approximate)"
    }

    /**
     * The block a message carries to the agent.
     *
     * It states the truth the engine gave - body, name, index, surface, area, centroid, normal,
     * and the picked point a face command takes - and the one thing only the app knows: which
     * way the face is turned from the camera the user is looking through. It ends with a blank
     * line so the user's own words follow it unaltered.
     */
    fun promptBlock(): String = buildString {
        append("SELECTED FACE (the user picked this face on the plate; act on THIS face, ")
        append("not on a guess)\n")
        append("body: ").append(body).append('\n')
        append("face: ").append(name).append(" (index ").append(index).append(")\n")
        append("surface: ").append(surface.label)
        if (!exact) append(" (approximate)")
        if (surface == FaceSurface.CYLINDRICAL && cylinderRadiusMm != null) {
            // The radius is what a "make this thicker" on a hole means; the engine reports it.
            append(", radius ").append(mmAlone(cylinderRadiusMm)).append(" mm")
        }
        append('\n')
        append("area: ").append(mmAlone(areaMm2)).append(" mm²\n")
        append("centroid: (").append(centroid.format()).append(") mm\n")
        append("normal: (").append(normal.format()).append(")\n")
        append("point on the face: (").append(pickPoint.format()).append(") mm\n")
        append("on screen: ").append(viewWords).append('\n')
        append('\n')
    }
}

/**
 * Which face a point of the document belongs to.
 *
 * The engine's face list is the whole truth here; nothing about the face's wire loop is
 * available, so a face is its surface: a point belongs to a planar face when it sits on that
 * plane, to a cylindrical one when it sits on that cylinder, and to any other when its nearest
 * area centre is that face's. A point on a shared edge is on two faces at once and goes to the
 * one whose area centre is nearer - which is also what separates the two halves a full cylinder's
 * side is split into. The tolerances cover tessellation, not sloppiness: a chord of a
 * tessellated cylinder lies inside the true surface by its sagitta.
 */
object FacePicker {
    /** How far a point may sit off a plane and still be on its face, in mm. */
    const val PLANE_TOLERANCE_MM = 0.05

    /** How far a tessellated point may sit inside a cylinder, in mm. */
    const val CYLINDER_TOLERANCE_MM = 0.6

    /** The face [point] (document millimetres) belongs to, or null when there are no faces. */
    fun match(faces: List<EngineFace>, point: FacePoint): EngineFace? {
        if (faces.isEmpty()) return null
        val onSurface = faces.filter { surfaceDistanceMm(it, point) <= toleranceMm(it) }
        val pool = if (onSurface.isNotEmpty()) onSurface else faces
        return pool.minByOrNull { point.distanceTo(it.centroid) }
    }

    /** How far a point is off one face's own surface, in millimetres. */
    fun surfaceDistanceMm(face: EngineFace, point: FacePoint): Double {
        val normal = face.planeNormal
        if (normal != null) return abs(normal.normalized().dot(point - face.pickPoint))
        val cylinder = face.cylinder
        if (cylinder != null) {
            val relative = point - cylinder.origin
            val along = relative.dot(cylinder.axis)
            val radial = relative - cylinder.axis * along
            return abs(radial.length - cylinder.radiusMm)
        }
        return point.distanceTo(face.centroid)
    }

    private fun toleranceMm(face: EngineFace): Double = when (face.surface) {
        FaceSurface.PLANAR -> PLANE_TOLERANCE_MM
        FaceSurface.CYLINDRICAL -> CYLINDER_TOLERANCE_MM
        FaceSurface.CURVED -> Double.MAX_VALUE
    }

    /**
     * The triangles of [bodyMesh] (document coordinates) that belong to [target].
     *
     * Returned as the body's own triangle indices: the plate's renderer tints triangles, so the
     * answer is the selection a tint buffer is filled from, not a second mesh. A triangle that
     * only looks like it is on the face - a neighbour's coplanar face, a different face of the
     * same cylinder - is classified by the same surface rule the tap was, and by the nearest
     * face centre when two faces share a surface.
     */
    fun selectedTriangles(
        bodyMesh: StlMesh,
        faces: List<EngineFace>,
        target: EngineFace,
    ): IntArray {
        if (bodyMesh.triangleCount <= 0 || faces.isEmpty()) return EMPTY_TRIANGLES
        val vertices = bodyMesh.interleavedVertices
        var found = IntArray(minOf(bodyMesh.triangleCount, INITIAL_SELECTION))
        var size = 0
        for (triangle in 0 until bodyMesh.triangleCount) {
            val base = triangle * 18
            val centre = FacePoint(
                (vertices[base].toDouble() + vertices[base + 6] + vertices[base + 12]) / 3.0,
                (vertices[base + 1].toDouble() + vertices[base + 7] + vertices[base + 13]) / 3.0,
                (vertices[base + 2].toDouble() + vertices[base + 8] + vertices[base + 14]) / 3.0,
            )
            val matched = match(faces, centre) ?: continue
            if (matched.index != target.index) continue
            if (size == found.size) found = found.copyOf(found.size * 2)
            found[size++] = triangle
        }
        return if (size == 0) EMPTY_TRIANGLES else found.copyOf(size)
    }

    private val EMPTY_TRIANGLES = IntArray(0)

    /** The first guess at a face's triangle count, so a selection allocates once. */
    private const val INITIAL_SELECTION = 64

    /** The triangle's own outward normal, averaged over its three stored normals. */
    fun triangleNormal(mesh: StlMesh, triangle: Int): FacePoint {
        val vertices = mesh.interleavedVertices
        val base = triangle * 18
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (corner in 0 until 3) {
            x += vertices[base + corner * 6 + 3].toDouble()
            y += vertices[base + corner * 6 + 4].toDouble()
            z += vertices[base + corner * 6 + 5].toDouble()
        }
        return FacePoint(x, y, z).normalized()
    }
}

/**
 * Turns a tap on the plate into the engine face under it, and the prompt block for the agent.
 *
 * The picker answers in PLATE coordinates: the derived mesh is already placed by the plate
 * object's own transform. The engine's document has its own coordinates, so the tap is mapped
 * back through that transform before it is matched - a part the user moved on the bed still
 * signals the right face. The normal comes from the picked triangle itself rather than from the
 * face's plane data, so a hole's wall reads as facing into the hole and a boss's as facing out.
 */
object FacePickMapper {

    /**
     * One pick, resolved.
     *
     * @param localTriangleIndex the triangle's index inside its own body's mesh, which is what
     *   the document-space mesh (and therefore the normal) can be read at.
     */
    fun resolve(
        hit: MeshPicker.Hit,
        localTriangleIndex: Int,
        body: String,
        bodyMesh: StlMesh,
        displayMesh: StlMesh,
        placement: ModelPlacement,
        faces: List<EngineFace>,
        orientation: ViewerOrientation?,
    ): FacePick? {
        val map = placement.documentPlacement(displayMesh)
        val documentCoordinates = map.toDocument(hit.x.toDouble(), hit.y.toDouble(), hit.z.toDouble())
            ?: return null
        val documentPoint = FacePoint(
            documentCoordinates[0],
            documentCoordinates[1],
            documentCoordinates[2],
        )
        val matched = FacePicker.match(faces, documentPoint) ?: return null
        val pickedTriangle = localTriangleIndex.coerceAtLeast(0)
        val normal = if (pickedTriangle < bodyMesh.triangleCount) {
            FacePicker.triangleNormal(bodyMesh, pickedTriangle)
        } else {
            matched.planeNormal ?: (documentPoint - matched.centroid).normalized()
        }
        val plateNormal = map.directionToPlate(normal.x, normal.y, normal.z)
            ?.let { FacePoint(it[0], it[1], it[2]) }
            ?: normal
        val selected = SelectedFace(
            body = body,
            index = matched.index,
            name = matched.name,
            surface = matched.surface,
            exact = matched.exact,
            areaMm2 = matched.areaMm2,
            centroid = matched.centroid,
            normal = normal,
            pickPoint = documentPoint,
            cylinderRadiusMm = matched.cylinder?.radiusMm,
            viewWords = FaceViewWords.describe(plateNormal, orientation),
        )
        return FacePick(selected, FacePicker.selectedTriangles(bodyMesh, faces, matched))
    }
}

/**
 * One resolved pick: the face the user picked, and the body-local triangles the plate tints.
 *
 * The triangles are the body's own indices - the caller maps them into the scene, where the
 * renderer's tint buffer lives, by adding the object's and the body's first triangle.
 */
class FacePick(val face: SelectedFace, val triangles: IntArray)

/**
 * The two engine calls a face pick makes, behind an interface so the mapping is testable
 * without a child process.
 */
interface FaceQuery {
    /** The body's faces, as `model.faces {body}` reports them. */
    fun faces(body: String): List<EngineFace>

    /** Sets the engine's selection to that face; true when the engine agreed. */
    fun select(body: String, index: Int, point: FacePoint): Boolean

    /** Clears the engine's selection, so the engine and the plate agree after a clear. */
    fun clear()
}

/** [FaceQuery] over the running engine session. */
class EngineFaceQuery(
    private val session: () -> SolidCraftSession?,
) : FaceQuery {
    override fun faces(body: String): List<EngineFace> {
        val live = session() ?: throw McpException("the CAD engine is not running")
        val result = live.execute("model.faces", JSONObject().put("body", body))
        val json = McpToolResults.json(result)
            ?: throw McpException("the engine's model.faces answer was not JSON")
        return EngineFaces.parse(json)
    }

    override fun select(body: String, index: Int, point: FacePoint): Boolean {
        val live = session() ?: throw McpException("the CAD engine is not running")
        // The engine's select.set takes a face as {body, index, point}: the point is where the
        // user picked it and the index is which face of that body. Verified against 0.24.0 on
        // the phone - a missing field is refused by name.
        val item = JSONObject()
            .put("type", "face")
            .put("body", body)
            .put("index", index)
            .put("point", arrayJson(point))
        val items = JSONArray().put(item)
        val result = live.execute("select.set", JSONObject().put("items", items))
        val json = McpToolResults.json(result) ?: return false
        return json.optInt("selected", 0) > 0
    }

    override fun clear() {
        val live = session() ?: return
        live.execute("select.clear")
    }

    private fun arrayJson(point: FacePoint): JSONArray =
        JSONArray().put(point.x).put(point.y).put(point.z)
}

/**
 * Which way a face is turned, relative to the camera the user is looking through.
 *
 * The plate's camera is a turntable: the scene is rotated by the orbit orientation and the eye
 * looks from (0, -d, 0.62 d) with up = +Z. The words are the ones a person would use standing in
 * front of the screen - "top", "facing you", "left side" - and they are computed in plate space,
 * so a part the user turned on the bed reads as they see it.
 */
object FaceViewWords {
    /** The model view's eye elevation, as [ViewerOrientationMath] defines it. */
    private const val ELEVATION = ViewerOrientationMath.MODEL_VIEW_ELEVATION

    /** "top", "facing you", "the face on your right" - a word or two, never a paragraph. */
    fun describe(normalPlate: FacePoint, orientation: ViewerOrientation?): String {
        val normal = normalPlate.normalized()
        val yaw = Math.toRadians((orientation?.yawDegrees ?: DEFAULT_ORBIT_YAW).toDouble())
        val pitch = Math.toRadians((orientation?.pitchDegrees ?: DEFAULT_ORBIT_PITCH).toDouble())
        val right = plateDirection(FacePoint(1.0, 0.0, 0.0), yaw, pitch)
        val up = plateDirection(
            FacePoint(0.0, ELEVATION / sqrt(1.0 + ELEVATION * ELEVATION), 1.0 / sqrt(1.0 + ELEVATION * ELEVATION)),
            yaw,
            pitch,
        )
        val forward = plateDirection(
            FacePoint(0.0, 1.0 / sqrt(1.0 + ELEVATION * ELEVATION), -ELEVATION / sqrt(1.0 + ELEVATION * ELEVATION)),
            yaw,
            pitch,
        )
        // The camera looks along +forward, so the eye is in the -forward direction.
        val towardEye = forward * -1.0
        val toward = normal.dot(towardEye)
        val away = -toward
        val upDot = normal.dot(up)
        val rightDot = normal.dot(right)
        return when {
            upDot >= STRONG -> "top"
            upDot <= -STRONG -> "bottom"
            toward >= STRONG -> "facing you"
            away >= STRONG -> "facing away"
            rightDot >= STRONG -> "the face on your right"
            rightDot <= -STRONG -> "the face on your left"
            else -> {
                val parts = ArrayList<String>(2)
                when {
                    toward >= WEAK -> parts += "facing you"
                    away >= WEAK -> parts += "facing away"
                }
                when {
                    upDot >= WEAK -> parts += "up"
                    upDot <= -WEAK -> parts += "down"
                }
                when {
                    rightDot >= WEAK -> parts += "to your right"
                    rightDot <= -WEAK -> parts += "to your left"
                }
                if (parts.isEmpty()) "a slanted face" else parts.take(2).joinToString(" and ")
            }
        }
    }

    /**
     * A scene-space direction turned into plate coordinates: the inverse of the renderer's
     * Rz(yaw) then Rx(pitch) turntable rotation.
     */
    fun plateDirection(scene: FacePoint, yaw: Double, pitch: Double): FacePoint {
        val cosPitch = cos(pitch)
        val sinPitch = sin(pitch)
        val cosYaw = cos(yaw)
        val sinYaw = sin(yaw)
        // Rx(-pitch), then Rz(-yaw): R^T = Rz(-yaw) * Rx(-pitch).
        val afterX = FacePoint(
            scene.x,
            scene.y * cosPitch + scene.z * sinPitch,
            -scene.y * sinPitch + scene.z * cosPitch,
        )
        return FacePoint(
            afterX.x * cosYaw + afterX.y * sinYaw,
            -afterX.x * sinYaw + afterX.y * cosYaw,
            afterX.z,
        )
    }

    /** The plate's default orbit, so a pick made before the viewer reports one still reads. */
    const val DEFAULT_ORBIT_YAW = -28f
    const val DEFAULT_ORBIT_PITCH = 58f

    private const val STRONG = 0.7
    private const val WEAK = 0.2
}
/**
 * What a document repaint does with the face the user has selected.
 *
 * Version 1.9.6 keeps the plate's tint across the agent's own edits by NAME: the engine's face
 * names survive a rebuild that moves or resizes a face (a box's `:top` is the top face after a
 * taller box), so the repaint re-resolves the name against the new body and re-tints whatever
 * triangles that face is now. A name the edit removed or replaced drops the selection with a
 * sentence that names it - never a tint left on triangles that now belong to something else, and
 * never a silent switch to a different face.
 */
sealed interface FaceReapply {
    /** The face is still there: its new report, and the triangles to tint now. */
    data class Retinted(val face: SelectedFace, val triangles: IntArray) : FaceReapply

    /** The face is gone: the sentence the status line carries, naming what was lost. */
    data class Dropped(val sentence: String) : FaceReapply
}

/** Re-resolves one selected face after a repaint. See [FaceReapply]. */
object FaceSelectionKeeper {

    fun reapply(
        previous: SelectedFace,
        body: String,
        bodyMesh: StlMesh,
        faces: List<EngineFace>,
        map: DocumentPlacementMap,
        orientation: ViewerOrientation?,
    ): FaceReapply {
        if (previous.body != body) return FaceReapply.Dropped(goneSentence(previous, body))
        val next = faces.firstOrNull { it.name == previous.name }
            ?: return FaceReapply.Dropped(goneSentence(previous, body))
        val triangles = FacePicker.selectedTriangles(bodyMesh, faces, next)
        if (triangles.isEmpty()) return FaceReapply.Dropped(goneSentence(previous, body))
        // The outward normal comes from the tinted triangle nearest the face's own picked point:
        // the direction the mesh itself carries is what survives an edit that moved the face, and
        // it is right for a hole (into the hole) and a boss (away from the axis) alike.
        val normal = normalNear(bodyMesh, triangles, next.pickPoint)
        val plateNormal = map.directionToPlate(normal.x, normal.y, normal.z)
            ?.let { FacePoint(it[0], it[1], it[2]) }
            ?: normal
        val face = previous.copy(
            index = next.index,
            name = next.name,
            surface = next.surface,
            exact = next.exact,
            areaMm2 = next.areaMm2,
            centroid = next.centroid,
            normal = normal,
            pickPoint = next.pickPoint,
            cylinderRadiusMm = next.cylinder?.radiusMm,
            viewWords = FaceViewWords.describe(plateNormal, orientation),
        )
        return FaceReapply.Retinted(face, triangles)
    }

    /** The sentence a dropped selection leaves in the status line; it names the face and body. */
    fun goneSentence(face: SelectedFace, body: String): String =
        "Face selection cleared: " + face.name + " on " + body + " is no longer in the document - " +
            "the edit removed or replaced that face."

    private fun normalNear(bodyMesh: StlMesh, triangles: IntArray, point: FacePoint): FacePoint {
        var best = triangles.first()
        var bestDistance = Double.MAX_VALUE
        for (triangle in triangles) {
            val centre = triangleCentre(bodyMesh, triangle)
            val distance = centre.distanceTo(point)
            if (distance < bestDistance) {
                bestDistance = distance
                best = triangle
            }
        }
        return FacePicker.triangleNormal(bodyMesh, best)
    }

    private fun triangleCentre(mesh: StlMesh, triangle: Int): FacePoint {
        val vertices = mesh.interleavedVertices
        val base = triangle * 18
        return FacePoint(
            (vertices[base].toDouble() + vertices[base + 6] + vertices[base + 12]) / 3.0,
            (vertices[base + 1].toDouble() + vertices[base + 7] + vertices[base + 13]) / 3.0,
            (vertices[base + 2].toDouble() + vertices[base + 8] + vertices[base + 14]) / 3.0,
        )
    }
}

/** The face's document-space normal drawn in plate coordinates, for the camera words. */
fun SelectedFace.plateNormal(map: DocumentPlacementMap): FacePoint {
    val plate = map.directionToPlate(normal.x, normal.y, normal.z)
        ?: return normal
    return FacePoint(plate[0], plate[1], plate[2])
}

/**
 * The same face re-worded for the camera the user is looking through now.
 *
 * The tint follows a face, not a screen position, so an orbit never clears the selection; what
 * does move with the orbit is the plain-words description, which the HUD and the next prompt
 * block read from here.
 */
fun SelectedFace.rewordedFor(
    map: DocumentPlacementMap,
    orientation: ViewerOrientation?,
): SelectedFace = copy(viewWords = FaceViewWords.describe(plateNormal(map), orientation))

/**
 * One tap resolved end to end: the face under it, its triangles, and the engine's own selection
 * set with `select.set` so the engine and the plate agree.
 *
 * A separate object from [FacePickMapper] because this is the part that talks to the engine -
 * and the part a JVM test can hand a fake [FaceQuery] and see exactly what was selected.
 */
object FaceSelectionFlow {
    /** The resolved pick and whether the engine accepted its own selection. */
    class Resolved(val face: SelectedFace, val triangles: IntArray, val engineAgreed: Boolean)

    fun select(
        query: FaceQuery,
        body: String,
        bodyMesh: StlMesh,
        displayMesh: StlMesh,
        placement: ModelPlacement,
        hit: MeshPicker.Hit,
        localTriangleIndex: Int,
        orientation: ViewerOrientation?,
    ): Resolved? {
        val faces = query.faces(body)
        val pick = FacePickMapper.resolve(
            hit = hit,
            localTriangleIndex = localTriangleIndex,
            body = body,
            bodyMesh = bodyMesh,
            displayMesh = displayMesh,
            placement = placement,
            faces = faces,
            orientation = orientation,
        ) ?: return null
        val agreed = runCatching {
            query.select(body, pick.face.index, pick.face.pickPoint)
        }.getOrDefault(false)
        return Resolved(pick.face, pick.triangles, agreed)
    }
}
