package com.tomppi.enderslicer.agent

import android.content.ClipDescription
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.agent.engine.EngineMcpServer
import com.tomppi.enderslicer.scanruler.engine.ScanRulerMcpServer
import com.tomppi.enderslicer.scanruler.engine.ScanRulerTokens
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

    private fun enginesFile() = EngineMcpServer.tokenFile(context.filesDir)
    private fun scanRulerFile() = ScanRulerTokens.mcpTokenFile(context)

    private fun writeToken(file: File, value: String) {
        file.parentFile?.mkdirs()
        file.writeText(value)
    }

    @Before
    fun clearTokenFiles() {
        listOf(enginesFile(), scanRulerFile()).forEach { runCatching { it.delete() } }
    }

    @Test
    fun theCopiedLineIsTheAddressThePortAndThatEnginesOwnToken() {
        val cases = listOf(
            Triple(EngineMcpServer.DEFAULT_PORT, "engines-token-0123456789abcdef", enginesFile()),
            Triple(ScanRulerMcpServer.DEFAULT_PORT, "scanruler-token-0123456789abcdef", scanRulerFile()),
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
            val ready = AgentBridgeCopy.line(address, EngineMcpServer.DEFAULT_PORT, token)
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
        writeToken(enginesFile(), "  engines-token-1  \n")
        writeToken(scanRulerFile(), "scanruler-token-4\n")

        assertEquals("engines-token-1", AgentBridgeTokens.read(context, EngineMcpServer.DEFAULT_PORT))
        assertEquals("scanruler-token-4", AgentBridgeTokens.read(context, ScanRulerMcpServer.DEFAULT_PORT))
        assertEquals(
            setOf(EngineMcpServer.DEFAULT_PORT, ScanRulerMcpServer.DEFAULT_PORT),
            AgentBridgeTokens.available(context),
        )
    }

    @Test
    fun aNeverStartedEngineHasNoTokenAndItsRowCannotCopy() {
        assertNull(AgentBridgeTokens.read(context, EngineMcpServer.DEFAULT_PORT))
        assertEquals(emptySet<Int>(), AgentBridgeTokens.available(context))
        val ready = AgentBridgeCopy.line(
            address,
            EngineMcpServer.DEFAULT_PORT,
            AgentBridgeTokens.read(context, EngineMcpServer.DEFAULT_PORT),
        )
        assertEquals(
            AgentBridgeCopy.MISSING_TOKEN_REASON,
            (ready as AgentBridgeCopy.Ready.MissingToken).reason,
        )
    }

    @Test
    fun theClipIsMarkedSensitiveAndCarriesTheExactLine() {
        val token = "solidcraft-token-0123456789abcdef"
        val ready = AgentBridgeCopy.line(address, EngineMcpServer.DEFAULT_PORT, token)
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
        writeToken(enginesFile(), token)

        // What the sheet renders is the model below: name, URL, state and sentence. It has no
        // token field, and none of its fields may carry the value.
        val row = AgentBridgeLine(
            target = AgentBridgeTarget("Modelling engines", EngineMcpServer.DEFAULT_PORT),
            line = "tcp://" + address + ":" + EngineMcpServer.DEFAULT_PORT,
            address = address,
            state = BridgeLinkState.UP,
            status = "app bridge up",
        )
        listOf(row.target.name, row.line.orEmpty(), row.status, row.toString()).forEach { shown ->
            assertFalse("the token reached a rendered field: " + shown, shown.contains(token))
        }

        // The only product that carries it is the clipboard payload, exactly once.
        ShadowLog.clear()
        val read = AgentBridgeTokens.read(context, EngineMcpServer.DEFAULT_PORT)
        val ready = AgentBridgeCopy.line(address, EngineMcpServer.DEFAULT_PORT, read)
            as AgentBridgeCopy.Ready.Line
        assertEquals("the token must appear exactly once in the payload", 1, ready.text.split(token).size - 1)
        assertTrue(
            "reading and formatting a token must not log it",
            ShadowLog.getLogs().none { log -> log.msg?.contains(token) == true },
        )
    }
}
