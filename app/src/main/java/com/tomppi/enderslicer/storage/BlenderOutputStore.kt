package com.tomppi.enderslicer.storage

import android.content.Context
import java.io.File

/**
 * The Blender handoff directory, as something the user can see and clear.
 *
 * Every model the engine exports is dropped here as an STL and picked up once
 * by the UI. Nothing ever removes them afterwards, so the directory grows for
 * the life of the install - on a real device this reached a dozen files and
 * hundreds of megabytes within a day of generating models. Surfacing it is the
 * honest fix: the app cannot know which of its own past outputs the user still
 * wants, so it shows them and lets the user decide.
 *
 * Only the exports directory is managed here. `files/models` holds the model
 * currently loaded, and deleting that out from under a running app would be a
 * different and much worse problem.
 */
class BlenderOutputStore(context: Context) {

    /** One exported file, with what the list needs to describe it. */
    data class Entry(
        val file: File,
        val name: String,
        val sizeBytes: Long,
        val modifiedAt: Long,
    )

    private val exportsDir = File(context.filesDir, "blender/exports")

    /** Exports on disk, newest first. Missing directory reads as empty. */
    fun list(): List<Entry> {
        val files = exportsDir.listFiles { f -> f.isFile && f.name.endsWith(".stl", ignoreCase = true) }
            ?: return emptyList()
        return files
            .map { Entry(it, it.name, it.length(), it.lastModified()) }
            .sortedByDescending { it.modifiedAt }
    }

    /**
     * Deletes [entries], returning how many actually went.
     *
     * Failures are counted rather than thrown: one file the filesystem refuses
     * to release should not stop the rest from being cleared.
     */
    fun delete(entries: List<Entry>): Int {
        var removed = 0
        for (entry in entries) {
            val ok = runCatching { entry.file.delete() }.getOrDefault(false)
            if (ok) removed++
        }
        return removed
    }

    fun deleteAll(): Int = delete(list())

    fun totalBytes(entries: List<Entry>): Long = entries.sumOf { it.sizeBytes }
}
