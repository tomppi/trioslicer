package com.tomppi.enderslicer.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.SlicerSettings

/**
 * One-shot flag that decides whether the first-run onboarding has run.
 * Skipping is also completing: the app never re-prompts.
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

/**
 * First-run setup (step 1 of 3, skippable). Machine values are edited
 * directly in the app state, so everything is consistent with the Print
 * settings and the build-plate viewer from the very first slice.
 * See docs/ux-redesign/mockups/07-onboarding.png.
 */
@Composable
internal fun OnboardingScreen(
    state: MainUiState,
    onSettings: (String, (SlicerSettings) -> SlicerSettings) -> Unit,
    onDone: () -> Unit,
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
                "Step 1 of 3 · printer",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) {
                    Text("Skip for now")
                }
                Button(onClick = onDone, modifier = Modifier.weight(1f)) {
                    Text("Continue")
                }
            }
        }
    }
}
