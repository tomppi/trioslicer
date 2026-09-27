package com.tomppi.enderslicer.ui.klipper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomppi.enderslicer.printer.KlipperConsoleLine
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperViewModel

/**
 * Everything the printer says, and a line to answer it with.
 *
 * klippy pushes no output until a client subscribes, so what is here is what this app
 * asked for - including the commands it sent itself, because a button that moved the
 * machine to the wrong place should leave a trace of what it said. The same stream
 * carries its errors, which is how a macro that fails stops looking like a macro that
 * did nothing.
 *
 * The command names are klippy's own list for this printer, so what is offered is what
 * it will actually accept - the extended commands of its config sections and macros
 * included, which is most of what anyone types here.
 */
@Composable
internal fun KlipperConsoleTab(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    val lines by viewModel.consoleLines.collectAsStateWithLifecycle()
    val commands by viewModel.commands.collectAsStateWithLifecycle()
    var typed by rememberSaveable { mutableStateOf("") }
    val sent = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    fun send(script: String = typed) {
        val text = script.trim()
        if (text.isEmpty()) return
        viewModel.sendCommand(text)
        sent.remove(text)
        sent.add(0, text)
        while (sent.size > RECENT_COMMANDS) sent.removeAt(sent.size - 1)
        typed = ""
    }

    // Follow the output only while the end is in view: scrolling a user back down
    // while they are reading something further up is worse than not following at all.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && !listState.canScrollForward) {
            listState.scrollToItem(lines.lastIndex)
        }
    }

    val suggestions = remember(typed, commands) {
        val prefix = typed.trim().uppercase()
        if (prefix.isEmpty() || prefix.contains(' ')) emptyList()
        else commands.keys.filter { it.startsWith(prefix) }.take(SUGGESTIONS)
    }
    val help = commands[typed.trim().uppercase()]

    Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    !state.connected -> "The host is not reachable"
                    else -> "\u25B8 " + (state.printFileName?.takeIf { state.isPrinting || state.isPaused }
                        ?: state.state)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            KlipperButton("Clear") { viewModel.clearConsole() }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(lines) { line ->
                KlipperMono(
                    text = line.text,
                    color = when (line.source) {
                        KlipperConsoleLine.Source.SENT -> MaterialTheme.colorScheme.primary
                        KlipperConsoleLine.Source.ERROR -> MaterialTheme.colorScheme.error
                        KlipperConsoleLine.Source.OUTPUT -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        if (suggestions.isNotEmpty()) {
            KlipperButtons {
                suggestions.forEach { name ->
                    KlipperButton(name) { typed = "$name " }
                }
            }
        }
        help?.takeIf { it.isNotBlank() }?.let { text ->
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (sent.isNotEmpty()) {
            KlipperButtons {
                sent.take(RECENT_COMMANDS).forEach { command ->
                    KlipperButton(command) { send(command) }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("G-code") },
                singleLine = true,
                enabled = state.connected,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f),
            )
            KlipperButton(
                text = "Send",
                enabled = state.connected && typed.isNotBlank(),
                onClick = { send() },
            )
        }
    }
}

/** How many command names are offered while typing, and how many past commands kept. */
private const val SUGGESTIONS = 6

private const val RECENT_COMMANDS = 4
