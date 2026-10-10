package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The snap-fit joint, as geometry: given the split plane's normal, the point on
 * the model the user tapped, a scale and the two halves, this builds the two
 * solids a boolean can put in - the beam, barb and key to union into one half,
 * and the matching pocket to subtract from the other.
 *
 * The design is the agreed one (docs/snap-fit-notes.md): a cantilever lying
 * across the seam along the assembly direction, with a barb on its free end,
 * plus a square key beside it for registration. Nothing protrudes past the
 * outline of either part.
 *
 * The barb is one-way, and its shape is the whole trick:
 *
 *  - The leading face - the one the other part reaches first, facing back along
 *    the assembly direction - is a [SnapFitParameters.rampAngleDeg]-degree ramp.
 *    The descending half's pocket mouth rides that ramp and cams the beam
 *    aside, which is the only reason the parts can be pushed together at all. A
 *    square leading face cannot cam in, and a joint with one never assembles.
 *  - The back face is square, perpendicular to the assembly direction. It is
 *    the catch: once the barb has sprung out into its pocket the square faces
 *    meet and pulling the halves apart only presses them together.
 *
 * Clearances are separate and explicit, because most printed snap fits fail on
 * exactly this: [SnapFitParameters.keyClearanceMm] around the key,
 * [SnapFitParameters.lipClearanceMm] around the beam and barb, and
 * [SnapFitParameters.matingClearanceMm] as the relief of the socket's mouth in
 * the mate's mating face. All three default to a printable 0.2 mm and all three
 * scale with [SnapFitParameters] like every other dimension, so one slider
 * drives the whole joint.
 *
 * Each solid is built in the frame of the half it belongs to, relative to that
 * half's OWN mating face: the union - beam, barb and key - in the beam half's
 * frame, the pocket in the mate's, and the two frames are placed through the
 * placements the halves carry. Where the halves happen to sit on the bed is
 * therefore not an input to the geometry at all. It matters: the plate's packer
 * moves the halves several millimetres apart after a split, and a frame built
 * around the midpoint of the gap between them lands in mid-air - the pocket
 * cuts nothing and the union adds a floating block, closed and manifold and
 * wrong. The split knows the plane in each half's own coordinates, and that is
 * what [SnapFitHalf.faceMm] carries here.
 *
 * The beam is unioned into the half on the low side of the plane and the pocket
 * is cut from the half on the high side; flipping the axis swaps which half
 * carries the beam. The beam's length is clamped to the material the other half
 * actually has (leaving a wall at least a beam-thickness thick), and its root
 * to the material its own half has, so the joint cannot run out through the far
 * face; if what is left is too small to be a joint, nothing is produced.
 *
 * Pure geometry: no UI, no state, no I/O, and no dependency outside the JVM.
 */
