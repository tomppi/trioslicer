package com.tomppi.enderslicer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
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
import com.tomppi.enderslicer.nativebridge.KlipperEngineService
import com.tomppi.enderslicer.octoprint.OctoPrintViewModel
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.ui.EnderSlicerTheme
import com.tomppi.enderslicer.ui.IntegratedEnderSlicerApp
import com.tomppi.enderslicer.ui.MainViewModel
import com.tomppi.enderslicer.ui.OnboardingScreen
import com.tomppi.enderslicer.ui.OnboardingStore
import com.tomppi.enderslicer.ui.SlicerEngineStore

class MainActivity : ComponentActivity() {
    private val slicerViewModel by viewModels<MainViewModel>()
    private val octoPrintViewModel by viewModels<OctoPrintViewModel>()

    /**
     * The printer this device drives itself. Held by the activity so the connection to
     * the host survives the screen being left and comes back with it.
     */
    private val klipperViewModel by viewModels<KlipperViewModel>()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startKlipperHostIfPrinterAttached(intent)
    }

    /**
     * Start the Klipper host when a printer has just been plugged in.
     *
     * This activity is the app's registered handler for that intent, so Android has
     * also just granted permission for the device - the one moment the port can be
     * opened without a dialog. Nothing else starts the host: with no printer there
     * is nothing for it to drive.
     */
    private fun startKlipperHostIfPrinterAttached(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        KlipperEngineService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MeshTriangleLimits.initialize(this)
        enableEdgeToEdge()
        requestNotificationPermission()
        startKlipperHostIfPrinterAttached(intent)
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
                        klipperViewModel = klipperViewModel,
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
