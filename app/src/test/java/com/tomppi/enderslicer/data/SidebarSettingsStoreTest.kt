package com.tomppi.enderslicer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.ui.DEFAULT_SIDEBAR_SETTINGS
import com.tomppi.enderslicer.ui.SIDEBAR_INFILL
import com.tomppi.enderslicer.ui.SIDEBAR_LAYER_HEIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The sidebar's chosen rows, stored per engine.
 *
 * The distinction the store has to keep is between an engine whose sidebar has
 * never been edited - which shows the built-in four - and one the user emptied on
 * purpose, which shows nothing. Reading both back as an empty list would silently
 * wipe a sidebar the user had built.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SidebarSettingsStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun eachEngineKeepsItsOwnRowsAndTheySurviveARelaunch() {
        val store = AppStateStore(context)
        assertNull("nothing stored for an engine nobody edited", store.restoreSidebarSettings(SlicerEngine.CURA))

        store.saveSidebarSettings(SlicerEngine.CURA, listOf(SIDEBAR_LAYER_HEIGHT, "wall_line_count"))
        store.saveSidebarSettings(SlicerEngine.ORCA, listOf(SIDEBAR_INFILL))

        // A relaunch reads the store again with nothing remembered in memory.
        val relaunched = AppStateStore(context)
        assertEquals(
            listOf(SIDEBAR_LAYER_HEIGHT, "wall_line_count"),
            relaunched.restoreSidebarSettings(SlicerEngine.CURA),
        )
        assertEquals(listOf(SIDEBAR_INFILL), relaunched.restoreSidebarSettings(SlicerEngine.ORCA))
        assertNull(
            "Prusa keeps its own list, which nobody has touched",
            relaunched.restoreSidebarSettings(SlicerEngine.PRUSA),
        )
    }

    @Test
    fun anEmptiedSidebarIsNotTheSameAsOneNeverEdited() {
        AppStateStore(context).saveSidebarSettings(SlicerEngine.CURA, emptyList())

        assertEquals(
            emptyList<String>(),
            AppStateStore(context).restoreSidebarSettings(SlicerEngine.CURA),
        )
    }
}
