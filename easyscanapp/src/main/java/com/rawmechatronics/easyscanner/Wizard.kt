package com.rawmechatronics.easyscanner

/** The guided flow's steps: calibration prerequisites followed by the three capture modes. */
enum class Step { BOARD, FOCUS, CAMERA, LASER, FUSION, LASER_ONLY, SPLAT, DONE }

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
        Step.FUSION, Step.LASER_ONLY, Step.SPLAT, Step.DONE -> false
    }

    /** The first step still to do: everything before it holds (defaults to FUSION once ready). */
    val firstOpen: Step
        get() = when {
            !boardConfirmed -> Step.BOARD
            !focusLocked -> Step.FOCUS
            !calibrated -> Step.CAMERA
            !planeReady -> Step.LASER
            else -> Step.FUSION
        }

    fun isCaptureStep(s: Step) = s == Step.FUSION || s == Step.LASER_ONLY || s == Step.SPLAT
}

/**
 * The step on screen. It follows the scanner's state — a step that becomes
 * done moves on, one whose prerequisite breaks (a new focus invalidates the
 * calibration) moves back — except while the user re-does a finished step,
 * or explicitly chose `SPLAT` (which needs no laser calibration) or `LASER_ONLY`.
 * Pure logic, no Android calls.
 */
class Wizard {
    var step = Step.BOARD
        private set

    /** The user opened a finished step or chose an alternate capture mode (LASER_ONLY / SPLAT). */
    private var userSelected = false

    fun update(r: Readiness): Step {
        if (step == Step.DONE) return step
        // Splat-only capture needs no focus lock or laser plane: stay on SPLAT whenever chosen.
        if (step == Step.SPLAT) return step
        if (userSelected) {
            // If prerequisites broke while on LASER_ONLY or FUSION, fall back to the broken step.
            if ((step == Step.FUSION || step == Step.LASER_ONLY) && r.firstOpen < Step.FUSION) {
                userSelected = false
                step = r.firstOpen
            }
            return step
        }
        step = r.firstOpen
        return step
    }

    /**
     * The user tapped [s]: `SPLAT` is always accessible (pure photogrammetry needs no laser plane);
     * `FUSION` and `LASER_ONLY` require the focus + laser prerequisites; earlier steps are open
     * once their own prerequisites hold.
     */
    fun open(s: Step, r: Readiness): Boolean {
        if (s == Step.DONE) return false
        if (s == Step.SPLAT) {
            step = Step.SPLAT
            userSelected = true
            return true
        }
        val first = r.firstOpen
        val allowed = when (s) {
            Step.FUSION, Step.LASER_ONLY -> first == Step.FUSION
            else -> s <= first
        }
        if (!allowed) return false
        step = s
        userSelected = s != first
        return true
    }

    /** The step on screen did what it is for (confirmed, locked, saved). */
    fun completed(r: Readiness) {
        userSelected = false
        update(r)
    }

    /** The scan is finished and saved. */
    fun finished() {
        step = Step.DONE
        userSelected = false
    }

    /** Leave the result for a new scan, returning to [returnTo] when its prerequisites still hold. */
    fun newScan(r: Readiness, returnTo: Step = Step.FUSION) {
        step = Step.FUSION
        userSelected = false
        if (returnTo == Step.SPLAT || (returnTo == Step.LASER_ONLY && r.firstOpen == Step.FUSION)) {
            open(returnTo, r)
        } else {
            update(r)
        }
    }
}
