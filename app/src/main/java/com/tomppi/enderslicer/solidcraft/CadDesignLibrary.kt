package com.tomppi.enderslicer.solidcraft

import java.io.File

/** One design in the workspace's files list. */
data class CadDesignEntry(
    val designFile: File,
    val name: String,
    /** How many bodies the design had when the app last wrote it; null when it never did. */
    val partCount: Int?,
    val modifiedAtMillis: Long,
    val sizeBytes: Long,
)

/**
 * The small sidecar that tells the files screen what a design is.
 *
 * A .solidcraft document is the engine's container; counting its bodies would mean opening it
 * through the engine, and the files screen must list a folder without starting CAD work. The
 * app writes what it already knows - the design's name and how many parts it had - beside the
 * file whenever it publishes or saves, and the listing shows that. A design the app has not
 * written since (one copied in, or written by an older version) lists by its file name and
 * says its part count is unknown rather than inventing one.
 *
 * Plain text on purpose: this is read before anything heavy is up, and a parser that cannot
 * fail is worth more here than a format nobody reads by hand.
 */
data class CadDesignMeta(
    val name: String,
    val partCount: Int,
    val savedAtMillis: Long,
) {
    fun encode(): String = buildString {
        append("name=").append(name.replace('\n', ' ').replace('\r', ' ')).append('\n')
        append("parts=").append(partCount).append('\n')
        append("saved=").append(savedAtMillis).append('\n')
    }

    companion object {
        /** Reads whatever of the three lines are there; null when even the name is not. */
        fun decode(text: String?): CadDesignMeta? {
            if (text.isNullOrBlank()) return null
            var name: String? = null
            var parts: Int? = null
            var saved = 0L
            for (line in text.lineSequence()) {
                val key = line.substringBefore('=', "").trim()
                val value = line.substringAfter('=', "").trim()
                if (key.isEmpty() || value.isEmpty()) continue
                when (key) {
                    "name" -> name = value
                    "parts" -> parts = value.toIntOrNull()?.takeIf { it >= 0 }
                    "saved" -> saved = value.toLongOrNull() ?: 0L
                }
            }
            val resolved = name ?: return null
            return CadDesignMeta(resolved, parts ?: 0, saved)
        }
    }
}

/**
 * The SolidCraft workspace's designs, as the files screen and the plate name them.
 *
 * A design is one .solidcraft file in designs/: the engine's own container, which is the source
 * of truth for a document. This object only lists them, remembers what the app already knows
 * about them, and deletes one when the human asks - it never opens, reads or changes the
 * design's contents, so listing a folder cannot start the engine.
 */
object CadDesignLibrary {

    /** What Delete says when the design it was asked to remove is the plate's document. */
    const val ON_PLATE_REFUSAL = "this design is on the plate; close or convert it first"

    /** The files screen with nothing in it. */
    const val EMPTY_SENTENCE = "No designs yet - New design, or open a STEP file."

    /** The sidecar's suffix: bracket.solidcraft -> bracket.meta. */
    private const val META_SUFFIX = ".meta"

    /** Every design the workspace holds, newest first. */
    fun list(workspace: CadWorkspace): List<CadDesignEntry> =
        workspace.designFiles().map { design ->
            val meta = readMeta(workspace, design)
            CadDesignEntry(
                designFile = design,
                name = meta?.name ?: design.nameWithoutExtension,
                partCount = meta?.partCount,
                modifiedAtMillis = design.lastModified(),
                sizeBytes = design.length(),
            )
        }.sortedByDescending { it.modifiedAtMillis }

    fun metaFile(workspace: CadWorkspace, design: File): File =
        File(workspace.designs, design.nameWithoutExtension + META_SUFFIX)

    fun readMeta(workspace: CadWorkspace, design: File): CadDesignMeta? = runCatching {
        val file = metaFile(workspace, design)
        if (!file.isFile) null else CadDesignMeta.decode(file.readText())
    }.getOrNull()

    /** Records what the app knows about [design]; a failed write only costs the sidecar. */
    fun writeMeta(workspace: CadWorkspace, design: File, name: String, partCount: Int, savedAtMillis: Long) {
        runCatching {
            workspace.ensureDirectories()
            metaFile(workspace, design).writeText(
                CadDesignMeta(name, partCount, savedAtMillis).encode(),
            )
        }
    }

    /** Removes the sidecar, for a design that is going away. */
    fun clearMeta(workspace: CadWorkspace, design: File) {
        runCatching { metaFile(workspace, design).delete() }
    }

    /**
     * True when [design] is the document the plate is showing.
     *
     * Compared by canonical path: the workspace path can be reached through a symlink, and a
     * delete that looks safe but removes the open document is exactly the mistake the refusal
     * exists to prevent.
     */
    fun isOnPlate(plateDesignPath: String?, design: File): Boolean {
        if (plateDesignPath.isNullOrBlank()) return false
        return sameFile(File(plateDesignPath), design)
    }

    /**
     * Removes a design and everything derived from it: the file, its sidecar and the engine's
     * display caches. The converted STLs are NOT deleted - those are the plate's own models
     * once the human has converted them, and this only ever deletes a design.
     */
    fun delete(workspace: CadWorkspace, design: File): Boolean {
        if (!design.isFile) return false
        val cacheFiles = workspace.designCacheFiles(design.nameWithoutExtension)
        val deleted = design.delete()
        clearMeta(workspace, design)
        for (cache in cacheFiles) runCatching { cache.delete() }
        return deleted
    }

    private fun sameFile(left: File, right: File): Boolean {
        val a = runCatching { left.canonicalFile }.getOrElse { left.absoluteFile }
        val b = runCatching { right.canonicalFile }.getOrElse { right.absoluteFile }
        return a == b
    }
}
