package com.tomppi.enderslicer.viewer

import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * The wall lip as a ring: one tooth round the whole seam's edge, one ledge to
 * match it, and the step the two halves give it sized from the ring.
 *
 * Every claim is read off the meshes the builder produced, and where the hold
 * itself is the question, off the pair after the joint has been applied to it -
 * which is what the app does and what the user sees.
 *
 * The halves are cut with the host Manifold build (scripts/build-manifold-host.sh),
 * which is only packaged for arm64-v8a. Where that build is absent - CI - the
 * class skips rather than measuring meshes it could not cut.
 */
class SnapLipTest {
    private val seam = 30f

    @Before
    fun theHostBooleanEngine() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
    }

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
        cuts: List<Vec3> = emptyList(),
    ): SnapLip.Outcome = SnapLip.buildRing(
        axis = Vec3(0f, 0f, 1f),
        anchorMm = Vec3(20f, 20f, seam),
        scale = 1f,
        lipHalf = SnapFitHalf.inPlace(lip, seam),
        mateHalf = SnapFitHalf.inPlace(mate, seam),
        parameters = parameters,
        cuts = cuts,
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
        // The part gave 40 mm of solid, and the ring's own recess leaves the asked
        // 2.00 mm of it: nothing to clamp, and the strain that wall carries is
        // the warning the user reads.
        check(abs(joint.dimensions.wallThicknessMm - 2f) < 0.01f) {
            "the ring left " + joint.dimensions.wallThicknessMm + " mm of wall in a solid block"
        }
        check(joint.clamps.none { it.label == "wall thickness" }) {
            "a solid part clamped a wall it was asked to make: " + joint.clamps
        }
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

    /** A solid block: the case the Wall control is for - there is no wall until the ring makes one. */
    private fun block(size: Float = 40f, depth: Float = seam): StlMesh =
        MeshFixtures.box(0f, 0f, seam - depth, size, size, seam, name = "block")

    @Test
    fun theWallControlReallyChangesTheWallInTheMeshes() {
        // The recess ring's outboard surface IS the wall's inner face, so the
        // wall it leaves is measurable off the mesh: the part's edge to the
        // recess, less the relief the recess keeps on that face.
        fun measuredWall(wall: Float): Float {
            val outcome = build(
                block(),
                solidHalf(),
                SnapFitParameters(lipWallOverrideMm = wall, lipRecessExtraOverrideMm = 0f),
            )
            check(outcome is SnapLip.Outcome.Built) { "refused: " + (outcome as? SnapLip.Outcome.Refused)?.reason }
            val recess = (outcome as SnapLip.Outcome.Built).joint.subtractSolid
            // The block's own edge is x = 0 and the recess's outer face sits the
            // wall less its relief in from it.
            return recess.bounds.minX + 0.2f
        }
        val thin = measuredWall(1.2f)
        val thick = measuredWall(3.0f)
        check(abs(thin - 1.2f) < 0.06f) { "a 1.20 mm wall measured " + thin + " mm in the mesh" }
        check(abs(thick - 3.0f) < 0.06f) { "a 3.00 mm wall measured " + thick + " mm in the mesh" }
    }

    @Test
    fun theDepthControlReallyMovesTheRecessFloor() {
        fun floorAndJoint(extra: Float): Triple<Float, Float, Float> {
            val outcome = build(
                block(),
                solidHalf(),
                SnapFitParameters(lipWallOverrideMm = 2f, lipRecessExtraOverrideMm = extra),
            )
            check(outcome is SnapLip.Outcome.Built) { "refused: " + (outcome as? SnapLip.Outcome.Refused)?.reason }
            val joint = (outcome as SnapLip.Outcome.Built).joint
            val floor = joint.subtractSolid.bounds.minZ - seam
            return Triple(floor, joint.dimensions.wallFreeLengthMm, joint.dimensions.lipStrainPercent)
        }
        val (flat, flatFree, flatStrain) = floorAndJoint(0f)
        val (deep, deepFree, deepStrain) = floorAndJoint(2.5f)
        // Both floors are behind the seam, so the deeper one is the smaller number.
        check(abs((flat - deep) - 2.5f) < 0.02f) {
            "the floor moved " + (flat - deep) + " mm for a 2.50 mm depth"
        }
        check(abs(flatFree - (flat * -1f)) < 0.6f) {
            "on a solid block the free length is the band's own depth: " + flatFree + " against " + flat
        }
        check(deepFree > flatFree + 1.5f) { "the free length did not follow the floor: " + deepFree }
        check(deepStrain < flatStrain) { "the strain did not fall with the depth" }
    }

    @Test
    fun aPartWhoseOwnWallIsThinnerKeepsItsWallAndSaysSo() {
        val outcome = build(
            hollowHalf(wall = 2f),
            solidHalf(),
            SnapFitParameters(lipWallOverrideMm = 3f),
        )
        check(outcome is SnapLip.Outcome.Built) { "refused: " + (outcome as? SnapLip.Outcome.Refused)?.reason }
        val joint = (outcome as SnapLip.Outcome.Built).joint
        check(abs(joint.dimensions.wallThicknessMm - 2f) < 0.1f) {
            "the part's own 2 mm wall was not kept: " + joint.dimensions.wallThicknessMm
        }
        val clamp = joint.clamps.firstOrNull { it.label == "wall thickness" }
        checkNotNull(clamp) { "the clamp went unsaid: " + joint.clamps }
        check(clamp.reason.contains("3.0 mm asked for")) { clamp.reason }
        check(clamp.reason.contains("2.0 mm")) { clamp.reason }
    }

    @Test
    fun aWallUnderWhatALipNeedsIsRefusedWithItsNumber() {
        val outcome = build(
            block(),
            solidHalf(),
            SnapFitParameters(lipWallOverrideMm = 0.3f),
        )
        check(outcome is SnapLip.Outcome.Refused) { "a 0.30 mm wall carried a ring" }
        val reason = (outcome as SnapLip.Outcome.Refused).reason
        check(reason.contains("0.3 mm")) { reason }
        check(reason.contains("0.4 mm")) { reason }
    }

    @Test
    fun aDepthPastThePartsOwnMaterialIsHeldOffWithItsNumber() {
        // A half only 5 mm deep: the step and its clearance take 3.0 mm of it and
        // the band keeps 0.4 mm of wall under itself, so 1.6 mm of extra depth is
        // all there is.
        val outcome = build(
            block(depth = 5f),
            solidHalf(),
            SnapFitParameters(lipWallOverrideMm = 2f, lipRecessExtraOverrideMm = 5f),
        )
        check(outcome is SnapLip.Outcome.Built) { "refused: " + (outcome as? SnapLip.Outcome.Refused)?.reason }
        val joint = (outcome as SnapLip.Outcome.Built).joint
        check(abs(joint.dimensions.lipDepthExtraMm - 1.6f) < 0.05f) {
            "the extra came out at " + joint.dimensions.lipDepthExtraMm + " mm"
        }
        val clamp = joint.clamps.firstOrNull { it.label == "recess depth" }
        checkNotNull(clamp) { "the clamp went unsaid: " + joint.clamps }
        check(clamp.reason.contains("5.0 mm asked for")) { clamp.reason }
        check(clamp.reason.contains("0.4 mm of wall under it")) { clamp.reason }
    }

    @Test
    fun aHalfTooShallowForTheStepIsRefusedWithItsNumber() {
        val outcome = build(
            block(depth = 3.2f),
            solidHalf(),
            SnapFitParameters(lipWallOverrideMm = 2f),
        )
        check(outcome is SnapLip.Outcome.Refused) { "a 3.2 mm deep half carried the step" }
        val reason = (outcome as SnapLip.Outcome.Refused).reason
        check(reason.contains("3.2")) { reason }
        check(reason.contains("before a wall is left under the band")) { reason }
    }

    /** The connected pieces of a mesh: how many arcs a cut left the ring in. */
    private fun pieces(mesh: StlMesh): Int {
        val parent = HashMap<Int, Int>()
        fun find(a: Int): Int {
            var at = a
            while (parent[at] != at) {
                parent[at] = parent[parent[at]]!!
                at = parent[at]!!
            }
            return at
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        val stride = MeshSolidBuilder.FLOATS_PER_VERTEX
        val welded = HashMap<String, Int>()
        val vertices = mesh.interleavedVertices
        for (triangle in 0 until mesh.triangleCount) {
            val ids = (0 until 3).map { corner ->
                val at = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE + corner * stride
                val key = "%.3f,%.3f,%.3f".format(vertices[at], vertices[at + 1], vertices[at + 2])
                welded.getOrPut(key) { welded.size }
            }
            ids.forEach { if (!parent.containsKey(it)) parent[it] = it }
            union(ids[0], ids[1])
            union(ids[1], ids[2])
        }
        return parent.keys.map { find(it) }.toSet().size
    }

    @Test
    fun aCutReallyPartsTheRingAndTheGapIsTheCutWidth() {
        assumeTrue(MeshBoolean.ensureLoaded())
        // The ring's flat holding face runs the whole way round, so a cut takes
        // exactly one slit's worth of it out, and the ring is in two pieces after.
        val whole = built()
        check(whole.cutSolid == null) { "an uncut ring carries a cut" }
        check(pieces(whole.unionSolid) == 1) { "an uncut ring came out in pieces" }
        val width = 0.8f
        val outcome = build(
            block(),
            solidHalf(),
            SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = width),
            cuts = listOf(Vec3(20f, 0f, seam)),
        )
        check(outcome is SnapLip.Outcome.Built) { "refused: " + (outcome as? SnapLip.Outcome.Refused)?.reason }
        val joint = (outcome as SnapLip.Outcome.Built).joint
        check(joint.dimensions.lipCuts == 1) { "the cut was not counted" }
        // One cut opens a closed ring into one arc: the ring is no longer closed,
        // which is the whole point - the wall has an end to bend from.
        check(joint.dimensions.lipArcs == 1) { "one cut left " + joint.dimensions.lipArcs + " arcs" }
        val cut = requireNotNull(joint.cutSolid) { "the cut solid went missing" }
        // One slit is one box: twelve triangles, as wide as the cut, and through
        // the wall and past the recess floor.
        check(cut.triangleCount == 12) { "one cut came out as " + cut.triangleCount + " triangles" }
        val across = cut.bounds.maxX - cut.bounds.minX
        check(abs(across - width) < 0.02f) { "a " + width + " mm cut measured " + across + " mm wide" }
        check(cut.bounds.maxZ - cut.bounds.minZ > joint.dimensions.lipRecessMm) {
            "the slit does not reach past the recess floor"
        }
        check(cut.bounds.maxY - cut.bounds.minY > joint.dimensions.wallThicknessMm) {
            "the slit does not reach through the wall"
        }
    }

    @Test
    fun cuttingEveryBendPutsOneCutOnEachCorner() {
        val bends = SnapLip.bendPoints(
            axis = Vec3(0f, 0f, 1f),
            anchorMm = Vec3(15f, 15f, seam),
            scale = 1f,
            lipHalf = SnapFitHalf.inPlace(block(size = 30f), seam),
            mateHalf = SnapFitHalf.inPlace(solidHalf(size = 30f), seam),
        )
        check(bends.size == 4) { "a square contour has " + bends.size + " bends" }
        val outcome = build(
            block(size = 30f),
            solidHalf(size = 30f),
            SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = 0.6f),
            cuts = bends,
        )
        check(outcome is SnapLip.Outcome.Built) { "refused: " + (outcome as? SnapLip.Outcome.Refused)?.reason }
        val joint = (outcome as SnapLip.Outcome.Built).joint
        check(joint.dimensions.lipCuts == 4) { joint.dimensions.lipCuts.toString() + " cuts for four bends" }
        check(joint.dimensions.lipArcs == 4) { "four cuts left " + joint.dimensions.lipArcs + " arcs" }
        val cut = requireNotNull(joint.cutSolid) { "the cuts went missing" }
        check(cut.triangleCount == 48) { "four cuts came out as " + cut.triangleCount + " triangles" }
        // One slit per side of the square, each a cut width long, and the wall
        // that is left runs the side less the slit.
        // The hooks run on the wall's face, so a side is the block's own side less
        // the wall at each end, and the wall left on it is that less one slit.
        val side = joint.dimensions.lipRingMm / 4f
        check(abs(joint.dimensions.lipShortestRunMm - (side - 0.6f)) < 1f) {
            "the shortest run is " + joint.dimensions.lipShortestRunMm + " mm on a " + side + " mm side"
        }
        check(abs(joint.dimensions.lipShortestRunMm - joint.dimensions.lipLongestRunMm) < 0.5f) {
            "the runs are " + joint.dimensions.lipShortestRunMm + " to " + joint.dimensions.lipLongestRunMm
        }
    }

    private fun extentAlong(mesh: StlMesh, direction: Vec3): Float {
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        val vertices = mesh.interleavedVertices
        val stride = MeshSolidBuilder.FLOATS_PER_VERTEX
        for (vertex in 0 until mesh.triangleCount * 3) {
            val at = vertex * stride
            val along = Vec3(vertices[at], vertices[at + 1], vertices[at + 2]).dot(direction)
            if (along < low) low = along
            if (along > high) high = along
        }
        return high - low
    }

    @Test
    fun everyCutAtABendSeversTheRingAcrossTheWall() {
        assumeTrue(MeshBoolean.ensureLoaded())
        val size = 30f
        val bends = SnapLip.bendPoints(
            axis = Vec3(0f, 0f, 1f),
            anchorMm = Vec3(15f, 15f, seam),
            scale = 1f,
            lipHalf = SnapFitHalf.inPlace(block(size = size), seam),
            mateHalf = SnapFitHalf.inPlace(solidHalf(size = size), seam),
        )
        check(bends.size == 4) { "a square contour has " + bends.size + " bends" }
        val width = 0.6f
        val all = build(
            block(size = size),
            solidHalf(size = size),
            SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = width),
            cuts = bends,
        ) as SnapLip.Outcome.Built
        // Four cuts on a closed ring leave four arcs, each with both ends free:
        // take the cuts out of the ring itself and count what is left.
        val severed = MeshBoolean.subtract(all.joint.unionSolid, requireNotNull(all.joint.cutSolid))
        check(severed is MeshBoolean.Result.Success) { "the cuts would not come out of the ring: " + severed }
        val arcs = pieces((severed as MeshBoolean.Result.Success).mesh)
        check(arcs == 4) { "four cuts left the ring in " + arcs + " arcs" }
        check(all.joint.dimensions.lipArcs == arcs) {
            "the readout says " + all.joint.dimensions.lipArcs + " arcs and the mesh has " + arcs
        }
        // Every bend on its own: the slit meets the tooth as a slice whose length
        // ALONG the bend's bisector is the cut's width, and whose depth ACROSS the
        // wall spans the tooth. A slit taken along the contour instead - which is
        // what taking the direction from a sliver's tangent does - comes out the
        // other way round and fails both.
        for (index in bends.indices) {
            val one = build(
                block(size = size),
                solidHalf(size = size),
                SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = width),
                cuts = listOf(bends[index]),
            ) as SnapLip.Outcome.Built
            val box = requireNotNull(one.joint.cutSolid)
            val hit = MeshBoolean.intersect(one.joint.unionSolid, box)
            check(hit is MeshBoolean.Result.Success) { "bend " + (index + 1) + " misses the ring" }
            val slice = (hit as MeshBoolean.Result.Success).mesh
            check(volumeOf(slice) > 0.02f) { "bend " + (index + 1) + " takes nothing out of the ring" }
            val here = bends[index]
            val previous = bends[(index + bends.size - 1) % bends.size]
            val next = bends[(index + 1) % bends.size]
            val inDir = (here - previous).normalized()!!
            val outDir = (next - here).normalized()!!
            val bisector = (inDir + outDir).normalized()!!
            val across = Vec3(-bisector.y, bisector.x, bisector.z)
            val along = extentAlong(slice, bisector)
            val through = extentAlong(slice, across)
            check(abs(along - width) < 0.2f) {
                "bend " + (index + 1) + " cuts " + along + " mm along the bisector, and the slit is " + width + " mm"
            }
            check(through > 1f) {
                "bend " + (index + 1) + " reaches only " + through + " mm across the wall"
            }
        }
    }

    @Test
    fun aCutOnAnEdgeSeversTheWallAcrossIt() {
        assumeTrue(MeshBoolean.ensureLoaded())
        // A tap-placed cut on a straight edge: its slit lies across the wall, one
        // cut width along the edge and the whole tooth deep.
        val width = 0.8f
        val one = build(
            block(),
            solidHalf(),
            SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = width),
            cuts = listOf(Vec3(20f, 0f, seam)),
        ) as SnapLip.Outcome.Built
        val box = requireNotNull(one.joint.cutSolid)
        val hit = MeshBoolean.intersect(one.joint.unionSolid, box)
        check(hit is MeshBoolean.Result.Success) { "the cut misses the ring" }
        val slice = (hit as MeshBoolean.Result.Success).mesh
        check(abs(extentAlong(slice, Vec3(1f, 0f, 0f)) - width) < 0.1f) {
            "the slit is " + extentAlong(slice, Vec3(1f, 0f, 0f)) + " mm along the edge"
        }
        check(extentAlong(slice, Vec3(0f, 1f, 0f)) > width + 0.5f) {
            "the slit does not reach across the wall"
        }
    }

    @Test
    fun aCutThatWouldDoNothingIsRefusedWithItsNumbers() {
        val outcome = build(
            block(),
            solidHalf(),
            SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = 0.6f),
            cuts = listOf(Vec3(20f, 0f, seam), Vec3(20.2f, 0f, seam)),
        )
        check(outcome is SnapLip.Outcome.Refused) { "two cuts on top of each other were built" }
        val reason = (outcome as SnapLip.Outcome.Refused).reason
        check(reason.contains("0.2 mm apart")) { reason }
        check(reason.contains("0.6 mm slit")) { reason }
    }

    @Test
    fun aCutThatWouldLeaveNoWallIsRefusedWithItsNumbers() {
        // A 2 mm slit every 3 mm round a 120 mm contour leaves a millimetre of
        // wall between them, and two ends need a millimetre between them.
        // Fifty slits 2 mm wide: 2.4 mm apart on the ring, so each leaves 0.4 mm
        // of wall where two ends need a millimetre between them.
        val side = 40f - 2f * 2f
        val cuts = (0 until 50).map { step ->
            val along = 4f * side * step / 50f
            when {
                along < side -> Vec3(2f + along, 2f, seam)
                along < 2f * side -> Vec3(2f + side, 2f + (along - side), seam)
                along < 3f * side -> Vec3(2f + side - (along - 2f * side), 2f + side, seam)
                else -> Vec3(2f, 2f + side - (along - 3f * side), seam)
            }
        }
        val outcome = build(
            block(),
            solidHalf(),
            SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = 2f),
            cuts = cuts,
        )
        check(outcome is SnapLip.Outcome.Refused) { "cuts that leave no wall were built" }
        val reason = (outcome as SnapLip.Outcome.Refused).reason
        check(reason.contains("stretch of wall between two cuts")) { reason }
        check(reason.contains(" mm")) { reason }
    }

    @Test
    fun theDefaultsAreTheJointItHasAlwaysBeen() {
        // Wall 2.00 and Depth 0.00 are the joint as it was before either control
        // existed: the same solids, to the triangle and the cubic millimetre.
        val joint = built()
        println(
            "RINGGOLD tooth=" + joint.unionSolid.triangleCount + "/" + "%.3f".format(volumeOf(joint.unionSolid)) +
                " recess=" + joint.subtractSolid.triangleCount + "/" + "%.3f".format(volumeOf(joint.subtractSolid)) +
                " ledge=" + joint.registrationSolid.triangleCount + "/" + "%.3f".format(volumeOf(joint.registrationSolid)) +
                " recessDepth=" + joint.dimensions.lipRecessMm + " wall=" + joint.dimensions.wallThicknessMm,
        )
        check(abs(joint.dimensions.lipDepthExtraMm) < 0.001f) { "the default depth is not zero" }
        check(joint.dimensions.lipCuts == 0 && joint.dimensions.lipArcs == 1) { "an uncut ring is not one ring" }
        check(joint.cutSolid == null) { "an uncut joint carries a cut solid" }
        check(abs(joint.dimensions.lipWallAskedMm - 2f) < 0.001f) { "the default wall is not 2.00 mm" }
        // The solids themselves, to the triangle and the cubic millimetre: the
        // numbers the build produced before either control existed.
        check(joint.unionSolid.triangleCount == 32) { "the tooth ring is " + joint.unionSolid.triangleCount + " triangles" }
        check(abs(volumeOf(joint.unionSolid) - 206.796f) < 0.01f) { "the tooth ring is " + volumeOf(joint.unionSolid) + " mm3" }
        check(joint.subtractSolid.triangleCount == 32) { "the recess ring is " + joint.subtractSolid.triangleCount + " triangles" }
        check(abs(volumeOf(joint.subtractSolid) - 1222.498f) < 0.01f) { "the recess ring is " + volumeOf(joint.subtractSolid) + " mm3" }
        check(joint.registrationSolid.triangleCount == 64) { "the ledge ring is " + joint.registrationSolid.triangleCount + " triangles" }
        check(abs(volumeOf(joint.registrationSolid) - 611.615f) < 0.01f) { "the ledge ring is " + volumeOf(joint.registrationSolid) + " mm3" }
        val step = joint.dimensions.lipToothHeightMm + joint.dimensions.lipRunMm +
            2f * joint.dimensions.lipClearanceMm
        check(abs(joint.dimensions.lipRecessMm - (step + joint.dimensions.lipClearanceMm)) < 0.01f) {
            "the recess is " + joint.dimensions.lipRecessMm + " and the step and its clearance are " + (step + 0.2f)
        }
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
