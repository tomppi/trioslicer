package com.tomppi.splatproto

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.rawmechatronics.easyscanner.SessionExport
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages a reconstructed (or raw photo) [Scan] into a standard COLMAP dataset `.zip` ready for
 * PC Gaussian Splatting and photogrammetry tools (Postshot, LichtFeld Studio, Brush, Nerfstudio,
 * gsplat, COLMAP, RealityCapture, CloudCompare, MeshLab, Blender).
 *
 * Archive layout:
 * - `images/<name>.jpg` — original keyframes
 * - `masks/<name>.png` — U2Net object masks when present
 * - `sparse/0/cameras.{txt,bin}`, `images.{txt,bin}`, `points3D.{txt,bin}` — COLMAP model with
 *   exact camera model & distortion parameters matching `images/`
 * - `dense.ply`, `sparse.ply`, `exports/<name>.ply`, `exports/<name>.stl` — point clouds / splats / meshes
 * - `scan.json`, `report.json`, `README.txt`
 */
object PcDatasetExporter {

    data class Stats(
        val images: Int,
        val masks: Int,
        val hasColmap: Boolean,
        val hasDensePly: Boolean,
        val extraClouds: Int,
        val fileCount: Int,
    )

    data class Exported(
        val uri: Uri,
        val fileName: String,
        val relativePath: String,
        val bytes: Long,
        val stats: Stats,
    )

    fun exportZipToDownloads(
        context: Context,
        scan: Scan,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Exported {
        val safeName = scan.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val hasSparse = File(scan.dir, "sparse/0/images.txt").isFile
        val suffix = if (hasSparse) "colmap" else "photos"
        val fileName = "easyscanner-${safeName}-${suffix}.zip"
        val relDir = Environment.DIRECTORY_DOWNLOADS + "/" + SessionExport.DOWNLOADS_DIR

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, relDir)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        var uri: Uri? = null
        try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore refused $fileName")
            val stats: Stats
            val outStream = resolver.openOutputStream(uri)
                ?: throw IOException("Could not open Downloads stream for $fileName")
            val counting = CountingOutputStream(outStream)
            counting.use { os ->
                stats = writeDatasetZip(scan, os, onProgress)
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            return Exported(
                uri = uri,
                fileName = fileName,
                relativePath = "${SessionExport.DOWNLOADS_DIR}/$fileName",
                bytes = counting.bytesWritten,
                stats = stats,
            )
        } catch (e: Exception) {
            uri?.let { resolver.delete(it, null, null) }
            throw e
        }
    }

