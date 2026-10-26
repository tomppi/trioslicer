package com.tomppi.enderslicer.agent

import android.content.ClipDescription
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.nativebridge.BlenderEngine
import com.tomppi.enderslicer.nativebridge.CadEngine
import com.tomppi.enderslicer.solidcraft.SolidCraftBridge
import com.tomppi.enderslicer.solidcraft.SolidCraftEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * The one-paste hand-over: each row's Copy line carries the address and THAT engine's token,
 * the missing-token row copies nothing and says why, the clip is marked sensitive, and the
 * token never reaches the UI model or a log.
 *
 * The clipboard itself is exercised through [AgentBridgeCopy.sensitiveClip] under Robolectric,
 * so the whole string and the flag are pinned without a device; the walk then checks the
 * sheet, the prompt and the toast on the phone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AgentBridgeHandoverTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val address = "100.64.13.7"

    private fun blenderFile() = BlenderEngine.tokenFile(File(context.filesDir, "blender"))
    private fun cadFile() = CadEngine.tokenFile(File(context.filesDir, "cad"))
    private fun solidCraftFile() = SolidCraftEngine.tokenFile(context)

    private fun writeToken(file: File, value: String) {
        file.parentFile?.mkdirs()
        file.writeText(value)
    }

    @Before
    fun clearTokenFiles() {
        listOf(blenderFile(), cadFile(), solidCraftFile()).forEach { runCatching { it.delete() } }
    }

    @Test
    fun theCopiedLineIsTheAddressThePortAndThatEnginesOwnToken() {
        val cases = listOf(
            Triple(BlenderEngine.DEFAULT_MCP_PORT, "blender-token-0123456789abcdef", blenderFile()),
            Triple(CadEngine.DEFAULT_PORT, "cad-token-0123456789abcdef", cadFile()),
            Triple(SolidCraftBridge.DEFAULT_PORT, "solidcraft-token-0123456789abcdef", solidCraftFile()),
        )
        for ((port, token, file) in cases) {
            writeToken(file, token + "\n")
            val ready = AgentBridgeCopy.line(address, port, AgentBridgeTokens.read(context, port))
            assertTrue("row $port did not have a token to copy", ready is AgentBridgeCopy.Ready.Line)
            val text = (ready as AgentBridgeCopy.Ready.Line).text
            assertEquals("tcp://" + address + ":" + port + " token=" + token, text)
            assertEquals("the line has trailing whitespace", text, text.trim())
            assertFalse("the line is more than one line", text.contains('\n'))
            assertFalse("the line has a carriage return", text.contains('\r'))
            assertEquals("exactly one space between the URL and the token", 1, text.count { it == ' ' })
        }
    }

    @Test
    fun aRowWithoutATokenCopiesNothingAndNamesTheReason() {
        for (token in listOf(null, "", "   ", "\n\t")) {
            val ready = AgentBridgeCopy.line(address, SolidCraftBridge.DEFAULT_PORT, token)
            assertTrue("token '$token' must not produce a line", ready is AgentBridgeCopy.Ready.MissingToken)
            assertEquals(
                AgentBridgeCopy.MISSING_TOKEN_REASON,
                (ready as AgentBridgeCopy.Ready.MissingToken).reason,
            )
            assertFalse(ready.reason.contains("tcp://"))
        }
    }

    @Test
    fun theTokensAreReadFromEachEnginesOwnFileAndTrimmed() {
        writeToken(blenderFile(), "  blender-token-1  \n")
        writeToken(cadFile(), "cad-token-2\n")
        writeToken(solidCraftFile(), "solidcraft-token-3\n")

        assertEquals("blender-token-1", AgentBridgeTokens.read(context, BlenderEngine.DEFAULT_MCP_PORT))
        assertEquals("cad-token-2", AgentBridgeTokens.read(context, CadEngine.DEFAULT_PORT))
        assertEquals("solidcraft-token-3", AgentBridgeTokens.read(context, SolidCraftBridge.DEFAULT_PORT))
        assertEquals(
            setOf(BlenderEngine.DEFAULT_MCP_PORT, CadEngine.DEFAULT_PORT, SolidCraftBridge.DEFAULT_PORT),
            AgentBridgeTokens.available(context),
        )
    }

    @Test
    fun aNeverStartedEngineHasNoTokenAndItsRowCannotCopy() {
        assertNull(AgentBridgeTokens.read(context, BlenderEngine.DEFAULT_MCP_PORT))
        assertEquals(emptySet<Int>(), AgentBridgeTokens.available(context))
        val ready = AgentBridgeCopy.line(
            address,
            BlenderEngine.DEFAULT_MCP_PORT,
            AgentBridgeTokens.read(context, BlenderEngine.DEFAULT_MCP_PORT),
        )
        assertEquals(
            AgentBridgeCopy.MISSING_TOKEN_REASON,
            (ready as AgentBridgeCopy.Ready.MissingToken).reason,
        )
    }

    @Test
    fun theClipIsMarkedSensitiveAndCarriesTheExactLine() {
        val token = "solidcraft-token-0123456789abcdef"
        val ready = AgentBridgeCopy.line(address, SolidCraftBridge.DEFAULT_PORT, token)
            as AgentBridgeCopy.Ready.Line
        val clip = AgentBridgeCopy.sensitiveClip("SolidCraft connect line", ready.text)

        assertEquals(1, clip.itemCount)
        assertEquals(ready.text, clip.getItemAt(0).text.toString())
        assertEquals("SolidCraft connect line", clip.description.label.toString())
        assertTrue(
            "the clip must ask Android not to preview it",
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true,
        )
        assertFalse(
            "the clip label must not carry the token",
            clip.description.label.toString().contains(token),
        )
    }

    @Test
    fun theTokenIsOnlyEverInTheClipboardPayloadNotInTheUiModelOrLogs() {
        val token = "solidcraft-token-0123456789abcdef"
        writeToken(solidCraftFile(), token)

        // What the sheet renders is the model below: name, URL, state and sentence. It has no
        // token field, and none of its fields may carry the value.
        val row = AgentBridgeLine(
            target = AgentBridgeTarget("SolidCraft", SolidCraftBridge.DEFAULT_PORT),
            line = "tcp://" + address + ":" + SolidCraftBridge.DEFAULT_PORT,
            address = address,
            state = BridgeLinkState.UP,
            status = "app bridge up",
        )
        listOf(row.target.name, row.line.orEmpty(), row.status, row.toString()).forEach { shown ->
            assertFalse("the token reached a rendered field: " + shown, shown.contains(token))
        }

        // The only product that carries it is the clipboard payload, exactly once.
        ShadowLog.clear()
        val read = AgentBridgeTokens.read(context, SolidCraftBridge.DEFAULT_PORT)
        val ready = AgentBridgeCopy.line(address, SolidCraftBridge.DEFAULT_PORT, read)
            as AgentBridgeCopy.Ready.Line
        assertEquals("the token must appear exactly once in the payload", 1, ready.text.split(token).size - 1)
        assertTrue(
            "reading and formatting a token must not log it",
            ShadowLog.getLogs().none { log -> log.msg?.contains(token) == true },
        )
    }
}
