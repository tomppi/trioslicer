package com.tomppi.enderslicer.harness

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the harness store does with a credential it cannot read.
 *
 * The launch token is pasted once from the URL the harness prints to its own
 * console, and the session cookie is minted once per approval, so neither has a
 * second copy. A decrypt failure keeps the ciphertext and answers empty - the
 * rule OctoPrintSecretStore states - because the Keystore can be momentarily
 * unavailable and destroying the only copy would cost a harness restart or a
 * hunt through the launcher log. Only an origin change drops them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HarnessConfigStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPreferences() {
        preferences().edit().clear().commit()
    }

    // The HarnessConfigStore's own preference keys, seeded raw so the decrypt
    // failure path can be exercised without the device Keystore.
    private fun preferences() = context.getSharedPreferences("harness_config", Context.MODE_PRIVATE)

    @Test
    fun aTokenThatWillNotDecryptKeepsItsCiphertext() {
        preferences().edit()
            .putString("base_url", "http://host:3080")
            .putString("launch_token_ciphertext", "v1:!!!")
            .putString("launch_token_origin", "http://host:3080")
            .commit()

        assertEquals("", HarnessConfigStore(context).load().launchToken)

        assertNotNull(
            "the ciphertext is the only copy of the launch token",
            preferences().getString("launch_token_ciphertext", null),
        )
    }

    @Test
    fun aCookieThatWillNotDecryptKeepsItsCiphertext() {
        preferences().edit()
            .putString("base_url", "http://host:3080")
            .putString("session_cookie_ciphertext", "v1:!!!")
            .putString("session_cookie_origin", "http://host:3080")
            .commit()

        assertEquals("", HarnessConfigStore(context).loadCookie("http://host:3080"))

        assertNotNull(
            "the ciphertext is the only copy of the 30-day session cookie",
            preferences().getString("session_cookie_ciphertext", null),
        )
    }

    @Test
    fun aCredentialFromAnotherHarnessIsStillDropped() {
        preferences().edit()
            .putString("base_url", "http://new:3080")
            .putString("launch_token_ciphertext", "v1:!!!")
            .putString("launch_token_origin", "http://old:3080")
            .commit()

        assertEquals("", HarnessConfigStore(context).load().launchToken)

        assertNull(preferences().getString("launch_token_ciphertext", null))
        assertNull(preferences().getString("launch_token_origin", null))
    }
}