object SnapFit {
    /**
     * Builds the best joint this seam can carry, or null when even the lightest
     * one cannot be put there: halves that do not meet on one plane, a mate too
     * thin for a beam, a beam half too thin to root one, or a scale or angle
     * that is not a positive number.
     *
     * The rungs are walked fullest first - pad, key and cantilever, then key and
     * cantilever, then the cantilever alone shrunk to the material - and the
     * joint says which rung it came out as and why the fuller ones were dropped.
     * [requested] pins one rung: a user asking for the full joint gets the full
     * joint, coverage and all, and only geometry that cannot be built refuses.
     */
    fun generate(
        axis: Vec3,
        /** The tapped point, in the two halves' own (unplaced) coordinates. */
        anchorMm: Vec3,
        scale: Float,
        beamHalf: SnapFitHalf,
        socketHalf: SnapFitHalf,
        parameters: SnapFitParameters = SnapFitParameters(),
        requested: SnapFitRung? = null,
    ): SnapFitJoint? {
        if (!axis.isFinite() || !anchorMm.isFinite() || !scale.isFinite() || scale <= 0f) return null
        if (!parameters.isUsable()) return null
        if (beamHalf.mesh.triangleCount <= 0 || socketHalf.mesh.triangleCount <= 0) return null

        val direction = axis.normalized() ?: return null
        val side = perpendicularTo(direction)
        val rise = direction.cross(side)

        val beamSpan = span(beamHalf, direction) ?: return null
        val socketSpan = span(socketHalf, direction) ?: return null
        // Each half's own mating face, along the assembly direction - the plane
        // the split carried into the half, not one measured from where the
        // packer happened to put it. The beam's half must not reach past the
        // mate's face: a pair the wrong way round overlaps by the whole model
        // and is refused here.
        val face = beamHalf.faceMm
        val mateFace = socketHalf.faceMm
        if (face > mateFace + PLANE_TOLERANCE_MM) return null

        // How much material each half has behind its own face. A half that
        // already carries a joint no longer ends at its mating face, which is
        // why the face is carried rather than measured.
        val ownDepth = face - beamSpan.start
        val mateDepth = socketSpan.endInclusive - mateFace

        // Each frame's origin is the anchor dropped onto that half's own mating
        // face, so each half's plane is local z = 0 in its own frame and the
        // beam sits where the user tapped.
        val alongAnchor = anchorMm.dot(direction)
        val beamOrigin = anchorMm - direction * (alongAnchor - face)
        val socketOrigin = anchorMm - direction * (alongAnchor - mateFace)
        val beamFrame = SnapFitFrame(beamOrigin, direction, side, rise, face)
        val socketFrame = SnapFitFrame(socketOrigin, direction, side, rise, mateFace)

        // Size from the part, not from millimetres. The face the two halves
        // share says how big a feature the seam can carry, and the material on
        // each side of it says how far the beam may reach; the parameters are
        // ceilings, never starting points. A 10 mm cube therefore gets a
        // millimetre-scale joint rather than one half the width of its face.
        val faceOverlap = sharedFace(beamHalf, socketHalf, side, rise, anchorMm)
        val reference = faceOverlap?.let { minOf(it.width, it.height) } ?: (2f * minOf(ownDepth, mateDepth))

        val rampAngle = parameters.rampAngleDeg
        val keyClearance = parameters.keyClearanceMm * scale
        val matingClearance = parameters.matingClearanceMm * scale
        val lipClearance = parameters.lipClearanceMm * scale
        val keyGap = minOf(parameters.keyGapMm, parameters.keySizeMm * 0.5f) * scale
        val keySize = minOf(parameters.keySizeMm, KEY_FRACTION * reference) * scale
        // The key is sunk flush: it never stands further out than the beam is
        // thick, so the face reads as a face rather than as a block.
        val keyHeight = minOf(parameters.keyHeightMm, parameters.beamThicknessMm) * scale
        val beamWidth = minOf(parameters.beamWidthMm, BEAM_WIDTH_FRACTION * reference) * scale
        // The scale sets every dimension; a parameter the user has typed a
        // millimetre value for overrides its own share of that, and the
        // material's ceilings still apply on top.
        val thicknessCeiling = BEAM_THICKNESS_FRACTION * reference * scale
        val fullThickness = parameters.beamThicknessOverrideMm
            ?.let { minOf(it, thicknessCeiling) }
            ?: minOf(parameters.beamThicknessMm, BEAM_THICKNESS_FRACTION * reference) * scale
        // The step's depth along the assembly axis is an ABSOLUTE step - order
        // half a millimetre to one and a half - clamped only by the material it
        // is cut into, never sized as a fraction of it: a rebate a tenth of a
        // millimetre deep is not a rebate, and on a big part a fraction of the
        // material is invisible.
        val stepDepth = minOf(
            parameters.stepDepthMm * scale,
            STEP_DEPTH_MAX_MM,
            STEP_DEPTH_MATERIAL_SHARE * mateDepth,
            fullThickness,
        )
        val stepClearance = minOf(matingClearance, stepDepth * 0.5f)
        val stepRim = minOf(parameters.stepRimMm * scale, STEP_RIM_FRACTION * reference)
        val keyCentre = beamWidth * 0.5f + keyGap + keySize * 0.5f

        // The ladder's plan: what the seam's own cross-section will carry. The
        // cap triangles are the material at the mating face, so a pad or a key
        // whose footprint mostly hangs over air would print as a floating block,
        // and the rung below it is the honest answer.
        val cap = capTriangles(beamHalf, direction, side, rise, face, anchorMm)
        val bossFace = faceOverlap?.inset(stepRim + stepClearance)
        val keyFace = LocalRect(
            keyCentre - keySize * 0.5f,
            keyCentre + keySize * 0.5f,
            -keySize * 0.5f,
            keySize * 0.5f,
        )
        val padCovered = bossFace != null && covers(cap, bossFace, PAD_COVERAGE)
        val keyCovered = covers(cap, keyFace, KEY_COVERAGE)
        val padWhy = when {
            faceOverlap == null -> "the two cross-sections barely overlap"
            bossFace == null || stepDepth <= 0f || stepDepth + stepClearance >= mateDepth ->
                "the mate is too shallow for the whole-seam pad"
            !padCovered -> "the seam is hollow where the whole-seam pad would sit"
            else -> "the pad does not fit this seam"
        }
        val keyWhy = when {
            !keyCovered && !padCovered -> "the seam is hollow where the pad and key would sit"
            !keyCovered -> "the seam is hollow where the key would sit"
            keyGap <= keyClearance + lipClearance -> "there is no room to keep a key clear of the beam"
            keyHeight + keyClearance + matingClearance >= mateDepth -> "the mate is too shallow for the key"
            else -> "the wall is too narrow for the key"
        }
        val padFits = bossFace != null && stepDepth >= STEP_DEPTH_MIN_MM &&
            stepDepth + stepClearance < mateDepth && padCovered
        val keyFits = keyGap > keyClearance + lipClearance &&
            keyHeight + keyClearance + matingClearance < mateDepth &&
            keyCovered
        val ladder = requested ?: when {
            padFits -> SnapFitRung.FULL
            keyFits -> SnapFitRung.SIMPLE
            else -> SnapFitRung.MINIMAL
        }
        fun rungReason(built: SnapFitRung, planned: SnapFitRung): String? = when {
            requested != null -> null
            built == SnapFitRung.FULL -> null
            // The plan said a fuller rung but its beam could not be built at
            // all: the reason is the material, not the feature that was tried.
            built.ordinal < planned.ordinal -> "the material is too thin for the fuller joint; " +
                if (built == SnapFitRung.SIMPLE) "key and cantilever only" else "a bare cantilever only"
            built == SnapFitRung.SIMPLE -> padWhy + "; key and cantilever only"
            else -> keyWhy + "; a bare cantilever only"
        }

        fun buildRung(rung: SnapFitRung): SnapFitJoint? {
            // The lightest rung shrinks its beam to the material it has, so a
            // thin wall still gets a cantilever rather than a refusal.
            val askedThickness = fullThickness
            val beamThickness = when (rung) {
                SnapFitRung.MINIMAL -> minOf(fullThickness, 0.5f * ownDepth, 0.5f * mateDepth)
                else -> fullThickness
            }
            if (beamThickness <= 0f) return null
            val lipCeiling = beamThickness * 0.5f
            val askedLip = parameters.lipDepthOverrideMm
                ?: minOf(parameters.lipDepthMm, parameters.beamThicknessMm * 0.5f) * scale
            val lipDepth = minOf(askedLip, lipCeiling)
            // Leave a wall of at least a beam-thickness beyond the beam's tip,
            // and the same below its root.
            val wall = beamThickness
            // The ONLY physical limit on the hook's length is the mate's own
            // material: past it the tip breaks out of the far face. A share of
            // the depth is not a limit - clamping downward protects the user
            // from the safe direction, since root strain falls with the square
            // of the length - so the hook runs as long as the material takes.
            val askedLength = parameters.beamLengthOverrideMm ?: parameters.beamLengthMm * scale
            val beamLength = minOf(askedLength, mateDepth - wall)
            val beamRoot = minOf(parameters.beamRootMm * scale, ROOT_FRACTION * ownDepth, ownDepth - wall)
            if (beamLength <= 0f || beamRoot <= 0f) return null
            // The hook's own lead-in: a ramp at the parameter's angle, or a
            // square face when the user asks for one. A square lead-in is the
            // same length along the axis as a 45-degree ramp - it is the shape
            // that changes, not the room it takes - and it is what the socket
            // mouth's own chamfer cams in on.
            val squareLeadIn = rampAngle >= SQUARE_LEAD_IN_DEG
            val leadRun = if (squareLeadIn) lipDepth else lipDepth / tan(rampAngle * DEGREES_TO_RADIANS)
            if (beamLength <= leadRun + lipClearance) return null

            val half = beamThickness * 0.5f
            val withKey = rung != SnapFitRung.MINIMAL
            val withPad = rung == SnapFitRung.FULL
            val stepRoot = minOf(beamRoot, STEP_ROOT_FRACTION * ownDepth)
            val rimmed = if (withPad) faceOverlap?.inset(stepRim) else null
            if (withPad && (bossFace == null || rimmed == null)) return null

            // What the user asked for that the material would not take. A
            // slider that stops moving without saying why is worse than no
            // slider, so every clamp travels with the joint.
            val clamps = ArrayList<SnapFitClamp>()

            // ------------------------------------------------- one pawl, N teeth
            // The ratchet the notes settled on: SEVERAL TEETH on the beam and
            // ONE PAWL on the mate. The pitch is the lead-in's run plus a
            // lip-depth flat, the tip-most tooth's ramp ends at the beam's tip,
            // and each next catch sits a pitch further back, so the catch faces
            // are at beamLength - leadRun - i * pitch and the DEEPEST of them is
            // where the mate's single step sits. The pocket is narrow from its
            // mouth back to that step and wide beyond it, so every tooth but the
            // one being caught is inside the wide slot: as the parts close, the
            // tooth nearest the tip reaches the pawl first and springs out, and
            // each further tooth springs out a pitch later. The engagement
            // depths are therefore 0, pitch, 2 * pitch ... behind the pawl,
            // which is what [SnapClick] names in the panel.
            val pitch = leadRun + lipDepth
            val askedBarbs = parameters.barbCount.coerceAtLeast(1)
            var barbs = minOf(askedBarbs, MAX_BARBS)
            while (barbs > 1 && beamLength - leadRun - (barbs - 1) * pitch < MIN_DEEPEST_CATCH_MM) {
                barbs--
            }
            if (barbs < askedBarbs) {
                clamps += SnapFitClamp(
                    label = "barbs",
                    askedMm = askedBarbs.toFloat(),
                    actualMm = barbs.toFloat(),
                    reason = "a " + millimetres(beamLength) + " mm hook takes " + barbs +
                        (if (barbs == 1) " tooth" else " teeth") + " at a " + millimetres(pitch) +
                        " mm pitch; the rest would sit behind the mating face",
                )
            }
            val catches = List(barbs) { index -> beamLength - leadRun - index * pitch }
            val lipBack = catches.first()
            // The pawl sits one clearance in front of the deepest catch, which is
            // the clearance the joint seats with - exactly where the single-barb
            // pocket's step has always been.
            val pawl = catches.last() - lipClearance
            if (askedLength > beamLength + CLAMP_EPSILON_MM) {
                clamps += SnapFitClamp(
                    label = "hook length",
                    askedMm = askedLength,
                    actualMm = beamLength,
                    reason = "the mate has only " + millimetres(mateDepth) + " mm of material; " +
                        "hook length clamped to " + millimetres(beamLength) + " mm",
                )
            }
            if (askedThickness > beamThickness + CLAMP_EPSILON_MM) {
                clamps += SnapFitClamp(
                    label = "hook thickness",
                    askedMm = askedThickness,
                    actualMm = beamThickness,
                    reason = "the material only takes a " + millimetres(beamThickness) +
                        " mm hook; thickness clamped",
                )
            }
            if (askedLip > lipDepth + CLAMP_EPSILON_MM) {
                clamps += SnapFitClamp(
                    label = "hook lip",
                    askedMm = askedLip,
                    actualMm = lipDepth,
                    reason = "the hook is " + millimetres(beamThickness) + " mm thick; lip depth clamped to " +
                        millimetres(lipDepth) + " mm",
                )
            }
            // ------------------------------------------------- the deflection room
            // The pawl rides the tooth's ramp and pushes the beam bodily aside
            // by the tooth's own height less the clearance it already has; the
            // whole free length dips with it. So the pocket's floor is dropped
            // by that much plus a clearance, measured on the side the beam bends
            // towards. The mate's own material below the anchor is the ceiling -
            // a socket ramp helps the hook along, it does not replace the room
            // the beam needs - and a ceiling that bites is a clamp with a
            // sentence, never a silent jam.
            val facingSign = parameters.facing.sign
            val sink = maxOf(0f, lipDepth - lipClearance)
            val roomAsked = sink + lipClearance
            val mateRise = span(socketHalf, rise)
            val anchorRise = anchorMm.dot(rise)
            val belowAvailable = when {
                mateRise == null -> roomAsked
                facingSign > 0f -> anchorRise - mateRise.start
                else -> mateRise.endInclusive - anchorRise
            }
            val room = minOf(roomAsked, maxOf(lipClearance, belowAvailable - half))
            if (room < roomAsked - CLAMP_EPSILON_MM) {
                clamps += SnapFitClamp(
                    label = "deflection room",
                    askedMm = roomAsked,
                    actualMm = room,
                    reason = "the mate has only " + millimetres(belowAvailable) +
                        " mm of material below the hook and the hook needs " + millimetres(roomAsked) +
                        " mm to bend through; the pocket floor was left with " + millimetres(room) +
                        " mm and the joint may not close",
                )
            }

            // ------------------------------------------------- the socket's ramp
            // The mouth chamfer: a funnel that opens at the mating face and
            // closes onto the pawl, so the lead-in lives in the hole. Its wall
            // makes [mouthChamferDeg] with the assembly axis - 45 degrees is the
            // printable limit for an overhanging roof, and it is what lets a
            // square-faced hook cam in and therefore a joint face either way.
            val chamferAngle = parameters.mouthChamferDeg
            val chamferDepth = if (parameters.socketRamp) {
                minOf(lipDepth, pawl * tan(chamferAngle * DEGREES_TO_RADIANS))
            } else {
                0f
            }
            val chamferRun = if (chamferDepth > 0f) {
                chamferDepth / tan(chamferAngle * DEGREES_TO_RADIANS)
            } else {
                0f
            }
            if (parameters.socketRamp && chamferDepth < lipDepth - CLAMP_EPSILON_MM) {
                clamps += SnapFitClamp(
                    label = "socket ramp",
                    askedMm = lipDepth,
                    actualMm = chamferDepth,
                    reason = "the pawl is only " + millimetres(pawl) + " mm in from the mating face, so the " +
                        "mouth chamfer is " + millimetres(chamferDepth) + " mm deep instead of " +
                        millimetres(lipDepth) + " mm",
                )
            }

            // The root strain a full deflection puts on the cantilever:
            // eps = 3 h d / (2 L^2) for a rectangular beam of thickness h
            // deflected by d over its free length L. Deflection accumulates
            // while the pawl rides each tooth's ramp, so the readout takes the
            // worst case - every tooth's lip depth together. A readout, never a
            // gate.
            val deflection = lipDepth * barbs
            val rootStrainPercent = 1.5f * beamThickness * deflection / (beamLength * beamLength) * 100f
            // What the panel names: which click the joint is holding at, from
            // the first (the tooth nearest the tip, caught with the halves still
            // apart) to the seated one on the deepest catch.
            val clicks = List(barbs) { index ->
                val order = index + 1
                SnapClick(
                    order = order,
                    gapMm = (barbs - 1 - index) * pitch + lipClearance,
                    label = when {
                        barbs == 1 -> SEATED_CLICK
                        order == 1 -> "first click - loose"
                        order == barbs -> SEATED_CLICK
                        order == 2 -> "second click"
                        else -> "third click"
                    },
                )
            }

            val dimensions = SnapFitDimensions(
                scale = scale,
                keySizeMm = keySize,
                keyHeightMm = keyHeight,
                keyClearanceMm = keyClearance,
                keyOffsetMm = keyCentre,
                beamLengthMm = beamLength,
                beamWidthMm = beamWidth,
                beamThicknessMm = beamThickness,
                beamRootMm = beamRoot,
                lipDepthMm = lipDepth,
                lipRunMm = leadRun,
                lipClearanceMm = lipClearance,
                matingClearanceMm = matingClearance,
                rampAngleDeg = rampAngle,
                beamTipMm = beamLength,
                lipBackMm = lipBack,
                stepDepthMm = stepDepth,
                stepRootMm = stepRoot,
                stepRimMm = stepRim,
                stepClearanceMm = stepClearance,
                rootStrainPercent = rootStrainPercent,
                barbCount = barbs,
                toothPitchMm = pitch,
                teethCatchMm = catches,
                pawlMm = pawl,
                clickGapMm = clicks.map { it.gapMm },
                deflectionMm = deflection,
                deflectionRoomMm = room,
                mouthChamferMm = chamferDepth,
                mouthChamferAngleDeg = if (chamferDepth > 0f) chamferAngle else 0f,
                facing = parameters.facing,
            )

            // ------------------------------------------------------------- union
            val union = MeshSolidBuilder(UNION_NAME)
            // The beam and its teeth are one prism: a cross-section in the
            // (rise, axis) plane, extruded across the beam's width. Building
            // them as one closed shell keeps the boolean from having to
            // reconcile two shells that share a face. The profile runs from the
            // root, along the beam's underside to its tip, then back along the
            // top: each tooth's lead-in (a ramp, or a square face), its square
            // catch, and the flat at the beam's own surface between teeth.
            val profile = ArrayList<Vec3>(5 + barbs * 3)
            fun tooth(y: Float, z: Float) {
                profile += Vec3(-beamWidth * 0.5f, facingSign * y, z)
            }
            tooth(-half, -beamRoot)
            tooth(-half, beamLength)
            for (catch in catches) {
                val lead = catch + leadRun
                tooth(half, lead)
                if (squareLeadIn) tooth(half + lipDepth, lead)
                tooth(half + lipDepth, catch)
                tooth(half, catch)
            }
            tooth(half, -beamRoot)
            addPrism(union, beamFrame, profile, Vec3(1f, 0f, 0f), beamWidth)
            // The square key, disjoint from the beam by the key gap.
            if (withKey) {
                addPrism(
                    union,
                    beamFrame,
                    listOf(
                        Vec3(keyCentre - keySize * 0.5f, -keySize * 0.5f, -beamRoot),
                        Vec3(keyCentre + keySize * 0.5f, -keySize * 0.5f, -beamRoot),
                        Vec3(keyCentre + keySize * 0.5f, keySize * 0.5f, -beamRoot),
                        Vec3(keyCentre - keySize * 0.5f, keySize * 0.5f, -beamRoot),
                    ),
                    Vec3(0f, 0f, 1f),
                    keyHeight + beamRoot,
                )
            }

            // ---------------------------------------------------------- subtract
            val socket = MeshSolidBuilder(SOCKET_NAME)
            // The beam and barb plus clearance, as one pocket. It starts below the
            // plane by the mating clearance, so the pocket is open at the mate's
            // mating face and that face is relieved around the joint instead of
            // bottoming out on the other half.
            val pocketSide = beamWidth * 0.5f + lipClearance
            val pocketEnd = beamLength + lipClearance
            val narrowRoof = half + lipClearance
            val wideRoof = half + lipDepth + lipClearance
            val floor = -(half + room)
            val socketProfile = ArrayList<Vec3>(8)
            fun pocket(y: Float, z: Float) {
                socketProfile += Vec3(-pocketSide, facingSign * y, z)
            }
            pocket(floor, -matingClearance)
            pocket(floor, pocketEnd)
            pocket(wideRoof, pocketEnd)
            // The pawl: the one step the mate carries, at the deepest catch.
            // Mouth-side of it the pocket is one clearance off the beam; beyond
            // it the pocket is wide enough for a tooth to stand up in.
            pocket(wideRoof, pawl)
            pocket(narrowRoof, pawl)
            if (chamferDepth > 0f) {
                // The mouth chamfer, opening at the mating face and closing onto
                // the pawl at the parameter's angle.
                pocket(narrowRoof, chamferRun)
                pocket(narrowRoof + chamferDepth, 0f)
                pocket(narrowRoof + chamferDepth, -matingClearance)
            } else {
                pocket(narrowRoof, -matingClearance)
            }
            addPrism(socket, socketFrame, socketProfile, Vec3(1f, 0f, 0f), beamWidth + 2f * lipClearance)
            if (withKey) {
                // The key's socket, the key plus the key's own clearance.
                val keySide = keySize * 0.5f + keyClearance
                addPrism(
                    socket,
                    socketFrame,
                    listOf(
                        Vec3(keyCentre - keySide, -keySide, -matingClearance),
                        Vec3(keyCentre + keySide, -keySide, -matingClearance),
                        Vec3(keyCentre + keySide, keySide, -matingClearance),
                        Vec3(keyCentre - keySide, keySide, -matingClearance),
                    ),
                    Vec3(0f, 0f, 1f),
                    keyHeight + keyClearance + matingClearance,
                )
            }

            // The step's two solids. The boss is inset by the rim plus the
            // clearance and the recess by the rim alone, so the boss sits inside
            // the recess with a printable gap all round; both are plain boxes in
            // the frame, which is the whole reason a step this shape can be cut
            // with the same box booleans the rest of the joint uses.
            val registration = MeshSolidBuilder(REGISTRATION_NAME)
            val recess = MeshSolidBuilder(RECESS_NAME)
            if (withPad && bossFace != null && rimmed != null) {
                boxLocal(registration, beamFrame, bossFace, -stepRoot, stepDepth)
                boxLocal(recess, socketFrame, rimmed, -stepClearance, stepDepth + stepClearance)
            }

            // The solids were laid out in the halves' own frames; the booleans
            // and the viewer work in plate coordinates, so each goes through the
            // placement of the half it belongs to. Nothing here has looked at
            // where the other half is - that is the whole point.
            return SnapFitJoint(
                union.build().placedBy(beamHalf, UNION_NAME),
                socket.build().placedBy(socketHalf, SOCKET_NAME),
                registration.build().placedBy(beamHalf, REGISTRATION_NAME),
                recess.build().placedBy(socketHalf, RECESS_NAME),
                beamHalf.placed(beamFrame),
                socketHalf.placed(socketFrame),
                dimensions,
                rung,
                null,
                clamps,
                clicks,
            )
        }

        // Walk the ladder from the rung the material (or the user) asked for
        // down to the lightest one: a rung whose beam cannot fit falls through
        // to the next, and only when none of them fits is there no joint.
        val rungs = if (requested != null) {
            listOf(requested)
        } else {
            SnapFitRung.entries.filter { it.ordinal >= ladder.ordinal }
        }
        for (rung in rungs) {
            val joint = buildRung(rung) ?: continue
            return joint.copy(rungReason = rungReason(rung, ladder))
        }
        return null
    }

