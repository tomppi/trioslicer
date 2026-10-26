package com.tomppi.enderslicer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The floating chat's three sizes, as transitions.
 *
 * The panel, the full-screen header, the bubble and the Back gesture all go through [ChatSizes],
 * so these are the states the UI cannot disagree about - and the ones the walk on the phone
 * exercises.
 */
class ChatSizeTest {

    @Test
    fun theSizesWalkFromBubbleToPanelToFullAndBack() {
        var size = ChatSize.BUBBLE
        size = ChatSizes.fromBubble(ChatSize.PANEL)
        assertEquals(ChatSize.PANEL, size)
        size = ChatSizes.expanded()
        assertEquals(ChatSize.FULL, size)
        size = ChatSizes.collapsed()
        assertEquals(ChatSize.PANEL, size)
        size = ChatSizes.minimised()
        assertEquals(ChatSize.BUBBLE, size)
        // Tapping the bubble again returns to the last expanded size, not the bubble.
        assertEquals(ChatSize.PANEL, ChatSizes.fromBubble(ChatSize.PANEL))
    }

    @Test
    fun backFromFullScreenGoesToThePanelAndTheSecondBackCloses() {
        assertEquals(ChatBackAction.TO_PANEL, ChatSizes.back(ChatSize.FULL))
        assertEquals(ChatBackAction.CLOSE, ChatSizes.back(ChatSize.PANEL))
        assertEquals(ChatBackAction.CLOSE, ChatSizes.back(ChatSize.BUBBLE))
    }

    @Test
    fun theChosenSizeSurvivesAReopenAndPanelIsNotForcedFull() {
        // Left as full screen: reopens full screen.
        assertEquals(ChatSize.FULL, ChatSizes.reopened(ChatSize.FULL, ChatSize.FULL))
        // Left as a panel: reopens as a panel, never forced full screen.
        assertEquals(ChatSize.PANEL, ChatSizes.reopened(ChatSize.PANEL, ChatSize.PANEL))
        // Minimised from full screen / from the panel: reopens where the user was.
        assertEquals(ChatSize.FULL, ChatSizes.reopened(ChatSize.BUBBLE, ChatSize.FULL))
        assertEquals(ChatSize.PANEL, ChatSizes.reopened(ChatSize.BUBBLE, ChatSize.PANEL))
    }

    @Test
    fun minimisingNeverRecordsTheBubbleAsTheLastExpandedSize() {
        assertEquals(ChatSize.FULL, ChatSizes.rememberExpanded(ChatSize.FULL, ChatSize.BUBBLE))
        assertEquals(ChatSize.PANEL, ChatSizes.rememberExpanded(ChatSize.FULL, ChatSize.PANEL))
        assertEquals(ChatSize.FULL, ChatSizes.rememberExpanded(ChatSize.PANEL, ChatSize.FULL))
    }
}
