package com.tomppi.enderslicer.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that picks the CAD bridge's connect line.
 *
 * The address is chosen from what the OS reports, in a fixed order, so the sheet can promise
 * the same line on every open and the user can hand it to an agent. The order exists because
 * the alternatives are wrong in different ways on a phone: a phone may have a tailnet address,
 * a Wi-Fi address, a mobile data address that is private because the carrier NATs it, and a
 * loopback address, all at once. Nothing here depends on an interface name or a device name -
 * the tests use made-up ones, and so does the detector.
 */
class BridgeNetworkTest {

    private fun candidate(
        address: String,
        interfaceName: String = "wlan0",
        pointToPoint: Boolean = false,
    ) = BridgeAccess.Candidate(address, interfaceName, pointToPoint)

    private fun reachable(access: BridgeAccess.Access): BridgeAccess.Access.Reachable {
        assertTrue("expected an address, got " + access, access is BridgeAccess.Access.Reachable)
        return access as BridgeAccess.Access.Reachable
    }

    @Test
    fun aTailnetAddressBeatsTheLocalNetwork() {
        val chosen = reachable(
            BridgeAccess.select(
                listOf(
                    candidate("192.168.1.42", "wlan0"),
                    candidate("100.64.13.7", "tailscale0", pointToPoint = true),
                ),
            ),
        )
        assertEquals(BridgeAccess.Kind.TAILNET, chosen.kind)
        assertEquals("100.64.13.7", chosen.address)
        assertEquals("tailscale0", chosen.interfaceName)
    }

    @Test
    fun theTailnetRangeIs100_64Through100_127() {
        assertTrue(BridgeAccess.isTailnet("100.64.0.0"))
        assertTrue(BridgeAccess.isTailnet("100.64.13.7"))
        assertTrue(BridgeAccess.isTailnet("100.127.255.255"))
        assertFalse("the range stops at the next /10", BridgeAccess.isTailnet("100.128.0.0"))
        assertFalse(BridgeAccess.isTailnet("100.63.255.255"))
        assertFalse(BridgeAccess.isTailnet("99.64.0.0"))
        assertFalse("not an address at all", BridgeAccess.isTailnet("100.72"))
    }

    @Test
    fun thePrivateRangesAreTheRfc1918Ones() {
        assertTrue(BridgeAccess.isPrivateLan("10.0.0.1"))
        assertTrue(BridgeAccess.isPrivateLan("10.255.255.255"))
        assertTrue(BridgeAccess.isPrivateLan("172.16.0.1"))
        assertTrue(BridgeAccess.isPrivateLan("172.31.255.255"))
        assertFalse(BridgeAccess.isPrivateLan("172.32.0.1"))
        assertTrue(BridgeAccess.isPrivateLan("192.168.0.1"))
        assertTrue(BridgeAccess.isPrivateLan("192.168.3.54"))
        assertFalse(BridgeAccess.isPrivateLan("192.169.3.54"))
        assertFalse(BridgeAccess.isPrivateLan("100.64.13.7"))
    }

    @Test
    fun aPublicAddressIsNeverOffered() {
        val access = BridgeAccess.select(
            listOf(candidate("8.8.8.8", "wlan0"), candidate("1.1.1.1", "eth0")),
        )
        assertTrue(access is BridgeAccess.Access.Unreachable)
        assertEquals(BridgeAccess.NO_ADDRESS_REASON, (access as BridgeAccess.Access.Unreachable).reason)
    }

    @Test
    fun loopbackIsNotANetworkAddress() {
        assertTrue(BridgeAccess.isLoopback("127.0.0.1"))
        assertFalse(BridgeAccess.isLoopback("192.168.1.42"))
        assertTrue(BridgeAccess.select(emptyList()) is BridgeAccess.Access.Unreachable)

        val chosen = reachable(
            BridgeAccess.select(listOf(candidate("127.0.0.1", "lo"), candidate("10.1.2.3", "eth0"))),
        )
        assertEquals("10.1.2.3", chosen.address)
    }