    /** Writes the standard COLMAP + photos dataset ZIP into [outFile] (used by JVM tests too). */
    fun writeDatasetZipToFile(
        scan: Scan,
        outFile: File,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Stats {
        FileOutputStream(outFile).use { fos ->
            return writeDatasetZip(scan, fos, onProgress)
        }
    }

    fun writeDatasetZip(
        scan: Scan,
        output: OutputStream,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Stats {
        val entries = ArrayList<Pair<String, File>>()
        var imageCount = 0
        var maskCount = 0
        var extraClouds = 0

        // 1. Keyframe images
        if (scan.imagesDir.isDirectory) {
            scan.imagesDir.listFiles { f -> f.isFile && !f.name.startsWith(".") }
                ?.sortedBy { it.name }
                ?.forEach { img ->
                    entries += ("images/${img.name}" to img)
                    imageCount++
                }
        }

        // 2. Object masks (if generated)
        val masksDir = File(scan.dir, "masks")
        if (masksDir.isDirectory) {
            masksDir.listFiles { f -> f.isFile && f.name.endsWith(".png", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?.forEach { mask ->
                    entries += ("masks/${mask.name}" to mask)
                    maskCount++
                }
        }

        // 3. Standard COLMAP sparse/0 (both .txt and exact-distortion .bin)
        val sparseDir = File(scan.dir, "sparse/0")
        var hasColmap = false
        var tempBinDir: File? = null
        if (File(sparseDir, "cameras.txt").isFile && File(sparseDir, "images.txt").isFile) {
            hasColmap = true
            for (name in listOf("cameras.txt", "images.txt", "points3D.txt")) {
                val f = File(sparseDir, name)
                if (f.isFile) entries += ("sparse/0/$name" to f)
            }
            // Write binary COLMAP preserving the original camera model & distortion params
            // matching images/*.jpg (Cross3DGS's own sparse/0/*.bin strips distortion for
            // undistorted images, so we emit a fresh exact binary into a temporary directory).
            val tmp = File(scan.dir, ".pc-colmap-bin-tmp").apply {
                deleteRecursively()
                mkdirs()
            }
            tempBinDir = tmp
            if (ColmapBinaryWriter.ensureBinaryWithCount(sparseDir, preserveCameraModel = true, outDir = tmp) >= 0) {
                for (name in listOf("cameras.bin", "images.bin", "points3D.bin")) {
                    val f = File(tmp, name)
                    if (f.isFile) entries += ("sparse/0/$name" to f)
                }
            }
        }

        // 4. Dense & sparse clouds
        val hasDensePly = scan.dense.isFile && scan.dense.length() > 80L
        if (hasDensePly) entries += ("dense.ply" to scan.dense)
        if (scan.sparse.isFile && scan.sparse.length() > 80L) entries += ("sparse.ply" to scan.sparse)

        // 5. Additional exports (laser PLYs, fused STL, trained splat)
        val exportsDir = File(scan.dir, "exports")
        if (exportsDir.isDirectory) {
            exportsDir.listFiles { f ->
                f.isFile && f.length() > 80L &&
                    f.extension.lowercase(Locale.ROOT) in setOf("ply", "stl", "splat")
            }?.sortedBy { it.name }?.forEach { f ->
                entries += ("exports/${f.name}" to f)
                extraClouds++
            }
        }

        // 6. Metadata
        if (scan.meta.isFile) entries += ("scan.json" to scan.meta)
        if (scan.report.isFile) entries += ("report.json" to scan.report)

        require(entries.isNotEmpty()) { "Scan ${scan.name} has no images or point clouds to export" }

        try {
            val zos = ZipOutputStream(BufferedOutputStream(output, 65536))
            try {
                // Write README.txt first so opening the ZIP immediately shows instructions.
                val readmeBytes = buildReadme(scan.name, imageCount, maskCount, hasColmap, hasDensePly, extraClouds)
                    .toByteArray(Charsets.UTF_8)
                val readmeEntry = ZipEntry("README.txt")
                zos.putNextEntry(readmeEntry)
                zos.write(readmeBytes)
                zos.closeEntry()

                val buf = ByteArray(65536)
                entries.forEachIndexed { idx, (zipPath, file) ->
                    onProgress(
                        (idx.toFloat() / entries.size.coerceAtLeast(1)).coerceIn(0f, 0.98f),
                        "Packing ${idx + 1}/${entries.size}: ${file.name}",
                    )
                    val ext = file.extension.lowercase(Locale.ROOT)
                    val storeRaw = ext in setOf("jpg", "jpeg", "png", "ply", "stl", "splat", "bin")
                    val ze = ZipEntry(zipPath)
                    if (storeRaw) {
                        ze.method = ZipEntry.STORED
                        ze.size = file.length()
                        ze.compressedSize = file.length()
                        ze.crc = crc32Of(file, buf)
                    }
                    zos.putNextEntry(ze)
                    file.inputStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            zos.write(buf, 0, n)
                        }
                    }
                    zos.closeEntry()
                }
                zos.finish()
            } finally {
                zos.flush()
            }
        } finally {
            tempBinDir?.deleteRecursively()
        }

        onProgress(1f, "PC dataset ZIP ready")
        return Stats(
            images = imageCount,
            masks = maskCount,
            hasColmap = hasColmap,
            hasDensePly = hasDensePly,
            extraClouds = extraClouds,
            fileCount = entries.size + 1,
        )
    }

    private fun crc32Of(file: File, buf: ByteArray): Long {
        val crc = CRC32()
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }

    private fun buildReadme(
        scanName: String,
        images: Int,
        masks: Int,
        hasColmap: Boolean,
        hasDensePly: Boolean,
        extraClouds: Int,
    ): String = buildString {
        appendLine("EasyScanner PC Export — $scanName")
        appendLine("======================================")
        appendLine()
        appendLine("Contents:")
        if (images > 0) appendLine("- images/       $images keyframe JPEG images")
        if (masks > 0) appendLine("- masks/        $masks U2Net object masks (<image>.png, 255=keep, 0=ignore)")
        if (hasColmap) {
            appendLine("- sparse/0/     Standard COLMAP camera poses & sparse 3D points (.txt and .bin)")
        }
        if (hasDensePly) appendLine("- dense.ply     Oriented dense MVS point cloud")
        if (extraClouds > 0) appendLine("- exports/      $extraClouds exported PLY / STL / splat file(s)")
        appendLine("- scan.json     Camera intrinsics and SuperVINS VIO poses")
        appendLine()
        appendLine("How to open on PC:")
        appendLine("1. Unzip this archive into a folder.")
        if (hasColmap) {
            appendLine("2. Gaussian Splatting (Postshot, LichtFeld Studio, Brush, gsplat, 3DGS, Nerfstudio):")
            appendLine("   Point the tool at the unzipped folder (contains images/ + sparse/0/).")
            appendLine("3. COLMAP GUI:")
            appendLine("   File -> Import model -> select sparse/0/ (images live in images/).")
        } else {
            appendLine("2. Gaussian Splatting / Photogrammetry (Postshot, COLMAP, RealityCapture, Metashape, Meshroom):")
            appendLine("   Import the images/ folder to run SfM and train splats or build meshes on PC.")
        }
        if (hasDensePly || extraClouds > 0) {
            appendLine("4. Point clouds & meshes:")
            appendLine("   Open dense.ply or exports/*.ply / *.stl in CloudCompare, MeshLab, or Blender.")
        }
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}
