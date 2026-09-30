package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which host a configuration operation works from.
 *
 * This is the rule a released bug broke: a configured computer whose configuration could not be
 * read fell back to this device's own file, and that file was then uploaded over the computer's
 * configuration and followed by a restart. The same rule decides which of the Machine tab's
 * operations are offered at all, because import and restore write this phone's own file.
 */
class ConfigHostRuleTest {

    @Test
    fun withNoComputerTheDeviceFileIsTheSource() {
        assertEquals(
            "device",
            resolveConfigText(remoteConfigured = false, deviceText = { "device" }, remoteText = { "computer" }),
        )
    }

    @Test
    fun withAComputerItsFileIsTheSource() {
        assertEquals(
            "computer",
            resolveConfigText(remoteConfigured = true, deviceText = { "device" }, remoteText = { "computer" }),
        )
    }

    @Test
    fun aComputerThatCannotBeReadIsNeverReplacedByTheDeviceFile() {
        assertNull(resolveConfigText(remoteConfigured = true, deviceText = { "device" }, remoteText = { null }))
    }

    @Test
    fun theSourceThatIsNotUsedIsNotEvenRead() {
        var deviceReads = 0
        resolveConfigText(
            remoteConfigured = true,
            deviceText = { deviceReads++; "device" },
            remoteText = { "computer" },
        )
        assertEquals("a remote route must not touch this device's file", 0, deviceReads)
    }
}
