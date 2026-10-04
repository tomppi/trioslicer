package com.tomppi.enderslicer.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticsTest {
    @Before
    fun reset() {
        Diagnostics.setEnabled(true)
        Diagnostics.clear()
    }

    @After
    fun leaveItOff() {
        Diagnostics.setEnabled(false)
    }

    @Test
    fun keepsNoLogWhileItIsOff() {
        Diagnostics.setEnabled(false)
        Diagnostics.info("slice", "started")
        Diagnostics.failure("slice", IllegalStateException("boom"))

        assertTrue("the log is the opt-in part", Diagnostics.entries.value.isEmpty())
    }

    @Test
    fun aFailureStillReachesTheCornerWhileTheLogIsOff() {
        // The corner is not the log. Everything the app reported used to go to a
        // status line that lives in a card folded down to a chevron, so the
        // outcome was invisible by default - a slice that failed looked exactly
        // like a slice that was never asked for.
        Diagnostics.setEnabled(false)
        Diagnostics.failure("slice", IllegalStateException("write failed: ENOSPC"))

        assertEquals("write failed: ENOSPC", Diagnostics.latestAlert.value?.message)

        Diagnostics.info("slice", "started")
        assertTrue("info lines are still log-only", Diagnostics.entries.value.isEmpty())
    }

    @Test
    fun anInfoLineIsNotAnAlert() {
        Diagnostics.info("slice", "Sliced 720 KiB in 4.0 s")

        assertEquals(1, Diagnostics.entries.value.size)
        assertNull("only warnings and failures reach the corner", Diagnostics.latestAlert.value)
    }

    @Test
    fun failuresAndWarningsReachTheCornerAndKeepTheirLevel() {
        Diagnostics.failure("slice", IllegalStateException("write failed: ENOSPC"))
        val failure = Diagnostics.latestAlert.value
        assertEquals(Diagnostics.Level.FAILURE, failure?.level)
        assertEquals("write failed: ENOSPC", failure?.message)

        Diagnostics.warning("slice", "nozzle collision risk")
        val warning = Diagnostics.latestAlert.value
        assertEquals(Diagnostics.Level.WARNING, warning?.level)
    }

    @Test
    fun aFailureWithNoMessageFallsBackToItsTypeName() {
        Diagnostics.failure("operation", IllegalStateException())

        assertEquals("IllegalStateException", Diagnostics.latestAlert.value?.message)
    }

    @Test
    fun dismissHidesTheCornerButKeepsTheRecord() {
        Diagnostics.failure("slice", IllegalStateException("boom"))
        Diagnostics.dismissAlert()

        assertNull(Diagnostics.latestAlert.value)
        assertEquals(1, Diagnostics.entries.value.size)
    }

    @Test
    fun theBufferIsBounded() {
        repeat(Diagnostics.MAX_ENTRIES + 50) { Diagnostics.info("slice", "line " + it) }

        assertEquals(Diagnostics.MAX_ENTRIES, Diagnostics.entries.value.size)
        // The newest survives: a log that drops the end is useless.
        assertEquals("line " + (Diagnostics.MAX_ENTRIES + 49), Diagnostics.entries.value.last().message)
    }

    @Test
    fun turningItOffClearsWhatItKept() {
        Diagnostics.info("slice", "started")
        Diagnostics.failure("slice", IllegalStateException("boom"))

        Diagnostics.setEnabled(false)

        assertTrue(Diagnostics.entries.value.isEmpty())
        assertNull(Diagnostics.latestAlert.value)
    }
}
