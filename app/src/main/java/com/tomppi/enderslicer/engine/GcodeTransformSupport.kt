package com.tomppi.enderslicer.engine

import java.io.File
import java.util.Locale

/** Cooperative-cancellation interval shared by the non-planar pipelines. */
internal const val CANCELLATION_INTERVAL = 1024

/** Hard cap on emitted moves shared by the G-code transformers. */
internal const val MAX_EMITTED_MOVES = 15_000_000

/** Six-decimal G-code formatting with "-0" normalized to "0". */
internal fun formatGcode(value: Double): String = String.format(Locale.US, "%.6f", value)
    .trimEnd('0')
    .trimEnd('.')
    .let { if (it == "-0") "0" else it }

internal fun quantizeGcode(value: Double): Double = formatGcode(value).toDouble()

/**
 * Atomically replaces [destination] with [temporary]; falls back to
 * rename-then-copy when the platform move fails (e.g. an antivirus hold).
 */
internal fun publishAtomic(temporary: File, destination: File, label: String) {
    try {
        java.nio.file.Files.move(
            temporary.toPath(),
            destination.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: java.io.IOException) {
        check(temporary.renameTo(destination) || temporary.copyTo(destination, overwrite = true).let { temporary.delete(); true }) {
            "Unable to publish $label"
        }
    }
}

/**
 * Cooperative cancellation check for the non-planar pipelines: throws
 * [InterruptedException] when the worker thread was interrupted, sampled once
 * per [interval] work items to keep hot loops cheap.
 */
internal fun checkCancellation(workItems: Int, consumer: String, interval: Int = CANCELLATION_INTERVAL) {
    if (workItems % interval == 0 && Thread.currentThread().isInterrupted) {
        throw InterruptedException("$consumer was cancelled")
    }
}
