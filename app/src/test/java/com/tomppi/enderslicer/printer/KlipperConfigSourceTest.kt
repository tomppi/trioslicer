package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the running configuration came from.
 *
 * It decides whether this app may rewrite the file - the app's own gets refreshed when the
 * app ships a fix, a user's never does - so it has to survive being written down and read
 * back, and it has to be right when nobody has imported anything at all.
 */
class KlipperConfigSourceTest {
    @Test
    fun aFreshInstallIsRunningWhatTheAppShips() {
        assertFalse(KlipperConfigSource.SHIPPED.imported)
        assertEquals("The configuration this app ships with", KlipperConfigSource.SHIPPED.describe())
    }

    @Test
    fun anImportedConfigurationSaysWhatItWasAndWhen() {
        val source = KlipperConfigSource(imported = true, name = "printer.cfg", atMillis = 1_700_000_000_000L)
        val reloaded = KlipperConfigSource.from(source.toText())
        assertTrue(reloaded.imported)
        assertEquals("printer.cfg", reloaded.name)
        assertEquals(1_700_000_000_000L, reloaded.atMillis)
        assertEquals("Your configuration, from printer.cfg", reloaded.describe())
    }

    @Test
    fun anEmptyOrUnreadableNoteMeansTheAppsOwn() {
        assertEquals(KlipperConfigSource.SHIPPED, KlipperConfigSource.from(""))
        assertEquals(KlipperConfigSource.SHIPPED, KlipperConfigSource.from("nonsense"))
        // Only the word matters: a note written by an older version has no timestamp.
        assertTrue(KlipperConfigSource.from("source=imported").imported)
    }
}
