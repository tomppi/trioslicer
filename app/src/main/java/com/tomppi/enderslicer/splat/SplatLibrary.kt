package com.tomppi.enderslicer.splat

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * The splat and PLY files the viewer can open across TrioSlicer and EasyScanner.
 *
 * Indexes:
 * - Trained Gaussian splats & imported scenes in `files/splats/`
 * - Trained splats & refined laser clouds in `files/scans/<id>/exports/`
 * - Live mesh PLYs in `files/scans/`
 * - EasyScanner session PLYs in `Android/data/.../files/sessions/<id>/`
 */
object SplatLibrary {

    /** The formats the viewers can load. */
    val EXTENSIONS: Set<String> = setOf("sog", "ply", "spz", "splat", "ksplat")

    data class Entry(
        val file: File,
        val displayName: String,
        val badge: String,
        val isGaussianSplat: Boolean,
        val canDelete: Boolean = true,
    )

    /** Lists all viewable splat and PLY files across app storage, newest first. */
    fun listEntries(context: Context): List<Entry> {
        val out = ArrayList<Entry>()
        val seenCanonical = HashSet<String>()
        val seenNames = HashSet<String>()

        fun addFile(file: File, displayName: String, badge: String? = null) {
            if (!file.isFile || file.length() < 300L || !isSupported(file.name)) return
            val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            if (!seenCanonical.add(canonical)) return
            val gaussian = isGaussianSplat(file)
            val resolvedBadge = badge ?: inferBadge(file.name, gaussian)
            out += Entry(
                file = file,
                displayName = displayName,
                badge = resolvedBadge,
                isGaussianSplat = gaussian,
                canDelete = isInsideAppStorage(context, file),
            )
            seenNames.add(file.name.lowercase())
        }

        // 1. Primary splat folder (trained Cross3DGS *-splat.ply, imported .sog/.ply, CloudView .splat)
        val primaryDir = directory(context)
        primaryDir.listFiles()
            ?.filter { it.isFile && isSupported(it.name) }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { file -> addFile(file, file.name) }

        // 2. SplatProto / EasyScanner scan exports and live meshes in files/scans/
        val scansRoot = File(context.filesDir, "scans")
        if (scansRoot.isDirectory) {
            scansRoot.listFiles()?.forEach { child ->
                if (child.isFile && child.extension.lowercase() == "ply") {
                    if (child.name.lowercase() !in seenNames) {
                        addFile(child, child.name, "Live Mesh")
                    }
                } else if (child.isDirectory) {
                    val exportsDir = File(child, "exports")
                    val scanName = child.name
                    val exportedSplat = File(exportsDir, "splat.ply")
                    if (exportedSplat.isFile && "${scanName}-splat.ply".lowercase() !in seenNames) {
                        addFile(exportedSplat, "$scanName-splat.ply", "Splat")
                    }
                    val laserRefined = File(exportsDir, "laser-ba-refined.ply")
                    if (laserRefined.isFile) {
                        addFile(laserRefined, "$scanName · laser-ba-refined.ply", "Laser PLY")
                    }
                    val laserOnly = File(exportsDir, "laser-only.ply")
                    if (laserOnly.isFile) {
                        addFile(laserOnly, "$scanName · laser-only.ply", "Laser PLY")
                    }
                }
            }
        }

        // 3. EasyScanner captured sessions in externalFilesDir/sessions/
        val sessionsRoot = context.getExternalFilesDir(null)?.let { File(it, "sessions") }
        if (sessionsRoot != null && sessionsRoot.isDirectory) {
            sessionsRoot.listFiles { f -> f.isDirectory }?.forEach { sessionDir ->
                val sid = sessionDir.name
                for ((fname, tag) in listOf(
                    "laser-only.ply" to "Laser PLY",
                    "laser.ply" to "Laser PLY",
                    "scan.ply" to "EasyScan PLY",
                )) {
                    val f = File(sessionDir, fname)
                    if (f.isFile && f.length() >= 300L) {
                        addFile(f, "$sid · $fname", tag)
                    }
                }
            }
        }

        out.sortByDescending { it.file.lastModified() }
        return out
    }

