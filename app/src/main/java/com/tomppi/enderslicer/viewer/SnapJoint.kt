package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import java.util.Locale

/**
 * Which half the user wants the beam in, and what that choice produces.
 *
 * [SnapFit.generate] builds the beam into whichever half it is handed as the
 * beam's own, along the direction it is handed: the beam sits in the half the
 * parts are pushed together from, because that is the side the barb cams in
 * from. Asking for the other half therefore means walking the assembly axis the
 * other way, and from there the pair is the other way round too - and so is each
 * half's own mating face, which is always read from the end the beam is coming
 * from. Both orders produce a joint; which one a given pair can hold depends on
 * how much material each half has, and this is where that is decided - and, when
 * neither works, explained in the half's own terms rather than as a bare
 * refusal.
 *
 * Pure: mesh spans and arithmetic, no UI, no engine, no state, no I/O.
 */
object SnapJoint {
    /**
     * One half's refusal: which half, and what about it. [summary] is the one
     * line the panel and the status message show.
     */
    data class Failure(val half: JointHalf, val reason: String) {
        val summary: String get() = half.label + " " + reason
    }

    /** Which half of a split a joint is being asked about. */
    enum class JointHalf(val label: String) {
        LOW("The lower half"),
        HIGH("The upper half"),
    }

    /** Where a built joint goes, and which half it went into. */
    data class Placement(
        val joint: SnapFitJoint,
        /** The half the beam is unioned into. */
        val beamMesh: StlMesh,
        /** The half the pocket is cut from. */
        val socketMesh: StlMesh,
        /** False when the joint was built for the other half than the one chosen. */
        val chosenHalfCarriesBeam: Boolean,
    )

    /** What a build came out as. */
    sealed interface Either {
        data class Placed(val placement: Placement) : Either

        /** Built, but into the other half; [why] says what the chosen half lacks. */
        data class Flipped(val placement: Placement, val why: Failure) : Either

        data class Failed(val failure: Failure) : Either
    }

