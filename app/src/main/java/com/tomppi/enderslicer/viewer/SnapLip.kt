package com.tomppi.enderslicer.viewer

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Which joint the user asked for, beside the cantilever.
 *
 * [CANTILEVER] is the joint the tool has always built: a beam lying across the
 * seam with a barb on its free end, the rung ladder, the key and the whole-seam
 * pad. [WALL_LIP] is the other one, and it is a different kind of joint rather
 * than a variation: nothing hangs in the air, the tooth stands on the half's own
 * wall, and what the wall does when the mate's ledge rides past is the whole
 * mechanism. The ladder belongs to the cantilever; the lip has one shape and
 * says which of its own requirements the material failed.
 */
enum class SnapJointStyle(val label: String) {
    CANTILEVER("Cantilever"),
    WALL_LIP("Wall lip"),
}

/**
 * The wall lip: one tooth on the half's own wall, one ledge on solid material
 * opposite it, and the wall between them as the spring.
 *
 * The design, per joint, one feature on each half (docs, and the agreed
 * drawing):
 *
 *  - On the half whose wall is free - the LIP half - a TOOTH stands inward from
 *    the wall's inner face, at the mating plane. Its trailing face is FLAT and
 *    perpendicular to the assembly axis; its leading face is a ramp at the
 *    parameter's angle (45 degrees by default, 90 for a square face). The tooth
 *    is rooted a little way into the wall, so the union is a volume and not two
 *    shells meeting on a plane.
 *  - On the mate - the half the drawing calls embossed, and which has to be
 *    SOLID a little inboard of the wall - the matching LEDGE protrudes outboard
 *    towards the wall: the same angle on its leading face, the same flat trailing
 *    face opposite the tooth's.
 *
 * Assembled, the two flat faces are a clearance apart along the axis, and that
 * pair is the whole hold: pulling the halves apart brings them together over the
 * engagement the user set, and every other face of the joint is a ramp that lets
 * the parts close. The wall bends outward as the ledge rides its tooth's ramp
 * and springs back behind it - the tooth passes under the ledge, and the ledge's
 * flat face comes to rest against the tooth's. There is no beam: the only
 * material that moves is the half's own wall, which is why the wall's thickness
 * and free length are measured from the mesh and why a wall that cannot move - a
 * corner, a stub, a solid block - is refused rather than built.
 *
 * A RECESS is cut into the lip half, inboard of the wall's face and open at the
 * mating plane, and it is what makes the rest possible: the tooth stands in it,
 * the mate's ledge comes into it, and the wall it leaves outboard is free to
 * bend. On a half that is already hollow inboard of the wall the recess cuts
 * nothing, which is the usual case for a split box; on a half with material
 * behind the wall it is the cut that frees the wall.
 *
 * The booleans are NOT the cantilever's: the tooth is unioned into the lip half,
 * the recess is subtracted from the SAME half, and the ledge is unioned into the
 * MATE - two unions on two different halves, which the one-union-per-half
 * pipeline cannot express. [SnapFitJoint] still carries the four solids, and
 * placeEveryJoint's lip branch is what knows where each of them goes.
 *
 * Pure: mesh probes and arithmetic, no UI, no engine, no state, no I/O.
 */
object SnapLip {
    /** What a lip build came out as: the joint, or the requirement the material failed. */
    sealed interface Outcome {
        data class Built(val joint: SnapFitJoint) : Outcome

        /** [reason] is the whole sentence the panel shows, with the numbers measured. */
        data class Refused(val reason: String) : Outcome
    }

