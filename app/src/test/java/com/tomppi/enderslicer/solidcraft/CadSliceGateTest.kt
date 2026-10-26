package com.tomppi.enderslicer.solidcraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three plate states and the one sentence each: nothing is sliceable, a live CAD document
 * is not until the human converts it, a converted one is, and a stale one says why.
 */
class CadSliceGateTest {

    private fun document(converted: Boolean = false, stale: Boolean = false): CadDocument =
        CadDocument(
            name = "Bracket",
            designPath = "/ws/designs/bracket.solidcraft",
            converted = converted,
            convertedRevision = if (converted) 4L else -1L,
            derivedStale = stale,
            staleReason = if (stale) "the design moved on" else null,
        )

    @Test
    fun aPlateWithNoDocumentSlicesNormally() {
        assertNull(CadSliceGate.reasonFor(null))
    }

    @Test
    fun aLiveDocumentIsRefusedByName() {
        assertEquals(CadSliceGate.NEEDS_STL, CadSliceGate.reasonFor(document()))
        assertEquals("Slice needs an STL - convert the CAD document first", CadSliceGate.NEEDS_STL)
        assertTrue(CadSliceGate.needsConversion(document()))
    }

    @Test
    fun aConvertedDocumentIsSliceable() {
        assertNull(CadSliceGate.reasonFor(document(converted = true)))
        assertFalse(CadSliceGate.needsConversion(document(converted = true)))
    }

    @Test
    fun aStalePlateSaysWhyItIsRefused() {
        assertEquals("the design moved on", CadSliceGate.reasonFor(document(converted = true, stale = true)))
        assertFalse(CadSliceGate.needsConversion(document(converted = true, stale = true)))
    }

    @Test
    fun aStaleDocumentWithoutItsOwnReasonStillSaysSomethingTrue() {
        val stale = CadDocument(
            name = "Bracket",
            designPath = "/ws/designs/bracket.solidcraft",
            converted = true,
            derivedStale = true,
        )
        assertEquals(CadSliceGate.STALE_FALLBACK, CadSliceGate.reasonFor(stale))
    }
}
