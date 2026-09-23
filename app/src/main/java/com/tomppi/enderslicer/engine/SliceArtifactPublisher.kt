package com.tomppi.enderslicer.engine

import java.io.Closeable
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Publishes completed slices by atomically renaming a fully prepared directory. */
internal class SliceArtifactPublisher(
    private val rootDirectory: File,
    private val copyFile: (File, File) -> Unit = { source, destination ->
        source.copyTo(destination, overwrite = false)
    },
) {
    data class PublishedArtifact(
        val id: String,
        val directory: File,
        val gcodeFile: File,
        val baseGcodeFile: File,
    )

    init {
        synchronized(ARTIFACT_LOCK) {
            if (rootDirectory.mkdirs() || rootDirectory.isDirectory) {
                cleanupAbandonedPublishingDirectories()
                cleanupStaleLeases()
                cleanupCompletedArtifacts(emptySet())
            }
        }
    }

    fun publish(
        id: String,
        gcodeSource: File,
        baseGcodeSource: File,
        printerEnvelope: PrinterEnvelope,
    ): PublishedArtifact {
        require(id.matches(ID_PATTERN)) { "Invalid slice artifact id" }
        require(gcodeSource.isFile && gcodeSource.length() > 0L) { "Validated G-code is unavailable" }
        require(baseGcodeSource.isFile && baseGcodeSource.length() > 0L) { "Base G-code is unavailable" }
        check(rootDirectory.mkdirs() || rootDirectory.isDirectory) { "Unable to create the slice result directory" }

        synchronized(ARTIFACT_LOCK) {
            cleanupAbandonedPublishingDirectories()
            cleanupStaleLeases()
        }

        val finalDirectory = File(rootDirectory, id)
        check(!finalDirectory.exists()) { "Slice artifact already exists: $id" }
        val publishingDirectory = File(rootDirectory, ".$id-publishing-${System.nanoTime()}")
        activePublishingDirectories += publishingDirectory
        check(publishingDirectory.mkdir()) { "Unable to create the slice publication directory" }

        try {
            val gcodeDestination = File(publishingDirectory, GCODE_FILE_NAME)
            val baseDestination = File(publishingDirectory, BASE_GCODE_FILE_NAME)
            copyStable(gcodeSource, gcodeDestination)
            copyStable(baseGcodeSource, baseDestination)
            val resolvedEnvelopeFile = File(gcodeSource.parentFile, PrinterEnvelope.METADATA_FILE_NAME)
            val effectiveEnvelope = if (resolvedEnvelopeFile.isFile) {
                PrinterEnvelope.readFrom(resolvedEnvelopeFile)
            } else {
                printerEnvelope
            }
            effectiveEnvelope.writeTo(File(publishingDirectory, PrinterEnvelope.METADATA_FILE_NAME))
            File(publishingDirectory, COMPLETE_MARKER_FILE_NAME).writeText(id)

            val artifact = synchronized(ARTIFACT_LOCK) {
                check(publishingDirectory.renameTo(finalDirectory)) {
                    "Unable to atomically publish the validated slice"
                }
                val published = PublishedArtifact(
                    id = id,
                    directory = finalDirectory,
                    gcodeFile = File(finalDirectory, GCODE_FILE_NAME),
                    baseGcodeFile = File(finalDirectory, BASE_GCODE_FILE_NAME),
                )
                cleanupCompletedArtifacts(setOf(id))
                published
            }
            return artifact
        } catch (error: Throwable) {
            publishingDirectory.deleteRecursively()
            throw error
        } finally {
            activePublishingDirectories -= publishingDirectory
        }
    }

    fun release(id: String) {
        if (!id.matches(ID_PATTERN)) return
        synchronized(ARTIFACT_LOCK) {
            val directory = File(rootDirectory, id)
            if (!isCompletedDirectory(directory)) return
            if (hasActiveLease(directory)) {
                File(directory, RELEASE_MARKER_FILE_NAME).writeText(id)
            } else {
                directory.deleteRecursively()
            }
        }
    }

    private fun copyStable(source: File, destination: File) {
        val length = source.length()
        val modified = source.lastModified()
        copyFile(source, destination)
        check(
            source.isFile &&
                source.length() == length &&
                source.lastModified() == modified &&
                destination.isFile &&
                destination.length() == length,
        ) { "Slice data changed while it was being published" }
    }

    private fun cleanupAbandonedPublishingDirectories() {
        rootDirectory.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith('.') && "-publishing-" in it.name }
            .filterNot { it in activePublishingDirectories }
            .forEach(File::deleteRecursively)
    }

    private fun cleanupStaleLeases() {
        val cutoff = System.currentTimeMillis() - STALE_LEASE_MILLIS
        rootDirectory.listFiles().orEmpty()
            .filter(File::isDirectory)
            .flatMap { directory ->
                directory.listFiles().orEmpty().filter {
                    it.isFile && it.name.startsWith(LEASE_FILE_PREFIX) && it.lastModified() < cutoff
                }
            }
            .forEach(File::delete)
    }

    private fun cleanupCompletedArtifacts(protectedIds: Set<String>) {
        val completed = rootDirectory.listFiles().orEmpty()
            .filter(::isCompletedDirectory)
            .sortedByDescending(File::lastModified)
        val retained = completed.take(MAX_RETAINED_COMPLETED).mapTo(hashSetOf(), File::getName)
        completed.forEach { directory ->
            if (directory.name in protectedIds || directory.name in retained) return@forEach
            if (hasActiveLease(directory)) {
                File(directory, RELEASE_MARKER_FILE_NAME).writeText(directory.name)
            } else {
                directory.deleteRecursively()
            }
        }
    }

    companion object {
        /** The directory under filesDir that published slices live in. */
        const val RESULTS_DIRECTORY_NAME = "slice-results"
        const val GCODE_FILE_NAME = "print.gcode"
        const val BASE_GCODE_FILE_NAME = "base.gcode"
        const val COMPLETE_MARKER_FILE_NAME = ".complete"
        private const val RELEASE_MARKER_FILE_NAME = ".released"
        private const val LEASE_FILE_PREFIX = ".lease-"
        private const val MAX_RETAINED_COMPLETED = 8
        private const val STALE_LEASE_MILLIS = 24L * 60L * 60L * 1_000L

        fun isCompleteGcode(file: File, expectedId: String? = null): Boolean {
            if (!file.isFile || file.name != GCODE_FILE_NAME || file.length() <= 0L) return false
            val id = completedArtifactId(file) ?: return false
            if (expectedId != null && id != expectedId) return false
            return runCatching {
                PrinterEnvelope.readFrom(File(file.parentFile, PrinterEnvelope.METADATA_FILE_NAME))
            }.isSuccess
        }

        fun readPrinterEnvelope(artifactFile: File): PrinterEnvelope {
            require(
                artifactFile.isFile &&
                    artifactFile.length() > 0L &&
                    artifactFile.name in ARTIFACT_FILE_NAMES,
            ) { "The published slice artifact is unavailable" }
            requireNotNull(completedArtifactId(artifactFile)) { "The slice artifact is incomplete" }
            return PrinterEnvelope.readFrom(
                File(requireNotNull(artifactFile.parentFile), PrinterEnvelope.METADATA_FILE_NAME),
            )
        }

        fun acquireLease(artifactFile: File, expectedId: String? = null): Closeable = synchronized(ARTIFACT_LOCK) {
            require(isCompleteGcode(artifactFile, expectedId)) {
                "The slice artifact is incomplete, stale, or does not match the expected result"
            }
            val id = requireNotNull(completedArtifactId(artifactFile))
            val directory = requireNotNull(artifactFile.parentFile)
            val lease = File(directory, "$LEASE_FILE_PREFIX${UUID.randomUUID()}")
            check(lease.createNewFile()) { "Unable to lease the slice artifact" }
            lease.writeText(id)
            Closeable {
                synchronized(ARTIFACT_LOCK) {
                    lease.delete()
                    if (
                        File(directory, RELEASE_MARKER_FILE_NAME).isFile &&
                        !hasActiveLease(directory)
                    ) {
                        directory.deleteRecursively()
                    }
                }
            }
        }

        private fun isCompletedDirectory(directory: File): Boolean {
            if (!directory.isDirectory) return false
            val marker = File(directory, COMPLETE_MARKER_FILE_NAME)
            val markerMatches = marker.isFile && runCatching {
                marker.readText().trim() == directory.name
            }.getOrDefault(false)
            if (!markerMatches) return false
            return File(directory, GCODE_FILE_NAME).isFile &&
                File(directory, BASE_GCODE_FILE_NAME).isFile &&
                File(directory, PrinterEnvelope.METADATA_FILE_NAME).isFile
        }

        private fun hasActiveLease(directory: File): Boolean = directory.listFiles().orEmpty().any {
            it.isFile && it.name.startsWith(LEASE_FILE_PREFIX)
        }

        private fun completedArtifactId(file: File): String? {
            val directory = file.parentFile ?: return null
            val marker = File(directory, COMPLETE_MARKER_FILE_NAME)
            if (!marker.isFile) return null
            val id = runCatching { marker.readText().trim() }.getOrNull() ?: return null
            return id.takeIf { it.isNotBlank() && it == directory.name }
        }

        private val ARTIFACT_LOCK = Any()
        private val ARTIFACT_FILE_NAMES = setOf(GCODE_FILE_NAME, BASE_GCODE_FILE_NAME)
        private val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        private val activePublishingDirectories = Collections.newSetFromMap(ConcurrentHashMap<File, Boolean>())
    }
}

