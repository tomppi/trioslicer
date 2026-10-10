package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.viewer.SnapFacing
import com.tomppi.enderslicer.viewer.SnapFitJoint
import com.tomppi.enderslicer.viewer.SnapJoint
import com.tomppi.enderslicer.viewer.SnapTightness
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.Vec3

/**
 * One joint the user placed on the seam, before it is built.
 *
 * A seam carries several, and each one is its own decision: where it sits, which
 * half carries its beam, whether its sockets are cut loose or tight, how many
 * teeth its beam carries and which way it faces. A tap adds one of these; the
 * panel's controls edit the selected one.
 */
data class SnapJointSpec(
    /** The tapped point on the plate. */
    val anchorPointMm: Vec3,
    /**
     * The object the tap landed on: the point is read in that half's own
     * coordinates, because the plate has moved the two halves apart since the
     * split and the same plate point is a different place in each frame.
     */
    val anchorHalfId: String?,
    /** Which half carries this joint's beam. */
    val beamHalf: SnapJoint.JointHalf,
    /** Which of the two clearance steps this joint is cut at. */
    val tightness: SnapTightness,
    /** How many teeth this joint's beam carries. */
    val barbs: Int,
    /** Which way this joint's hook faces. */
    val facing: SnapFacing,
)

/**
 * One joint that was really built and put into the two halves, with what it
 * came out as.
 */
data class SnapPlacedJoint(
    val spec: SnapJointSpec,
    val joint: SnapFitJoint,
    /** False when the beam went into the other half than the one chosen. */
    val beamInChosenHalf: Boolean,
    /** The name of the half the beam was unioned into. */
    val beamHalfName: String,
    /** The name of the half the pocket was cut from. */
    val socketHalfName: String,
)

/**
 * One in-process result of the snap fit: the two halves as the booleans left
 * them, and every joint they now carry.
 *
 * This is what the plate shows between a tap and Apply, and what Apply stages -
 * the same meshes, so what the user approved is what gets written to its own
 * STL. The inputs it was built from travel with it: every joint's own spec, the
 * scale, and the hook controls, so a slider that has moved on since is not
 * silently previewed with, and a stale result cannot be applied.
 */
data class SnapPreview(
    /** The jointed half on the low side of the assembly axis. */
    val lowMesh: StlMesh,
    /** The jointed half on the high side. */
    val highMesh: StlMesh,
    /** Every joint, in the order the user placed them. */
    val joints: List<SnapPlacedJoint>,
    /** The specs this was built from, for the panel and for the staleness test. */
    val specs: List<SnapJointSpec>,
    /**
     * The two plate objects this was built from, and where they stood, for the staleness
     * test.
     *
     * The specs are not the whole input: the geometry is the halves themselves, and a half
     * that has been rotated, scaled, moved or swapped out since leaves this preview
     * describing a plate that no longer exists. Comparing ids alone would let a placement
     * change through, which is what a long-press rotate does under a standing preview.
     */
    val lowHalfId: String? = null,
    val highHalfId: String? = null,
    val lowPlacement: ModelPlacement? = null,
    val highPlacement: ModelPlacement? = null,
    val scale: Float,
    /** True when the full joint was asked for rather than fitted to the seam. */
    val fullJoint: Boolean,
    /** True when the socket mouths were chamfered for this build. */
    val socketRamp: Boolean,
    /** The hook dimensions this was built with, in mm, or null for the scale's own. */
    val hookLengthMm: Float?,
    val hookThicknessMm: Float?,
    val hookLipMm: Float?,
    /** What a repaired half had to have done to it, or null when neither needed it. */
    val repairNote: String?,
) {
    /** The first joint's own geometry: what a one-joint panel reads. */
    val joint: SnapFitJoint get() = joints.first().joint

    /** How many joints are in this result. */
    val jointCount: Int get() = joints.size
}
