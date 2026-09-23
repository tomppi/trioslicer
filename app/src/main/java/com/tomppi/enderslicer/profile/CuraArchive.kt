package com.tomppi.enderslicer.profile

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

internal object CuraArchive {
    fun readTextEntries(
        input: InputStream,
        maximumEntryBytes: Int = 16 * 1024 * 1024,
        maximumAcceptedEntries: Int = 512,
        maximumTotalBytes: Long = 64L * 1024L * 1024L,
        maximumArchiveEntries: Int = 2_048,
        maximumInflatedBytes: Long = 256L * 1024L * 1024L,
        maximumCompressionRatio: Long = 2_000L,
        maximumWorkMillis: Long = 30_000L,
        maximumEntryNameLength: Int = 512,
        accept: (String) -> Boolean = { true },
    ): Map<String, String> {
        require(maximumEntryBytes > 0) { "Archive entry limit must be positive" }
        require(maximumAcceptedEntries > 0) { "Archive accepted entry-count limit must be positive" }
        require(maximumTotalBytes > 0L) { "Archive accepted size limit must be positive" }
        require(maximumArchiveEntries > 0) { "Archive global entry-count limit must be positive" }
        require(maximumInflatedBytes > 0L) { "Archive global inflated-size limit must be positive" }
        require(maximumCompressionRatio > 0L) { "Archive compression ratio limit must be positive" }
        require(maximumWorkMillis > 0L) { "Archive work-time limit must be positive" }
        require(maximumEntryNameLength > 0) { "Archive entry-name length limit must be positive" }

        val counted = CountingInputStream(input.buffered())
        val startedNanos = System.nanoTime()
        val result = linkedMapOf<String, String>()
        var archiveEntries = 0
        var acceptedEntries = 0
        var acceptedBytes = 0L
        var inflatedBytes = 0L
        val buffer = ByteArray(16 * 1024)

        fun checkBudget() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Cura archive parsing was cancelled")
            val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L
            require(elapsedMillis <= maximumWorkMillis) { "Archive decompression exceeded its work-time limit" }
            require(inflatedBytes <= maximumInflatedBytes) { "Archive inflated data exceeds its global safety limit" }
            if (inflatedBytes >= MIN_RATIO_CHECK_BYTES) {
                val compressed = counted.count.coerceAtLeast(1L)
                require(inflatedBytes / compressed <= maximumCompressionRatio) {
                    "Archive compression ratio exceeds its global safety limit"
                }
            }
        }

        ZipInputStream(counted).use { zip ->
            while (true) {
                checkBudget()
                val entry = zip.nextEntry ?: break
                archiveEntries++
                require(archiveEntries <= maximumArchiveEntries) {
                    "Archive contains more than $maximumArchiveEntries entries"
                }
                require(entry.name.length <= maximumEntryNameLength) {
                    "Archive entry name exceeds the $maximumEntryNameLength character safety limit"
                }
                val accepted = !entry.isDirectory && accept(entry.name)
                val output = if (accepted) ByteArrayOutputStream() else null
                var entryBytes = 0L
                if (accepted) {
                    require(entry.name !in result) { "Archive contains a duplicate entry: ${entry.name}" }
                    acceptedEntries++
                    require(acceptedEntries <= maximumAcceptedEntries) {
                        "Archive contains more than $maximumAcceptedEntries accepted entries"
                    }
                }
                if (!entry.isDirectory) {
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read.toLong()
                        inflatedBytes += read.toLong()
                        checkBudget()
                        if (accepted) {
                            acceptedBytes += read.toLong()
                            require(entryBytes <= maximumEntryBytes) {
                                "Archive entry ${entry.name} exceeds the ${maximumEntryBytes / 1024 / 1024} MiB safety limit"
                            }
                            require(acceptedBytes <= maximumTotalBytes) {
                                "Accepted archive data exceeds the ${maximumTotalBytes / 1024 / 1024} MiB safety limit"
                            }
                            output?.write(buffer, 0, read)
                        }
                    }
                }
                if (accepted) result[entry.name] = requireNotNull(output).toString(Charsets.UTF_8.name())
                zip.closeEntry()
            }
        }
        return result
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Long = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) count += it.toLong() }
    }

    private const val MIN_RATIO_CHECK_BYTES = 1L * 1024L * 1024L
}
