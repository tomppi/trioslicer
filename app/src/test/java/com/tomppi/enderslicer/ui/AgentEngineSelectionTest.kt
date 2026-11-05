package com.tomppi.enderslicer.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.agent.engine.AgentEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The chat window's engine menu: the selection follows the workspace in both directions, and
 * a switch re-binds the tool host without touching the conversation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AgentEngineSelectionTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun theConversationStartsOnSolidcraft() {
        context.applicationInfo.nativeLibraryDir = context.cacheDir.absolutePath
        val viewModel = MainViewModel(context)

        assertEquals(AgentEngine.SOLIDCRAFT, viewModel.agentEngine)
    }

    @Test
    fun pickingAModellingEngineStaysOnThePlateAndRebindsTheHost() {
        context.applicationInfo.nativeLibraryDir = context.cacheDir.absolutePath
        val viewModel = MainViewModel(context)
        val conversation = viewModel.cadConversations

        viewModel.selectAgentEngine(AgentEngine.BLENDER)

        assertEquals(AgentEngine.BLENDER, viewModel.agentEngine)
        assertEquals(PlateView.PLATE, viewModel.uiState.value.activeView)
        assertNull("the next turn binds the new engine's host", viewModel.inAppCadAgent)
        assertEquals("the conversation survives the switch", conversation, viewModel.cadConversations)
    }

    @Test
    fun pickingScanRulerMovesToItsWorkspaceAndBack() {
        context.applicationInfo.nativeLibraryDir = context.cacheDir.absolutePath
        val viewModel = MainViewModel(context)

        viewModel.selectAgentEngine(AgentEngine.SCANRULER)
        assertEquals(AgentEngine.SCANRULER, viewModel.agentEngine)
        assertEquals(PlateView.SCAN_RULER, viewModel.uiState.value.activeView)

        // The workspace switch that leaves ScanRuler returns to the last modelling engine.
        viewModel.selectAgentEngine(AgentEngine.CAD)
        assertEquals(AgentEngine.CAD, viewModel.agentEngine)
        assertEquals(PlateView.PLATE, viewModel.uiState.value.activeView)
    }
}
