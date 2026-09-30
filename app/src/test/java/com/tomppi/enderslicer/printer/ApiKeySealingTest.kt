package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reading an API key written by an older build.
 *
 * The key is sealed with a Keystore entry now, because preferences go into cloud backups. A key
 * written before that is plaintext: reading it as sealed returns null, and falling back to the
 * stored value is what keeps an existing printer reachable.
 */
class ApiKeySealingTest {

    @Test
    fun anEmptyPreferenceIsNoKey() {
        assertEquals("", apiKeyFromStored("") { null })
    }

    @Test
    fun aSealedValueIsOpened() {
        assertEquals("secret", apiKeyFromStored("sealed:abc") { "secret" })
    }

    @Test
    fun aPlaintextValueFromAnOlderBuildStillWorks() {
        assertEquals("legacy-key", apiKeyFromStored("legacy-key") { null })
    }

    @Test
    fun aSealedValueThisDeviceCannotOpenIsNotSentAsTheKey() {
        // A restore or a rotated Keystore entry leaves a sealed blob nothing can open. It is not
        // the key: sending it earns a 401, and re-sealing the blob would destroy what was there.
        assertEquals("", apiKeyFromStored("sealed:AAAA") { null })
    }

}
