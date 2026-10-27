package com.tomppi.enderslicer.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The network publish of the three agent bridges: a two-way byte pipe, one listener per engine
 * port on the detected address, and an honest state for a target that is not running.
 *
 * Hermetic: the fake engine lives on 127.0.0.2 and the proxy publishes on 127.0.0.1 - two loopback
 * addresses, so both can hold the SAME port, which is the whole point (the real engines hold
 * 127.0.0.1:9876..9878 and the proxy publishes on the device address at those same numbers).
 * No real Blender, no real CAD engine, no Tailscale and no network.
 */
class AgentBridgeProxyTest {

    private val targetAddress = "127.0.0.2"

    /** A port that is free on both loopback addresses the test uses. */
    private val port: Int = ServerSocket(0, 1, InetAddress.getByName(targetAddress)).use { it.localPort }

    private val logs = mutableListOf<String>()

    private val engines = mutableListOf<ServerSocket>()

    private fun proxy(addressProvider: () -> BridgeAccess.Access): AgentBridgeProxy {
        val proxy = AgentBridgeProxy(
            targets = listOf(AgentBridgeTarget("Fake engine", port)),
            addressProvider = addressProvider,
            targetHost = targetAddress,
            log = { message -> synchronized(logs) { logs += message } },
        )
        proxies += proxy
        return proxy
    }

    private val proxies = mutableListOf<AgentBridgeProxy>()

