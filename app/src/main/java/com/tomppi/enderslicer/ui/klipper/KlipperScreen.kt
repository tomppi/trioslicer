package com.tomppi.enderslicer.ui.klipper

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
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
                KlipperTab.MESH -> KlipperMeshTab(state, viewModel)
                KlipperTab.HISTORY -> KlipperHistoryTab(viewModel)
                KlipperTab.MACHINE -> KlipperMachineTab(state, viewModel)
            }
        }
    }
}
