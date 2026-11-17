package com.rawmechatronics.easyscanner

import java.io.File

/**
 * What the app has captured, for the on-phone viewer: the scan sessions
 * (`files/sessions/<id>/`: photos, scan.ply / laser.ply) and the loose
 * expert-mode exports (the PLY files in `files/scans/`), newest first.
 */
object ScanLibrary {
    class Item(val name: String, val modified: Long, val dir: File?, val cloud: File?, val points: Int?, val photos: List<File>)

    fun list(files: File): List<Item> {
        val sessions = (File(files, "sessions").listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { d ->
            val cloud = cloudOf(d)
            val photos = photos(d)
            if (cloud == null && photos.isEmpty()) null
            else Item(d.name, newest(d), d, cloud, cloud?.let(PlyCloud::vertexCount), photos)
        }
        val scans = (File(files, "scans").listFiles { f -> f.isFile && f.name.endsWith(".ply") } ?: emptyArray()).map {
            Item(it.name.removeSuffix(".ply"), it.lastModified(), null, it, PlyCloud.vertexCount(it), emptyList())
        }
        return (sessions + scans).sortedByDescending { it.modified }
    }

    /** The Z-up scan.ply when the session has one, else orbiter-recon's laser.ply (board-centred, also Z up). */
    fun cloudOf(dir: File): File? =
        listOf(SessionExport.SCAN_PLY, "laser.ply").map { File(dir, it) }.firstOrNull { it.isFile && it.length() > 0 }

    fun photos(dir: File): List<File> =
        (File(dir, "photos").listFiles { f -> f.name.endsWith(".jpg") && !f.name.startsWith(".") } ?: emptyArray())
            .sortedBy { it.name }

    private fun newest(dir: File): Long = dir.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: dir.lastModified()
}
