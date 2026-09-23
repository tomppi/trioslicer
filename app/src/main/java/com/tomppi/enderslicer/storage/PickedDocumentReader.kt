package com.tomppi.enderslicer.storage

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Reads a document the user picked into memory, refusing anything past
 * [limitBytes].
 *
 * Every import path here reads a text document a share sheet or a file picker
 * handed over - a PrusaSlicer config, an OrcaSlicer profile, a TrioSlicer
 * configuration snapshot - and the cap only exists so a mistyped pick cannot
 * read a gigabyte into a String. The limit is checked while reading, so a huge
 * file is refused before it is held.
 */
internal fun readPickedBytes(input: InputStream, limitBytes: Long, subject: String): ByteArray {
    require(limitBytes > 0L) { "A document size limit must be positive" }
    val collected = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= limitBytes) {
            "The selected " + subject + " exceeds the " + (limitBytes / (1024 * 1024)) + " MiB safety limit"
        }
        collected.write(buffer, 0, count)
    }
    return collected.toByteArray()
}

/** [readPickedBytes] as UTF-8 text, which is what every one of these documents is. */
internal fun readPickedText(input: InputStream, limitBytes: Long, subject: String): String =
    String(readPickedBytes(input, limitBytes, subject), StandardCharsets.UTF_8)

/**
 * [readPickedText] for a document the app is *exporting* rather than importing: the
 * first [limitBytes] are read and the rest is dropped, with `true` as the second
 * element. A diagnostic export that refused to export the very log it exists to
 * carry would fail exactly when the log is longest, so the cap truncates instead.
 */
internal fun readPickedTextTruncated(input: InputStream, limitBytes: Long): Pair<String, Boolean> {
    require(limitBytes > 0L) { "A document size limit must be positive" }
    val collected = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    var truncated = false
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (total + count > limitBytes) {
            collected.write(buffer, 0, (limitBytes - total).toInt())
            truncated = true
            break
        }
        total += count
        collected.write(buffer, 0, count)
    }
    return String(collected.toByteArray(), StandardCharsets.UTF_8) to truncated
}
