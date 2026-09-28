package com.tomppi.enderslicer.ui.klipper

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperViewModel

/**
 * The printer, as ten screens rather than one scroll.
 *
 * The app drives the printer itself, so this is the whole of its front end: what it is
 * doing, what it is set to, moving it, feeding it, its macros, its files, what it says,
 * the bed it measured, what it has printed, and the machine underneath all of it. The
 * reference for what belongs on each screen is what a user of Mainsail or Fluidd
 * already knows how to find, arranged for a phone rather than for a desktop browser.
 *
 * The tab that is showing is remembered across a rotation and across leaving the
 * destination, because a user who was watching a temperature is not asking to be
 * returned to the dashboard every time the screen turns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KlipperScreen(
    state: KlipperPrinterState,
    viewModel: KlipperViewModel,
    localGcodePath: String?,
    suggestedFileName: String,
    modifier: Modifier = Modifier,
) {
    var selectedName by rememberSaveable { mutableStateOf(KlipperTab.DASHBOARD.name) }
    val selected = KlipperTab.named(selectedName)
    // The tab that is showing is remembered, so the state of the one that was showing has to
    // be too: only one is composed at a time, and a composable that leaves composition is
    // forgotten rather than saved. Without this a frequency typed into Shaping was gone the
    // moment the user looked at another tab - and the save they pressed afterwards wrote the
    // configured value instead, which is a silent way to lose a measurement.
    val tabState = rememberSaveableStateHolder()
    var confirmEmergencyStop by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.navigationBarsPadding()) {
        SecondaryScrollableTabRow(
            selectedTabIndex = selected.ordinal,
            edgePadding = 8.dp,
        ) {
            KlipperTab.entries.forEach { tab ->
                Tab(
                    selected = tab == selected,
                    onClick = { selectedName = tab.name },
                    text = { Text(tab.label) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                )
            }
        }
        HorizontalDivider()
        //
        // While a print is running, the way to stop it is on every screen rather than only on
        // the dashboard: a print going wrong is noticed wherever the user happens to be
        // looking, and the cost of finding another tab is paid in filament.
        //
        if (state.isPrinting || state.isPaused) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.printFileName?.takeIf { it.isNotBlank() }
                        ?: if (state.isPaused) "Paused" else "Printing",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                KlipperButton("Stop") { confirmEmergencyStop = true }
            }
            HorizontalDivider()
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Keyed by the tab, so the state of the tab being left is saved rather than
            // forgotten, and restored when the user comes back to it.
            tabState.SaveableStateProvider(selected.name) {
                when (selected) {
                    KlipperTab.DASHBOARD ->
                        KlipperDashboardTab(state, viewModel, localGcodePath, suggestedFileName)
                    // Each screen collects the flows it reads itself, rather than the whole
                    // interface being rebuilt when a console line arrives on another one.
                    KlipperTab.TEMPERATURES -> KlipperTemperaturesTab(state, viewModel)
                    KlipperTab.MOVE -> KlipperMoveTab(state, viewModel)
                    KlipperTab.EXTRUDE -> KlipperExtrudeTab(state, viewModel)
                    KlipperTab.MACROS -> KlipperMacrosTab(state, viewModel)
                    KlipperTab.FILES ->
                        KlipperFilesTab(state, viewModel, localGcodePath, suggestedFileName)
                    KlipperTab.CONSOLE -> KlipperConsoleTab(state, viewModel)
                    KlipperTab.ZPROBE -> KlipperZProbeTab(state, viewModel)
                    KlipperTab.SHAPING -> KlipperShapingTab(state, viewModel)
                    KlipperTab.MESH -> KlipperMeshTab(state, viewModel)
                    KlipperTab.HISTORY -> KlipperHistoryTab(viewModel)
                    KlipperTab.MACHINE -> KlipperMachineTab(state, viewModel)
                }
            }
        }
    }

    if (confirmEmergencyStop) {
        KlipperConfirmDialog(
            title = "Emergency stop?",
            text = "The printer stops at once and the print cannot be resumed from here. The " +
                "homing it has done and the mesh it measured survive; the print does not.",
            confirmLabel = "Stop the printer",
            destructive = true,
            onConfirm = {
                confirmEmergencyStop = false
                viewModel.emergencyStop()
            },
            onDismiss = { confirmEmergencyStop = false },
        )
    }
}
