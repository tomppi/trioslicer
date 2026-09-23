package com.tomppi.enderslicer.ui

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Produces collision-resistant printer-compatible G-code export names. */
internal object GcodeExportName {
    fun suggest(now: LocalDateTime = LocalDateTime.now()): String {
        return "print_${TIMESTAMP.format(now)}.gcode"
    }

    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
}
