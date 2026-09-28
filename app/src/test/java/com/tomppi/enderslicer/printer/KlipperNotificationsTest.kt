package com.tomppi.enderslicer.printer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a print ending is worth telling the phone about.
 *
 * The rule is the transition, not the state: klippy repeats a status many times a second and
 * the same terminal state arrives again with every update, so notifying on the state alone would
 * post the same message until the user turned notifications off. Only leaving printing or paused
 * behind is news, and it is news exactly once.
 */
class KlipperNotificationsTest {
    @Test
    fun leavingAPrintBehindIsWorthTelling() {
        assertTrue(KlipperNotifications.endedBetween("printing", "complete"))
        assertTrue(KlipperNotifications.endedBetween("printing", "error"))
        assertTrue(KlipperNotifications.endedBetween("printing", "cancelled"))
        // A paused print can be cancelled, and that is an ending too.
        assertTrue(KlipperNotifications.endedBetween("paused", "cancelled"))
        assertTrue(KlipperNotifications.endedBetween("paused", "error"))
    }

    @Test
    fun theSameStateArrivingAgainIsNot() {
        assertFalse(KlipperNotifications.endedBetween("complete", "complete"))
        assertFalse(KlipperNotifications.endedBetween("error", "error"))
        assertFalse(KlipperNotifications.endedBetween("cancelled", "cancelled"))
    }

    @Test
    fun startingOrPausingIsNotAnEnding() {
        assertFalse(KlipperNotifications.endedBetween("standby", "printing"))
        assertFalse(KlipperNotifications.endedBetween("printing", "paused"))
        assertFalse(KlipperNotifications.endedBetween("paused", "printing"))
        assertFalse(KlipperNotifications.endedBetween("standby", "complete"))
        assertFalse(KlipperNotifications.endedBetween(null, "complete"))
        assertFalse(KlipperNotifications.endedBetween("printing", null))
    }
}
