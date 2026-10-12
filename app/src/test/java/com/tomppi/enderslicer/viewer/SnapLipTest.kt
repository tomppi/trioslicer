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

    /**
     * A solid prism from a CONVEX polygon's corners wound counter-clockwise seen
     * from +Z: the cap at [top] is the seam and the polygon is the wall's own
     * outline. It is what lets a fixture be a circle's stand-in, a rhombus, or
     * whatever shape a claim needs.
     */
    private fun prism(corners: List<Pair<Float, Float>>, top: Float = seam): StlMesh {
        require(corners.size >= 3) { "a prism needs a polygon" }
        val soup = ArrayList<Float>()
        fun add(a: FloatArray, b: FloatArray, c: FloatArray) {
            soup.addAll(a.toList())
            soup.addAll(b.toList())
            soup.addAll(c.toList())
        }
        fun point(at: Int, z: Float) = floatArrayOf(corners[at].first, corners[at].second, z)
        // The top cap is the seam, and the bottom cap faces the other way.
        for (at in 1 until corners.size - 1) add(point(0, top), point(at, top), point(at + 1, top))
        for (at in 1 until corners.size - 1) add(point(0, 0f), point(at + 1, 0f), point(at, 0f))
        for (at in corners.indices) {
            val next = (at + 1) % corners.size
            add(point(at, 0f), point(next, 0f), point(next, top))
            add(point(at, 0f), point(next, top), point(at, top))
        }
        return MeshFixtures.fromSoup("prism", soup.toFloatArray())
    }

    /** A round half: a many-sided prism whose polyline stands in for a circle. */
    private fun roundHalf(radius: Float = 15f, sides: Int = 36): StlMesh {
        val corners = (0 until sides).map { at ->
            val angle = 2.0 * Math.PI * at / sides
            (radius + radius * Math.cos(angle)).toFloat() to
                (radius + radius * Math.sin(angle)).toFloat()
        }
        return prism(corners)
    }

    private fun build(
        lip: StlMesh,
        mate: StlMesh,
        parameters: SnapFitParameters = SnapFitParameters(),
        cuts: List<Vec3> = emptyList(),
        cutDirections: List<Vec3> = emptyList(),
    ): SnapLip.Outcome = SnapLip.buildRing(
        axis = Vec3(0f, 0f, 1f),
        anchorMm = Vec3(20f, 20f, seam),
        scale = 1f,
        lipHalf = SnapFitHalf.inPlace(lip, seam),
        mateHalf = SnapFitHalf.inPlace(mate, seam),
        parameters = parameters,
        cuts = cuts,
        cutDirections = cutDirections,
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
        check(reason.contains("skin")) { "the refusal does not say the cut would open the skin: " + reason }
        check(reason.contains("insert")) { "the refusal does not name the insert: " + reason }
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
    fun aCutPartsTheInsertsRingAndTheGapIsTheCutWidth() {
        assumeTrue(MeshBoolean.ensureLoaded())
        // The cut severs the LEDGE ring on the insert, and only that ring: the
        // box is the slit, one cut leaves the closed ledge in one arc, and the
        // slit's floor is inside the insert's face rather than out on the skin.
        val whole = built()
        check(whole.cutSolid == null) { "an uncut ring carries a cut" }
        check(pieces(whole.registrationSolid) == 1) { "an uncut ledge came out in pieces" }
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
        // One cut opens the closed ledge into one arc: the ring is no longer
        // closed, which is the whole point - it has an end to bend from.
        check(joint.dimensions.lipArcs == 1) { "one cut left " + joint.dimensions.lipArcs + " arcs" }
        val cut = requireNotNull(joint.cutSolid) { "the cut solid went missing" }
        // One slit is one box: twelve triangles, as wide as the cut.
        check(cut.triangleCount == 12) { "one cut came out as " + cut.triangleCount + " triangles" }
        val across = cut.bounds.maxX - cut.bounds.minX
        check(abs(across - width) < 0.02f) { "a " + width + " mm cut measured " + across + " mm wide" }
        val slitted = MeshBoolean.subtract(joint.registrationSolid, cut)
        check(slitted is MeshBoolean.Result.Success) { "the cut would not come out of the ledge: " + slitted }
        val arcs = pieces((slitted as MeshBoolean.Result.Success).mesh)
        check(arcs == 1) { "one cut left the ledge in " + arcs + " arcs" }
        check(joint.dimensions.lipArcs == arcs) {
            "the readout says " + joint.dimensions.lipArcs + " arcs and the mesh has " + arcs
        }
        // The floor is above the mating plane, in the insert, and past the root
        // the ledge stands on.
        check(cut.bounds.maxZ - seam > 0.6f) {
            "the slit's floor is only " + (cut.bounds.maxZ - seam) + " mm into the insert"
        }
        check(cut.bounds.minZ < seam) { "the slit does not reach below the ledge's own bottom" }
        // And its outboard bound stops short of the part's outer skin: the cut is
        // at y = 20 on the block's edge at y = 0, and the slit starts 1.9 mm in.
        check(cut.bounds.minY > 1.5f) {
            "the slit reaches to y = " + cut.bounds.minY + ", out at the part's own skin"
        }
    }

    /**
     * The rule the user set: a cut may open only the ring that is a protrusion on
     * the embossed insert, never the half whose ring is its own outer wall. Here
     * the tooth's half is a hollow tray whose 2 mm wall is its own skin and the
     * insert is solid, and the app's own booleans are run: the tray comes out of
     * them byte for byte what it was without any cuts, while the insert's ledge
     * ring is the ring that comes apart.
     */
    @Test
    fun theCutsLandOnTheInsertAndNeverOnTheSkinHalf() {
        assumeTrue(MeshBoolean.ensureLoaded())
        val tray = hollowHalf()
        val insert = solidHalf()
        val parameters = SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = 0.6f)
        fun lipOf(cuts: List<Vec3>): SnapFitJoint {
            val outcome = build(tray, insert, parameters, cuts = cuts)
            check(outcome is SnapLip.Outcome.Built) { "refused: " + (outcome as? SnapLip.Outcome.Refused)?.reason }
            return (outcome as SnapLip.Outcome.Built).joint
        }
        val plain = lipOf(emptyList())
        val cut = lipOf(listOf(Vec3(20f, 0f, seam), Vec3(0f, 20f, seam)))
        // The app's own lip path for the tooth's half: the recess comes out of it,
        // the tooth goes on, and no cut is ever taken from it.
        fun toothed(joint: SnapFitJoint): StlMesh {
            val hollowed = MeshBoolean.subtract(tray, joint.subtractSolid)
            check(hollowed is MeshBoolean.Result.Success) { "the recess would not come out: " + hollowed }
            val tooth = MeshBoolean.union((hollowed as MeshBoolean.Result.Success).mesh, joint.unionSolid)
            check(tooth is MeshBoolean.Result.Success) { "the tooth would not go on: " + tooth }
            return (tooth as MeshBoolean.Result.Success).mesh
        }
        val trayPlain = toothed(plain)
        val trayCut = toothed(cut)
        check(trayCut.triangleCount == trayPlain.triangleCount) {
            "the skin half changed shape when the cuts were added: " + trayPlain.triangleCount +
                " triangles became " + trayCut.triangleCount
        }
        check(abs(volumeOf(trayCut) - volumeOf(trayPlain)) < 1e-3f) {
            "the skin half lost " + (volumeOf(trayPlain) - volumeOf(trayCut)) + " mm3 to the cuts"
        }
        // The ring that does come apart is the insert's ledge, and every slit is
        // on it.
        val severed = MeshBoolean.subtract(cut.registrationSolid, requireNotNull(cut.cutSolid))
        check(severed is MeshBoolean.Result.Success) { "the cuts would not come out of the ledge: " + severed }
        val arcs = pieces((severed as MeshBoolean.Result.Success).mesh)
        check(arcs == 2) { "two cuts left the insert's ledge in " + arcs + " arcs" }
        check(cut.dimensions.lipArcs == arcs) {
            "the readout says " + cut.dimensions.lipArcs + " arcs and the mesh has " + arcs
        }
        // The slit stops short of the part's own skin: the tray's outer edges are
        // x = 0 and y = 0, and the cuts reach no further out than 1.9 mm in.
        val box = requireNotNull(cut.cutSolid)
        check(box.bounds.minX > 1f && box.bounds.minY > 1f) {
            "a slit reaches out to (" + box.bounds.minX + ", " + box.bounds.minY + "), at the skin"
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
        // take the cuts out of the LEDGE ring itself and count what is left.
        val severed = MeshBoolean.subtract(all.joint.registrationSolid, requireNotNull(all.joint.cutSolid))
        check(severed is MeshBoolean.Result.Success) { "the cuts would not come out of the ring: " + severed }
        val arcs = pieces((severed as MeshBoolean.Result.Success).mesh)
        check(arcs == 4) { "four cuts left the ring in " + arcs + " arcs" }
        check(all.joint.dimensions.lipArcs == arcs) {
            "the readout says " + all.joint.dimensions.lipArcs + " arcs and the mesh has " + arcs
        }
        // Every bend on its own: the slit meets the ledge as a slice whose length
        // ALONG the bend's bisector is the cut's width, and whose reach ACROSS it
        // spans the ledge's whole cross-section. A slit taken along the contour
        // instead - which is what taking the direction from a sliver's tangent
        // does - comes out the other way round and fails both.
        for (index in bends.indices) {
            val one = build(
                block(size = size),
                solidHalf(size = size),
                SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = width),
                cuts = listOf(bends[index]),
            ) as SnapLip.Outcome.Built
            val box = requireNotNull(one.joint.cutSolid)
            val hit = MeshBoolean.intersect(one.joint.registrationSolid, box)
            check(hit is MeshBoolean.Result.Success) { "bend " + (index + 1) + " misses the ledge ring" }
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

    /**
     * The rule the user stated and can predict: cut at the big angle, towards the
     * centre of the part. At a rectangle's four bends the slit RUNS at the ring's
     * centre - the way across the band is the way to the centre - so its whole
     * depth is measured along that way and its narrow width across it. A slit
     * run along the other bisector, or along the wall, leaves the ring joined.
     */
    @Test
    fun everyAutomaticCutPointsAtTheRingCentre() {
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
        // The ring's own centre: on a rectangle the four bends average to it.
        val centre = bends.fold(Vec3(0f, 0f, 0f)) { sum, bend -> sum + bend } * (1f / bends.size)
        val width = 0.6f
        val all = build(
            block(size = size),
            solidHalf(size = size),
            SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = width),
            cuts = bends,
        )
        check(all is SnapLip.Outcome.Built) { "refused: " + (all as? SnapLip.Outcome.Refused)?.reason }
        val whole = (all as SnapLip.Outcome.Built).joint
        // Each automatic cut severs the ring: the insert's ledge minus its cuts
        // comes apart into four arcs, and the readout's arc count is the mesh's own.
        val severed = MeshBoolean.subtract(whole.registrationSolid, requireNotNull(whole.cutSolid))
        check(severed is MeshBoolean.Result.Success) { "the cuts would not come out of the ring: " + severed }
        val arcs = pieces((severed as MeshBoolean.Result.Success).mesh)
        check(arcs == 4) { "four cuts left the ring in " + arcs + " arcs" }
        check(whole.dimensions.lipArcs == arcs) {
            "the readout says " + whole.dimensions.lipArcs + " arcs and the mesh has " + arcs
        }
        for (index in bends.indices) {
            val one = build(
                block(size = size),
                solidHalf(size = size),
                SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = width),
                cuts = listOf(bends[index]),
            )
            check(one is SnapLip.Outcome.Built) { "refused: " + (one as? SnapLip.Outcome.Refused)?.reason }
            val box = requireNotNull((one as SnapLip.Outcome.Built).joint.cutSolid)
            val hit = MeshBoolean.intersect(one.joint.registrationSolid, box)
            check(hit is MeshBoolean.Result.Success) { "bend " + (index + 1) + " misses the ring" }
            val slice = (hit as MeshBoolean.Result.Success).mesh
            check(volumeOf(slice) > 0.02f) { "bend " + (index + 1) + " takes nothing out of the ring" }
            val radial = (bends[index] - centre).normalized()!!
            val across = Vec3(-radial.y, radial.x, 0f)
            val run = extentAlong(slice, radial)
            val narrow = extentAlong(slice, across)
            check(run > 1f) {
                "bend " + (index + 1) + " runs only " + run +
                    " mm towards the centre, so the slit does not cross the band"
            }
            check(abs(narrow - width) < 0.2f) {
                "bend " + (index + 1) + " is " + narrow + " mm across the way to the centre, and the " +
                    "slit is " + width + " mm wide"
            }
        }
    }

    /**
     * A contour with no real bends - a circle, an ellipse, a rounded seam - is a
     * curve the boolean engine tessellated, and its polyline vertices are not
     * corners. It gets two cuts half the ring apart rather than one at every
     * vertex, and the two runs left are the halves of the ring.
     */
    @Test
    fun aRoundSeamGetsTwoCutsHalfTheRingApart() {
        assumeTrue(MeshBoolean.ensureLoaded())
        val half = roundHalf()
        val mate = solidHalf(size = 40f)
        val bends = SnapLip.bendPoints(
            axis = Vec3(0f, 0f, 1f),
            anchorMm = Vec3(15f, 15f, seam),
            scale = 1f,
            lipHalf = SnapFitHalf.inPlace(half, seam),
            mateHalf = SnapFitHalf.inPlace(mate, seam),
        )
        check(bends.size == 2) { "a round seam with no real bends has " + bends.size + " cuts" }
        val width = 0.6f
        val outcome = build(
            half,
            mate,
            SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = width),
            cuts = bends,
        )
        check(outcome is SnapLip.Outcome.Built) { "refused: " + (outcome as? SnapLip.Outcome.Refused)?.reason }
        val joint = (outcome as SnapLip.Outcome.Built).joint
        check(joint.dimensions.lipCuts == 2) { joint.dimensions.lipCuts.toString() + " cuts on a round ring" }
        val severed = MeshBoolean.subtract(joint.registrationSolid, requireNotNull(joint.cutSolid))
        check(severed is MeshBoolean.Result.Success) { "the cuts would not come out of the ring: " + severed }
        val arcs = pieces((severed as MeshBoolean.Result.Success).mesh)
        check(arcs == 2) { "two cuts left the round ring in " + arcs + " arcs" }
        check(joint.dimensions.lipArcs == arcs) {
            "the readout says " + joint.dimensions.lipArcs + " arcs and the mesh has " + arcs
        }
        val expected = joint.dimensions.lipRingMm * 0.5f - width
        check(abs(joint.dimensions.lipShortestRunMm - expected) < 1f) {
            "the shortest run is " + joint.dimensions.lipShortestRunMm + " mm and half the ring less a " +
                "slit is " + expected + " mm"
        }
        check(abs(joint.dimensions.lipShortestRunMm - joint.dimensions.lipLongestRunMm) < 0.5f) {
            "the two runs are " + joint.dimensions.lipShortestRunMm + " and " +
                joint.dimensions.lipLongestRunMm + " mm, so the cuts are not half the ring apart"
        }
    }

    /**
     * More bends than the ring carries slits: a rhombus turns 120 degrees at two
     * of its corners and 60 at the other two, and a cut wide enough that only two
     * fit must take the two sharp ones. The user's rule is to cut at the big
     * angles, so the order the bends come in is the order that decides.
     */
    @Test
    fun theSharpestBendsAreCutWhenTheRingCannotTakeThemAll() {
        val side = 30f
        val rise = (side * Math.sin(Math.toRadians(60.0))).toFloat()
        val run = (side * Math.cos(Math.toRadians(60.0))).toFloat()
        val corners = listOf(0f to 0f, side to 0f, (side + run) to rise, run to rise)
        val rhombus = prism(corners)
        val cornersMm = corners.map { Vec3(it.first, it.second, seam) }
        // Two 40 mm slits on a 120 mm ring: the gap a cut needs is its width plus
        // the wall two ends stand on, so the two sharp corners are all that fit.
        val width = 40f
        val bends = SnapLip.bendPoints(
            axis = Vec3(0f, 0f, 1f),
            anchorMm = Vec3(20f, 13f, seam),
            scale = 1f,
            lipHalf = SnapFitHalf.inPlace(rhombus, seam),
            mateHalf = SnapFitHalf.inPlace(solidHalf(size = 60f), seam),
            parameters = SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = width),
        )
        check(bends.size == 2) { "a rhombus with four real bends has " + bends.size + " cuts" }
        val sharp = listOf(cornersMm[0], cornersMm[2])
        val blunt = listOf(cornersMm[1], cornersMm[3])
        for (bend in bends) {
            val toSharp = sharp.minOf { (it - bend).length() }
            check(toSharp < 0.01f) {
                "a returned bend is not on a sharp corner (it is " + toSharp + " mm from one)"
            }
            check(blunt.minOf { (it - bend).length() } > toSharp) {
                "a blunt corner was cut before a sharp one"
            }
        }
        // And the two it chose really build.
        val outcome = build(
            rhombus,
            solidHalf(size = 60f),
            SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = width),
            cuts = bends,
        )
        check(outcome is SnapLip.Outcome.Built) { "refused: " + (outcome as? SnapLip.Outcome.Refused)?.reason }
        check((outcome as SnapLip.Outcome.Built).joint.dimensions.lipCuts == 2) {
            "the sharp corners did not build as two cuts"
        }
    }

    /**
     * A stroke across the wall hands its cut the stroke's own direction: the slit
     * lies perpendicular to the stroke wherever it was drawn, so its narrow width
     * is measured along the stroke and its whole depth across it. The same cut
     * without the stroke takes the automatic direction and comes out the other way
     * round - which is what the swipe overrides.
     */
    @Test
    fun aSwipedCutLiesPerpendicularToTheStroke() {
        assumeTrue(MeshBoolean.ensureLoaded())
        val width = 0.8f
        val stroke = requireNotNull(Vec3(1f, 1f, 0f).normalized())
        val across = Vec3(-stroke.y, stroke.x, 0f)
        val at = Vec3(20f, 0f, seam)
        val swiped = build(
            block(),
            solidHalf(),
            SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = width),
            cuts = listOf(at),
            cutDirections = listOf(stroke),
        )
        check(swiped is SnapLip.Outcome.Built) { "refused: " + (swiped as? SnapLip.Outcome.Refused)?.reason }
        val strokeJoint = (swiped as SnapLip.Outcome.Built).joint
        val strokeHit = MeshBoolean.intersect(strokeJoint.registrationSolid, requireNotNull(strokeJoint.cutSolid))
        check(strokeHit is MeshBoolean.Result.Success) { "the swiped cut misses the ring" }
        val swipedSlice = (strokeHit as MeshBoolean.Result.Success).mesh
        check(abs(extentAlong(swipedSlice, stroke) - width) < 0.2f) {
            "the swiped slit is " + extentAlong(swipedSlice, stroke) + " mm along the stroke, and the " +
                "slit is " + width + " mm wide"
        }
        check(extentAlong(swipedSlice, across) > 1f) {
            "the swiped slit does not reach across the stroke"
        }
        // The same stroke drawn the other way is the same slit: its two ways are
        // one line, and the inboard one is chosen, so neither reaches the skin.
        val reversed = build(
            block(),
            solidHalf(),
            SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = width),
            cuts = listOf(at),
            cutDirections = listOf(stroke * -1f),
        )
        check(reversed is SnapLip.Outcome.Built) { "refused: " + (reversed as? SnapLip.Outcome.Refused)?.reason }
        val reversedCut = requireNotNull((reversed as SnapLip.Outcome.Built).joint.cutSolid)
        check(abs(reversedCut.bounds.minY - requireNotNull(strokeJoint.cutSolid).bounds.minY) < 0.01f) {
            "the reversed stroke aims the slit the other way: " + reversedCut.bounds.minY +
                " against " + strokeJoint.cutSolid!!.bounds.minY
        }
        check(reversedCut.bounds.minY > 1.5f) {
            "the reversed stroke reaches the part's own skin at y = " + reversedCut.bounds.minY
        }
        val automatic = build(
            block(),
            solidHalf(),
            SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = width),
            cuts = listOf(at),
        )
        check(automatic is SnapLip.Outcome.Built) {
            "refused: " + (automatic as? SnapLip.Outcome.Refused)?.reason
        }
        val automaticJoint = (automatic as SnapLip.Outcome.Built).joint
        val automaticHit =
            MeshBoolean.intersect(automaticJoint.registrationSolid, requireNotNull(automaticJoint.cutSolid))
        check(automaticHit is MeshBoolean.Result.Success) { "the automatic cut misses the ring" }
        val automaticSlice = (automaticHit as MeshBoolean.Result.Success).mesh
        check(extentAlong(automaticSlice, stroke) > 1f) {
            "the automatic cut already ran perpendicular to the stroke, so the swipe changed nothing"
        }
    }

    /**
     * The depth asked for is the depth built: the slit's own floor is the face of
     * the cut solid that many millimetres above the mating plane, inside the
     * insert, and a depth too shallow to sever the ledge's root is held up to one
     * that does - with the clamp said out loud.
     */
    @Test
    fun theCutDepthControlReallyMovesTheSlitsFloor() {
        assumeTrue(MeshBoolean.ensureLoaded())
        fun cutAt(asked: Float?): Pair<SnapFitJoint, StlMesh> {
            val outcome = build(
                block(),
                solidHalf(),
                SnapFitParameters(
                    lipWallOverrideMm = 2f,
                    lipCutWidthOverrideMm = 0.6f,
                    lipCutDepthMm = asked,
                ),
                cuts = listOf(Vec3(20f, 0f, seam)),
            )
            check(outcome is SnapLip.Outcome.Built) { "refused: " + (outcome as? SnapLip.Outcome.Refused)?.reason }
            val joint = (outcome as SnapLip.Outcome.Built).joint
            return joint to requireNotNull(joint.cutSolid)
        }
        val (defaultJoint, defaultCut) = cutAt(null)
        val through = defaultCut.bounds.maxZ - seam
        check(through > 0.6f) {
            "the default cut has its floor at " + through + " mm, not past the ledge's root"
        }
        check(abs(defaultJoint.dimensions.lipCutDepthMm - through) < 0.02f) {
            "the readout says " + defaultJoint.dimensions.lipCutDepthMm + " mm and the floor is " + through
        }
        val asked = 2.6f
        val (askedJoint, askedCut) = cutAt(asked)
        val floor = askedCut.bounds.maxZ - seam
        check(abs(floor - asked) < 0.02f) { "a " + asked + " mm cut has its floor at " + floor + " mm" }
        val face = flatFaces(askedCut).firstOrNull { it.up && abs(it.z - (seam + asked)) < 0.01f }
        checkNotNull(face) { "the slit's floor is not a face of the cut: " + flatFaces(askedCut) }
        check(abs(askedJoint.dimensions.lipCutDepthMm - asked) < 0.02f) {
            "the readout says " + askedJoint.dimensions.lipCutDepthMm + " mm and the cut is " + floor + " mm"
        }
        val (shallowJoint, shallowCut) = cutAt(0.5f)
        val shallow = shallowCut.bounds.maxZ - seam
        check(abs(shallow - 0.9f) < 0.02f) {
            "a 0.50 mm ask cut " + shallow + " mm deep, and a slit that severs the 0.60 mm root is 0.90"
        }
        val clamp = shallowJoint.clamps.firstOrNull { it.label == "cut depth" }
        checkNotNull(clamp) { "the depth clamp went unsaid: " + shallowJoint.clamps }
        check(clamp.reason.contains("joined to the insert")) { clamp.reason }
    }

    @Test
    fun aCutOnAnEdgeSeversTheLedgeAcrossIt() {
        assumeTrue(MeshBoolean.ensureLoaded())
        // A tap-placed cut on a straight edge: its slit lies across the ledge, one
        // cut width along the edge and the ring's whole depth through it.
        val width = 0.8f
        val one = build(
            block(),
            solidHalf(),
            SnapFitParameters(lipWallOverrideMm = 2f, lipCutWidthOverrideMm = width),
            cuts = listOf(Vec3(20f, 0f, seam)),
        ) as SnapLip.Outcome.Built
        val box = requireNotNull(one.joint.cutSolid)
        val hit = MeshBoolean.intersect(one.joint.registrationSolid, box)
        check(hit is MeshBoolean.Result.Success) { "the cut misses the ledge ring" }
        val slice = (hit as MeshBoolean.Result.Success).mesh
        check(abs(extentAlong(slice, Vec3(1f, 0f, 0f)) - width) < 0.1f) {
            "the slit is " + extentAlong(slice, Vec3(1f, 0f, 0f)) + " mm along the edge"
        }
        check(extentAlong(slice, Vec3(0f, 1f, 0f)) > width + 0.5f) {
            "the slit does not reach across the ledge"
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
        check(reason.contains("stretch of ring between two cuts")) { reason }
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
