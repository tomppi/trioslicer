package com.tomppi.enderslicer.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundTwoUiContractTest {
    @Test
    fun eventRepublicationAndConsumersUseOneCompletedArtifactIdentity() {
        val viewModel = source("ui/MainViewModel.kt")
        val state = source("ui/MainUiState.kt")
        val integrated = source("ui/IntegratedEnderSlicerApp.kt")
        val publisher = source("engine/SliceArtifactPublisher.kt")

        assertTrue(viewModel.contains("sliceResultId = result.artifactId"))
        assertTrue(viewModel.contains("isCompleteGcode(source, expectedArtifactId)"))
        // Completeness is computed once when the result is stored, on the engine's IO
        // path; hasCurrentGcode() reads cached state, so a Compose body never does file
        // I/O (it used to check the artifact and parse its metadata on every call).
        assertTrue(viewModel.contains("SliceArtifactPublisher.isCompleteGcode("))
        assertTrue(state.contains("val gcodeComplete: Boolean = false"))
        assertTrue(
            state.contains(
                "fun hasCurrentGcode(): Boolean = sliceResultId != null && gcodePath != null && gcodeComplete",
            ),
        )
        assertFalse(state.contains("isCompleteGcode"))
        assertTrue(integrated.contains("slicerState.hasCurrentGcode()"))
        assertFalse(publisher.contains("return@synchronized Closeable { }"))
    }

    @Test
    fun nozzlePathSurfaceForwardsLifecycleAndReleasesThePlatformView() {
        val source = source("ui/NozzlePathView.kt")
        assertTrue(source.contains("LifecycleEventObserver"))
        assertTrue(source.contains("Lifecycle.Event.ON_RESUME -> view.onResume()"))
        assertTrue(source.contains("-> view.onPause()"))
        assertTrue(source.contains("onRelease = { view ->"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/$relative"),
            File("app/src/main/java/com/tomppi/enderslicer/$relative"),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate source file for $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