    /**
     * The face the two halves share, in the halves' own plane: the overlap of
     * their outlines, in their own (side, rise) millimetres. Reading both
     * outlines in the halves' own coordinates is what makes the answer the
     * seam's real cross-section - read on the plate, the packer's sideways
     * offset would shrink it to nothing. Null when they do not overlap at all,
     * which leaves the joint without a registration step rather than with one
     * hanging off the part.
     */
    private fun sharedFace(
        beamHalf: SnapFitHalf,
        socketHalf: SnapFitHalf,
        side: Vec3,
        rise: Vec3,
        /** The tapped point, in the halves' own coordinates: the frames' origin. */
        origin: Vec3,
    ): LocalRect? {
        val first = outline(beamHalf, side, rise, origin) ?: return null
        val second = outline(socketHalf, side, rise, origin) ?: return null
        return first.intersect(second)
    }

    /** How far a half reaches along [direction], in that half's own coordinates. */
    private fun span(half: SnapFitHalf, direction: Vec3): ClosedFloatingPointRange<Float>? {
        val along = half.probe(direction) ?: return null
        val vertices = half.mesh.interleavedVertices
        val count = half.mesh.triangleCount * 3
        if (count <= 0) return null
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        for (vertex in 0 until count) {
            val base = vertex * MeshSolidBuilder.FLOATS_PER_VERTEX
            val at = along(vertices[base], vertices[base + 1], vertices[base + 2])
            if (at < low) low = at
            if (at > high) high = at
        }
        return if (low <= high) low..high else null
    }

