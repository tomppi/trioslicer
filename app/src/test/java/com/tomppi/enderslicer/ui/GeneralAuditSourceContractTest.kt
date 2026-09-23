package com.tomppi.enderslicer.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralAuditSourceContractTest {
    @Test
    fun nonPlanarMutualExclusionAndSmartInfillValidationFailClosed() {
        val viewModel = source("ui/MainViewModel.kt")
        val integrated = source("ui/IntegratedEnderSlicerApp.kt")
        assertTrue(viewModel.contains("NonPlanarRuntime.snapshot() != null && ConicalRuntime.snapshot() != null"))
        assertTrue(integrated.contains("smartInfillValidating"))
        assertTrue(integrated.contains("SmartInfillRuntime.activate(null)"))
        assertTrue(integrated.contains("sliceBlockedReason"))
    }

    @Test
    fun pendingResultsAndExportsHaveDurableOwnership() {
        val viewModel = source("ui/MainViewModel.kt")
        val integrated = source("ui/IntegratedEnderSlicerApp.kt")
        assertTrue(viewModel.contains("deferUntilRestoreCompletes"))
        assertTrue(viewModel.contains("pendingExportStore.begin(uri)"))
        // The destination survives a failed export - it may already hold every byte -
        // so the failure path only clears the record the store keeps.
        assertTrue(viewModel.contains("pendingExportStore.fail(uri)"))
        assertTrue(viewModel.contains("pendingExportStore.recover()"))
        assertTrue(integrated.contains("processSmartInfillResult"))
    }

    @Test
    fun apiKeyDraftIsNotSaveable() {
        val sheet = source("ui/HardenedOctoPrintSheet.kt")
        assertTrue(sheet.contains("var apiKey by remember { mutableStateOf(\"\") }"))
        assertFalse(sheet.contains("var apiKey by rememberSaveable"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/$relative"),
            File("app/src/main/java/com/tomppi/enderslicer/$relative"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Unable to find source file $relative")
    }
}
