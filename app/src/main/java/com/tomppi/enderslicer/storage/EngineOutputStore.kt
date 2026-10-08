package com.tomppi.enderslicer.storage

import android.content.Context
import java.io.File

/**
 * An engine's handoff directory, as something the user can see and clear.
 *
 * Every model a modelling engine exports is dropped here and picked up once by the UI. Nothing
 * removes them afterwards, so the directory grows for the life of the install - on a real device
 * the Blender one reached a dozen files and hundreds of megabytes within a day of generating
 * models. Surfacing it is the honest fix: the app cannot know which of its own past outputs the
 * user still wants, so it shows them and lets the user decide.
 *
 * Only the exports directory is managed here. `files/models` holds the model currently loaded,
 * and deleting that out from under a running app would be a different and much worse problem.
 *
 * @param engine whose exports these are: `files/<engine>/exports`.
 * @param extensions what to list. Empty lists everything the engine wrote.
 * @param exclude names that are the app's own rather than the user's - the CAD viewport's frame
 *   file is rewritten whenever the camera moves, and is not an export.
 */
class EngineOutputStore(
    context: Context,
    engine: String,
    private val extensions: Set<String> = emptySet(),
    private val exclude: Set<String> = emptySet(),
) {

    /** One exported file, with what the list needs to describe it. */
    data class Entry(
        val file: File,
        val name: String,
        val sizeBytes: Long,
        val modifiedAt: Long,
    )

    private val exportsDir = File(context.filesDir, "$engine/exports")

    /** Exports on disk, newest first. Missing directory reads as empty. */
    fun list(): List<Entry> {
        val files = exportsDir.listFiles { f ->
            f.isFile && f.name !in exclude &&
                (extensions.isEmpty() || f.extension.lowercase() in extensions)
        } ?: return emptyList()
        return files
            .map { Entry(it, it.name, it.length(), it.lastModified()) }
            .sortedByDescending { it.modifiedAt }
    }

    /**
     * Deletes [entries], returning how many actually went.
     *
     * Failures are counted rather than thrown: one file the filesystem refuses to release should
     * not stop the rest from being cleared.
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
