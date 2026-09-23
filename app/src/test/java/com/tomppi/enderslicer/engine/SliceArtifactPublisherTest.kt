package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SliceArtifactPublisherTest {
    @Test
    fun finalDirectoryAppearsOnlyAfterFilesEnvelopeAndCompletionMarkerAreReady() {
        val root = kotlin.io.path.createTempDirectory("enderslicer-results").toFile()
        val sourceDirectory = kotlin.io.path.createTempDirectory("enderslicer-staging").toFile()
        val gcode = File(sourceDirectory, "output.gcode").apply { writeText("validated-output") }
        val base = File(sourceDirectory, "base.gcode").apply { writeText("validated-base") }
        var observedHiddenFinalDirectory = false
        val publisher = SliceArtifactPublisher(root) { source, destination ->
            destination.writeText(source.readText())
            if (!observedHiddenFinalDirectory) {
                assertFalse(File(root, "slice-1").exists())
                observedHiddenFinalDirectory = true
            }
        }

        val artifact = publisher.publish("slice-1", gcode, base, envelope())

        assertTrue(observedHiddenFinalDirectory)
        assertEquals("validated-output", artifact.gcodeFile.readText())
        assertEquals("validated-base", artifact.baseGcodeFile.readText())
        assertTrue(SliceArtifactPublisher.isCompleteGcode(artifact.gcodeFile, "slice-1"))
        assertEquals(envelope(), SliceArtifactPublisher.readPrinterEnvelope(artifact.baseGcodeFile))
    }

    @Test
    fun publishedArtifactDoesNotChangeWhenStagingFilesAreReused() {
        val root = kotlin.io.path.createTempDirectory("enderslicer-results").toFile()
        val sourceDirectory = kotlin.io.path.createTempDirectory("enderslicer-staging").toFile()
        val gcode = File(sourceDirectory, "output.gcode").apply { writeText("first-output") }
        val base = File(sourceDirectory, "base.gcode").apply { writeText("first-base") }
        val artifact = SliceArtifactPublisher(root).publish("slice-2", gcode, base, envelope())

        gcode.writeText("second-output")
        base.writeText("second-base")

        assertEquals("first-output", artifact.gcodeFile.readText())
        assertEquals("first-base", artifact.baseGcodeFile.readText())
    }

    @Test
    fun changingSourceDuringCopyCannotPublishACompletedArtifact() {
        val root = kotlin.io.path.createTempDirectory("enderslicer-results").toFile()
        val sourceDirectory = kotlin.io.path.createTempDirectory("enderslicer-staging").toFile()
        val gcode = File(sourceDirectory, "output.gcode").apply { writeText("validated-output") }
        val base = File(sourceDirectory, "base.gcode").apply { writeText("validated-base") }
        val publisher = SliceArtifactPublisher(root) { source, destination ->
            destination.writeText(source.readText())
            source.appendText("changed")
        }

        val error = runCatching { publisher.publish("slice-3", gcode, base, envelope()) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertFalse(File(root, "slice-3").exists())
        assertFalse(root.listFiles().orEmpty().any { it.name.contains("slice-3-publishing") })
    }

    @Test
    fun abandonedPublishingDirectoryIsRemovedBeforeTheNextPublication() {
        val root = kotlin.io.path.createTempDirectory("enderslicer-results").toFile()
        val abandoned = File(root, ".old-publishing-1").apply {
            mkdirs()
            File(this, "partial.gcode").writeText("partial")
        }
        val sourceDirectory = kotlin.io.path.createTempDirectory("enderslicer-staging").toFile()
        val gcode = File(sourceDirectory, "output.gcode").apply { writeText("validated-output") }
        val base = File(sourceDirectory, "base.gcode").apply { writeText("validated-base") }

        SliceArtifactPublisher(root).publish("slice-4", gcode, base, envelope())

        assertFalse(abandoned.exists())
    }

    @Test
    fun releasedArtifactWaitsForActiveLease() {
        val root = kotlin.io.path.createTempDirectory("enderslicer-results").toFile()
        val sourceDirectory = kotlin.io.path.createTempDirectory("enderslicer-staging").toFile()
        val gcode = File(sourceDirectory, "output.gcode").apply { writeText("validated-output") }
        val base = File(sourceDirectory, "base.gcode").apply { writeText("validated-base") }
        val publisher = SliceArtifactPublisher(root)
        val artifact = publisher.publish("slice-lease", gcode, base, envelope())

        val lease = SliceArtifactPublisher.acquireLease(artifact.gcodeFile)
        publisher.release(artifact.id)
        assertTrue(artifact.directory.isDirectory)

        lease.close()
        assertFalse(artifact.directory.exists())
    }

    @Test
    fun completedArtifactsAreBounded() {
        val root = kotlin.io.path.createTempDirectory("enderslicer-results").toFile()
        val sourceDirectory = kotlin.io.path.createTempDirectory("enderslicer-staging").toFile()
        val gcode = File(sourceDirectory, "output.gcode").apply { writeText("validated-output") }
        val base = File(sourceDirectory, "base.gcode").apply { writeText("validated-base") }
        val publisher = SliceArtifactPublisher(root)

        repeat(12) { index ->
            publisher.publish("slice-retained-$index", gcode, base, envelope())
        }

        assertTrue(root.listFiles().orEmpty().count(File::isDirectory) <= 8)
    }

    @Test
    fun missingEnvelopeMakesAnOtherwiseMarkedArtifactIncomplete() {
        val root = kotlin.io.path.createTempDirectory("enderslicer-results").toFile()
        val directory = File(root, "slice-5").apply { mkdirs() }
        val gcode = File(directory, SliceArtifactPublisher.GCODE_FILE_NAME).apply { writeText("gcode") }
        File(directory, SliceArtifactPublisher.COMPLETE_MARKER_FILE_NAME).writeText("slice-5")

        assertFalse(SliceArtifactPublisher.isCompleteGcode(gcode, "slice-5"))
    }

    private fun envelope(): PrinterEnvelope = PrinterEnvelope(
        widthMm = 230.0,
        depthMm = 230.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
    )
}
