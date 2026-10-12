package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.viewer.SnapFacing
import com.tomppi.enderslicer.viewer.SnapFitJoint
import com.tomppi.enderslicer.viewer.SnapJoint
import com.tomppi.enderslicer.viewer.SnapJointStyle
import com.tomppi.enderslicer.viewer.SnapLibraryShape
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
    /** Which joint this is: the cantilever's beam, the wall lip's tooth and ledge, or a library pair. */
    val style: SnapJointStyle = SnapJointStyle.CANTILEVER,
    /**
     * Which bundled shape this joint is grafted from, for
     * [SnapJointStyle.LIBRARY_HOOK]: one control picks it per joint, so a seam
     * can carry a cantilever01 hook beside a round one.
     */
    val libraryShape: SnapLibraryShape = SnapLibraryShape.CANTILEVER01,
    /** The lip's engagement in millimetres, or null for the scale's own. */
    val lipEngagementMm: Float? = null,
    /** The lip's tooth height in millimetres, or null for the scale's own. */
    val lipToothHeightMm: Float? = null,
    /** The wall the lip's ring leaves where the part is solid, or null for the scale's own. */
    val lipWallMm: Float? = null,
    /** How far the lip's recess floor goes past the step, or null for the scale's own. */
    val lipRecessExtraMm: Float? = null,
    /** How wide a slit a cut takes out of the lip's ring, or null for the scale's own. */
    val lipCutWidthMm: Float? = null,
    /**
     * How deep into the insert's face a cut's slit floor would run, or null for
     * the ledge's own root. The part's own surface caps it either way, because a
     * cut past the roof the emboss stands on would groove the model's own face.
     */
    val lipCutDepthMm: Float? = null,
    /** Where the user cut the lip's ring, in the pair's own coordinates. */
    val lipCuts: List<Vec3> = emptyList(),
    /**
     * The direction each cut's slit runs in, on the plate and lined up with
     * [lipCuts]: a stroke across the wall gives its own cut a direction, and a
     * zero entry leaves that cut the automatic inward bisector. Empty means no
     * cut carries one.
     */
    val lipCutDirections: List<Vec3> = emptyList(),
)

/**
 * One joint as the pair REMEMBERS it, for the next session to rebuild.
 *
 * The difference from a [SnapJointSpec] is the anchor: a spec carries a point on
 * the plate, which the packer moves between sessions, while this carries the
 * same point in the pair's own assembled frame, which it cannot. Which half the
 * tap landed on travels as a side rather than as an object id, because every
 * apply gives the two halves fresh ids.
 */
data class SnapJointRecord(
    /** The tap in the pair's own assembled frame - the frame its joints are measured in. */
    val anchorLocalMm: Vec3,
    /** True when the tap landed on the half on the low side of the assembly axis. */
    val onLowHalf: Boolean,
    val beamHalf: SnapJoint.JointHalf,
    val tightness: SnapTightness,
    val barbs: Int,
    val facing: SnapFacing,
    /**
     * The joint's style and, for a lip, the two sizes the user set. They are kept
     * per joint for the same reason the teeth and the facing are: a lip printed
     * once can be re-tuned afterwards rather than re-placed, and a seam can carry
     * a cantilever and a lip side by side.
     */
    val style: SnapJointStyle = SnapJointStyle.CANTILEVER,
    /** Which bundled library shape, for the library hook style. */
    val libraryShape: SnapLibraryShape = SnapLibraryShape.CANTILEVER01,
    val lipEngagementMm: Float? = null,
    val lipToothHeightMm: Float? = null,
    val lipWallMm: Float? = null,
    val lipRecessExtraMm: Float? = null,
    val lipCutWidthMm: Float? = null,
    val lipCutDepthMm: Float? = null,
    /** The cuts in the pair's own assembled frame, which the plate cannot move. */
    val lipCuts: List<Vec3> = emptyList(),
    /** The direction each slit runs in, in the pair's own frame, lined up with [lipCuts]. */
    val lipCutDirections: List<Vec3> = emptyList(),
)

/**
 * The pair's own record: the two unjointed halves every joint on it is built
 * from, and the joints that have been built.
 *
 * This is what makes a joint editable after it has been applied. The boolean is
 * baked into the meshes on the plate - a pocket that has been cut cannot be
 * un-cut - so a second Apply that started from those meshes would union the beam
 * again and cut the pocket a second time. The source files are the halves as
 * they were before the first joint: Apply always stages the joints onto THESE,
 * which is why applying the same joints twice leaves the geometry identical, and
 * why deleting every joint and applying puts the plain halves back.
 *
 * The files belong to the pair and are deleted exactly when the pair dies, never
 * by the cleanup that takes a replaced object's own file.
 */
data class SnapRecord(
    val lowSourcePath: String,
    val highSourcePath: String,
    /** The panel values the recorded joints were built at, restored with them. */
    val scale: Float,
    val fullJoint: Boolean,
    val socketRamp: Boolean,
    val hookLengthMm: Float?,
    val hookThicknessMm: Float?,
    val hookLipMm: Float?,
    /** Every joint on the pair, in the order it was placed. */
    val joints: List<SnapJointRecord>,
) {
    /** Both files, for the paths that delete or check them together. */
    val sourcePaths: List<String> get() = listOf(lowSourcePath, highSourcePath)
}

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
 * A joint in the list that could not be built, and what the material said about it.
 *
 * One joint refusing is that joint's business: the others are placed and the panel says which
 * one is not, because dropping the whole preview - or showing the joints that were built as if
 * the list were all of them - is the same silent loss in two different shapes.
 */
data class SnapJointFailure(
    /** Which joint in the list, from zero. */
    val index: Int,
    /** What refused it, without the joint's own name, which the panel adds. */
    val reason: String,
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
    /**
     * The joints in [specs] that could not be built, by their place in the list.
     *
     * Empty for every preview whose joints all stand, which is the ordinary case. When it is
     * not empty the geometry carries [joints] and none of these: Apply refuses while it does,
     * because a pair joined with fewer joints than the list shows is the bug this reports.
     */
    val failed: List<SnapJointFailure> = emptyList(),
) {
    /** The first joint's own geometry: what a one-joint panel reads. */
    val joint: SnapFitJoint get() = joints.first().joint

    /** How many joints are in this result. */
    val jointCount: Int get() = joints.size
}
