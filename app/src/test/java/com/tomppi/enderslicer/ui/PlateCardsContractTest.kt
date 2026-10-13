package com.tomppi.enderslicer.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Plate's floating cards: on the right, translucent, and under anything the top
 * bar opens.
 *
 * The cards are content of the plate's box, so a top-bar menu - a DropdownMenu, its
 * own popup window - is always above them; the contract here is that this is how
 * they are built, since a card drawn in a popup of its own could cover a menu.
 */
class PlateCardsContractTest {
    private val app = source("ui/EnderSlicerApp.kt")
    private val card = source("ui/CollapsibleCard.kt")

    /** The card component itself is the translucent one, so both Plate cards are. */
    @Test
    fun thePlateCardsAreTranslucent() {
        assertTrue(
            "the card paints its container with the Plate card's alpha",
            card.contains("PLATE_CARD_ALPHA") &&
                card.contains("MaterialTheme.colorScheme.surface.copy(alpha = PLATE_CARD_ALPHA)"),
        )
        assertTrue(
            "and both of the Plate's cards are that component",
            app.contains("CollapsibleCard(") && app.contains("NoticeBanner("),
        )
    }

    /** The cards sit on the right, clear of the session rail's column. */
    @Test
    fun theCardsSitOnTheRight() {
        val column = app.substringAfter("The Plate's floating cards live on the right now")
            .substringBefore("if (preview != null || gcodeAvailable)")
        assertTrue(
            "the card column is anchored top-end",
            column.contains("Alignment.TopEnd") && column.contains("horizontalAlignment = Alignment.End"),
        )
    }

    /**
     * Every menu the top bar opens is a popup: each label is a TopBarTextAction with
     * its own DropdownMenu beside it, and nothing on the Plate opens a popup the
     * cards could be drawn in.
     */
    @Test
    fun theTopBarMenusArePopupsOverTheCards() {
        listOf("Import", "Plate", "Blender", "CAD").forEach { label ->
            assertTrue("the $label action is in the top bar", app.contains("label = \"$label\""))
        }
        assertTrue("the menus are DropdownMenus", app.contains("DropdownMenu("))
        assertTrue(
            "and the Plate's cards are content, not a popup of their own",
            !app.contains("Popup(") && app.contains("CollapsibleCard("),
        )
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/$relative"),
            File("app/src/main/java/com/tomppi/enderslicer/$relative"),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate source file for $relative from " + File(".").absolutePath)
        return file.readText()
    }
}