    /**
     * Builds the joint for the chosen beam half, or says why that half cannot
     * take one.
     *
     * The chosen half is tried first, as the task asks; only when it cannot
     * hold the beam at this scale is the other half tried, and then the result
     * is reported as a flip rather than passed off as what was asked for. When
     * both fail, the refusal names the half and the millimetres.
     *
     * [anchorMm] is the tapped point in the two halves' own coordinates - the
     * same coordinates their mating faces are carried in - so nothing here
     * depends on where the plate's packer put them.
     */
    fun build(
        axis: ModelPlacement.Axis,
        anchorMm: Vec3,
        scale: Float,
        lowHalf: SnapFitHalf,
        highHalf: SnapFitHalf,
        beamHalf: JointHalf,
        parameters: SnapFitParameters = SnapFitParameters(),
        /** The rung the user pinned, or null to let the material decide. */
        requested: SnapFitRung? = null,
        /**
         * Which joint to build. The cantilever walks the rung ladder; the wall
         * lip has one shape and answers with its own requirements when the
         * material fails one of them, rather than dropping to another style.
         */
        style: SnapJointStyle = SnapJointStyle.CANTILEVER,
        /**
         * The wall lip's cuts, in the halves' own coordinates: slits through the
         * lip half's ring where the user tapped. Empty for every other joint.
         */
        cuts: List<Vec3> = emptyList(),
        /**
         * The direction each cut's slit runs in, lined up with [cuts]: a stroke
         * across the wall hands its own slit a direction, and anything absent or
         * degenerate leaves that cut the automatic inward bisector.
         */
        cutDirections: List<Vec3> = emptyList(),
        /**
         * The bundled library shape's loaded pair, for [SnapJointStyle.LIBRARY_HOOK].
         * The caller loads it - the pieces are megabytes from the app's assets -
         * and hands it here, so the builder itself stays arithmetic over meshes.
         */
        library: SnapLibraryPair? = null,
    ): Either {
        val axisDirection = SnapFit.axisDirection(axis)
        // The two halves meet when their own mating faces agree: each carries
        // its own side of one plane. Two parts side by side - or the same part
        // handed over twice - do not, and have no seam to put a joint on.
        if (!SnapFit.halvesMeet(lowHalf, highHalf)) {
            return Either.Failed(
                Failure(
                    JointHalf.LOW,
                    "cannot carry a joint: the two parts lie side by side along " + axis.name +
                        ", not across it (" + describe(lowHalf.mesh, axis) + " and " + describe(highHalf.mesh, axis) +
                        " of material), so there is no seam to put one on.",
                ),
            )
        }
        val chosen = join(
            axisDirection, beamHalf, anchorMm, scale, lowHalf, highHalf, parameters, requested, style,
            cuts, cutDirections, library,
        )
        if (chosen is Attempt.Built) {
            return Either.Placed(placementOf(chosen.joint, lowHalf, highHalf, beamHalf, true))
        }
        val otherHalf = if (beamHalf == JointHalf.LOW) JointHalf.HIGH else JointHalf.LOW
        val flipped = join(
            axisDirection, otherHalf, anchorMm, scale, lowHalf, highHalf, parameters, requested, style,
            cuts, cutDirections, library,
        )
        if (flipped is Attempt.Built) {
            // Which half the sentence names. The chosen round roots the beam in the chosen
            // half and reaches into the mate, so the material can refuse it in two places:
            // the chosen half's own depth behind its face (the beam's root) or the mate's
            // depth beyond its own (the beam's reach and its pocket). This used to assert the
            // first whatever had happened, so a thin MATE was reported as the chosen half
            // having too little material - and since the flipped joint is built into that very
            // mate, the sentence said the opposite of what had just been done.
            val short = shortHalfForFlip(axis, scale, lowHalf, highHalf, beamHalf, parameters)
            val reason = when (short) {
                beamHalf -> "has too little material for the beam at this scale; the joint was " +
                    "built into the other half instead"
                null -> "cannot take the beam with this mate at this scale; the joint was built " +
                    "into the other half instead"
                else -> "has too little material for the beam's pocket at this scale; the beam " +
                    "was built into it instead"
            }
            return Either.Flipped(
                placementOf(flipped.joint, lowHalf, highHalf, otherHalf, false),
                Failure(short ?: beamHalf, reason),
            )
        }
        // The lip measures its own requirements and says which one failed; the
        // cantilever's sentence is the material's, measured the same way it always
        // was, and only when the generator produced nothing at all.
        val refused = (chosen as? Attempt.Refused)?.reason.orEmpty()
        return Either.Failed(
            Failure(
                beamHalf,
                refused.ifBlank { reasonFor(axis, scale, lowHalf, highHalf, beamHalf, parameters) },
            ),
        )
    }

    /** One try at the joint: the geometry, or the reason this style's own rules refused it. */
    private sealed interface Attempt {
        data class Built(val joint: SnapFitJoint) : Attempt

        /** Blank for the cantilever, whose refusal is measured by [reasonFor]. */
        data class Refused(val reason: String) : Attempt
    }

