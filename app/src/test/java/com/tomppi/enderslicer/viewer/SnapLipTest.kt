package com.tomppi.enderslicer.viewer

import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The wall lip as a ring: one tooth round the whole seam's edge, one ledge to
 * match it, and the step the two halves give it sized from the ring.
 *
 * Every claim is read off the meshes the builder produced, and where the hold
 * itself is the question, off the pair after the joint has been applied to it -
 * which is what the app does and what the user sees.
 */
class SnapLipTest {
    private val seam = 30f

    /** A hollow half: the outer block up to [seam], with a cavity cut clean through the top. */
    private fun hollowHalf(
        outer: Float = 40f,
        wall: Float = 2f,
        floor: Float = 2f,
        top: Float = 60f,
    ): StlMesh {
        val solid = MeshFixtures.box(0f, 0f, 0f, outer, outer, seam, name = "half-outer")
        val cavity = MeshFixtures.box(wall, wall, floor, outer - wall, outer - wall, top, name = "half-cavity")
        val cut = MeshBoolean.subtract(solid, cavity)
        check(cut is MeshBoolean.Result.Success) { "the cavity was not cut: " + cut }
        return (cut as MeshBoolean.Result.Success).mesh
    }

    /** The mate: solid from the seam up, so the ledge has something to stand on. */
    private fun solidHalf(size: Float = 40f, top: Float = 60f): StlMesh =
        MeshFixtures.box(0f, 0f, seam, size, size, top, name = "mate")

    /** The mate as a hollow box leaves it: its material is a ring at the seam. */
    private fun hollowMate(): StlMesh {
        val solid = MeshFixtures.box(0f, 0f, seam, 40f, 40f, 60f, name = "mate-outer")
        val cavity = MeshFixtures.box(2f, 2f, 0f, 38f, 38f, 59f, name = "mate-cavity")
        val cut = MeshBoolean.subtract(solid, cavity)
        check(cut is MeshBoolean.Result.Success) { "the mate's cavity was not cut: " + cut }
        return (cut as MeshBoolean.Result.Success).mesh
    }

    private fun build(
        lip: StlMesh,
        mate: StlMesh,
        parameters: SnapFitParameters = SnapFitParameters(),
    ): SnapLip.Outcome = SnapLip.buildRing(
        axis = Vec3(0f, 0f, 1f),
        anchorMm = Vec3(20f, 20f, seam),
        scale = 1f,
        lipHalf = SnapFitHalf.inPlace(lip, seam),
        mateHalf = SnapFitHalf.inPlace(mate, seam),
        parameters = parameters,
    )

    private fun built(
        lip: StlMesh = hollowHalf(),
        mate: StlMesh = solidHalf(),
        parameters: SnapFitParameters = SnapFitParameters(),
    ): SnapFitJoint {
        val outcome = build(lip, mate, parameters)
        check(outcome is SnapLip.Outcome.Built) { "refused: " + (outcome as? SnapLip.Outcome.Refused)?.reason }
        return (outcome as SnapLip.Outcome.Built).joint
    }

    /** One flat face of a mesh that stands perpendicular to the axis, grouped by its plane. */
    private class Face(
        val z: Float,
        val up: Boolean,
        val area: Float,
    )

    private fun flatFaces(mesh: StlMesh, axis: Vec3 = Vec3(0f, 0f, 1f)): List<Face> {
        val groups = LinkedHashMap<String, Face>()
        val vertices = mesh.interleavedVertices
        val stride = MeshSolidBuilder.FLOATS_PER_VERTEX
        for (triangle in 0 until mesh.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            val points = (0 until 3).map { corner ->
                val at = base + corner * stride
                Vec3(vertices[at], vertices[at + 1], vertices[at + 2])
            }
            val normal = (points[1] - points[0]).cross(points[2] - points[0])
            val length = kotlin.math.sqrt(normal.dot(normal))
            if (length < 1e-9f) continue
            val unit = normal * (1f / length)
            if (abs(unit.dot(axis)) < 0.999f) continue
            val z = points[0].dot(axis)
            val key = (if (unit.dot(axis) > 0f) "+" else "-") + "%.2f".format(z)
            val area = length * 0.5f
            val existing = groups[key]
            groups[key] = Face(z, unit.dot(axis) > 0f, (existing?.area ?: 0f) + area)
        }
        return groups.values.toList()
    }

    private fun volumeOf(mesh: StlMesh): Float {
        var six = 0f
        val vertices = mesh.interleavedVertices
        val stride = MeshSolidBuilder.FLOATS_PER_VERTEX
        for (triangle in 0 until mesh.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            fun point(corner: Int): Vec3 {
                val at = base + corner * stride
                return Vec3(vertices[at], vertices[at + 1], vertices[at + 2])
            }
            val a = point(0)
            six += a.dot(point(1).cross(point(2)))
        }
        return six / 6f
    }

    @Test
    fun theRingRunsTheWholeEdgeWithOneClosedHook() {
        val joint = built()
        val tooth = joint.unionSolid
        // One solid, wound outward: a ring that closed on itself, not a row of pieces.
        check(volumeOf(tooth) > 0f) { "the tooth ring came out inside-out" }
        val ring = flatFaces(tooth).firstOrNull { !it.up && it.z < seam - 0.5f }
        checkNotNull(ring) { "the tooth's flat holding face is not on the ring: " + flatFaces(tooth) }
        val perimeter = joint.dimensions.lipRingMm
        val flatWidth = joint.dimensions.lipToothHeightMm + joint.dimensions.wallThicknessMm / 3f
        val straight = perimeter * flatWidth
        // The flat face runs the whole way round: at least the straight runs'
        // worth of it, and not much more when the mitred corners are counted. A
        // gap, a corner notch or a doubled segment lands outside that band.
        check(ring.area > straight * 0.85f && ring.area < straight * 1.15f) {
            "the flat face covers %.2f mm2, and %.2f mm of ring at %.2f mm wide is %.2f mm2".format(
                ring.area, perimeter, flatWidth, straight,
            )
        }
    }

