package com.tomppi.enderslicer.agent

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** One engine's loopback MCP port, and the names used for it. */
data class AgentBridgeTarget(
    /** The row label (and the toast's subject): "Blender MCP", "CAD engine", "SolidCraft". */
    val name: String,
    val port: Int,
    /**
     * The engine as a sentence names it - "the app's <engineName> engine" - which is why
     * "CAD engine" cannot be used here unchanged (it would read "CAD engine engine").
     */
    val engineName: String = name,
)

/** How one published engine bridge is doing, in the words the sheet prints. */
enum class BridgeLinkState {
    /** The switch is off; nothing is published and the loopback port is all there is. */
    OFF,

    /** The switch is on, but this device has no address another device could use. */
    NO_ADDRESS,

    /** The listeners are being moved to a new address, or a bind is being retried. */
    REBINDING,

    /** The listener is on the network address and the engine answers on its loopback port. */
    UP,

    /** The listener is on the network address but nothing answers on the engine's loopback port. */
    ENGINE_NOT_RUNNING,
}

/** One row in the sheet: the engine, the line to paste, and the honest state behind it. */
data class AgentBridgeLine(
    val target: AgentBridgeTarget,
    /** `tcp://<device address>:<port>`, or null when there is no line to show. */
    val line: String?,
    /** The device address behind [line]; null with it. Never the token - that is not in this model. */
    val address: String? = null,
    val state: BridgeLinkState,
    /** The sentence under the line: "app bridge up", "engine not running", the named reason... */
    val status: String,
)

/**
 * Republishes this device's agent engines on one network address, at their own loopback ports.
 *
 * The Blender MCP addon and the Python CAD engine are not ours to change: they bind 127.0.0.1
 * and stay that way. A phone with no adb cannot be reached from a laptop that way, which is why
 * reaching those two used to need an `adb forward` or a tailnet serve rule on the phone. This
 * proxy is the way out: when the CAD agent sheet's **Reachable on the network** switch is on,
 * the app listens on the device's own network address (the one [BridgeAccess] picks) at the
 * SAME port each engine uses - 9876, 9877, and the app's own SolidCraft bridge on 9878 - and
 * forwards every connection's bytes both ways to 127.0.0.1 at that port. Binding the network
 * address does not collide with the engine's loopback listener: they are different local
 * addresses on the same port, which is exactly why the numbers can stay identical and the line
 * to paste is as simple as `tcp://<device address>:9876`.
 *
 * **It is a transport, not a gate.** Each engine checks its own bearer token on every message,
 * exactly as it did on loopback; the proxy checks nothing, adds nothing and keeps nothing.
 * No bytes are logged, so a token in a forwarded message does not become a log line.
 *
 * The listeners follow the device: [refresh] re-reads the address, moves every listener when it
 * changed (Wi-Fi joins or leaves, a tailnet comes up) and retries a bind that failed;
 * [setEnabled] false closes every listener and leaves the loopback engines untouched.
 *
 * [targetHost] is 127.0.0.1 in the app and injectable only so tests can forward to a fake
 * engine on another loopback address without touching the real ports.
 */
