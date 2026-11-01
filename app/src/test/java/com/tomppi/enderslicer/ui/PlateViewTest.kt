package com.tomppi.enderslicer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Plate <-> ScanRuler workspace choice: a persisted UI preference (like the rail's fold),
 * defaulting to the plate, and the source wiring that makes the ViewModel the single owner of
 * the state the in-app agent context reads.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PlateViewTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun resetTheStoredChoice() {
        context.getSharedPreferences(PlateViewPreference.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        PlateViewPreference.initialize(context)
    }

    @Test
    fun theDefaultWorkspaceIsThePlate() {
        assertEquals(PlateView.PLATE, PlateViewPreference.DEFAULT)
        assertEquals(PlateView.PLATE, PlateViewPreference.initialize(context))
        assertEquals(PlateView.PLATE, PlateViewPreference.current())
    }

    @Test
    fun theChosenWorkspaceSurvivesARelaunch() {
        PlateViewPreference.save(context, PlateView.SCAN_RULER)
        assertEquals(PlateView.SCAN_RULER, PlateViewPreference.current())
        assertEquals(
            "a later launch reads the ScanRuler workspace back",
            PlateView.SCAN_RULER,
            PlateViewPreference.initialize(context),
        )

        PlateViewPreference.save(context, PlateView.PLATE)
        assertEquals(PlateView.PLATE, PlateViewPreference.initialize(context))
    }

    @Test
    fun aStoredValueIsReadByTheKeyTheViewModelWrites() {
        context.getSharedPreferences(PlateViewPreference.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PlateViewPreference.KEY_VIEW, PlateView.SCAN_RULER.name)
            .commit()
        assertEquals(PlateView.SCAN_RULER, PlateViewPreference.initialize(context))
    }

    @Test
    fun unknownOrStaleValuesFallBackToThePlate() {
        assertEquals(PlateView.PLATE, PlateViewPreference.parse(null))
        assertEquals(PlateView.PLATE, PlateViewPreference.parse(""))
        assertEquals(PlateView.PLATE, PlateViewPreference.parse("CAD"))
        assertEquals(PlateView.SCAN_RULER, PlateViewPreference.parse("SCAN_RULER"))
    }

    @Test
    fun theViewModelOwnsTheStateAndThePreferenceWrite() {
        val vm = source("ui/MainViewModel.kt")
        assertTrue(vm.contains("activeView = PlateViewPreference.initialize(app)"))
        val setter = vm
            .substringAfter("fun setPlateView(view: PlateView)")
            .substringBefore("fun selectModel")
        assertTrue(setter.contains("PlateViewPreference.save(app, view)"))
        assertTrue(setter.contains("activeView = view"))
        assertTrue("no second state owner", !vm.contains("var activeView by mutableStateOf"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/" + relative),
            File("app/src/main/java/com/tomppi/enderslicer/" + relative),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Unable to locate " + relative)
    }
}
