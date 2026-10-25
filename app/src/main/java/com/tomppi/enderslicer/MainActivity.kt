package com.tomppi.enderslicer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomppi.enderslicer.engine.EnginePreparation
import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.nativebridge.KlipperEngineService
import com.tomppi.enderslicer.octoprint.OctoPrintViewModel
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.solidcraft.SolidCraftEngine
import com.tomppi.enderslicer.ui.Diagnostics
import com.tomppi.enderslicer.ui.EnderSlicerTheme
import com.tomppi.enderslicer.ui.EngineSetupScreen
import com.tomppi.enderslicer.ui.IntegratedEnderSlicerApp
import com.tomppi.enderslicer.ui.MainViewModel
import com.tomppi.enderslicer.ui.OnboardingFlow
import com.tomppi.enderslicer.ui.OnboardingScaleStep
import com.tomppi.enderslicer.ui.OnboardingScreen
import com.tomppi.enderslicer.ui.OnboardingStep
import com.tomppi.enderslicer.ui.OnboardingStore
import com.tomppi.enderslicer.ui.SlicerEngineStore
import com.tomppi.enderslicer.ui.UiScale
import com.tomppi.enderslicer.ui.decodeEngineSelection
import com.tomppi.enderslicer.ui.encodeEngineSelection

/**
 * A FragmentActivity rather than a bare ComponentActivity: androidx.biometric hosts its
 * prompt in a fragment, and the two "Copy MCP token" items are gated behind that prompt.
 * FragmentActivity is a ComponentActivity subclass, so setContent, enableEdgeToEdge and
 * the viewModels above are unchanged.
 */
class MainActivity : FragmentActivity() {
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
     * The SolidCraft engine is a child process, and a child does not die with its parent.
     * Left running it holds the live design in a process nobody can see or stop, so ending
     * the app ends it. Stopping the activity for a rotation is not ending the app: the view
     * model survives and the engine stays up with the plate.
     */
    override fun onDestroy() {
        if (isFinishing) {
            // The stop waits for the child to exit, and this is the UI thread: ending the app
            // must not wait up to the five seconds the destroy could take.
            Thread { runCatching { SolidCraftEngine.stop() } }.start()
        }
        super.onDestroy()
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
        UiScale.initialize(this)
        Diagnostics.initialize(this)
        enableEdgeToEdge()
        requestNotificationPermission()
        startKlipperHostIfPrinterAttached(intent)
        setContent {
            val engineStore = remember { SlicerEngineStore(applicationContext) }
            var engine by remember { mutableStateOf(engineStore.load()) }
            EnderSlicerTheme(engine = engine) {
                val state by slicerViewModel.uiState.collectAsStateWithLifecycle()
                val onboardingStore = remember { OnboardingStore(applicationContext) }
                // Read before the provider below: inside it LocalDensity is already scaled.
                val deviceDensity = LocalDensity.current
                var uiScalePercent by remember { mutableIntStateOf(UiScale.current()) }
                // Applied on every change, persisted when the control commits: the
                // first-run scale step resizes the plate as the thumb moves, the
                // Settings sheet when the thumb is let go.
                val onUiScaleChange: (Int, Boolean) -> Unit = { percent, commit ->
                    uiScalePercent = UiScale.sanitize(percent)
                    if (commit) UiScale.save(applicationContext, percent)
                }
                CompositionLocalProvider(
                    LocalDensity provides UiScale.scaled(deviceDensity, uiScalePercent),
                ) {
                    // First-run setup: step 1 sets the machine, step 2 sets the
                    // interface scale over the real plate, and only finishing step 2
                    // writes the one-shot flag. An install that finished the old
                    // skippable onboarding carries the same flag, so it starts at the
                    // app and never sees the new step.
                    var step by rememberSaveable {
                        mutableStateOf(OnboardingFlow.initialStep(onboardingStore.isComplete()))
                    }
                    when (step) {
                        OnboardingStep.PRINT_SETUP -> OnboardingScreen(
                            state = state,
                            onSettings = slicerViewModel::updateSettings,
                            onContinue = { step = OnboardingFlow.advance(step) },
                        )
                        // The first init, last because it is the only step that can spend
                        // minutes and hundreds of megabytes. It is skippable on purpose: the
                        // app works without it, and every engine's own screen now says what
                        // it is waiting for and where to prepare it.
                        OnboardingStep.ENGINES -> {
                            val prepRows by EnginePreparation.rows.collectAsStateWithLifecycle()
                            var selection by rememberSaveable {
                                mutableStateOf(
                                    encodeEngineSelection(
                                        EnginePreparation.defaultSelection(engine.name.lowercase()),
                                    ),
                                )
                            }
                            // A screen opened after a previous run says ready rather than
                            // offering work that is already done.
                            LaunchedEffect(Unit) {
                                EnginePreparation.refresh(applicationContext)
                            }
                            EngineSetupScreen(
                                rows = prepRows,
                                selected = decodeEngineSelection(selection),
                                // Read from the rows rather than a flag: the rows are observable,
                                // and "busy" is exactly "something is still being written".
                                busy = prepRows.any { it.phase == EnginePreparation.Phase.RUNNING },
                                freeBytes = EnginePreparation.freeBytes(applicationContext),
                                onPrepare = {
                                    // The run owns its own scope: skipping this step must not
                                    // kill an extraction half way and leave a tree without a stamp.
                                    EnginePreparation.start(
                                        applicationContext,
                                        decodeEngineSelection(selection),
                                    )
                                },
                                onToggle = { toggled ->
                                    val next = decodeEngineSelection(selection).toMutableSet()
                                    if (!next.remove(toggled)) {
                                        next.add(toggled)
                                        // CAD cannot run without the Python the Klipper payload
                                        // brings, so a CAD tick ticks it too rather than
                                        // extracting 533 MB behind an unticked row.
                                        if (toggled == EnginePreparation.Engine.CAD) {
                                            next.add(EnginePreparation.Engine.KLIPPER)
                                        }
                                    }
                                    selection = encodeEngineSelection(next)
                                },
                                onSelectAll = {
                                    selection = encodeEngineSelection(EnginePreparation.Engine.entries.toSet())
                                },
                                // Both leave through the same door: prepared or skipped, the
                                // step is finished and the app opens. The screen picks which
                                // label to show from the state of the rows.
                                onDone = {
                                    onboardingStore.complete()
                                    step = OnboardingFlow.advance(step)
                                },
                                onSkip = {
                                    onboardingStore.complete()
                                    step = OnboardingFlow.advance(step)
                                },
                                onBack = { step = OnboardingFlow.back(step) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        else -> Box(modifier = Modifier.fillMaxSize()) {
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
                                uiScalePercent = uiScalePercent,
                                onUiScaleChange = onUiScaleChange,
                            )
                            if (step == OnboardingStep.SCALE) {
                                OnboardingScaleStep(
                                    currentPercent = uiScalePercent,
                                    onScale = onUiScaleChange,
                                    onDone = {
                                        val next = OnboardingFlow.advance(step)
                                        // Only the finished flow sets the flag.
                                        if (OnboardingFlow.marksComplete(next)) {
                                            onboardingStore.complete()
                                        }
                                        step = next
                                    },
                                )
                            }
                        }
                    }
                    // Composed after the app, so while the first run is unfinished
                    // this is the handler that answers Back.
                    BackHandler(enabled = step != OnboardingStep.COMPLETE) {
                        val back = OnboardingFlow.back(step)
                        if (back == step) {
                            Toast.makeText(
                                applicationContext,
                                "Finish the setup to start printing",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        step = back
                    }
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
