package com.tomppi.enderslicer.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.SlicerSettings

/**
 * One-shot flag that decides whether the first-run onboarding has run.
 *
 * It is only written when the last step - the interface scale - is finished;
 * neither step can be skipped and back does not leave the flow. The preference
 * name and key are the ones the old skippable setup wrote, so an install that
 * already finished that one is past this flow and never sees the scale step.
 */
class OnboardingStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isComplete(): Boolean = preferences.getBoolean(KEY_DONE, false)

    fun complete() {
        preferences.edit().putBoolean(KEY_DONE, true).apply()
    }

    private companion object {
        const val PREFERENCES = "onboarding"
        const val KEY_DONE = "done"
    }
}

/** Where the first-run flow is. Only the last step lets the app be used. */
internal enum class OnboardingStep { PRINT_SETUP, SCALE, COMPLETE }

/**
 * The first-run flow as a state machine, so what the gate does is testable
 * without a device.
 */
internal object OnboardingFlow {
    fun initialStep(complete: Boolean): OnboardingStep =
        if (complete) OnboardingStep.COMPLETE else OnboardingStep.PRINT_SETUP

    fun advance(step: OnboardingStep): OnboardingStep = when (step) {
        OnboardingStep.PRINT_SETUP -> OnboardingStep.SCALE
        OnboardingStep.SCALE -> OnboardingStep.COMPLETE
        OnboardingStep.COMPLETE -> OnboardingStep.COMPLETE
    }

    /**
     * Back inside the flow. It never leaves it: the setup is required, so the
     * first step stays where it is rather than falling out to the launcher.
     */
    fun back(step: OnboardingStep): OnboardingStep = when (step) {
        OnboardingStep.SCALE -> OnboardingStep.PRINT_SETUP
        else -> step
    }

    /** True only where the "done" flag may be written. */
    fun marksComplete(step: OnboardingStep): Boolean = step == OnboardingStep.COMPLETE
}

/**
 * First-run setup, step 1 of 2: the machine. It cannot be skipped - there is no
 * Skip control and back stays on this screen - because these values drive the
 * engine and the build plate from the very first slice. Machine values are
 * edited directly in the app state, so everything is consistent with the Print
 * settings and the build-plate viewer from the very first slice.
 * See docs/ux-redesign/mockups/07-onboarding.png.
 */
@Composable
internal fun OnboardingScreen(
    state: MainUiState,
    onSettings: (String, (SlicerSettings) -> SlicerSettings) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(28.dp))
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(18.dp))
            Text("TrioSlicer", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "One app for slicing, previewing and printing.\nStart by telling us about your machine.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Your printer", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    StringField( "Printer name", settings.printerName, "Built-in default") {
                        onSettings(SlicerSettings.Keys.PRINTER_NAME) { current -> current.copy(printerName = it.take(120)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            NumberField("Build width X (mm)", settings.machineWidthMm, "Built-in default") {
                                onSettings(SlicerSettings.Keys.MACHINE_WIDTH) { current -> current.copy(machineWidthMm = it.coerceIn(1.0, 2000.0)) }
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            NumberField("Build depth Y (mm)", settings.machineDepthMm, "Built-in default") {
                                onSettings(SlicerSettings.Keys.MACHINE_DEPTH) { current -> current.copy(machineDepthMm = it.coerceIn(1.0, 2000.0)) }
                            }
                        }
                    }
                    NumberField("Build height Z (mm)", settings.machineHeightMm, "Built-in default") {
                        onSettings(SlicerSettings.Keys.MACHINE_HEIGHT) { current -> current.copy(machineHeightMm = it.coerceIn(1.0, 2000.0)) }
                    }
                    NumberField("Nozzle diameter (mm)", settings.nozzleSizeMm, "Built-in default") {
                        onSettings(SlicerSettings.Keys.NOZZLE_SIZE) { current -> current.copy(nozzleSizeMm = it.coerceIn(0.05, 5.0)) }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("!", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Importing a Cura project (.3mf) fills machine and print settings from your desktop setup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Step 1 of 2 · printer setup",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Continue")
            }
            Text(
                "Required before the first print · next: interface scale",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * First-run setup, step 2 of 2: the interface scale, chosen against the real
 * Plate. The app is drawn behind this panel exactly as it looks after setup,
 * and it resizes as the slider moves - so the size is picked on the screen the
 * user will actually land on. The panel is pinned to the device's own density
 * while the plate behind it grows, so the control stays under the finger that
 * is setting it. Done is the only way out of the first run.
 */
@Composable
internal fun OnboardingScaleStep(
    currentPercent: Int,
    onScale: (percent: Int, commit: Boolean) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val deviceDensity = remember(context, configuration) {
        Density(context.resources.displayMetrics.density, configuration.fontScale)
    }
    Box(modifier = modifier.fillMaxSize()) {
        // The app behind is what this step is about, but it is a preview while
        // the step is unfinished: a touch that reached it could open a screen
        // whose back this step has consumed. The scrim takes every gesture.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                },
        )
        // The whole panel is drawn at the device's density, its padding and
        // spacing included: on the app's own density the panel would grow and
        // shrink with the value it is setting, and the slider would slide out
        // from under the finger holding it. Only the plate behind it resizes.
        CompositionLocalProvider(LocalDensity provides deviceDensity) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                tonalElevation = EnderSlicerDimens.Space4,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            horizontal = EnderSlicerDimens.Space16,
                            vertical = EnderSlicerDimens.Space12,
                        ),
                    verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
                ) {
                    Text(
                        "Step 2 of 2 · interface scale",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "How large the app draws itself. The plate behind is the real screen, at this size.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    UiScaleControl(
                        currentPercent = currentPercent,
                        onChange = onScale,
                        live = true,
                    )
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text("Done")
                    }
                }
            }
        }
    }
}
