package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuTopologyTest {
    @Test
    fun parsesLinuxCpuListsAndRanges() {
        assertEquals(setOf(0, 1, 2, 3), CpuTopology.parseCpuList("0-3"))
        assertEquals(setOf(0, 2, 4, 5, 6, 8), CpuTopology.parseCpuList("0,2,4-6,8"))
        assertEquals(setOf(3), CpuTopology.parseCpuList(" 3\n"))
    }

    @Test
    fun rejectsMalformedCpuLists() {
        assertNull(CpuTopology.parseCpuList(null))
        assertNull(CpuTopology.parseCpuList(""))
        assertNull(CpuTopology.parseCpuList("3-1"))
        assertNull(CpuTopology.parseCpuList("0--3"))
        assertNull(CpuTopology.parseCpuList("0,broken"))
        assertNull(CpuTopology.parseCpuList("0-5000"))
    }

    @Test
    fun physicalTopologyOverridesTemporaryThreeCpuRuntimeView() {
        val files = mapOf(
            "/sys/devices/system/cpu/possible" to "0-7\n",
            "/sys/devices/system/cpu/present" to "0-7\n",
            "/sys/devices/system/cpu/online" to "0-7\n",
            "/proc/self/status" to "Name:\tenderslicer\nCpus_allowed_list:\t0-2\n",
        )

        val snapshot = CpuTopology.detect(runtimeAvailableProcessors = 3, readText = files::get)

        assertEquals(3, snapshot.runtimeAvailableProcessors)
        assertEquals("0-7", snapshot.presentCpuList)
        assertEquals("0-2", snapshot.allowedCpuList)
        assertEquals(8, snapshot.hardwareProcessorCount)
        assertEquals(8, snapshot.recommendedThreadCount)
    }

    @Test
    fun runtimeCountIsSafeFallbackWhenSysfsIsUnavailable() {
        val snapshot = CpuTopology.detect(runtimeAvailableProcessors = 3) { null }

        assertEquals(3, snapshot.hardwareProcessorCount)
        assertEquals(3, snapshot.recommendedThreadCount)
    }

    @Test
    fun threadCountRemainsCappedOnLargerSystems() {
        assertEquals(8, CpuTopology.recommendedThreadCount(3, 12))
        assertEquals(8, CpuTopology.recommendedThreadCount(32, 32))
    }
}
