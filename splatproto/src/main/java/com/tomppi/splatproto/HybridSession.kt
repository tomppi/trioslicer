package com.tomppi.splatproto

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * First consumer of the unified EasyScanner hybrid session
 * (docs/UNIFIED_CAPTURE_SESSION.md).
 *
 * EasyScanner writes the photographs and the per-analysis-frame laser points
 * separately. This class is the read side: it parses frames.csv, finds the
 * analysis frame nearest to a still's sensor timestamp, and exposes that
 * frame's board pose, VIO pose and laser file. It does not change the native
 * reconstruction yet; it gives the importer a verified data model first.
 */
class HybridSession(
    val dir: File,
    val manifest: JSONObject?,
    val frames: List<Frame>,
) {
    data class Frame(
        val index: Int,
        val timestampNs: Long,
        /** pose16: board -> camera, row-major, board units (mm). */
        val boardPose: DoubleArray?,
        /** vio_pose16: VIO world -> camera, row-major, metres. */
        val vioPose: DoubleArray?,
        /** Camera-frame laser points for this analysis frame, or null. */
        val laser: File?,
        val laserPoints: Int,
        /** The 640x480 analysis JPEG written for this frame, or null. */
        val jpeg: File?,
    )

    /** The nearest analysis frame to a still, with the signed/absolute time gap. */
    data class Nearest(val frame: Frame, val gapNs: Long)

    fun nearest(timestampNs: Long, toleranceNs: Long = DEFAULT_TOLERANCE_NS): Nearest? {
        if (frames.isEmpty() || timestampNs <= 0L) return null
        var lo = 0
        var hi = frames.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (frames[mid].timestampNs < timestampNs) lo = mid + 1 else hi = mid
        }
        var best = frames[lo]
        var bestGap = kotlin.math.abs(best.timestampNs - timestampNs)
        if (lo > 0) {
            val prev = frames[lo - 1]
            val g = kotlin.math.abs(prev.timestampNs - timestampNs)
            if (g < bestGap) { best = prev; bestGap = g }
        }
        if (bestGap > toleranceNs) return null
        return Nearest(best, best.timestampNs - timestampNs)
    }

    fun readLaserPoints(f: Frame): FloatArray? = readLaser(f.laser)

    companion object {
        const val DEFAULT_TOLERANCE_NS = 50_000_000L
        const val MANIFEST = "hybrid.json"

        fun load(sessionDir: File): HybridSession {
            val manifest = runCatching {
                File(sessionDir, MANIFEST).takeIf { it.isFile }?.let { JSONObject(it.readText()) }
            }.getOrNull()
            val frames = parseFrames(File(sessionDir, "frames.csv"), sessionDir,
                manifest?.optJSONObject("laser")?.optString("dir") ?: "laser")
            return HybridSession(sessionDir, manifest, frames)
        }

        fun readLaser(file: File?): FloatArray? {
            if (file == null || !file.isFile || file.length() <= 0L) return null
            val len = file.length()
            if (len % 12L != 0L) return null
            return runCatching {
                val bb = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                val out = FloatArray((len / 12L).toInt() * 3)
                for (i in out.indices) out[i] = bb.float
                out
            }.getOrNull()
        }

        private fun parseFrames(csv: File, sessionDir: File, laserDirName: String): List<Frame> {
            if (!csv.isFile) return emptyList()
            val lines = csv.bufferedReader().use { it.readLines() }
            if (lines.size < 2) return emptyList()
            val header = lines.first().split(',')
            fun idx(name: String) = header.indexOf(name)
            val iFrame = idx("frame")
            val iTs = idx("ts_ns")
            val iPose = idx("pose16")
            val iVio = idx("vio_pose16")
            val iJpeg = idx("jpeg")
            if (iFrame < 0 || iTs < 0) return emptyList()
            val laserDir = File(sessionDir, laserDirName)
            val out = ArrayList<Frame>(lines.size - 1)
            for (line in lines.drop(1)) {
                if (line.isBlank()) continue
                val p = line.split(',')
                if (p.size <= maxOf(iFrame, iTs)) continue
                val frame = p[iFrame].toIntOrNull() ?: continue
                val ts = p[iTs].toLongOrNull() ?: continue
                // Older/TrackerRecorder rows carry one extra scalar before the
                // board pose (the header/row column count can differ). Find the
                // two 16-float matrices by content, not by a fixed offset.
                val boardAt = firstMatrix(p, iPose)
                val board = boardAt?.second
                val vio = if (boardAt != null) firstMatrix(p, boardAt.first + 1)?.second else firstMatrix(p, iVio)?.second
                val laser = File(laserDir, "f%05d.f32".format(frame))
                val points = if (laser.isFile && laser.length() > 0L && laser.length() % 12L == 0L)
                    (laser.length() / 12L).toInt() else 0
                val jpegName = p.getOrNull(iJpeg)?.takeIf { it.endsWith(".jpg", true) }
                    ?: p.lastOrNull { it.endsWith(".jpg", true) }
                val jpeg = jpegName?.let { File(sessionDir, it) }?.takeIf { it.isFile }
                out.add(Frame(frame, ts, board, vio,
                    laser.takeIf { points > 0 }, points, jpeg))
            }
            out.sortBy { it.timestampNs }
            return out
        }

        private fun parse16(s: String): DoubleArray? {
            if (s.isBlank()) return null
            val v = s.trim().split(' ').mapNotNull { it.toDoubleOrNull() }
            return if (v.size == 16) DoubleArray(16) { v[it] } else null
        }

        /** First field at or after [from] that holds exactly 16 numbers. */
        private fun firstMatrix(p: List<String>, from: Int): Pair<Int, DoubleArray>? {
            if (from < 0) return null
            var i = from
            while (i < p.size && i < from + 4) {
                val m = parse16(p[i])
                if (m != null) return i to m
                i++
            }
            return null
        }
    }
}