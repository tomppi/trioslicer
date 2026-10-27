package com.tomppi.enderslicer.texturizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-apply-at-a-time and cancel plumbing the studio and its bridge share. */
class BumpMeshCallSlotTest {

    @Test
    fun onlyOneApplyRunsAtATime() {
        val slot = BumpMeshCallSlot()
        assertTrue(slot.tryBegin())
        assertTrue(slot.isRunning)
        assertFalse("a second apply is refused", slot.tryBegin())
        slot.finish()
        assertFalse(slot.isRunning)
        assertTrue("the next apply may begin", slot.tryBegin())
        slot.finish()
    }

    @Test
    fun cancelRequestsAreOnlyAcceptedWhileAnApplyIsRunningAndAreClearedWithTheApply() {
        val slot = BumpMeshCallSlot()
        assertFalse("nothing to cancel", slot.requestCancel())
        assertTrue(slot.tryBegin())
        assertTrue(slot.requestCancel())
        assertTrue(slot.isCancelRequested)
        slot.finish()
        assertFalse("a finished apply forgets its cancel", slot.isCancelRequested)
        assertTrue(slot.tryBegin())
        assertFalse("a fresh apply starts uncancelled", slot.isCancelRequested)
        slot.finish()
    }
}