    /**
     * A half's outline in the halves' (side, rise) plane, relative to [origin] -
     * the tapped point, which is the frame both features are laid out in. The
     * subtraction is what makes the rectangle frame-local: the halves' own
     * coordinates run over the whole bed, and a rectangle left in them would
     * put the pad wherever the model happened to sit.
     */
    private fun outline(half: SnapFitHalf, side: Vec3, rise: Vec3, origin: Vec3): LocalRect? {
        val across = half.probe(side) ?: return null
        val through = half.probe(rise) ?: return null
        if (half.mesh.triangleCount <= 0) return null
        val originX = origin.dot(side)
        val originY = origin.dot(rise)
        val vertices = half.mesh.interleavedVertices
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (vertex in 0 until half.mesh.triangleCount * 3) {
            val base = vertex * MeshSolidBuilder.FLOATS_PER_VERTEX
            val x = across(vertices[base], vertices[base + 1], vertices[base + 2]) - originX
            val y = through(vertices[base], vertices[base + 1], vertices[base + 2]) - originY
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        return LocalRect(minX, maxX, minY, maxY)
    }

    /**
     * The triangles of a half that lie on its own mating face, projected into
     * the halves' (side, rise) plane: the material a face-level feature would
     * actually sit on.
     *
     * A hollow cross-section - a hull, a tube - shows up here as a thin band
     * with air where the middle is, which is how the ladder tells a pad that
     * would print as a floating block from one that lands on the seam.
     */
    private fun capTriangles(
        half: SnapFitHalf,
        direction: Vec3,
        side: Vec3,
        rise: Vec3,
        face: Float,
        /** The tapped point, in the halves' own coordinates: the frames' origin. */
        origin: Vec3,
    ): List<CapTriangle> {
        val along = half.probe(direction) ?: return emptyList()
        val across = half.probe(side) ?: return emptyList()
        val through = half.probe(rise) ?: return emptyList()
        val originX = origin.dot(side)
        val originY = origin.dot(rise)
        val vertices = half.mesh.interleavedVertices
        val triangles = ArrayList<CapTriangle>()
        val xs = FloatArray(3)
        val ys = FloatArray(3)
        for (triangle in 0 until half.mesh.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            var onFace = true
            for (corner in 0 until 3) {
                val at = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
                val x = vertices[at]
                val y = vertices[at + 1]
                val z = vertices[at + 2]
                if (abs(along(x, y, z) - face) > CAP_PLANE_TOLERANCE_MM) {
                    onFace = false
                    break
                }
                xs[corner] = across(x, y, z) - originX
                ys[corner] = through(x, y, z) - originY
            }
            if (onFace) triangles += CapTriangle(xs[0], ys[0], xs[1], ys[1], xs[2], ys[2])
        }
        return triangles
    }

    /** How much of [face] has cap material under it, on a fixed sample grid. */
    private fun covers(cap: List<CapTriangle>, face: LocalRect, required: Float): Boolean {
        if (cap.isEmpty()) return false
        var inside = 0
        for (row in 0 until COVERAGE_SAMPLES) {
            for (column in 0 until COVERAGE_SAMPLES) {
                val x = face.minX + face.width * (column + 0.5f) / COVERAGE_SAMPLES
                val y = face.minY + face.height * (row + 0.5f) / COVERAGE_SAMPLES
                if (cap.any { it.contains(x, y) }) inside++
            }
        }
        return inside >= required * COVERAGE_SAMPLES * COVERAGE_SAMPLES
    }

    /** One cap triangle, in the halves' (side, rise) plane. */
    private class CapTriangle(
        private val ax: Float,
        private val ay: Float,
        private val bx: Float,
        private val by: Float,
        private val cx: Float,
        private val cy: Float,
    ) {
        private val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

        val minX: Float get() = minOf(ax, bx, cx)
        val maxX: Float get() = maxOf(ax, bx, cx)
        val minY: Float get() = minOf(ay, by, cy)
        val maxY: Float get() = maxOf(ay, by, cy)

        fun contains(x: Float, y: Float): Boolean {
            if (abs(area) < CAP_AREA_EPSILON) return false
            val first = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            val second = (cx - bx) * (y - by) - (cy - by) * (x - bx)
            val third = (ax - cx) * (y - cy) - (ay - cy) * (x - cx)
            return if (area > 0f) {
                first >= -CAP_EDGE_EPSILON && second >= -CAP_EDGE_EPSILON && third >= -CAP_EDGE_EPSILON
            } else {
                first <= CAP_EDGE_EPSILON && second <= CAP_EDGE_EPSILON && third <= CAP_EDGE_EPSILON
            }
        }
    }

    /**
     * [mesh], laid out in [half]'s own coordinates, as that half sits on the
     * plate. The joint's solids are tiny, so the copy is not worth avoiding; the
     * halves themselves are never copied, which is why the measurements walk
     * the placed mesh through [SnapFitHalf.probe].
     */
    private fun StlMesh.placedBy(half: SnapFitHalf, name: String): StlMesh {
        if (half.isInPlace) return copy(displayName = name)
        val builder = MeshSolidBuilder(name)
        val points = FloatArray(9)
        for (triangle in 0 until triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            for (corner in 0 until 3) {
                val at = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
                val plate = half.toPlate(Vec3(interleavedVertices[at], interleavedVertices[at + 1], interleavedVertices[at + 2]))
                points[corner * 3] = plate.x
                points[corner * 3 + 1] = plate.y
                points[corner * 3 + 2] = plate.z
            }
            builder.addTriangle(
                points[0], points[1], points[2],
                points[3], points[4], points[5],
                points[6], points[7], points[8],
            )
        }
        return builder.build()
    }

    /** [frame], laid out in [half]'s own coordinates, as that half sits on the plate. */
    private fun SnapFitHalf.placed(frame: SnapFitFrame): SnapFitFrame {
        val origin = toPlate(frame.originMm)
        val axis = directionToPlate(frame.axis)
        val side = directionToPlate(frame.side)
        val rise = directionToPlate(frame.rise)
        return SnapFitFrame(origin, axis, side, rise, origin.dot(axis))
    }

    private fun SnapFitHalf.directionToPlate(direction: Vec3): Vec3 {
        val m = transform.linear
        return Vec3(
            (m[0] * direction.x + m[1] * direction.y + m[2] * direction.z).toFloat(),
            (m[3] * direction.x + m[4] * direction.y + m[5] * direction.z).toFloat(),
            (m[6] * direction.x + m[7] * direction.y + m[8] * direction.z).toFloat(),
        ).normalized() ?: direction
    }

    /** An axis-aligned box in frame-local millimetres, wound outwards. */
    private fun boxLocal(builder: MeshSolidBuilder, frame: SnapFitFrame, face: LocalRect, low: Float, high: Float) {
        val corners = arrayOf(
            Vec3(face.minX, face.minY, low), Vec3(face.maxX, face.minY, low),
            Vec3(face.maxX, face.maxY, low), Vec3(face.minX, face.maxY, low),
            Vec3(face.minX, face.minY, high), Vec3(face.maxX, face.minY, high),
            Vec3(face.maxX, face.maxY, high), Vec3(face.minX, face.maxY, high),
        ).map { frame.model(it) }
        fun face(a: Int, b: Int, c: Int, d: Int) {
            builder.addFace(corners[a], corners[b], corners[c])
            builder.addFace(corners[a], corners[c], corners[d])
        }
        face(0, 3, 2, 1) // bottom, -z
        face(4, 5, 6, 7) // top, +z
        face(0, 1, 5, 4) // -y
        face(3, 7, 6, 2) // +y
        face(0, 4, 7, 3) // -x
        face(1, 2, 6, 5) // +x
    }

    /** A rectangle in the frame's plane, in local millimetres. */
    private data class LocalRect(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float) {
        val width: Float get() = maxX - minX
        val height: Float get() = maxY - minY

        fun inset(by: Float): LocalRect? {
            val rect = LocalRect(minX + by, maxX - by, minY + by, maxY - by)
            return rect.takeIf { it.width > 0f && it.height > 0f }
        }

        fun intersect(other: LocalRect): LocalRect? {
            val rect = LocalRect(
                maxOf(minX, other.minX),
                minOf(maxX, other.maxX),
                maxOf(minY, other.minY),
                minOf(maxY, other.maxY),
            )
            return rect.takeIf { it.width > 0f && it.height > 0f }
        }
    }

    /**
     * Extrudes a planar polygon into a closed prism: caps triangulated, sides
     * quad by quad, every face wound outwards. The polygon is re-oriented if it
     * was handed over the wrong way round, so callers only have to list their
     * corners in order.
     */
    private fun addPrism(
        builder: MeshSolidBuilder,
        frame: SnapFitFrame,
        points: List<Vec3>,
        direction: Vec3,
        length: Float,
    ) {
        if (points.size < 3 || !(length > 0f)) return
        val ordered = if (polygonNormal(points).dot(direction) < 0f) points.reversed() else points
        val extrusion = direction.normalized() ?: return
        val origin = ordered[0]
        val basisU = perpendicularTo(extrusion)
        val basisV = extrusion.cross(basisU)
        val flat = ordered.map { point ->
            val delta = point - origin
            Vec2(delta.dot(basisU), delta.dot(basisV))
        }
        val triangles = Triangulator.triangulate(flat)
        val shift = extrusion * length
        for (triangle in triangles) {
            // The cap at the far end faces along the extrusion and keeps the
            // polygon's own winding; the near cap is the same triangle turned
            // round.
            val (first, second, third) = triangle
            builder.addFace(
                frame.model(ordered[first] + shift),
                frame.model(ordered[second] + shift),
                frame.model(ordered[third] + shift),
            )
            builder.addFace(
                frame.model(ordered[first]),
                frame.model(ordered[third]),
                frame.model(ordered[second]),
            )
        }
        for (edge in ordered.indices) {
            val from = ordered[edge]
            val to = ordered[(edge + 1) % ordered.size]
            val fromTop = from + shift
            val toTop = to + shift
            builder.addFace(frame.model(from), frame.model(to), frame.model(toTop))
            builder.addFace(frame.model(from), frame.model(toTop), frame.model(fromTop))
        }
    }

    /** One triangle of a feature, from three model-space corners. */
    private fun MeshSolidBuilder.addFace(a: Vec3, b: Vec3, c: Vec3) {
        addTriangle(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z)
    }

    /** The unnormalised normal of a polygon, by Newell's method. */
    private fun polygonNormal(points: List<Vec3>): Vec3 {
        var x = 0f
        var y = 0f
        var z = 0f
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            x += (current.y - next.y) * (current.z + next.z)
            y += (current.z - next.z) * (current.x + next.x)
            z += (current.x - next.x) * (current.y + next.y)
        }
        return Vec3(x, y, z)
    }