    /**
     * One lip, built on the pair's own faces. [lipHalf] is the half whose wall
     * carries the tooth - the half the user chose - and [mateHalf] is the mate
     * that has to give the ledge something solid to stand on.
     *
     * Every dimension comes from the parameters and the scale, and every one of
     * them is then clamped by what the two halves' own material allows; what the
     * material refused is reported in [SnapFitJoint.clamps] rather than silently
     * changed.
     */
    fun build(
        axis: Vec3,
        /** The tapped point, in the two halves' own (unplaced) coordinates. */
        anchorMm: Vec3,
        scale: Float,
        lipHalf: SnapFitHalf,
        mateHalf: SnapFitHalf,
        parameters: SnapFitParameters = SnapFitParameters(),
        requested: SnapFitRung? = null,
    ): Outcome {
        if (!axis.isFinite() || !anchorMm.isFinite() || !scale.isFinite() || scale <= 0f) {
            return Outcome.Refused("cannot be built: the split's axis or the tap is not a real place.")
        }
        if (!parameters.isUsable()) {
            return Outcome.Refused("cannot be built: the joint's dimensions are not all positive numbers.")
        }
        if (lipHalf.mesh.triangleCount <= 0 || mateHalf.mesh.triangleCount <= 0) {
            return Outcome.Refused("cannot be built: one of the two halves has no geometry to work on.")
        }
        val direction = axis.normalized()
            ?: return Outcome.Refused("cannot be built: the assembly direction has no length.")
        val face = lipHalf.faceMm
        val mateFace = mateHalf.faceMm
        if (face > mateFace + PLANE_TOLERANCE_MM) {
            return Outcome.Refused(
                "cannot be built: the two halves overlap along the assembly axis, so there is no seam here.",
            )
        }
        val rawSide = perpendicularTo(direction)
        val rawRise = direction.cross(rawSide)

        // The tapped point dropped onto each half's own mating face: each half's
        // frame has its own face at local z = 0, which is what lets the feature on
        // one half be built without ever looking at where the other half stands.
        val alongAnchor = anchorMm.dot(direction)
        val lipOrigin = anchorMm - direction * (alongAnchor - face)
        val mateOrigin = anchorMm - direction * (alongAnchor - mateFace)

        // The material each half has AT the seam, read in the plane's own two axes
        // around the tap: the band of rise nearest the tapped point, which is the
        // wall (or the block, or the corner) on the lip half and the mate's own
        // face. The tap may be on either half - it marks a place on the seam - so
        // each half's band is the nearest material to it on that half.
        val rawLipCap = SnapFit.capTriangles(lipHalf, direction, rawSide, rawRise, face, lipOrigin)
        val rawMateCap = SnapFit.capTriangles(mateHalf, direction, rawSide, rawRise, face = mateFace, origin = mateOrigin)
        if (rawLipCap.isEmpty()) {
            return Outcome.Refused(
                "cannot be built: the half the tooth goes into has no material at the seam under this tap.",
            )
        }
        // Which of the plane's two axes is the WALL's thickness: the shorter run
        // of the material through the tap. The tooth stands along the thickness
        // and the joint is measured across it, so the frame is turned to put the
        // thickness on the rise axis - the split hands over two axes with no
        // promise about which is which, and a lip built along the length of a wall
        // would measure 40 mm of "wall" and refuse everything.
        val acrossRise = runInRise(rawLipCap, 0f, 0f)
        val acrossSide = runInSide(rawLipCap, 0f, 0f)
        val swap = acrossRise != null && acrossSide != null &&
            acrossRise.endInclusive - acrossRise.start > acrossSide.endInclusive - acrossSide.start
        val side = if (swap) rawRise else rawSide
        val risePlane = if (swap) rawSide else rawRise
        val lipCap = if (swap) {
            SnapFit.capTriangles(lipHalf, direction, side, risePlane, face, lipOrigin)
        } else {
            rawLipCap
        }
        val mateCap = if (swap) {
            SnapFit.capTriangles(mateHalf, direction, side, risePlane, face = mateFace, origin = mateOrigin)
        } else {
            rawMateCap
        }
        val lipBand = nearestRun(lipCap, 0f)
            ?: return Outcome.Refused(
                "cannot be built: there is no material of the tooth's half within " +
                    millimetres(BAND_SCAN_LIMIT_MM) + " mm of the tap on the seam.",
            )
        val mateBand = nearestRun(mateCap, 0f)

        // Which way is INWARD: the tooth and the ledge both stand from the wall's
        // inner face, into the material the mate has (or the void the part has).
        // The mate is the evidence - it is the half that has to be solid there -
        // and where the two halves carry the same band (a wall cut in two, both
        // hollow inboard) the lip half's own bulk decides, which puts the joint
        // inside the part rather than on its outside.
        val inward = chooseInward(lipHalf, lipBand, mateBand, risePlane, anchorMm)
        val wallFaceAt = if (inward > 0f) lipBand.endInclusive else lipBand.start
        val wallThickness = lipBand.endInclusive - lipBand.start
        if (wallThickness < MIN_WALL_MM) {
            return Outcome.Refused(
                "cannot be built: the material at this point is only " + millimetres(wallThickness) +
                    " mm through, and a lip needs a wall to bend (at least " + millimetres(MIN_WALL_MM) +
                    " mm). Move the joint along the seam.",
            )
        }
        if (wallThickness > MAX_WALL_MM) {
            return Outcome.Refused(
                "cannot be built: the material at this point is " + millimetres(wallThickness) +
                    " mm of solid, not a wall - the lip's spring is the wall it stands on, and this much " +
                    "material does not bend. Use the cantilever here, or tap nearer the part's own wall.",
            )
        }
        // The frame the features are laid out in: the same two in-plane axes, with
        // the rise origin moved onto the wall's inner face and the rise sense
        // turned inward, so the wall's material is at rise < 0 in every number
        // below and the wall's face is rise = 0.
        val riseAxis = risePlane * inward
        val frameOrigin = lipOrigin + risePlane * wallFaceAt
        val mateFrameOrigin = mateOrigin + risePlane * wallFaceAt

        // Both halves' material at the seam, re-read in the frame the features are
        // built in: the wall on its own face at rise < 0, and the mate's face
        // wherever its material begins outboard of the wall's.
        val wallCap = SnapFit.capTriangles(lipHalf, direction, side, riseAxis, face, frameOrigin)
        val mateFrameCap = SnapFit.capTriangles(mateHalf, direction, side, riseAxis, face = mateFace, origin = mateFrameOrigin)
        val wallSeed = -wallThickness * 0.5f
        if (runAt(wallCap, 0f, wallSeed) == null) {
            return Outcome.Refused(
                "cannot be built: the wall measured at the tap is not there when the joint's own frame " +
                    "is laid over it; tap the middle of the wall.",
            )
        }

        // How far the wall runs along the axis behind the seam before it joins
        // other material: its free length, and the length the strain is computed
        // over. A wall that joins something within its own thickness is a stub,
        // not a spring.
        val freeLength = freeLength(lipHalf, direction, side, riseAxis, face, frameOrigin, wallThickness)
        if (freeLength < MIN_FREE_LENGTH_MM || freeLength < wallThickness) {
            return Outcome.Refused(
                "cannot be built: the wall only runs " + millimetres(freeLength) +
                    " mm back from the seam before it joins other material, and a " +
                    millimetres(wallThickness) + " mm wall that short cannot bend. Move the joint " +
                    "away from the corner or the boss it runs into.",
            )
        }

        // The clearance, the tooth and the hold, in model millimetres.
        val clearance = max(parameters.lipClearanceMm * scale, SnapFit.MIN_CLEARANCE_MM)
        val toothHeight = parameters.lipToothHeightOverrideMm
            ?: parameters.lipToothHeightMm * scale
        val engagementAsked = parameters.lipEngagementOverrideMm
            ?: parameters.lipEngagementMm * scale
        if (toothHeight <= 0f || engagementAsked <= 0f) {
            return Outcome.Refused("cannot be built: the tooth's height and the engagement must be positive.")
        }
        // The tooth's tip has to clear the ledge's inner face, so the hold can
        // never be deeper than the tooth is tall less that clearance.
        val engagement = min(engagementAsked, toothHeight - clearance)
        if (engagement < MIN_ENGAGEMENT_MM) {
            return Outcome.Refused(
                "cannot be built: a " + millimetres(toothHeight) + " mm tooth against a " +
                    millimetres(clearance) + " mm clearance leaves " + millimetres(engagement) +
                    " mm of hold, and a lip catches nothing below " + millimetres(MIN_ENGAGEMENT_MM) +
                    " mm. Raise the tooth's height, or lower the clearance.",
            )
        }
        val square = parameters.rampAngleDeg >= SnapFit.SQUARE_LEAD_IN_DEG
        val rampAngle = if (square) 90f else parameters.rampAngleDeg
        // The ramp's run along the axis: a square face takes the room a 45-degree
        // ramp would, exactly as the cantilever's own square lead-in does - the
        // shape changes, not the space it needs.
        val run = if (square) toothHeight else toothHeight / tan(rampAngle * DEGREES_TO_RADIANS)
        val lipRun = if (square) engagement else engagement / tan(rampAngle * DEGREES_TO_RADIANS)
        val root = min(wallThickness / 3f, ROOT_MM)

        // The strain a full deflection puts on the wall: eps = 3 W d / (2 L^2) for
        // a rectangular wall of thickness W, free length L, moved by d - the same
        // closed form the cantilever's readout uses, with the wall's own numbers.
        // What the wall has to move is the tooth's tip travel: its height less the
        // clearance the ledge keeps off the wall's face. At the default tooth
        // height (the engagement plus two clearances) that travel is exactly the
        // engagement plus the clearance, which is the rule of thumb for the joint;
        // a taller tooth travels further and is measured as it is.
        val deflection = max(0f, toothHeight - clearance)
        val strainPercent = 1.5f * wallThickness * deflection / (freeLength * freeLength) * 100f
        if (strainPercent > MATERIAL_STRAIN_PERCENT) {
            return Outcome.Refused(
                "cannot be built: a " + millimetres(wallThickness) + " mm wall bent " +
                    millimetres(deflection) + " mm over a " + millimetres(freeLength) +
                    " mm free length reaches " + percent(strainPercent) + " at its root, and PLA's own " +
                    "figure is " + percent(MATERIAL_STRAIN_PERCENT) + ". Lower the engagement or the tooth's " +
                    "height, or move the joint to a longer wall.",
            )
        }

        // The mate's own face, in the joint's frame: where its material begins
        // inboard of the wall, and how much of it there is. The ledge has to stand
        // on this, and the tooth has to pass outboard of it.
        val mateRun = nearestRun(mateFrameCap, 0f, from = 0f)
        val mateStart = mateRun?.start
        val mateEnd = mateRun?.endInclusive
        println("MATEDBG run=$mateRun inward=$inward wallFaceAt=$wallFaceAt capSize=" + mateFrameCap.size)
        if (mateStart == null || mateEnd == null || mateEnd <= toothHeight + clearance) {
            return Outcome.Refused(
                "cannot be built: the other half is hollow just inboard of the wall here" +
                    (if (mateStart != null && mateEnd != null) {
                        " (its material ends " + millimetres(mateEnd) + " mm in)"
                    } else {
                        ""
                    }) +
                    ", and the lip's ledge has nothing solid to stand on. Use the cantilever here, or tap " +
                    "where the other half is solid inboard of the wall.",
            )
        }
        // The ledge reaches from its inner face out to the material it stands on:
        // the plug is thin enough to print and is rooted that far into the mate.
        val plugOuter = max(toothHeight + clearance + PLUG_THICKNESS_MM, mateStart + ROOT_MM)
        if (plugOuter > toothHeight + clearance + MAX_SHANK_MM || plugOuter > mateEnd) {
            return Outcome.Refused(
                "cannot be built: the other half's material starts " + millimetres(mateStart) +
                    " mm inboard of the wall's face, and the lip's ledge reaches only " +
                    millimetres(toothHeight + clearance + MAX_SHANK_MM) + " mm. Tap where the other half " +
                    "comes closer to the wall.",
            )
        }

        // The tooth's width along the seam, and whether the wall is long enough
        // there to carry it: a tooth whose width hangs off the end of the wall is
        // a tooth half in the air, and a wall that turns a corner within its own
        // reach cannot bend at all.
        val run0 = sideRun(wallCap, wallSeed)
        val cornerAt = cornerDistance(wallCap, run0, wallThickness)
        val askedWidth = parameters.beamWidthMm * scale
        val halfWidthWanted = askedWidth * 0.5f
        val wallRoom = max(0f, min(-run0.start, run0.endInclusive) - SIDE_MARGIN_MM - clearance)
        val cornerRoom = max(0f, cornerAt - SIDE_MARGIN_MM - clearance)
        val halfWidth = min(halfWidthWanted, min(wallRoom, cornerRoom))
        // A tooth at a corner is the one failure the user cannot see on the screen:
        // it is built, it is closed, and the wall it stands on does not move. The
        // corner is measured, and a tooth that would reach into it is refused by
        // name rather than squeezed into a stub.
        if (cornerRoom < halfWidthWanted && cornerRoom <= wallRoom) {
            return Outcome.Refused(
                "cannot be built: another wall joins the seam " + millimetres(cornerAt) +
                    " mm along it from this point, and the tooth needs " + millimetres(askedWidth) +
                    " mm clear of it - a tooth at a corner has nowhere to move, and the wall there " +
                    "cannot bend. Move the joint away from the corner.",
            )
        }
        if (halfWidth < MIN_TOOTH_HALF_MM) {
            return Outcome.Refused(
                "cannot be built: the wall is only " + millimetres(run0.endInclusive - run0.start) +
                    " mm long along the seam here, and the tooth needs " +
                    millimetres(2f * MIN_TOOTH_HALF_MM) + " mm of it. Move the joint to the middle of " +
                    "the wall.",
            )
        }
        val width = halfWidth * 2f
        val clamps = ArrayList<SnapFitClamp>()
        if (halfWidth < halfWidthWanted - CLAMP_EPSILON_MM) {
            clamps += SnapFitClamp(
                label = "tooth width",
                askedMm = askedWidth,
                actualMm = width,
                reason = "the wall runs only " + millimetres(run0.endInclusive - run0.start) +
                    " mm along the seam from this point, so the tooth is " + millimetres(width) +
                    " mm wide rather than " + millimetres(askedWidth) + " mm",
            )
        }
        if (engagementAsked > engagement + CLAMP_EPSILON_MM) {
            clamps += SnapFitClamp(
                label = "engagement",
                askedMm = engagementAsked,
                actualMm = engagement,
                reason = "a " + millimetres(toothHeight) + " mm tooth against a " +
                    millimetres(clearance) + " mm clearance can hold " + millimetres(engagement) +
                    " mm at most; the engagement is clamped to it",
            )
        }

        // ------------------------------------------------------------ the tooth
        // In the (rise, axis) plane, in the lip half's own frame: the hold face at
        // z = -run (the tooth's base reaches exactly the mating plane, so nothing
        // of the lip crosses it), the ramp at the parameter's angle up to the tip,
        // and a root tab a little way into the wall so the union has a volume to
        // fuse rather than two shells meeting on a plane.
        val tip = toothHeight
        val base = -run
        val toothProfile = if (square) {
            listOf(
                Vec3(-halfWidth, -root, base),
                Vec3(-halfWidth, tip, base),
                Vec3(-halfWidth, tip, base + run),
                Vec3(-halfWidth, -root, base + run),
            )
        } else {
            listOf(
                Vec3(-halfWidth, -root, base),
                Vec3(-halfWidth, tip, base),
                Vec3(-halfWidth, 0f, base + run),
                Vec3(-halfWidth, -root, base + run),
            )
        }
        val lipFrame = SnapFitFrame(frameOrigin, direction, side, riseAxis, frameOrigin.dot(direction))
        val mateFrame = SnapFitFrame(mateFrameOrigin, direction, side, riseAxis, mateFrameOrigin.dot(direction))
        val tooth = MeshSolidBuilder(TOOTH_NAME)
        SnapFit.addPrism(tooth, lipFrame, toothProfile, Vec3(1f, 0f, 0f), width)

        // ----------------------------------------------------------- the recess
        // Open at the mating plane, outboard bound on the wall's face (the wall is
        // never touched), deep enough for the tooth and the ledge and no deeper: it
        // is the room the mate's material comes into and the reason the wall is a
        // spring rather than a bump on a block.
        // The recess is open at the seam and bound on the wall's face, with a
        // relief no deeper than a quarter of the wall: the cut has to keep off the
        // wall's own surface plane (a cut exactly on it is the coplanar contact
        // booleans are worst at) without thinning the spring it leaves behind.
        val recessOuter = -min(clearance, wallThickness * 0.25f)
        val recessInner = plugOuter + clearance
        val recessDepth = run + clearance + lipRun + clearance
        val recessWidth = width + 2f * clearance
        val recess = MeshSolidBuilder(RECESS_NAME)
        SnapFit.boxLocal(
            recess,
            lipFrame,
            SnapFit.LocalRect(-recessWidth * 0.5f, recessWidth * 0.5f, recessOuter, recessInner),
            -recessDepth,
            0f,
        )

        // ------------------------------------------------------------ the ledge
        // The mate's hook, in the mate half's own frame: the bearing face (the
        // flat that meets the tooth's, the engagement wide and no wider - behind it
        // the hook is relieved so the tooth's tip passes), the ramp it shares with
        // the tooth, and a shank that reaches inboard onto the mate's own material.
        val holdAt = base - clearance
        val holdTop = clearance + engagement
        val neckAt = holdAt - min(clearance, lipRun * 0.5f)
        val rampBack = holdAt - lipRun
        val ledgeProfile = listOf(
            Vec3(-halfWidth, clearance, holdAt),
            Vec3(-halfWidth, holdTop, holdAt),
            Vec3(-halfWidth, holdTop, neckAt),
            Vec3(-halfWidth, tip + clearance, neckAt),
            Vec3(-halfWidth, tip + clearance, ROOT_MM),
            Vec3(-halfWidth, plugOuter, ROOT_MM),
            Vec3(-halfWidth, plugOuter, rampBack),
            Vec3(-halfWidth, holdTop, rampBack),
        ).let { points ->
            // A square tooth has a face across the axis, and the ledge's leading
            // face has to be the same shape or the two would meet ramp-to-flat.
            // The room it takes is the ramp's either way.
            if (square) points + Vec3(-halfWidth, clearance, rampBack) else points
        }
        val ledge = MeshSolidBuilder(LEDGE_NAME)
        SnapFit.addPrism(ledge, mateFrame, ledgeProfile, Vec3(1f, 0f, 0f), width)

        val dimensions = SnapFitDimensions(
            style = SnapJointStyle.WALL_LIP,
            scale = scale,
            keySizeMm = 0f,
            keyHeightMm = 0f,
            keyClearanceMm = 0f,
            keyOffsetMm = 0f,
            beamLengthMm = run,
            beamWidthMm = width,
            beamThicknessMm = wallThickness,
            beamRootMm = root,
            lipDepthMm = toothHeight,
            lipRunMm = run,
            lipClearanceMm = clearance,
            matingClearanceMm = 0f,
            rampAngleDeg = rampAngle,
            beamTipMm = run,
            lipBackMm = holdAt,
            stepDepthMm = 0f,
            stepRootMm = 0f,
            stepRimMm = 0f,
            stepClearanceMm = 0f,
            rootStrainPercent = strainPercent,
            barbCount = 1,
            toothPitchMm = 0f,
            teethCatchMm = listOf(base),
            pawlMm = holdAt,
            pawlLandMm = lipRun,
            clickGapMm = listOf(clearance),
            deflectionMm = deflection,
            deflectionRoomMm = 0f,
            mouthChamferMm = 0f,
            mouthChamferAngleDeg = 0f,
            facing = SnapFacing.SAME,
            wallThicknessMm = wallThickness,
            wallFreeLengthMm = freeLength,
            lipEngagementMm = engagement,
            lipToothHeightMm = toothHeight,
            lipDeflectionMm = deflection,
            lipStrainPercent = strainPercent,
            lipWidthMm = width,
            lipHoldAtMm = holdAt,
            lipHoldTopMm = holdTop,
            lipRecessMm = recessDepth,
            lipRecessInnerMm = recessInner,
        )
        return Outcome.Built(
            SnapFitJoint(
                // The tooth goes into the half the user chose, the recess is cut
                // from that same half, and the ledge is unioned into the mate.
                unionSolid = SnapFit.placeOnPlate(tooth.build(), lipHalf, TOOTH_NAME),
                subtractSolid = SnapFit.placeOnPlate(recess.build(), lipHalf, RECESS_NAME),
                registrationSolid = SnapFit.placeOnPlate(ledge.build(), mateHalf, LEDGE_NAME),
                // The lip has no second recess: the cantilever's slot for one is
                // filled with an empty mesh so the pipeline's own shape is kept.
                registrationRecess = MeshSolidBuilder(LEDGE_NAME).build(),
                frame = SnapFit.placeFrame(lipHalf, lipFrame),
                socketFrame = SnapFit.placeFrame(mateHalf, mateFrame),
                dimensions = dimensions,
                style = SnapJointStyle.WALL_LIP,
                // The lip is no rung of the cantilever's ladder: it is always the
                // single feature this file builds, and a rung of FULL here would
                // send it through the pad's contouring, which is the cantilever's.
                rung = SnapFitRung.MINIMAL,
                rungReason = null,
                clamps = clamps,
                clicks = listOf(SnapClick(order = 1, gapMm = clearance, label = SnapFit.SEATED_CLICK)),
            ),
        )
    }

