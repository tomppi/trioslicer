package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * The decimal mark, which a phone set to Finnish makes a comma - and which everything from
 * Kotlin's own parsing to Klipper's G-code parser expects to be a dot.
 */
class DecimalTextTest {
    private val finnish = Locale("fi", "FI")

    @Test
    fun aTypedCommaIsUnderstood() {
        assertEquals(89.8, parseDecimal("89,8")!!, 1e-9)
        assertEquals(89.8, parseDecimal("89.8")!!, 1e-9)
        assertEquals(0.1, parseDecimal(" 0,1 ")!!, 1e-9)
        assertNull(parseDecimal(""))
        assertNull(parseDecimal("eighty"))
    }

    @Test
    fun whatTheAppWritesItCanReadBackOnAnyPhone() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(finnish)
            // The bug this exists for: written with the phone's own idea of a decimal mark, the
            // field would hold "89,8" and the app's own parser would refuse it.
            val written = formatDecimal(89.8, 1)
            assertEquals("89.8", written)
            assertEquals(89.8, parseDecimal(written)!!, 1e-9)
            assertEquals(0.05, parseDecimal(formatDecimal(0.05, 3))!!, 1e-9)
        } finally {
            Locale.setDefault(previous)
        }
    }
}