/**
 * Removes the published slices under [root], the way the conical and non-planar
 * settings stores both need once a change invalidates the last run.
 *
 * Returns null when nothing was left behind, or a message for the user when the
 * old G-code is still on disk: [SliceArtifactPublisher.release] marks a directory
 * a reader still holds a lease on instead of pulling it away, and deletes it when
 * the lease closes. Either way the caller has to slice again before exporting.
 */
internal suspend fun invalidatePublishedSliceArtifacts(root: File): String? = withContext(Dispatchers.IO) {
    runCatching {
        val publisher = SliceArtifactPublisher(root)
        val published = root.listFiles().orEmpty().filter(::isPublishedSlice).map(File::getName)
        published.forEach(publisher::release)
        published.map { name -> File(root, name) }.filter(::isPublishedSlice)
    }.fold(
        onSuccess = { stuck ->
            if (stuck.isEmpty()) null
            else "Previous G-code is still on disk; slice again before exporting"
        },
        onFailure = { error ->
            "Previous G-code could not be removed (" + (error.message ?: error::class.java.simpleName) +
                "); slice again before exporting"
        },
    )
}

/** True for a directory the publisher has finished renaming into place. */
private fun isPublishedSlice(directory: File): Boolean = directory.isDirectory &&
    SliceArtifactPublisher.isCompleteGcode(
        File(directory, SliceArtifactPublisher.GCODE_FILE_NAME),
        directory.name,
    )
