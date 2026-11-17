package com.rawmechatronics.easyscanner

/** The guided flow's steps, in the order a first scan needs them. */
enum class Step { BOARD, FOCUS, CAMERA, LASER, SCAN, DONE }

/** What the guided flow needs to know, refreshed from every frame result. */
data class Readiness(
    /** The board in the settings is the one the user confirmed in view. */
    val boardConfirmed: Boolean,
    /** The lens focus is locked, or the camera has no manual focus to lock. */
    val focusLocked: Boolean,
    /** A camera calibration taken at this focus is saved. */
    val calibrated: Boolean,
    /** A laser plane fitted with this calibration is saved. */
    val planeReady: Boolean,
) {
    fun done(s: Step) = when (s) {
        Step.BOARD -> boardConfirmed
        Step.FOCUS -> focusLocked
        Step.CAMERA -> calibrated
        Step.LASER -> planeReady
        Step.SCAN, Step.DONE -> false
    }

    /** The first step still to do: everything before it holds. */
    val firstOpen get() = Step.entries.first { !done(it) }
}

/**
 * The step on screen. It follows the scanner's state — a step that becomes
 * done moves on, one whose prerequisite breaks (a new focus invalidates the
 * calibration) moves back — except while the user re-does a finished step,
 * which stays until it completes again. Pure logic, no Android calls.
 */
class Wizard {
    var step = Step.BOARD
        private set

    /** The user opened a finished step to do it again. */
    private var redoing = false

    fun update(r: Readiness): Step {
        if (step != Step.DONE && !redoing) step = r.firstOpen
        return step
    }

    /**
     * The user tapped [s]: any finished step or the first open one can be
     * opened; later ones wait for their prerequisites.
     */
    fun open(s: Step, r: Readiness): Boolean {
        val first = r.firstOpen
        if (s == Step.DONE || s > first) return false
        step = s
        redoing = s != first
        return true
    }

    /** The step on screen did what it is for (confirmed, locked, saved). */
    fun completed(r: Readiness) {
        redoing = false
        update(r)
    }

    /** The scan is finished and saved. */
    fun finished() {
        step = Step.DONE
        redoing = false
    }

    /** Leave the result for a new scan. */
    fun newScan(r: Readiness) {
        step = Step.SCAN
        redoing = false
        update(r)
    }
}
