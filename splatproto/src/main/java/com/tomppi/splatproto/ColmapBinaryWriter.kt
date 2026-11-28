package com.tomppi.splatproto

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts Parallax's text COLMAP model (cameras.txt, images.txt, points3D.txt)
 * into binary COLMAP tables (cameras.bin, images.bin, points3D.bin) for Cross3DGS.
 *
 * Cross3DGS trains on undistorted pinhole images (`cross3dgs/images`), so OPENCV
 * cameras (model 4) are written as PINHOLE (model 1) keeping [fx, fy, cx, cy].
 * Sparse 3D points are written verbatim so Cross3DGS initializes directly on the
 * reconstructed scene geometry when no laser checkpoint is supplied.
 */
object ColmapBinaryWriter {
    private val CAMERA_MODELS = mapOf(
        "SIMPLE_PINHOLE" to 0, "PINHOLE" to 1, "SIMPLE_RADIAL" to 2,
        "RADIAL" to 3, "OPENCV" to 4, "OPENCV_FISHEYE" to 5,
        "FULL_OPENCV" to 6, "FOV" to 7, "SIMPLE_RADIAL_FISHEYE" to 8,
        "RADIAL_FISHEYE" to 9, "THIN_PRISM_FISHEYE" to 10,
        "RAD_TAN_THIN_PRISM_FISHEYE" to 11,
    )

    fun ensureBinary(sparseDir: File, preserveCameraModel: Boolean = false): Boolean =
        ensureBinaryWithCount(sparseDir, preserveCameraModel) >= 0

    /**
     * Converts text COLMAP files to binary and returns the sparse 3D point count, or -1 on error.
     * When [preserveCameraModel] is false (default, for Cross3DGS on undistorted images), OPENCV
     * cameras are converted to PINHOLE. When true (for PC COLMAP exports with original photos),
     * the exact camera model and distortion parameters from `cameras.txt` are preserved.
     */
    fun ensureBinaryWithCount(
        sparseDir: File,
        preserveCameraModel: Boolean = false,
        outDir: File = sparseDir,
    ): Int {
        if (!sparseDir.isDirectory) return -1
        val camerasTxt = File(sparseDir, "cameras.txt")
        val imagesTxt = File(sparseDir, "images.txt")
        if (!camerasTxt.isFile || !imagesTxt.isFile) return -1
        val cameras = readCameras(camerasTxt) ?: return -1
        val images = readImages(imagesTxt) ?: return -1
        if (cameras.isEmpty() || images.isEmpty()) return -1
        val pointsTxt = File(sparseDir, "points3D.txt")
        val points = if (pointsTxt.isFile) readPoints(pointsTxt) else emptyList()
        outDir.mkdirs()
        writeCameras(File(outDir, "cameras.bin"), cameras, preserveCameraModel)
        writeImages(File(outDir, "images.bin"), images)
        writePoints(File(outDir, "points3D.bin"), points)
        return points.size
    }

