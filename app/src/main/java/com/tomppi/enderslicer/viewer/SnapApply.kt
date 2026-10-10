package com.tomppi.enderslicer.viewer

/**
 * The pair as Apply must stage it: the two previewed halves back in the pair's
 * own frame, the mating face each of them carries there, and the linear part of
 * the placement that puts each of them back on the plate.
 */
data class StagedSnapPair(
    val lowMesh: StlMesh,
    val highMesh: StlMesh,
    val lowFaceMm: Float,
    val highFaceMm: Float,
    /** The own-to-plate rotation, kept so a half that was rotated stays rotated. */
    val lowLinear: List<Double>,
    val highLinear: List<Double>,
)

/**
 * The pair's own frame, which is the frame Apply must stage the halves in.
 *
 * A preview's two meshes are the booleans of the halves where the plate's
 * packer left them, and the packer moves the halves apart in every direction:
 * it drops each onto the bed, so a 20 mm split's two mating planes sit at 20
 * and 0, and it sets the halves side by side, tens of millimetres apart across
 * the seam. The joint builder reads both halves in ONE frame - the frame the
 * split leaves them in, where the halves are ASSEMBLED: one plane between them
 * and one shared outline across it. Staging the preview meshes as they stand
 * therefore makes each new object's own frame the packer's separated one, and
 * the pair the tool has just made is one it cannot work on:
 *
 * - the recorded faces are in two different places, so SnapFit.halvesMeet is
 *   false and the next tap is refused with "the two parts lie side by side
 *   along Z, not across it";
 * - and where the faces do agree - which forcing them to agree would produce -
 *   the next joint's pocket is built beside the mate's material rather than in
 *   it, and the panel says the matching pocket "would cut nothing out of the
 *   other half".
 *
 * So the previewed meshes go back through the placements they were previewed
 * with ([SnapFitHalf.unplace]): the exact assembled frame the pair's own faces
 * are recorded in, and the frame every joint on the pair is measured in. Apply
 * re-centres each half on the bed with its placement's own linear part, so a
 * half that was rotated stays rotated and the parts stand and print where the
 * preview showed them.
 */
object SnapApply {
    /**
     * [lowMesh] and [highMesh], the previewed halves on the plate, as the two
     * objects Apply writes: [lowHalf] and [highHalf] are the pair's own
     * (unplaced) halves, which carry that frame and its face.
     */
    fun stage(
        lowHalf: SnapFitHalf,
        highHalf: SnapFitHalf,
        lowMesh: StlMesh,
        highMesh: StlMesh,
        lowName: String,
        highName: String,
    ): StagedSnapPair {
        val lowOwn = lowHalf.unplace(lowMesh, lowName)
            ?: throw IllegalStateException(
                "The lower half's placement cannot be inverted, so the pair cannot be re-framed",
            )
        val highOwn = highHalf.unplace(highMesh, highName)
            ?: throw IllegalStateException(
                "The upper half's placement cannot be inverted, so the pair cannot be re-framed",
            )
        val staged = StagedSnapPair(
            lowMesh = lowOwn,
            highMesh = highOwn,
            lowFaceMm = lowHalf.faceMm,
            highFaceMm = highHalf.faceMm,
            lowLinear = lowHalf.transform.linear,
            highLinear = highHalf.transform.linear,
        )
        // The invariant every joint on this pair is built on: one shared frame,
        // with the two mating faces on one plane.
        check(
            SnapFit.halvesMeet(
                SnapFitHalf.inPlace(staged.lowMesh, staged.lowFaceMm),
                SnapFitHalf.inPlace(staged.highMesh, staged.highFaceMm),
            ),
        ) { "An applied pair was staged with mating faces that do not meet" }
        return staged
    }
}
