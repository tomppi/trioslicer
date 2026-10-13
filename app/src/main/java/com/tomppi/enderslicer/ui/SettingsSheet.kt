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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The two settings columns of a wide window, tagged so a test can measure them. */
internal const val SETTINGS_LEFT_PANE_TAG = "settings-left-pane"
internal const val SETTINGS_RIGHT_PANE_TAG = "settings-right-pane"

/** Every settings card carries its own title as a tag, so a test can list them. */
internal const val SETTINGS_SECTION_TAG_PREFIX = "settings-section:"

/**
 * One engine's imported snapshot, in its profile block: the file the settings came
 * from, the line that says they are a snapshot of it, what it carried, and anything
 * in it this app will not send to the engine - named, not dropped in silence.
 *
 * Below it sits "Reset to engine defaults", which is the stronger of the pair: this
 * card's own button puts back what the import set, and that one discards the import
 * as well. Each says which it is, and each asks before it acts.
 */
@Composable
internal fun EngineSnapshotCard(
    snapshot: EngineSnapshot,
    overrides: Int,
    onAskReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(snapshot.name, style = MaterialTheme.typography.titleMedium)
            Text(snapshot.source, style = MaterialTheme.typography.bodySmall)
            if (snapshot.importedValues > 0) {
                Text(
                    snapshot.importedValues.toString() + " imported values · " +
                        overrides.toString() + " app overrides",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (snapshot.notSentKeys.isNotEmpty()) {
                // The names are the point: a key the engine is never given is a
                // difference between what was imported and what will be printed.
                Text(
                    "Not sent to this engine: " + snapshot.notSentKeys.take(SNAPSHOT_KEY_NAMES_SHOWN)
                        .joinToString(", ") +
                        if (snapshot.notSentKeys.size > SNAPSHOT_KEY_NAMES_SHOWN) {
                            " and " + (snapshot.notSentKeys.size - SNAPSHOT_KEY_NAMES_SHOWN) + " more"
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(
                onClick = onAskReset,
                enabled = overrides > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Reset all app overrides")
            }
        }
    }
}

/** How many refused keys the snapshot names before it counts the rest. */
private const val SNAPSHOT_KEY_NAMES_SHOWN = 8

/**
 * One collapsible card of a settings screen: what it is called, whether the phone
 * opens it by default, and its rows.
 *
 * The screen is these, in order - the same list the phone stacks in its one column
 * and the wide layout splits between its two.
 */
internal class SettingsSection(
    val title: String,
    val initiallyExpanded: Boolean,
    val content: @Composable () -> Unit,
)

/**
 * One engine's settings screen, in the two shapes a window has - and the one place
 * that shape is written, so all three engines look and behave the same.
 *
 * A phone renders the profile block ([header]) and then **every** section, in the
 * one scrolling column it has always had.
 *
 * A wide window splits that same list in two, side by side: the profile block and
 * the first half of the sections in the left column, the rest in the right. It is a
 * partition, not a copy - every section is reachable exactly once, in one column or
 * the other - and the cut is the app's own order split down the middle, which is
 * where a reader looks for a section whose position he knows. Each column scrolls on
 * its own and each card keeps its own open or closed state, so Quality can be open on
 * the left while Walls and top/bottom is open on the right and twice the settings are
 * on the screen at once. The cards start closed in both columns: a card that opened
 * itself twice would only show the same fields twice.
 */
@Composable
internal fun SettingsSheet(
    title: String,
    expandedLayout: Boolean,
    sections: List<SettingsSection>,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
) {
    if (expandedLayout) {
        // The app's own order, split down the middle: with an odd number the left
        // column takes the extra one, since it also carries the profile block.
        val split = (sections.size + 1) / 2
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
                    header = header,
                    sections = sections.take(split),
                )
                // The divider is the split's own line; the space either side of it
                // is what keeps the two columns' fields from reading as one.
                VerticalDivider(modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp))
                SettingsPane(
                    modifier = Modifier.weight(1f).testTag(SETTINGS_RIGHT_PANE_TAG),
                    sections = sections.drop(split),
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
            header()
            sections.forEach { section ->
                SettingsCategory(section.title, initiallyExpanded = section.initiallyExpanded) {
                    section.content()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One column of the wide layout: its own scroll, its own cards. */
@Composable
private fun SettingsPane(
    sections: List<SettingsSection>,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        header?.invoke()
        sections.forEach { section ->
            // Closed in the two columns: which two sections are open is the user's
            // choice, and a card that opened itself in both would be the duplicate
            // the split exists to avoid.
            SettingsCategory(section.title, initiallyExpanded = false) {
                section.content()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
