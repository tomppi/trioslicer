package com.tomppi.enderslicer.model

/**
 * A short history of placement changes, so a mis-drag can be taken back.
 *
 * Only the placement is kept, never the mesh: every change goes through the path
 * that re-transforms the source mesh, so putting a placement back rebuilds exactly
 * the geometry that was on screen before.
 */
class PlacementHistory(private val limit: Int = DEFAULT_LIMIT) {
    /** One step: what the change was called, and where the model was before it. */
    data class Step(val label: String, val placement: ModelPlacement)

    private val steps = ArrayDeque<Step>()

    val canUndo: Boolean get() = steps.isNotEmpty()

    /** What undoing would take back, for a button that says so. */
    val nextLabel: String? get() = steps.lastOrNull()?.label

    /** Records the placement that a change labelled [label] is about to replace. */
    fun record(label: String, previous: ModelPlacement) {
        steps.addLast(Step(label, previous))
        while (steps.size > limit) {
            steps.removeFirst()
        }
    }

    /**
     * Takes the last step off the history, or null when there is nothing to take
     * back. Removing it here rather than after the change lands means a refused
     * undo is not retried forever.
     */
    fun undo(): Step? = steps.removeLastOrNull()

    fun clear() {
        steps.clear()
    }

    companion object {
        /** Deep enough for a working session, shallow enough to stay trivial. */
        const val DEFAULT_LIMIT = 20
    }
}
