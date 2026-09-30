package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Test

class KlipperConfigAgeTest {
    private val now = 1_700_000_000_000L

    @Test
    fun aFileWrittenAMomentAgoIsJustNow() {
        assertEquals("just now", describeConfigAge(now, now - 1_000L))
    }

    @Test
    fun minutesHoursAndDaysReadAsWords() {
        assertEquals("1 minute ago", describeConfigAge(now, now - 60_000L))
        assertEquals("5 minutes ago", describeConfigAge(now, now - 5 * 60_000L))
        assertEquals("1 hour ago", describeConfigAge(now, now - 3_600_000L))
        assertEquals("2 hours ago", describeConfigAge(now, now - 2 * 3_600_000L))
        assertEquals("1 day ago", describeConfigAge(now, now - 86_400_000L))
        assertEquals("3 days ago", describeConfigAge(now, now - 3 * 86_400_000L))
    }

    @Test
    fun aFileWithNoTimeSaysSoRatherThanPretending() {
        assertEquals("unknown", describeConfigAge(now, null))
        assertEquals("unknown", describeConfigAge(now, 0L))
    }

    @Test
    fun aClockThatDisagreesWithTheHostIsNotANegativeAge() {
        assertEquals("not yet", describeConfigAge(now, now + 60_000L))
    }
}
