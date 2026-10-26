package com.tomppi.enderslicer.agent

import android.content.Context
import android.util.Log
import com.tomppi.enderslicer.nativebridge.BlenderEngine
import com.tomppi.enderslicer.nativebridge.CadEngine
import com.tomppi.enderslicer.solidcraft.SolidCraftBridge
import kotlin.concurrent.thread

/**
 * The app's three agent bridges, published together on the device's network address.
 *
 * One setting owns all of them because they are one promise to the user: with **Reachable on
 * the network** on, the agent lines this app prints for Blender (9876), the Python CAD engine
 * (9877) and the SolidCraft document engine (9878) work from another device with just the
 * token. Each engine keeps binding 127.0.0.1 - none of them is ours to change - and
 * [AgentBridgeProxy] forwards the network side to it at the same port.
 *
 * The setting is stored in [BridgeNetworkPreference], so it survives a relaunch:
 * [ensureStarted] runs at app start and puts the listeners back up without the sheet, and
 * [setEnabled] applies a flip immediately. A watcher re-reads the device's address every few
 * seconds, so a Wi-Fi change moves the listeners instead of leaving a stale line in the sheet.
 */
object AgentBridges {

    private const val TAG = "AgentBridges"
    private const val WATCH_MILLIS = 3_000L

    /** The engines and the loopback ports they listen on; the sheet lists one line each. */
    val TARGETS = listOf(
        AgentBridgeTarget("Blender MCP", BlenderEngine.DEFAULT_MCP_PORT),
        AgentBridgeTarget("CAD engine", CadEngine.DEFAULT_PORT),
        AgentBridgeTarget("SolidCraft", SolidCraftBridge.DEFAULT_PORT),
    )

    private val lock = Any()

    @Volatile
    private var proxy: AgentBridgeProxy? = null

    @Volatile
    private var watcher: Thread? = null

    /**
     * Called at app start: honours a saved ON and publishes the bridges without the sheet being
     * open. A saved OFF does nothing at all - there is nothing to listen on.
     */
    fun ensureStarted(context: Context) {
        if (!BridgeNetworkPreference.isEnabled(context)) return
        synchronized(lock) {
            val live = proxy ?: newProxy().also { proxy = it }
            live.setEnabled(true)
            ensureWatcher()
        }
    }

    /**
     * Applies the CAD agent sheet's **Reachable on the network** switch.
     *
     * On binds every bridge on the device's current network address; off closes every listener
     * before this returns, so nothing is left published. Returns null on success, or the
     * sentence to show when the setting itself could not be stored.
     */
    fun setEnabled(context: Context, enabled: Boolean): String? {
        if (!BridgeNetworkPreference.save(context, enabled)) {
            return "the network reach setting could not be saved; the agent bridges were left as they were"
        }
        synchronized(lock) {
            if (!enabled) {
                proxy?.close()
                proxy = null
                Log.i(TAG, "agent bridges: loopback only again")
                return null
            }
            val live = proxy ?: newProxy().also { proxy = it }
            live.setEnabled(true)
            ensureWatcher()
            return null
        }
    }

    /** The sheet's rows: one per engine, with its connect line and honest state. */
    fun lines(): List<AgentBridgeLine> = proxy?.lines()
        ?: TARGETS.map { AgentBridgeLine(it, null, BridgeLinkState.OFF, "off - this device only") }

    fun isEnabled(context: Context): Boolean = BridgeNetworkPreference.isEnabled(context)

    private fun newProxy() = AgentBridgeProxy(
        targets = TARGETS,
        addressProvider = { BridgeAccess.reachable() },
        log = { message -> Log.i(TAG, message) },
    )

    /** One process-lifetime watcher: follows the device's address while anything is published. */
    private fun ensureWatcher() {
        if (watcher != null) return
        watcher = thread(name = "agent-bridges-watch", isDaemon = true) {
            while (true) {
                try {
                    Thread.sleep(WATCH_MILLIS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                proxy?.refresh()
            }
        }
    }
}
