package com.tomppi.enderslicer.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user pastes whatever the harness printed, so parsing has to survive the
 * shapes that actually come out of it - a launch token, extra query parameters,
 * a fragment, or a bare address typed by hand.
 *
 * The token is the one thing the app cannot get anywhere else: the launcher
 * prints it once and publishes nothing, so parsing keeps it while everything
 * else about the query goes.
 */
class HarnessConfigTest {

    @Test
    fun parsesTheLaunchUrlTheHarnessPrints() {
        val config = HarnessConfig.parseLaunchUrl("http://100.64.0.10:3080/?token=abc123")

        assertEquals("http://100.64.0.10:3080", config.baseUrl)
        assertEquals("abc123", config.launchToken)
        assertTrue(config.isConfigured)
    }

    @Test
    fun acceptsABareAddress() {
        val config = HarnessConfig.parseLaunchUrl("http://100.64.0.10:3080")

        assertEquals("http://100.64.0.10:3080", config.baseUrl)
        assertEquals("", config.launchToken)
        assertTrue(config.isConfigured)
    }

    @Test
    fun trimsTrailingSlashSoPathsDoNotDouble() {
        val config = HarnessConfig.parseLaunchUrl("http://host:3080/")

        assertEquals("http://host:3080", config.baseUrl)
    }

    @Test
    fun keepsTheTokenAndDropsTheRestOfTheQuery() {
        // The printed URL can carry more than the token. None of the rest is part
        // of the address - keeping "?token=abc&session=s1" would send every later
        // request to a path the harness does not serve - but the token is the
        // only copy of the credential this app will ever see.
        val config = HarnessConfig.parseLaunchUrl("http://host:3080/?token=abc&session=s1")

        assertEquals("http://host:3080", config.baseUrl)
        assertEquals("abc", config.launchToken)
    }

    @Test
    fun findsTheTokenWhereverItSitsInTheQuery() {
        val config = HarnessConfig.parseLaunchUrl("http://host:3080/?a=1&token=xyz-123_AB&b=2")

        assertEquals("xyz-123_AB", config.launchToken)
    }

    @Test
    fun aTokenThatIsNotFirstStillLeavesTheAddressClean() {
        val config = HarnessConfig.parseLaunchUrl("https://host.ts.net/?x=1&token=t")

        assertEquals("https://host.ts.net", config.baseUrl)
        assertEquals("t", config.launchToken)
    }

    @Test
    fun anEmptyTokenIsNotAToken() {
        // "?token=" and "?token=%20" are what a truncated paste looks like; the
        // connect path has to see "no token" and say so rather than spend an
        // empty string against the harness.
        assertEquals("", HarnessConfig.parseLaunchUrl("http://host:3080/?token=").launchToken)
        assertEquals("", HarnessConfig.parseLaunchUrl("http://host:3080/?token=%20").launchToken)
    }

    @Test
    fun dropsTheFragment() {
        val config = HarnessConfig.parseLaunchUrl("http://host:3080/?token=abc#/chat")

        assertEquals("http://host:3080", config.baseUrl)
        assertEquals("abc", config.launchToken)
    }

    @Test
    fun handlesSurroundingWhitespaceFromAPaste() {
        val config = HarnessConfig.parseLaunchUrl("  http://host:3080/?token=abc  ")

        assertEquals("http://host:3080", config.baseUrl)
        assertEquals("abc", config.launchToken)
    }

    @Test
    fun emptyInputIsNotConfigured() {
        assertFalse(HarnessConfig.parseLaunchUrl("").isConfigured)
        assertFalse(HarnessConfig.parseLaunchUrl("   ").isConfigured)
    }

