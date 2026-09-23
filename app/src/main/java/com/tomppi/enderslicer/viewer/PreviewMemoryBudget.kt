package com.tomppi.enderslicer.viewer

import kotlin.math.min

/** Checked renderer planning in bytes, rather than a misleading path-record cap. */
internal object PreviewMemoryBudget {
    data class Plan(
        val baseSegmentLimit: Int,
        val ribbonSegmentLimit: Int,
        val baseBytes: Long,
        val ribbonBytes: Long,
    ) {
        val totalBytes: Long = Math.addExact(baseBytes, ribbonBytes)
    }

    fun plan(
        totalSegments: Int,
        selectedLayerSegments: Int,
        byteBudget: Long = DEFAULT_RENDERER_BYTE_BUDGET,
    ): Plan {
        require(totalSegments >= 0 && selectedLayerSegments >= 0) {
            "Preview segment counts cannot be negative"
        }
        require(byteBudget >= MINIMUM_BYTE_BUDGET) { "Preview byte budget is too small" }

        val baseBudget = (byteBudget - MINIMUM_RIBBON_RESERVE_BYTES).coerceAtLeast(0L)
        val baseLimitByBytes = min(Int.MAX_VALUE.toLong(), baseBudget / BASE_BYTES_PER_SEGMENT).toInt()
        val baseLimit = min(totalSegments, baseLimitByBytes)
        val baseBytes = checkedBytes(baseLimit, BASE_BYTES_PER_SEGMENT)

        val ribbonBudget = (byteBudget - baseBytes).coerceAtLeast(0L)
        val ribbonLimitByBytes = min(Int.MAX_VALUE.toLong(), ribbonBudget / RIBBON_BYTES_PER_SEGMENT).toInt()
        val ribbonLimit = min(selectedLayerSegments, min(MAX_RIBBON_SEGMENTS, ribbonLimitByBytes))
        val ribbonBytes = checkedBytes(ribbonLimit, RIBBON_BYTES_PER_SEGMENT)
        val plan = Plan(baseLimit, ribbonLimit, baseBytes, ribbonBytes)
        check(plan.totalBytes <= byteBudget) { "Preview memory plan exceeds its byte budget" }
        return plan
    }

    fun shouldRetain(sourceIndex: Int, sourceCount: Int, retainedLimit: Int): Boolean {
        if (sourceIndex !in 0 until sourceCount || retainedLimit <= 0) return false
        if (sourceCount <= retainedLimit) return true
        val before = sourceIndex.toLong() * retainedLimit / sourceCount
        val after = (sourceIndex.toLong() + 1L) * retainedLimit / sourceCount
        return after > before
    }

    fun checkedFloatCount(segmentCount: Int, floatsPerSegment: Int): Int {
        require(segmentCount >= 0 && floatsPerSegment >= 0)
        return Math.multiplyExact(segmentCount, floatsPerSegment)
    }

    private fun checkedBytes(segmentCount: Int, bytesPerSegment: Long): Long =
        Math.multiplyExact(segmentCount.toLong(), bytesPerSegment)

    /** Two line vertices: positions (6 floats) plus colors (8 floats). */
    const val BASE_BYTES_PER_SEGMENT = 56L

    /** Core + halo positions (36 floats) and six RGBA colors (24 floats). */
    const val RIBBON_BYTES_PER_SEGMENT = 240L

    const val DEFAULT_RENDERER_BYTE_BUDGET = 64L * 1024L * 1024L
    private const val MINIMUM_RIBBON_RESERVE_BYTES = 8L * 1024L * 1024L
    private const val MINIMUM_BYTE_BUDGET = MINIMUM_RIBBON_RESERVE_BYTES
    private const val MAX_RIBBON_SEGMENTS = 100_000
}
