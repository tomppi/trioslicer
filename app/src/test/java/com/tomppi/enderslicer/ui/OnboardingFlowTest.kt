package com.tomppi.enderslicer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The first-run gate: what a fresh install sees, what a finished one never sees
 * again, and how far back may go.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OnboardingFlowTest {

    @Test
    fun aFreshInstallStartsAtThePrintSetup() {
        assertEquals(OnboardingStep.PRINT_SETUP, OnboardingFlow.initialStep(complete = false))
    }

    /** The flag is one-shot: the flow is not entered again, new step or not. */
    @Test
    fun aFinishedInstallGoesStraightToTheApp() {
        assertEquals(OnboardingStep.COMPLETE, OnboardingFlow.initialStep(complete = true))
    }

    @Test
    fun setupLeadsThroughTheScaleAndTheEnginesToTheApp() {
        val scale = OnboardingFlow.advance(OnboardingStep.PRINT_SETUP)
        assertEquals(OnboardingStep.SCALE, scale)
        // The scale step is not finished by reaching it: nothing is written yet.
        assertFalse(OnboardingFlow.marksComplete(scale))

        val engines = OnboardingFlow.advance(scale)
        assertEquals(OnboardingStep.ENGINES, engines)
        // Nor is the engine step: it is left by preparing or by skipping, and only leaving
        // it writes the flag.
        assertFalse(OnboardingFlow.marksComplete(engines))

        val finished = OnboardingFlow.advance(engines)
        assertEquals(OnboardingStep.COMPLETE, finished)
        assertTrue(OnboardingFlow.marksComplete(finished))
    }

    @Test
    fun backNeverLeavesTheFlowBeforeTheEnginesAreFinished() {
        // The first step is immovable, so Back cannot fall out to the launcher.
        assertEquals(OnboardingStep.PRINT_SETUP, OnboardingFlow.back(OnboardingStep.PRINT_SETUP))
        // The scale step goes back to the setup it follows, still inside the flow.
        assertEquals(OnboardingStep.PRINT_SETUP, OnboardingFlow.back(OnboardingStep.SCALE))
        // And the engine step goes back to the scale that precedes it.
        assertEquals(OnboardingStep.SCALE, OnboardingFlow.back(OnboardingStep.ENGINES))
    }

    /**
     * The migration contract: the preference name and key are unchanged, so the
     * `done` flag written by the old skippable onboarding still means "past this",
     * and an existing user is never sent through the new scale step.
     */
    @Test
    fun theFlagTheOldOnboardingWroteStillCompletesIt() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("done", true)
            .commit()

        assertTrue(OnboardingStore(context).isComplete())
    }
}