    /** Any unit vector perpendicular to [direction]. */
    private fun perpendicularTo(direction: Vec3): Vec3 {
        val reference = when {
            abs(direction.x) <= abs(direction.y) && abs(direction.x) <= abs(direction.z) -> Vec3(1f, 0f, 0f)
            abs(direction.y) <= abs(direction.z) -> Vec3(0f, 1f, 0f)
            else -> Vec3(0f, 0f, 1f)
        }
        return reference.cross(direction).normalized() ?: Vec3(1f, 0f, 0f)
    }

    /** How far a mesh reaches along one of the plate's own axes. */
    fun extentAlongAxis(mesh: StlMesh, axis: ModelPlacement.Axis): ClosedFloatingPointRange<Float>? =
        extent(mesh, axisDirection(axis))

    /** How far a half reaches along one of the plate's own axes, in its own coordinates. */
    fun spanAlongAxis(half: SnapFitHalf, axis: ModelPlacement.Axis): ClosedFloatingPointRange<Float>? =
        span(half, axisDirection(axis))

    /**
     * Whether two halves, each carrying its own mating face, straddle one plane
     * in this order: the low half's material below its face, the high half's
     * above its own. False when they overlap - two parts side by side, or the
     * same part twice - which is a pair with no seam to put a joint on.
     */
    fun halvesMeet(lowHalf: SnapFitHalf, highHalf: SnapFitHalf): Boolean =
        lowHalf.faceMm <= highHalf.faceMm + PLANE_TOLERANCE_MM

    /**
     * The material the two halves share at their own mating faces, sampled on a
     * grid in the seam's own plane and reduced to the rim of that shared
     * cross-section.
     *
     * This is the contour the pad work already computes, read the other way
     * round: the pad is the cross-section inset, and the rim is its outline. A
     * joint placed on a rim point stands on material in BOTH halves - the beam
     * has something to root in and the mate has something to cut - which is why
     * the automatic spread uses it and not the face's bounding box: a boat
     * hull's cross-section is a thin band around a void, and the middle of that
     * box is air.
     */
    fun seamRim(
        beamHalf: SnapFitHalf,
        socketHalf: SnapFitHalf,
        direction: Vec3,
        side: Vec3,
        rise: Vec3,
        originMm: Vec3,
    ): SeamRim? {
        val beamCap = capTriangles(beamHalf, direction, side, rise, beamHalf.faceMm, originMm)
        val socketCap = capTriangles(socketHalf, direction, side, rise, socketHalf.faceMm, originMm)
        if (beamCap.isEmpty() || socketCap.isEmpty()) return null
        // Where the two cross-sections overlap at all: read off the cap
        // triangles themselves, never off the halves' bounding boxes - a side
        // cut's two halves do not share a bounding box at all, and yet their
        // mating faces are the same cross-section.
        val rect = LocalRect(
            maxOf(beamCap.minOf { it.minX }, socketCap.minOf { it.minX }),
            minOf(beamCap.maxOf { it.maxX }, socketCap.maxOf { it.maxX }),
            maxOf(beamCap.minOf { it.minY }, socketCap.minOf { it.minY }),
            minOf(beamCap.maxOf { it.maxY }, socketCap.maxOf { it.maxY }),
        )
        if (rect.width <= 0f || rect.height <= 0f) return null
        val count = kotlin.math.ceil(maxOf(rect.width, rect.height) / RIM_CELL_MM).toInt()
            .coerceIn(MIN_RIM_SAMPLES, MAX_RIM_SAMPLES)
        val beamCells = rasterize(beamCap, rect, count)
        val socketCells = rasterize(socketCap, rect, count)
        val shared = BooleanArray(count * count) { beamCells[it] && socketCells[it] }
        val cells = ArrayList<SeamPoint>()
        for (row in 0 until count) {
            for (column in 0 until count) {
                if (!shared[row * count + column]) continue
                val edge = row == 0 || column == 0 || row == count - 1 || column == count - 1 ||
                    !shared[(row - 1) * count + column] || !shared[(row + 1) * count + column] ||
                    !shared[row * count + column - 1] || !shared[row * count + column + 1]
                if (edge) {
                    cells += SeamPoint(
                        x = rect.minX + rect.width * (column + 0.5f) / count,
                        y = rect.minY + rect.height * (row + 0.5f) / count,
                    )
                }
            }
        }
        if (cells.isEmpty()) return null
        return SeamRim(originMm, side, rise, rect.width, rect.height, cells)
    }

    /**
     * The cap triangles of one half, as a grid of cells that any of them covers.
     * Rasterised rather than sampled: a real seam's face carries thousands of
     * triangles and a per-cell scan of all of them is what would make an
     * automatic spread feel slow.
     */
    private fun rasterize(cap: List<CapTriangle>, rect: LocalRect, samples: Int): BooleanArray {
        val cells = BooleanArray(samples * samples)
        if (cap.isEmpty()) return cells
        val stepX = rect.width / samples
        val stepY = rect.height / samples
        for (triangle in cap) {
            val lowColumn = ((triangle.minX - rect.minX) / stepX).toInt().coerceIn(0, samples - 1)
            val highColumn = ((triangle.maxX - rect.minX) / stepX).toInt().coerceIn(0, samples - 1)
            val lowRow = ((triangle.minY - rect.minY) / stepY).toInt().coerceIn(0, samples - 1)
            val highRow = ((triangle.maxY - rect.minY) / stepY).toInt().coerceIn(0, samples - 1)
            for (row in lowRow..highRow) {
                val y = rect.minY + stepY * (row + 0.5f)
                for (column in lowColumn..highColumn) {
                    val x = rect.minX + stepX * (column + 0.5f)
                    if (triangle.contains(x, y)) cells[row * samples + column] = true
                }
            }
        }
        return cells
    }

    /**
     * A right-handed frame across the seam: the unit assembly direction, and the
     * two unit axes across it. The same frame every joint on the seam is built
     * in, so a point placed on one joint's plane means the same place for the
     * next - which is what the automatic spread and the spacing guard need.
     */
    fun frameAxes(direction: Vec3): Triple<Vec3, Vec3, Vec3>? {
        val axis = direction.normalized() ?: return null
        val side = perpendicularTo(axis)
        return Triple(axis, side, axis.cross(side))
    }

    /** The unit direction of a plate axis: the assembly direction of a cut run on it. */
    fun axisDirection(axis: ModelPlacement.Axis): Vec3 = when (axis) {
        ModelPlacement.Axis.X -> Vec3(1f, 0f, 0f)
        ModelPlacement.Axis.Y -> Vec3(0f, 1f, 0f)
        ModelPlacement.Axis.Z -> Vec3(0f, 0f, 1f)
    }

    /** The span of a mesh's vertices along [direction]. */
    private fun extent(mesh: StlMesh, direction: Vec3): ClosedFloatingPointRange<Float>? {
        val vertices = mesh.interleavedVertices
        val count = mesh.triangleCount * 3
        if (count <= 0) return null
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        for (vertex in 0 until count) {
            val base = vertex * MeshSolidBuilder.FLOATS_PER_VERTEX
            val along = vertices[base] * direction.x + vertices[base + 1] * direction.y + vertices[base + 2] * direction.z
            if (along < low) low = along
            if (along > high) high = along
        }
        return if (low <= high) low..high else null
    }

