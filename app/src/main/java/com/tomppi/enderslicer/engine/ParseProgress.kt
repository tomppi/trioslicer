package com.tomppi.enderslicer.engine

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets

/** Threshold between progress reports; ~1 MiB of file content per report. */
internal const val PARSE_PROGRESS_SAMPLE_BYTES = 1L shl 20

/**
 * Byte-counting stream that reports the number of bytes consumed so far.
 * Used by the nozzle-path parsers to surface live parse progress (the UI
 * would otherwise look frozen while a multi-hundred-MB G-code is read twice).
 */
internal class ProgressCountingInputStream(
    private val delegate: InputStream,
    sampleBytes: Long = PARSE_PROGRESS_SAMPLE_BYTES,
    private val onBytes: (Long) -> Unit,
) : InputStream() {
    private val reportInterval = sampleBytes.coerceAtLeast(1L)
    private var counted = 0L
    private var nextReport = reportInterval

    override fun read(): Int = delegate.read().also { if (it != -1) add(1) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length).also { if (it > 0) add(it) }

    override fun available(): Int = delegate.available()

    private fun add(amount: Int) {
        counted += amount
        if (counted >= nextReport) {
            onBytes(counted)
            while (nextReport <= counted) nextReport += reportInterval
        }
    }
}

/**
 * UTF-8 reader over [file] (the same behaviour as `File.bufferedReader()`) that
 * reports bytes consumed every [sampleBytes] via [onBytes].
 */
internal fun progressReader(
    file: File,
    sampleBytes: Long = PARSE_PROGRESS_SAMPLE_BYTES,
    onBytes: (Long) -> Unit = {},
): Reader = InputStreamReader(
    ProgressCountingInputStream(FileInputStream(file), sampleBytes, onBytes),
    StandardCharsets.UTF_8,
)
