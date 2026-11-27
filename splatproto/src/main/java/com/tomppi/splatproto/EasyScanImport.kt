package com.tomppi.splatproto

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * Imports an EasyScanner hybrid session into a splat Scan.
 *
 * The fusion session already contains a 640x480 analysis JPEG for every
 * recorded frame (TrackerRecorder's fNNNNN.jpg) next to that frame's laser
 * file, timestamp and board/VIO pose in frames.csv. Those are the photo
 * keyframes; the separate photos/ folder is the old full-sensor still path and
 * is only used for legacy sessions without frame JPEGs.
 */
object EasyScanImport {
    private const val MAX_KEYFRAMES = 50
    private const val MAX_SPLAT_KEYFRAMES = 80
    class Result(
        val scan: Scan,
        val images: Int,
        val skipped: Int,
        val laserFrames: Int,
        val splatOnly: Boolean = false,
        val kind: ScanKind = if (splatOnly) ScanKind.SPLAT_ONLY else ScanKind.FUSED,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
    )

    fun import(ctx: Context, sessionDir: File): Result {
        val splatManifest = File(sessionDir, "splat_session.json")
        if (splatManifest.isFile) {
            return importSplatOnly(ctx, sessionDir, splatManifest)
        }
        val sessionFile = sessionDir.resolve("session.json")
        val session = if (sessionFile.isFile) runCatching { JSONObject(sessionFile.readText()) }.getOrNull() else null
        val hybrid = HybridSession.load(sessionDir)
        val allFrameRows = hybrid.frames.filter { it.jpeg != null }
        val jpegSize = allFrameRows.firstOrNull()?.jpeg?.let { probeJpeg(it) }

        val (base, cameraId) = resolveIntrinsics(ctx, sessionDir, session, jpegSize)
        val intr = if (jpegSize != null && base.width > 0 && base.height > 0 &&
            (jpegSize.first != base.width || jpegSize.second != base.height)
        ) {
            val sx = jpegSize.first.toDouble() / base.width
            val sy = jpegSize.second.toDouble() / base.height
            Intrinsics(
                jpegSize.first, jpegSize.second,
                base.fx * sx, base.fy * sy,
                (base.cx + 0.5) * sx - 0.5, (base.cy + 0.5) * sy - 0.5,
                base.k1, base.k2, base.p1, base.p2, base.k3, base.calibrated,
            )
        } else {
            base
        }

        // Uniformly decimate photo keyframes to MAX_KEYFRAMES so SfM/MVS and Cross3DGS stay fast
        // even on long 10 fps EasyScanner recordings, while keeping 100% of laser frames below.
        val frameRows = if (allFrameRows.size > MAX_KEYFRAMES) {
            val out = ArrayList<HybridSession.Frame>(MAX_KEYFRAMES)
            for (k in 0 until MAX_KEYFRAMES) {
                val idx = ((k.toDouble() * (allFrameRows.size - 1)) / (MAX_KEYFRAMES - 1)).toInt()
                out.add(allFrameRows[idx])
            }
            out
        } else allFrameRows

        val hybridRows = ArrayList<JSONObject>()
        val scan = ScanStore.create(ctx)
        val images = ArrayList<ScanImage>()
        var skipped = 0

        if (frameRows.isNotEmpty()) {
            for (f in frameRows) {
                val src = f.jpeg ?: continue
                if (!src.isFile || src.length() == 0L) { skipped++; continue }
                val name = src.name
                val dst = File(scan.imagesDir, name)
                if (!dst.exists()) linkOrCopy(src, dst)
                images += ScanImage(name, 0, -1, floatArrayOf(0f, 1f, 0f), f.timestampNs,
                    0L, 0, 0f, false, 0L, null, f.vioPose)
                var laserRel: String? = null
                if (f.laser != null) {
                    val ldir = File(scan.dir, "hybrid/laser").apply { mkdirs() }
                    val ldst = File(ldir, "f%05d.f32".format(f.index))
                    if (!ldst.exists()) linkOrCopy(f.laser, ldst)
                    laserRel = ldst.relativeTo(scan.dir).path.replace(File.separatorChar, '/')
                }
                hybridRows += JSONObject()
                    .put("file", name)
                    .put("sensor_timestamp_ns", f.timestampNs)
                    .put("frame", f.index)
                    .put("time_gap_ns", 0L)
                    .put("laser", laserRel ?: JSONObject.NULL)
                    .put("laser_points", f.laserPoints)
                    .put("board_pose16", f.boardPose?.joinToString(" ") ?: JSONObject.NULL)
                    .put("vio_pose16", f.vioPose?.joinToString(" ") ?: JSONObject.NULL)
                    .put("image_pose16", f.boardPose?.joinToString(" ") ?: JSONObject.NULL)
            }
        } else {
            // Legacy fallback: full-sensor stills from photos.jsonl.
            val rows = ArrayList<JSONObject>()
            val jsonl = File(sessionDir, "photos.jsonl")
            if (jsonl.exists()) {
                jsonl.forEachLine { line ->
                    if (line.isNotBlank()) runCatching { rows += JSONObject(line) }
                }
            }
            val hasAnyLaser = hybrid.frames.any { it.laser != null && it.laserPoints > 0 }
            if (rows.isEmpty() && !hasAnyLaser) error("no photos.jsonl rows and no frame JPEGs")
            for (row in rows) {
                val rel = row.optString("file", "")
                if (rel.isEmpty()) { skipped++; continue }
                val src = File(sessionDir, rel)
                if (!src.isFile || src.length() == 0L) { skipped++; continue }
                val name = src.name
                val dst = File(scan.imagesDir, name)
                if (!dst.exists()) linkOrCopy(src, dst)
                val ts = row.optLong("sensor_timestamp_ns", 0L)
                val q = row.optJSONObject("pose")?.optJSONArray("q_wxyz")
                val t = row.optJSONObject("pose")?.optJSONArray("t_mm")
                var vio: DoubleArray? = null
                if (q != null && q.length() == 4 && t != null && t.length() == 3) {
                    val qw = q.getDouble(0); val qx = q.getDouble(1); val qy = q.getDouble(2); val qz = q.getDouble(3)
                    val r = doubleArrayOf(
                        1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw),
                        2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw),
                        2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy))
                    vio = DoubleArray(16)
                    for (i in 0..2) for (j in 0..2) vio[i * 4 + j] = r[i * 3 + j]
                    vio[3] = t.getDouble(0) / 1000.0
                    vio[7] = t.getDouble(1) / 1000.0
                    vio[11] = t.getDouble(2) / 1000.0
                    vio[15] = 1.0
                }
                images += ScanImage(name, 0, -1, floatArrayOf(0f, 1f, 0f), ts,
                    0L, 0, 0f, false, 0L, null, vio)
                val near = hybrid.nearest(ts)
                var laserRel: String? = null
                if (near != null && near.frame.laser != null) {
                    val ldir = File(scan.dir, "hybrid/laser").apply { mkdirs() }
                    val ldst = File(ldir, name.removeSuffix(".jpg") + ".f32")
                    if (!ldst.exists()) linkOrCopy(near.frame.laser, ldst)
                    laserRel = ldst.relativeTo(scan.dir).path.replace(File.separatorChar, '/')
                }
                hybridRows += JSONObject()
                    .put("file", name)
                    .put("sensor_timestamp_ns", ts)
                    .put("frame", near?.frame?.index ?: -1)
                    .put("time_gap_ns", near?.gapNs ?: 0L)
                    .put("laser", laserRel ?: JSONObject.NULL)
                    .put("laser_points", near?.frame?.laserPoints ?: 0)
                    .put("board_pose16", near?.frame?.boardPose?.joinToString(" ") ?: JSONObject.NULL)
                    .put("vio_pose16", near?.frame?.vioPose?.joinToString(" ") ?: JSONObject.NULL)
                    .put("image_pose16", vio?.joinToString(" ") ?: JSONObject.NULL)
            }
        }

        // Laser is geometric data: keep every analysis frame, even frames that
        // have no 640x480 JPEG. The images stay the appearance keyframes.
        val laserRows = ArrayList<JSONObject>()
        var laserFramesWithPoints = 0
        for (f in hybrid.frames) {
            var laserRel: String? = null
            if (f.laser != null && f.laserPoints > 0) {
                val ldir = File(scan.dir, "hybrid/laser").apply { mkdirs() }
                val ldst = File(ldir, "f%05d.f32".format(f.index))
                if (!ldst.exists()) linkOrCopy(f.laser, ldst)
                laserRel = ldst.relativeTo(scan.dir).path.replace(File.separatorChar, '/')
                laserFramesWithPoints++
            }
            laserRows += JSONObject()
                .put("frame", f.index)
                .put("sensor_timestamp_ns", f.timestampNs)
                .put("laser", laserRel ?: JSONObject.NULL)
                .put("laser_points", f.laserPoints)
                .put("board_pose16", f.boardPose?.joinToString(" ") ?: JSONObject.NULL)
                .put("vio_pose16", f.vioPose?.joinToString(" ") ?: JSONObject.NULL)
        }
        val laserOnlyMeta = runCatching {
            val mf = File(sessionDir, "meta.json")
            mf.isFile && JSONObject(mf.readText()).optString("kind", "") == "laser_only_scan"
        }.getOrDefault(false)
        if (images.isEmpty()) {
            if (laserFramesWithPoints > 0 || laserOnlyMeta) {
                val exportsDir = File(scan.dir, "exports").apply { mkdirs() }
                File(sessionDir, "laser-only.ply").takeIf { it.isFile && it.length() > 0L }?.let { src ->
                    val dst = File(exportsDir, "laser-only.ply")
                    if (!dst.exists()) linkOrCopy(src, dst)
                }
                File(ctx.filesDir, "scans/laser-only-${sessionDir.name}.stl").takeIf { it.isFile && it.length() > 84L }?.let { src ->
                    val dst = File(exportsDir, "laser-only.stl")
                    if (!dst.exists()) linkOrCopy(src, dst)
                }
                val cam = ScanCamera(0, "EasyScanner Laser", cameraId, null, 0, intr, null)
                scan.write(listOf(cam), emptyList())
                val meta = JSONObject(scan.meta.readText())
                meta.put("source", "easyscan_laser")
                meta.put("mode", ScanKind.LASER_ONLY.wire)
                meta.put("sourceSession", sessionDir.name)
                session?.optJSONObject("board")?.let { meta.put("board", it) }
                scan.meta.writeText(meta.toString(1))
                if (hybrid.frames.isNotEmpty()) writeHybridSidecar(scan, sessionDir, hybrid, hybridRows, laserRows)
                return Result(
                    scan = scan,
                    images = 0,
                    skipped = skipped,
                    laserFrames = laserFramesWithPoints,
                    splatOnly = false,
                    kind = ScanKind.LASER_ONLY,
                    imageWidth = intr.width,
                    imageHeight = intr.height,
                )
            }
            error("no readable photos or frame JPEGs")
        }
        val resolvedKind = if (laserFramesWithPoints > 0) ScanKind.FUSED else ScanKind.SPLAT_ONLY
        val cam = ScanCamera(0, "EasyScanner", cameraId, null, 0, intr, null)
        scan.write(listOf(cam), images)
        val meta = JSONObject(scan.meta.readText())
        meta.put("source", if (resolvedKind == ScanKind.FUSED) "easyscan" else "easyscan_splat")
        meta.put("mode", resolvedKind.wire)
        meta.put("sourceSession", sessionDir.name)
        session?.optJSONObject("board")?.let { meta.put("board", it) }
        scan.meta.writeText(meta.toString(1))
        if (resolvedKind == ScanKind.FUSED && hybrid.frames.isNotEmpty()) {
            writeHybridSidecar(scan, sessionDir, hybrid, hybridRows, laserRows)
        }
        return Result(
            scan = scan,
            images = images.size,
            skipped = skipped,
            laserFrames = laserFramesWithPoints,
            splatOnly = resolvedKind == ScanKind.SPLAT_ONLY,
            kind = resolvedKind,
            imageWidth = intr.width,
            imageHeight = intr.height,
        )
    }

    /** Imports a high-resolution EasyScanner Splat-Only session (`splat_session.json` + `splat_photos/`). */
    private fun importSplatOnly(ctx: Context, sessionDir: File, manifestFile: File): Result {
        val doc = JSONObject(manifestFile.readText())
        val rawArr = doc.optJSONArray("images") ?: error("splat_session.json has no images")
        val allEntries = ArrayList<JSONObject>(rawArr.length())
        for (i in 0 until rawArr.length()) {
            rawArr.optJSONObject(i)?.let { allEntries.add(it) }
        }
        if (allEntries.isEmpty()) error("No high-res photos in Splat session")

        val chosen = if (allEntries.size > MAX_SPLAT_KEYFRAMES) {
            val out = ArrayList<JSONObject>(MAX_SPLAT_KEYFRAMES)
            for (k in 0 until MAX_SPLAT_KEYFRAMES) {
                val idx = ((k.toDouble() * (allEntries.size - 1)) / (MAX_SPLAT_KEYFRAMES - 1)).toInt()
                out.add(allEntries[idx])
            }
            out
        } else allEntries

        val firstFile = chosen.firstNotNullOfOrNull { row ->
            val rel = row.optString("file", "")
            if (rel.isNotEmpty()) File(sessionDir, rel).takeIf { it.isFile && it.length() > 0L } else null
        } ?: error("No readable JPEG files in Splat session")
        val jpegSize = probeJpeg(firstFile) ?: (doc.optInt("width", 1280) to doc.optInt("height", 960))

        val (base, cameraId) = resolveSplatIntrinsics(ctx, doc, jpegSize)
        val intr = if (base.width > 0 && base.height > 0 &&
            (jpegSize.first != base.width || jpegSize.second != base.height)
        ) {
            val sx = jpegSize.first.toDouble() / base.width
            val sy = jpegSize.second.toDouble() / base.height
            Intrinsics(
                jpegSize.first, jpegSize.second,
                base.fx * sx, base.fy * sy,
                (base.cx + 0.5) * sx - 0.5, (base.cy + 0.5) * sy - 0.5,
                base.k1, base.k2, base.p1, base.p2, base.k3, base.calibrated,
            )
        } else base

        val scan = ScanStore.create(ctx)
        val images = ArrayList<ScanImage>(chosen.size)
        var skipped = 0
        for (row in chosen) {
            val rel = row.optString("file", "")
            if (rel.isEmpty()) { skipped++; continue }
            val src = File(sessionDir, rel)
            if (!src.isFile || src.length() == 0L) { skipped++; continue }
            val name = src.name
            val dst = File(scan.imagesDir, name)
            if (!dst.exists()) linkOrCopy(src, dst)
            val ts = row.optLong("sensor_timestamp_ns", 0L)
            val vioPose = parsePose16(row.optString("vio_pose16", ""))
            images += ScanImage(name, 0, -1, floatArrayOf(0f, 1f, 0f), ts, 0L, 0, 0f, false, 0L, null, vioPose)
        }
        if (images.isEmpty()) error("No readable high-res photos in Splat session")

        val cam = ScanCamera(0, "EasyScanner Splat", cameraId, null, 0, intr, null)
        scan.write(listOf(cam), images)
        val meta = JSONObject(scan.meta.readText())
        meta.put("source", "easyscan_splat")
        meta.put("mode", "splat_only")
        meta.put("sourceSession", sessionDir.name)
        scan.meta.writeText(meta.toString(1))
        return Result(
            scan = scan,
            images = images.size,
            skipped = skipped,
            laserFrames = 0,
            splatOnly = true,
            imageWidth = intr.width,
            imageHeight = intr.height,
        )
    }

    private fun resolveSplatIntrinsics(
        ctx: Context,
        doc: JSONObject,
        jpegSize: Pair<Int, Int>,
    ): Pair<Intrinsics, String> {
        val camId = doc.optString("camera_id", "0").ifBlank { "0" }
        fun fromJson(o: JSONObject?): Intrinsics? {
            if (o == null) return null
            val w = o.optInt("width", 0)
            val h = o.optInt("height", 0)
            val fx = o.optDouble("fx", 0.0)
            val fy = o.optDouble("fy", 0.0)
            if (w <= 0 || h <= 0 || fx <= 0.0 || fy <= 0.0) return null
            val dist = o.optJSONArray("dist")
            fun d(i: Int) = dist?.optDouble(i, 0.0) ?: 0.0
            return Intrinsics(
                w, h, fx, fy,
                o.optDouble("cx", w * 0.5), o.optDouble("cy", h * 0.5),
                d(0), d(1), d(2), d(3), if (dist != null && dist.length() > 4) d(4) else 0.0,
                o.optBoolean("calibrated", true),
            )
        }
        fromJson(doc.optJSONObject("calibrated_intrinsics"))?.let { return it to camId }
        val liveFile = File(ctx.filesDir, "live_intrinsics.json")
        if (liveFile.isFile) {
            runCatching { fromJson(JSONObject(liveFile.readText())) }.getOrNull()?.let { return it to camId }
        }
        val w = jpegSize.first
        val h = jpegSize.second
        val f = maxOf(w, h) * 0.72
        return Intrinsics(w, h, f, f, w * 0.5, h * 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, false) to camId
    }

    private fun parsePose16(raw: String): DoubleArray? {
        if (raw.isBlank()) return null
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.size < 16) return null
        val out = DoubleArray(16)
        for (i in 0 until 16) {
            val d = parts[i].toDoubleOrNull() ?: return null
            if (!d.isFinite()) return null
            out[i] = d
        }
        return out
    }

    /**
     * Resolves camera intrinsics for an EasyScanner session from:
     * 1. `session.json` (when the Rust photo session wrote it),
     * 2. `files/live_intrinsics.json` (EasyScanner's calibrated camera profile), or
     * 3. the first valid `fx, fy, cx, cy` row in `frames.csv`.
     */
    private fun resolveIntrinsics(
        ctx: Context,
        sessionDir: File,
        session: JSONObject?,
        jpegSize: Pair<Int, Int>?,
    ): Pair<Intrinsics, String> {
        if (session != null) {
            runCatching {
                val eye = session.getJSONObject("eyes").getJSONObject("left")
                val intrJson = eye.getJSONObject("intrinsics")
                val wh = eye.getJSONArray("wh")
                val dist = intrJson.optJSONArray("dist")
                fun d(i: Int) = dist?.optDouble(i, 0.0) ?: 0.0
                val intr = Intrinsics(
                    wh.getInt(0), wh.getInt(1),
                    intrJson.getDouble("fx"), intrJson.getDouble("fy"),
                    intrJson.getDouble("cx"), intrJson.getDouble("cy"),
                    d(0), d(1), d(2), d(3), if (dist != null && dist.length() > 4) d(4) else 0.0,
                    !intrJson.isNull("rms_px"),
                )
                return intr to eye.optString("camera_id", "easyscan")
            }
        }
        val liveFile = File(ctx.filesDir, "live_intrinsics.json")
        if (liveFile.isFile) {
            runCatching {
                val o = JSONObject(liveFile.readText())
                val w = o.optInt("width", 0)
                val h = o.optInt("height", 0)
                val fx = o.optDouble("fx", 0.0)
                val fy = o.optDouble("fy", 0.0)
                if (w > 0 && h > 0 && fx > 0.0 && fy > 0.0) {
                    val dist = o.optJSONArray("dist")
                    fun d(i: Int) = dist?.optDouble(i, 0.0) ?: 0.0
                    val intr = Intrinsics(
                        w, h, fx, fy,
                        o.optDouble("cx", w * 0.5), o.optDouble("cy", h * 0.5),
                        d(0), d(1), d(2), d(3), if (dist != null && dist.length() > 4) d(4) else 0.0,
                        o.optBoolean("calibrated", true),
                    )
                    return intr to o.optString("camera_id", "0")
                }
            }
        }
        val csv = File(sessionDir, "frames.csv")
        if (csv.isFile) {
            runCatching {
                csv.bufferedReader().use { br ->
                    val hdr = br.readLine()?.split(',') ?: return@use
                    val iFx = hdr.indexOf("fx"); val iFy = hdr.indexOf("fy")
                    val iCx = hdr.indexOf("cx"); val iCy = hdr.indexOf("cy")
                    if (iFx >= 0 && iFy >= 0 && iCx >= 0 && iCy >= 0) {
                        while (true) {
                            val line = br.readLine() ?: break
                            val p = line.split(',')
                            val fx = p.getOrNull(iFx)?.toDoubleOrNull() ?: continue
                            val fy = p.getOrNull(iFy)?.toDoubleOrNull() ?: continue
                            val cx = p.getOrNull(iCx)?.toDoubleOrNull() ?: continue
                            val cy = p.getOrNull(iCy)?.toDoubleOrNull() ?: continue
                            if (fx > 10.0 && fy > 10.0) {
                                val w = jpegSize?.first ?: (cx * 2.0).toInt().coerceAtLeast(320)
                                val h = jpegSize?.second ?: (cy * 2.0).toInt().coerceAtLeast(240)
                                return Intrinsics(w, h, fx, fy, cx, cy, 0.0, 0.0, 0.0, 0.0, 0.0, false) to "easyscan"
                            }
                        }
                    }
                }
            }
        }
        val w = jpegSize?.first ?: 640
        val h = jpegSize?.second ?: 480
        val f = maxOf(w, h) * 0.72
        return Intrinsics(w, h, f, f, w * 0.5, h * 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, false) to "easyscan"
    }

    private fun probeJpeg(file: File): Pair<Int, Int>? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }.getOrNull()

    /** Keep the hybrid mapping next to the splat scan, independent of scan.json. */
    private fun writeHybridSidecar(
        scan: Scan,
        sessionDir: File,
        hybrid: HybridSession,
        rows: List<JSONObject>,
        laserRows: List<JSONObject>,
    ) {
        try {
            val dir = File(scan.dir, "hybrid").apply { mkdirs() }
            for (name in listOf("frames.csv", "imu.csv", "laser.ply", "scan.ply")) {
                val src = File(sessionDir, name)
                if (src.isFile) {
                    val dst = File(dir, name)
                    if (!dst.exists()) linkOrCopy(src, dst)
                }
            }
            val doc = JSONObject()
                .put("schema", "easyscanner.hybrid_scan.v1")
                .put("source_session", sessionDir.name)
                .put("source_manifest", hybrid.manifest?.toString() ?: JSONObject.NULL)
                .put("frames", "hybrid/frames.csv")
                .put("imu", "hybrid/imu.csv")
                .put("laser_root", "hybrid/laser")
                .put("frame", "board")
                .put("images", JSONArray(rows))
                .put("laser_frames", JSONArray(laserRows))
            File(dir, "hybrid.json").writeText(doc.toString(2))
        } catch (e: Exception) {
            android.util.Log.w("EasyScanImport", "hybrid sidecar failed", e)
        }
    }

    private fun linkOrCopy(src: File, dst: File) {
        runCatching { Files.createLink(dst.toPath(), src.toPath()) }.getOrElse {
            src.copyTo(dst, overwrite = false)
        }
    }
}