    /**
     * The contiguous band of rise the cap covers around [seed], at side [x].
     *
     * [seed] has to be inside the material - the bands are found by walking out
     * from a point known to be on it - and the walk is bounded, because a cap that
     * never ends is a frame problem rather than a wall.
     */
    private fun runAt(cap: List<SnapFit.CapTriangle>, x: Float, seed: Float): ClosedFloatingPointRange<Float>? {
        val from = if (insideAt(cap, x, seed)) seed else nudgeInto(cap, x, seed) ?: return null
        return runFrom(cap, x, from)
    }

    private fun runFrom(cap: List<SnapFit.CapTriangle>, x: Float, seed: Float): ClosedFloatingPointRange<Float> {
        val seed = seed
        var lo = seed
        var hi = seed
        var guard = 0
        while (guard++ < MAX_BAND_PROBES && capContains(cap, x, lo - BAND_STEP_MM)) lo -= BAND_STEP_MM
        guard = 0
        while (guard++ < MAX_BAND_PROBES && capContains(cap, x, hi + BAND_STEP_MM)) hi += BAND_STEP_MM
        // The walk stops a step short of the material's own edge, and the step is
        // what the thickness would be short by: close the last step by halving it.
        return refine(cap, x, lo, lo - BAND_STEP_MM, rise = true)..
            refine(cap, x, hi, hi + BAND_STEP_MM, rise = true)
    }

