package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The pair Apply stages, and the joint the tool must then be able to build on it.
 *
 * The defect these pin down, in its two halves. A preview's two meshes are the
 * booleans of the halves where the plate's packer left them, and the packer
 * separates them: it drops each onto the bed, so a split at Z = 15 puts the
 * high half's mating plane at 0 and the low half's at 15, and it sets them side
 * by side. Apply made those meshes the halves' new own frames, so
 *
 * - the recorded faces no longer met, and the next tap was refused with "the
 *   two parts lie side by side along Z, not across it" - the sentence the
 *   device showed, 23 mm and 15 mm of material; and
 * - where the faces agree, the two halves are still tens of millimetres apart
 *   in the own frame, so the next joint's pocket is built beside the mate's
 *   material and the panel says it "would cut nothing out of the other half".
 *
 * The staging puts the previewed meshes back through the placements they were
 * previewed with ([SnapApply.stage]), which is the assembled, shared frame a
 * split leaves its halves in. These tests build the first joint with the real
 * engine on a plate packed the way the device packs one, stage it the way Apply
 * does, then build a second joint on the result and measure what it really
 * cuts.
 *
 * Runs against a host build of the same Manifold JNI shim the phone runs
 * (scripts/build-manifold-host.sh). Where that build is absent the tests skip,
 * the way the host CuraEngine tests do.
 */
