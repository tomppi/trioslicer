package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The two settings columns of a wide window, tagged so a test can measure them. */
internal const val SETTINGS_LEFT_PANE_TAG = "settings-left-pane"
internal const val SETTINGS_RIGHT_PANE_TAG = "settings-right-pane"

/**
 * One engine's settings screen, in the two shapes a window has - and the one place
 * that shape is written, so all three engines look and behave the same.
 *
 * A phone renders [menu] once, in the one scrolling column it has always had.
 *
 * A wide window renders the **same menu twice**, side by side, each with its own
 * scroll: two settings menus, not a list of sections and a detail pane. Every card
 * keeps its own open or closed state per column - the state lives in the card's own
 * saveable slot - so one section can be open on the left and another on the right
 * and twice the settings are on the screen at once.
 *
 * [menu]'s parameter is whether the sheet's own default cards start open: true in
 * the phone's single column, which is the screen it has always been, and false in
 * the two columns, where a card that opened itself in both would only show the same
 * fields twice.
 */
@Composable
internal fun SettingsSheet(
    title: String,
    expandedLayout: Boolean,
    modifier: Modifier = Modifier,
    menu: @Composable (phoneDefaults: Boolean) -> Unit,
) {
    if (expandedLayout) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                SettingsPane(
                    modifier = Modifier.weight(1f).testTag(SETTINGS_LEFT_PANE_TAG),
                    menu = menu,
                )
                // The divider is the split's own line; the space either side of it
                // is what keeps the two columns' fields from reading as one.
                VerticalDivider(modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp))
                SettingsPane(
                    modifier = Modifier.weight(1f).testTag(SETTINGS_RIGHT_PANE_TAG),
                    menu = menu,
                )
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            menu(true)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One column of the wide layout: the whole menu, its own scroll, its own cards. */
@Composable
private fun SettingsPane(
    menu: @Composable (phoneDefaults: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        menu(false)
        Spacer(Modifier.height(24.dp))
    }
}