    /**
     * Whether there is material at (x, y), allowing for a tap that landed exactly
     * on a face's own edge: a picker reports the point it hit, and the point it
     * hits is very often the seam between two faces, where a strict test says
     * there is no material at all and the measurement then reads the wrong thing
     * entirely.
     */
    private fun insideAt(cap: List<SnapFit.CapTriangle>, x: Float, y: Float): Boolean =
        capContains(cap, x, y) ||
            capContains(cap, x + SEED_NUDGE_MM, y) || capContains(cap, x - SEED_NUDGE_MM, y) ||
            capContains(cap, x, y + SEED_NUDGE_MM) || capContains(cap, x, y - SEED_NUDGE_MM)

    /** A seed a hair inside the material, for a tap that landed on an edge. */
    private fun nudgeInto(cap: List<SnapFit.CapTriangle>, x: Float, y: Float): Float? {
        for (offset in listOf(-SEED_NUDGE_MM, SEED_NUDGE_MM, -2f * SEED_NUDGE_MM, 2f * SEED_NUDGE_MM)) {
            if (capContains(cap, x, y + offset)) return y + offset
        }
        return null
    }

    /**
     * The material's own edge, between a point known to be on it and one known to
     * be off: the last probe step, halved until it is a thousandth of a
     * millimetre. A wall measured a step thin is a strain a tenth too low.
     */
    private fun refine(
        cap: List<SnapFit.CapTriangle>,
        at: Float,
        inside: Float,
        outside: Float,
        rise: Boolean,
    ): Float {
        var on = inside
        var off = outside
        repeat(REFINE_STEPS) {
            val middle = (on + off) * 0.5f
            val hit = if (rise) capContains(cap, at, middle) else capContains(cap, middle, at)
            if (hit) on = middle else off = middle
        }
        return on
    }

