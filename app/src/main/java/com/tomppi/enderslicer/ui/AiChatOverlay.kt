package com.tomppi.enderslicer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.cadagent.AgentQuestion
import com.tomppi.enderslicer.cadagent.AgentToolTrace
import com.tomppi.enderslicer.harness.HarnessClient
import kotlin.math.roundToInt

/** What one entry of a chat transcript is: prose, a tool row, or a plain-words activity line. */
enum class AiChatEntryKind { MESSAGE, TOOL, ACTIVITY, ERROR, QUESTION }

/** One turn in the AI conversation. */
data class AiChatMessage(
    val fromUser: Boolean,
    val text: String,
    val kind: AiChatEntryKind = AiChatEntryKind.MESSAGE,
    /** Non-null for a [AiChatEntryKind.TOOL] row: the call this row is showing. */
    val tool: AgentToolTrace? = null,
    /** Non-null for a [AiChatEntryKind.QUESTION] entry: the question card's content. */
    val question: AgentQuestion? = null,
)

/** Which backend the floating chat is showing. */
enum class ChatSource(val label: String) {
    /** The app's own CAD agent: the same conversation the CAD agent sheet's ask box drives. */
    IN_APP("CAD agent (in-app)"),

    /** The DSH harness on a computer - the advanced path, unchanged. */
    HARNESS("Harness"),
}

/**
 * The in-app agent as the floating chat sees it.
 *
 * The messages are [com.tomppi.enderslicer.ui.AiChatMessage]s of the one CAD conversation the
 * CAD agent sheet's ask box also writes - there is no second transcript. The tool rows are in
 * that same list, in the order they happened, so the chat shows the work as it runs.
 */
data class InAppChat(
    /** This backend's own conversation - never the harness session's. */
    val messages: List<AiChatMessage>,
    val busy: Boolean = false,
    /** The last failure, in the agent's own words (endpoint, key, engine). */
    val status: String? = null,
    /** "In-app - deepseek-chat at api.deepseek.com", or "In-app - not configured". */
    val backendLine: String = "",
    /** False until the endpoint has a URL and a model; the composer says so. */
    val configured: Boolean = true,
    val onSend: (String) -> Unit = {},
    /** The question the agent is waiting on, or null; the waiting card and composer follow it. */
    val question: AgentQuestion? = null,
    /** The user's answer (a chip or free text): clears the wait and starts the next turn. */
    val onAnswerQuestion: (String) -> Unit = {},
    /** Dismiss the waiting question; the agent's next message starts a fresh turn. */
    val onDismissQuestion: () -> Unit = {},
)

/**
 * Address entry, shown until the harness has been reached once.
 *
 * The chat asks for this itself rather than hiding it in a settings screen,
 * because the first thing anyone does with an unconfigured chat is try to use
 * it. The address field takes the whole URL the launcher prints, launch token
 * included: that token is the only credential the app gets, and the launcher no
 * longer publishes one anywhere a passer-by could read it.
 */
data class ChatSetup(
    val address: String,
    val onAddressChange: (String) -> Unit,
    val onConnect: () -> Unit,
    /** Harness-side working directory; decides which skills the agent can see. */
    val workspace: String = "",
    val onWorkspaceChange: (String) -> Unit = {},
)

private val ExpandedWidth = 320.dp
private val ExpandedHeight = 520.dp
private val BubbleSize = 56.dp

/** The full-screen transcript column's reading width; wider windows get margins, not a wall. */
private val FullScreenReadingWidth = 760.dp

