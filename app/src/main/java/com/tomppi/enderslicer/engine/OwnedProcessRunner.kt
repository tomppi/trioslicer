package com.tomppi.enderslicer.engine

import java.io.File
import java.io.FileWriter
import java.util.concurrent.TimeUnit

/** Owns a native child from creation through exit, timeout, or interruption. */
internal object OwnedProcessRunner {
    class ProcessTimeoutException : Exception("Process timed out")

    fun run(
        start: () -> Process,
        timeout: Long,
        unit: TimeUnit,
        shutdownGraceMillis: Long = DEFAULT_SHUTDOWN_GRACE_MILLIS,
    ): Int {
        require(timeout > 0L) { "Process timeout must be positive" }
        require(shutdownGraceMillis >= 0L) { "Process shutdown grace must not be negative" }

        var process: Process? = null
        var interrupted = false
        try {
            process = start()
            if (Thread.currentThread().isInterrupted) {
                interrupted = true
                throw InterruptedException("Process launch completed after cancellation")
            }
            if (!process.waitFor(timeout, unit)) throw ProcessTimeoutException()
            return process.exitValue()
        } catch (error: InterruptedException) {
            interrupted = true
            throw error
        } finally {
            process?.let { terminate(it, shutdownGraceMillis) }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /**
     * Starts the process, streams its merged output into [log] while parsing
     * progress lines with [parseProgress], and reaps the child on every path.
     *
     * Returns the exit code, or null when [timeout] expired: the engines word that
     * failure differently, so the caller raises its own exception. The same is true
     * of the log, which [appendToLog] decides between opening for append - where
     * the request and the command are already in it - and starting fresh.
     *
     * A line that cannot be parsed is only ever logged, never fatal: an engine's
     * stdout is not a contract, and a slice must not fail over a message.
     */
    fun runStreaming(
        start: () -> Process,
        log: File,
        appendToLog: Boolean,
        parseProgress: (String) -> Int?,
        onProgress: (Int) -> Unit,
        timeout: Long,
        unit: TimeUnit,
        shutdownGraceMillis: Long = DEFAULT_SHUTDOWN_GRACE_MILLIS,
    ): Int? {
        require(timeout > 0L) { "Process timeout must be positive" }
        var process: Process? = null
        var readerThread: Thread? = null
        var interrupted = false
        try {
            val started = start()
            process = started
            val logSink = if (appendToLog) FileWriter(log, true).buffered() else log.bufferedWriter()
            var lastReported: Int? = null
            readerThread = Thread {
                try {
                    started.inputStream.bufferedReader().forEachLine { line ->
                        if (line.isNotBlank()) {
                            logSink.appendLine(line)
                            logSink.flush()
                        }
                        val percent = parseProgress(line)?.coerceIn(0, 100)
                        // The engines repeat a step's percentage; once is enough.
                        if (percent != null && percent != lastReported) {
                            lastReported = percent
                            onProgress(percent)
                        }
                    }
                } catch (_: Exception) {
                    // A killed child closes the pipe under us; nothing to report.
                } finally {
                    runCatching { logSink.close() }
                }
            }
            readerThread.isDaemon = true
            readerThread.start()
            if (!started.waitFor(timeout, unit)) return null
            return started.exitValue()
        } catch (error: InterruptedException) {
            // waitFor clears the flag when it throws; run() restores it and the
            // engines' throwIfInterrupted() reads it, so this path has to as well.
            interrupted = true
            throw error
        } finally {
            process?.let { terminate(it, shutdownGraceMillis) }
            try {
                readerThread?.join(READER_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** Stops [process] (destroy, grace, then force) for owners that reap it themselves. */
    internal fun terminate(process: Process, shutdownGraceMillis: Long) {
        if (!process.isAlive) return
        process.destroy()
        try {
            if (process.isAlive && !process.waitFor(shutdownGraceMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                if (shutdownGraceMillis > 0L) {
                    process.waitFor(shutdownGraceMillis, TimeUnit.MILLISECONDS)
                }
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
        }
    }

    private const val DEFAULT_SHUTDOWN_GRACE_MILLIS = 3_000L

    /** How long the reader thread is given to drain the pipe after the child exits. */
    private const val READER_JOIN_MILLIS = 2_000L
}
