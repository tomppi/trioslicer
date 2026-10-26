package com.tomppi.enderslicer.agent

import android.content.Context
import com.tomppi.enderslicer.data.AppStateStore
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * How an agent reaches the app's three agent bridges: loopback only, or this device's address.
 *
 * Blender MCP (9876), the Python CAD engine (9877) and the SolidCraft document engine (9878)
 * all listen on 127.0.0.1. That is exactly right for an agent that shares the phone (the
 * harness inside the app) and useless for one that does not: a laptop, a desktop or a second
 * phone had to publish each loopback port itself - an adb forward, or a tailnet serve rule -
 * before it could connect. The CAD agent sheet now offers the other reach: **Reachable on the
 * network**, off by default, has the app listen on this device's own network address at the
 * same three ports and forward the bytes to the loopback engines ([AgentBridgeProxy]), so any
 * device that can route to this phone reaches the same engines with the same tokens.
 *
 * Two things live here, both free of Android state so the sheet's wording and the choice of
 * address are testable without a device:
 *
 * - [BridgeNetworkPreference] is the user's choice, stored in the app's own preferences file -
 *   the same store [com.tomppi.enderslicer.modelling.CadViewerPreference] and the app's other
 *   device-local switches use, so a preference manager clearing profiles does not clear it.
 * - [BridgeAccess] enumerates the device's IPv4 interfaces and picks the address the sheet
 *   prints. The choice is a rule, not a table of device names: a tailnet address first, then a
 *   private LAN address on a real interface, then the mobile data address only when there is no
 *   Wi-Fi or Ethernet one, then nothing with a sentence that says so.
 *
 * Nothing here reads, writes or compares any token. The published listeners are pure transport:
 * each engine's token check stays exactly where it was, and the forwarder adds no authentication
 * of its own.
 */
object BridgeNetworkPreference {

    private const val KEY_NETWORK = "agent-bridges-network"

    /** The CAD agent sheet's switch: false is loopback only, and is the default. */
    fun isEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_NETWORK, false)

    /** Stores the choice; false when the write did not reach disk. */
    fun save(context: Context, enabled: Boolean): Boolean =
        preferences(context).edit().putBoolean(KEY_NETWORK, enabled).commit()

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(AppStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
}

/**
 * The device's own addresses, and the sentence the sheet prints for them.
 *
 * A listener bound to "0.0.0.0" is not an address a user can paste anywhere. The line a user
 * hands to an agent names the address this phone has on the network that agent can reach,
 * chosen from what the OS reports rather than from any device name written down here - a tailnet
 * can be any interface, and a phone's Wi-Fi interface name is the vendor's business, not this
 * app's.
 */
object BridgeAccess {

    /** One IPv4 address found on one interface. */
    data class Candidate(
        val address: String,
        val interfaceName: String,
        /**
         * Android reports the mobile data interface (and VPN tunnels) as point-to-point;
         * Wi-Fi and Ethernet are not point-to-point. It is the OS's own description of the
         * interface, not a name this app knows.
         */
        val pointToPoint: Boolean = false,
    )

    /** Which rule chose the address, so the sheet can name where it came from. */
    enum class Kind {
        /** An address inside the tailnet range 100.64.0.0/10 (Tailscale and friends). */
        TAILNET,

        /** A private LAN address (10/8, 172.16/12, 192.168/16) on a real interface. */
        LAN,

        /** A private address on the mobile data interface, used only when no LAN one exists. */
        MOBILE,
    }

    /** The address to print, or the reason there is none. */
    sealed interface Access {
        data class Reachable(val address: String, val interfaceName: String, val kind: Kind) : Access

        /** A named reason the sheet shows instead of a dead line. */
        data class Unreachable(val reason: String) : Access
    }

    /** What the sheet prints under the switch, and whether it can be copied. */
    data class ConnectionLine(val text: String, val copyable: Boolean)

    /**
     * The sentence that goes with a network address: the token is still the whole gate.
     *
     * The network bind changes who can open the socket, not who is let in. An address on the
     * tailnet is reachable by the tailnet's own ACL; an address on the LAN by the LAN. Nothing
     * in the bridge was loosened to make this work.
     */
    const val NETWORK_WARNING =
        "Any device that can reach this address and has the token can drive the engine. " +
            "The token is still the only check; the network bind adds no authentication of its own."

    /** What the sheet says when the switch is off: the old loopback-only reach, by name. */
    fun loopbackLine(port: Int): String = "127.0.0.1:" + port + " - this device only"

    /** The line a user pastes to an agent: scheme, this device's address and the port. */
    fun connectLine(address: String, port: Int): String = "tcp://" + address + ":" + port

