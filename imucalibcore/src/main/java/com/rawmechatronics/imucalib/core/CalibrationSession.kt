
package com.rawmechatronics.imucalib.core

/**
 * A recorded calibration dataset: board poses at camera times and IMU samples,
 * all on one relative clock (seconds since the first sample). The file formats
 * are the ones TrackerRecorder writes (frames.csv / imu.csv in the scanner).
 */
data class CalibrationSession(
    val boardPoses: List<BoardPose>,
    val imu: List<ImuSample>,
    val offsetSeconds: Double,
    /** ChArUco corner observations for pixel reprojection, if the recording has them. */
    val corners: List<CornerObservation> = emptyList(),
    val board: BoardLayout = BoardLayout(),
) {
    companion object {

        fun parseFramesCsv(text: String): List<BoardPose> {
            val lines = text.lineSequence().filter { it.isNotBlank() }.iterator()
            if (!lines.hasNext()) return emptyList()
            val header = lines.next().split(',')
            val iTs = header.indexOf("ts_ns").let { if (it >= 0) it else 1 }
            val iDet = header.indexOf("detected")
            val iPose = header.indexOf("pose16")
            val out = ArrayList<BoardPose>()
            while (lines.hasNext()) {
                val p = lines.next().split(',')
                if (p.size <= iTs) continue
                val ts = p[iTs].toDoubleOrNull() ?: continue
                if (iDet >= 0 && (p.getOrNull(iDet)?.toDoubleOrNull() ?: 0.0) <= 0.5) continue
                val pose = findPose16(p, iPose) ?: continue
                // The engine's pose16 is the camera pose in the board frame
                // (verified against the header euler angles); older builds wrote
                // millimetres, current ones metres - detect from the magnitude.
                val raw = translationOf(pose)
                val scale = if (kotlin.math.sqrt(raw[0] * raw[0] + raw[1] * raw[1] + raw[2] * raw[2]) > 10.0) 1e-3 else 1.0
                // The engine pose16 is the board -> camera transform (its own docs/header
                // call the columns camera coordinates); the calibrator works with the camera
                // pose in the board frame, so invert it.
                val rBc = rotationOf(pose)
                val rWc = transpose(rBc)
                val tBc = doubleArrayOf(raw[0] * scale, raw[1] * scale, raw[2] * scale)
                val pWc = doubleArrayOf(
                    -(rWc[0] * tBc[0] + rWc[1] * tBc[1] + rWc[2] * tBc[2]),
                    -(rWc[3] * tBc[0] + rWc[4] * tBc[1] + rWc[5] * tBc[2]),
                    -(rWc[6] * tBc[0] + rWc[7] * tBc[1] + rWc[8] * tBc[2]),
                )
                out.add(BoardPose(ts * 1e-9, rWc, pWc))
            }
            return out
        }

        fun parseImuCsv(text: String): List<ImuSample> {
            val lines = text.lineSequence().filter { it.isNotBlank() }.iterator()
            if (!lines.hasNext()) return emptyList()
            lines.next()
            val out = ArrayList<ImuSample>()
            while (lines.hasNext()) {
                val p = lines.next().split(',')
                if (p.size < 5) continue
                val kind = p[0].firstOrNull() ?: continue
                if (kind != 'g' && kind != 'a') continue
                val ts = p[1].toDoubleOrNull() ?: continue
                val x = p[2].toDoubleOrNull() ?: continue
                val y = p[3].toDoubleOrNull() ?: continue
                val z = p[4].toDoubleOrNull() ?: continue
                if (kind == 'g') out.add(ImuSample(ts * 1e-9, doubleArrayOf(x, y, z), doubleArrayOf(0.0, 0.0, 0.0)))
                else out.add(ImuSample(ts * 1e-9, doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(x, y, z)))
            }
            return out
        }

        /**
         * Combine the two files into one IMU stream on a relative clock: gyro and accel
         * arrive as separate asynchronous events, so both are linearly interpolated to the
         * union of their timestamps (one combined sample per event).
         */
        fun fromCsv(
            framesCsv: String,
            imuCsv: String,
            cornersCsv: String? = null,
            boardLayout: BoardLayout = BoardLayout(),
            pointsCsv: String? = null,
        ): CalibrationSession? {
            val board = parseFramesCsv(framesCsv)
            val raw = parseImuCsv(imuCsv)
            if (board.isEmpty() || raw.isEmpty()) return null
            val gyro = raw.filter { it.gyro[0] != 0.0 || it.gyro[1] != 0.0 || it.gyro[2] != 0.0 }
                .sortedBy { it.t }
            val accel = raw.filter { it.accel[0] != 0.0 || it.accel[1] != 0.0 || it.accel[2] != 0.0 }
                .sortedBy { it.t }
            if (gyro.isEmpty() || accel.isEmpty()) return null
            // The scanner records IMU for the whole session while frames.csv only holds scan
            // frames, so clip the IMU to the board-pose time span (plus a small margin); without
            // this the spline would extend over unconstrained time and diverge.
            val bMin = board.minOf { it.t }
            val bMax = board.maxOf { it.t }
            val margin = 0.5
            val gyroClipped = gyro.filter { it.t >= bMin - margin && it.t <= bMax + margin }
            val accelClipped = accel.filter { it.t >= bMin - margin && it.t <= bMax + margin }
            if (gyroClipped.size < 50 || accelClipped.size < 50) return null
            var minT = Double.MAX_VALUE
            for (b in board) minT = minOf(minT, b.t)
            for (g in gyroClipped) minT = minOf(minT, g.t)
            for (a in accelClipped) minT = minOf(minT, a.t)
            val offset = minT
            val boardOut = board.map { BoardPose(it.t - offset, it.rWc, it.pWc, it.sigmaRot, it.sigmaTrans) }

            val times = ArrayList<Double>()
            var gi = 0
            var ai = 0
            while (gi < gyroClipped.size || ai < accelClipped.size) {
                val tg = if (gi < gyroClipped.size) gyroClipped[gi].t else Double.MAX_VALUE
                val ta = if (ai < accelClipped.size) accelClipped[ai].t else Double.MAX_VALUE
                val t = minOf(tg, ta)
                times.add(t)
                if (tg <= ta) gi++
                if (ta <= tg) ai++
            }
            val imuOut = ArrayList<ImuSample>(times.size)
            for (t in times) {
                imuOut.add(ImuSample(t - offset, interp(gyroClipped, t, true), interp(accelClipped, t, false)))
            }
            val cornersOut = ArrayList<CornerObservation>()
            if (cornersCsv != null) cornersOut.addAll(parseCornersCsv(cornersCsv, boardLayout, offset))
            if (pointsCsv != null) cornersOut.addAll(parsePointsCsv(pointsCsv, offset))
            return CalibrationSession(boardOut, imuOut, offset, cornersOut, boardLayout)
        }

        /** Long-format corners.csv: frame,ts_ns,id,u,v (one row per observed corner). */
        fun parseCornersCsv(text: String, board: BoardLayout, offsetSeconds: Double): List<CornerObservation> {
            val lines = text.lineSequence().filter { it.isNotBlank() }.iterator()
            if (!lines.hasNext()) return emptyList()
            val header = lines.next().split(',')
            val iTs = header.indexOf("ts_ns").let { if (it >= 0) it else 1 }
            val iId = header.indexOf("id")
            val iU = header.indexOf("u")
            val iV = header.indexOf("v")
            if (iId < 0 || iU < 0 || iV < 0) return emptyList()
            val out = ArrayList<CornerObservation>()
            while (lines.hasNext()) {
                val p = lines.next().split(',')
                if (p.size <= maxOf(iTs, iId, iU, iV)) continue
                val ts = p[iTs].toDoubleOrNull() ?: continue
                val id = p[iId].toIntOrNull() ?: continue
                val u = p[iU].toDoubleOrNull() ?: continue
                val v = p[iV].toDoubleOrNull() ?: continue
                if (board.corner3d(id) == null) continue
                if (!u.isFinite() || !v.isFinite()) continue
                out.add(CornerObservation(ts * 1e-9 - offsetSeconds, id, u, v))
            }
            return out
        }

        /** points.csv: frame,ts_ns,x,y,z,u,v with 3D board coordinates [m] (marker corners). */
        fun parsePointsCsv(text: String, offsetSeconds: Double): List<CornerObservation> {
            val lines = text.lineSequence().filter { it.isNotBlank() }.iterator()
            if (!lines.hasNext()) return emptyList()
            val header = lines.next().split(',')
            val iTs = header.indexOf("ts_ns").let { if (it >= 0) it else 1 }
            val ix = header.indexOf("x"); val iy = header.indexOf("y"); val iz = header.indexOf("z")
            val iu = header.indexOf("u"); val iv = header.indexOf("v")
            if (ix < 0 || iy < 0 || iz < 0 || iu < 0 || iv < 0) return emptyList()
            val out = ArrayList<CornerObservation>()
            while (lines.hasNext()) {
                val p = lines.next().split(',')
                if (p.size <= maxOf(iTs, ix, iy, iz, iu, iv)) continue
                val ts = p[iTs].toDoubleOrNull() ?: continue
                val x = p[ix].toDoubleOrNull() ?: continue
                val y = p[iy].toDoubleOrNull() ?: continue
                val z = p[iz].toDoubleOrNull() ?: continue
                val u = p[iu].toDoubleOrNull() ?: continue
                val v = p[iv].toDoubleOrNull() ?: continue
                if (!u.isFinite() || !v.isFinite()) continue
                out.add(CornerObservation(ts * 1e-9 - offsetSeconds, -1, u, v, doubleArrayOf(x, y, z)))
            }
            return out
        }

        private fun interp(list: List<ImuSample>, t: Double, gyro: Boolean): DoubleArray {
            if (t <= list.first().t) return if (gyro) list.first().gyro.copyOf() else list.first().accel.copyOf()
            if (t >= list.last().t) return if (gyro) list.last().gyro.copyOf() else list.last().accel.copyOf()
            var lo = 0
            var hi = list.size - 1
            while (lo + 1 < hi) {
                val mid = (lo + hi) / 2
                if (list[mid].t <= t) lo = mid else hi = mid
            }
            val a = list[lo]
            val b = list[lo + 1]
            val f = if (b.t > a.t) (t - a.t) / (b.t - a.t) else 0.0
            return if (gyro) doubleArrayOf(
                a.gyro[0] + f * (b.gyro[0] - a.gyro[0]),
                a.gyro[1] + f * (b.gyro[1] - a.gyro[1]),
                a.gyro[2] + f * (b.gyro[2] - a.gyro[2]),
            ) else doubleArrayOf(
                a.accel[0] + f * (b.accel[0] - a.accel[0]),
                a.accel[1] + f * (b.accel[1] - a.accel[1]),
                a.accel[2] + f * (b.accel[2] - a.accel[2]),
            )
        }

        private fun findPose16(parts: List<String>, preferred: Int): DoubleArray? {
            if (preferred in parts.indices) {
                val v = parts[preferred].trim().split(' ').mapNotNull { it.toDoubleOrNull() }
                if (v.size == 16) return v.toDoubleArray()
            }
            for (i in parts.indices) {
                if (i == preferred) continue
                val v = parts[i].trim().split(' ').mapNotNull { it.toDoubleOrNull() }
                if (v.size == 16) return v.toDoubleArray()
            }
            return null
        }

        private fun transpose(m: DoubleArray): DoubleArray = doubleArrayOf(
            m[0], m[3], m[6],
            m[1], m[4], m[7],
            m[2], m[5], m[8],
        )

        private fun rotationOf(m: DoubleArray): DoubleArray = doubleArrayOf(
            m[0], m[1], m[2],
            m[4], m[5], m[6],
            m[8], m[9], m[10],
        )

        private fun translationOf(m: DoubleArray): DoubleArray = doubleArrayOf(m[3], m[7], m[11])
    }
}