class SnapApplyTest {
    @Before
    fun requireTheEngine() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
    }

    @Test
    fun aSecondJointIsBuiltOnThePairApplyJustMade() {
        for (beamHalf in listOf(SnapJoint.JointHalf.LOW, SnapJoint.JointHalf.HIGH)) {
            val preview = previewOfFirstJoint(beamHalf)
            val (low, high) = applied(preview)

            assertTrue(
                "the applied pair meets on one plane (first beam in $beamHalf): " +
                    preview.lowHalf.faceMm + " and " + preview.highHalf.faceMm,
                SnapFit.halvesMeet(low, high),
            )
            val second = SnapJoint.build(
                axis = ModelPlacement.Axis.Z,
                anchorMm = SECOND_ANCHOR,
                scale = 1f,
                lowHalf = low,
                highHalf = high,
                beamHalf = SnapJoint.JointHalf.LOW,
            )
            val placement = when (second) {
                is SnapJoint.Either.Placed -> second.placement
                is SnapJoint.Either.Flipped -> second.placement
                is SnapJoint.Either.Failed ->
                    throw AssertionError("the second joint was refused: " + second.failure.summary)
            }
            val joint = placement.joint
            // Real geometry, not a null: a beam with material and a pocket that
            // really takes some out of the mate, each sharing volume with the
            // half it was built for - the measurement the device's panel makes.
            assertTrue("the second beam is a solid", joint.unionSolid.triangleCount > 0)
            assertTrue("the second pocket is a solid", joint.subtractSolid.triangleCount > 0)
            assertTrue("the beam has volume", MeshVolume.of(joint.unionSolid) > 0.0)
            assertTrue("the pocket has volume", MeshVolume.of(joint.subtractSolid) > 0.0)
            assertTrue(
                "the second beam roots in its own half: " + shared(joint.unionSolid, placement.beamMesh) + " mm3",
                shared(joint.unionSolid, placement.beamMesh) > 0.0,
            )
            assertTrue(
                "and its pocket really cuts the mate: " + shared(joint.subtractSolid, placement.socketMesh) + " mm3",
                shared(joint.subtractSolid, placement.socketMesh) > 0.0,
            )
        }
    }

    @Test
    fun theRecordedFacesAreThePlanesTheStagedHalvesAreIn() {
        for (beamHalf in listOf(SnapJoint.JointHalf.LOW, SnapJoint.JointHalf.HIGH)) {
            val preview = previewOfFirstJoint(beamHalf)
            val staged = stage(preview)

            assertEquals(
                "the low half records the plane its own frame carries",
                preview.lowHalf.faceMm,
                staged.lowFaceMm,
                0f,
            )
            assertEquals(
                "and so does the high half",
                preview.highHalf.faceMm,
                staged.highFaceMm,
                0f,
            )
            assertTrue(
                "so the pair meets",
                SnapFit.halvesMeet(
                    SnapFitHalf.inPlace(staged.lowMesh, staged.lowFaceMm),
                    SnapFitHalf.inPlace(staged.highMesh, staged.highFaceMm),
                ),
            )
            // The staged halves are back in the assembled frame: the high half's
            // material sits above the low half's plane, not instead on the bed,
            // and the two outlines really face each other - which is what the
            // packer's frame got wrong.
            val lowSpan = SnapFit.extentAlongAxis(staged.lowMesh, ModelPlacement.Axis.Z)!!
            val highSpan = SnapFit.extentAlongAxis(staged.highMesh, ModelPlacement.Axis.Z)!!
            assertTrue(
                "the low half's material lies below the plane: " + lowSpan,
                lowSpan.start < staged.lowFaceMm - 1f,
            )
            assertTrue(
                "and the high half's above it: " + highSpan,
                highSpan.endInclusive > staged.highFaceMm + 1f,
            )
            val lowBounds = staged.lowMesh.bounds
            val highBounds = staged.highMesh.bounds
            assertTrue("the outlines overlap across the seam in X",
                minOf(lowBounds.maxX, highBounds.maxX) > maxOf(lowBounds.minX, highBounds.minX))
            assertTrue("and in Y",
                minOf(lowBounds.maxY, highBounds.maxY) > maxOf(lowBounds.minY, highBounds.minY))
            // Measured, not arithmetic: the socket half's own material starts or
            // ends exactly on the plane both halves record. (The beam half's
            // extent is its beam's tip, which is why the face travels with the
            // frame rather than being measured off the mesh.)
            if (preview.beamIsLow) {
                assertEquals("the mate's material starts on the recorded plane", staged.highFaceMm, highSpan.start, 1e-3f)
            } else {
                assertEquals("the mate's material ends on the recorded plane", staged.lowFaceMm, lowSpan.endInclusive, 1e-3f)
            }
            assertEquals("a split pair's placement is a pure translation", ModelPlacement.IDENTITY, staged.lowLinear)
            assertEquals(ModelPlacement.IDENTITY, staged.highLinear)
        }
    }

    @Test
    fun theFirstApplyStagesTheSameGeometryAsBefore() {
        val preview = previewOfFirstJoint(SnapJoint.JointHalf.LOW)
        val staged = stage(preview)

        // The re-framing is rigid: the same volumes and the same triangles as
        // the meshes the preview showed.
        assertEquals(
            "the low half's volume is unchanged: " + MeshVolume.of(preview.lowMesh),
            MeshVolume.of(preview.lowMesh),
            MeshVolume.of(staged.lowMesh),
            1.0,
        )
        assertEquals(
            "the high half's volume is unchanged: " + MeshVolume.of(preview.highMesh),
            MeshVolume.of(preview.highMesh),
            MeshVolume.of(staged.highMesh),
            1.0,
        )
        assertEquals("same triangles in the low half", preview.lowMesh.triangleCount, staged.lowMesh.triangleCount)
        assertEquals("same triangles in the high half", preview.highMesh.triangleCount, staged.highMesh.triangleCount)
        // And Apply re-centres each staged half on the bed, which cancels the
        // re-framing: the part stands where the un-staged mesh would have stood,
        // so the first apply's plate and its print are untouched.
        for ((name, previewed, stagedMesh) in listOf(
            Triple("low", preview.lowMesh, staged.lowMesh),
            Triple("high", preview.highMesh, staged.highMesh),
        )) {
            val before = placedOnBed(previewed)
            val after = placedOnBed(stagedMesh)
            assertEquals("$name half minX", before.bounds.minX, after.bounds.minX, 1e-3f)
            assertEquals("$name half minY", before.bounds.minY, after.bounds.minY, 1e-3f)
            assertEquals("$name half minZ", before.bounds.minZ, after.bounds.minZ, 1e-3f)
            assertEquals("$name half maxX", before.bounds.maxX, after.bounds.maxX, 1e-3f)
            assertEquals("$name half maxY", before.bounds.maxY, after.bounds.maxY, 1e-3f)
            assertEquals("$name half maxZ", before.bounds.maxZ, after.bounds.maxZ, 1e-3f)
        }
    }

    /**
     * The two halves of the root cause, kept as the regression they are: the
     * packer's own frame refuses the second joint outright when its faces are
     * taken as they stand, and when they are forced to agree the pocket is
     * built beside the mate's material instead of in it. The production path
     * never works in that frame - it stages through [SnapApply.stage] first -
     * so this test stays green and says why the staging exists.
     */
    @Test
    fun thePackersFrameIsWhatRefusedTheSecondJoint() {
        val preview = previewOfFirstJoint(SnapJoint.JointHalf.LOW)
        // Where the previewed meshes' mating planes sit on the plate: the low
        // half's at its own thickness, the high half's at 0, the floor both were
        // dropped onto. A pure-translation placement is what a split makes.
        val plateLowFace = preview.lowHalf.faceMm + preview.lowHalf.transform.translationZmm.toFloat()
        val plateHighFace = preview.highHalf.faceMm + preview.highHalf.transform.translationZmm.toFloat()
        assertNotEquals("the packer leaves the two planes at different coordinates", plateLowFace, plateHighFace)

        val asPlateLow = SnapFitHalf.inPlace(preview.lowMesh, plateLowFace)
        val asPlateHigh = SnapFitHalf.inPlace(preview.highMesh, plateHighFace)
        assertFalse("so the pair does not meet", SnapFit.halvesMeet(asPlateLow, asPlateHigh))
        val refused = SnapJoint.build(
            axis = ModelPlacement.Axis.Z,
            anchorMm = PLATE_ANCHOR,
            scale = 1f,
            lowHalf = asPlateLow,
            highHalf = asPlateHigh,
            beamHalf = SnapJoint.JointHalf.LOW,
        )
        assertTrue("and the second joint is refused for it: " + refused, refused is SnapJoint.Either.Failed)
        assertTrue(
            "with the sentence the device showed",
            (refused as SnapJoint.Either.Failed).failure.summary
                .contains("lie side by side along Z, not across it"),
        )

        // Slide the planes onto one coordinate - what a fix that only puts the
        // faces in agreement leaves - and the refusal goes away and is replaced
        // by the other half of the defect: the halves are still the packer's
        // tens of millimetres apart across the seam, so the pocket is built
        // beside the mate's material and takes nothing out of it.
        val samePlaneLow = SnapFitHalf.inPlace(preview.lowMesh, preview.lowHalf.faceMm)
        val samePlaneHigh = SnapFitHalf.inPlace(
            shifted(preview.highMesh, 0f, 0f, preview.lowHalf.faceMm - plateHighFace),
            preview.lowHalf.faceMm,
        )
        assertTrue("the slid pair meets", SnapFit.halvesMeet(samePlaneLow, samePlaneHigh))
        val built = SnapJoint.build(
            axis = ModelPlacement.Axis.Z,
            anchorMm = PLATE_ANCHOR,
            scale = 1f,
            lowHalf = samePlaneLow,
            highHalf = samePlaneHigh,
            beamHalf = SnapJoint.JointHalf.LOW,
        )
        val placement = when (built) {
            is SnapJoint.Either.Placed -> built.placement
            is SnapJoint.Either.Flipped -> built.placement
            is SnapJoint.Either.Failed -> throw AssertionError("the slid pair should build: " + built.failure.summary)
        }
        // The measurement the panel makes: what the pocket takes out of the mate.
        val carved = MeshBoolean.subtract(placement.socketMesh, placement.joint.subtractSolid)
        assertTrue("the pocket's subtraction has to run: " + carved, carved is MeshBoolean.Result.Success)
        val loss = MeshVolume.of(placement.socketMesh) - (carved as MeshBoolean.Result.Success).volumeMm3
        assertEquals(
            "the second pocket cuts nothing out of the mate in the packer's frame: " + loss + " mm3",
            0.0,
            loss,
            1e-6,
        )
    }

    /** One preview of the pair after the first joint: the meshes the plate shows and where they sit. */
    private class Preview(
        val lowHalf: SnapFitHalf,
        val highHalf: SnapFitHalf,
        val lowMesh: StlMesh,
        val highMesh: StlMesh,
        val joint: SnapFitJoint,
        val beamIsLow: Boolean,
    )

    /**
     * A 30 mm cube split at Z = 15, its halves on the bed as the device's packer
     * leaves them: each dropped so its own floor is 0 and set side by side, the
     * high half's slot 36 mm along X.
     */
    private fun previewOfFirstJoint(beamHalf: SnapJoint.JointHalf): Preview {
        val cube = MeshFixtures.box(0f, 0f, 0f, 30f, 30f, 30f)
        val lowOwn = BedClipper.clipClosed(cube, ModelPlacement.Axis.Z, 15f, BedClipper.Half.LOW)
        val highOwn = BedClipper.clipClosed(cube, ModelPlacement.Axis.Z, 15f, BedClipper.Half.HIGH)
        val lowFit = SnapFitHalf.placed(lowOwn, packed(lowOwn, LOW_CENTER), 15f)
        val highFit = SnapFitHalf.placed(highOwn, packed(highOwn, HIGH_CENTER), 15f)
        val built = SnapJoint.build(
            axis = ModelPlacement.Axis.Z,
            anchorMm = FIRST_ANCHOR,
            scale = 1f,
            lowHalf = lowFit,
            highHalf = highFit,
            beamHalf = beamHalf,
        )
        val placement = when (built) {
            is SnapJoint.Either.Placed -> built.placement
            is SnapJoint.Either.Flipped -> built.placement
            is SnapJoint.Either.Failed -> throw AssertionError("the first joint has to exist: " + built.failure.summary)
        }
        val joint = placement.joint
        val union = (MeshBoolean.union(placement.beamMesh, joint.unionSolid) as? MeshBoolean.Result.Success)
            ?.mesh ?: throw AssertionError("the first beam's union has to run")
        val registered = (MeshBoolean.union(union, joint.registrationSolid) as? MeshBoolean.Result.Success)
            ?.mesh ?: throw AssertionError("the first registration step has to run")
        val socket = (MeshBoolean.subtract(placement.socketMesh, joint.subtractSolid) as? MeshBoolean.Result.Success)
            ?.mesh ?: throw AssertionError("the first pocket's subtraction has to run")
        val recessed = (MeshBoolean.subtract(socket, joint.registrationRecess) as? MeshBoolean.Result.Success)
            ?.mesh ?: throw AssertionError("the first recess has to cut")
        val beamIsLow = placement.beamMesh === lowFit.mesh
        return Preview(
            lowHalf = lowFit,
            highHalf = highFit,
            lowMesh = if (beamIsLow) registered else recessed,
            highMesh = if (beamIsLow) recessed else registered,
            joint = joint,
            beamIsLow = beamIsLow,
        )
    }

    private fun stage(preview: Preview): StagedSnapPair = SnapApply.stage(
        lowHalf = preview.lowHalf,
        highHalf = preview.highHalf,
        lowMesh = preview.lowMesh,
        highMesh = preview.highMesh,
        lowName = "lower",
        highName = "upper",
    )

    /** The staged pair as Apply's two objects carry it, on the same packed plate. */
    private fun applied(preview: Preview): Pair<SnapFitHalf, SnapFitHalf> {
        val staged = stage(preview)
        return objectFor(staged.lowMesh, staged.lowLinear, staged.lowFaceMm, LOW_CENTER) to
            objectFor(staged.highMesh, staged.highLinear, staged.highFaceMm, HIGH_CENTER)
    }

    /** What cutHalfObject() plus the packer leave on the plate for a staged mesh. */
    private fun objectFor(
        mesh: StlMesh,
        linear: List<Double>,
        faceMm: Float,
        center: Pair<Double, Double>,
    ): SnapFitHalf {
        val placement = ModelPlacement.centeredOnBed(mesh, BED_MM, BED_MM).copy(
            linear = linear,
            centerXmm = center.first,
            centerYmm = center.second,
        )
        return SnapFitHalf.placed(mesh, placement.transformed(mesh), faceMm)
    }

    /** A cut half on the plate: dropped to the bed and centred on [center]. */
    private fun packed(mesh: StlMesh, center: Pair<Double, Double>): StlMesh =
        ModelPlacement(centerXmm = center.first, centerYmm = center.second, baseZmm = 0.0).transformed(mesh)

    /** What centredOnBed() alone leaves on the plate; the un-staged comparison. */
    private fun placedOnBed(mesh: StlMesh): StlMesh =
        ModelPlacement.centeredOnBed(mesh, BED_MM, BED_MM).transformed(mesh)

    /** [mesh] shifted, keeping its winding and its triangle count. */
    private fun shifted(mesh: StlMesh, dx: Float, dy: Float, dz: Float): StlMesh {
        val builder = MeshSolidBuilder(mesh.displayName)
        val vertices = mesh.interleavedVertices
        for (triangle in 0 until mesh.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            builder.addTriangle(
                vertices[base] + dx, vertices[base + 1] + dy, vertices[base + 2] + dz,
                vertices[base + 6] + dx, vertices[base + 7] + dy, vertices[base + 8] + dz,
                vertices[base + 12] + dx, vertices[base + 13] + dy, vertices[base + 14] + dz,
            )
        }
        return builder.build()
    }

    /** The volume two solids share, straight from the engine. */
    private fun shared(first: StlMesh, second: StlMesh): Double {
        if (first.triangleCount == 0 || second.triangleCount == 0) return 0.0
        val result = MeshBoolean.intersect(first, second)
        assertTrue(
            "the engine refused the intersection: " + (result as? MeshBoolean.Result.Failure)?.reason,
            result is MeshBoolean.Result.Success,
        )
        return (result as MeshBoolean.Result.Success).volumeMm3
    }

    private companion object {
        /** On the seam, near the middle of the 30 mm face. */
        val FIRST_ANCHOR = Vec3(15f, 15f, 15f)
        /** And away from it, for the second joint. */
        val SECOND_ANCHOR = Vec3(24f, 24f, 15f)
        /** The same point on the plate, in the frame the packer produced. */
        val PLATE_ANCHOR = Vec3(97f, 21f, 15f)
        val LOW_CENTER = 97.0 to 21.0
        val HIGH_CENTER = 133.0 to 21.0
        const val BED_MM = 230.0
    }
}
