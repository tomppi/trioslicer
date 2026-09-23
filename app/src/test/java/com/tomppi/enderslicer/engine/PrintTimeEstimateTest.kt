package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The engines report the estimate with get_time_dhms, which adds a day field for
 * anything from 24 hours up. An unanchored pattern matched the empty string and
 * reported those prints as zero seconds.
 */
class PrintTimeEstimateTest {

    @Test
    fun anEstimateWithDaysIsNotZero() {
        assertEquals(102_792, parseEnginePrintTimeEstimate("1d 4h 33m 12s"))
        assertEquals(93_600, parseEnginePrintTimeEstimate("1d 2h 0m 0s"))
    }

    @Test
    fun theShorterFormsStillParse() {
        assertEquals(15_003, parseEnginePrintTimeEstimate("4h 10m 3s"))
        assertEquals(42, parseEnginePrintTimeEstimate("42s"))
        assertEquals(1_632, parseEnginePrintTimeEstimate("27m 12s"))
        assertEquals(7_200, parseEnginePrintTimeEstimate("2h"))
    }

    @Test
    fun fourClampedFieldsSaturateInsteadOfWrappingNegative() {
        // Each field is clamped to 100,000, and 100,000 days alone is already past
        // Int seconds; the sum must read as "absurdly long", never as a negative.
        assertEquals(Int.MAX_VALUE, parseEnginePrintTimeEstimate("100000d 100000h 100000m 100000s"))
        assertEquals(Int.MAX_VALUE, parseEnginePrintTimeEstimate("1000000d"))
    }

    @Test
    fun somethingThatNamesNoTimeIsNull() {
        assertNull(parseEnginePrintTimeEstimate(""))
        assertNull(parseEnginePrintTimeEstimate("unknown"))
        assertNull(parseEnginePrintTimeEstimate("4h 10m 3s trailing"))
    }
}
