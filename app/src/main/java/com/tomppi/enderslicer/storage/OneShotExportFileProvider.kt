package com.tomppi.enderslicer.storage

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * FileProvider for temporary model handoffs.
 *
 * BumpMesh and filaSim exports can be hundreds of megabytes. Their consumers
 * materialize the content immediately, so the private cache file is deleted as
 * soon as the granted read descriptor is closed instead of accumulating until
 * Android eventually evicts the app cache.
 */
class OneShotExportFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") return openNormally(uri, mode)
        val file = resolveOneShotExport(uri) ?: return openNormally(uri, mode)
        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_ONLY,
            Handler(Looper.getMainLooper()),
        ) {
            file.delete()
        } ?: throw FileNotFoundException("Temporary export could not be opened")
    }

    private fun openNormally(uri: Uri, mode: String): ParcelFileDescriptor =
        super.openFile(uri, mode)
            ?: throw FileNotFoundException("FileProvider could not open the requested URI")

    private fun resolveOneShotExport(uri: Uri): File? {
        val segments = uri.pathSegments
        if (segments.size < 2) return null
        val rootDirectory = when (segments.first()) {
            BUMPMESH_ROOT -> File(requireNotNull(context).cacheDir, BUMPMESH_DIRECTORY)
            SMART_INFILL_ROOT -> File(requireNotNull(context).cacheDir, SMART_INFILL_DIRECTORY)
            else -> return null
        }
        val root = rootDirectory.canonicalFile
        val target = segments.drop(1)
            .fold(rootDirectory) { parent, segment -> File(parent, segment) }
            .canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) {
            throw FileNotFoundException("Export URI escapes its configured cache directory")
        }
        if (!target.isFile) throw FileNotFoundException("Temporary export is unavailable")
        return target
    }

    companion object {
        /**
         * The cache directories this provider serves, and the URI roots they are
         * served under. Both halves are mirrored by `res/xml/file_paths.xml`, which
         * is what `FileProvider.getUriForFile` checks: a file written outside them
         * throws instead of handing out a URI, so every writer uses these names
         * rather than a literal. `ExportPathsTest` proves the pair still matches
         * the packaged configuration.
         */
        const val SMART_INFILL_DIRECTORY = "smart-infill-exports"
        const val BUMPMESH_DIRECTORY = "bumpmesh-exports"

        private const val BUMPMESH_ROOT = "bumpmesh_exports"
        private const val SMART_INFILL_ROOT = "smart_infill_exports"
    }
}