    @Test
    fun mergingATypedAddressReplacesTheStoredOne() {
        val stored = HarnessConfig(baseUrl = "http://old:3080", workspace = "C:\\work", sessionId = "session-1")

        val merged = stored.mergedWith(
            parsed = HarnessConfig.parseLaunchUrl("http://host:3080/?token=abc"),
            workspace = "C:\\work",
            sessionId = "session-1",
        )

        assertEquals("http://host:3080", merged.baseUrl)
        assertEquals("abc", merged.launchToken)
    }

    @Test
    fun mergingAlwaysTakesTheWorkspaceAndSessionFromTheCaller() {
        val stored = HarnessConfig(
            baseUrl = "http://host:3080",
            workspace = "C:\\old",
            sessionId = "session-old",
        )

        val merged = stored.mergedWith(
            parsed = HarnessConfig(baseUrl = "http://host:3080"),
            workspace = "C:\\new",
            sessionId = "session-new",
        )

        assertEquals("C:\\new", merged.workspace)
        assertEquals("session-new", merged.sessionId)
    }

    @Test
    fun mergingABlankAddressKeepsTheStoredOne() {
        val stored = HarnessConfig(baseUrl = "http://host:3080", workspace = "C:\\work", sessionId = "session-1")

        val merged = stored.mergedWith(
            parsed = HarnessConfig(),
            workspace = "C:\\work",
            sessionId = "session-1",
        )

        assertEquals("http://host:3080", merged.baseUrl)
        assertTrue(merged.isConfigured)
    }

    @Test
    fun reconnectingWithABareAddressKeepsTheStoredToken() {
        // This is the everyday path: the field shows the stored address and the
        // Connect button spends the token that was pasted the first time.
        val stored = HarnessConfig(baseUrl = "http://host:3080", launchToken = "abc")

        val merged = stored.mergedWith(
            parsed = HarnessConfig.parseLaunchUrl("http://host:3080"),
            workspace = "",
            sessionId = "",
        )

        assertEquals("abc", merged.launchToken)
    }

    @Test
    fun aNewAddressDoesNotCarryTheOldTokenAcross() {
        // A token is minted by one harness and means nothing to another; keeping
        // it would spend a credential against a server that never issued it.
        val stored = HarnessConfig(baseUrl = "http://old:3080", launchToken = "old-token")

        val merged = stored.mergedWith(
            parsed = HarnessConfig.parseLaunchUrl("http://new:3080"),
            workspace = "",
            sessionId = "",
        )

        assertEquals("http://new:3080", merged.baseUrl)
        assertEquals("", merged.launchToken)
    }

    @Test
    fun aFreshTokenWinsOverTheStoredOne() {
        val stored = HarnessConfig(baseUrl = "http://host:3080", launchToken = "old-token")

        val merged = stored.mergedWith(
            parsed = HarnessConfig.parseLaunchUrl("http://host:3080/?token=new-token"),
            workspace = "",
            sessionId = "",
        )

        assertEquals("new-token", merged.launchToken)
    }

    @Test
    fun theCleartextPermissionBelongsToOneAddress() {
        val stored = HarnessConfig(baseUrl = "http://old:3080", allowCleartext = true)

        val sameHost = stored.mergedWith(
            parsed = HarnessConfig.parseLaunchUrl("http://old:3080"),
            workspace = "",
            sessionId = "",
        )
        val otherHost = stored.mergedWith(
            parsed = HarnessConfig.parseLaunchUrl("http://new:3080"),
            workspace = "",
            sessionId = "",
        )

        assertTrue(sameHost.allowCleartext)
        assertFalse(otherHost.allowCleartext)
    }

    @Test
    fun acceptingCleartextIsCarriedIntoTheStoredConfiguration() {
        // The dialog accepts an address; the parser never grants it, so this is
        // the only way the permission can be recorded.
        val stored = HarnessConfig()

        val merged = stored.mergedWith(
            parsed = HarnessConfig.parseLaunchUrl("http://host:3080").copy(allowCleartext = true),
            workspace = "",
            sessionId = "",
        )

        assertTrue(merged.allowCleartext)
    }
}