    /**
     * The material of one half at the seam, nearest the tapped point: the first
     * band the scan meets walking out from the anchor's own rise, which is the
     * wall the tap is beside and not some other wall across the part.
     *
     * [from] is where the scan starts - the anchor's own rise for the first read,
     * the wall's face once the frame is settled - and the scan runs both ways from
     * it so a tap just off the material still finds it.
     */
    private fun nearestRun(cap: List<SnapFit.CapTriangle>, x: Float, from: Float = 0f): ClosedFloatingPointRange<Float>? {
        val reach = ceil(BAND_SCAN_LIMIT_MM / BAND_STEP_MM).toInt()
        for (step in 0..reach) {
            val at = step * BAND_STEP_MM
            if (step == 0 || at <= from + BAND_SCAN_LIMIT_MM) {
                runAt(cap, x, from + at)?.let { return it }
            }
            if (at > 0f && at <= BAND_SCAN_LIMIT_MM - from) {
                runAt(cap, x, from - at)?.let { return it }
            }
        }
        return null
    }

    private fun capContains(cap: List<SnapFit.CapTriangle>, x: Float, y: Float): Boolean =
        cap.any { it.contains(x, y) }

    /** The contiguous run of cap material along the rise axis, at side [x]. */
    private fun runInRise(cap: List<SnapFit.CapTriangle>, x: Float, from: Float): ClosedFloatingPointRange<Float>? =
        runAt(cap, x, from)

