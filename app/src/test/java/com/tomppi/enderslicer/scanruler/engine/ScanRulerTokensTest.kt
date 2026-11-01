package com.tomppi.enderslicer.scanruler.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScanRulerTokensTest {

    @Test
    fun theMcpTokenIsCreatedOnceAndStaysTheSame() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, ScanRulerTokens.DIRECTORY).deleteRecursively()
        val first = ScanRulerTokens.ensureMcpToken(context)
        val second = ScanRulerTokens.ensureMcpToken(context)
        assertTrue(first != null && first.length == 32)
        assertEquals(first, second)
        val file = ScanRulerTokens.mcpTokenFile(context)
        assertTrue(file.isFile)
        assertEquals(first, file.readText().trim())
        assertTrue(file.canonicalPath.contains("/files/scanruler/"))
        File(context.filesDir, ScanRulerTokens.DIRECTORY).deleteRecursively()
    }

    @Test
    fun theAgentInstructionsNameScanRulerAndTheNoteTools() {
        val text = ScanRulerEmbeddedSession.AGENT_INSTRUCTIONS
        assertTrue(text.contains("ScanRuler"))
        assertTrue(text.contains("reverse_note_list"))
        assertTrue(text.contains("Download/ScanRuler"))
    }
}