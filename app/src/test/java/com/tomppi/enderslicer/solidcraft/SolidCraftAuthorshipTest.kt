package com.tomppi.enderslicer.solidcraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The one-author rule, on its own.
 *
 * This is the object the bridge and the in-app agent share, so the UI can only read a state and
 * never decide one. Two authors at once must be impossible by construction, and [state] asserts
 * the invariant so a future transition that breaks it fails here rather than producing a
 * document with two designers.
 */
class SolidCraftAuthorshipTest {

    private val ownership = SolidCraftAuthorship()

    private fun nameClients(count: Int) {
        repeat(count) { assertTrue(ownership.attachExternal()) }
    }

    @Test
    fun nobodyIsTheAuthorOnAFreshDocument() {
        val state = ownership.state()
        assertEquals(SolidCraftAuthorship.Author.NOBODY, state.author)
        assertEquals("Author: nobody", state.authorLine)
        assertTrue("the in-app composer is available", state.canInAppEdit)
        assertFalse(state.inAppPaused)
    }

    @Test
    fun anInAppTurnNamesItselfAndIsTheOnlyAuthor() {
        assertTrue(ownership.beginInAppTurn())
        val state = ownership.state()
        assertEquals(SolidCraftAuthorship.Author.IN_APP, state.author)
        assertEquals("Author: in-app agent", state.authorLine)
        assertTrue(state.canInAppEdit)
        assertFalse(state.inAppPaused)

        // The bridge's door is shut while the turn runs, with the named sentence.
        assertFalse(ownership.externalAllowed())
        assertTrue(ownership.attachExternal().not())
        assertTrue(ownership.refusalMessage().contains("in-app agent is driving"))
        assertTrue(ownership.refusalMessage().contains("retry when its turn finishes"))

        ownership.endInAppTurn()
        assertEquals(SolidCraftAuthorship.Author.NOBODY, ownership.state().author)
        assertTrue(ownership.attachExternal())
    }

    @Test
    fun anAttachedExternalClientPausesTheInAppAgent() {
        nameClients(1)
        val state = ownership.state()
        assertEquals(SolidCraftAuthorship.Author.EXTERNAL, state.author)
        assertEquals("Author: external agent on the bridge", state.authorLine)
        assertEquals("external agent connected - in-app agent paused", state.pausedLine)
        assertFalse("the composer is disabled", state.canInAppEdit)
        assertTrue(state.inAppPaused)

        // The in-app side cannot claim a turn, and the named reason says what to do.
        assertFalse(ownership.beginInAppTurn())
        assertNotNull(ownership.inAppBlockedReason())
        assertTrue(ownership.inAppBlockedReason().orEmpty().contains("Take over"))
        assertEquals("the attached client was not disconnected to make room", 1, ownership.state().externalClients)
    }

    @Test
    fun theAuthorLineNamesThePortWhileTheBridgeListens() {
        ownership.noteBridgePort(9878)
        nameClients(2)
        assertEquals("Author: external agent on 9878", ownership.state().authorLine)
        ownership.noteBridgePort(0)
        assertEquals("Author: external agent on the bridge", ownership.state().authorLine)
    }

    @Test
    fun takeOverClosesTheLiveConnectionsAndFlipsToNobody() {
        var closed = 0
        ownership.bindExternalCloser {
            // The bridge detaches each connection before closing it, as closeExternalClients does.
            while (ownership.state().externalClients > 0) {
                ownership.detachExternal()
                closed++
            }
            closed
        }
        nameClients(2)
        assertEquals(SolidCraftAuthorship.Author.EXTERNAL, ownership.state().author)

        assertEquals(2, ownership.takeOver())
        assertEquals(2, closed)
        assertEquals("the next poll already reads nobody", SolidCraftAuthorship.Author.NOBODY, ownership.state().author)
        assertTrue("a new external client may attach again", ownership.attachExternal())
    }

    @Test
    fun takeOverIsRefusedWhileTheInAppAgentIsAlreadyTheAuthor() {
        assertTrue(ownership.beginInAppTurn())
        try {
            ownership.takeOver()
            fail("expected the authorship to refuse a takeover of its own turn")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("already the author"))
        } finally {
            ownership.endInAppTurn()
        }
    }

    @Test
    fun aTakeOverInProgressRefusesNewAttachments() {
        ownership.bindExternalCloser {
            // While the closer runs, the ownership is between authors: no new client may attach.
            assertFalse(ownership.attachExternal())
            assertTrue(ownership.refusalMessage().contains("taking this document back"))
            0
        }
        nameClients(1)
        ownership.takeOver()
    }

    @Test
    fun theTwoAuthorStatesCanNeverBothBeTrue() {
        val violations = AtomicInteger()
        val threads = (0 until 8).map { index ->
            thread {
                repeat(2_000) {
                    if (index % 2 == 0) {
                        if (ownership.beginInAppTurn()) {
                            val state = ownership.state()
                            if (state.inAppTurn && state.externalClients > 0) violations.incrementAndGet()
                            if (state.author == SolidCraftAuthorship.Author.IN_APP &&
                                state.author == SolidCraftAuthorship.Author.EXTERNAL
                            ) {
                                violations.incrementAndGet()
                            }
                            ownership.endInAppTurn()
                        }
                    } else {
                        if (ownership.attachExternal()) {
                            val state = ownership.state()
                            if (state.inAppTurn && state.externalClients > 0) violations.incrementAndGet()
                            ownership.detachExternal()
                        }
                    }
                }
            }
        }
        threads.forEach { it.join() }
        assertEquals("the invariant was broken under concurrency", 0, violations.get())
        assertEquals(0, ownership.state().externalClients)
        assertFalse(ownership.state().inAppTurn)
    }

    @Test
    fun detachingMoreThanWasAttachedIsHarmless() {
        ownership.detachExternal()
        ownership.detachExternal()
        assertEquals(0, ownership.state().externalClients)
        assertNull(ownership.inAppBlockedReason())
    }
}
