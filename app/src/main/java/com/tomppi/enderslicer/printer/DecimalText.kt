package com.tomppi.enderslicer.printer

import java.util.Locale

/**
 * Numbers a person typed, and numbers the app hands back to them.
 *
 * The same trap as the one that once sent "G1 Y10,000 F3000" to a printer: a Finnish phone
 * writes a decimal comma, and everything downstream of that expects a dot. It has now been hit
 * twice - once in the G-code, and once in the Shaping screen's own fields, where a value the
 * app had written with a comma could not be read back by the app, leaving the apply button
 * disabled with a perfectly good number on screen.
 *
 * So there are two rules, and they are different:
 *
 *  - text the app puts in a field it will read again is written with a dot, whatever the phone
 *    is set to - [formatDecimal]
 *  - text a person typed is accepted with either separator, because a comma is what their
 *    keyboard offers - [parseDecimal]
 */
fun parseDecimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

/** A number for a field the app reads back, or for a command: a dot, always. */
fun formatDecimal(value: Double, decimals: Int): String =
    String.format(Locale.ROOT, "%." + decimals + "f", value)