    /** Shown in place of the line when nothing usable was found; a sentence, never a blank. */
    const val NO_ADDRESS_REASON =
        "No network address to show: this device has no IPv4 address in the tailnet range " +
            "(100.64.0.0/10) and none on a private LAN (10/8, 172.16/12, 192.168/16). " +
            "Connect Wi-Fi or a tailnet, then reopen the sheet."

    /**
     * The sheet's state under the switch.
     *
     * Off is the loopback sentence, not a network line; on with a reachable address is the
     * connect line; on without one is the named reason. The caller decides how to lay it out -
     * this is the text, so a test can pin it without Compose.
     */
    fun connectionLine(enabled: Boolean, access: Access, port: Int): ConnectionLine =
        if (!enabled) {
            ConnectionLine(loopbackLine(port), copyable = false)
        } else {
            when (access) {
                is Access.Reachable -> ConnectionLine(connectLine(access.address, port), copyable = true)
                is Access.Unreachable -> ConnectionLine(access.reason, copyable = false)
            }
        }

    /**
     * Picks the address to print from what the OS reports.
     *
     * The order is the rule: a tailnet address first (it is reachable from wherever the agent
     * is, and it is the one range that is not the local network); then a private LAN address on
     * any interface that is not point-to-point, preferring nothing in particular because a
     * phone usually has one; then a private address on a point-to-point interface - the mobile
     * data one - which is only useful while there is no LAN at all. Public addresses are never
     * offered: the bridge authenticates every message with the token, but publishing an engine
     * on a world-routable address is not what the switch promises, and a phone behind carrier
     * NAT could not accept the connection anyway. Loopback is ignored - it is the off state.
     */
    fun select(candidates: List<Candidate>): Access {
        val usable = candidates.filter { candidate ->
            ipv4Octets(candidate.address) != null && !isLoopback(candidate.address)
        }.sortedWith(ORDER)
        usable.firstOrNull { isTailnet(it.address) }
            ?.let { return Access.Reachable(it.address, it.interfaceName, Kind.TAILNET) }
        usable.firstOrNull { !it.pointToPoint && isPrivateLan(it.address) }
            ?.let { return Access.Reachable(it.address, it.interfaceName, Kind.LAN) }
        usable.firstOrNull { it.pointToPoint && isPrivateLan(it.address) }
            ?.let { return Access.Reachable(it.address, it.interfaceName, Kind.MOBILE) }
        return Access.Unreachable(NO_ADDRESS_REASON)
    }

    /** The address this device would print right now, from the OS's live interface list. */
    fun reachable(): Access = select(enumerate())

    /**
     * Every IPv4 address the OS reports on a real, up interface.
     *
     * Only what java.net.NetworkInterface exposes is read: no WifiManager, no connectivity
     * service, no name matching. Interfaces that are down or throw while being inspected are
     * skipped; one unreadable interface must not hide the others. VPN interfaces are kept
     * deliberately - Tailscale's tunnel address is the tailnet candidate the rule above wants.
     */
    fun enumerate(): List<Candidate> {
        val result = mutableListOf<Candidate>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        } catch (_: Throwable) {
            return emptyList()
        }
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (runCatching { iface.isUp }.getOrNull() != true) continue
            val pointToPoint = runCatching { iface.isPointToPoint }.getOrDefault(false)
            val addresses = runCatching { iface.inetAddresses }.getOrNull() ?: continue
            while (addresses.hasMoreElements()) {
                val inet = addresses.nextElement()
                if (inet !is Inet4Address) continue
                if (inet.isLoopbackAddress) continue
                val host = inet.hostAddress?.substringBefore('%') ?: continue
                if (ipv4Octets(host) == null) continue
                result += Candidate(host, iface.name, pointToPoint)
            }
        }
        return result
    }

    /** 100.64.0.0/10: the shared address space Tailscale hands a tailnet node. */
    fun isTailnet(address: String): Boolean {
        val octets = ipv4Octets(address) ?: return false
        return octets[0] == 100 && octets[1] in 64..127
    }

    /** 10/8, 172.16/12, 192.168/16: the RFC 1918 private LAN ranges. */
    fun isPrivateLan(address: String): Boolean {
        val octets = ipv4Octets(address) ?: return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    /** 127.0.0.0/8: the off state, never a network address. */
    fun isLoopback(address: String): Boolean {
        val octets = ipv4Octets(address) ?: return false
        return octets[0] == 127
    }

    /** Stable order so the sheet prints the same line on every open. */
    private val ORDER: Comparator<Candidate> = compareBy({ it.interfaceName }, { it.address })

    /** Dotted-quad to four octets, or null when the text is not an IPv4 address. */
    private fun ipv4Octets(address: String): IntArray? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (index in 0..3) {
            val value = parts[index].toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[index] = value
        }
        return octets
    }
}