    @Test
    fun theEngagementIsTheSameAllTheWayRound() {
        val joint = built()
        val ledge = joint.registrationSolid
        val bearing = flatFaces(ledge).firstOrNull { it.up && it.z < seam - 0.5f }
        checkNotNull(bearing) { "the ledge's bearing face is missing: " + flatFaces(ledge) }
        val engagement = joint.dimensions.lipEngagementMm
        val perimeter = joint.dimensions.lipRingMm
        // The bearing face is the engagement wide, all the way round the ring: the
        // one number the panel reports is the number the whole edge carries.
        val width = bearing.area / perimeter
        check(abs(width - engagement) < 0.04f) {
            "the bearing face measures %.3f mm wide over %.1f mm of ring, and the engagement is %.2f mm".format(
                width, perimeter, engagement,
            )
        }
        check(joint.dimensions.lipStrainPercent < SnapLip.MATERIAL_STRAIN_PERCENT) {
            "the tray's own wall strains %.2f%%".format(joint.dimensions.lipStrainPercent)
        }
    }

    @Test
    fun theStepIsSizedFromTheRingNotFromTheCantileverPad() {
        val joint = built()
        val dimensions = joint.dimensions
        val expected = dimensions.lipToothHeightMm + dimensions.lipRunMm + 2f * dimensions.lipClearanceMm
        check(abs(dimensions.lipStepMm - expected) < 0.01f) {
            "the step is %.2f mm, and the ring needs %.2f mm".format(dimensions.lipStepMm, expected)
        }
        check(dimensions.lipRecessMm > dimensions.lipStepMm) { "the recess has no clearance behind the step" }
        // The line the panel shows, read as the user reads it: the numbers, not
        // the format string. It shipped once as "pad %.2f mm: a %.2f mm tooth".
        val sentence = joint.lipStepSummary
        check(!sentence.contains("%")) { "the step line is unformatted: " + sentence }
        check(sentence.startsWith("pad " + millimetresOf(dimensions.lipStepMm))) { sentence }
        check(sentence.contains(millimetresOf(dimensions.lipToothHeightMm) + " mm tooth")) { sentence }
        check(sentence.contains("the recess " + millimetresOf(dimensions.lipRecessMm) + " mm")) { sentence }
    }

    /** Two decimals, the way the line's own format does it. */
    private fun millimetresOf(value: Float): String = "%.2f".format(value)

    @Test
    fun aSolidHalfIsGivenAWallAndWarnedAbout() {
        // No wall at all: the ring's own recess has to leave one, and the strain
        // that puts on it is a warning the user reads rather than a refusal.
        val outcome = build(MeshFixtures.box(0f, 0f, 0f, 40f, 40f, seam, name = "block"), solidHalf())
        check(outcome is SnapLip.Outcome.Built) { "refused: " + (outcome as? SnapLip.Outcome.Refused)?.reason }
        val joint = (outcome as SnapLip.Outcome.Built).joint
        check(joint.dimensions.wallThicknessMm <= 2.01f) { "the ring's own wall is the whole block" }
        check(joint.clamps.any { it.label == "wall thickness" }) { "the clamp went unsaid: " + joint.clamps }
        check(joint.warnings.any { it.contains("2.0%") }) { "no strain warning on a solid block: " + joint.warnings }
    }

    @Test
    fun aHollowMateIsRefusedWithItsNumber() {
        val outcome = build(hollowHalf(), hollowMate())
        check(outcome is SnapLip.Outcome.Refused) { "a hollow mate built a ledge in mid-air" }
        val reason = (outcome as SnapLip.Outcome.Refused).reason
        check(reason.contains("hollow just inboard of the wall")) { reason }
        check(reason.contains(" mm")) { "the refusal has no number in it: " + reason }
    }

    @Test
    fun aWallThinnerThanTheRingNeedsIsRefusedWithItsNumber() {
        val outcome = build(hollowHalf(wall = 0.3f), solidHalf())
        check(outcome is SnapLip.Outcome.Refused) { "a 0.3 mm wall carried a ring" }
        val reason = (outcome as SnapLip.Outcome.Refused).reason
        check(reason.contains("0.3")) { reason }
    }

    @Test
    fun theRingIsReadAtManyPlacesAroundTheSeam() {
        val joint = built()
        check(joint.dimensions.lipSamples >= 8) { "the ring was read at " + joint.dimensions.lipSamples + " places" }
        check(joint.dimensions.lipWorstAtMm > 0f) { "the worst reading has no place on the ring" }
        // The tray's wall is 2 mm thick and runs 28 mm down to its floor.
        check(abs(joint.dimensions.wallFreeLengthMm - 28f) < 1.5f) {
            "the ring's shortest free length is " + joint.dimensions.wallFreeLengthMm
        }
    }

    @Test
    fun aSquareRampIsBuiltSquareAndTakesTheSameRoom() {
        val joint = built(parameters = SnapFitParameters(rampAngleDeg = 90f))
        check(joint.dimensions.rampAngleDeg == 90f)
        check(abs(joint.dimensions.lipRunMm - joint.dimensions.lipToothHeightMm) < 0.01f) {
            "a square tooth runs " + joint.dimensions.lipRunMm + " mm"
        }
    }
}
