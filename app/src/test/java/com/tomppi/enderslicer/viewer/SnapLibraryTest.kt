package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The bundled library's own geometry, measured in the meshes.
 *
 * Nothing here counts anything: the pair is loaded, placed on two halves that
 * meet at a plane, and then the meshes themselves are asked where the hook
 * landed, where the catch landed, how far the catch's graft reaches, whether
 * the applied halves still fit together, and what the one scale slider does to
 * the pair's size and to its register. The shapes are kaje's own ready-made
 * joints, so the fit these tests measure is the library's, and the numbers they
 * compare against are the library's too.
 */
class SnapLibraryTest {
    /**
     * Every shape loads, both its pieces are closed solids, the two pieces are
     * in one assembled coordinate system, and they do not interpenetrate. A
     * shape that fails any of these cannot be grafted at all, and this is where
     * that is said - with the shape's own name in the message.
     */
    @Test
    fun everyShapeLoadsWithItsPiecesClosedAndInRegister() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        for (shape in SnapLibraryShape.entries) {
            // Loaded from scratch, timed: the panel waits for this the first time
            // a shape is picked, and a shape's files are megabytes.
            SnapLibrary.forget()
            val started = System.nanoTime()
            val pair = pairOf(shape)
            val loadMillis = (System.nanoTime() - started) / 1_000_000
            val catch = read(shape.catchFile)
            assertTrue(
                shape.label + ": the hook piece is not a closed solid",
                MeshBoolean.isManifold(pair.hook),
            )
            assertTrue(
                shape.label + ": the catch piece is not a closed solid",
                MeshBoolean.isManifold(catch),
            )
            // Non-interpenetration, measured rather than asserted: take the
            // catch out of the hook and see how much of the hook went. Two
            // pieces that only touch on the mating plane lose nothing; two that
            // overlap lose the overlap. (The engine cannot read back an
            // intersection of two solids that touch on a coplanar face, which is
            // exactly how the author assembled these, so the difference is what
            // answers the question here.)
            val cut = MeshBoolean.subtract(pair.hook, catch)
            assertTrue(
                shape.label + ": the two pieces could not even be separated: " +
                    (cut as? MeshBoolean.Result.Failure)?.reason,
                cut is MeshBoolean.Result.Success,
            )
            val lost = MeshVolume.of(pair.hook) - (cut as MeshBoolean.Result.Success).volumeMm3
            assertTrue(
                shape.label + ": the two pieces interpenetrate by " +
                    millimetres(lost) + " mm3, so they are not one assembly",
                lost < 0.05,
            )
            assertTrue(
                shape.label + ": the pair has no clearance anywhere (smallest gap " +
                    millimetres(pair.gapOneToOneMm.toDouble()) + " mm)",
                pair.gapOneToOneMm > 0f,
            )
            println(
                String.format(
                    Locale.ROOT,
                    "LIBRARY %-20s hook %6.2f x %6.2f x %6.2f mm, catch %6.2f x %6.2f x %6.2f mm, " +
                        "gap %.3f mm, box %.1f x %.1f x %.1f, graft tris %d, void tris %d, loaded %d ms",
                    shape.label,
                    pair.hookSizeMm.x, pair.hookSizeMm.y, pair.hookSizeMm.z,
                    catch.bounds.maxX - catch.bounds.minX,
                    catch.bounds.maxY - catch.bounds.minY,
                    catch.bounds.maxZ - catch.bounds.minZ,
                    pair.gapOneToOneMm,
                    pair.box.sizeX, pair.box.sizeY, pair.box.sizeZ,
                    pair.catchGraft.triangleCount, pair.catchVoid.triangleCount, loadMillis,
                ),
            )
        }
    }

    /** A rectangular shape: the hook lands on the half that was chosen, and the catch on the mate. */
    @Test
    fun aRectangularShapeLandsItsHookOnTheChosenHalfAndItsCatchOnTheMate() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        for (half in SnapJoint.JointHalf.entries) {
            val placement = placementOf(SnapLibraryShape.CANTILEVER01, half)
            val joint = placement.joint
            val toMate = if (half == SnapJoint.JointHalf.LOW) 1f else -1f
            assertEquals(
                "the hook went into the half the user chose",
                if (half == SnapJoint.JointHalf.LOW) "low" else "high",
                placement.beamMesh.displayName,
            )
            assertEquals(
                "and the catch into the mate",
                if (half == SnapJoint.JointHalf.LOW) "high" else "low",
                placement.socketMesh.displayName,
            )
            assertTrue("nothing was flipped for a library shape", placement.chosenHalfCarriesBeam)
            // The seam is at z = 20. The hook's plate stands on the chosen half's
            // side of it and its hooks reach across into the mate's.
            assertTrue(
                "the hook roots in the chosen half: " + bounds(joint.unionSolid),
                if (toMate > 0f) {
                    joint.unionSolid.bounds.minZ < SEAM_Z
                } else {
                    joint.unionSolid.bounds.maxZ > SEAM_Z
                },
            )
            assertTrue(
                "and reaches across the seam into the mate: " + bounds(joint.unionSolid),
                if (toMate > 0f) {
                    joint.unionSolid.bounds.maxZ > SEAM_Z + 5f
                } else {
                    joint.unionSolid.bounds.minZ < SEAM_Z - 5f
                },
            )
            // The catch's own material is entirely on the mate's side of the seam.
            assertTrue(
                "the catch lands on the mate: " + bounds(joint.registrationSolid),
                if (toMate > 0f) {
                    joint.registrationSolid.bounds.minZ > SEAM_Z - 0.01f
                } else {
                    joint.registrationSolid.bounds.maxZ < SEAM_Z + 0.01f
                },
            )
            assertTrue(
                "and it is the mate's half the pipeline will boolean it into",
                joint.registrationSolid.bounds.minZ != joint.registrationSolid.bounds.maxZ,
            )
        }
    }

    /** A round shape: the same two facts, because the placement is the same arithmetic. */
    @Test
    fun aRoundShapeLandsItsHookOnTheChosenHalfAndItsCatchOnTheMate() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        val placement = placementOf(SnapLibraryShape.ANNULAR, SnapJoint.JointHalf.HIGH)
        val joint = placement.joint
        // The hook is in the HIGH half, so the library is walked the other way:
        // its plate stands above the seam and its collar reaches down into the
        // low half's space.
        // The round shape's hook is a collar that stands ON the seam: it is all
        // on the chosen half's side, and it is the catch's bead that reaches
        // across into the collar's groove.
        assertTrue(
            "the hook stands on the seam in the chosen half: " + bounds(joint.unionSolid),
            joint.unionSolid.bounds.minZ > SEAM_Z - 0.02f && joint.unionSolid.bounds.maxZ > SEAM_Z + 2f,
        )
        assertTrue(
            "the catch's bead reaches up into the hook's half: " + bounds(joint.registrationSolid),
            joint.registrationSolid.bounds.maxZ > SEAM_Z + 1f,
        )
        assertTrue(
            "and its body stays in the mate's: " + bounds(joint.registrationSolid),
            joint.registrationSolid.bounds.minZ < SEAM_Z - 2f,
        )
    }

    /**
     * The catch is grafted LOCAL. The mate's own material inside the graft's
     * neighbourhood is replaced by the catch's shape there, and outside that
     * neighbourhood the part is untouched: measured by taking the same region
     * out of the half before and after, and comparing what is in it.
     */
    @Test
    fun theCatchGraftIsLocalToTheBoxAroundTheHook() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        val placement = placementOf(SnapLibraryShape.CANTILEVER01, SnapJoint.JointHalf.LOW)
        val joint = placement.joint
        val mate = placement.socketMesh
        val freed = success(MeshBoolean.subtract(mate, joint.registrationRecess))
        val applied = success(MeshBoolean.union(freed.mesh, joint.registrationSolid))
        // Everything of the mate outside a box grown around the joint's own
        // material has to be the same material it was. The grown box is the
        // joint's whole graft - hook and catch together - with a margin, which
        // is the neighbourhood the graft is allowed to touch.
        val graft = unionOf(joint.unionSolid, joint.registrationSolid)
        val margin = SnapLibrary.GRAFT_MARGIN_MM + 1f
        val neighbourhood = MeshFixtures.box(
            graft.bounds.minX - margin, graft.bounds.minY - margin, graft.bounds.minZ - margin,
            graft.bounds.maxX + margin, graft.bounds.maxY + margin, graft.bounds.maxZ + margin,
            name = "neighbourhood",
        )
        val outside = success(
            MeshBoolean.subtract(
                MeshFixtures.box(
                    mate.bounds.minX - 5f, mate.bounds.minY - 5f, mate.bounds.minZ - 5f,
                    mate.bounds.maxX + 5f, mate.bounds.maxY + 5f, mate.bounds.maxZ + 5f,
                    name = "everything",
                ),
                neighbourhood,
            ),
        )
        val before = success(MeshBoolean.intersect(mate, outside.mesh)).volumeMm3
        val after = success(MeshBoolean.intersect(applied.mesh, outside.mesh)).volumeMm3
        assertEquals(
            "the mate changed outside the graft's own neighbourhood",
            before,
            after,
            1e-3,
        )
        // And inside it, it really did change: the graft is not a no-op.
        val inside = success(MeshBoolean.intersect(applied.mesh, neighbourhood)).volumeMm3
        val wasInside = success(MeshBoolean.intersect(mate, neighbourhood)).volumeMm3
        assertTrue(
            "the graft changed nothing at all: " + millimetres(inside - wasInside) + " mm3",
            kotlin.math.abs(inside - wasInside) > 1.0,
        )
    }

    /**
     * The two applied halves FIT. Assembled - each graft on its own half, the
     * two halves pushed together on the seam - the hook and the mate's own
     * material do not interpenetrate, and the library's own designed clearance
     * is still there between them.
     */
    @Test
    fun theAppliedHalvesFitAsTheLibraryDesignedThem() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        for (shape in listOf(SnapLibraryShape.CANTILEVER01)) {
            val pair = pairOf(shape)
            val placement = placementOf(shape, SnapJoint.JointHalf.LOW)
            val joint = placement.joint
            val mate = placement.socketMesh
            val applied = success(MeshBoolean.union(freedFrom(mate, joint), joint.registrationSolid))
            // Assembled, the hook and the mate's own material must not occupy the
            // same space: taking the hook out of the applied mate loses nothing.
            // (The engine cannot read back an intersection of two solids that
            // touch on the seat, which is how the pair is assembled, so the
            // difference is what answers it.)
            val clearance = success(MeshBoolean.subtract(applied.mesh, joint.unionSolid))
            val seized = MeshVolume.of(applied.mesh) - clearance.volumeMm3
            assertTrue(
                shape.label + ": the hook and the mate's material interpenetrate by " +
                    millimetres(seized) + " mm3, so the halves would not close",
                seized < 0.05,
            )
            // And the library's own designed gap is still there, because the two
            // grafts are the author's own pieces placed by one transform: the
            // distance between them is the loaded pair's, to the last micrometre.
            val gap = SnapLibrary.smallestGapMm(joint.unionSolid, joint.registrationSolid)
            println(
                String.format(
                    Locale.ROOT,
                    "APPLIED %-14s library gap %.4f mm, applied graft gap %.4f mm, seized %.4f mm3",
                    shape.label, pair.gapOneToOneMm, gap, seized,
                ),
            )
            assertEquals(
                shape.label + ": the applied pair's own clearance is not the library's",
                pair.gapOneToOneMm,
                gap,
                1e-3f,
            )
        }
    }

    /**
     * The round pair needs room. Its collar is a ring that stands on the seam
     * and its catch is a bead the collar's groove closes over, so on a seam
     * whose two halves are SOLID where the joint lands, the bead ends up inside
     * the half that takes the hook's own material - the printed halves would
     * intersect and could not close. This measures that, because it is the one
     * failure no closedness or seat check anywhere would see, and it is why the
     * pipeline refuses a round joint on a solid seam by name.
     */
    @Test
    fun theRoundPairNeedsRoomAndOnASolidSeamTheHalvesWouldIntersect() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        val placement = placementOf(SnapLibraryShape.ANNULAR, SnapJoint.JointHalf.LOW)
        val joint = placement.joint
        val mate = placement.socketMesh
        val applied = success(MeshBoolean.union(freedFrom(mate, joint), joint.registrationSolid))
        val clash = success(MeshBoolean.intersect(applied.mesh, placement.beamMesh))
        println(
            String.format(
                Locale.ROOT,
                "ROUND annular on a solid seam: the two halves would overlap by %.3f mm3",
                clash.volumeMm3,
            ),
        )
        assertTrue(
            "the round pair must be measured to overlap a solid seam, or the check that " +
                "refuses it has nothing to stand on: " + millimetres(clash.volumeMm3) + " mm3",
            clash.volumeMm3 > 0.5,
        )
        // And the rectangular pair, whose catch frees its own room, does not.
        val rectangular = placementOf(SnapLibraryShape.CANTILEVER01, SnapJoint.JointHalf.LOW)
        val rectangularMate = success(
            MeshBoolean.union(
                freedFrom(rectangular.socketMesh, rectangular.joint),
                rectangular.joint.registrationSolid,
            ),
        )
        val clear = success(MeshBoolean.intersect(rectangularMate.mesh, rectangular.beamMesh))
        assertTrue(
            "the rectangular pair's two applied halves must not overlap: " +
                millimetres(clear.volumeMm3) + " mm3",
            clear.volumeMm3 < 0.05,
        )
    }

    /**
     * The one scale slider scales BOTH pieces together, and the register between
     * them does not move: the hook's built size follows the scale exactly, and so
     * does the clearance the pair runs on.
     */
    @Test
    fun theScaleSizesThePairAndKeepsItsRegister() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        val shape = SnapLibraryShape.CANTILEVER01
        val one = placementOf(shape, SnapJoint.JointHalf.LOW, scale = 1f)
        val half = placementOf(shape, SnapJoint.JointHalf.LOW, scale = 0.5f)
        val big = builtSize(one.joint)
        val small = builtSize(half.joint)
        assertEquals("the hook's width follows the scale", big[0] * 0.5f, small[0], 0.02f)
        assertEquals("the hook's length follows the scale", big[1] * 0.5f, small[1], 0.02f)
        assertEquals("the hook's depth follows the scale", big[2] * 0.5f, small[2], 0.02f)
        assertEquals(
            "the clearance follows the scale too",
            one.joint.dimensions.libraryGapMm * 0.5f,
            half.joint.dimensions.libraryGapMm,
            1e-4f,
        )
        // The register: where the hook sits relative to the pair's own mating
        // plane, as a share of its own length, is the same at both scales. That
        // is what "the register never changes" means in the meshes.
        assertEquals(
            "the hook's share of the seam plane moved with the scale",
            seamShare(one.joint),
            seamShare(half.joint),
            0.002f,
        )
        assertEquals(
            "the catch's own size follows the scale as well",
            0.5f,
            (half.joint.registrationSolid.bounds.maxZ - half.joint.registrationSolid.bounds.minZ) /
                (one.joint.registrationSolid.bounds.maxZ - one.joint.registrationSolid.bounds.minZ),
            0.02f,
        )
    }

    /** The joint placed on two 40 mm halves meeting at [SEAM_Z]. */
    private fun placementOf(
        shape: SnapLibraryShape,
        half: SnapJoint.JointHalf,
        scale: Float = 1f,
    ): SnapJoint.Placement {
        val low = SnapFitHalf.inPlace(MeshFixtures.box(0f, 0f, 0f, 40f, 40f, SEAM_Z, "low"), SEAM_Z)
        val high = SnapFitHalf.inPlace(
            MeshFixtures.box(0f, 0f, SEAM_Z, 40f, 40f, SEAM_Z + 8f, "high"),
            SEAM_Z,
        )
        val result = SnapJoint.build(
            axis = ModelPlacement.Axis.Z,
            anchorMm = Vec3(20f, 20f, SEAM_Z),
            scale = scale,
            lowHalf = low,
            highHalf = high,
            beamHalf = half,
            style = SnapJointStyle.LIBRARY_HOOK,
            library = pairOf(shape),
        )
        return (result as SnapJoint.Either.Placed).placement
    }

    /** The hook's own breadth, length and depth, as built. */
    private fun builtSize(joint: SnapFitJoint): FloatArray = floatArrayOf(
        joint.unionSolid.bounds.maxX - joint.unionSolid.bounds.minX,
        joint.unionSolid.bounds.maxY - joint.unionSolid.bounds.minY,
        joint.unionSolid.bounds.maxZ - joint.unionSolid.bounds.minZ,
    )

    /** Where the hook's material runs, as a share of its own depth, from the seam plane. */
    private fun seamShare(joint: SnapFitJoint): Float {
        val size = builtSize(joint)[2]
        val past = joint.unionSolid.bounds.maxZ - SEAM_Z
        return past / size
    }

    /**
     * The mate with the graft's own room freed: the box minus the catch, taken
     * out. A shape whose catch is not a housing has no such solid - the round
     * catch is a bead to add, not a box to open - so the mate is its own answer
     * there, exactly as the pipeline's own branch has it.
     */
    private fun freedFrom(mate: StlMesh, joint: SnapFitJoint): StlMesh {
        val void = joint.registrationRecess
        return if (void.triangleCount == 0) mate else success(MeshBoolean.subtract(mate, void)).mesh
    }

    private fun unionOf(first: StlMesh, second: StlMesh): StlMesh =
        success(MeshBoolean.union(first, second)).mesh

    private fun success(result: MeshBoolean.Result): MeshBoolean.Result.Success =
        result as? MeshBoolean.Result.Success
            ?: error("the engine refused: " + (result as MeshBoolean.Result.Failure).reason)

    private fun pairOf(shape: SnapLibraryShape): SnapLibraryPair = SnapLibrary.load(shape, ::read)

    private fun read(file: String): StlMesh {
        val directory = listOf(
            File("src/main/assets/" + SnapLibrary.DIRECTORY),
            File("app/src/main/assets/" + SnapLibrary.DIRECTORY),
        ).firstOrNull(File::isDirectory) ?: error("the library assets are not where they were")
        return StlParser.parse(File(directory, file), file)
    }

    private fun bounds(mesh: StlMesh): String = String.format(
        Locale.ROOT,
        "x %.2f..%.2f y %.2f..%.2f z %.2f..%.2f",
        mesh.bounds.minX, mesh.bounds.maxX,
        mesh.bounds.minY, mesh.bounds.maxY,
        mesh.bounds.minZ, mesh.bounds.maxZ,
    )

    private fun millimetres(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private companion object {
        /** Where the two test halves meet. */
        const val SEAM_Z = 20f
    }
}
