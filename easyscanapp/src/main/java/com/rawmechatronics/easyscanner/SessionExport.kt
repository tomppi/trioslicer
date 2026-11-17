package com.rawmechatronics.easyscanner

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Copies scan sessions out of the app's own storage (which only the app and
 * adb can read) into `Download/EasyScanner/<session>/`, where a computer sees
 * them over USB and orbiter-recon can take the folder as it is. The original
 * stays; a marker file records the copy so a session is offered only once.
 */
object SessionExport {
    const val DOWNLOADS_DIR = "EasyScanner"
    private const val MARKER = "exported.json"
    /** Files of a copy that broke off, one relative path a line: a retry skips them instead of doubling them. */
    private const val PROGRESS = ".export_progress"
    const val SCAN_PLY = "scan.ply"

    /** [names]: the top-level files copied (scan.ply, session.json, ...). */
    class Copied(val relativeDir: String, val files: Int, val photos: Int, val names: Set<String>, val scanUri: Uri?)

    /** Sessions under [root] with something in them that was never copied out, oldest first. */
    fun pending(root: File, except: File?): List<File> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it != except && !File(it, MARKER).exists() && contents(it).isNotEmpty() }
            .sortedBy { it.name }

    fun photoCount(dir: File) = File(dir, "photos").listFiles { f -> f.name.endsWith(".jpg") && !f.name.startsWith(".") }?.size ?: 0

    private fun contents(dir: File) = dir.walkTopDown()
        .filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".tmp") && it.name != MARKER }
        .toList()

    /**
     * Copy every file of [dir]; throws on the first file that fails. The
     * files before it stay copied and are skipped when the copy is retried.
     */
    fun copyToDownloads(ctx: Context, dir: File): Copied {
        val files = contents(dir)
        if (files.isEmpty()) throw IOException("nothing was captured")
        val base = "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOADS_DIR/${dir.name}"
        val progress = File(dir, PROGRESS)
        val done = if (progress.exists()) progress.readLines().toHashSet() else HashSet()
        var scanUri: Uri? = null
        for (f in files) {
            val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
            if (rel in done) continue
            val sub = rel.substringBeforeLast('/', "")
            val uri = copyOne(ctx, f, if (sub.isEmpty()) "$base/" else "$base/$sub/")
            if (rel == SCAN_PLY) scanUri = uri
            progress.appendText("$rel\n")
        }
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        File(dir, MARKER).writeText(JSONObject().put("downloads", base).put("files", files.size).put("utc", utc).toString(1))
        progress.delete()
        val names = files.filter { it.parentFile == dir }.map { it.name }.toSet()
        return Copied(base, files.size, photoCount(dir), names, scanUri)
    }

    /** One file into the Downloads collection (our own entries need no storage permission). */
    private fun copyOne(ctx: Context, f: File, relativePath: String): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime(f.name))
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads refused ${f.name}")
        try {
            (resolver.openOutputStream(uri) ?: throw IOException("cannot write ${f.name}")).use { out ->
                f.inputStream().use { it.copyTo(out) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            // no half-written entry may stay behind (MediaStore also throws unchecked exceptions)
            runCatching { resolver.delete(uri, null, null) }
            throw if (e is IOException) e else IOException("${f.name}: ${e.message}", e)
        }
        return uri
    }

    /** A generic type for anything without a registered one keeps MediaStore from renaming the file. */
    private fun mime(name: String) = when (name.substringAfterLast('.').lowercase(Locale.US)) {
        "jpg", "jpeg" -> "image/jpeg"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }
}
