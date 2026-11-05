package com.tomppi.enderslicer.scanruler.engine

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream

/**
 * Where ScanRuler's agent-facing files go and what their descriptor reports.
 *
 * MediaStore owns the stored name - a collision becomes "name (1).ext" - so the descriptor
 * reports the name the collection assigned, never the one that was merely asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScanRulerFileExportsTest {

    private class FakeDownloads : ContentProvider() {
        val requestedNames = ArrayList<String>()

        override fun onCreate(): Boolean = true

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            requestedNames += values?.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty()
            return ASSIGNED
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)).apply {
            addRow(arrayOf(ASSIGNED_NAME))
        }

        override fun getType(uri: Uri): String = "application/octet-stream"

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 1

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 1

        companion object {
            val ASSIGNED: Uri = Uri.parse("content://media/external/downloads/42")
            const val ASSIGNED_NAME = "part (1).step"
        }
    }

    private lateinit var context: Context
    private lateinit var provider: FakeDownloads

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = FakeDownloads()
        ShadowContentResolver.registerProviderInternal("media", provider)
        shadowOf(context.contentResolver).registerOutputStream(FakeDownloads.ASSIGNED, ByteArrayOutputStream())
    }

    @Test
    fun theReportedNameIsTheOneMediaStoreAssigned() {
        val written = ScanRulerFileExports.write(context, byteArrayOf(1, 2, 3), "part.step", "application/step")
        assertEquals(listOf("part.step"), provider.requestedNames)
        assertEquals(FakeDownloads.ASSIGNED_NAME, written.name)
        assertEquals("application/step", written.mimeType)
        assertEquals(3L, written.size)
        assertEquals(FakeDownloads.ASSIGNED.toString(), written.uri)
    }

    @Test
    fun theMimeTypeFallsBackToTheExtension() {
        assertEquals("application/step", ScanRulerFileExports.mimeTypeFor("part.step"))
        assertEquals("model/stl", ScanRulerFileExports.mimeTypeFor("part.STL"))
        assertEquals("model/3mf", ScanRulerFileExports.mimeTypeFor("part.3mf"))
        assertEquals("application/octet-stream", ScanRulerFileExports.mimeTypeFor("part.bin"))
    }

    @Test
    fun aResultWithoutAMimeTypeGetsTheOneFromItsName() {
        val result = JSONObject().put(
            "file",
            JSONObject().put("name", "part.3mf").put("bytes", byteArrayOf(1)),
        )
        assertEquals(1, ScanRulerFileExports.rewriteBinaryValues(context, result))
        val stored = result.getJSONObject("file").getJSONObject("bytes")
        assertEquals("model/3mf", stored.getString("mimeType"))
        assertEquals(FakeDownloads.ASSIGNED_NAME, stored.getString("name"))
    }
}
