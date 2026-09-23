package com.tomppi.enderslicer.data

import android.content.Context
import android.net.Uri

/** Records a user-visible SAF destination until the complete payload is durably closed. */
class PendingDocumentExportStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun begin(uri: Uri) {
        check(preferences.edit().putString(KEY_PENDING_URI, uri.toString()).commit()) {
            "Unable to record the pending export destination"
        }
    }

    fun complete(uri: Uri) {
        if (preferences.getString(KEY_PENDING_URI, null) == uri.toString()) {
            check(preferences.edit().remove(KEY_PENDING_URI).commit()) {
                "Unable to complete the export transaction"
            }
        }
    }

    /**
     * Forgets a failed export without touching its destination.
     *
     * The failure can arrive after every byte was already flushed - the process
     * was killed between the flush and the commit, or the commit itself failed -
     * so the document is left in place: deleting it would take away something the
     * user may already have received. Reporting the failure is the caller's job.
     */
    fun fail(uri: Uri) {
        // A commit that fails here is not worth a second exception on a path that
        // is already handling one; the record then survives to [recover].
        runCatching { complete(uri) }
    }

    /**
     * Forgets an export a previous process never completed, and returns its
     * destination for the caller to report.
     *
     * The document is deliberately not deleted for the same reason as [fail]: an
     * interrupted process may have written all of it.
     */
    fun recover(): Uri? {
        val raw = preferences.getString(KEY_PENDING_URI, null) ?: return null
        preferences.edit().remove(KEY_PENDING_URI).commit()
        return Uri.parse(raw)
    }

    private companion object {
        const val PREFERENCES = "enderslicer-pending-exports-v1"
        const val KEY_PENDING_URI = "pending-uri"
    }
}
