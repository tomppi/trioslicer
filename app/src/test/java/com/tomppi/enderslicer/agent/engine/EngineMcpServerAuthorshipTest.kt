package com.tomppi.enderslicer.agent.engine

import com.tomppi.enderslicer.solidcraft.McpForwarder
import com.tomppi.enderslicer.solidcraft.SolidCraftAuthorship
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-author rule across the unified server: the document may be edited by the in-app
 * agent or by attached external connections, never both - and a connection is counted the
 * moment it would edit, so no editing connection is ever invisible to the rule.
 */
class EngineMcpServerAuthorshipTest {

    private class FakeSurface(override val id: String) : EngineToolSurface {
        override val displayName: String = id
        val calls = mutableListOf<String>()
        override fun isRunning(): Boolean = true
        override fun reachAndRules(): String = "rules"
        override fun tools(): org.json.JSONArray = org.json.JSONArray()
        override fun call(name: String, arguments: JSONObject, timeoutMillis: Long): JSONObject {
            calls += name
            return JSONObject().put("ran", name)
        }
    }

    private class SilentForwarder : McpForwarder {
        override fun request(method: String, params: JSONObject, timeoutMillis: Long): JSONObject =
            JSONObject().put("tools", org.json.JSONArray())

        override fun notify(method: String, params: JSONObject) {}
    }

    private fun callLine(id: Int, name: String, token: String? = "secret"): JSONObject {
        val message = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "tools/call")
            .put("params", JSONObject().put("name", name))
        if (token != null) message.put("token", token)
        return message
    }

    private fun selectLine(engine: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0").put("id", 90).put("method", "tools/call").put("token", "secret")
        .put(
            "params",
            JSONObject().put("name", "engine_select")
                .put("arguments", JSONObject().put("engine", engine)),
        )

    /** Reads until a message carrying an id: the list_changed broadcast can interleave. */
    private fun readReply(reader: java.io.BufferedReader): JSONObject {
        while (true) {
            val line = reader.readLine() ?: error("the connection closed before the reply")
            val message = JSONObject(line)
            if (message.has("id")) return message
        }
    }

    private fun server(authorship: SolidCraftAuthorship): Pair<EngineMcpServer, FakeSurface> {
        val solidcraft = FakeSurface("solidcraft")
        val server = EngineMcpServer(
            token = "secret",
            surfaces = listOf(
                solidcraft,
                FakeSurface("cad"),
                SolidCraftToolSurface(forwarder = { SilentForwarder() }, onDocumentChanged = {}),
            ).map { it },
            authorship = authorship,
            // An ephemeral port: a fixed one collides with whatever else is on this machine
            // (a developer's adb forward to a real device, for one).
            port = 0,
        )
        return server to solidcraft
    }

    @Test
    fun aSolidCraftCallDuringAnInAppTurnIsRefusedAndNeverRouted() {
        val authorship = SolidCraftAuthorship()
        check(authorship.beginInAppTurn())
        val (server, solidcraft) = server(authorship)

        val reply = JSONObject(requireNotNull(server.handleLine(callLine(1, "solid_tool").toString())))
        val error = reply.getJSONObject("error")

        assertEquals(EngineMcpServer.AUTHORSHIP_BUSY, error.getInt("code"))
        assertTrue(solidcraft.calls.isEmpty())
    }

    @Test
    fun theOtherEnginesKeepWorkingWhileTheInAppAgentEditsTheDocument() {
        val authorship = SolidCraftAuthorship()
        check(authorship.beginInAppTurn())
        val (server, solidcraft) = server(authorship)

        server.handleLine(selectLine("cad").toString())
        val reply = JSONObject(requireNotNull(server.handleLine(callLine(1, "cad_tool").toString())))

        assertTrue("the CAD engine is a different author", reply.has("result"))
        assertTrue(solidcraft.calls.isEmpty())
    }

    @Test
    fun aConnectionIsCountedWhileItIsUpAndReleasedWhenItGoes() {
        val authorship = SolidCraftAuthorship()
        val (server, _) = server(authorship)
        authorship.bindExternalCloser { server.closeAttachedConnections() }
        server.start()
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", server.boundPort), 2_000)
            Thread.sleep(150)
            assertTrue("the live connection blocks the in-app agent", !authorship.beginInAppTurn())

            socket.close()
            val deadline = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < deadline && !authorship.beginInAppTurn()) {
                Thread.sleep(20)
            }
            authorship.endInAppTurn()
        } finally {
            server.close()
        }
    }

    @Test
    fun takeOverClosesTheAttachedClientsAndTheNextConnectionIsServed() {
        val authorship = SolidCraftAuthorship()
        val (server, _) = server(authorship)
        authorship.bindExternalCloser { server.closeAttachedConnections() }
        server.start()
        try {
            val first = Socket().apply { connect(InetSocketAddress("127.0.0.1", server.boundPort), 2_000) }
            val second = Socket().apply { connect(InetSocketAddress("127.0.0.1", server.boundPort), 2_000) }
            Thread.sleep(150)

            val closed = authorship.takeOver()

            assertEquals("both attached clients went", 2, closed)
            assertTrue(first.getInputStream().read() < 0)
            assertTrue(second.getInputStream().read() < 0)
            val next = Socket().apply { connect(InetSocketAddress("127.0.0.1", server.boundPort), 2_000) }
            Thread.sleep(150)
            assertTrue("the next connection is counted again", !authorship.beginInAppTurn())
            authorship.endInAppTurn()
            next.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun aConnectionWhoseAttachLostTheRaceAttachesAtItsFirstEdit() {
        val authorship = SolidCraftAuthorship()
        check(authorship.beginInAppTurn())
        val (server, _) = server(authorship)
        server.start()
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", server.boundPort), 2_000)
            // The in-app turn is running, so the accept-time attach was refused: the first
            // SolidCraft call is refused with it, and the connection is never uncounted.
            val writer = socket.getOutputStream()
            val read = socket.getInputStream().bufferedReader()
            writer.write((callLine(1, "solid_tool").toString() + "\n").toByteArray())
            writer.flush()
            val reply = readReply(read)
            assertEquals(EngineMcpServer.AUTHORSHIP_BUSY, reply.getJSONObject("error").getInt("code"))

            // The CAD engine is not the document: the same connection keeps working on it.
            writer.write((selectLine("cad").toString() + "\n").toByteArray())
            writer.flush()
            readReply(read)
            writer.write((callLine(2, "cad_tool").toString() + "\n").toByteArray())
            writer.flush()
            assertTrue(readReply(read).has("result"))
            socket.close()
        } finally {
            authorship.endInAppTurn()
            server.close()
        }
    }
}
