package com.tomppi.enderslicer.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A chat-open request is a one-shot event, not sticky state.
 *
 * The question notification's tap becomes a tick in [ChatOpenRequest]; the composition opens the
 * chat for a tick once and remembers it. Before that, the request was sticky: any later Activity
 * recreate saw the non-zero counter and re-opened a chat the user had closed. [ChatRotationTest]
 * drives the same rule through a real `ActivityScenario.recreate`; this pins the mechanism the
 * composition relies on, including that a second composition with no new request gets nothing.
 */
class ChatOpenRequestTest {

    @Before
    fun setUp() = ChatOpenRequest.resetForTest()

    @After
    fun tearDown() = ChatOpenRequest.resetForTest()

    @Test
    fun aRequestIsHandledExactlyOnce() {
        ChatOpenRequest.request()
        assertNotNull("the first composition handles the notification tap", ChatOpenRequest.takeUnhandled())
        assertNull("the same request is not handled twice", ChatOpenRequest.takeUnhandled())
    }

    @Test
    fun aRecreatedActivityWithNoNewRequestLeavesTheChatClosed() {
        ChatOpenRequest.request()
        assertNotNull("the running composition opens the chat", ChatOpenRequest.takeUnhandled())

        // An Activity recreate is a new composition over the same process-wide state. Without a
        // new notification tap it must not replay the previous one.
        assertNull(
            "a recreated activity must not replay the last notification tap",
            ChatOpenRequest.takeUnhandled(),
        )
    }

    @Test
    fun eachNewTapIsHandledOnceAgain() {
        ChatOpenRequest.request()
        assertEquals(1, ChatOpenRequest.takeUnhandled())
        assertEquals(1, ChatOpenRequest.ticks.value)
        ChatOpenRequest.request()
        assertEquals(2, ChatOpenRequest.takeUnhandled())
        assertNull(ChatOpenRequest.takeUnhandled())
    }

    @Test
    fun twoTapsBeforeHandlingOpenOnceAndAreThenConsumed() {
        ChatOpenRequest.request()
        ChatOpenRequest.request()
        assertEquals("the newest tick is the one acted on", 2, ChatOpenRequest.takeUnhandled())
        assertNull(ChatOpenRequest.takeUnhandled())
        assertEquals("the counter still records both taps", 2, ChatOpenRequest.ticks.value)
    }
}
