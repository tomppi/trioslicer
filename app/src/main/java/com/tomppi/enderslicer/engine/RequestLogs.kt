package com.tomppi.enderslicer.engine

import java.io.File

/** How long an engine's per-request diagnostic log is kept. */
internal const val STALE_REQUEST_LOG_MILLIS = 24L * 60L * 60L * 1_000L

/**
 * Deletes one engine's per-request diagnostic logs once they are a day old.
 *
 * Every slice writes its own `<engine>-<timestamp>-<uuid>.log` - the header, the
 * resolved settings and the engine's own output - and nothing reads an older one
 * back, so without this the logs directory only grows. The `latest-<engine>.log`
 * copy the Export log action reads does not carry the prefix and is kept: it is
 * one file, overwritten by the next request.
 */
/**
 * Keeps a stable copy of the newest request log beside the per-request ones, under
 * the name support reads. Each engine overwrites it with its own request, and the
 * log is text, so a byte copy is the whole of it.
 */
internal fun updateLatestLog(log: File, name: String) {
    runCatching { log.copyTo(File(log.parentFile, name), overwrite = true) }
}

internal fun cleanupStaleRequestLogs(logsDirectory: File, prefix: String) {
    val cutoff = System.currentTimeMillis() - STALE_REQUEST_LOG_MILLIS
    logsDirectory.listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith(prefix) && it.lastModified() in 1 until cutoff }
        .forEach(File::delete)
}
