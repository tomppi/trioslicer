package com.tomppi.enderslicer.engine

/**
 * The seconds behind an engine's "; estimated printing time = ..." header.
 *
 * PrusaSlicer and OrcaSlicer write that value with get_time_dhms, which grows a
 * day field for anything from 24 hours up: "1d 4h 33m 12s". The field is optional
 * and the whole value is anchored here, because an unanchored pattern matches the
 * empty string at index 0 and reports a 28-hour print as zero. Returns null when
 * the value names no time at all.
 */
internal fun parseEnginePrintTimeEstimate(value: String): Int? {
    val match = ESTIMATE_PATTERN.matchEntire(value.trim()) ?: return null
    val days = GcodeSanitizer.clockComponent(match.groupValues[1])
    val hours = GcodeSanitizer.clockComponent(match.groupValues[2])
    val minutes = GcodeSanitizer.clockComponent(match.groupValues[3])
    val seconds = GcodeSanitizer.clockComponent(match.groupValues[4])
    if (days == 0L && hours == 0L && minutes == 0L && seconds == 0L && !match.value.any(Char::isDigit)) return null
    // Every component is clamped to 100,000, but the sum of four clamped fields
    // still overflows an Int seconds value; saturate instead of wrapping negative.
    val total = days * 86_400 + hours * 3_600 + minutes * 60 + seconds
    return if (total > Int.MAX_VALUE) Int.MAX_VALUE else total.toInt()
}

private val ESTIMATE_PATTERN = Regex("""^(?:(\d+)d)?\s*(?:(\d+)h)?\s*(?:(\d+)m)?\s*(?:(\d+)s)?$""")
