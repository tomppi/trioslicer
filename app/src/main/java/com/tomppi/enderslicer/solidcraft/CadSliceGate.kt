package com.tomppi.enderslicer.solidcraft

/**
 * Why the plate cannot be sliced right now, in the app's one sentence per reason.
 *
 * The plate has three states, and the user asked for them to be told apart rather than blended:
 * nothing (or imported meshes) is sliceable; a live CAD document is not, because a slicer needs
 * triangles and converting is the human's own press; a converted document is sliceable, and
 * only a document that moved on after it was converted is refused until it is converted again.
 *
 * Nothing here converts. Converting as a side effect of Slice is exactly what the user asked
 * not to have.
 */
object CadSliceGate {

    /** The sentence a live document puts where Slice would be. */
    const val NEEDS_STL = "Slice needs an STL - convert the CAD document first"

    /** The fallback for a stale plate that lost its own reason. */
    const val STALE_FALLBACK =
        "the CAD document changed after the plate was built from it; convert it again to update the plate"

    /**
     * The reason Slice is refused, or null when it may run.
     *
     * A stale plate outranks the live-document sentence: the user already has an STL, and what
     * they need to know is that it is out of date.
     */
    fun reasonFor(document: CadDocument?): String? = when {
        document == null -> null
        document.derivedStale -> document.staleReason ?: STALE_FALLBACK
        !document.converted -> NEEDS_STL
        else -> null
    }

    /** True when a live document is what is holding Slice - the one case the button still taps. */
    fun needsConversion(document: CadDocument?): Boolean =
        document != null && !document.converted && !document.derivedStale
}
