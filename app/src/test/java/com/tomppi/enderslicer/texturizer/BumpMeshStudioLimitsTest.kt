package com.tomppi.enderslicer.texturizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The safety guard rails: the app's triangle limit, the tiers and the measured memory ceiling. */
class BumpMeshStudioLimitsTest {

    @Test
    fun theTiersAreFastDetailedAndExtreme() {
        assertEquals("fast", BumpMeshStudio.tierFor(800_000))
        assertEquals("fast", BumpMeshStudio.tierFor(1))
        assertEquals("detailed", BumpMeshStudio.tierFor(800_001))
        assertEquals("detailed", BumpMeshStudio.tierFor(2_000_000))
        assertEquals("extreme", BumpMeshStudio.tierFor(2_000_001))
        assertEquals(800_000, BumpMeshStudio.FAST_MAX_TRIANGLES)
        assertEquals(2_000_000, BumpMeshStudio.DETAILED_MAX_TRIANGLES)
        assertEquals(8_000_000, BumpMeshStudio.EXTREME_MAX_TRIANGLES)
    }

    @Test
    fun anApplyAboveTheAppsLimitIsRefusedWithTheRaiseItSentence() {
        val refusal = BumpMeshStudioLimits.refusalFor(2_000_001, 0.4f, 1_500_000)
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("1,500,000"))
        assertTrue(refusal.contains("Raise"))
        assertNull(BumpMeshStudioLimits.refusalFor(1_500_000, 0.4f, 1_500_000))
    }

    @Test
    fun anApplyAboveTheAppsMaximumIsRefusedByName() {
        val refusal = BumpMeshStudioLimits.refusalFor(8_000_001, 0.4f, 8_000_000)
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("at most"))
        assertTrue(refusal.contains("8,000,000"))
    }

    @Test
    fun anExtremeApplyAtTheMeasuredMemoryRiskyResolutionIsRefusedWithTheMeasuredNumber() {
        val refusal = BumpMeshStudioLimits.refusalFor(3_000_000, 0.1f, 8_000_000)
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("0.12"))
        assertNull(BumpMeshStudioLimits.refusalFor(3_000_000, 0.12f, 8_000_000))
        assertNull("fast and detailed applies keep any resolution they like", BumpMeshStudioLimits.refusalFor(1_200_000, 0.05f, 8_000_000))
    }

    @Test
    fun theExtremeMemoryWarningOnlyAppearsAboveTheDetailedTier() {
        assertNull(BumpMeshStudio.memoryWarningFor(2_000_000))
        val warning = BumpMeshStudio.memoryWarningFor(2_000_001)
        assertNotNull(warning)
        assertTrue(warning!!.contains("5.13 GiB"))
    }
}