    /** The contiguous run of cap material along the side axis, at rise [y]. */
    private fun runInSide(cap: List<SnapFit.CapTriangle>, y: Float, from: Float): ClosedFloatingPointRange<Float>? {
        if (!insideAt(cap, from, y)) return null
        var lo = from
        var hi = from
        var guard = 0
        while (guard++ < MAX_BAND_PROBES && capContains(cap, lo - BAND_STEP_MM, y)) lo -= BAND_STEP_MM
        guard = 0
        while (guard++ < MAX_BAND_PROBES && capContains(cap, hi + BAND_STEP_MM, y)) hi += BAND_STEP_MM
        return refine(cap, y, lo, lo - BAND_STEP_MM, rise = false)..
            refine(cap, y, hi, hi + BAND_STEP_MM, rise = false)
    }

    /**
     * The cap's run along the seam at the anchor's own rise: how much wall there
     * is to either side of the tap.
     */
    private fun sideRun(cap: List<SnapFit.CapTriangle>, riseAt: Float): ClosedFloatingPointRange<Float> {
        var lo = 0f
        var hi = 0f
        var guard = 0
        while (guard++ < MAX_SIDE_PROBES && capContains(cap, lo - SIDE_STEP_MM, riseAt)) lo -= SIDE_STEP_MM
        guard = 0
        while (guard++ < MAX_SIDE_PROBES && capContains(cap, hi + SIDE_STEP_MM, riseAt)) hi += SIDE_STEP_MM
        return refine(cap, riseAt, lo, lo - SIDE_STEP_MM, rise = false)..
            refine(cap, riseAt, hi, hi + SIDE_STEP_MM, rise = false)
    }

    /**
     * How far along the seam the wall runs before another wall joins it: a corner,
     * where the cap's band at that point is thicker than the wall the tooth stands
     * on. Read from the tap outwards in both directions, and [MAX_SIDE_PROBES]
     * caps the walk where the wall simply keeps going.
     */
    private fun cornerDistance(
        cap: List<SnapFit.CapTriangle>,
        run: ClosedFloatingPointRange<Float>,
        wallThickness: Float,
    ): Float {
        val reach = max(run.endInclusive, -run.start)
        var guard = 0
        var at = SIDE_STEP_MM
        while (guard++ < MAX_SIDE_PROBES && at <= reach) {
            if (bandThicker(cap, at, wallThickness)) return at
            if (bandThicker(cap, -at, wallThickness)) return at
            at += SIDE_STEP_MM
        }
        return reach
    }

    /** Whether the cap's band at [x] is more than the wall the tooth stands on. */
    private fun bandThicker(cap: List<SnapFit.CapTriangle>, x: Float, wallThickness: Float): Boolean {
        for (y in 0 until BAND_PROBES) {
            val at = -BAND_PROBES * BAND_STEP_MM * 0.5f + y * BAND_STEP_MM
            if (!capContains(cap, x, at)) continue
            val run = runAt(cap, x, at) ?: continue
            if (run.endInclusive - run.start > wallThickness + max(CORNER_TOLERANCE_MM, wallThickness * 0.5f)) {
                return true
            }
        }
        return false
    }

