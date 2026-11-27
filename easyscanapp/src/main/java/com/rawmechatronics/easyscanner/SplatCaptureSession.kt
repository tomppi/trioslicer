package com.rawmechatronics.easyscanner

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Manages a high-resolution, range-free Splat-Only capture session inside EasyScanner.
 *
 * Unlike Fusion/Laser scanning, Gaussian Splatting needs crisp high-resolution photographs
 * from arbitrary distances and angles without laser triangulation or out-of-range pausing.
 * Photos are triggered automatically from SuperVINS camera motion (distance / angle) with
 * a timed fallback when VIO is not tracking, plus manual snap support.
 */
class SplatCaptureSession(
    val sessionDir: File,
    val maxPhotos: Int = DEFAULT_MAX_PHOTOS,
) {
    data class Entry(
        val file: String,
        val timestampNs: Long,
        val width: Int,
        val height: Int,
        val vioPose16: DoubleArray?,
    )

    val photosDir = File(sessionDir, "splat_photos").apply { mkdirs() }
    private val lock = Any()
    private val entries = ArrayList<Entry>()

    @Volatile var paused: Boolean = false
    @Volatile var savedCount: Int = 0
        private set
    @Volatile var lastWidth: Int = 0
        private set
    @Volatile var lastHeight: Int = 0
        private set

    private var lastTriggerMs: Long = 0L
    private var stillInFlightSinceMs: Long = 0L
    private var lastCapturedPose: RigidPose? = null

    /**
     * Returns true when a new high-resolution still should be triggered automatically.
     * Enforces [minIntervalMs] and prevents overlapping still requests (with a 2.5 s timeout).
     */
    fun shouldAutoCapture(
        nowMs: Long,
        pose: RigidPose?,
        minDistM: Double = DEFAULT_MIN_DIST_M,
        minRotDeg: Double = DEFAULT_MIN_ROT_DEG,
        fallbackIntervalMs: Long = DEFAULT_FALLBACK_INTERVAL_MS,
        minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    ): Boolean = synchronized(lock) {
        if (paused || savedCount >= maxPhotos) return false
        if (stillInFlightSinceMs > 0L && nowMs - stillInFlightSinceMs < STILL_TIMEOUT_MS) return false
        if (lastTriggerMs > 0L && nowMs - lastTriggerMs < minIntervalMs) return false

        val prev = lastCapturedPose
        val trigger = when {
            savedCount == 0 && lastTriggerMs == 0L -> true
            pose != null && prev != null -> {
                val c0 = prev.cameraCentre()
                val c1 = pose.cameraCentre()
                val dist = sqrt(
                    (c1[0] - c0[0]) * (c1[0] - c0[0]) +
                        (c1[1] - c0[1]) * (c1[1] - c0[1]) +
                        (c1[2] - c0[2]) * (c1[2] - c0[2]),
                )
                val rotDeg = rotationAngleDeg(prev.r, pose.r)
                dist >= minDistM || rotDeg >= minRotDeg
            }
            else -> nowMs - lastTriggerMs >= fallbackIntervalMs
        }
        if (trigger) {
            lastTriggerMs = nowMs
            stillInFlightSinceMs = nowMs
        }
        trigger
    }

    /** Marks a manual snap request in flight so auto-capture waits for it to finish. */
    fun markManualTrigger(nowMs: Long): Boolean = synchronized(lock) {
        if (savedCount >= maxPhotos) return false
        if (stillInFlightSinceMs > 0L && nowMs - stillInFlightSinceMs < STILL_TIMEOUT_MS) return false
        lastTriggerMs = nowMs
        stillInFlightSinceMs = nowMs
        true
    }

    fun onStillFailed() = synchronized(lock) {
        stillInFlightSinceMs = 0L
    }

    /** Writes a delivered high-resolution JPEG still to disk and records its pose + metadata. */
    fun recordStill(still: Still, vioPose16: DoubleArray?): Entry? {
        val index: Int
        synchronized(lock) {
            stillInFlightSinceMs = 0L
            if (savedCount >= maxPhotos) return null
            index = entries.size
        }
        val name = "s%04d.jpg".format(Locale.US, index)
        val outFile = File(photosDir, name)
        outFile.writeBytes(still.jpeg)

        val poseCopy = vioPose16?.takeIf { it.size == 16 }?.copyOf()
        val entry = Entry(
            file = "splat_photos/$name",
            timestampNs = still.timestampNs,
            width = still.width,
            height = still.height,
            vioPose16 = poseCopy,
        )
        synchronized(lock) {
            entries.add(entry)
            savedCount = entries.size
            lastWidth = still.width
            lastHeight = still.height
            poseCopy?.let { RigidPose.fromRowMajor16(it, 1.0) }?.let { lastCapturedPose = it }
        }
        return entry
    }

    fun snapshotEntries(): List<Entry> = synchronized(lock) { entries.toList() }

    /** Persists `splat_session.json` so SplatProto imports the high-res JPEGs directly. */
    fun writeManifest(cameraId: String, calibratedIntrinsicsJson: String? = null): File {
        val snap = snapshotEntries()
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        fun esc(s: String): String = buildString(s.length + 2) {
            append('"')
            for (ch in s) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }
        val sb = StringBuilder(512 + snap.size * 220)
        sb.append("{\n")
        sb.append("  \"schema\": \"easyscanner.splat_session.v1\",\n")
        sb.append("  \"mode\": \"splat_only\",\n")
        sb.append("  \"session_id\": ").append(esc(sessionDir.name)).append(",\n")
        sb.append("  \"created_utc\": ").append(esc(utc)).append(",\n")
        sb.append("  \"camera_id\": ").append(esc(cameraId)).append(",\n")
        sb.append("  \"width\": ").append(lastWidth).append(",\n")
        sb.append("  \"height\": ").append(lastHeight).append(",\n")
        sb.append("  \"photo_count\": ").append(snap.size).append(",\n")
        if (!calibratedIntrinsicsJson.isNullOrBlank() && calibratedIntrinsicsJson.trim().startsWith("{")) {
            sb.append("  \"calibrated_intrinsics\": ").append(calibratedIntrinsicsJson.trim()).append(",\n")
        }
        sb.append("  \"images\": [\n")
        for ((idx, e) in snap.withIndex()) {
            sb.append("    {")
            sb.append("\"index\": ").append(idx).append(", ")
            sb.append("\"file\": ").append(esc(e.file)).append(", ")
            sb.append("\"sensor_timestamp_ns\": ").append(e.timestampNs).append(", ")
            sb.append("\"width\": ").append(e.width).append(", ")
            sb.append("\"height\": ").append(e.height).append(", ")
            sb.append("\"vio_pose16\": ")
            val vp = e.vioPose16
            if (vp != null && vp.size == 16) {
                sb.append(esc(vp.joinToString(" ")))
            } else {
                sb.append("null")
            }
            sb.append(if (idx + 1 < snap.size) "},\n" else "}\n")
        }
        sb.append("  ]\n}\n")
        val out = File(sessionDir, MANIFEST_NAME)
        out.writeText(sb.toString())
        return out
    }

    private fun rotationAngleDeg(ra: DoubleArray, rb: DoubleArray): Double {
        var tr = 0.0
        for (i in 0 until 9) tr += ra[i] * rb[i]
        return Math.toDegrees(acos(((tr - 1.0) * 0.5).coerceIn(-1.0, 1.0)))
    }

    companion object {
        const val MANIFEST_NAME = "splat_session.json"
        const val DEFAULT_MAX_PHOTOS = 80
        const val DEFAULT_MIN_DIST_M = 0.045
        const val DEFAULT_MIN_ROT_DEG = 9.0
        const val DEFAULT_FALLBACK_INTERVAL_MS = 950L
        const val DEFAULT_MIN_INTERVAL_MS = 380L
        private const val STILL_TIMEOUT_MS = 2500L
    }
}
