package com.tomppi.enderslicer.scanruler

import java.io.OutputStream

/**
 * One ScanRuler export being written to the human's chosen document.
 *
 * Kept free of Android types so the byte accounting and the cap are unit-testable: the
 * activity owns the SAF stream and the WebView bridge, this owns "did every declared byte
 * arrive, in order, under the cap, and was the stream closed cleanly". A failed transfer is
 * reported with a sentence meant for the user; the activity deletes the partial document when
 * it can.
 */
internal class ScanRulerTransfer(
    private val maxBytes: Long,
    private val maxChunkBytes: Int,
) {
    enum class State { IDLE, WAITING_FOR_DESTINATION, WRITING, DONE, CANCELLED, FAILED }

    data class Begin(
        val accepted: Boolean,
        val suggestedName: String = "",
        val message: String? = null,
    )

    var state: State = State.IDLE
        private set

    var failureMessage: String? = null
        private set

    var expectedBytes: Long = -1L
        private set

    var writtenBytes: Long = 0L
        private set

    var fileName: String = ""
        private set

    private var output: OutputStream? = null

    /** True while a destination has not been chosen yet (the activity is showing the picker). */
    val waitingForDestination: Boolean
        get() = state == State.WAITING_FOR_DESTINATION

    /** True while bytes are being accepted. */
    val writing: Boolean
        get() = state == State.WRITING

    /**
     * Starts one export. [expectedBytes] comes from the page's own Blob size, so a page that
     * declares a huge or zero size is refused before the picker opens.
     */
    fun begin(rawName: String, expectedBytes: Double): Begin {
        if (state == State.WAITING_FOR_DESTINATION || state == State.WRITING) {
            return Begin(false, message = "Another export is already being written.")
        }
        if (!expectedBytes.isFinite() || expectedBytes <= 0.0) {
            return Begin(false, message = "The export reported no size, so no file was written.")
        }
        if (expectedBytes > maxBytes.toDouble()) {
            return Begin(
                false,
                message = "The export is too large for this app to write (limit " +
                    formatMegabytes(maxBytes) + ").",
            )
        }
        val name = safeName(rawName)
        fileName = name
        this.expectedBytes = expectedBytes.toLong()
        writtenBytes = 0L
        failureMessage = null
        state = State.WAITING_FOR_DESTINATION
        return Begin(true, suggestedName = name)
    }

    /** Attaches the SAF stream the human chose. */
    fun attach(output: OutputStream): Boolean {
        if (state != State.WAITING_FOR_DESTINATION) return false
        this.output = output
        state = State.WRITING
        return true
    }

    /** Writes one decoded chunk. False means the transfer has failed and [failureMessage] is set. */
    fun append(chunk: ByteArray): Boolean {
        val stream = output
        if (state != State.WRITING || stream == null) return false
        if (chunk.isEmpty()) {
            fail("The export sent an empty chunk.")
            return false
        }
        if (chunk.size > maxChunkBytes) {
            fail("The export sent a chunk that is too large.")
            return false
        }
        if (writtenBytes + chunk.size > expectedBytes) {
            fail("The export wrote more bytes than it declared, so the file was abandoned.")
            return false
        }
        return runCatching {
            stream.write(chunk)
            writtenBytes += chunk.size
            true
        }.getOrElse { error ->
            fail("The export could not be written to the chosen destination: " +
                (error.message ?: "storage error"))
            false
        }
    }

    /** Closes the stream and checks every declared byte arrived. */
    fun finish(): Boolean {
        val stream = output ?: return false
        val closed = runCatching {
            stream.flush()
            stream.close()
        }.isSuccess
        output = null
        if (!closed) {
            fail("The export could not be finished: the chosen destination stopped accepting bytes.")
            return false
        }
        if (writtenBytes != expectedBytes) {
            fail(
                "The export did not finish (wrote " + writtenBytes + " of " + expectedBytes +
                    " bytes), so no complete file was written.",
            )
            return false
        }
        state = State.DONE
        return true
    }

    /** Abandons whatever is open; the partial document is the activity's to delete. */
    fun cancel() {
        runCatching { output?.flush() }
        runCatching { output?.close() }
        output = null
        state = State.CANCELLED
    }

    /** Fails the transfer with a user-facing sentence. */
    fun fail(message: String) {
        runCatching { output?.close() }
        output = null
        failureMessage = message
        state = State.FAILED
    }

    private fun safeName(rawName: String): String {
        val base = rawName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .ifBlank { "scanruler-export" }
            .take(120)
        return base
    }

    private fun formatMegabytes(bytes: Long): String =
        (bytes / (1024L * 1024L)).toString() + " MB"
}
