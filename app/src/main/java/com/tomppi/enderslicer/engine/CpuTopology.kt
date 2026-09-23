package com.tomppi.enderslicer.engine

import java.io.File

/**
 * Reads the Linux CPU topology directly instead of trusting only
 * [Runtime.availableProcessors]. Android may report the app's temporary cpuset
 * there, which can be smaller than the physical CPU topology while slicing.
 */
internal object CpuTopology {
    data class Snapshot(
        val runtimeAvailableProcessors: Int,
        val possibleCpuList: String?,
        val presentCpuList: String?,
        val onlineCpuList: String?,
        val allowedCpuList: String?,
        val hardwareProcessorCount: Int,
        val recommendedThreadCount: Int,
    )

    fun detect(
        runtimeAvailableProcessors: Int = Runtime.getRuntime().availableProcessors(),
        readText: (String) -> String? = ::readTextSafely,
    ): Snapshot {
        val runtimeCount = runtimeAvailableProcessors.coerceAtLeast(1)
        val possible = normalizeCpuList(readText(POSSIBLE_CPUS_PATH))
        val present = normalizeCpuList(readText(PRESENT_CPUS_PATH))
        val online = normalizeCpuList(readText(ONLINE_CPUS_PATH))
        val allowed = readAllowedCpuList(readText(PROCESS_STATUS_PATH))

        val presentCount = parseCpuList(present)?.size
        val possibleCount = parseCpuList(possible)?.size
        val onlineCount = parseCpuList(online)?.size
        val hardwareCount = maxOf(
            runtimeCount,
            presentCount ?: possibleCount ?: onlineCount ?: runtimeCount,
        )

        return Snapshot(
            runtimeAvailableProcessors = runtimeCount,
            possibleCpuList = possible,
            presentCpuList = present,
            onlineCpuList = online,
            allowedCpuList = allowed,
            hardwareProcessorCount = hardwareCount,
            recommendedThreadCount = recommendedThreadCount(runtimeCount, hardwareCount),
        )
    }

    internal fun recommendedThreadCount(
        runtimeAvailableProcessors: Int,
        hardwareProcessorCount: Int,
    ): Int = maxOf(runtimeAvailableProcessors, hardwareProcessorCount)
        .coerceIn(1, MAX_RECOMMENDED_THREADS)

    internal fun parseCpuList(value: String?): Set<Int>? {
        val text = normalizeCpuList(value) ?: return null
        val cpus = linkedSetOf<Int>()

        for (rawSegment in text.split(',')) {
            val segment = rawSegment.trim()
            if (segment.isEmpty()) return null

            val dashIndex = segment.indexOf('-')
            if (dashIndex < 0) {
                val cpu = parseCpuIndex(segment) ?: return null
                cpus += cpu
                continue
            }

            if (segment.indexOf('-', dashIndex + 1) >= 0) return null
            val start = parseCpuIndex(segment.substring(0, dashIndex).trim()) ?: return null
            val end = parseCpuIndex(segment.substring(dashIndex + 1).trim()) ?: return null
            if (start > end) return null
            for (cpu in start..end) cpus += cpu
        }

        return cpus.takeIf { it.isNotEmpty() }
    }

    private fun parseCpuIndex(value: String): Int? = value
        .toIntOrNull()
        ?.takeIf { it in 0..MAX_CPU_INDEX }

    private fun normalizeCpuList(value: String?): String? = value
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun readAllowedCpuList(status: String?): String? = status
        ?.lineSequence()
        ?.map { it.trimStart() }
        ?.firstOrNull { it.startsWith("Cpus_allowed_list:") }
        ?.substringAfter(':')
        ?.let(::normalizeCpuList)

    private fun readTextSafely(path: String): String? = runCatching {
        File(path).readText()
    }.getOrNull()

    private const val POSSIBLE_CPUS_PATH = "/sys/devices/system/cpu/possible"
    private const val PRESENT_CPUS_PATH = "/sys/devices/system/cpu/present"
    private const val ONLINE_CPUS_PATH = "/sys/devices/system/cpu/online"
    private const val PROCESS_STATUS_PATH = "/proc/self/status"
    private const val MAX_RECOMMENDED_THREADS = 8
    private const val MAX_CPU_INDEX = 4095
}
