package com.tomppi.enderslicer.solidcraft

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The stdio JSON-RPC client's contract, without a process in the way.
 *
 * A scripted transport plays the engine: it records what the client wrote and lets each test
 * deliver the exact answer - in order, out of order, never, malformed - the protocol is
 * supposed to survive.
 */
class McpClientTest {

    /** A transport whose engine side is the test: lines in, lines delivered by hand. */
    private class ScriptedTransport : McpTransport {
        private val inbound = LinkedBlockingQueue<String>()
        val written = mutableListOf<JSONObject>()
        private val writes = AtomicInteger(0)
        val writeCount: Int get() = writes.get()

        /** Auto-answer every request with this, on the client's write thread, when set. */
        @Volatile
        var respond: ((JSONObject) -> JSONObject?)? = null

        override fun write(line: String) {
            val message = JSONObject(line)
            written += message
            writes.incrementAndGet()
            respond?.invoke(message)?.let { deliver(it.toString()) }
        }

        fun deliver(line: String) {
            inbound.put(line)
        }

        fun eof() {
            inbound.put(EOF)
        }

        override fun readLine(): String? {
            val line = inbound.poll(5, TimeUnit.SECONDS) ?: return null
            return if (line == EOF) null else line
        }

        override fun close() {
            runCatching { inbound.put(EOF) }
        }

        private companion object {
            const val EOF = "\u0000EOF"
        }
    }

    private fun response(id: Long, result: JSONObject): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()

    @Test
    fun requestsCarryJsonrpcAnIncreasingIdAndParams() {
        val transport = ScriptedTransport()
        transport.respond = { request ->
            JSONObject().put("jsonrpc", "2.0").put("id", request.getLong("id"))
                .put("result", JSONObject().put("method", request.optString("method")))
        }
        McpClient(transport) { }.start().use { client ->
            val first = client.request("initialize", JSONObject().put("protocolVersion", "2025-06-18"))
            val second = client.request("tools/list")
            assertEquals("initialize", first.optString("method"))
            assertEquals("tools/list", second.optString("method"))
        }
        assertEquals(2, transport.written.size)
        assertEquals("2.0", transport.written[0].optString("jsonrpc"))
        assertEquals(1L, transport.written[0].optLong("id"))
        assertEquals(2L, transport.written[1].optLong("id"))
        assertEquals("2025-06-18", transport.written[0].getJSONObject("params").optString("protocolVersion"))
    }

    @Test
    fun answersThatArriveOutOfOrderLandOnTheirOwnRequest() {
        val transport = ScriptedTransport()
        val client = McpClient(transport) { }.start()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            // Both requests are in flight at once, as an export and a status poll are when an
            // agent is mid-edit; the engine may answer them in either order.
            val first = pool.submit<JSONObject> {
                client.request("one", timeoutMillis = 3_000)
            }
            Thread.sleep(100)
            val second = pool.submit<JSONObject> {
                client.request("two", timeoutMillis = 3_000)
            }
            Thread.sleep(100)
            assertEquals(2, transport.written.size)
            transport.deliver(response(transport.written[1].getLong("id"), JSONObject().put("value", 2)))
            transport.deliver(response(transport.written[0].getLong("id"), JSONObject().put("value", 1)))
            assertEquals(1, first.get(3, TimeUnit.SECONDS).optInt("value"))
            assertEquals(2, second.get(3, TimeUnit.SECONDS).optInt("value"))
        } finally {
            pool.shutdownNow()
            client.close()
        }
    }

    @Test
    fun aRequestWithNoAnswerTimesOutAndNamesItself() {
        val transport = ScriptedTransport()
        val client = McpClient(transport) { }.start()
        try {
            client.request("slow.export", timeoutMillis = 250)
            fail("expected a timeout")
        } catch (timeout: McpTimeoutException) {
            assertEquals("slow.export", timeout.method)
            assertEquals(250, timeout.timeoutMillis)
            assertTrue(timeout.message!!.contains("slow.export"))
        } finally {
            client.close()
        }
    }

    @Test
    fun aLateAnswerAfterATimeoutIsIgnoredAndTheSessionKeepsWorking() {
        val transport = ScriptedTransport()
        val client = McpClient(transport) { }.start()
        try {
            client.request("slow", timeoutMillis = 150)
        } catch (_: McpTimeoutException) {
            // expected
        }
        transport.deliver(response(transport.written[0].getLong("id"), JSONObject().put("value", 99)))
        Thread.sleep(100)
        transport.respond = { request ->
            JSONObject().put("jsonrpc", "2.0").put("id", request.getLong("id"))
                .put("result", JSONObject().put("value", 1))
        }
        val next = client.request("next", timeoutMillis = 1_000)
        assertEquals(1, next.optInt("value"))
        client.close()
    }

    @Test
    fun aJsonRpcErrorKeepsTheEnginesCodeAndMessage() {
        val transport = ScriptedTransport()
        transport.respond = { request ->
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", request.getLong("id"))
                .put(
                    "error",
                    JSONObject().put("code", -32602).put("message", "missing tool name"),
                )
        }
        val client = McpClient(transport) { }.start()
        try {
            client.request("tools/call")
            fail("expected the engine's refusal")
        } catch (refusal: McpRemoteException) {
            assertEquals(-32602, refusal.code)
            assertEquals("missing tool name", refusal.remoteMessage)
            assertTrue(refusal.message!!.contains("missing tool name"))
        } finally {
            client.close()
        }
    }

    @Test
    fun aMalformedLineIsSkippedAndTheAnswerAfterItIsDelivered() {
        val transport = ScriptedTransport()
        val client = McpClient(transport) { }.start()
        transport.respond = { request ->
            // A malformed line and an empty one ahead of the real answer, as a wedged write
            // would leave them; they are delivered on the write, before the client waits.
            transport.deliver("{ this is not json")
            transport.deliver("")
            JSONObject().put("jsonrpc", "2.0").put("id", request.getLong("id"))
                .put("result", JSONObject().put("value", 7))
        }
        val pending = client.request("tools/call", timeoutMillis = 2_000)
        assertEquals(7, pending.optInt("value"))
        client.close()
    }

    @Test
    fun endOfStreamFailsEveryPendingRequestInsteadOfHangingIt() {
        val transport = ScriptedTransport()
        val client = McpClient(transport) { }.start()
        val waiter = Thread {
            try {
                client.request("never", timeoutMillis = 10_000)
            } catch (_: McpTransportException) {
                // expected: the engine exited under the call
            }
        }
        waiter.start()
        Thread.sleep(150)
        transport.eof()
        waiter.join(2_000)
        assertTrue("the waiter was left hanging", !waiter.isAlive)
        client.close()
    }

    @Test
    fun aNotificationWritesWithoutAnIdAndExpectsNoReply() {
        val transport = ScriptedTransport()
        val client = McpClient(transport) { }.start()
        client.notify("notifications/initialized")
        assertEquals(1, transport.writeCount)
        assertTrue(!transport.written[0].has("id"))
        assertEquals("notifications/initialized", transport.written[0].optString("method"))
        client.close()
    }

    @Test
    fun closingTheClientRefusesFurtherRequests() {
        val transport = ScriptedTransport()
        val client = McpClient(transport) { }.start()
        client.close()
        try {
            client.request("late")
            fail("expected a closed-session failure")
        } catch (refused: IllegalStateException) {
            assertTrue(refused.message!!.contains("closed"))
        }
    }
}
