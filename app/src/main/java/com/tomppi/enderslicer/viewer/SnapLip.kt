package com.tomppi.enderslicer.viewer

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
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
 * The wall lip: one tooth RING around the whole seam, one ledge ring on the
 * mate, and the wall along that edge as the spring.
 *
 * The design, per pair, one feature on each half (the agreed drawing):
 *
 *  - The seam's own outline - the closed contour the split gives - is the shape.
 *    The half whose wall is free carries a TOOTH that stands inward from the
 *    wall's inner face and runs the whole way round it: its trailing face is
 *    FLAT and perpendicular to the assembly axis, and its leading face is a ramp
 *    at the parameter's angle (45 degrees by default, 90 for a square face). It
 *    is rooted a little way into the wall, so the union is a volume and not two
 *    shells meeting on a plane.
 *  - On the mate - the half the drawing calls embossed, and which has to be
 *    SOLID a little inboard of the wall - the matching LEDGE runs the same ring:
 *    the same angle on its leading face, the same flat trailing face opposite
 *    the tooth's.
 *
 * Assembled, the two flat faces are a clearance apart along the axis, and that
 * pair is the whole hold: pulling the halves apart brings them together over the
 * engagement the user set, and every other face of the joint is a ramp that lets
 * the parts close. The wall bends outward as the ledge rides the ramp and springs
 * back behind it. There is no beam and there is no single tooth: the hook is the
 * edge, and the wall that moves is the whole edge with it.
 *
 * The ring is SWEPT round the contour with every corner mitred, so it closes on
 * itself with no gap and no staircase, and it lives in a STEP the two halves give
 * it: the mate's edge is embossed into the lip half's recess, and that band is
 * sized from the ring - the tooth's height, its ramp's run and the clearances -
 * rather than left at the shallow key the cantilever's pad uses.
 *
 * The booleans are NOT the cantilever's: the tooth is unioned into the lip half,
 * the recess is subtracted from the SAME half, and the ledge is unioned into the
 * MATE - two unions on two different halves, which the one-union-per-half
 * pipeline cannot express. [SnapFitJoint] still carries the four solids, and
 * placeEveryJoint's lip branch is what knows where each of them goes.
 *
 * The wall is read round the whole ring: its thickness, its free length and the
 * strain, sampled all the way round, with the shortest free length and the worst
 * strain reported and their place on the contour named. A ring that crosses a
 * corner or a hollow mate is BUILT AS ASKED and warned about, because the user
 * asked for the whole edge and the numbers are how he judges it.
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
 * The wall lip: one tooth RING around the whole seam, one ledge ring on the
 * mate, and the wall along that edge as the spring.
 *
 * The design, per pair, one feature on each half (docs, and the drawing the user
 * agreed to):
 *
 * - The seam's own outline - the closed contour the split gives - is the shape.
 *   The half whose wall is free carries a tooth that stands inward from the
 *   wall's inner face and runs the whole way round it, with a 45 degree ramp on
 *   its leading face and a flat face behind; the mate carries a matching ledge,
 *   ring for ring, and assembled the two flat faces meet and that pair is the
 *   whole hold. The wall bends outward as the ledge rides the ramp and springs
 *   back behind it. The ring closes at the corners: the profile is swept round
 *   the contour with mitred corners, not placed piece by piece, so there is no
 *   gap and no staircase anywhere on it.
 *
 * - The ring lives in a step the two halves give it. The mate's edge is embossed
 *   into the lip half's recess, and that band has to be deep enough for the
 *   tooth, its ramp and the flat face behind it: the step is sized FROM the ring
 *   (the tooth's height plus the ramp's run plus the clearance the pair runs on),
 *   never left at the shallow key the cantilever's pad uses, and the recess is
 *   that much again plus the clearance.
 *
 * - The sizes are the style's, not a tap's: ENGAGEMENT is how far the tooth and
 *   its ledge overlap - the hold, and the same number all the way round - and
 *   TOOTH HEIGHT is the tooth's own height, which is also the ceiling on the
 *   engagement because the tip has to clear the ledge's inner face.
 *
 * - The wall is read around the whole ring rather than at one place: its
 *   thickness, its free length behind the seam, and the strain a full deflection
 *   puts on it, at every sample. The reading reports the shortest free length and
 *   the worst strain and says where on the ring they are. A ring that passes
 *   places where the wall cannot move - a corner, a boss, a hollow mate - is
 *   built as asked and warned about by name and number, because the user asked
 *   for the whole edge and the numbers are how he judges it. Only a ring the
 *   material cannot carry at all is refused.
 */
    /**
     * The tooth ring, the recess it lives in and the mate's ledge ring, all
     * swept around the seam's own outline. [anchorMm] only has to be a point on
     * the seam: the ring is the whole contour, so where the tap was does not
     * shape it.
     */
    fun buildRing(
        axis: Vec3,
        anchorMm: Vec3,
        scale: Float,
        lipHalf: SnapFitHalf,
        mateHalf: SnapFitHalf,
        parameters: SnapFitParameters = SnapFitParameters(),
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
        if (face > mateHalf.faceMm + PLANE_TOLERANCE_MM) {
            return Outcome.Refused(
                "cannot be built: the two halves overlap along the assembly axis, so there is no seam here.",
            )
        }
        val clearance = max(parameters.lipClearanceMm * scale, SnapFit.MIN_CLEARANCE_MM)
        val toothHeight = parameters.lipToothHeightOverrideMm ?: parameters.lipToothHeightMm * scale
        val engagementAsked = parameters.lipEngagementOverrideMm ?: parameters.lipEngagementMm * scale
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
        val run = if (square) toothHeight else toothHeight / tan(rampAngle * DEGREES_TO_RADIANS)
        val lipRun = if (square) engagement else engagement / tan(rampAngle * DEGREES_TO_RADIANS)
        // The step the ring needs and the recess that holds it: the tooth's height
        // and the ramp's run, the clearnces the pair runs on, and the recess that
        // much again so the mate's emboss goes in with room on every face.
        val stepDepth = toothHeight + run + 2f * clearance
        val recessDepth = stepDepth + clearance

        // ------------------------------------------------------------ the seam
        // The plane's own two axes, and the outline the two halves share there:
        // the contour the split leaves, walked from the lip half's own cap.
        val side = perpendicularTo(direction)
        val rise = direction.cross(side)
        val alongAnchor = anchorMm.dot(direction)
        val origin = anchorMm - direction * (alongAnchor - face)
        val cap = SnapFit.capTriangles(lipHalf, direction, side, rise, face, origin)
        if (cap.isEmpty()) {
            return Outcome.Refused(
                "cannot be built: the lip half has no material at the seam, so there is no edge to " +
                    "run the ring around.",
            )
        }
        val contour = SnapFit.capOutlines(cap)
            .map { counterClockwise(it) }
            .filter { it.size >= 3 }
            .maxByOrNull { abs(polygonArea(it)) }
            ?: return Outcome.Refused(
                "cannot be built: the seam's own edge could not be read off the lip half.",
            )
        if (contour.size < 3) {
            return Outcome.Refused("cannot be built: the seam's edge is a line, not a ring.")
        }
        val perimeter = perimeterOf(contour)
        if (perimeter < 2f * PI_ISH || polygonArea(contour) <= 0f) {
            return Outcome.Refused(
                "cannot be built: the seam's edge measures " + millimetres(perimeter) +
                    " mm around, too little to carry a ring.",
            )
        }

        // ------------------------------------------------------- the wall, read
        // The wall's thickness where the ring will stand: the material's own band
        // inward from the edge, at samples spread around the whole contour. The
        // ring's own working thickness is the thinner of that and what a printed
        // wall can be, so a solid block is given a wall to bend rather than
        // refused for having none.
        val samples = ringSamples(contour)
        val bands = samples.map { bandAt(lipHalf, direction, side, rise, origin, it) }
        // The wall along the seam is the material's own band round the ring: the
        // middle one, so a single corner read - where two faces meet and any
        // thickness is true of something - cannot decide it for the whole edge.
        val measured = bands.mapNotNull { it?.lengthMm }.sorted()
        val thinnest = measured.getOrNull(measured.size / 2)
        if (thinnest == null) {
            return Outcome.Refused(
                "cannot be built: the lip half's material could not be read anywhere along the seam.",
            )
        }
        if (thinnest < MIN_WALL_MM) {
            return Outcome.Refused(
                "cannot be built: the wall along the seam is only " + millimetres(thinnest) +
                    " mm through at its thinnest, and a lip needs a wall to bend (at least " +
                    millimetres(MIN_WALL_MM) + " mm). Use the cantilever here.",
            )
        }
        val wall = min(thinnest, RING_WALL_MM)
        val clamps = ArrayList<SnapFitClamp>()
        if (thinnest > RING_WALL_MM + CLAMP_EPSILON_MM) {
            clamps += SnapFitClamp(
                label = "wall thickness",
                askedMm = thinnest,
                actualMm = wall,
                reason = "the part is solid along the seam (" + millimetres(thinnest) +
                    " mm of material), so the ring's own recess leaves a " + millimetres(wall) +
                    " mm wall to bend",
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

        // The wall's own face: the seam's edge inset by the wall's thickness, so
        // the tooth stands on the wall's inner face and the recess is cut inboard
        // of it. Where that inset folds into itself the ring cannot exist.
        val wallFace = insetPolygon(contour, wall)
            ?: return Outcome.Refused(
                "cannot be built: a " + millimetres(wall) + " mm wall's inner face folds through " +
                    "itself somewhere along this edge, so there is no room for the ring. Use a " +
                    "thinner wall, or the cantilever.",
            )

        // The ring's own numbers, in the frame the seam is read in: the tooth's
        // root a little way into the wall, the flat face, the ramp at the
        // parameter's angle, and the recess and ledge that fit round them.
        val root = min(wall / 3f, ROOT_MM)
        val tip = toothHeight
        val base = -run
        val holdAt = base - clearance
        val holdTop = clearance + engagement
        val neckAt = holdAt - min(clearance, lipRun * 0.5f)
        // The ledge's ramp spans the whole run the step was sized for, so the
        // mate's emboss is as deep as the ring needs and no deeper.
        val rampBack = holdAt - run
        val recessOuter = -min(clearance, wall * 0.25f)
        val plugOuter = tip + clearance + PLUG_THICKNESS_MM
        val recessInner = plugOuter + clearance

        // --------------------------------------------------- the ring, measured
        // The free length and the strain around the whole ring: the shortest free
        // length found, the worst strain, and where on the contour they are. A
        // ring that passes a corner or a boss is not refused for it - the user
        // asked for the whole edge - it is warned about by name and number.
        val deflection = max(0f, toothHeight - clearance)
        val reading = measureRing(
            lipHalf = lipHalf,
            mateHalf = mateHalf,
            axis = direction,
            side = side,
            rise = rise,
            origin = origin,
            contour = wallFace,
            wall = wall,
            recessDepth = recessDepth,
            deflection = deflection,
            samples = samples,
            face = face,
            direction = direction,
            mateMustStartBy = tip + clearance + CLAMP_EPSILON_MM,
            mateMustReach = plugOuter,
        )
        if (reading.hollowSamples * 10 >= samples.size * 9) {
            return Outcome.Refused(
                "cannot be built: the other half is hollow just inboard of the wall the whole way " +
                    "round the seam (its material ends " + millimetres(reading.hollowEndMm) +
                    " mm in at its best), and the ring's ledge has nothing solid to stand on. Use the " +
                    "cantilever here.",
            )
        }
        val warnings = ArrayList<String>()
        if (reading.minFreeMm < MIN_FREE_LENGTH_MM || reading.minFreeMm < wall) {
            warnings += "along " + millimetres(reading.minFreeAtMm) + " mm of the seam the wall only " +
                "runs " + millimetres(reading.minFreeMm) + " mm back before it joins other material, " +
                "and a " + millimetres(wall) + " mm wall that short cannot bend: the ring is there and " +
                "that part of it will not move."
        }
        if (reading.maxStrainPercent > MATERIAL_STRAIN_PERCENT) {
            warnings += "the worst place is " + millimetres(reading.maxStrainAtMm) + " mm along the " +
                "seam, where a " + millimetres(wall) + " mm wall bent " + millimetres(reading.deflectionMm) +
                " mm over " + millimetres(reading.minFreeMm) + " mm free reaches " +
                percent(reading.maxStrainPercent) + " - past PLA's own " +
                percent(MATERIAL_STRAIN_PERCENT) + " figure. Lower the engagement or the tooth's height."
        }
        if (reading.cornerAtMm != null) {
            warnings += "another wall joins the seam " + millimetres(reading.cornerDistanceMm) +
                " mm along it from " + millimetres(reading.cornerAtMm) + " mm, and the wall there cannot " +
                "bend: the ring crosses it, and that stretch of the hook will not flex."
        }
        if (reading.hollowAtMm != null) {
            warnings += "the other half is hollow just inboard of the wall around " +
                millimetres(reading.hollowAtMm) + " mm along the seam (its material ends " +
                millimetres(reading.hollowEndMm) + " mm in), so the ledge has no solid to stand on there."
        }

        // ----------------------------------------------------------- the rings
        val toothProfile = if (square) {
            listOf(
                -root to base,
                tip to base,
                tip to base + run,
                -root to base + run,
            )
        } else {
            listOf(
                -root to base,
                tip to base,
                0f to base + run,
                -root to base + run,
            )
        }
        val recessProfile = listOf(
            recessOuter to -recessDepth,
            recessInner to -recessDepth,
            recessInner to RECESS_OVER_MM,
            recessOuter to RECESS_OVER_MM,
        )
        val ledgeProfile = listOf(
            clearance to holdAt,
            holdTop to holdAt,
            holdTop to neckAt,
            tip + clearance to neckAt,
            tip + clearance to ROOT_MM,
            plugOuter to ROOT_MM,
            plugOuter to rampBack,
            holdTop to rampBack,
        ) + if (square) listOf(clearance to rampBack) else emptyList()

        val tooth = ringMesh(TOOTH_NAME, wallFace, direction, side, rise, origin, toothProfile)
            ?: return Outcome.Refused(
                "cannot be built: the tooth ring folds through itself on this edge, so it cannot be " +
                    "printed as one closed hook.",
            )
        val recess = ringMesh(RECESS_NAME, wallFace, direction, side, rise, origin, recessProfile)
            ?: return Outcome.Refused("cannot be built: the recess ring folds through itself on this edge.")
        val ledge = ringMesh(LEDGE_NAME, wallFace, direction, side, rise, origin, ledgeProfile)
            ?: return Outcome.Refused("cannot be built: the ledge ring folds through itself on this edge.")

        val dimensions = SnapFitDimensions(
            style = SnapJointStyle.WALL_LIP,
            scale = scale,
            keySizeMm = 0f,
            keyHeightMm = 0f,
            keyClearanceMm = 0f,
            keyOffsetMm = 0f,
            beamLengthMm = run,
            beamWidthMm = 0f,
            beamThicknessMm = wall,
            beamRootMm = root,
            lipDepthMm = toothHeight,
            lipRunMm = run,
            lipClearanceMm = clearance,
            matingClearanceMm = 0f,
            rampAngleDeg = rampAngle,
            beamTipMm = run,
            lipBackMm = holdAt,
            stepDepthMm = stepDepth,
            stepRootMm = ROOT_MM,
            stepRimMm = clearance,
            stepClearanceMm = clearance,
            rootStrainPercent = reading.maxStrainPercent,
            barbCount = 1,
            toothPitchMm = 0f,
            teethCatchMm = listOf(base),
            pawlMm = holdAt,
            pawlLandMm = lipRun,
            clickGapMm = listOf(clearance),
            deflectionMm = reading.deflectionMm,
            deflectionRoomMm = 0f,
            mouthChamferMm = 0f,
            mouthChamferAngleDeg = 0f,
            facing = SnapFacing.SAME,
            wallThicknessMm = wall,
            wallFreeLengthMm = reading.minFreeMm,
            lipEngagementMm = engagement,
            lipToothHeightMm = toothHeight,
            lipDeflectionMm = reading.deflectionMm,
            lipStrainPercent = reading.maxStrainPercent,
            lipWidthMm = perimeter,
            lipHoldAtMm = holdAt,
            lipHoldTopMm = holdTop,
            lipRecessMm = recessDepth,
            lipRecessInnerMm = recessInner,
            lipStepMm = stepDepth,
            // The hooks run on the wall's face, not on the part's outer edge: the
            // ring's own length is the contour the tooth stands on.
            lipRingMm = perimeterOf(wallFace),
            lipSamples = samples.size,
            lipWorstAtMm = reading.maxStrainAtMm,
        )
        val frame = SnapFitFrame(origin, direction, side, rise, origin.dot(direction))
        return Outcome.Built(
            SnapFitJoint(
                unionSolid = SnapFit.placeOnPlate(tooth, lipHalf, TOOTH_NAME),
                subtractSolid = SnapFit.placeOnPlate(recess, lipHalf, RECESS_NAME),
                registrationSolid = SnapFit.placeOnPlate(ledge, mateHalf, LEDGE_NAME),
                registrationRecess = MeshSolidBuilder(LEDGE_NAME).build(),
                frame = SnapFit.placeFrame(lipHalf, frame),
                socketFrame = SnapFit.placeFrame(mateHalf, frame),
                dimensions = dimensions,
                style = SnapJointStyle.WALL_LIP,
                rung = SnapFitRung.MINIMAL,
                rungReason = null,
                clamps = clamps,
                warnings = warnings,
                clicks = listOf(SnapClick(order = 1, gapMm = clearance, label = SnapFit.SEATED_CLICK)),
            ),
        )
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
        /** How far behind the seam the ring's own recess has already freed the wall. */
        freedDownToMm: Float = 0f,
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
            // Inside the ring's own recess the wall is free by construction - that
            // is what the recess is for - so a solid part's walk is not ended by
            // the block the recess was cut into.
            if (at <= freedDownToMm) return true
            // This walk's origin is on the wall's OUTER surface, so the wall lies
            // at positive rise from it: the seed is half its thickness in.
            val band = sliceBand(sideAt, riseAt, alongAt, count, face, -at, seed = wallThickness * 0.5f)
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

    /** A point in the seam's own plane, in the frame the ring is laid out in. */
    private data class P2(val x: Float, val y: Float) {
        operator fun plus(other: P2) = P2(x + other.x, y + other.y)
        operator fun minus(other: P2) = P2(x - other.x, y - other.y)
        operator fun times(scale: Float) = P2(x * scale, y * scale)
        fun dot(other: P2) = x * other.x + y * other.y
        fun cross(other: P2) = x * other.y - y * other.x
        val length: Float get() = sqrt(x * x + y * y)
        fun unit(): P2? {
            val size = length
            return if (size < 1e-6f) null else P2(x / size, y / size)
        }
    }

    /** One place the ring is read at: where it is on the contour, and its own two directions. */
    private class RingSample(
        /** How far along the contour this sample sits, from the contour's first point. */
        val atMm: Float,
        /** The sample in the plane's own axes. */
        val point: P2,
        /** The contour's direction there. */
        val edge: P2,
        /** The contour's inward normal there: the way the tooth stands. */
        val inward: P2,
        /** Which edge of the contour this sample sits on. */
        val edgeIndex: Int,
    )

    /** The material's own band inward from the seam, at one place on the ring. */
    private class RingBand(val startMm: Float, val lengthMm: Float)

    /** What the ring's own reading found around the whole contour. */
    private class RingReading(
        val minFreeMm: Float,
        val minFreeAtMm: Float,
        val maxStrainPercent: Float,
        val maxStrainAtMm: Float,
        val deflectionMm: Float,
        val cornerAtMm: Float?,
        val cornerDistanceMm: Float,
        val hollowAtMm: Float?,
        val hollowEndMm: Float,
        /** How many of the ring's samples have no mate material to stand the ledge on. */
        val hollowSamples: Int,
    )

    /** The same points, wound counter-clockwise and with repeated corners dropped. */
    private fun counterClockwise(points: List<Pair<Float, Float>>): List<P2> {
        val welded = ArrayList<P2>()
        for ((x, y) in points) {
            val point = P2(x, y)
            if (welded.isEmpty() || (point - welded.last()).length > WELD_MM) welded += point
        }
        if (welded.size >= 2 && (welded.first() - welded.last()).length <= WELD_MM) welded.removeAt(welded.size - 1)
        if (welded.size < 3) return welded
        return if (polygonArea(welded) < 0f) welded.reversed() else welded
    }

    private fun polygonArea(points: List<P2>): Float {
        var twice = 0f
        for (at in points.indices) {
            val here = points[at]
            val next = points[(at + 1) % points.size]
            twice += here.cross(next)
        }
        return twice * 0.5f
    }

    private fun perimeterOf(points: List<P2>): Float {
        var total = 0f
        for (at in points.indices) total += (points[(at + 1) % points.size] - points[at]).length
        return total
    }

    private fun insidePolygon(points: List<P2>, point: P2): Boolean {
        var inside = false
        for (at in points.indices) {
            val a = points[at]
            val b = points[(at + 1) % points.size]
            if ((a.y > point.y) != (b.y > point.y)) {
                val x = a.x + (point.y - a.y) / (b.y - a.y) * (b.x - a.x)
                if (point.x < x) inside = !inside
            }
        }
        return inside
    }

    /**
     * The point a corner's own offset lands on: where the two edges either side of
     * it, each moved [distance] along its inward normal, meet. Both a wall's inner
     * face and every swept profile corner are this, which is what closes the ring
     * at a corner instead of leaving a notch or a staircase.
     */
    private fun offsetVertex(previous: P2, here: P2, next: P2, distance: Float): P2? {
        val inEdge = (here - previous).unit() ?: return null
        val outEdge = (next - here).unit() ?: return null
        val a = here + P2(-inEdge.y, inEdge.x) * distance
        val b = here + P2(-outEdge.y, outEdge.x) * distance
        val denominator = inEdge.cross(outEdge)
        if (abs(denominator) < 1e-5f) return b
        val t = (b - a).cross(outEdge) / denominator
        return a + inEdge * t
    }

    /** The contour moved inward by [distance], or null where it would fold through itself. */
    private fun insetPolygon(contour: List<P2>, distance: Float): List<P2>? {
        val out = ArrayList<P2>(contour.size)
        for (at in contour.indices) {
            val point = offsetVertex(
                contour[(at + contour.size - 1) % contour.size],
                contour[at],
                contour[(at + 1) % contour.size],
                distance,
            ) ?: return null
            out += point
        }
        if (polygonArea(out) <= 0f) return null
        for (point in out) if (!insidePolygon(contour, point)) return null
        return out
    }

    /** The places the ring is read at, spread around the whole contour. */
    private fun ringSamples(contour: List<P2>): List<RingSample> {
        val all = ArrayList<RingSample>()
        var travelled = 0f
        for (at in contour.indices) {
            val here = contour[at]
            val next = contour[(at + 1) % contour.size]
            val edge = next - here
            val length = edge.length
            // A boolean engine's outline carries slivers where faces were cut; a
            // wall is never half a millimetre long, and a sample on a sliver reads
            // the corner it sits in rather than the wall.
            if (length < MIN_RING_EDGE_MM) continue
            val along = edge * (1f / length)
            val inward = P2(-along.y, along.x)
            val steps = max(1, ceil(length / RING_SAMPLE_MM).toInt())
            for (step in 0 until steps) {
                val where = length * (step + 0.5f) / steps
                all += RingSample(travelled + where, here + along * where, along, inward, at)
            }
            travelled += length
        }
        if (all.size <= MAX_RING_SAMPLES) return all
        val thinned = ArrayList<RingSample>(MAX_RING_SAMPLES)
        for (at in 0 until MAX_RING_SAMPLES) thinned += all[at * all.size / MAX_RING_SAMPLES]
        return thinned
    }

    /** The material's own band inward from one place on the seam. */
    private fun bandAt(
        half: SnapFitHalf,
        axis: Vec3,
        side: Vec3,
        rise: Vec3,
        origin: Vec3,
        sample: RingSample,
    ): RingBand? {
        val point = origin + side * sample.point.x + rise * sample.point.y
        val edgeAxis = side * sample.edge.x + rise * sample.edge.y
        val inwardAxis = side * sample.inward.x + rise * sample.inward.y
        val cap = SnapFit.capTriangles(half, axis, edgeAxis, inwardAxis, half.faceMm, point)
        val run = runAt(cap, 0f, 0f) ?: return null
        return RingBand(run.start, run.endInclusive - run.start)
    }

    /** The mate's material inward from one place, in the same frame the lip is read in. */
    private fun mateBandAt(
        mateHalf: SnapFitHalf,
        axis: Vec3,
        side: Vec3,
        rise: Vec3,
        origin: Vec3,
        sample: RingSample,
    ): RingBand? {
        val point = origin + side * sample.point.x + rise * sample.point.y
        val edgeAxis = side * sample.edge.x + rise * sample.edge.y
        val inwardAxis = side * sample.inward.x + rise * sample.inward.y
        val cap = SnapFit.capTriangles(mateHalf, axis, edgeAxis, inwardAxis, mateHalf.faceMm, point)
        val run = nearestRun(cap, 0f, from = 0f) ?: return null
        return RingBand(run.start, run.endInclusive - run.start)
    }

    /**
     * The free length and the strain around the whole ring.
     *
     * One slice of the lip half is taken every half millimetre behind the seam
     * and every sample still standing is read off it in the same pass: the wall is
     * still free where the slice's material is the wall's own thickness, and it
     * stops being free where the material behind it closes up. Inside the ring's
     * own recess the wall is free by construction - that is what the recess is
     * for - so those slices do not end it early on a solid part.
     */
    private fun measureRing(
        lipHalf: SnapFitHalf,
        mateHalf: SnapFitHalf,
        axis: Vec3,
        side: Vec3,
        rise: Vec3,
        origin: Vec3,
        contour: List<P2>,
        wall: Float,
        recessDepth: Float,
        deflection: Float,
        samples: List<RingSample>,
        face: Float,
        direction: Vec3,
        /** The innermost place the ledge's plug needs the mate's material to have begun. */
        mateMustStartBy: Float,
        /** And how far in it has to reach, or the plug has nothing to root in. */
        mateMustReach: Float,
    ): RingReading {
        // The wall's own walk, at places spread around the ring: the same measured
        // walk the single joint used, which the dev phone's tray confirmed, asked
        // at eight points instead of one.
        // One place per edge, at its middle: a corner is where two faces meet and
        // a slice through it belongs to neither, so the walk is asked where the
        // wall runs straight rather than at the seam's own joints.
        val walkSamples = samples.groupBy { it.edgeIndex }.values
            .map { group -> group[group.size / 2] }
            .take(WALK_SAMPLES)

        var minFree = Float.MAX_VALUE
        var minFreeAt = 0f
        var maxStrain = 0f
        var maxStrainAt = 0f
        for (sample in walkSamples) {
            val point = origin + side * sample.point.x + rise * sample.point.y
            val edgeAxis = side * sample.edge.x + rise * sample.edge.y
            val inwardAxis = side * sample.inward.x + rise * sample.inward.y
            val length = freeLength(
                half = lipHalf,
                direction = direction,
                side = edgeAxis,
                rise = inwardAxis,
                face = face,
                origin = point,
                wallThickness = wall,
                freedDownToMm = recessDepth,
            )
            val strain = 1.5f * wall * deflection / (max(length, 0.01f) * max(length, 0.01f)) * 100f
            if (length < minFree) {
                minFree = length
                minFreeAt = sample.atMm
            }
            if (strain > maxStrain) {
                maxStrain = strain
                maxStrainAt = sample.atMm
            }
        }
        if (minFree == Float.MAX_VALUE) {
            minFree = MAX_FREE_LENGTH_MM
            minFreeAt = 0f
        }

        // A corner the ring crosses, and a mate that is hollow inboard of the wall:
        // both are places the hook is built and cannot work, and both are named
        // with their numbers rather than refused.
        var cornerAt: Float? = null
        var cornerDistance = 0f
        var hollowAt: Float? = null
        var hollowEnd = 0f
        var hollowSamples = 0
        for (at in samples.indices) {
            val sample = samples[at]
            val point = origin + side * sample.point.x + rise * sample.point.y
            val edgeAxis = side * sample.edge.x + rise * sample.edge.y
            val inwardAxis = side * sample.inward.x + rise * sample.inward.y
            if (cornerAt == null) {
                val cap = SnapFit.capTriangles(lipHalf, direction, edgeAxis, inwardAxis, face, point)
                val band = runAt(cap, 0f, 0f)
                if (band != null) {
                    val distance = cornerDistance(cap, band, wall)
                    if (distance < CORNER_NEAR_MM) {
                        cornerAt = sample.atMm
                        cornerDistance = distance
                    }
                }
            }
            val mate = mateBandAt(mateHalf, direction, side, rise, origin, sample)
            val reach = if (mate == null) 0f else mate.startMm + mate.lengthMm
            if (mate == null || mate.startMm > mateMustStartBy || reach < mateMustReach) {
                hollowSamples++
                if (hollowAt == null) {
                    hollowAt = sample.atMm
                    hollowEnd = if (mate == null) 0f else mate.startMm
                }
            }
        }
        return RingReading(
            minFreeMm = minFree,
            minFreeAtMm = minFreeAt,
            maxStrainPercent = maxStrain,
            maxStrainAtMm = maxStrainAt,
            deflectionMm = deflection,
            cornerAtMm = cornerAt,
            cornerDistanceMm = cornerDistance,
            hollowAtMm = hollowAt,
            hollowEndMm = hollowEnd,
            hollowSamples = hollowSamples,
        )
    }



    /**
     * One profile swept around the wall's face: the ring itself. The profile's
     * first coordinate runs inward from the wall's face and its second along the
     * axis, and every corner of the contour is mitred, so the ring closes on
     * itself with no gap and no staircase.
     */
    private fun ringMesh(
        name: String,
        contour: List<P2>,
        axis: Vec3,
        side: Vec3,
        rise: Vec3,
        origin: Vec3,
        profile: List<Pair<Float, Float>>,
    ): StlMesh? {
        if (contour.size < 3 || profile.size < 3) return null
        val corners = ArrayList<P2>(contour.size * profile.size)
        for (at in contour.indices) {
            val previous = contour[(at + contour.size - 1) % contour.size]
            val here = contour[at]
            val next = contour[(at + 1) % contour.size]
            for ((radius, height) in profile) {
                val point = offsetVertex(previous, here, next, radius) ?: return null
                corners += point
            }
        }
        fun vertex(sample: Int, corner: Int): Vec3 {
            val point = corners[sample * profile.size + corner]
            val (_, height) = profile[corner]
            return origin + side * point.x + rise * point.y + axis * height
        }
        val faces = ArrayList<Array<Vec3>>()
        for (sample in contour.indices) {
            val next = (sample + 1) % contour.size
            for (corner in profile.indices) {
                val after = (corner + 1) % profile.size
                val a = vertex(sample, corner)
                val b = vertex(sample, after)
                val c = vertex(next, after)
                val d = vertex(next, corner)
                faces += arrayOf(a, b, c)
                faces += arrayOf(a, c, d)
            }
        }
        // A closed sweep's own sense depends on which way the profile is wound and
        // which way the contour runs: the mesh is only useful wound outward, so it
        // is measured and turned round if it came out inside-out.
        var sixTimesVolume = 0f
        for (face in faces) {
            val a = face[0]
            val b = face[1]
            val c = face[2]
            sixTimesVolume += a.dot(b.cross(c))
        }
        val builder = MeshSolidBuilder(name)
        for (face in faces) {
            val a = face[0]
            val b = if (sixTimesVolume >= 0f) face[1] else face[2]
            val c = if (sixTimesVolume >= 0f) face[2] else face[1]
            builder.addTriangle(
                a.x, a.y, a.z,
                b.x, b.y, b.z,
                c.x, c.y, c.z,
            )
        }
        return builder.build()
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

    /**
     * The wall the ring is built to when the part is solid along the seam: the
     * recess leaves this much material at the edge to be the spring, because a
     * block with no wall at all has nothing to bend.
     */
    private const val RING_WALL_MM = 2f

    /** How far apart the ring's own reading samples the seam. */
    private const val RING_SAMPLE_MM = 1.5f
    private const val MAX_RING_SAMPLES = 64

    /** How many places round the ring the wall's own walk is asked for its length. */
    private const val WALK_SAMPLES = 8

    /** Edges shorter than this are cut slivers, not walls, and carry no samples. */
    private const val MIN_RING_EDGE_MM = 1f

    /** Two contour corners this close are the same corner. */
    private const val WELD_MM = 5e-3f

    /** How near a corner has to be for the ring to be warned about it. */
    private const val CORNER_NEAR_MM = 1f

    /** The recess reaches a hair past the mating plane so the cut is clean there. */
    private const val RECESS_OVER_MM = 0.05f

    /** Below this the seam is a line rather than a ring. */
    private const val PI_ISH = 0.5f

    private val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()
}
