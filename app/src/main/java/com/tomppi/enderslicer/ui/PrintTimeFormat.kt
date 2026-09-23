package com.tomppi.enderslicer.ui

internal fun formatPrintTime(totalSeconds: Int): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return buildString {
        if (days > 0) append("${days}d ")
        if (hours > 0 || days > 0) append("${hours}h ")
        append("${minutes}m")
    }.trim()
}