    /**
     * How far the wall runs along the axis behind the seam before it joins other
     * material: the free length the strain is computed over.
     *
     * A slice a little behind the mating face is walked back one step at a time,
     * and the material's rise band at each slice is read from the triangles that
     * cross it, within a window around the anchor's own side. The wall ends where
     * that band is suddenly more than the wall - it has joined a boss, a base or
     * another wall - or where the material is gone, which is the wall's own free
     * end and as good a spring as it gets.
     */
    private fun freeLength(
        half: SnapFitHalf,
        direction: Vec3,
        side: Vec3,
        rise: Vec3,
        face: Float,
        origin: Vec3,
        /** The wall's thickness, measured at the seam: what "still the wall" means here. */
        wallThickness: Float,
    ): Float {
        val probes = listOfNotNull(half.probe(direction), half.probe(side), half.probe(rise))
        if (probes.size != 3) return 0f
        val along = probes[0]
        val across = probes[1]
        val through = probes[2]
        val originSide = origin.dot(side)
        val originRise = origin.dot(rise)
        val vertices = half.mesh.interleavedVertices
        val count = half.mesh.triangleCount
        val sideAt = FloatArray(count * 3)
        val riseAt = FloatArray(count * 3)
        val alongAt = FloatArray(count * 3)
        for (vertex in 0 until count * 3) {
            val base = vertex * MeshSolidBuilder.FLOATS_PER_VERTEX
            val x = vertices[base]
            val y = vertices[base + 1]
            val z = vertices[base + 2]
            sideAt[vertex] = across(x, y, z) - originSide
            riseAt[vertex] = through(x, y, z) - originRise
            alongAt[vertex] = along(x, y, z)
        }
        // The walk starts just behind the seam, where the wall's own side faces
        // cross the slice: the plane of the mating face itself is a face, and a
        // slice through it is the cap the thickness was measured from already.
        val ceiling = wallThickness + max(CORNER_TOLERANCE_MM, wallThickness * 0.5f)
        fun stillWall(at: Float): Boolean {
            val band = sliceBand(sideAt, riseAt, alongAt, count, face, -at, seed = -wallThickness * 0.5f)
                ?: return false
            return band.endInclusive - band.start <= ceiling
        }
        var walked = 0f
        var step = 0
        while (step++ < MAX_FREE_PROBES && walked < MAX_FREE_LENGTH_MM) {
            val previous = walked
            walked += FREE_STEP_MM
            if (stillWall(walked)) continue
            // The wall's end is between the two probes - its own free end, or the
            // material it joins - and it is closed by halving the gap, because a
            // free length a step too long is a strain read a third too low.
            var inside = previous
            var outside = walked
            repeat(REFINE_STEPS) {
                val middle = (inside + outside) * 0.5f
                if (stillWall(middle)) inside = middle else outside = middle
            }
            return inside
        }
        return walked
    }

    /**
     * The rise band of the material crossing the plane [behind] millimetres along
     * the axis, within a window either side of the anchor's own side: the wall's
     * thickness at that slice.
     *
     * Each triangle the plane cuts becomes a segment, and the segment is read at
     * the window rather than at its own ends: a wall's faces are big triangles
     * whose crossing points sit at the part's corners, so a filter that only kept
     * the points inside the window would find nothing at all and call every wall
     * a stub.
     */
    private fun sliceBand(
        sideAt: FloatArray,
        riseAt: FloatArray,
        alongAt: FloatArray,
        triangles: Int,
        face: Float,
        behind: Float,
        /** A rise the wall is known to occupy: the run through it is the one wanted. */
        seed: Float,
    ): ClosedFloatingPointRange<Float>? {
        val plane = face + behind
        val crossings = ArrayList<Float>()
        fun include(y: Float) {
            crossings += y
        }
        for (triangle in 0 until triangles) {
            val a = triangle * 3
            var firstSide = 0f
            var firstRise = 0f
            var secondSide = 0f
            var secondRise = 0f
            var crossings = 0
            for (edge in 0 until 3) {
                val first = a + edge
                val second = a + (edge + 1) % 3
                val one = alongAt[first] - plane
                val two = alongAt[second] - plane
                if ((one > 0f) == (two > 0f)) continue
                val span = one - two
                if (abs(span) < SLICE_EPSILON_MM) continue
                val t = one / span
                val x = sideAt[first] + (sideAt[second] - sideAt[first]) * t
                val y = riseAt[first] + (riseAt[second] - riseAt[first]) * t
                if (crossings == 0) {
                    firstSide = x
                    firstRise = y
                    crossings = 1
                } else if (crossings == 1) {
                    secondSide = x
                    secondRise = y
                    crossings = 2
                }
            }
            if (crossings == 0) continue
            if (crossings == 1) {
                if (abs(firstSide) <= SLICE_WINDOW_MM) include(firstRise)
                continue
            }
            // The piece of the segment the window covers, read at its own ends.
            val lowSide = min(firstSide, secondSide)
            val highSide = max(firstSide, secondSide)
            val fromSide = max(lowSide, -SLICE_WINDOW_MM)
            val toSide = min(highSide, SLICE_WINDOW_MM)
            if (fromSide > toSide) continue
            if (abs(highSide - lowSide) < SLICE_EPSILON_MM) {
                include(firstRise)
                include(secondRise)
                continue
            }
            val slope = (secondRise - firstRise) / (secondSide - firstSide)
            include(firstRise + (fromSide - firstSide) * slope)
            include(firstRise + (toSide - firstSide) * slope)
        }
        if (crossings.isEmpty()) return null
        // The wall is the material the seed sits IN, not everything the slice meets:
        // a tray's two opposite walls are both crossed at the anchor's side, and the
        // span from one to the other is the cavity, not the wall. The crossings
        // either side of the seed bound the piece it is inside - counted once each,
        // because a face is two triangles and the slice crosses both of them.
        crossings.sort()
        val edges = ArrayList<Float>(crossings.size)
        for (y in crossings) {
            if (edges.isEmpty() || abs(y - edges.last()) > SLICE_EDGE_MM) edges += y
        }
        var below = 0
        for (y in edges) if (y < seed) below++
        if (below % 2 == 0) return null
        val low = edges.last { it < seed }
        val high = edges.firstOrNull { it > seed } ?: return null
        return low..high
    }