    /**
     * One try at the joint, walking [axisDirection] when the beam goes in the
     * low half and the reverse of it when the beam goes in the high one.
     *
     * The direction decides which end each half is read from, so each half's
     * mating face is negated with it: positive is always the side the beam
     * grows towards. Every geometric claim in [SnapFit] still holds: the beam
     * lies along the direction it was given, roots in its own half and reaches
     * into the mate's.
     */
    private fun join(
        axisDirection: Vec3,
        beamHalf: JointHalf,
        anchorMm: Vec3,
        scale: Float,
        lowHalf: SnapFitHalf,
        highHalf: SnapFitHalf,
        parameters: SnapFitParameters,
        requested: SnapFitRung?,
        style: SnapJointStyle,
        cuts: List<Vec3>,
        cutDirections: List<Vec3>,
        library: SnapLibraryPair?,
    ): Attempt {
        val forward = beamHalf == JointHalf.LOW
        val sign = if (forward) 1f else -1f
        val beam = if (forward) lowHalf else highHalf
        val socket = if (forward) highHalf else lowHalf
        val directedAxis = if (forward) axisDirection else axisDirection * -1f
        val beamFit = beam.copy(faceMm = beam.faceMm * sign)
        val socketFit = socket.copy(faceMm = socket.faceMm * sign)
        if (style == SnapJointStyle.LIBRARY_HOOK) {
            // The library pair is placed, not fitted: its hook and its catch are
            // one author's assembly, in register already, so the chosen half
            // always takes the hook and the material has no say. The RAW halves
            // go over - this style reads each half's own mating face the way the
            // split recorded it, not the direction-negated copy the two designed
            // styles walk.
            val pair = library ?: return Attempt.Refused(
                "is a library shape, and none is loaded: pick one of the eight shapes in the " +
                    "panel, and the app will read it out of its own assets.",
            )
            return when (
                val placed = SnapLibrary.build(
                    pair = pair,
                    axisDirection = axisDirection,
                    anchorMm = anchorMm,
                    scale = scale,
                    hookHalf = if (forward) lowHalf else highHalf,
                    mateHalf = if (forward) highHalf else lowHalf,
                    hookOnLowHalf = forward,
                )
            ) {
                is SnapLibrary.Outcome.Built -> Attempt.Built(placed.joint)
                // The place is too short or too shallow for the piece: refused
                // here, in the build, so the preview, the ghost, the row and
                // Apply all answer with the same sentence and the same numbers.
                is SnapLibrary.Outcome.Refused -> Attempt.Refused(placed.reason)
            }
        }
        return when (style) {
            SnapJointStyle.CANTILEVER -> SnapFit.generate(
                axis = directedAxis,
                anchorMm = anchorMm,
                scale = scale,
                beamHalf = beamFit,
                socketHalf = socketFit,
                parameters = parameters,
                requested = requested,
            )?.let { Attempt.Built(it) } ?: Attempt.Refused("")

            SnapJointStyle.WALL_LIP -> when (
                val lip = SnapLip.buildRing(
                    axis = directedAxis,
                    anchorMm = anchorMm,
                    scale = scale,
                    lipHalf = beamFit,
                    mateHalf = socketFit,
                    parameters = parameters,
                    cuts = cuts,
                    cutDirections = cutDirections,
                )
            ) {
                is SnapLip.Outcome.Built -> Attempt.Built(lip.joint)
                is SnapLip.Outcome.Refused -> Attempt.Refused(lip.reason)
            }
        }
    }

    /** Which half the beam went into, given the round the joint was built in. */
    private fun placementOf(
        joint: SnapFitJoint,
        lowHalf: SnapFitHalf,
        highHalf: SnapFitHalf,
        beamHalf: JointHalf,
        chosenHalfCarriesBeam: Boolean,
    ): Placement {
        val beamIsLow = beamHalf == JointHalf.LOW
        return Placement(
            joint = joint,
            beamMesh = if (beamIsLow) lowHalf.mesh else highHalf.mesh,
            socketMesh = if (beamIsLow) highHalf.mesh else lowHalf.mesh,
            chosenHalfCarriesBeam = chosenHalfCarriesBeam,
        )
    }

    /**
     * Which half of the requested round the material refused, or null when neither side is
     * the obvious one.
     *
     * Measured the way [reasonFor] measures: the chosen half's own depth behind its mating
     * face, and the mate's depth beyond its own. A half whose own depth does not even reach
     * the beam's wall has nowhere to root the beam; a mate with less than the beam's reach
     * plus that wall has nowhere for the beam to go. The first is the half the user chose,
     * the second is the mate - and the sentence has to name whichever it was.
     */
    private fun shortHalfForFlip(
        axis: ModelPlacement.Axis,
        scale: Float,
        lowHalf: SnapFitHalf,
        highHalf: SnapFitHalf,
        beamHalf: JointHalf,
        parameters: SnapFitParameters,
    ): JointHalf? {
        val chosenIsHigh = beamHalf == JointHalf.HIGH
        val chosen = if (chosenIsHigh) highHalf else lowHalf
        val mate = if (chosenIsHigh) lowHalf else highHalf
        val chosenSpan = SnapFit.spanAlongAxis(chosen, axis) ?: return beamHalf
        val mateSpan = SnapFit.spanAlongAxis(mate, axis) ?: return beamHalf
        val ownDepth = if (chosenIsHigh) {
            chosenSpan.endInclusive - chosen.faceMm
        } else {
            chosen.faceMm - chosenSpan.start
        }
        val mateDepth = if (chosenIsHigh) {
            mate.faceMm - mateSpan.start
        } else {
            mateSpan.endInclusive - mate.faceMm
        }
        val wall = parameters.beamThicknessMm * scale
        val needed = parameters.beamLengthMm * scale + wall
        return when {
            ownDepth <= wall -> beamHalf
            mateDepth <= needed -> if (chosenIsHigh) JointHalf.LOW else JointHalf.HIGH
            else -> null
        }
    }

