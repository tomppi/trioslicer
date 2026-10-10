package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.viewer.SnapFitJoint
import com.tomppi.enderslicer.viewer.SnapJoint
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.Vec3

/**
 * One in-process result of the snap fit: the two halves as the boolean left
 * them, and the joint they now carry.
 *
 * This is what the plate shows between a tap and Apply, and what Apply stages -
 * the same meshes, so what the user approved is what gets written to its own
 * STL. The inputs it was built from travel with it: the anchor, the scale and
 * the half carrying the beam, so a slider that has moved on since is not
 * silently previewed with, and a stale result cannot be applied.
 */
data class SnapPreview(
    /** The jointed half on the low side of the assembly axis. */
    val lowMesh: StlMesh,
    /** The jointed half on the high side. */
    val highMesh: StlMesh,
    val joint: SnapFitJoint,
    /** The tapped point this was built from, before the seam projection. */
    val anchorPoint: Vec3,
    val scale: Float,
    val beamHalf: SnapJoint.JointHalf,
    /** False when the joint went into the other half than the one chosen. */
    val beamInChosenHalf: Boolean,
    /** True when the full joint was asked for rather than fitted to the seam. */
    val fullJoint: Boolean,
    /** The hook dimensions this was built with, in mm, or null for the scale's own. */
    val hookLengthMm: Float?,
    val hookThicknessMm: Float?,
    val hookLipMm: Float?,
    /** What a repaired half had to have done to it, or null when neither needed it. */
    val repairNote: String?,
    /**
     * Where each half's mating face ends up, along the split axis, in the
     * coordinates the applied halves will carry.
     *
     * Apply re-centres these two meshes and makes them the halves' new own
     * frames, so the faces the next joint on the pair is measured from are
     * exactly where they were on the plate before that re-centring.
     */
    val lowFaceMm: Float,
    val highFaceMm: Float,
)