class AgentBridgeProxy(
    private val targets: List<AgentBridgeTarget>,
    /** What the device currently has on the network, or the named reason it has nothing. */
    private val addressProvider: () -> BridgeAccess.Access,
    private val targetHost: String = "127.0.0.1",
    private val log: (String) -> Unit = {},
) : Closeable {

    private val lock = Any()

    @Volatile
    private var enabled = false

    /** The address every listener is bound to, or null while there is none. */
    @Volatile
    private var address: String? = null

    @Volatile
    private var noAddressReason: String? = null

    @Volatile
    private var bindProblem: String? = null

    private val listeners = HashMap<Int, ServerSocket>()

    /** The engine's loopback answer, last observed: true up, false down, absent untested. */
    private val engineRunning = ConcurrentHashMap<Int, Boolean>()

    fun isEnabled(): Boolean = enabled

    /** The address the listeners are on, or null. The sheet's lines carry it too. */
    fun boundAddress(): String? = address

    /** True when this proxy has a listening socket for [port] right now. */
    fun isListening(port: Int): Boolean = synchronized(lock) { listeners[port]?.isClosed == false }

    /** The first sentence describing why nothing is published, or null when it is. */
    fun problem(): String? = when {
        !enabled -> null
        address == null -> noAddressReason
        else -> bindProblem
    }

    /**
     * Turns the publishing on or off. On binds immediately (and reports through [problem] what
     * could not be done); off closes every listener before returning, so nothing survives the
     * switch being turned off.
     */
    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            this.enabled = enabled
            if (enabled) {
                bindAll()
                probeEngines()
            } else {
                closeListeners()
                engineRunning.clear()
                address = null
                noAddressReason = null
                bindProblem = null
            }
        }
    }

    /**
     * Re-reads the device's address and follows it: a changed address moves every listener, an
     * address that is gone closes them, and a bind that failed earlier is retried.
     */
    fun refresh() {
        synchronized(lock) {
            if (!enabled) return
            bindAll()
            probeEngines()
        }
    }

    /** The three (or however many) rows the sheet prints, with their live state. */
    fun lines(): List<AgentBridgeLine> = synchronized(lock) {
        targets.map { target ->
            when {
                !enabled -> AgentBridgeLine(target, null, state = BridgeLinkState.OFF, status = "off - this device only")
                address == null -> AgentBridgeLine(
                    target,
                    null,
                    state = BridgeLinkState.NO_ADDRESS,
                    status = noAddressReason ?: BridgeAccess.NO_ADDRESS_REASON,
                )
                listeners[target.port]?.isClosed != false -> AgentBridgeLine(
                    target,
                    BridgeAccess.connectLine(address!!, target.port),
                    address = address,
                    state = BridgeLinkState.REBINDING,
                    status = bindProblem ?: "address changed - rebinding…",
                )
                else -> {
                    val up = engineRunning[target.port] == true
                    AgentBridgeLine(
                        target,
                        BridgeAccess.connectLine(address!!, target.port),
                        address = address,
                        state = if (up) BridgeLinkState.UP else BridgeLinkState.ENGINE_NOT_RUNNING,
                        status = if (up) "app bridge up" else "engine not running",
                    )
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            enabled = false
            closeListeners()
            engineRunning.clear()
            address = null
            noAddressReason = null
            bindProblem = null
        }
    }

    /** Binds or moves every listener onto the address [addressProvider] reports right now. */
    private fun bindAll() {
        val access = addressProvider()
        val next = (access as? BridgeAccess.Access.Reachable)?.address
        if (next == null) {
            val reason = (access as? BridgeAccess.Access.Unreachable)?.reason
                ?: BridgeAccess.NO_ADDRESS_REASON
            if (address != null || listeners.isNotEmpty()) {
                log("no network address to publish the agent bridges on: " + reason)
            }
            closeListeners()
            address = null
            noAddressReason = reason
            return
        }
        noAddressReason = null
        val previous = address
        if (previous != null && previous != next) {
            log("the device address changed from " + previous + " to " + next + "; rebinding the agent bridges")
            closeListeners()
        }
        if (previous == null) {
            log("publishing the agent bridges on " + next)
        }
        address = next
        bindProblem = null
        for (target in targets) {
            val existing = listeners[target.port]
            if (existing != null && !existing.isClosed && existing.inetAddress?.hostAddress == next) continue
            runCatching { existing?.close() }
            listeners.remove(target.port)
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName(next), target.port))
                listeners[target.port] = socket
                thread(name = "agent-bridge-" + target.port, isDaemon = true) {
                    accept(target, socket)
                }
                log(
                    "agent bridge up: " + next + ":" + target.port +
                        " -> " + targetHost + ":" + target.port,
                )
            } catch (error: Throwable) {
                val sentence = "could not listen on " + next + ":" + target.port + ": " +
                    (error.message ?: error.javaClass.simpleName)
                bindProblem = sentence
                log(sentence)
            }
        }
    }

    /** One accept loop per published port; each connection becomes a two-way pipe. */
    private fun accept(target: AgentBridgeTarget, socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                break
            }
            thread(name = "agent-bridge-" + target.port + "-pipe", isDaemon = true) {
                forward(target, client)
            }
        }
    }

    /**
     * Connects the client to the engine's loopback port and copies bytes both ways.
     *
     * A target that refuses the connection is a dead engine, not a dead network: the client is
     * closed straight away and the sheet says "engine not running". Nothing about the payload is
     * read for logging - the buffers are only ever written to the other socket.
     */
    private fun forward(target: AgentBridgeTarget, client: Socket) {
        val engine = try {
            Socket().apply { connect(InetSocketAddress(targetHost, target.port), CONNECT_TIMEOUT_MILLIS) }
        } catch (_: Throwable) {
            engineRunning[target.port] = false
            runCatching { client.close() }
            return
        }
        engineRunning[target.port] = true
        try {
            thread(name = "agent-bridge-" + target.port + "-pipe-rev", isDaemon = true) {
                pump(engine, client)
            }
            pump(client, engine)
        } catch (_: Throwable) {
            // Either side ending is the connection ending; both are closed below.
        } finally {
            runCatching { client.close() }
            runCatching { engine.close() }
        }
    }

    /**
     * Copies one direction until EOF or an error, then closes both sockets.
     *
     * Closing both is what makes the other direction end too: one peer hanging up must end the
     * whole connection, not leave half of it waiting for a reply that cannot come. The buffer is
     * only ever written to the opposite socket - never formatted, never logged.
     */
    private fun pump(from: Socket, to: Socket) {
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (_: Throwable) {
            // A closed peer is how a connection ends; both sockets are closed in the finally.
        } finally {
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    /** Checks each engine's loopback port so the sheet can say whether it is running. */
    private fun probeEngines() {
        for (target in targets) {
            engineRunning[target.port] = runCatching {
                Socket().use { it.connect(InetSocketAddress(targetHost, target.port), PROBE_TIMEOUT_MILLIS) }
                true
            }.getOrDefault(false)
        }
    }

    private fun closeListeners() {
        for (socket in listeners.values) runCatching { socket.close() }
        listeners.clear()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 2_000
        const val PROBE_TIMEOUT_MILLIS = 250
        const val BUFFER_BYTES = 16 * 1024
    }
}
