package com.tomppi.enderslicer.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.data.AppStateStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The CAD bridge's network-reach choice, stored in the app's own preferences file.
 *
 * Two things this pins: the default is off (nobody's engine becomes reachable because they
 * updated), and a choice written by the sheet is what the next launch reads - the same store
 * the app's other device-local switches use, not a second file that a profile reset could
 * clear along with print settings.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BridgeNetworkSettingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun theDefaultIsOffAndAChoiceSurvivesTheNextRead() {
        context.getSharedPreferences(AppStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()

        assertFalse("the network bind must be off until the user turns it on", BridgeNetworkPreference.isEnabled(context))

        assertTrue(BridgeNetworkPreference.save(context, true))
        assertTrue(BridgeNetworkPreference.isEnabled(context))

        // The next launch builds a new store; the value is on disk, not in this object.
        assertTrue(BridgeNetworkPreference.isEnabled(context.applicationContext))

        assertTrue(BridgeNetworkPreference.save(context, false))
        assertFalse(BridgeNetworkPreference.isEnabled(context))
    }
}