    /** A point in the plane of a prism's cross-section, for the triangulator. */
    private class Vec2(val x: Float, val y: Float)

    /**
     * Ear clipping for a simple polygon given counter-clockwise. The joints are
     * six-gons with one reflex corner, which a triangle fan cannot handle; the
     * fan is only the last-resort fallback for a polygon this rejects.
     */
    private object Triangulator {
        fun triangulate(points: List<Vec2>): List<Triple<Int, Int, Int>> {
            val count = points.size
            if (count < 3) return emptyList()
            val remaining = MutableList(count) { it }
            val triangles = ArrayList<Triple<Int, Int, Int>>(count - 2)
            var guard = 0
            while (remaining.size > 3 && guard < count * count) {
                guard++
                var clipped = false
                for (position in remaining.indices) {
                    val previous = remaining[(position + remaining.size - 1) % remaining.size]
                    val current = remaining[position]
                    val next = remaining[(position + 1) % remaining.size]
                    if (turn(points[previous], points[current], points[next]) <= EPSILON) continue
                    if (contains(points, remaining, previous, current, next)) continue
                    triangles.add(Triple(previous, current, next))
                    remaining.removeAt(position)
                    clipped = true
                    break
                }
                if (!clipped) return fan(count)
            }
            if (remaining.size == 3) triangles.add(Triple(remaining[0], remaining[1], remaining[2]))
            return triangles
        }

        private fun fan(count: Int): List<Triple<Int, Int, Int>> =
            (1 until count - 1).map { Triple(0, it, it + 1) }

        private fun turn(a: Vec2, b: Vec2, c: Vec2): Float =
            (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

        private fun contains(points: List<Vec2>, remaining: List<Int>, a: Int, b: Int, c: Int): Boolean {
            for (index in remaining) {
                if (index == a || index == b || index == c) continue
                val point = points[index]
                if (turn(points[a], points[b], point) >= -EPSILON &&
                    turn(points[b], points[c], point) >= -EPSILON &&
                    turn(points[c], points[a], point) >= -EPSILON
                ) {
                    return true
                }
            }
            return false
        }

        private const val EPSILON = 1e-6f
    }

    private const val UNION_NAME = "snap-fit joint"
    private const val SOCKET_NAME = "snap-fit socket"
    private const val REGISTRATION_NAME = "snap-fit registration"
    private const val RECESS_NAME = "snap-fit recess"
    private const val PLANE_TOLERANCE_MM = 1e-3f

    /** Below this, a ceiling and the value it clamped are the same number. */
    private const val CLAMP_EPSILON_MM = 1e-3f

    /** A lead-in this steep is square: the hook has no ramp of its own. */
    const val SQUARE_LEAD_IN_DEG = 90f

    /** The socket mouth's chamfer: 45 degrees to the axis, the FDM overhang limit. */
    const val MOUTH_CHAMFER_DEG = 45f

    /** One pawl, up to this many teeth: more than three and the beam is a saw. */
    const val MAX_BARBS = 3

    /** What the panel calls the deepest catch: the engagement the joint is designed for. */
    const val SEATED_CLICK = "seated - tight"

    /** The deepest catch has to sit this far inside the pocket to be a catch at all. */
    private const val MIN_DEEPEST_CATCH_MM = 0.4f

    /** The tightest clearance worth cutting: below this FDM cannot resolve the fit. */
    const val MIN_CLEARANCE_MM = 0.05f

    /** One decimal place, locale-independent: these sentences land in the panel. */
    private fun millimetres(value: Float): String =
        String.format(java.util.Locale.ROOT, "%.1f", value)

    // The ladder's thresholds. A pad wants most of its footprint on material;
    // a key is small enough to take a little less. Five samples an axis is
    // enough to tell a solid cross-section from a hollow one and cheap enough
    // to run on every preview.
    private const val PAD_COVERAGE = 0.6f
    private const val KEY_COVERAGE = 0.5f
    private const val COVERAGE_SAMPLES = 5

    // The rim's sampling: about a millimetre a cell, and never so fine that a
    // big seam's grid costs more than the booleans it exists to place.
    private const val RIM_CELL_MM = 1f
    private const val MIN_RIM_SAMPLES = 12
    private const val MAX_RIM_SAMPLES = 64

    /** How close to its own face a triangle has to be to count as cap. */
    private const val CAP_PLANE_TOLERANCE_MM = 0.01f
    private const val CAP_AREA_EPSILON = 1e-9f
    private const val CAP_EDGE_EPSILON = 1e-6f

    // Every feature is a share of the part, and the parameters above are its
    // ceiling: an eighth of the face for the key, a fifth for the beam's width,
    // an eighth for its thickness, a quarter of the mate's depth for its reach.
    private const val KEY_FRACTION = 0.12f
    private const val BEAM_WIDTH_FRACTION = 0.18f
    private const val BEAM_THICKNESS_FRACTION = 0.12f
    private const val ROOT_FRACTION = 0.3f

    // The step is shallower still: a face-level offset, not a tongue.
    // The step's own depth, in millimetres: an absolute step, at least half a
    // millimetre and never more than one and a half, with the material able to
    // pull it shorter.
    private const val STEP_DEPTH_MIN_MM = 0.5f
    private const val STEP_DEPTH_MAX_MM = 1.5f
    private const val STEP_DEPTH_MATERIAL_SHARE = 0.3f
    private const val STEP_RIM_FRACTION = 0.08f
    private const val STEP_ROOT_FRACTION = 0.2f
    private val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()
}

/**
 * How much joint a seam can carry, fullest first.
 *
 * A ladder rather than a yes/no: a complicated seam - a hollow hull, a thin
 * curved wall - gets the lightest joint that still attaches, and a heavier one
 * can come later. This is a stepping stone, not a verdict on the model, and the
 * user can always ask for the full joint anyway; nothing here blocks him.
 */
enum class SnapFitRung(val label: String) {
    /** Whole-seam pad and matching recess, square key, and the ramped cantilever. */
    FULL("Full joint"),

    /**
     * The key and the cantilever without the pad: often the only thing a thin
     * curved wall can take.
     */
    SIMPLE("Simplified joint"),

    /** The bare ramped cantilever, shrunk to whatever material there is. */
    MINIMAL("Minimal joint"),
}

/**
 * Which side of the beam the barb stands on.
 *
 * [SAME] is today's joint and the default for every joint on a seam: the barb
 * is on the frame's positive rise side. [OPPOSITE] mirrors the beam and its
 * pocket about the beam's own centreline - the hook points the other way - and
 * a pair that mixes the two is locked against sliding along the seam in both
 * directions, one hook blocking each way, while every joint still assembles
 * along the same axis. Mirroring is the only thing this changes: the pull-apart
 * catch stays square and faces the same way, so an opposite joint holds exactly
 * as a same one does.
 */
enum class SnapFacing(val label: String, val sign: Float) {
    SAME("Same way", 1f),
    OPPOSITE("Other way", -1f);

