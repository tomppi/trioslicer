package com.tomppi.enderslicer.viewer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notes are claims like the panel's, and the fifth audit found two that had
 * stopped being true: the pad refusal quoted as a sentence MainViewModel no
 * longer builds, and the multi-barb design still filed as "not yet built" below
 * the section recording that it landed. The document is checked here against
 * the code it describes, so the next drift fails a test rather than a reader.
 */
class SnapDocsContractTest {
    /** The file with its line wrapping flattened: quotes have to survive a wrap. */
    private val notes = source("docs/snap-fit-notes.md").replace(Regex("\\s+"), " ")

    @Test
    fun thePadRefusalQuotedIsTheOneTheAppBuilds() {
        assertTrue(
            "the notes quote the refusal the app builds, rung and all",
            notes.contains(SnapPad.TOO_THIN_REASON + "; minimal joint only"),
        )
        assertFalse(
            "and not the stale tail the ladder no longer produces",
            notes.contains(SnapPad.TOO_THIN_REASON + "; a bare cantilever only"),
        )
    }

    @Test
    fun theMultiBarbDesignIsNotFiledAsUnbuilt() {
        assertFalse("the multi-barb beam landed", notes.contains("NOT yet built"))
        assertFalse("and the notes say so where it is described", notes.contains("also asked for and not yet built"))
        assertTrue(
            "with the section that records it",
            notes.contains("MULTI-BARB"),
        )
    }

    @Test
    fun theClickNamesAndThePairFrameAreTheOnesInTheCode() {
        assertFalse("a click label is a position, not a fit", notes.contains("seated - tight"))
        assertTrue("the notes name the seated click as the code does", notes.contains(SnapFit.SEATED_CLICK))
        assertTrue("and quote the panel's engagement wording", notes.contains("engagement"))
        assertTrue(
            "the notes record that a pair is read in its assembled frame",
            notes.contains("assembled frame"),
        )
        assertTrue(
            "and that the pair is persisted with the plate",
            notes.contains("workspace descriptor"),
        )
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("../" + relative),
            File(relative),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate " + relative + " from " + File(".").absolutePath)
        return file.readText()
    }
}
