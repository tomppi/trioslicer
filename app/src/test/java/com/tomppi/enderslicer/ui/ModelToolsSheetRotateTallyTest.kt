package com.tomppi.enderslicer.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rotate tally follows the placement it is shown under - the same contract the
 * position and scale fields keep - so a selection change or an Undo cannot show one
 * model's rotations under another's.
 */
class ModelToolsSheetRotateTallyTest {

    @Test
    fun theRotateTallyIsKeyedToThePlacementItIsShownUnder() {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/ui/ModelToolsSheet.kt"),
            File("app/src/main/java/com/tomppi/enderslicer/ui/ModelToolsSheet.kt"),
        )
        val source = candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate ModelToolsSheet.kt")
        val text = source.readText()

        assertTrue(
            "the X tally is keyed to the placement, like the position fields",
            text.contains("var rotatedX by rememberSaveable(placement)"),
        )
        assertTrue(text.contains("var rotatedY by rememberSaveable(placement)"))
        assertTrue(text.contains("var rotatedZ by rememberSaveable(placement)"))
    }
}