    fun flipped(): SnapFacing = if (this == SAME) OPPOSITE else SAME
}

/**
 * The Loose/Tight pair: two steps that differ by a clearance FDM can still
 * resolve, so a seam can carry one joint that goes together easily and one that
 * goes together tight - which is what makes a two-joint seam hold without a
 * hammer. [stepMm] is how much TIGHTER than the parameters' own clearances this
 * step is; [LOOSE] is the parameters as they are.
 */
enum class SnapTightness(val label: String, val stepMm: Float) {
    LOOSE("Loose", 0f),
    TIGHT("Tight", TIGHTNESS_STEP_MM),
}

/**
 * The whole difference between the two tightness steps, in millimetres of
 * clearance. 0.06 mm is about a third of a 0.4 mm nozzle's own width and near
 * the limit FDM resolves, which is the point: any more and the tight joint
 * stops going together at all.
 */
private const val TIGHTNESS_STEP_MM = 0.06f

/**
 * One engagement depth of a multi-tooth beam, as the panel names it.
 *
 * [gapMm] is how far apart the two mating faces are held when this tooth is the
 * one the pawl has caught: the tooth nearest the tip catches first, at the
 * largest gap, and the deepest catch seats the halves. [label] is what the
 * panel says, because a joint that holds at three depths with no indication of
 * which one it is holding at is the failure this design has to avoid.
 */
data class SnapClick(
    /** 1 is the first tooth the pawl meets, counting in from the beam's tip. */
    val order: Int,
    val gapMm: Float,
    val label: String,
)

/**
 * One half of a split, as the joint builder needs it: the half as it sits on
 * the plate, the plane of its mating face in the half's OWN coordinates, and
 * the placement that maps one to the other.
 *
 * The face is carried rather than measured off the mesh, because a half that
 * already carries a joint no longer ends at its mating face - the beam's root
 * pushed the beam half's bound up and the pocket pulled the mate's down - and
 * because measuring on the plate would read the packer's separation as part of
 * the joint. The split knows the plane in the half's own coordinates, and this
 * is where it travels.
 */
data class SnapFitHalf(
    /** The half as placed on the plate: the mesh the booleans and the measurements run over. */
    val mesh: StlMesh,
    /**
     * The mating face along the assembly direction, in the half's own
     * coordinates. [SnapJoint] negates it when the joint is walked the other
     * way, so positive is always the side the beam grows towards.
     */
    val faceMm: Float,
    /** The half's own coordinates to the plate's. */
    val transform: StlSliceTransform,
) {
    init {
        require(faceMm.isFinite()) { "A half's mating face must be a finite offset" }
    }

    /** True when this half's own coordinates already are the plate's. */
    val isInPlace: Boolean
        get() = transform.translationXmm == 0.0 && transform.translationYmm == 0.0 &&
            transform.translationZmm == 0.0 &&
            transform.linear.indices.all { abs(transform.linear[it] - IN_PLACE[it]) < 1e-9 }

    /** [pointMm], in this half's own coordinates, or null when [transform] has no inverse. */
    fun toLocal(pointMm: Vec3): Vec3? {
        val inverse = inverse() ?: return null
        val x = pointMm.x - transform.translationXmm
        val y = pointMm.y - transform.translationYmm
        val z = pointMm.z - transform.translationZmm
        return Vec3(
            (inverse[0] * x + inverse[1] * y + inverse[2] * z).toFloat(),
            (inverse[3] * x + inverse[4] * y + inverse[5] * z).toFloat(),
            (inverse[6] * x + inverse[7] * y + inverse[8] * z).toFloat(),
        ).takeIf { it.isFinite() }
    }

    /** [pointMm], given in this half's own coordinates, as it sits on the plate. */
    fun toPlate(pointMm: Vec3): Vec3 {
        val m = transform.linear
        return Vec3(
            (m[0] * pointMm.x + m[1] * pointMm.y + m[2] * pointMm.z + transform.translationXmm).toFloat(),
            (m[3] * pointMm.x + m[4] * pointMm.y + m[5] * pointMm.z + transform.translationYmm).toFloat(),
            (m[6] * pointMm.x + m[7] * pointMm.y + m[8] * pointMm.z + transform.translationZmm).toFloat(),
        )
    }

    /**
     * A reader of this half's own coordinates along [localDirection] for a point
     * given in plate coordinates: the inverse of the placement applied to the
     * direction once, so walking a large placed mesh costs one dot per vertex
     * and no allocation. Null when the placement has no inverse.
     */
    fun probe(localDirection: Vec3): ((Float, Float, Float) -> Float)? {
        val inverse = inverse() ?: return null
        // local·d = (p - t)·(invᵀ d)
        val dx = (inverse[0] * localDirection.x + inverse[3] * localDirection.y + inverse[6] * localDirection.z).toFloat()
        val dy = (inverse[1] * localDirection.x + inverse[4] * localDirection.y + inverse[7] * localDirection.z).toFloat()
        val dz = (inverse[2] * localDirection.x + inverse[5] * localDirection.y + inverse[8] * localDirection.z).toFloat()
        val tx = transform.translationXmm.toFloat()
        val ty = transform.translationYmm.toFloat()
        val tz = transform.translationZmm.toFloat()
        return { x, y, z -> (x - tx) * dx + (y - ty) * dy + (z - tz) * dz }
    }

    /** [mesh], given in this half's own coordinates, as it sits on the plate. */
    fun place(mesh: StlMesh, name: String): StlMesh {
        if (isInPlace) return mesh.copy(displayName = name)
        val builder = MeshSolidBuilder(name)
        val vertices = mesh.interleavedVertices
        val points = FloatArray(9)
        for (triangle in 0 until mesh.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            for (corner in 0 until 3) {
                val at = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
                val plate = toPlate(Vec3(vertices[at], vertices[at + 1], vertices[at + 2]))
                points[corner * 3] = plate.x
                points[corner * 3 + 1] = plate.y
                points[corner * 3 + 2] = plate.z
            }
            builder.addTriangle(
                points[0], points[1], points[2],
                points[3], points[4], points[5],
                points[6], points[7], points[8],
            )
        }
        return builder.build()
    }

    /**
     * [mesh], given on the plate, in this half's own coordinates. The two
     * halves of a split share those coordinates, which is what lets a shape
     * contoured on one half be the matching shape on the other.
     */
    fun unplace(mesh: StlMesh, name: String): StlMesh? {
        if (isInPlace) return mesh.copy(displayName = name)
        val inverse = inverse() ?: return null
        val builder = MeshSolidBuilder(name)
        val vertices = mesh.interleavedVertices
        val points = FloatArray(9)
        for (triangle in 0 until mesh.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            for (corner in 0 until 3) {
                val at = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
                val x = vertices[at] - transform.translationXmm
                val y = vertices[at + 1] - transform.translationYmm
                val z = vertices[at + 2] - transform.translationZmm
                points[corner * 3] = (inverse[0] * x + inverse[1] * y + inverse[2] * z).toFloat()
                points[corner * 3 + 1] = (inverse[3] * x + inverse[4] * y + inverse[5] * z).toFloat()
                points[corner * 3 + 2] = (inverse[6] * x + inverse[7] * y + inverse[8] * z).toFloat()
            }
            builder.addTriangle(
                points[0], points[1], points[2],
                points[3], points[4], points[5],
                points[6], points[7], points[8],
            )
        }
        return builder.build()
    }

    /** [direction], given on the plate, in this half's own coordinates. */
    fun toLocalDirection(direction: Vec3): Vec3 {
        val inverse = inverse() ?: return direction
        return Vec3(
            (inverse[0] * direction.x + inverse[1] * direction.y + inverse[2] * direction.z).toFloat(),
            (inverse[3] * direction.x + inverse[4] * direction.y + inverse[5] * direction.z).toFloat(),
            (inverse[6] * direction.x + inverse[7] * direction.y + inverse[8] * direction.z).toFloat(),
        ).normalized() ?: direction
    }

    /** The inverse of the linear part, row-major, or null when there is none. */
    private fun inverse(): DoubleArray? {
        val m = transform.linear
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])
        if (!det.isFinite() || abs(det) < 1e-12) return null
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
            (m[5] * m[6] - m[3] * m[8]) / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
            (m[3] * m[7] - m[4] * m[6]) / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det,
        )
    }

    companion object {
        private val IN_PLACE = ModelPlacement.IDENTITY

        /** A half whose own coordinates already are the plate's - an unplaced mesh. */
        fun inPlace(mesh: StlMesh, faceMm: Float): SnapFitHalf =
            SnapFitHalf(mesh, faceMm, StlSliceTransform(IN_PLACE, 0.0, 0.0, 0.0))

        /**
         * [mesh], as [placed] has it: the transform the placed copy carries
         * when it was made by [ModelPlacement.transformed], and otherwise the
         * translation its bounds imply - which is what a fixture that placed
         * the mesh by hand means. The mating face stays in [mesh]'s own
         * coordinates.
         */
        fun placed(mesh: StlMesh, placed: StlMesh, faceMm: Float): SnapFitHalf {
            val transform = placed.slicingTransform ?: StlSliceTransform(
                linear = IN_PLACE,
                translationXmm = (placed.bounds.minX - mesh.bounds.minX).toDouble(),
                translationYmm = (placed.bounds.minY - mesh.bounds.minY).toDouble(),
                translationZmm = (placed.bounds.minZ - mesh.bounds.minZ).toDouble(),
            )
            return SnapFitHalf(placed, faceMm, transform)
        }
    }
}

/**
 * The dimensions a joint was actually built with, in model millimetres: the
 * scaled parameters after the beam was clamped to the material available and
 * the lip's run was derived from the ramp angle.
 */
data class SnapFitDimensions(
    val scale: Float,
    val keySizeMm: Float,
    val keyHeightMm: Float,
    val keyClearanceMm: Float,
    /** The key's centre, along the in-plane side axis from the anchor. */
    val keyOffsetMm: Float,
    val beamLengthMm: Float,
    val beamWidthMm: Float,
    val beamThicknessMm: Float,
    /** How deep the beam roots into its own half, below the mating plane. */
    val beamRootMm: Float,
    val lipDepthMm: Float,
    /** The ramp's run along the assembly axis: the lip's depth at that angle. */
    val lipRunMm: Float,
    val lipClearanceMm: Float,
    val matingClearanceMm: Float,
    val rampAngleDeg: Float,
    /** The beam's free end, along the assembly axis from the mating plane. */
    val beamTipMm: Float,
    /** The lip's square catch face, along the assembly axis from the plane. */
    val lipBackMm: Float,
    /** How proud the whole-seam registration boss stands of the mating face. */
    val stepDepthMm: Float,
    /** How deep the boss roots into its own half, below the mating face. */
    val stepRootMm: Float,
    /** The rim the step leaves around the seam. */
    val stepRimMm: Float,
    /** The clearance between the boss and its recess, and under it. */
    val stepClearanceMm: Float,
    /**
     * The bending strain at the beam's root at full deflection, in per cent:
     * eps = 3 h d / (2 L^2), the closed form for a rectangular cantilever of
     * thickness h, free length L, deflected by d (the lip depth). A readout,
     * never a gate.
     */
    val rootStrainPercent: Float,
    /** How many teeth the beam actually carries, after the beam's length had its say. */
    val barbCount: Int,
    /** The teeth's pitch along the axis: the lead-in's run plus a lip-depth flat. */
    val toothPitchMm: Float,
    /**
     * Each tooth's square catch face, along the axis from the mating plane,
     * tip-most first. The last is the [pawlMm] the mate's step sits in front of.
     */
    val teethCatchMm: List<Float>,
    /** The mate's single step, along the axis from the mating plane. */
    val pawlMm: Float,
    /** The gap between the mating faces at each click, in the same order as [teethCatchMm]. */
    val clickGapMm: List<Float>,
    /**
     * The bending the pawl puts on the beam at the worst case - every tooth's
     * lip depth together - which is what the strain readout is computed from.
     */
    val deflectionMm: Float,
    /** How far below the beam the pocket's floor was dropped so the beam can bend. */
    val deflectionRoomMm: Float,
    /** The socket mouth's chamfer, on the axis: 0 when there is none. */
    val mouthChamferMm: Float,
    /** The chamfer's angle to the axis, or 0 when there is no chamfer. */
    val mouthChamferAngleDeg: Float,
    /** Which side of the beam the barb stands on. */
    val facing: SnapFacing,
)

