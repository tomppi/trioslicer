package com.tomppi.enderslicer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone plate's two collapsible surfaces, and the one rule between them: only
 * one is ever expanded.
 *
 * The sidebar panel and the action bar share the plate's bottom strip. Expanding
 * the panel folds the bar to its arrow; the bar's arrow brings the bar back and
 * folds the panel in the same tap; folding the panel with its own arrow leaves the
 * bar where it was. Each remembers its own state.
 */
class PlatePanelStateTest {

    @Test
    fun thePhoneOpensWithTheBarUpAndThePanelClosed() {
        val start = PlatePanelState()

        assertFalse("the panel starts closed", start.railOpen)
        assertTrue("and the bar starts expanded", start.actionBarShowsRows)
        assertFalse("so the bar is not just a handle", start.actionBarHandleOnly)
    }

    @Test
    fun expandingThePanelFoldsTheBarToItsArrow() {
        val open = PlatePanelState().openedRail()

        assertTrue(open.railOpen)
        assertFalse("the bar's rows are gone while the panel is up", open.actionBarShowsRows)
        assertTrue("but its arrow is there to bring it back", open.actionBarHandleOnly)
    }

    @Test
    fun theBarsArrowBringsTheBarBackAndFoldsThePanel() {
        val restored = PlatePanelState().openedRail().toggledActionBar()

        assertFalse("the panel is folded", restored.railOpen)
        assertTrue("and the bar is up again", restored.actionBarShowsRows)
        assertFalse(restored.actionBarHandleOnly)
    }

    @Test
    fun foldingThePanelLeavesTheBarsOwnStateAlone() {
        val folded = PlatePanelState().openedRail().closedRail()

        assertFalse(folded.railOpen)
        assertFalse(
            "the bar stays folded until its arrow is tapped; that is its own state",
            folded.actionBarShowsRows,
        )
        assertEquals(true, folded.toggledActionBar().actionBarShowsRows)
    }

    @Test
    fun theBarsArrowStillFoldsTheBarWhenThePanelIsClosed() {
        val collapsed = PlatePanelState().toggledActionBar()

        assertFalse(collapsed.actionBarShowsRows)
        assertTrue("with the panel still closed", collapsed.toggledActionBar().actionBarShowsRows)
    }

    /** However the two are moved, they are never both expanded. */
    @Test
    fun onlyOneOfTheTwoIsEverExpanded() {
        val states = listOf(
            PlatePanelState(),
            PlatePanelState().openedRail(),
            PlatePanelState().openedRail().closedRail(),
            PlatePanelState().openedRail().toggledActionBar(),
            PlatePanelState().toggledActionBar(),
            PlatePanelState().toggledActionBar().toggledActionBar(),
        )

        states.forEach { state ->
            assertFalse(
                "the panel and the bar were both expanded: $state",
                state.railOpen && state.actionBarShowsRows,
            )
        }
    }
}
