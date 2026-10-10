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
        val chosenJoint = join(axisDirection, beamHalf, anchorMm, scale, lowHalf, highHalf, parameters, requested)
        if (chosenJoint != null) {
            return Either.Placed(placementOf(chosenJoint, lowHalf, highHalf, beamHalf, true))
        }
        val otherHalf = if (beamHalf == JointHalf.LOW) JointHalf.HIGH else JointHalf.LOW
        val flipped = join(axisDirection, otherHalf, anchorMm, scale, lowHalf, highHalf, parameters, requested)
        if (flipped != null) {
            return Either.Flipped(
                placementOf(flipped, lowHalf, highHalf, otherHalf, false),
                Failure(
                    beamHalf,
                    "has too little material for the beam at this scale; the joint was built into " +
                        "the other half instead",
                ),
            )
        }
        return Either.Failed(
            Failure(beamHalf, reasonFor(axis, scale, lowHalf, highHalf, beamHalf, parameters)),
        )
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
    ): SnapFitJoint? {
        val forward = beamHalf == JointHalf.LOW
        val sign = if (forward) 1f else -1f
        val beam = if (forward) lowHalf else highHalf
        val socket = if (forward) highHalf else lowHalf
        return SnapFit.generate(
            axis = if (forward) axisDirection else axisDirection * -1f,
            anchorMm = anchorMm,
            scale = scale,
            beamHalf = beam.copy(faceMm = beam.faceMm * sign),
            socketHalf = socket.copy(faceMm = socket.faceMm * sign),
            parameters = parameters,
            requested = requested,
        )
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
        return "cannot carry a joint here: it has only " + millimetres(availableBehind) +
            " mm of material behind its mating face and the mate has " + millimetres(mateAvailable ?: 0f) +
            " mm " + beyond + " it, and the beam needs " + millimetres(needed) +
            " mm (its reach plus a " + millimetres(wall) + " mm wall) at this scale. Move the " +
            "joint, lower the scale, or flip which half carries the beam."
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
