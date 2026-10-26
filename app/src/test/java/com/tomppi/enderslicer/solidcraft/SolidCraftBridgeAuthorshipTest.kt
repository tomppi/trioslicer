package com.tomppi.enderslicer.solidcraft

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

/**
 * The bridge's side of the one-author rule, over a real loopback socket.
 *
 * The fake engine is the test's own [McpForwarder], so what is exercised is the bridge's
 * connection counting, its named refusal while the in-app agent drives, and Take over closing
 * the live clients. Hermetic: 127.0.0.1, a fake forwarder, no engine process.
 */
class SolidCraftBridgeAuthorshipTest {

    private class FakeForwarder : McpForwarder {
        var lastMethod: String? = null
        val requests = mutableListOf<String>()

        override fun request(method: String, params: JSONObject, timeoutMillis: Long): JSONObject {
            lastMethod = method
            requests += method
            return JSONObject().put("protocolVersion", "2025-06-18")
        }

        override fun notify(method: String, params: JSONObject) {
            requests += method
        }
    }

    private val forwarder = FakeForwarder()
    private val ownership = SolidCraftAuthorship()
    private val bridges = mutableListOf<SolidCraftBridge>()
    private val sockets = mutableListOf<Socket>()

    private fun bridge(): SolidCraftBridge {
        val bridge = SolidCraftBridge(
            token = "secret",
            forwarder = forwarder,
            onDocumentChanged = {},
            authorship = ownership,
            port = 0,
        )
        bridges += bridge
        bridge.start()
        assertTrue("bridge did not bind", bridge.boundPort > 0)
        return bridge
    }

    private fun Socket.send(json: JSONObject) {
        getOutputStream().write((json.toString() + "\n").toByteArray())
        getOutputStream().flush()
    }

    private fun Socket.readLine(): String? {
        soTimeout = 5_000
        return getInputStream().bufferedReader().readLine()
    }

    private fun connect(bridge: SolidCraftBridge): Socket =
        Socket("127.0.0.1", bridge.boundPort).also { socket ->
            sockets += socket
        }

    private fun initialize(id: Int): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("method", "initialize")
        .put("token", "secret")
        .put("params", JSONObject())

    private fun awaitExternalCount(bridge: SolidCraftBridge, wanted: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (bridge.externalClientCount() == wanted) return
            Thread.sleep(20)
        }
        assertEquals(wanted, bridge.externalClientCount())
    }

    @After
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        bridges.forEach { runCatching { it.close() } }
    }

    @Test
    fun aLiveConnectionIsCountedAndDisconnectingBringsTheCountBack() {
        val bridge = bridge()
        assertEquals(0, ownership.state().externalClients)
        val socket = connect(bridge)
        socket.send(initialize(1))
        assertEquals(1, JSONObject(socket.readLine()!!).getInt("id"))
        awaitExternalCount(bridge, 1)
        assertEquals(SolidCraftAuthorship.Author.EXTERNAL, ownership.state().author)

        socket.close()
        awaitExternalCount(bridge, 0)
        assertEquals(SolidCraftAuthorship.Author.NOBODY, ownership.state().author)
    }

    @Test
    fun aConnectionDuringAnInAppTurnIsRefusedWithTheNamedErrorAndNeverForwarded() {
        val bridge = bridge()
        assertTrue(ownership.beginInAppTurn())
        val socket = connect(bridge)
        socket.send(initialize(1))
        val reply = JSONObject(socket.readLine()!!)
        val error = reply.optJSONObject("error") ?: error("expected a JSON-RPC error, got " + reply)
        assertEquals(SolidCraftBridge.AUTHORSHIP_BUSY, error.getInt("code"))
        assertTrue(error.getString("message").contains("in-app agent is driving"))
        assertTrue(error.getString("message").contains("retry when its turn finishes"))
        assertEquals(1, reply.getInt("id"))
        assertNull("the refusal is not a queued call", forwarder.lastMethod)
        // The refused connection is not an attached author, and it is closed after the answer.
        assertEquals(0, bridge.externalClientCount())
        assertEquals(SolidCraftAuthorship.Author.IN_APP, ownership.state().author)
        ownership.endInAppTurn()
    }

    @Test
    fun anAttachedClientIsNeverDisconnectedToMakeRoomForTheInAppAgent() {
        val bridge = bridge()
        val socket = connect(bridge)
        socket.send(initialize(1))
        socket.readLine()

        assertFalse("an attached client blocks the in-app turn", ownership.beginInAppTurn())
        assertEquals(1, bridge.externalClientCount())
        // The same live connection still works: nothing dropped or queued it.
        socket.send(initialize(2))
        assertEquals(2, JSONObject(socket.readLine()!!).getInt("id"))
    }

    @Test
    fun takeOverClosesTheLiveClientsAndTheNextConnectionIsServed() {
        val bridge = bridge()
        val first = connect(bridge)
        first.send(initialize(1))
        first.readLine()
        awaitExternalCount(bridge, 1)

        val closed = bridge.closeExternalClients()
        assertEquals(1, closed)
        assertEquals("detached before the sockets close", 0, ownership.state().externalClients)
        assertEquals("Author: nobody", ownership.state().authorLine)
        assertNull("the closed client sees EOF", first.readLine())

        val second = connect(bridge)
        second.send(initialize(2))
        assertEquals(2, JSONObject(second.readLine()!!).getInt("id"))
        assertTrue(ownership.attachExternal()) // and the authorship is open again
        ownership.detachExternal()
    }

    @Test
    fun handleLineRefusesTheTurnEvenWithoutASocket() {
        val bridge = bridge()
        assertTrue(ownership.beginInAppTurn())
        val reply = JSONObject(bridge.handleLine(initialize(7).toString())!!)
        assertEquals(SolidCraftBridge.AUTHORSHIP_BUSY, reply.getJSONObject("error").getInt("code"))
        assertNull(forwarder.lastMethod)
        ownership.endInAppTurn()

        // And the door opens the moment the turn ends.
        val accepted = JSONObject(bridge.handleLine(initialize(8).toString())!!)
        assertTrue(accepted.has("result"))
        assertEquals("initialize", forwarder.lastMethod)
    }

    @Test
    fun theTakeOverIsRefusedWhileTheInAppAgentIsTheAuthor() {
        bridge()
        assertTrue(ownership.beginInAppTurn())
        try {
            ownership.takeOver()
            org.junit.Assert.fail("expected the authorship to refuse its own turn's takeover")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("already the author"))
        } finally {
            ownership.endInAppTurn()
        }
    }
}