    /**
     * Which way is inward at this tap, as a sign on the rise axis: the direction
     * the mate's material lies in beyond the lip half's own band, and where the
     * two bands are the same the direction of the lip half's own bulk.
     */
    private fun chooseInward(
        lipHalf: SnapFitHalf,
        lipBand: ClosedFloatingPointRange<Float>,
        mateBand: ClosedFloatingPointRange<Float>?,
        rise: Vec3,
        anchorMm: Vec3,
    ): Float {
        if (mateBand != null) {
            val outward = lipBand.start - mateBand.start
            val inward = mateBand.endInclusive - lipBand.endInclusive
            if (abs(inward - outward) > BAND_CHOICE_EPSILON_MM) return if (inward > outward) 1f else -1f
        }
        // The halves carry the same band: a wall cut in two. The joint belongs
        // inside the part, so the tooth stands towards the bulk of the half it is
        // rooted in - the far end of the half's own extent along the rise axis.
        val span = SnapFit.span(lipHalf, rise) ?: return 1f
        val anchorAt = anchorMm.dot(rise)
        return if (span.endInclusive - anchorAt >= anchorAt - span.start) 1f else -1f
    }

    /** Any unit vector perpendicular to [direction]; the frame's side axis. */
    private fun perpendicularTo(direction: Vec3): Vec3 {
        val reference = when {
            abs(direction.x) <= abs(direction.y) && abs(direction.x) <= abs(direction.z) -> Vec3(1f, 0f, 0f)
            abs(direction.y) <= abs(direction.z) -> Vec3(0f, 1f, 0f)
            else -> Vec3(0f, 0f, 1f)
        }
        return reference.cross(direction).normalized() ?: Vec3(1f, 0f, 0f)
    }

    /** One decimal place, locale-independent: these sentences land in the panel. */
    private fun millimetres(value: Float): String =
        String.format(java.util.Locale.ROOT, "%.1f", value)

    /** A percentage with the sign the panel prints. */
    private fun percent(value: Float): String =
        String.format(java.util.Locale.ROOT, "%.1f%%", value)

    private const val TOOTH_NAME = "wall lip tooth"
    private const val RECESS_NAME = "wall lip recess"
    private const val LEDGE_NAME = "wall lip ledge"
    private const val PLANE_TOLERANCE_MM = 1e-3f
    private const val CLAMP_EPSILON_MM = 1e-3f

    /**
     * The material's own figure, in per cent: PLA is the app's reference filament
     * and 2% is where its snap fits are designed to sit. A setting that asks the
     * wall for more than this is refused by name rather than printed and hoped
     * for.
     */
    const val MATERIAL_STRAIN_PERCENT = 2f

    /** Below this a lip catches nothing worth the name: it is refused, with the numbers. */
    const val MIN_ENGAGEMENT_MM = 0.2f

    /** A wall thinner than this is not a wall; a thicker one is a block. */
    private const val MIN_WALL_MM = 0.4f
    private const val MAX_WALL_MM = 4f

    /** A wall that joins other material within this much of the seam cannot bend. */
    private const val MIN_FREE_LENGTH_MM = 2f

    /** How thick the shank that carries the ledge onto the mate's material is. */
    private const val PLUG_THICKNESS_MM = 1.2f
    private const val ROOT_MM = 0.6f

    /** How far inboard the ledge will reach for the mate's material, and no further. */
    private const val MAX_SHANK_MM = 3f

    /** Room kept between the tooth and the end of the wall it stands on. */
    private const val SIDE_MARGIN_MM = 0.4f
    private const val MIN_TOOTH_HALF_MM = 0.4f

    // The probes the measurements walk with. Coarse on purpose: a wall's own
    // dimensions do not change under a tenth of a millimetre, and this runs on
    // every preview.
    private const val BAND_STEP_MM = 0.1f
    private const val BAND_PROBES = 40
    private const val MAX_BAND_PROBES = 400
    private const val BAND_SCAN_LIMIT_MM = 30f
    private const val REFINE_STEPS = 7

    /** How far a seed is nudged off a face's own edge to find the material behind it. */
    private const val SEED_NUDGE_MM = 0.05f
    private const val SIDE_STEP_MM = 0.25f
    private const val MAX_SIDE_PROBES = 240
    private const val CORNER_TOLERANCE_MM = 0.4f
    private const val BAND_CHOICE_EPSILON_MM = 0.05f
    private const val FREE_STEP_MM = 0.5f
    private const val MAX_FREE_PROBES = 240
    private const val MAX_FREE_LENGTH_MM = 60f
    private const val SLICE_WINDOW_MM = 0.6f
    private const val SLICE_EPSILON_MM = 1e-4f

    /** Two crossings this close are one edge of the material, not two. */
    private const val SLICE_EDGE_MM = 5e-3f

    private val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()
}