    /** Backwards-compatible file list, newest first. */
    fun list(context: Context): List<File> = listEntries(context).map { it.file }

    /** The folder the viewer reads and imports into. */
    fun directory(context: Context): File = File(context.filesDir, "splats").apply { mkdirs() }

    fun isSupported(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /**
     * Distinguishes 3D Gaussian Splat scenes (`.sog`/`.spz`/`.ksplat`, or `.ply` with Gaussian
     * attributes like `f_dc_0` / `opacity` / `scale_0`) from plain XYZ/RGB laser point clouds.
     */
    fun isGaussianSplat(file: File): Boolean {
        val ext = file.extension.lowercase()
        if (ext in setOf("sog", "spz", "ksplat")) return true
        if (ext != "ply") return false
        return runCatching {
            val buf = ByteArray(2048)
            val n = file.inputStream().use { it.read(buf) }
            if (n <= 0) return@runCatching false
            val header = String(buf, 0, n, Charsets.US_ASCII)
            header.contains("f_dc_0") ||
                (header.contains("opacity") && header.contains("scale_0"))
        }.getOrDefault(false)
    }

    private fun inferBadge(name: String, gaussian: Boolean): String {
        val lower = name.lowercase()
        return when {
            gaussian -> "Splat"
            lower.startsWith("fused-") -> "Fusion Cloud"
            lower.startsWith("laser-") -> "Laser Cloud"
            lower.startsWith("live-mesh-") -> "Live Mesh"
            lower.startsWith("da3-") -> "Depth Cloud"
            lower.startsWith("ar-scan-") || lower.startsWith("arscan-") -> "Scan Cloud"
            lower.endsWith(".ply") -> "PLY Cloud"
            else -> "Cloud"
        }
    }

    /**
     * A file name that is safe to append to the folder: no separators, no "..", no control
     * characters, and always with one of the supported extensions.
     */
    fun safeName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            for (character in base) {
                when {
                    character.isLetterOrDigit() || character == '.' || character == '_' || character == '-' -> append(character)
                    character == ' ' -> append('_')
                }
            }
        }.trim('.', '_')
        if (cleaned.isEmpty() || cleaned == "..") return null
        if (!isSupported(cleaned)) return null
        return cleaned.take(120)
    }

    /**
     * Copy a document the user picked into the folder, under a name that cannot escape it.
     */
    fun import(context: Context, uri: Uri, displayName: String?): File {
        val name = safeName(displayName) ?: throw IllegalArgumentException(
            "That file is not a splat or PLY the viewer can open (" +
                EXTENSIONS.joinToString("/") + ").",
        )
        val folder = directory(context)
        val destination = uniqueFile(folder, name)
        val temporary = File(folder, "." + destination.name + ".part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("The file could not be read.")
            if (!temporary.renameTo(destination)) {
                throw IOException("The file could not be saved.")
            }
            return destination
        } finally {
            temporary.delete()
        }
    }

    /** The same name unless it is taken, then "name-1.ext", "name-2.ext"... */
    fun uniqueFile(folder: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var candidate = File(folder, name)
        var index = 1
        while (candidate.exists()) {
            candidate = File(folder, base + "-" + index + (if (extension.isEmpty()) "" else "." + extension))
            index++
        }
        return candidate
    }

    /** True only when [file] resolves inside the app's own internal or external storage roots. */
    fun isInsideAppStorage(context: Context, file: File): Boolean {
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return false
        val allowedRoots = listOfNotNull(
            runCatching { directory(context).canonicalPath }.getOrNull(),
            runCatching { File(context.filesDir, "scans").canonicalPath }.getOrNull(),
            runCatching { context.getExternalFilesDir(null)?.let { File(it, "sessions").canonicalPath } }.getOrNull(),
        )
        return allowedRoots.any { root -> canonical.startsWith(root + File.separator) }
    }

    /** Remove one splat/PLY inside the app's own scan/splat storage. */
    fun delete(context: Context, file: File): Boolean {
        if (!isInsideAppStorage(context, file)) return false
        return file.delete()
    }

    /** A human title for the viewer's toolbar: the file name without its extension. */
    fun titleOf(file: File): String = file.name.substringBeforeLast('.', file.name)
}
