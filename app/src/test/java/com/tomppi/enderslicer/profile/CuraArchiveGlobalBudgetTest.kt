package com.tomppi.enderslicer.profile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test

class CuraArchiveGlobalBudgetTest {
    @Test
    fun ignoredEntriesConsumeGlobalInflatedBudget() {
        val archive = zip("ignored.bin" to ByteArray(4_096), "Cura/a.cfg" to "ok".toByteArray())
        val failure = runCatching {
            CuraArchive.readTextEntries(
                ByteArrayInputStream(archive),
                maximumInflatedBytes = 1_024,
                accept = { it.startsWith("Cura/") },
            )
        }.exceptionOrNull()
        assertTrue(failure != null)
    }

    @Test
    fun ignoredEntriesConsumeGlobalEntryBudget() {
        val archive = zip(*Array(5) { index -> "ignored-$index" to byteArrayOf(index.toByte()) })
        val failure = runCatching {
            CuraArchive.readTextEntries(
                ByteArrayInputStream(archive),
                maximumArchiveEntries = 3,
                accept = { false },
            )
        }.exceptionOrNull()
        assertTrue(failure != null)
    }

    @Test
    fun oversizedEntryNameIsRejected() {
        val archive = zip("Cura/" + "a".repeat(1_024) to "x".toByteArray())
        val failure = runCatching {
            CuraArchive.readTextEntries(
                ByteArrayInputStream(archive),
                maximumEntryNameLength = 256,
                accept = { it.startsWith("Cura/") },
            )
        }.exceptionOrNull()
        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("entry name"))
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value)
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
