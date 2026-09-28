package com.tomppi.enderslicer.ui.klipper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import com.tomppi.enderslicer.printer.missingStarterMacros
import kotlinx.coroutines.launch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.printer.KlipperMacro
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.printer.macros

/**
 * The printer's own macros, which is where most of what a machine can do lives.
 *
 * Read from its configuration rather than from a list in this app: a macro is a section
 * in printer.cfg, so whatever the printer has is what appears here - including the ones
 * this app's own screens use, which are worth being able to run by hand.
 *
 * Macros whose names begin with an underscore and the ones that rename an existing
 * command are left out, which is what Moonraker and Mainsail do for the same reason:
 * the first are helpers meant to be called by other macros, and the second are the
 * printer's replacement for a built-in command rather than something to press.
 */
@Composable
internal fun KlipperMacrosTab(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    var filter by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val missing = state.missingStarterMacros
    val all = state.macros
    val shown = remember(all, filter) {
        val needle = filter.trim()
        if (needle.isEmpty()) all
        else all.filter {
            it.name.contains(needle, ignoreCase = true) ||
                it.description.contains(needle, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        //
        // Klipper's own starter macros, for the ones this printer does not have.
        //
        // The macros themselves live in printer.cfg, which belongs to the user - so this offers
        // the ones a Klipper printer is usually given and adds only what is missing, leaving
        // every macro already in the file exactly as it is. Klipper reads its macros at start,
        // so the host is restarted afterwards, which is what makes them appear on this screen.
        //
        if (missing.isNotEmpty() || added.isNotEmpty()) {
            KlipperCard(
                title = "Starter macros",
                subtitle = if (missing.isEmpty()) "Added" else "${missing.size} not in printer.cfg",
            ) {
                KlipperNote(
                    "Klipper's own sample macros, adapted: a filament change, load and unload, " +
                        "and object cancellation under Marlin's name. Adding them writes new " +
                        "sections into printer.cfg and leaves everything already there alone. " +
                        "The host restarts to pick them up.",
                )
                Spacer(Modifier.height(4.dp))
                missing.forEach { macro -> KlipperValue(macro.name, macro.summary) }
                added.forEach { name -> KlipperValue(name, "added") }
                Spacer(Modifier.height(8.dp))
                KlipperButtons {
                    KlipperButton(
                        text = if (adding) "Adding…" else "Add them",
                        enabled = !adding && missing.isNotEmpty() && state.isReady,
                    ) {
                        adding = true
                        scope.launch {
                            val names = viewModel.addStarterMacros()
                            adding = false
                            added = names
                            if (names.isNotEmpty()) viewModel.restartHost()
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Find a macro") },
            singleLine = true,
            enabled = all.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (all.isEmpty()) {
            Text(
                text = if (state.connected) {
                    "This printer defines no macros."
                } else {
                    "The host is not reachable, so its macros cannot be read."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Text(
            text = shown.size.toString() + " of " + all.size + " macros · tapping one runs it",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(shown, key = { it.name }) { macro ->
                MacroRow(macro, enabled = state.isReady) { viewModel.runMacro(macro.name) }
            }
        }
    }
}

/** One macro: what it is called, what it says it does, and a way to run it. */
@Composable
private fun MacroRow(macro: KlipperMacro, enabled: Boolean, onRun: () -> Unit) {
    KlipperCard(title = macro.name) {
        if (macro.description.isNotBlank()) {
            Text(
                text = macro.description.trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KlipperButton("Run", enabled = enabled, onClick = onRun)
        }
    }
}
