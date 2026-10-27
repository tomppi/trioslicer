package com.tomppi.enderslicer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The wide rail's fold choice is a UI preference, not remembered composition state.
 *
 * Same mechanism and shape as the UI scale: one small preferences file, the stored value
 * read before the first frame, every save written back, default expanded (today's rail).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RailMenuPreferenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun clearStoredChoice() {
        context.getSharedPreferences(RailMenuPreference.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Before
    fun resetTheStoredChoice() {
        clearStoredChoice()
        RailMenuPreference.initialize(context)
    }

    @Test
    fun theDefaultIsTheExpandedRail() {
        assertTrue("today's rail is the default", RailMenuPreference.initialize(context))
        assertTrue(RailMenuPreference.current())
    }

    @Test
    fun theFoldedChoiceSurvivesAnInitialize() {
        RailMenuPreference.save(context, false)
        assertFalse("the running session remembers the fold", RailMenuPreference.current())
        assertFalse(
            "a relaunch reads the folded rail back",
            RailMenuPreference.initialize(context),
        )

        RailMenuPreference.save(context, true)
        assertTrue(RailMenuPreference.initialize(context))
    }

    @Test
    fun aStoredFoldIsReadBackByTheKeyTheRailWrites() {
        context.getSharedPreferences(RailMenuPreference.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(RailMenuPreference.KEY_EXPANDED, false)
            .commit()
        assertFalse(RailMenuPreference.initialize(context))
    }
}