    /**
     * Why neither half could take the joint: the half that was asked for, the
     * material it has behind its own mating face, the material the mate has
     * beyond its own, and what the beam and its wall need.
     *
     * Both are measured in the halves' own coordinates, the way the generator
     * measures them, so this sentence is about the same geometry the generator
     * refused.
     */
    private fun reasonFor(
        axis: ModelPlacement.Axis,
        scale: Float,
        lowHalf: SnapFitHalf,
        highHalf: SnapFitHalf,
        beamHalf: JointHalf,
        parameters: SnapFitParameters,
    ): String {
        val chosenIsHigh = beamHalf == JointHalf.HIGH
        val chosen = if (chosenIsHigh) highHalf else lowHalf
        val chosenSpan = SnapFit.spanAlongAxis(chosen, axis)
            ?: return "cannot take a joint: it has no geometry to measure."
        val availableBehind = if (chosenIsHigh) chosenSpan.endInclusive - chosen.faceMm else chosen.faceMm - chosenSpan.start
        val mate = if (chosenIsHigh) lowHalf else highHalf
        val mateSpan = SnapFit.spanAlongAxis(mate, axis)
        val mateAvailable = mateSpan?.let {
            if (chosenIsHigh) mate.faceMm - it.start else it.endInclusive - mate.faceMm
        }
        val wall = parameters.beamThicknessMm * scale
        val needed = parameters.beamLengthMm * scale + wall
        val beyond = if (chosenIsHigh) "below" else "above"
        val measured = "cannot carry a joint here: it has only " + millimetres(availableBehind) +
            " mm of material behind its mating face and the mate has " + millimetres(mateAvailable ?: 0f) +
            " mm " + beyond + " it, and the beam needs " + millimetres(needed) +
            " mm (its reach plus a " + millimetres(wall) + " mm wall) at this scale. Move the " +
            "joint, lower the scale, or flip which half carries the beam."
        // A seam thin enough that the tooth is not even proud of the clearance
        // around it is a different failure with a different answer, and it used
        // to be reported as nothing at all: the joint was built, closed, and
        // held with a NEGATIVE engagement. Say which numbers did it.
        val thinBeam = minOf(
            parameters.beamThicknessMm * scale,
            0.5f * availableBehind,
            0.5f * (mateAvailable ?: 0f),
        )
        val thinLip = minOf(parameters.lipDepthMm * scale, thinBeam * 0.5f)
        val clearance = parameters.lipClearanceMm * scale
        val engagement = thinLip - clearance
        if (thinBeam > 0f && engagement < SnapFit.MIN_ENGAGEMENT_MM) {
            return measured + " There is also no tooth to catch here: the material only takes a " +
                millimetres(thinBeam) + " mm beam, whose tooth would stand " + millimetres(thinLip) +
                " mm proud against the " + millimetres(clearance) + " mm of clearance around it - " +
                millimetres(engagement) + " mm of engagement. Thicken the part, or lower the clearance."
        }
        return measured
    }

    /** One decimal place, locale-independent: this text lands in a status line. */
    private fun millimetres(value: Float): String = String.format(Locale.ROOT, "%.1f", value)

    /**
     * Where a part reaches along the assembly axis, as one readable span.
     *
     * The refusal above is only useful if it says what it measured: a pair that
     * genuinely does not straddle the plane and a pair whose halves are not the
     * ones the split made read the same otherwise.
     */
    private fun describe(mesh: StlMesh, axis: ModelPlacement.Axis): String {
        val span = SnapFit.extentAlongAxis(mesh, axis) ?: return "no geometry"
        return millimetres(span.endInclusive - span.start) + " mm"
    }
}
