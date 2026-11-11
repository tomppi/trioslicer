package com.tomppi.enderslicer.splat

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * The splat models the viewer can open, and the folder they live in.
 *
 * The files arrive as exports from the training machine - a .sog or .ply written by
 * LichtFeld Studio - and every one of them is a self-contained scene. They are kept in app
 * storage rather than on the shared volume because the viewer page fetches them through the
 * app's own asset-loader origin: a file:// page cannot read a Downloads folder, and a model
 * the user deletes from a file manager should not leave a broken tile behind.
 */
object SplatLibrary {

    /** The formats the Aholo viewer loads (its SplatFileType enum). */
    val EXTENSIONS: Set<String> = setOf("sog", "ply", "spz", "splat", "ksplat")

    /** The files the viewer lists, newest first. */
    fun list(context: Context): List<File> = directory(context)
        .listFiles()
        ?.filter { it.isFile && isSupported(it.name) }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()

    /** The folder the viewer reads and imports into. */
    fun directory(context: Context): File = File(context.filesDir, "splats").apply { mkdirs() }

    fun isSupported(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /**
     * A file name that is safe to append to the folder: no separators, no "..", no control
     * characters, and always with one of the supported extensions.
     */
    fun safeName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            for (character in base) {
                when {
                    character.isLetterOrDigit() || character == '.' || character == '_' || character == '-' -> append(character)
                    character == ' ' -> append('_')
                }
            }
        }.trim('.', '_')
        if (cleaned.isEmpty() || cleaned == "..") return null
        if (!isSupported(cleaned)) return null
        return cleaned.take(120)
    }

    /**
     * Copy a document the user picked into the folder, under a name that cannot escape it.
     *
     * The bytes are written beside the destination and renamed into place: an interrupted
     * import leaves no half-file that the list would offer as a model.
     */
    fun import(context: Context, uri: Uri, displayName: String?): File {
        val name = safeName(displayName) ?: throw IllegalArgumentException(
            "That file is not a splat the viewer can open (" +
                EXTENSIONS.joinToString("/") + ").",
        )
        val folder = directory(context)
        val destination = uniqueFile(folder, name)
        val temporary = File(folder, "." + destination.name + ".part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("The file could not be read.")
            if (!temporary.renameTo(destination)) {
                throw IOException("The file could not be saved.")
            }
            return destination
        } finally {
            temporary.delete()
        }
    }

    /** The same name unless it is taken, then "name-1.ext", "name-2.ext"... */
    fun uniqueFile(folder: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var candidate = File(folder, name)
        var index = 1
        while (candidate.exists()) {
            candidate = File(folder, base + "-" + index + (if (extension.isEmpty()) "" else "." + extension))
            index++
        }
        return candidate
    }

    /** A human title for the viewer's toolbar: the file name without its extension. */
    fun titleOf(file: File): String = file.name.substringBeforeLast('.', file.name)
}
