package com.tomppi.enderslicer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.octoprint.OctoPrintViewModel
import com.tomppi.enderslicer.ui.EnderSlicerTheme
import com.tomppi.enderslicer.ui.IntegratedEnderSlicerApp
import com.tomppi.enderslicer.ui.MainViewModel
import com.tomppi.enderslicer.ui.OnboardingScreen
import com.tomppi.enderslicer.ui.OnboardingStore
import com.tomppi.enderslicer.ui.SlicerEngineStore

class MainActivity : ComponentActivity() {
    private val slicerViewModel by viewModels<MainViewModel>()
    private val octoPrintViewModel by viewModels<OctoPrintViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MeshTriangleLimits.initialize(this)
        enableEdgeToEdge()
        requestNotificationPermission()
        setContent {
            val engineStore = remember { SlicerEngineStore(applicationContext) }
            var engine by remember { mutableStateOf(engineStore.load()) }
            EnderSlicerTheme(engine = engine) {
                val state by slicerViewModel.uiState.collectAsStateWithLifecycle()
                // First-run onboarding (skippable, one-shot): sets the machine
                // values that drive the engine and the build-plate viewer.
                var onboardingDone by remember {
                    mutableStateOf(OnboardingStore(applicationContext).isComplete())
                }
                if (!onboardingDone) {
                    OnboardingScreen(
                        state = state,
                        onSettings = slicerViewModel::updateSettings,
                        onDone = {
                            OnboardingStore(applicationContext).complete()
                            onboardingDone = true
                        },
                    )
                } else {
                    IntegratedEnderSlicerApp(
                        slicerViewModel = slicerViewModel,
                        octoPrintViewModel = octoPrintViewModel,
                        engine = engine,
                        onEngineChange = {
                            engineStore.save(it)
                            engine = it
                            slicerViewModel.onEngineChanged()
                        },
                    )
                }
            }
        }
    }

    /**
     * The Blender engine keeper runs a foreground service whose persistent
     * notification is visible only with the POST_NOTIFICATIONS runtime grant
     * (Android 13+). Ask once at first launch so users SEE that the engine is
     * alive with the screen locked; the service still runs (process pinned)
     * even if the user declines.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0x4E01)
    }
}