    /** A fake engine: greets each connection, then echoes, so both pipe directions are exercised. */
    private fun startFakeEngine(): ServerSocket {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName(targetAddress), port))
        engines += server
        thread(name = "fake-engine-accept", isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                thread(name = "fake-engine-client", isDaemon = true) {
                    try {
                        val output = client.getOutputStream()
                        output.write("from-engine\n".toByteArray())
                        output.flush()
                        val input = client.getInputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            output.flush()
                        }
                    } catch (_: Throwable) {
                        // The client hanging up is the end of this connection.
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }
        }
        return server
    }

    private fun reachable(address: String) = BridgeAccess.Access.Reachable(
        address = address,
        interfaceName = "test0",
        kind = BridgeAccess.Kind.LAN,
    )

    private fun lineOf(proxy: AgentBridgeProxy) = proxy.lines().single()

    @After
    fun cleanUp() {
        proxies.forEach { it.close() }
        engines.forEach { runCatching { it.close() } }
    }

    @Test
    fun forwardsBytesBothWaysOnTheDetectedAddressAtTheEnginesOwnPort() {
        startFakeEngine()
        val proxy = proxy { reachable("127.0.0.1") }
        proxy.setEnabled(true)

        assertEquals("127.0.0.1", proxy.boundAddress())
        assertTrue("the proxy did not listen on the engine's port", proxy.isListening(port))
        assertEquals("tcp://127.0.0.1:" + port, lineOf(proxy).line)
        assertEquals(BridgeLinkState.UP, lineOf(proxy).state)

        Socket("127.0.0.1", port).use { client ->
            client.soTimeout = 5_000
            val reader = client.getInputStream().bufferedReader()
            // engine -> the network direction, unsolicited
            assertEquals("from-engine", reader.readLine())
            // the network -> engine direction, and the echo back
            client.getOutputStream().write("from-agent\n".toByteArray())
            client.getOutputStream().flush()
            assertEquals("from-agent", reader.readLine())
        }
    }

    @Test
    fun aClosedTargetReadsAsEngineNotRunningAndNotAsADeadNetwork() {
        // Nothing is listening on the fake engine's port: the target is down, the proxy is not.
        val proxy = proxy { reachable("127.0.0.1") }
        proxy.setEnabled(true)
        proxy.refresh()

        assertTrue("the network listener must still be up", proxy.isListening(port))
        val line = lineOf(proxy)
        assertEquals(BridgeLinkState.ENGINE_NOT_RUNNING, line.state)
        assertEquals("engine not running", line.status)
        assertNotNull("a dead target still has a line to show", line.line)

        // The network side accepts the connection; the target refusal closes it instead of
        // leaving the caller thinking the network is dead.
        Socket("127.0.0.1", port).use { client ->
            client.soTimeout = 5_000
            assertEquals(-1, client.getInputStream().read())
        }
    }

    @Test
    fun turningTheSwitchOffClosesEveryListenerAndReleasesThePort() {
        startFakeEngine()
        val proxy = proxy { reachable("127.0.0.1") }
        proxy.setEnabled(true)
        assertTrue(proxy.isListening(port))

        proxy.setEnabled(false)

        assertFalse("the listener survived the switch turning off", proxy.isListening(port))
        assertNull(proxy.boundAddress())
        assertEquals(BridgeLinkState.OFF, lineOf(proxy).state)
        assertNull("off has no line to paste", lineOf(proxy).line)

        // The listener is really gone: its own address and port are bindable again. This is
        // the check that cannot be fooled by another process on the host holding that port at
        // the moment (a plain connect can be answered by anything, and was, once).
        //
        // Bounded rather than one shot: the kernel can take a moment to release a closed
        // listening socket, and a single bind raced with that on CI ("Address already in use",
        // 1.9.8's release build). A genuine leak still fails - this only waits up to two
        // seconds for the OS to let go, which no leak ever does.
        val deadline = System.currentTimeMillis() + 2_000
        var bindable = false
        var lastRefusal: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                }
                bindable = true
                break
            } catch (error: BindException) {
                lastRefusal = error
                Thread.sleep(25)
            }
        }
        assertTrue(
            "the port is bindable again once the listener is gone: " + lastRefusal,
            bindable,
        )
    }

    @Test
    fun anAddressChangeMovesTheListenerToTheNewAddress() {
        startFakeEngine()
        var address = "127.0.0.1"
        val proxy = proxy { reachable(address) }
        proxy.setEnabled(true)
        assertEquals("127.0.0.1", proxy.boundAddress())

        // Wi-Fi handed the device a new address: the listener follows it, the engine does not move.
        address = "127.0.0.3"
        proxy.refresh()

        assertEquals("127.0.0.3", proxy.boundAddress())
        assertTrue(proxy.isListening(port))
        assertEquals("tcp://127.0.0.3:" + port, lineOf(proxy).line)
        val oldRefused = runCatching { Socket("127.0.0.1", port).close() }.exceptionOrNull()
        assertTrue("the old address must stop answering", oldRefused is ConnectException)

        Socket("127.0.0.3", port).use { client ->
            client.soTimeout = 5_000
            assertEquals("from-engine", client.getInputStream().bufferedReader().readLine())
        }
    }

    @Test
    fun noAddressClosesTheListenersAndNamesTheReason() {
        val proxy = proxy { BridgeAccess.Access.Unreachable(BridgeAccess.NO_ADDRESS_REASON) }
        proxy.setEnabled(true)

        assertNull(proxy.boundAddress())
        assertFalse(proxy.isListening(port))
        val line = lineOf(proxy)
        assertEquals(BridgeLinkState.NO_ADDRESS, line.state)
        assertEquals(BridgeAccess.NO_ADDRESS_REASON, line.status)
        assertNull("there is no line to paste without an address", line.line)
    }

    @Test
    fun aFloodPastThePipeCapIsRefusedByNameInsteadOfSpawned() {
        startFakeEngine()
        val proxy = proxy { reachable("127.0.0.1") }
        proxy.setEnabled(true)
        val cap = proxy.maxConcurrentPipes()
        val clients = ArrayList<Socket>()
        try {
            repeat(cap) {
                val client = Socket("127.0.0.1", port)
                client.soTimeout = 5_000
                clients += client
            }
            // accept() and forward() are asynchronous: wait for the cap to be really in use.
            val deadline = System.currentTimeMillis() + 5_000
            while (proxy.activePipeCount() < cap && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertEquals("every allowed pipe is counted", cap, proxy.activePipeCount())

            Socket("127.0.0.1", port).use { extra ->
                extra.soTimeout = 5_000
                val line = extra.getInputStream().bufferedReader().readLine()
                assertNotNull("a refused connection gets a named line, not a hang", line)
                val refusal = line!!
                assertTrue("the refusal names the cap: " + refusal, refusal.contains("limit " + cap))
                assertTrue("the refusal says what happened: " + refusal, refusal.contains("refused"))
                assertTrue("it says which bridge: " + refusal, refusal.contains("port " + port))
                // Closed right after the one line.
                assertEquals(-1, runCatching { extra.getInputStream().read() }.getOrDefault(-1))
            }
            synchronized(logs) {
                assertTrue(
                    "the cap was not logged: " + logs,
                    logs.any { it.contains("refused a connection") },
                )
            }
            assertEquals("a refused connection is not counted as a pipe", cap, proxy.activePipeCount())
        } finally {
            clients.forEach { runCatching { it.close() } }
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (proxy.activePipeCount() != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("closing the clients releases every pipe", 0, proxy.activePipeCount())
    }

    @Test
    fun noPayloadIsEverLogged() {
        startFakeEngine()
        val proxy = proxy { reachable("127.0.0.1") }
        proxy.setEnabled(true)
        val secret = "SECRET-PAYLOAD-0123456789"

        Socket("127.0.0.1", port).use { client ->
            client.soTimeout = 5_000
            val reader = client.getInputStream().bufferedReader()
            assertEquals("from-engine", reader.readLine())
            client.getOutputStream().write((secret + "\n").toByteArray())
            client.getOutputStream().flush()
            assertEquals(secret, reader.readLine())
        }

        synchronized(logs) {
            assertTrue("the lifecycle was not logged at all", logs.any { it.contains("agent bridge up") })
            assertTrue(
                "a forwarded payload reached the log: " + logs,
                logs.none { it.contains(secret) || it.contains("from-engine") },
            )
        }
    }
}