/**
 * Floating AI chat, with a source switch and three sizes: the bubble, the panel and full screen.
 *
 * Deliberately not an AppBottomSheet. The user paints a region onto the model to
 * show the AI what to work on, so the chat has to stay open and stay out of the
 * way while they do it - a bottom sheet covers the model and dismisses on the
 * first tap outside it. This floats, drags anywhere, and collapses to a bubble
 * so the plate stays usable. Full screen is the same surface given the whole
 * viewport for reading a long transcript; the plate keeps running underneath and
 * the engine and the turn are untouched by a resize.
 *
 * **Two backends, one surface - and two conversations.** [ChatSource.IN_APP] renders the
 * in-app agent's own messages (a row for every tool it runs: name, arguments, duration,
 * outcome), and [ChatSource.HARNESS] renders the harness session's own messages. They are
 * separate lists owned by their backends; switching the source never shows the other one's
 * transcript, and each empty state says which backend is being looked at. The header names the
 * backend and its state, and the document's author, so an attached external agent reads as
 * "in-app agent paused" instead of a chat that quietly does nothing.
 *
 * The size itself lives with the caller ([size], [lastExpandedSize], [onSizeChange]) so it
 * survives the chat being closed and reopened in the same session, and so back and the header
 * buttons cannot disagree about what a tap does - both use [ChatSizes].
 *
 * Position is intentionally not persisted: a saved offset from a different
 * window size or orientation would land off-screen, so it re-seats itself.
 */