    /** Reads the leading uint64 point count from points3D.bin (0 if missing or invalid). */
    fun readBinaryPointsCount(sparseDir: File): Int {
        val f = File(sparseDir, "points3D.bin")
        if (!f.isFile || f.length() < 8L) return 0
        return runCatching {
            val head = ByteArray(8)
            f.inputStream().use { if (it.read(head) != 8) return 0 }
            val count = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).long
            if (count in 0..Int.MAX_VALUE) count.toInt() else 0
        }.getOrDefault(0)
    }

    private data class Cam(val id: Int, val model: Int, val width: Long, val height: Long, val params: DoubleArray)
    private data class Img(val id: Int, val q: DoubleArray, val t: DoubleArray, val camera: Int, val name: String)
    private data class Pt3(val id: Long, val x: Double, val y: Double, val z: Double, val r: Int, val g: Int, val b: Int, val err: Double)

    private fun readCameras(file: File): List<Cam>? {
        val out = ArrayList<Cam>()
        file.forEachLine { line ->
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) return@forEachLine
            val p = s.split(Regex("\\s+"))
            if (p.size < 4) return@forEachLine
            val id = p[0].toIntOrNull() ?: return@forEachLine
            val model = CAMERA_MODELS[p[1]] ?: return@forEachLine
            val w = p[2].toLongOrNull() ?: return@forEachLine
            val h = p[3].toLongOrNull() ?: return@forEachLine
            val params = p.drop(4).mapNotNull { it.toDoubleOrNull() }.toDoubleArray()
            out.add(Cam(id, model, w, h, params))
        }
        return out
    }

    private fun readImages(file: File): List<Img>? {
        val out = ArrayList<Img>()
        val lines = file.readLines()
        var i = 0
        while (i < lines.size) {
            val s = lines[i].trim()
            i++
            if (s.isEmpty() || s.startsWith("#")) continue
            val p = s.split(Regex("\\s+"))
            if (p.size < 10) continue
            val id = p[0].toIntOrNull() ?: continue
            val q = DoubleArray(4) { k -> p[1 + k].toDoubleOrNull() ?: return null }
            val t = DoubleArray(3) { k -> p[5 + k].toDoubleOrNull() ?: return null }
            val cam = p[8].toIntOrNull() ?: continue
            // Preserve image names that may contain spaces by taking the remainder after cam_id.
            val name = p.subList(9, p.size).joinToString(" ")
            out.add(Img(id, q, t, cam, name))
            // The following line is the 2D observation list; skip it.
            if (i < lines.size) i++
        }
        return out
    }

    private fun readPoints(file: File): List<Pt3> {
        val out = ArrayList<Pt3>()
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                val s = line.trim()
                if (s.isEmpty() || s.startsWith("#")) continue
                val p = s.split(Regex("\\s+"))
                if (p.size < 8) continue
                val id = p[0].toLongOrNull() ?: continue
                val x = p[1].toDoubleOrNull() ?: continue
                val y = p[2].toDoubleOrNull() ?: continue
                val z = p[3].toDoubleOrNull() ?: continue
                if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
                val r = (p[4].toIntOrNull() ?: 128).coerceIn(0, 255)
                val g = (p[5].toIntOrNull() ?: 128).coerceIn(0, 255)
                val b = (p[6].toIntOrNull() ?: 128).coerceIn(0, 255)
                val err = p[7].toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
                out.add(Pt3(id, x, y, z, r, g, b, err))
            }
        }
        return out
    }

    private fun writeCameras(file: File, list: List<Cam>, preserveCameraModel: Boolean) {
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(list.size.toLong()).array()
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            out.write(head)
            val fixed = ByteBuffer.allocate(4 + 4 + 8 + 8).order(ByteOrder.LITTLE_ENDIAN)
            for (c in list) {
                // Cross3DGS trains on undistorted pinhole images in cross3dgs/images, so when
                // preserveCameraModel is false, convert OPENCV (4) / FULL_OPENCV (6) to PINHOLE (1).
                val stripDistortion = !preserveCameraModel && (c.model == 4 || c.model == 6)
                val model = if (stripDistortion) 1 else c.model
                val params = if (stripDistortion) c.params.take(4) else c.params.toList()
                fixed.clear()
                fixed.putInt(c.id); fixed.putInt(model); fixed.putLong(c.width); fixed.putLong(c.height)
                out.write(fixed.array())
                val pb = ByteBuffer.allocate(params.size * 8).order(ByteOrder.LITTLE_ENDIAN)
                for (d in params) pb.putDouble(d)
                out.write(pb.array())
            }
        }
    }

    private fun writeImages(file: File, list: List<Img>) {
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(list.size.toLong()).array())
            val buf = ByteBuffer.allocate(4 + 7 * 8 + 4).order(ByteOrder.LITTLE_ENDIAN)
            val zeroLong = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0L).array()
            for (im in list) {
                buf.clear()
                buf.putInt(im.id)
                for (d in im.q) buf.putDouble(d)
                for (d in im.t) buf.putDouble(d)
                buf.putInt(im.camera)
                out.write(buf.array())
                out.write(im.name.toByteArray(Charsets.UTF_8))
                out.write(0)
                out.write(zeroLong)
            }
        }
    }

    private fun writePoints(file: File, list: List<Pt3>) {
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(list.size.toLong()).array())
            // Per COLMAP points3D.bin record: uint64 id, 3*double xyz, 3*uint8 rgb, double err, uint64 track_len(0).
            val rec = ByteBuffer.allocate(8 + 24 + 3 + 8 + 8).order(ByteOrder.LITTLE_ENDIAN)
            for (pt in list) {
                rec.clear()
                rec.putLong(pt.id)
                rec.putDouble(pt.x); rec.putDouble(pt.y); rec.putDouble(pt.z)
                rec.put(pt.r.toByte()); rec.put(pt.g.toByte()); rec.put(pt.b.toByte())
                rec.putDouble(pt.err)
                rec.putLong(0L)
                out.write(rec.array())
            }
        }
    }
}
