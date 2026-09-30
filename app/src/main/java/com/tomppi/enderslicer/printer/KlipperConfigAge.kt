package com.tomppi.enderslicer.printer

/**
 * How long ago a configuration file was written, in words.
 *
 * This is the file's own timestamp, so it says when the file last changed rather than when any
 * setting in it was calibrated: a SAVE_CONFIG rewrites the whole file, and every value in it
 * takes that time. That is still the question worth answering when two hosts disagree about a
 * setting - which of these is the one I set yesterday - so it is answered plainly. Which of the
 * two is newer is left to the caller, who is holding both.
 */
internal fun describeConfigAge(nowMillis: Long, changedMillis: Long?): String {
    if (changedMillis == null || changedMillis <= 0L) return "unknown"
    val elapsed = nowMillis - changedMillis
    // A host whose clock is ahead of this phone's, or a file stamped in the future.
    if (elapsed < 0L) return "not yet"
    val minutes = elapsed / 60_000L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "$minutes minute${plural(minutes)} ago"
        minutes < 60L * 24L -> {
            val hours = minutes / 60L
            "$hours hour${plural(hours)} ago"
        }
        else -> {
            val days = minutes / (60L * 24L)
            "$days day${plural(days)} ago"
        }
    }
}

private fun plural(value: Long): String = if (value == 1L) "" else "s"
