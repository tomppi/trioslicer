package com.tomppi.enderslicer.engine

import java.util.Locale

internal fun formatDecimal(value: Double, precision: Int): String =
    String.format(Locale.US, "%.${precision}f", value).trimEnd('0').trimEnd('.')