/** One dimension the material clamped short of what the user asked for. */
data class SnapFitClamp(
    val label: String,
    val askedMm: Float,
    val actualMm: Float,
    /** The whole sentence the panel shows. */
    val reason: String,
)

/**
 * Where the joint was put: the anchor dropped onto the mating plane, and the
 * right-handed frame the features are laid out in. Local x is the in-plane side
 * axis, local y the in-plane rise, local z the assembly axis with zero on the
 * mating plane; [model] and [local] convert either way.
 */
data class SnapFitFrame(
    val originMm: Vec3,
    /** The assembly direction, from the beam's half to the other one. */
    val axis: Vec3,
    val side: Vec3,
    val rise: Vec3,
    /** Where the mating plane sits along [axis], in model coordinates. */
    val planeOffsetMm: Float,
) {
    fun model(local: Vec3): Vec3 = originMm + side * local.x + rise * local.y + axis * local.z

    fun local(point: Vec3): Vec3 {
        val delta = point - originMm
        return Vec3(delta.dot(side), delta.dot(rise), delta.dot(axis))
    }
}

/** The solids, ready for [MeshBoolean], and the geometry they were built to. */
data class SnapFitJoint(
    /** Union this into the half on the low side of the plane. */
    val unionSolid: StlMesh,
    /** Subtract this from the half on the high side. */
    val subtractSolid: StlMesh,
    /**
     * The whole-seam registration boss, unioned into the beam's half after
     * [unionSolid]: it is a solid of its own because a mesh holding two
     * overlapping shells is not manifold, and the engine is what fuses them.
     */
    val registrationSolid: StlMesh,
    /** The matching recess, subtracted from the socket half after [subtractSolid]. */
    val registrationRecess: StlMesh,
    /**
     * The beam half's own frame, on the plate: the plane is that half's mating
     * face, and local z is the assembly direction. [socketFrame] is the mate's,
     * with the same local z sense; the two differ by nothing but each half's
     * placement, so the features line up when the halves are pushed together.
     */
    val frame: SnapFitFrame,
    val socketFrame: SnapFitFrame,
    val dimensions: SnapFitDimensions,
    /** Which rung of the ladder this joint is: what it actually carries. */
    val rung: SnapFitRung,
    /**
     * Why the fuller rungs were dropped, in the user's terms - "the seam is
     * hollow where the whole-seam pad would sit; key and cantilever only" - or
     * null when nothing was dropped, or when a rung was pinned by the caller.
     */
    val rungReason: String?,
    /** Every dimension the material clamped, with the sentence that says why. */
    val clamps: List<SnapFitClamp>,
    /**
     * Every engagement depth this beam offers, in the order the pawl meets
     * them, with the panel's own name for each. One entry for a one-tooth
     * beam, whose only click is the seated one.
     */
    val clicks: List<SnapClick> = emptyList(),
) {
    /** The click the joint is designed to be used at: the deepest catch. */
    val seatedClick: SnapClick? get() = clicks.lastOrNull()

    /** The one line the panel shows for where the joint is holding. */
    val clickSummary: String
        get() = seatedClick?.let { seated ->
            seated.label + " (" + millimetres(seated.gapMm) + " mm)" +
                if (clicks.size > 1) {
                    "; clicks at " + clicks.joinToString(", ") { millimetres(it.gapMm) + " mm" }
                } else {
                    ""
                }
        } ?: "one click"

    private fun millimetres(value: Float): String =
        String.format(java.util.Locale.ROOT, "%.2f", value)
}

/**
 * The joint's starting dimensions, before the scale. Every one of them is
 * multiplied by the caller's scale factor, clearances included, so the whole
 * joint follows one slider.
 */
data class SnapFitParameters(
    /** The square key's side. */
    val keySizeMm: Float = 5f,
    /** How far the key stands out of the mating face. */
    val keyHeightMm: Float = 2f,
    /** Clearance around the key in its socket. */
    val keyClearanceMm: Float = 0.2f,
    /** The beam's reach past the mating face, before clamping. */
    val beamLengthMm: Float = 8f,
    val beamWidthMm: Float = 3f,
    val beamThicknessMm: Float = 1.4f,
    /** How deep the beam roots inside its own half. */
    val beamRootMm: Float = 2f,
    /** How far the barb stands out sideways from the beam. */
    val lipDepthMm: Float = 0.8f,
    /** Clearance around the beam and barb in the pocket. */
    val lipClearanceMm: Float = 0.2f,
    /**
     * How far the pocket is sunk below the mating plane, which relieves the
     * mate's mating face at the joint's mouth.
     */
    val matingClearanceMm: Float = 0.2f,
    /** The material left between the beam and the key. */
    val keyGapMm: Float = 1f,
    /**
     * The barb's lead-in angle; 45 degrees is the printable default. A value of
     * exactly 90 is a SQUARE lead-in: the hook has no ramp of its own, and what
     * cams it in is the socket mouth's own chamfer ([socketRamp]).
     */
    val rampAngleDeg: Float = 45f,
    /**
     * How many teeth the beam carries: 1 to 3, one pawl on the mate. The pitch
     * is the lead-in's run plus a lip-depth flat, and the deepest catch is the
     * pawl, so the joint holds at [SnapFitJoint.clicks]. One is the default and
     * is exactly the joint that was built before this parameter existed.
     */
    val barbCount: Int = 1,
    /** Which side of the beam the barb stands on; the default is today's joint. */
    val facing: SnapFacing = SnapFacing.SAME,
    /**
     * The socket-mouth chamfer: a lead-in ramp in the hole instead of on the
     * hook, at [mouthChamferDeg] to the assembly axis, so a square-faced hook
     * cams in and a joint can face either way. Off by default.
     */
    val socketRamp: Boolean = false,
    /** The mouth chamfer's angle to the axis: 45 degrees is the printable limit. */
    val mouthChamferDeg: Float = SnapFit.MOUTH_CHAMFER_DEG,
    /**
     * How far the whole-seam registration step stands proud of the mating
     * face, as a ceiling: the step is sized from the part like every other
     * feature, so a small model gets a shallow rebate rather than this value.
     */
    val stepDepthMm: Float = 1f,
    /** The rim the step leaves around the seam, as a ceiling in millimetres. */
    val stepRimMm: Float = 2f,
    /**
     * The hook length the user typed, in millimetres, or null to take the
     * scaled parameter. The scale still sets everything else; these override
     * their own dimension and are clamped by the material like any other.
     */
    val beamLengthOverrideMm: Float? = null,
    /** The hook thickness the user typed, in millimetres, or null. */
    val beamThicknessOverrideMm: Float? = null,
    /** The lip depth the user typed, in millimetres, or null. */
    val lipDepthOverrideMm: Float? = null,
) {
    /**
     * The same joint with every fit clearance tightened by [stepMm] millimetres,
     * floored at what FDM can resolve. This is the whole of the Loose/Tight
     * stepping: the hook's length, thickness, lip, pitch and every angle stay
     * exactly as they were, so a tight joint is the loose one with less room in
     * its sockets and no other difference at all.
     */
    fun tightenedBy(stepMm: Float): SnapFitParameters {
        if (!stepMm.isFinite() || stepMm <= 0f) return this
        return copy(
            lipClearanceMm = (lipClearanceMm - stepMm).coerceAtLeast(SnapFit.MIN_CLEARANCE_MM),
            keyClearanceMm = (keyClearanceMm - stepMm).coerceAtLeast(SnapFit.MIN_CLEARANCE_MM),
        )
    }

    internal fun isUsable(): Boolean {
        val lengths = listOf(
            keySizeMm, keyHeightMm, keyClearanceMm, beamLengthMm, beamWidthMm, beamThicknessMm,
            beamRootMm, lipDepthMm, lipClearanceMm, matingClearanceMm, keyGapMm,
            stepDepthMm, stepRimMm,
        )
        val overrides = listOfNotNull(beamLengthOverrideMm, beamThicknessOverrideMm, lipDepthOverrideMm)
        return lengths.all { it.isFinite() && it > 0f } &&
            overrides.all { it.isFinite() && it > 0f } &&
            rampAngleDeg.isFinite() && rampAngleDeg > 1f && rampAngleDeg <= SnapFit.SQUARE_LEAD_IN_DEG &&
            barbCount >= 1 && mouthChamferDeg.isFinite() && mouthChamferDeg in 1f..SnapFit.MOUTH_CHAMFER_DEG
    }
}

/** A point or direction in build-plate millimetres. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3): Vec3 = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3? {
        val size = length()
        if (!size.isFinite() || size <= 0f) return null
        return Vec3(x / size, y / size, z / size)
    }

    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)

    operator fun times(factor: Float): Vec3 = Vec3(x * factor, y * factor, z * factor)
}