    @Test
    fun wifiBeatsMobileDataEvenWhenTheMobileAddressIsPrivate() {
        // A carrier-NAT mobile data address is 10/8 like a home LAN; the point-to-point flag is
        // what the OS reports about the interface, and it is the only thing that separates them.
        val chosen = reachable(
            BridgeAccess.select(
                listOf(
                    candidate("10.44.1.9", "rmnet_data0", pointToPoint = true),
                    candidate("192.168.3.54", "wlan0"),
                ),
            ),
        )
        assertEquals(BridgeAccess.Kind.LAN, chosen.kind)
        assertEquals("192.168.3.54", chosen.address)
    }

    @Test
    fun mobileDataIsUsedWhenThereIsNoLanAtAll() {
        val chosen = reachable(
            BridgeAccess.select(listOf(candidate("10.44.1.9", "rmnet_data0", pointToPoint = true))),
        )
        assertEquals(BridgeAccess.Kind.MOBILE, chosen.kind)
        assertEquals("10.44.1.9", chosen.address)
    }

    @Test
    fun noCandidateIsANamedReasonNotAnEmptyLine() {
        val access = BridgeAccess.select(emptyList())
        assertTrue(access is BridgeAccess.Access.Unreachable)
        val reason = (access as BridgeAccess.Access.Unreachable).reason
        assertTrue("the reason names the tailnet range", reason.contains("100.64.0.0/10"))
        assertTrue("the reason names the private ranges", reason.contains("192.168/16"))
        assertFalse("it is a sentence, not a dead line", reason.startsWith("tcp://"))
    }

    @Test
    fun theChosenAddressIsStableAcrossOpens() {
        val candidates = listOf(
            candidate("192.168.3.54", "wlan0"),
            candidate("10.9.9.9", "eth0"),
            candidate("172.20.0.2", "eth1"),
        )
        val first = reachable(BridgeAccess.select(candidates))
        val second = reachable(BridgeAccess.select(candidates.reversed()))
        assertEquals(first.address, second.address)
        assertEquals(first.interfaceName, second.interfaceName)
    }

    @Test
    fun theConnectLineIsTheSchemeTheAddressAndThePort() {
        val tailnet = reachable(
            BridgeAccess.select(listOf(candidate("100.64.13.7", "tailscale0", pointToPoint = true))),
        )
        assertEquals("tcp://100.64.13.7:9878", BridgeAccess.connectLine(tailnet.address, 9878))

        val lan = reachable(BridgeAccess.select(listOf(candidate("192.168.1.42", "wlan0"))))
        assertEquals("tcp://192.168.1.42:9878", BridgeAccess.connectLine(lan.address, 9878))
    }

    @Test
    fun theSheetLineIsTheLoopbackWordingWhenTheSwitchIsOff() {
        val line = BridgeAccess.connectionLine(
            enabled = false,
            access = BridgeAccess.select(listOf(candidate("192.168.1.42", "wlan0"))),
            port = 9878,
        )
        assertEquals("127.0.0.1:9878 - this device only", line.text)
        assertFalse("the loopback sentence is not an address to copy", line.copyable)
    }

    @Test
    fun theSheetLineIsTheCopyableConnectLineWhenTheSwitchIsOn() {
        val line = BridgeAccess.connectionLine(
            enabled = true,
            access = BridgeAccess.select(listOf(candidate("192.168.1.42", "wlan0"))),
            port = 9878,
        )
        assertEquals("tcp://192.168.1.42:9878", line.text)
        assertTrue(line.copyable)
    }

    @Test
    fun theSheetLineIsTheNamedReasonWhenTheSwitchIsOnAndThereIsNoAddress() {
        val line = BridgeAccess.connectionLine(
            enabled = true,
            access = BridgeAccess.select(listOf(candidate("8.8.8.8", "wlan0"))),
            port = 9878,
        )
        assertEquals(BridgeAccess.NO_ADDRESS_REASON, line.text)
        assertFalse("there is nothing to copy", line.copyable)
    }

    @Test
    fun theNetworkWarningSaysTheTokenIsStillTheOnlyCheck() {
        assertTrue(BridgeAccess.NETWORK_WARNING.contains("has the token can drive the engine"))
        assertTrue(BridgeAccess.NETWORK_WARNING.contains("token is still the only check"))
    }
}