@Composable
fun AiChatOverlay(
    /** The harness session's own conversation - never the in-app agent's. */
    messages: List<AiChatMessage>,
    /** Sends to the harness session. */
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Stops the running turn of whichever backend is shown. */
    onStop: () -> Unit = {},
    /** Non-null until the harness address is known; its setup replaces the composer. */
    setup: ChatSetup? = null,
    /** True while a harness request is in flight, so the composer can say so. */
    busy: Boolean = false,
    /** Last harness transport outcome, shown under the conversation. */
    status: String? = null,
    /** True while the harness session travels over plain HTTP to another machine. */
    insecure: Boolean = false,
    /** The app's own CAD agent. Non-null enables the source switch and the in-app view. */
    inApp: InAppChat? = null,
    /**
     * The selected source, owned by the caller so the CAD agent sheet's ask box and this chat
     * cannot disagree about which backend a message goes to. Null lets the overlay remember its
     * own selection (tests, previews).
     */
    source: ChatSource? = null,
    /** Called when a source chip is tapped; the caller persists the shared selection. */
    onSourceChange: (ChatSource) -> Unit = {},
    /** Which source the chat opens on when [source] is null; in-app is the app's default. */
    initialSource: ChatSource = ChatSource.IN_APP,
    /** "Author: nobody" / "Author: in-app agent" / "Author: external agent on 9878". */
    authorLine: String = "",
    /** Non-null when another author has the document; the in-app composer is paused. */
    authorPausedReason: String? = null,
    /** "Harness - session connected" / "Harness - not connected". */
    harnessLine: String = "",
    /** Receives the shown backend's transcript text; the caller owns the clipboard. */
    onCopyTranscript: (String) -> Unit = {},
    /** The remembered size; the overlay only asks the caller to change it. */
    size: ChatSize = ChatSize.PANEL,
    /** The last non-bubble size, so tapping the bubble returns where the user was. */
    lastExpandedSize: ChatSize = ChatSize.PANEL,
    onSizeChange: (ChatSize) -> Unit = {},
    /**
     * What the system Back gesture asks for. The chat registers its own handler while it is
     * composed, so Back from full screen collapses instead of leaving the app or closing the
     * chat; the caller applies the action ([ChatSizes.back] decides which one).
     */
    onBack: (ChatBackAction) -> Unit = {},
) {
    // Back belongs to the chat while it is on screen: full screen collapses to the panel,
    // anything smaller closes it. Registered here, inside the chat, so it beats the screen's
    // own handlers and the two paths can never disagree.
    BackHandler { onBack(ChatSizes.back(size)) }

    var draft by rememberSaveable { mutableStateOf("") }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var placed by remember { mutableStateOf(false) }
    // The caller owns the selection (one shared selection with the CAD agent sheet); with no
    // caller-owned source the overlay remembers its own, which is what previews and tests use.
    var localSource by rememberSaveable { mutableStateOf(initialSource) }
    val shownSource = source ?: localSource
    val selectSource: (ChatSource) -> Unit = { wanted ->
        if (source == null) {
            localSource = wanted
        } else {
            onSourceChange(wanted)
        }
    }
    val density = LocalDensity.current
    val scroll = rememberScrollState()

    val showInApp = inApp != null && shownSource == ChatSource.IN_APP
    val app = if (showInApp) inApp else null
    // The harness state and the in-app state are separate: whichever source is not on screen
    // keeps its own messages untouched - switching source or size is not a new conversation.
    val activeBusy = if (showInApp) app?.busy == true else busy
    val activeMessages = if (showInApp) app?.messages.orEmpty() else messages
    // A source switch and every streamed tool event should land at the newest line. A size
    // change is deliberately not a key: scrolling while resizing keeps the user's position.
    LaunchedEffect(showInApp, activeMessages) { scroll.animateScrollTo(scroll.maxValue) }

    val expand: () -> Unit = { onSizeChange(ChatSizes.expanded()) }
    val collapse: () -> Unit = { onSizeChange(ChatSizes.collapsed()) }
    val minimise: () -> Unit = { onSizeChange(ChatSizes.minimised()) }
    val openBubble: () -> Unit = { onSizeChange(ChatSizes.fromBubble(lastExpandedSize)) }

    // --- the shared chrome: the panel and the full-screen surface render one layout ----------

    val sourceChips: @Composable () -> Unit = {
        if (inApp != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space6),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EnderSlicerDimens.CardPadding),
            ) {
                ChatSource.entries.forEach { candidate ->
                    FilterChip(
                        selected = shownSource == candidate,
                        onClick = { selectSource(candidate) },
                        label = { Text(candidate.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }

    // Which backend is on screen, and its own state: "In-app - model at host" or
    // "Harness - session connected". The switch alone does not say what it is connected to.
    val backendRow: @Composable () -> Unit = {
        val line = if (showInApp) app?.backendLine.orEmpty() else harnessLine
        if (line.isNotBlank()) {
            Text(
                text = line,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier.padding(
                    start = EnderSlicerDimens.CardPadding,
                    end = EnderSlicerDimens.CardPadding,
                    top = EnderSlicerDimens.Space4,
                ),
            )
        }
    }

    val authorRow: @Composable () -> Unit = {
        // The one-author rule, in the chat's own header: an external agent holding the document
        // must read as "in-app agent paused", never as a chat that is silently unable to send.
        // The author is the document's, not a backend's, so the line shows in both sources.
        if (authorLine.isNotBlank()) {
            Text(
                text = if (authorPausedReason != null && showInApp) {
                    authorLine + " · in-app agent paused"
                } else {
                    authorLine
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (authorPausedReason != null && showInApp) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(
                    start = EnderSlicerDimens.CardPadding,
                    end = EnderSlicerDimens.CardPadding,
                    top = EnderSlicerDimens.Space2,
                ),
            )
        }
    }

    val insecureNote: @Composable () -> Unit = {
        // Stays up for as long as the session does: the setup-time warning is gone the moment
        // the chat is in use, which is when every prompt and photo is actually sent.
        if (insecure && !showInApp) {
            Text(
                text = "Unencrypted connection: anyone on this network can read " +
                    "this chat and the credentials it uses.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = EnderSlicerDimens.CardPadding,
                        end = EnderSlicerDimens.CardPadding,
                        top = EnderSlicerDimens.Space4,
                    ),
            )
        }
    }

    val transcript: @Composable (Modifier) -> Unit = { transcriptModifier ->
        Column(
            verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
            modifier = transcriptModifier
                .verticalScroll(scroll)
                .padding(EnderSlicerDimens.CardPadding),
        ) {
            when {
                app != null -> {
                    if (app.messages.isEmpty()) {
                        Text(
                            text = "No in-app conversation yet - ask for a change. Every " +
                                "tool the in-app agent runs will show up here as a row.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    app.messages.forEach { message ->
                        ChatEntry(
                            message = message,
                            pendingQuestion = app.question,
                            onAnswerQuestion = app.onAnswerQuestion,
                            onDismissQuestion = app.onDismissQuestion,
                        )
                    }
                    if (app.busy) {
                        Text(
                            text = "Working…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (authorPausedReason != null) {
                        Text(
                            text = authorPausedReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    app.status?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                setup != null -> {
                    if (messages.isEmpty()) {
                        Text(
                            text = "No harness session - connect the harness from the " +
                                "Harness tab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "Paste the launch URL your harness printed - the " +
                            "https://your-machine.your-tailnet.ts.net address with " +
                            "?token=... on the end. It is the only copy of that token.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = setup.address,
                        onValueChange = setup.onAddressChange,
                        label = { Text("Launch URL") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { setup.onConnect() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Everything this chat sends - the launch token, the session cookie and any
                    // photo uploaded with a prompt - travels in the clear over plain HTTP to
                    // another machine, and the cookie is good for 30 days. The address is still
                    // accepted, because a LAN or Tailscale harness is a legitimate answer, but
                    // not without being asked.
                    if (HarnessClient.isUnencryptedRemote(setup.address)) {
                        Text(
                            "Not HTTPS: the launch token, the session cookie and " +
                                "anything you send would travel unencrypted. You will " +
                                "be asked to confirm before connecting.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedTextField(
                        value = setup.workspace,
                        onValueChange = setup.onWorkspaceChange,
                        label = { Text("Workspace on the harness") },
                        supportingText = {
                            Text("Where your skills live. Without it the agent " +
                                "cannot see them, however the prompt names them.")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = setup.onConnect,
                        enabled = setup.address.isNotBlank() && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "Connecting…" else "Connect") }
                    status?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                else -> {
                    if (messages.isEmpty()) {
                        Text(
                            text = "No harness session - connect the harness from the " +
                                "Harness tab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    messages.forEach { message -> AiChatBubble(message) }
                    if (busy) {
                        Text(
                            text = "Thinking…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Surfaced here as well as during setup: "still running after 20 minutes"
                    // and "stopped" are only useful if they are visible once the chat is in use.
                    status?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // A pending question changes what the composer does: a typed answer is the next user
    // message and clears the wait, and a closed option set closes the composer entirely.
    val pendingQuestion = if (showInApp) app?.question else null
    val answering = pendingQuestion != null
    val canTypeAnswer = !answering || pendingQuestion?.allowText == true
    val submitComposer: () -> Unit = {
        if (draft.isNotBlank()) {
            when {
                answering && pendingQuestion?.allowText == true -> {
                    app?.onAnswerQuestion?.invoke(draft.trim())
                    draft = ""
                }
                !answering && canSend(showInApp, app, activeBusy, authorPausedReason) -> {
                    sendDraft(showInApp, draft, app, onSend)
                    draft = ""
                }
            }
        }
    }
    val composerPlaceholder: String = when {
        !showInApp -> "Ask about the model"
        answering && canTypeAnswer -> "Type your answer"
        answering -> "Pick an option above"
        else -> "Ask for a CAD change"
    }

    val composer: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = EnderSlicerDimens.Space8,
                    end = EnderSlicerDimens.Space8,
                    top = EnderSlicerDimens.Space8,
                ),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(composerPlaceholder) },
                enabled = canTypeAnswer,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submitComposer() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(EnderSlicerDimens.Space4))
            FilledIconButton(
                onClick = submitComposer,
                enabled = draft.isNotBlank() &&
                    (if (answering) canTypeAnswer else canSend(showInApp, app, activeBusy, authorPausedReason)),
            ) {
                Icon(AppIcons.Send, contentDescription = "Send")
            }
        }
    }

    val footer: @Composable (Modifier) -> Unit = { outerModifier ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = outerModifier
                .fillMaxWidth()
                .padding(
                    start = EnderSlicerDimens.Space8,
                    end = EnderSlicerDimens.Space8,
                    bottom = EnderSlicerDimens.Space8,
                ),
        ) {
            // The shown backend's transcript, tool rows included for the in-app agent.
            TextButton(
                onClick = { onCopyTranscript(chatTranscriptText(activeMessages)) },
                enabled = activeMessages.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
            ) { Text("Copy transcript") }
            if (showInApp && app?.configured == false) {
                Text(
                    text = "Set the endpoint in the CAD agent sheet.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Only while something is running. Stops this backend's turn, not the engine: the
            // caller passes the same cancel the CAD agent sheet's Cancel uses.
            if (activeBusy) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onStop,
                    contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                ) { Text("Stop") }
            }
        }
    }

    // --- full screen: one surface over the plate, readable column on a wide window -----------

    if (size == ChatSize.FULL) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = FullScreenReadingWidth)
                        .fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            // The full-screen surface draws behind the status bar, so its own
                            // top bar starts below it instead of under the clock.
                            .statusBarsPadding()
                            .padding(
                                start = EnderSlicerDimens.CardPadding,
                                end = EnderSlicerDimens.Space4,
                                top = EnderSlicerDimens.Space6,
                                bottom = EnderSlicerDimens.Space6,
                            ),
                    ) {
                        Icon(
                            imageVector = AppIcons.Sparkle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(EnderSlicerDimens.Space8))
                        Text(
                            text = "Ask AI",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = collapse) {
                            Icon(
                                imageVector = AppIcons.Collapse,
                                contentDescription = "Collapse chat",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = minimise) {
                            Icon(
                                imageVector = AppIcons.Minimise,
                                contentDescription = "Hide chat",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onClose) { Text("Close") }
                    }
                    sourceChips()
                    backendRow()
                    authorRow()
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    insecureNote()
                    transcript(Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (showInApp || setup == null) {
                        composer()
                        // Pinned to the bottom and clear of the system navigation area.
                        footer(Modifier.navigationBarsPadding())
                    }
                }
            }
        }
        return
    }

    // --- the bubble and the floating panel ---------------------------------------------------

    BoxWithConstraints(modifier = modifier) {
        val panelWidth = if (size == ChatSize.BUBBLE) BubbleSize else minOf(ExpandedWidth, maxWidth * 0.94f)
        val panelHeight = if (size == ChatSize.BUBBLE) BubbleSize else minOf(ExpandedHeight, maxHeight * 0.92f)
        val widthPx = with(density) { panelWidth.toPx() }
        val heightPx = with(density) { panelHeight.toPx() }
        val boundsX = (constraints.maxWidth - widthPx).coerceAtLeast(0f)
        val boundsY = (constraints.maxHeight - heightPx).coerceAtLeast(0f)

        fun clamp(candidate: Offset) = Offset(
            candidate.x.coerceIn(0f, boundsX),
            candidate.y.coerceIn(0f, boundsY),
        )

        // Seat it right of centre, clear of the top bar, once the bounds are known.
        if (!placed && boundsX > 0f) {
            LaunchedEffect(boundsX, boundsY) {
                offset = Offset(boundsX, boundsY * 0.25f)
                placed = true
            }
        }
        // A rotation can leave a stale offset outside the new bounds.
        LaunchedEffect(boundsX, boundsY) { offset = clamp(offset) }

        val dragHandle = Modifier.pointerInput(boundsX, boundsY) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                offset = clamp(offset + dragAmount)
            }
        }

        if (size == ChatSize.BUBBLE) {
            Surface(
                onClick = openBubble,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                    .size(BubbleSize)
                    .then(dragHandle),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = AppIcons.Sparkle,
                        contentDescription = "Open AI chat",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            return@BoxWithConstraints
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .width(panelWidth)
                .height(panelHeight),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header doubles as the drag handle.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(dragHandle)
                        .padding(
                            start = EnderSlicerDimens.CardPadding,
                            end = EnderSlicerDimens.Space4,
                            top = EnderSlicerDimens.Space6,
                            bottom = EnderSlicerDimens.Space6,
                        ),
                ) {
                    Icon(
                        imageVector = AppIcons.Sparkle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(EnderSlicerDimens.Space8))
                    Text(
                        text = "Ask AI",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = expand) {
                        Icon(
                            imageVector = AppIcons.Expand,
                            contentDescription = "Expand chat",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = minimise) {
                        Icon(
                            imageVector = AppIcons.Minimise,
                            contentDescription = "Hide chat",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                sourceChips()
                backendRow()
                authorRow()
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                insecureNote()
                transcript(Modifier.weight(1f).fillMaxWidth())
                // The harness setup keeps the old behaviour: it replaces the composer until the
                // harness has been reached once. The in-app view always has a composer.
                if (!showInApp && setup != null) return@Column
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                composer()
                footer(Modifier)
            }
        }
    }
}

/** Whether the composer may send right now, for either backend. */
private fun canSend(showInApp: Boolean, app: InAppChat?, busy: Boolean, paused: String?): Boolean {
    if (busy) return false
    if (!showInApp) return true
    return app != null && app.configured && paused == null
}

private fun sendDraft(showInApp: Boolean, text: String, app: InAppChat?, onSendHarness: (String) -> Unit) {
    if (showInApp) app?.onSend?.invoke(text.trim()) else onSendHarness(text.trim())
}

/** One transcript entry: a prose bubble, a tool row, or the agent's question card. */
@Composable
private fun ChatEntry(
    message: AiChatMessage,
    pendingQuestion: AgentQuestion? = null,
    onAnswerQuestion: (String) -> Unit = {},
    onDismissQuestion: () -> Unit = {},
) {
    val tool = message.tool
    if (tool != null) {
        AiChatToolRow(tool)
        return
    }
    val question = message.question
    if (question != null) {
        // Waiting is per question instance, not per position: an older answered card must not
        // look live just because a later question happens to have the same words.
        AiQuestionCard(
            question = question,
            waiting = question === pendingQuestion,
            onAnswer = onAnswerQuestion,
            onDismiss = onDismissQuestion,
        )
        return
    }
    // The plain-words activity lines exist for the sheet, which has no tool rows; the chat's
    // rows already say what is happening, so repeating it as a bubble is noise here.
    if (message.kind == AiChatEntryKind.ACTIVITY) return
    // A turn that failed says so in the transcript itself - endpoint host, round number and
    // what to do - so a copy of the conversation is diagnosable without a logcat.
    if (message.kind == AiChatEntryKind.ERROR) {
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(EnderSlicerDimens.Space8),
        )
        return
    }
    AiChatBubble(message)
}

/**
 * The agent's question, as a card of its own rather than a normal agent bubble.
 *
 * While it is the pending question the card says the agent is waiting and offers its options as
 * one-tap chips; a free-text answer goes through the chat's own composer. Once answered (or
 * dismissed) the card stays in the transcript as history and says `Answered.` - it is never
 * live again.
 */
@Composable
private fun AiQuestionCard(
    question: AgentQuestion,
    waiting: Boolean,
    onAnswer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(EnderSlicerDimens.Space12),
            verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
        ) {
            Text(
                text = if (waiting) "The agent asks" else "The agent asked",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = question.question,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (question.options.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
                ) {
                    question.options.forEach { option ->
                        TextButton(
                            onClick = { if (waiting) onAnswer(option) },
                            enabled = waiting,
                            contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space12),
                        ) { Text(option) }
                    }
                }
            }
            if (waiting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "The agent is waiting on you.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                    ) { Text("Dismiss") }
                }
            } else {
                Text(
                    text = if (question.allowText) "Answered." else "Answered from the options.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/**
 * One tool call as a row: what the agent ran, on what, for how long, and how it went.
 *
 * A failure is a red row carrying the engine's or the endpoint's own sentence - the whole point
 * of showing the work is that a bad turn is visible, not silent.
 */
@Composable
private fun AiChatToolRow(trace: AgentToolTrace) {
    val failed = trace.failed
    val container = if (failed) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val onContainer = if (failed) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(EnderSlicerDimens.Space8)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = trace.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (trace.running) "running…" else trace.durationMillis.toString() + " ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainer,
                )
            }
            if (trace.arguments.isNotBlank()) {
                Text(
                    text = trace.arguments,
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainer,
                    maxLines = 2,
                )
            }
            if (!trace.running && trace.outcome.isNotBlank()) {
                Text(
                    text = trace.outcome,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun AiChatBubble(message: AiChatMessage) {
    val alignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    val container = if (message.fromUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = if (message.fromUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = container,
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                color = onContainer,
                modifier = Modifier.padding(EnderSlicerDimens.Space8),
            )
        }
    }
}
