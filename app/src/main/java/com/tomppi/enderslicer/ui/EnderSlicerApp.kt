package com.tomppi.enderslicer.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScope
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tomppi.enderslicer.BuildConfig
import com.tomppi.enderslicer.annotation.AnnotationAnchor
import com.tomppi.enderslicer.annotation.AnnotationGesture
import com.tomppi.enderslicer.annotation.AnnotationKind
import com.tomppi.enderslicer.annotation.AnnotationState
import com.tomppi.enderslicer.annotation.SegmentEnd
import com.tomppi.enderslicer.conical.ConicalSettingsStore
import com.tomppi.enderslicer.engine.GcodeDialect
import com.tomppi.enderslicer.harness.HarnessChat
import com.tomppi.enderslicer.harness.HarnessClient
import com.tomppi.enderslicer.harness.HarnessConfig
import com.tomppi.enderslicer.harness.HarnessConfigStore
import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.AllSettingsCatalogs
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.model.withSettings
import com.tomppi.enderslicer.modelling.CameraOwner
import com.tomppi.enderslicer.modelling.ModellingCamera
import com.tomppi.enderslicer.modelling.ModellingCameraStore
import com.tomppi.enderslicer.nonplanar.NonPlanarSettingsStore
import com.tomppi.enderslicer.supportpaint.SupportPaintMode
import com.tomppi.enderslicer.texturizer.BumpMeshActivity
import com.tomppi.enderslicer.viewer.MeshPicker
import com.tomppi.enderslicer.viewer.ModelSurfaceView
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.ViewerOrientation
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.material.icons.filled.Lock
import com.tomppi.enderslicer.nativebridge.BlenderEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private enum class ViewerMode { MODEL, LAYERS, NOZZLE_PATH }

private val ViewerOrientationSaver = listSaver<ViewerOrientation?, Float>(
    save = { orientation ->
        if (orientation == null) emptyList() else listOf(orientation.yawDegrees, orientation.pitchDegrees)
    },
    restore = { saved ->
        if (saved.size < 2) null else ViewerOrientation(saved[0], saved[1])
    },
)

/** The four persistent destinations. See docs/ux-redesign/DESIGN_PROPOSAL.md. */
internal enum class AppTab(
    val label: String,
    /** What the rail and the bottom bar call it, where there is no room to spare. */
    val shortLabel: String,
    val subtitleFor: (MainUiState) -> String,
) {
    PLATE("Plate", "Plate", { state -> state.mesh?.displayName ?: "No model yet" }),
    SETTINGS("Print settings", "Settings", { "Apply immediately" }),
    PRINT("Print", "Print", { "OctoPrint session" }),
    MORE("More", "More", { "Everything outside the plate" }),
}

/** The glyph a destination is drawn with. */
internal val AppTab.icon: ImageVector
    get() = when (this) {
        AppTab.PLATE -> AppIcons.Plate
        AppTab.SETTINGS -> AppIcons.Settings
        AppTab.PRINT -> AppIcons.Print
        AppTab.MORE -> AppIcons.More
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnderSlicerApp(
    viewModel: MainViewModel = viewModel(),
    engine: SlicerEngine = SlicerEngine.CURA,
    onEngineChange: (SlicerEngine) -> Unit = {},
    sliceBlockedReason: String? = null,
    plateOverflowItems: @Composable (() -> Unit) -> Unit = { _ -> },
    moreExtraItems: @Composable () -> Unit = {},
    printTabContent: @Composable () -> Unit = {},
    plateOverlayContent: @Composable BoxScope.() -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.PLATE) }
    var importMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var plateOverflowExpanded by rememberSaveable { mutableStateOf(false) }
    var profilesOpen by rememberSaveable { mutableStateOf(false) }
    var printerScreenOpen by rememberSaveable { mutableStateOf(false) }
    val printerChecklistStore = remember(context) { PrinterChecklistStore(context.applicationContext) }
    var printerChecklistDone by remember(printerChecklistStore) { mutableStateOf(printerChecklistStore.load()) }
    var modelToolsOpen by rememberSaveable { mutableStateOf(false) }
    // Dragging the model across the plate is a mode on the viewer, held here
    // because the Transform panel that turns it on is not the plate itself.
    var modelDragMove by rememberSaveable { mutableStateOf(false) }
    var supportPaintUiOpen by rememberSaveable { mutableStateOf(false) }
    var annotationUiOpen by rememberSaveable { mutableStateOf(false) }
    // Hoisted out of the layout branches: the gesture help folds away for good
    // once dismissed, and a flag remembered inside a branch starts over when the
    // device folds or unfolds and that branch leaves the composition.
    var modelHintsDismissed by rememberSaveable { mutableStateOf(false) }
    var layerEventsOpen by rememberSaveable { mutableStateOf(false) }
    var meshLimitOpen by rememberSaveable { mutableStateOf(false) }
    var nonPlanarOpen by rememberSaveable { mutableStateOf(false) }
    var allSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var conicalOpen by rememberSaveable { mutableStateOf(false) }
    var blenderMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var blenderFilesOpen by rememberSaveable { mutableStateOf(false) }
    var aiChatOpen by rememberSaveable { mutableStateOf(false) }
    // The floating Plate cards fold independently: one saveable key per card, so
    // each choice survives rotation and is still there after a tab switch. The
    // flags live here rather than inside the cards because selecting another tab
    // disposes the whole Plate subtree, which would take a rememberSaveable in a
    // card down with it.
    var printerCardExpanded by rememberSaveable(key = "plate-printer-card-expanded") { mutableStateOf(false) }
    // Folded by default now: the Plate opens on the model, and the banner is
    // one tap away. The key is new because the old one remembered the old
    // default, and a restored "expanded" would win over this one.
    var noticeCardExpanded by rememberSaveable(key = "plate-notice-card-expanded-v2") {
        mutableStateOf(false)
    }
    // The phone bottom action block folds the same way and for the same
    // reason: it is part of the Plate subtree, so its flag belongs here too.
    var plateActionBarExpanded by rememberSaveable(key = "plate-action-bar-expanded") { mutableStateOf(true) }
    // Conversation is session-scoped on purpose: the harness owns the real
    // transcript, and a half-sent turn must not survive a process restart.
    var aiMessages by remember { mutableStateOf(listOf<AiChatMessage>()) }
    var aiStatus by remember { mutableStateOf<String?>(null) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiAddress by remember { mutableStateOf("") }
    var aiWorkspace by remember { mutableStateOf("") }
    var aiConfigured by remember { mutableStateOf(false) }
    // True while the connected harness is reached over plain HTTP to another
    // machine, so the panel can keep saying so rather than only warning once.
    var aiInsecure by remember { mutableStateOf(false) }
    // Address waiting for the user to accept an unencrypted connection.
    var aiCleartextPrompt by remember { mutableStateOf<String?>(null) }

    // Modelling from scratch is its own conversation with its own agent: it
    // shares the harness, the Blender engine and nothing else with the
    // image-to-model chat, whose transcript is about somebody's photograph.
    var modellingOpen by rememberSaveable { mutableStateOf(false) }
    var modellingMessages by remember { mutableStateOf(listOf<AiChatMessage>()) }
    var modellingStatus by remember { mutableStateOf<String?>(null) }
    var modellingBusy by remember { mutableStateOf(false) }
    var modellingOwner by remember { mutableStateOf(CameraOwner.AGENT) }
    var modellingCamera by remember { mutableStateOf<ModellingCamera?>(null) }
    var modellingBootstrapped by remember { mutableStateOf(false) }
    // The standing brief is owed from the first visit to the modelling screen,
    // but the connection that screen starts in the same frame is asynchronous:
    // the brief waits here until a chat exists to carry it.
    var modellingIntroPending by remember { mutableStateOf(false) }
    // True once a modelling chat has been adopted. Read only to know when the
    // deferred brief can be delivered.
    var modellingChatReady by remember { mutableStateOf(false) }
    val modellingChat = remember { java.util.concurrent.atomic.AtomicReference<HarnessChat?>(null) }
    // Revision this app last wrote or read. The file is the handover point, so
    // writes bump it and an adoption takes the agent's value verbatim.
    var modellingCameraRev by remember { mutableStateOf(0L) }
    // Twenty minutes. Generating a model means waking a machine, running a
    // diffusion model and delivering a file, so a two-minute window gave up
    // long before the work finished and reported it as a failure.
    val harnessReplyPolls = 600
    val harnessPollMs = 2_000L
    // A queued turn is briefly neither running nor answered, so "not running"
    // only means "ended without a reply" once it has held still for a while.
    // Twenty seconds is comfortably longer than the queue latency seen against
    // the live harness and far shorter than the twenty-minute poll ceiling.
    val harnessSettlePolls = 10
    // Spells out the order rather than just the goal, because the failure modes
    // are ordering failures: generating before the box is awake looks like a
    // hang, and hibernating early loses the model.
    val imageModelPrompt = buildString {
        append("Build a 3D model from the image I just attached. ")
        append("Use the image-to-3d-model skill, in its documented order: ")
        append("wake the GPU box first (gpu-box-power), generate with ")
        append("Hunyuan3D-2mini using DMC, post-process to a printable scale, ")
        append("validate that it is watertight, deliver the STL into the app's ")
        append("blender/exports folder with a fresh unique filename so the ")
        append("hot-load picks it up, confirm it landed, then hibernate the box.")
    }
    val harnessStore = remember(context) { HarnessConfigStore(context.applicationContext) }
    val harnessChat = remember { java.util.concurrent.atomic.AtomicReference<HarnessChat?>(null) }
    val aiScope = rememberCoroutineScope()
    // Camera-file writes: their own scope and a single-threaded dispatcher, so a
    // gesture's burst of publishes cannot interleave inside write-and-rename.
    val cameraScope = rememberCoroutineScope()
    val cameraWriteLock = remember { Mutex() }
    val cameraWrittenRev = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    var modellingCameraTouchedAt by remember { mutableStateOf(0L) }

    // Adopt a stored address on first composition, so an install that was
    // configured once comes up ready rather than asking again.
    LaunchedEffect(Unit) {
        val stored = harnessStore.load()
        aiAddress = stored.baseUrl
        aiWorkspace = stored.workspace
        aiConfigured = stored.isConfigured
    }

    /**
     * One round trip: prompt, then poll the projection until the turn is
     * answered. The harness acknowledges a prompt without carrying the reply,
     * so the answer is read back from the session list.
     */
    // Shared by the text and image paths: the harness acknowledges a prompt
    // without carrying the reply, so the answer is read back from the session
    // projection until the newest turn has one.
    suspend fun pollForReply(
        chat: HarnessChat,
        publish: (List<AiChatMessage>) -> Unit,
        report: (String?) -> Unit,
        /** True when picking a turn up again that was already running. */
        resumed: Boolean = false,
    ) {
        var attempts = 0
        var idle = 0
        while (attempts < harnessReplyPolls) {
            delay(harnessPollMs)
            val state = withContext(Dispatchers.IO) { chat.state() }
            if (!state.exists) {
                // Blanking the conversation on a failed lookup would erase a
                // chat that is working perfectly well, so this only reports.
                report("Session not in the harness list — reconnecting may help")
            } else {
                // Always the log, never the projection. The projection clips
                // every turn to about a hundred characters, so falling back to
                // it while a turn ran collapsed the *entire* transcript to
                // ellipses the moment you sent anything - past answers included,
                // which had not changed and had no reason to be re-read as
                // previews. The page is a little larger; it is one request every
                // two seconds and it is worth it.
                val shown = withContext(Dispatchers.IO) { chat.messages() }
                publish(shown.map { AiChatMessage(fromUser = it.fromUser, text = it.text) })
                if (chat.replyIsIn(state)) {
                    report(null)
                    return
                }
                // "Not running" is only meaningful once the new turn has been
                // published. Before that the session may simply not have picked
                // the prompt up yet - except for a resumed poll, which has
                // already seen the turn running, so the projection has published
                // it and "not running" immediately means "ended".
                val ended = if (resumed) !state.running else !state.running && chat.hasNewTurn(state)
                if (!ended) {
                    idle = 0
                } else if (++idle >= harnessSettlePolls) {
                    // Idle and never answered: the turn ended without a reply.
                    // A cancelled or failed turn looks exactly like this, and
                    // waiting for a response that will never arrive is what
                    // kept the composer busy until its own timeout.
                    report("No reply — the turn ended")
                    return
                }
            }
            attempts++
        }
        report("Still running after 20 minutes — open the chat again to check")
    }

    /**
     * Picks an in-flight turn up again after the activity was recreated.
     *
     * Rotation cancels the poll but not the turn: the harness carries on working,
     * and nothing else ever reads that reply back, so the answer was lost and the
     * next message started a second generation. Only a turn the harness still
     * reports as running is resumed - picking up anything else would sit on a
     * finished or failed session for the whole twenty-minute poll.
     */
    fun resumeReplyIfRunning(
        chat: HarnessChat,
        publish: (List<AiChatMessage>) -> Unit,
        report: (String?) -> Unit,
        setBusy: (Boolean) -> Unit,
    ) {
        aiScope.launch {
            val state = withContext(Dispatchers.IO) { chat.state() }
            if (!state.exists || !state.running) return@launch
            setBusy(true)
            try {
                pollForReply(chat, publish, report, resumed = true)
            } finally {
                setBusy(false)
            }
        }
    }

    fun askHarness(text: String) {
        aiScope.launch {
            aiBusy = true
            aiStatus = null
            try {
                val chat = harnessChat.get() ?: error("Connect to the harness first")
                withContext(Dispatchers.IO) { chat.send(text) }
                aiMessages = aiMessages + AiChatMessage(fromUser = true, text = text)
                pollForReply(chat, { aiMessages = it }, { aiStatus = it })
            } catch (error: Throwable) {
                aiStatus = error.message?.take(160) ?: "Harness call failed"
            } finally {
                aiBusy = false
            }
        }
    }

    /**
     * Uploads the picked image, then asks the harness to model it.
     *
     * Upload first, prompt second: the prompt refers to the staged attachment,
     * so sending it the other way round would ask about an image the harness
     * does not have yet.
     *
     * Nothing is read back. The model arrives through the export folder like
     * any other export, so the app never has to poll for a file.
     */
    fun sendImageToHarness(uri: Uri) {
        aiScope.launch {
            aiBusy = true
            aiStatus = null
            try {
                val chat = harnessChat.get() ?: error("Connect to the harness first")
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not read that image")
                }
                val name = "model-source-" + System.currentTimeMillis() + ".jpg"
                withContext(Dispatchers.IO) {
                    val id = chat.ensureSession()
                    // Upload first, prompt second, and carry the receipt into
                    // the prompt: staging the bytes is not the same as sending
                    // them, and without the receipt block the agent receives a
                    // text-only message.
                    val receipt = chat.attach(id, name, bytes)
                    chat.send(imageModelPrompt, receipt)
                }
                aiMessages = aiMessages + AiChatMessage(
                    fromUser = true,
                    text = "Build a 3D model from this image",
                )
                pollForReply(chat, { aiMessages = it }, { aiStatus = it })
            } catch (error: Throwable) {
                aiStatus = error.message?.take(200) ?: "Image upload failed"
            } finally {
                aiBusy = false
            }
        }
    }

    /**
     * Connects to the harness at [aiAddress] and adopts its session.
     *
     * @param cleartextAccepted true only from the confirmation dialog for an
     *   address that would carry the session in the clear.
     */
    fun connectHarness(cleartextAccepted: Boolean = false) {
        // Guarded before the launch rather than inside it: tapping Connect twice
        // used to run two connect attempts against the same stored id, and an
        // empty stored id made each of them create its own session.
        if (aiBusy) return
        val parsed = HarnessConfig.parseLaunchUrl(aiAddress)
        val stored = harnessStore.load()
        val accepted = if (cleartextAccepted) parsed.copy(allowCleartext = true) else parsed
        // A cleared address field must not erase the stored one, the same way
        // mergedWith refuses to: an empty base URL cannot authenticate, and the
        // token that belongs to the stored address would be spent against
        // nothing.
        val target = stored.mergedWith(accepted, aiWorkspace.trim(), stored.sessionId)
        if (HarnessClient.isUnencryptedRemote(target.baseUrl) && !target.allowCleartext) {
            // Plain HTTP to another machine puts the launch token and the 30-day
            // session cookie on a channel anyone on the network can read. Asked
            // once per address, and only when the check above cannot clear it.
            aiCleartextPrompt = target.baseUrl
            return
        }
        aiBusy = true
        aiScope.launch {
            aiStatus = null
            try {
                val client = HarnessClient(
                    baseUrl = target.baseUrl,
                    launchToken = target.launchToken,
                    allowCleartext = target.allowCleartext,
                    // The cookie outlives a harness restart; the launch token does
                    // not. Reusing it is what keeps a restart from costing the
                    // user another paste.
                    initialCookie = harnessStore.loadCookie(target.baseUrl),
                )
                val authenticated = withContext(Dispatchers.IO) { client.authenticate() }
                harnessStore.saveCookie(target.baseUrl, authenticated)
                // A freshly installed app cannot know which directory on the harness host
                // its sessions belong in, so the launcher publishes one in the served
                // dist (defaults.json - a path, no credential). What the user typed still
                // wins, and the field is filled in so the choice is visible rather than
                // implied.
                val suggested = withContext(Dispatchers.IO) { client.suggestedWorkspace() }
                val workspace = aiWorkspace.trim().ifBlank { suggested }
                if (workspace.isNotEmpty() && workspace != aiWorkspace) aiWorkspace = workspace
                // Rooted where the skills are. A session created without a
                // workspace lands in the harness process's own directory, where
                // the prompt's skill names resolve to nothing.
                val chat = HarnessChat(client, workspace)
                val adopted = withContext(Dispatchers.IO) { chat.connect(stored.sessionId) }
                harnessChat.set(chat)
                // A second conversation with its own session id, so modelling
                // never inherits the image-to-model transcript.
                val modelling = HarnessChat(client, workspace)
                val adoptedModelling = withContext(Dispatchers.IO) {
                    modelling.connect(harnessStore.loadModellingSession())
                }
                modellingChat.set(modelling)
                // A modelling chat exists now, so the brief a first visit owed
                // has somewhere to go.
                modellingChatReady = true
                harnessStore.saveModellingSession(adoptedModelling.sessionId)
                modellingMessages = withContext(Dispatchers.IO) { modelling.messages() }
                    .map { AiChatMessage(fromUser = it.fromUser, text = it.text) }
                // Merged over what was stored, never over what was typed: the address
                // field is a bare URL here, so a blank one must not erase the stored
                // address, workspace or session ids.
                harnessStore.save(stored.mergedWith(accepted, workspace, adopted.sessionId))
                aiConfigured = true
                aiInsecure = HarnessClient.isUnencryptedRemote(target.baseUrl)
                // The field keeps the address, not the credential: the token is
                // stored encrypted now, and a bearer credential left in a text
                // field is one paste away from somewhere it does not belong.
                aiAddress = target.baseUrl
                aiMessages = withContext(Dispatchers.IO) { chat.messages() }.map {
                    AiChatMessage(fromUser = it.fromUser, text = it.text)
                }
                // A turn that outlived the activity is still running on the
                // harness, and nothing else reads its reply back: pick both
                // conversations up again instead of losing the answers to a
                // rotation and starting a second generation on the next send.
                resumeReplyIfRunning(chat, { aiMessages = it }, { aiStatus = it }) { aiBusy = it }
                resumeReplyIfRunning(
                    modelling,
                    { modellingMessages = it },
                    { modellingStatus = it },
                ) { modellingBusy = it }
                aiStatus = when {
                    !adopted.reused -> "Started a new conversation"
                    workspace.isBlank() -> "No workspace set — the agent cannot see your skills"
                    else -> null
                }
            } catch (error: Throwable) {
                aiStatus = error.message?.take(200) ?: "Could not reach the harness"
                aiConfigured = false
                aiInsecure = false
            } finally {
                aiBusy = false
            }
        }
    }

    /**
     * Stops the conversation.
     *
     * Cancels the turn and then tells the agent to drop the work that cancel
     * does not reach: a background job survives it, and its completion notice
     * starts a fresh turn on its own.
     */
    fun stopHarness() {
        val chat = harnessChat.get() ?: return
        aiScope.launch {
            try {
                withContext(Dispatchers.IO) { chat.stop() }
                aiStatus = "Stopped"
            } catch (error: Throwable) {
                aiStatus = error.message?.take(160) ?: "Could not stop the session"
            } finally {
                // The poll loop may be waiting on a turn that will now never
                // answer, so the composer has to be released here too.
                aiBusy = false
            }
        }
    }

    // ------------------------------------------------------------------
    // Modelling from scratch
    // ------------------------------------------------------------------

    /** Handoff directory shared with the engine, and where the camera lives. */
    val blenderDir = java.io.File(context.filesDir, "blender")

    /**
     * The standing brief for the modelling agent.
     *
     * Spelled out because the failure mode is an ordering failure again: the
     * camera file has to be read *before* the render, or the agent renders a
     * view the user is not looking at and the two drift apart inside one turn.
     */
    val modellingIntroPrompt = buildString {
        append("We are modelling a part from scratch in the embedded Blender engine. ")
        append("If the engine still holds its default scene, export that default cube to ")
        append("files/blender/exports/ under a fresh unique filename so it hot-loads and I can see it. ")
        append("Before every render, read files/blender/camera.json in the engine's files/blender ")
        append("directory: it carries the camera we share (target, eye, up, fov). Place the render ")
        append("camera from those values so your render and my view agree. Its \"owner\" field says ")
        append("who has the camera - while it says user I am looking at something and you must not ")
        append("move it. Render with BLENDER_WORKBENCH for geometry checks - about 10 ms a view - ")
        append("and CYCLES with cycles.device = 'CPU' when materials or lighting matter. Both work ")
        append("in this engine build. Look at your work with renders as you go, and export to ")
        append("files/blender/exports/ whenever there is something worth looking at.")
    }

    /**
     * Writes one camera revision through the serialised, rev-guarded path.
     *
     * Every writer of the file has to go through here. A caller that wrote it
     * directly - as [setModellingOwner] used to - could land on disk after a newer
     * revision a gesture had already queued, leaving the agent reading an owner or
     * a camera the user has moved away from; the lock and the revision guard only
     * mean anything when they cover all of the writers.
     */
    fun writeModellingCamera(camera: ModellingCamera) {
        cameraScope.launch(Dispatchers.IO) {
            cameraWriteLock.withLock {
                // The lock serialises the writes but not their order: a burst can
                // reach a multi-threaded dispatcher out of order, and an older camera
                // written last leaves the agent reading a stale one.
                if (camera.rev >= cameraWrittenRev.get()) {
                    ModellingCameraStore.write(blenderDir, camera)
                    cameraWrittenRev.set(camera.rev)
                }
            }
        }
    }

    /**
     * Moves the camera between the two owners.
     *
     * The file is the handover, not just a note: the agent reads `owner` to
     * decide whether it may move the camera, so taking it has to be published -
     * through the same path as a gesture, or a queued gesture write can land after
     * it and leave the agent holding the previous owner.
     */
    fun setModellingOwner(owner: CameraOwner) {
        modellingOwner = owner
        val current = modellingCamera ?: return
        val next = current.copy(owner = owner, rev = modellingCameraRev + 1)
        modellingCameraRev = next.rev
        modellingCamera = next
        writeModellingCamera(next)
    }

    fun askModellingAgent(text: String) {
        aiScope.launch {
            // Sending hands the camera back: the user has said what to look at,
            // so the agent has to be free to move in order to look at it.
            setModellingOwner(CameraOwner.AGENT)
            modellingBusy = true
            modellingStatus = null
            try {
                val chat = modellingChat.get() ?: error("Connect to the harness first")
                withContext(Dispatchers.IO) { chat.send(text) }
                modellingMessages = modellingMessages + AiChatMessage(fromUser = true, text = text)
                pollForReply(chat, { modellingMessages = it }, { modellingStatus = it })
            } catch (error: Throwable) {
                modellingStatus = error.message?.take(160) ?: "Harness call failed"
            } finally {
                modellingBusy = false
            }
        }
    }

    /** Publishes the camera the user is looking through, so the agent can adopt it. */
    fun publishModellingCamera(camera: ModellingCamera) {
        // The agent writes this file too and bumps the same counter, so an
        // unchanged camera must not burn a revision number the agent is about to
        // use - with two writers on one counter, one side's camera is silently
        // ignored by the other.
        val current = modellingCamera
        if (current != null &&
            current.owner == modellingOwner &&
            kotlin.math.abs(current.yawDeg - camera.yawDeg) < 0.01f &&
            kotlin.math.abs(current.pitchDeg - camera.pitchDeg) < 0.01f &&
            kotlin.math.abs(current.distanceMm - camera.distanceMm) < 0.01f &&
            kotlin.math.abs(current.targetX - camera.targetX) < 0.01f &&
            kotlin.math.abs(current.targetY - camera.targetY) < 0.01f &&
            kotlin.math.abs(current.targetZ - camera.targetZ) < 0.01f
        ) {
            return
        }
        val next = camera.copy(owner = modellingOwner, rev = modellingCameraRev + 1)
        modellingCameraRev = next.rev
        modellingCamera = next
        // Written off the UI thread and serialised on one: this is called from the
        // gesture loop, so the file write and rename used to run on the main thread
        // several times per frame during every orbit, pan and pinch.
        modellingCameraTouchedAt = System.currentTimeMillis()
        writeModellingCamera(next)
    }

    fun openModelling() {
        modellingOpen = true
        if (modellingChat.get() == null) {
            if (aiConfigured) {
                connectHarness()
            } else {
                // There is no connect button on this screen, so the way to make
                // it work has to be said here.
                modellingStatus = "The harness is not set up yet - connect it in the AI chat " +
                    "(Blender menu, Ask AI) first"
            }
        }
        // The engine starts on its default scene, so a first visit has nothing
        // to show until the agent exports the cube it already has. The brief is
        // owed rather than sent: connecting is asynchronous, so asking in this
        // frame handed the prompt to a chat that did not exist yet, and the agent
        // never learned the camera, Workbench and export contract.
        if (!modellingBootstrapped && state.mesh == null) {
            modellingBootstrapped = true
            modellingIntroPending = true
        }
    }

    // The agent writes the same camera file. Poll it while the modelling screen
    // is open so its moves land here too - that is what makes the camera shared
    // rather than one-way.
    LaunchedEffect(modellingOpen) {
        if (!modellingOpen) return@LaunchedEffect
        while (true) {
            delay(700)
            val onDisk = withContext(Dispatchers.IO) { ModellingCameraStore.read(blenderDir) }
                ?: continue
            if (onDisk.rev <= modellingCameraRev) continue
            // Never take the camera out of a hand that is holding it. While the user
            // is mid-gesture our own publishes are still arriving; adopting the
            // agent's camera here flipped the owner, cancelled the gesture and
            // jumped the view. The agent's revision is still newer, so the next
            // poll after the gesture picks it up.
            if (System.currentTimeMillis() - modellingCameraTouchedAt < 500) continue
            modellingCameraRev = onDisk.rev
            modellingOwner = onDisk.owner
            if (onDisk.owner == CameraOwner.AGENT) modellingCamera = onDisk
        }
    }

    // Rebuilds the conversation when the chat is opened with no live client.
    //
    // Rotating the phone recreates this activity, and with it every `remember`
    // above: the chat object and the message list both go, while
    // `aiConfigured` comes back true from the store. That left an empty chat
    // with the setup panel already dismissed and nothing left that could
    // reconnect it - the conversation looked reset. The same happens on any
    // configuration change and after Android kills the process.
    //
    // Everything needed to recover is already persisted, so reconnect rather
    // than making the user do it. Declared here because a local function
    // cannot be called before it is defined.
    LaunchedEffect(aiConfigured, aiChatOpen, modellingOpen) {
        // The modelling screen is a conversation too, and it has no connect UI of
        // its own: without this a rotation left it empty and every send failed
        // with "Connect to the harness first" and no way back.
        val missingClient = (aiChatOpen && harnessChat.get() == null) ||
            (modellingOpen && modellingChat.get() == null)
        if (aiConfigured && missingClient) connectHarness()
    }

    // The standing brief waits for the connection its screen opened in the same
    // frame, and is cleared before it is sent so it can only go once.
    LaunchedEffect(modellingOpen, modellingChatReady, modellingIntroPending) {
        if (!modellingOpen || !modellingChatReady || !modellingIntroPending) return@LaunchedEffect
        modellingIntroPending = false
        askModellingAgent(modellingIntroPrompt)
    }

    // Android-style back navigation: back closes any open layer instead of exiting.
    // The menu-unfold handler is composed FIRST so it has the LOWEST priority (the
    // last registered enabled BackHandler wins in Compose), letting any open sheet
    // or dialog consume the back event before the menu is expanded again.
    BackHandler(enabled = allSettingsOpen) { allSettingsOpen = false }
    BackHandler(enabled = printerScreenOpen) { printerScreenOpen = false }
    BackHandler(enabled = modelToolsOpen) { modelToolsOpen = false }
    BackHandler(enabled = nonPlanarOpen) { nonPlanarOpen = false }
    BackHandler(enabled = conicalOpen) { conicalOpen = false }
    BackHandler(enabled = meshLimitOpen) { meshLimitOpen = false }
    BackHandler(enabled = profilesOpen) { profilesOpen = false }
    BackHandler(
        enabled = layerEventsOpen && state.layerPreview != null && state.hasCurrentGcode(),
    ) { layerEventsOpen = false }
    BackHandler(enabled = aiChatOpen && selectedTab == AppTab.PLATE) { aiChatOpen = false }
    BackHandler(enabled = modellingOpen) { modellingOpen = false }
    BackHandler(enabled = blenderFilesOpen && !modellingOpen) { blenderFilesOpen = false }
    // The paint brush and the annotation tools own the model's gestures while they
    // are open, so back leaves them the way their own Close action does.
    BackHandler(enabled = supportPaintUiOpen) {
        viewModel.setPaintMode(SupportPaintMode.NONE)
        supportPaintUiOpen = false
    }
    BackHandler(enabled = annotationUiOpen) {
        viewModel.setAnnotationActive(false)
        annotationUiOpen = false
    }
    var viewerMode by rememberSaveable { mutableStateOf(ViewerMode.MODEL) }
    var selectedLayerIndex by rememberSaveable { mutableStateOf(0) }
    var modelOrientation by rememberSaveable(stateSaver = ViewerOrientationSaver) {
        mutableStateOf<ViewerOrientation?>(null)
    }
    var lastAutoSelectedResultId by rememberSaveable { mutableStateOf<String?>(null) }
    val nonPlanarStore = remember(context) { NonPlanarSettingsStore(context.applicationContext) }
    var nonPlanarSettings by remember(nonPlanarStore) { mutableStateOf(nonPlanarStore.load()) }
    val conicalStore = remember(context) { ConicalSettingsStore(context.applicationContext) }
    var conicalSettings by remember(conicalStore) { mutableStateOf(conicalStore.load()) }

    // Each of these opens a screen composed only inside one tab's branch. Left set
    // across a tab change, the title bar and the back handler keep pointing at a
    // screen that is no longer there, and the first back press is swallowed.
    LaunchedEffect(selectedTab) {
        if (selectedTab != AppTab.MORE) printerScreenOpen = false
        if (selectedTab != AppTab.SETTINGS) allSettingsOpen = false
        if (selectedTab != AppTab.PLATE) {
            modelToolsOpen = false
            modelDragMove = false
        }
    }

    LaunchedEffect(state.sliceResultId, state.layerPreview, nonPlanarSettings, conicalSettings) {
        val gcodeAvailable = state.hasCurrentGcode()
        val preview = state.layerPreview.takeIf { gcodeAvailable }
        val resultId = state.sliceResultId.takeIf { gcodeAvailable }
        if (preview == null) {
            viewerMode = ViewerMode.MODEL
            selectedLayerIndex = 0
            if (resultId == null) lastAutoSelectedResultId = null
        } else if (resultId != null && lastAutoSelectedResultId != resultId) {
            val firstSupport = preview.layers.indexOfFirst {
                it.supportSegmentCount > 0 || it.supportInterfaceSegmentCount > 0
            }
            selectedLayerIndex = if (firstSupport >= 0) firstSupport else 0
            viewerMode = if ((nonPlanarSettings.enabled || conicalSettings.enabled) && gcodeAvailable) {
                ViewerMode.NOZZLE_PATH
            } else {
                ViewerMode.LAYERS
            }
            lastAutoSelectedResultId = resultId
        }
    }

    val stlPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }
    val profilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importCuraProfile)
    }
    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importCuraProject)
    }
    // Image -> model is a harness job, not an on-device one: the harness wakes
    // the GPU box over Wake-on-LAN, runs the generator, and hibernates it again.
    // The upload path is not wired yet, so this reports rather than pretends.
    val aiImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendImageToHarness(uri)
    }
    val textureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let(viewModel::importModel)
        }
    }
    val configExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        uri?.let(viewModel::exportConfiguration)
    }
    val configImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importConfiguration)
    }
    val diagnosticExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        uri?.let(viewModel::exportDiagnosticLog)
    }
    val prusaConfigImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importPrusaConfig)
    }
    // A bundle's extension is not a registered MIME type, so the picker cannot filter to one.
    val orcaProfileImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importOrcaProfile)
    }
    val gcodeExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/x-gcode"),
    ) { uri: Uri? ->
        uri?.let(viewModel::exportGcode)
    }

    val effectiveSliceBlockedReason = sliceBlockedReason
        ?: if (nonPlanarSettings.enabled && conicalSettings.enabled) {
            "Non-planar and conical slicing are mutually exclusive; disable one before slicing"
        } else {
            null
        }

    fun launchBumpMesh() {
        val mesh = state.mesh
        if (mesh == null || state.isBusy) {
            Toast.makeText(context, "Import a model first", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val source = File(
                        context.cacheDir,
                        "bumpmesh-source/current-displayed.stl",
                    )
                    StlMeshWriter.writeBinary(mesh, source)
                    source
                }
            }.onSuccess { source ->
                textureLauncher.launch(
                    Intent(context, BumpMeshActivity::class.java)
                        .putExtra(BumpMeshActivity.EXTRA_MODEL_PATH, source.absolutePath)
                        .putExtra(BumpMeshActivity.EXTRA_MODEL_NAME, mesh.displayName),
                )
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    error.message ?: "Unable to prepare the model for BumpMesh",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val expandedLayout = maxWidth >= 600.dp
        // The app's navigation is a suite, not a bar: the same four
        // destinations are a bottom bar on a phone and a rail down the side
        // once the window is wide enough for one. The suite reads the window's
        // own size class and hands this Scaffold whatever it does not take.
        // The app's chrome, once. A phone puts the destinations in the suite's
        // bottom bar; a fold puts them in the rail on the left, and that rail is
        // ours: the printing session lives in it too, under the destinations,
        // which the suite's own rail has no room for.
        val chrome: @Composable (Modifier) -> Unit = { scaffoldModifier ->
            Scaffold(
                modifier = scaffoldModifier,
                // No app chrome while modelling. The modelling screen is a whole
                // destination with its own bar, and stacking the Plate header above it
                // spent a strip of the screen saying "Plate" about a tab that is not
                // what you are looking at.
                topBar = {
                    if (modellingOpen) return@Scaffold
                    TopAppBar(
                        navigationIcon = {
                            if (printerScreenOpen) {
                                androidx.compose.material3.IconButton(onClick = { printerScreenOpen = false }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back to More",
                                    )
                                }
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    if (printerScreenOpen) "Printer" else selectedTab.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    if (printerScreenOpen) "Machine profile & safety" else selectedTab.subtitleFor(state),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        actions = {
                            if (selectedTab == AppTab.PLATE) {
                                // The slice's status, in the one strip of the Plate
                                // that no card can fold away.
                                SliceStatus(state = state, detailed = expandedLayout)
                                Box {
                                    TopBarTextAction(
                                        label = "Import",
                                        onClick = { importMenuExpanded = true },
                                    )
                                    DropdownMenu(
                                        expanded = importMenuExpanded,
                                        onDismissRequest = { importMenuExpanded = false },
                                        modifier = Modifier.widthIn(min = 280.dp, max = 340.dp),
                                    ) {
                                        MenuSectionLabel("Files")
                                        DropdownMenuItem(
                                            text = { Text("Import model") },
                                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                            onClick = {
                                                importMenuExpanded = false
                                                stlPicker.launch(arrayOf("*/*"))
                                            },
                                            enabled = !state.isBusy,
                                        )
                                    }
                                }
                                Box {
                                    TopBarTextAction(
                                        label = "Plate",
                                        onClick = { plateOverflowExpanded = true },
                                    )
                                    DropdownMenu(
                                        expanded = plateOverflowExpanded,
                                        onDismissRequest = { plateOverflowExpanded = false },
                                        modifier = Modifier.widthIn(min = 280.dp, max = 340.dp),
                                    ) {
                                        MenuSectionLabel("Model")
                                        DropdownMenuItem(
                                            text = { Text("Position & rotation") },
                                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                            onClick = {
                                                plateOverflowExpanded = false
                                                modelToolsOpen = true
                                            },
                                            enabled = state.mesh != null && !state.isBusy,
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Mesh triangle limit") },
                                            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                            onClick = {
                                                plateOverflowExpanded = false
                                                meshLimitOpen = true
                                            },
                                            enabled = !state.isBusy,
                                        )
                                        HorizontalDivider()
                                        MenuSectionLabel("Storage")
                                        DropdownMenuItem(
                                            text = { Text("Blender files") },
                                            leadingIcon = { Icon(AppIcons.Cube, contentDescription = null) },
                                            onClick = {
                                                plateOverflowExpanded = false
                                                blenderFilesOpen = true
                                            },
                                        )
                                        HorizontalDivider()
                                        plateOverflowItems { plateOverflowExpanded = false }
                                    }
                                }
                                Box {
                                    TopBarTextAction(
                                        label = "Blender",
                                        onClick = { blenderMenuExpanded = true },
                                    )
                                    DropdownMenu(
                                        expanded = blenderMenuExpanded,
                                        onDismissRequest = { blenderMenuExpanded = false },
                                        modifier = Modifier.widthIn(min = 280.dp, max = 340.dp),
                                    ) {
                                        MenuSectionLabel("Model")
                                        DropdownMenuItem(
                                            text = { Text("Model from scratch") },
                                            leadingIcon = { Icon(AppIcons.Cube, contentDescription = null) },
                                            onClick = {
                                                blenderMenuExpanded = false
                                                openModelling()
                                            },
                                            enabled = !state.isBusy,
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Upload model to Blender") },
                                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                            onClick = {
                                                blenderMenuExpanded = false
                                                viewModel.sendModelToBlender()
                                            },
                                            enabled = state.mesh != null && !state.isBusy,
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Stop Blender engine") },
                                            leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                                            onClick = {
                                                blenderMenuExpanded = false
                                                viewModel.stopBlenderEngine()
                                            },
                                            enabled = !state.isBusy,
                                        )
                                        // The engine's shared secret lives in app-private storage,
                                        // which nothing outside the app can read on a phone without
                                        // root, so the app is the only place a modelling agent can
                                        // be handed it.
                                        DropdownMenuItem(
                                            text = { Text("Copy MCP token") },
                                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                                            onClick = {
                                                blenderMenuExpanded = false
                                                copyMcpToken(context)
                                            },
                                            enabled = !state.isBusy,
                                        )
                                        HorizontalDivider()
                                        MenuSectionLabel("Paint")
                                        DropdownMenuItem(
                                            text = { Text("Start point-to-point paint") },
                                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                            onClick = {
                                                blenderMenuExpanded = false
                                                viewModel.setAnnotationActive(true)
                                                annotationUiOpen = true
                                            },
                                            enabled = state.mesh != null && !state.isBusy,
                                        )
                                        HorizontalDivider()
                                        MenuSectionLabel("Assistant")
                                        DropdownMenuItem(
                                            text = { Text("Ask AI") },
                                            leadingIcon = { Icon(AppIcons.Sparkle, contentDescription = null) },
                                            onClick = {
                                                blenderMenuExpanded = false
                                                aiChatOpen = true
                                            },
                                            enabled = !state.isBusy,
                                        )
                                    }
                                }
                            }
                        },
                    )
                },
                bottomBar = {
                    // The session pane owns Slice/Export on the expanded layout; the
                    // bottom action bar is for phone-sized windows only. The tabs are not
                    // in here any more: the navigation suite draws them around this
                    // Scaffold, as a bottom bar or as a rail.
                    if (selectedTab == AppTab.PLATE && !expandedLayout) {
                        ActionBar(
                            state = state,
                            nonPlanarEnabled = nonPlanarSettings.enabled,
                            conicalEnabled = conicalSettings.enabled,
                            sliceBlockedReason = effectiveSliceBlockedReason,
                            onSlice = viewModel::sliceModel,
                            onExportGcode = { gcodeExportPicker.launch(GcodeExportName.suggest()) },
                            onTools = { modelToolsOpen = true },
                            expanded = plateActionBarExpanded,
                            onToggle = { plateActionBarExpanded = !plateActionBarExpanded },
                        )
                    }
                },
            ) { padding ->
                // Outside the tab switch on purpose: the Plate menu is what opens this,
                // so tying it to the More tab meant the tap set a flag and nothing drew.
                if (modellingOpen) {
                    ModellingScreen(
                        state = state,
                        messages = modellingMessages,
                        busy = modellingBusy,
                        status = modellingStatus,
                        owner = modellingOwner,
                        // Locked while the agent works: the camera is the agent's until
                        // it stops, which is the whole point of the lock.
                        canTakeCamera = !modellingBusy,
                        onSend = ::askModellingAgent,
                        onExit = { modellingOpen = false },
                        onTakeCamera = { setModellingOwner(CameraOwner.USER) },
                        onHandBackCamera = { setModellingOwner(CameraOwner.AGENT) },
                        onCameraMoved = ::publishModellingCamera,
                        incomingCamera = modellingCamera,
                        blenderDir = blenderDir,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                } else if (blenderFilesOpen) {
                    BlenderFilesScreen(
                        onBack = { blenderFilesOpen = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                } else when (selectedTab) {
                    AppTab.PLATE -> BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        // The expanded decision is the window's, above: this box has
                        // already lost the rail's width, so measuring here would see
                        // less than the window really has.
                        // Captured before the bottom stack: ColumnScope carries the
                        // layout scope marker, so the box's own maxHeight would not be
                        // reachable from inside it.
                        val plateHeightDp = maxHeight
                        // One call for both window sizes: the expanded and phone plates
                        // differ only in the chrome around the viewer, and that is
                        // decided from the window width above. Branching again here on
                        // this box's width - which the rail has already narrowed - let
                        // the two decisions disagree on a 600-680dp window, laying the
                        // model tools out for a phone beside a rail meant for a fold.
                        ViewerPanel(
                            state = state,
                            viewerMode = viewerMode,
                            selectedLayerIndex = selectedLayerIndex,
                            modelOrientation = modelOrientation,
                            onOrientationChanged = { modelOrientation = it },
                            nonPlanarEnabled = nonPlanarSettings.enabled,
                            conicalEnabled = conicalSettings.enabled,
                            supportPaintUiOpen = supportPaintUiOpen,
                            annotationUiOpen = annotationUiOpen,
                            onViewerMode = { viewerMode = it },
                            onLayerSelected = { selectedLayerIndex = it },
                            onEditLayerEvents = { layerEventsOpen = true },
                            onPaintHit = viewModel::paintAt,
                            onSurfacePick = viewModel::pickSurfaceAt,
                            dragMove = modelDragMove,
                            onModelDrag = { deltaX, deltaY ->
                                viewModel.nudgeModel(deltaX.toDouble(), deltaY.toDouble())
                            },
                            onPaintMode = viewModel::setPaintMode,
                            onCloseSupportPaintUi = {
                                viewModel.setPaintMode(SupportPaintMode.NONE)
                                supportPaintUiOpen = false
                            },
                            onAnnotationTap = viewModel::onAnnotationTap,
                            onAnnotationAdjust = viewModel::onAnnotationAdjust,
                            onAnnotationZAdjustStart = viewModel::beginAnnotationZAdjust,
                            onAnnotationZAdjust = viewModel::onAnnotationZAdjust,
                            onAnnotationZAdjustEnd = viewModel::endAnnotationZAdjust,
                            plateOverlayContent = plateOverlayContent,
                            annotationActions = AnnotationActions(
                                onLockSegment = viewModel::lockAnnotationSegment,
                                onLockSeries = viewModel::lockAnnotationSeries,
                                onUndo = viewModel::undoAnnotation,
                                onClear = viewModel::clearAnnotation,
                                onSave = viewModel::saveAnnotation,
                                onThickness = viewModel::setAnnotationThickness,
                                onPlaneZ = viewModel::setAnnotationWorkPlaneZ,
                            ),
                            onCloseAnnotationUi = {
                                viewModel.setAnnotationActive(false)
                                annotationUiOpen = false
                            },
                            printerCardExpanded = printerCardExpanded,
                            onPrinterCardToggle = { printerCardExpanded = !printerCardExpanded },
                            noticeExpanded = noticeCardExpanded,
                            onNoticeToggle = { noticeCardExpanded = !noticeCardExpanded },
                            hintsDismissed = modelHintsDismissed,
                            onDismissHints = { modelHintsDismissed = true },
                            modifier = Modifier.fillMaxSize(),
                        )
    
                        // Floating model-tools bar over the viewer: one slim row of
                        // group buttons, each opening only its own compact panel above
                        // the bar. Both are content-sized Cards and there is
                        // deliberately no scrim and no full-size touch surface, so
                        // touches that miss them reach the model view underneath and
                        // the camera keeps orbiting, panning and zooming. The stack
                        // itself carries no pointer input either, so the cells only
                        // consume input inside a card's own bounds.
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(),
                        ) {
                            if (modelToolsOpen) {
                                ModelToolsOverlay(
                                    state = state,
                                    expandedLayout = expandedLayout,
                                    plateHeight = plateHeightDp,
                                    onMove = viewModel::moveModel,
                                    onRotate = viewModel::rotateModel,
                                    onScale = viewModel::scaleModel,
                                    onDropToBed = viewModel::dropModelToBed,
                                    onLayFlat = viewModel::layModelFlat,
                                    onReset = viewModel::resetModelTransform,
                                    onApplyImportedTransform = viewModel::applyImportedSceneTransform,
                                    onOpenSupportPaintUi = {
                                        modelToolsOpen = false
                                        supportPaintUiOpen = true
                                    },
                                    onBrushRadius = viewModel::setBrushRadius,
                                    onClearPaint = viewModel::clearSupportPaint,
                                    dragMove = modelDragMove,
                                    onToggleDragMove = { modelDragMove = !modelDragMove },
                                    onClose = { modelToolsOpen = false },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // Phone layouts keep the bar tucked into the
                                        // bottom-end corner; the expanded layout has
                                        // the session panel on the right, so the bar
                                        // sits bottom-centre where nothing overlaps it.
                                        .padding(horizontal = 12.dp)
                                        .padding(top = 12.dp)
                                        // Keeps the 12dp inset from the plate's
                                        // bottom edge now that nothing sits below it.
                                        .padding(bottom = 12.dp),
                                )
                            }
                        }
                    }
                    AppTab.SETTINGS -> if (allSettingsOpen) {
                        val catalogSpecs = remember(engine) {
                            when (engine) {
                                SlicerEngine.CURA -> AllSettingsCatalogs.cura(context.assets)
                                SlicerEngine.PRUSA -> AllSettingsCatalogs.prusa(context.assets)
                                SlicerEngine.ORCA -> AllSettingsCatalogs.orca(context.assets)
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                        ) {
                            AllSettingsSheet(
                                engineLabel = engine.label,
                                specs = catalogSpecs,
                                added = when (engine) {
                                    SlicerEngine.CURA -> state.extraCuraSettings
                                    SlicerEngine.PRUSA -> state.extraPrusaSettings
                                    SlicerEngine.ORCA -> state.extraOrcaSettings
                                },
                                managedKeys = when (engine) {
                                    SlicerEngine.CURA -> AllSettingsCatalogs.CURA_MANAGED_KEYS
                                    SlicerEngine.PRUSA -> AllSettingsCatalogs.PRUSA_MANAGED_KEYS
                                    SlicerEngine.ORCA -> AllSettingsCatalogs.ORCA_MANAGED_KEYS
                                },
                                blockedKeys = when (engine) {
                                    SlicerEngine.CURA -> AllSettingsCatalogs.CURA_BLOCKED_KEYS
                                    SlicerEngine.PRUSA -> AllSettingsCatalogs.PRUSA_BLOCKED_KEYS
                                    SlicerEngine.ORCA -> AllSettingsCatalogs.ORCA_BLOCKED_KEYS
                                },
                                onAdd = { key, value -> viewModel.setExtraSetting(engine, key, value) },
                                onRemove = { key -> viewModel.removeExtraSetting(engine, key) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            )
                            OutlinedButton(
                                onClick = { allSettingsOpen = false },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Back to settings")
                            }
                        }
                    } else Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        EngineSelectorCard(
                            engine = engine,
                            onEngineChange = onEngineChange,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (engine == SlicerEngine.PRUSA) {
                            LaunchedEffect(Unit) { viewModel.loadPrusaPresets() }
                            PrusaSettingsSheet(
                                state = state,
                                onSettings = viewModel::updatePrusaSettings,
                                onImportConfig = { prusaConfigImportPicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
                                onOpenAllSettings = { allSettingsOpen = true },
                                onPrusaPrinter = viewModel::selectPrusaPrinter,
                                onPrusaPrint = viewModel::selectPrusaPrint,
                                onPrusaFilament = viewModel::selectPrusaFilament,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            )
                        } else if (engine == SlicerEngine.ORCA) {
                            OrcaSettingsSheet(
                                state = state,
                                onSettings = viewModel::updateOrcaSettings,
                                onOpenAllSettings = { allSettingsOpen = true },
                                onImportProfile = {
                                    orcaProfileImportPicker.launch(
                                        arrayOf("application/json", "application/zip", "*/*"),
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            )
                        } else {
                            LaunchedEffect(Unit) { viewModel.loadCuraMachines() }
                            CategorizedSettingsSheet(
                                state = state,
                                onSettings = viewModel::updateSettings,
                                onResetOverrides = viewModel::resetAllSettingOverrides,
                                onImportProfile = { profilePicker.launch(arrayOf("*/*")) },
                                onImportProject = {
                                    projectPicker.launch(
                                        arrayOf(
                                            "model/3mf",
                                            "application/vnd.ms-package.3dmanufacturing-3dmodel+xml",
                                            "*/*",
                                        ),
                                    )
                                },
                                onOpenAllSettings = { allSettingsOpen = true },
                                onCuraMachine = viewModel::selectCuraMachine,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            )
                        }
                    }
                    AppTab.PRINT -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        printTabContent()
                    }
                    AppTab.MORE -> if (printerScreenOpen) {
                        PrinterScreen(
                            state = state,
                            checklistDone = printerChecklistDone,
                            onChecklistToggle = { id, checked ->
                                val updated = if (checked) printerChecklistDone + id else printerChecklistDone - id
                                printerChecklistDone = updated
                                printerChecklistStore.save(updated)
                            },
                            onSettings = viewModel::updateSettings,
                            onResetOverrides = viewModel::resetAllSettingOverrides,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                        )
                    } else MoreScreen(
                        state = state,
                        nonPlanarEnabled = nonPlanarSettings.enabled,
                        conicalEnabled = conicalSettings.enabled,
                        onProfiles = { profilesOpen = true },
                        onMachineSettings = { printerScreenOpen = true },
                        onExportConfig = { configExportPicker.launch("printer-config.json") },
                        onExportLog = { diagnosticExportPicker.launch("enderslicer-diagnostic.txt") },
                        onImportConfig = { configImportPicker.launch(arrayOf("application/json", "*/*")) },
                        onBumpMesh = ::launchBumpMesh,
                        onNonPlanar = { nonPlanarOpen = true },
                        onConical = { conicalOpen = true },
                        onMeshLimit = { meshLimitOpen = true },
                        extraItems = moreExtraItems,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                }
            }
        }

        if (expandedLayout) {
            Row(modifier = Modifier.fillMaxSize()) {
                SessionRail(
                    selected = selectedTab,
                    onSelect = { selectedTab = it },
                    engine = engine,
                    state = state,
                    gcodeAvailable = state.hasCurrentGcode(),
                    sliceBlockedReason = effectiveSliceBlockedReason,
                    onSlice = viewModel::sliceModel,
                    onExportGcode = { gcodeExportPicker.launch(GcodeExportName.suggest()) },
                    onTools = { modelToolsOpen = true },
                    onSettings = viewModel::updateSettings,
                    onPrusaSettings = viewModel::updatePrusaSettings,
                    onOrcaSettings = viewModel::updateOrcaSettings,
                )
                chrome(Modifier.weight(1f))
            }
        } else {
            NavigationSuiteScaffold(
                navigationSuiteItems = { AppTabItems(selectedTab, onSelect = { selectedTab = it }) },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                chrome(Modifier.fillMaxSize())
            }
        }
    }

    if (profilesOpen) {
        AppBottomSheet(
            onDismissRequest = { profilesOpen = false },
        ) {
            ProfileManagementSheet(
                state = state,
                viewModel = viewModel,
                engine = engine,
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .navigationBarsPadding(),
            )
        }
    }

    if (meshLimitOpen) {
        AppBottomSheet(
            onDismissRequest = { meshLimitOpen = false },
        ) {
            MeshTriangleLimitSheet(
                currentLimit = MeshTriangleLimits.current(),
                currentModelTriangles = state.mesh?.triangleCount,
                onSave = { limit ->
                    val saved = MeshTriangleLimits.save(context, limit)
                    meshLimitOpen = false
                    Toast.makeText(
                        context,
                        "Mesh triangle limit set to ${MeshTriangleLimits.formatCount(saved)}",
                        Toast.LENGTH_LONG,
                    ).show()
                },
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .navigationBarsPadding(),
            )
        }
    }

    if (nonPlanarOpen) {
        AppBottomSheet(
            onDismissRequest = { nonPlanarOpen = false },
        ) {
            val effectivePrinter = state.printer.withSettings(state.settings)
            NonPlanarSettingsSheet(
                initial = nonPlanarSettings,
                layerHeightMm = state.settings.layerHeightMm,
                nozzleDiameterMm = effectivePrinter.nozzleSizeMm,
                engine = engine,
                onSave = { value ->
                    val safe = value.validated()
                    // The store reports the change it saw, and that is also what
                    // decides whether the published slices have to go.
                    val changed = nonPlanarStore.save(safe)
                    nonPlanarSettings = safe
                    nonPlanarOpen = false
                    if (changed) {
                        viewerMode = ViewerMode.MODEL
                        selectedLayerIndex = 0
                        lastAutoSelectedResultId = null
                        // Off the UI thread and after the sheet is gone: the
                        // settings are saved whatever happens to the old G-code,
                        // so the click must not wait on a directory walk.
                        scope.launch {
                            nonPlanarStore.invalidatePublishedSlices()?.let { message ->
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    Toast.makeText(
                        context,
                        if (changed) {
                            if (safe.enabled) {
                                "Non-planar settings saved; slice again before export"
                            } else {
                                "Non-planar printing disabled; slice again before export"
                            }
                        } else if (safe.enabled) {
                            "Non-planar settings unchanged"
                        } else {
                            "Non-planar printing remains disabled"
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .navigationBarsPadding(),
            )
        }
    }

    if (conicalOpen) {
        AppBottomSheet(
            onDismissRequest = { conicalOpen = false },
        ) {
            ConicalSettingsSheet(
                initial = conicalSettings,
                onSave = { value ->
                    val safe = value.validated()
                    // The store reports the change it saw, and that is also what
                    // decides whether the published slices have to go.
                    val changed = conicalStore.save(safe)
                    conicalSettings = safe
                    conicalOpen = false
                    if (changed) {
                        viewerMode = ViewerMode.MODEL
                        selectedLayerIndex = 0
                        lastAutoSelectedResultId = null
                        // Off the UI thread and after the sheet is gone: the
                        // settings are saved whatever happens to the old G-code,
                        // so the click must not wait on a directory walk.
                        scope.launch {
                            conicalStore.invalidatePublishedSlices()?.let { message ->
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    Toast.makeText(
                        context,
                        if (changed) {
                            if (safe.enabled) {
                                "Conical slicing settings saved; slice again before export"
                            } else {
                                "Conical slicing disabled; slice again before export"
                            }
                        } else if (safe.enabled) {
                            "Conical slicing settings unchanged"
                        } else {
                            "Conical slicing remains disabled"
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .navigationBarsPadding(),
            )
        }
    }

    if (layerEventsOpen && state.layerPreview != null && state.hasCurrentGcode()) {
        val preview = requireNotNull(state.layerPreview)
        val layer = preview.layers[selectedLayerIndex.coerceIn(preview.layers.indices)]
        AppBottomSheet(
            onDismissRequest = { layerEventsOpen = false },
        ) {
            LayerEventsSheet(
                layer = layer,
                events = state.layerEvents,
                settings = state.settings,
                isBusy = state.isBusy,
                onAdd = { type, value, secondary, text ->
                    viewModel.addLayerEvent(layer.number, layer.z, type, value, secondary, text)
                },
                onRemove = viewModel::removeLayerEvent,
                onClearUserEvents = viewModel::clearLayerEvents,
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .navigationBarsPadding(),
            )
        }
    }

    // Plate only: the other tabs have no viewer for the user to paint on, and
    // the chat's whole point is describing painted regions.
    if (aiChatOpen && selectedTab == AppTab.PLATE) {
        AiChatOverlay(
            messages = aiMessages,
            onSend = ::askHarness,
            onUploadImage = { aiImagePicker.launch(arrayOf("image/*")) },
            onStop = ::stopHarness,
            onClose = { aiChatOpen = false },
            setup = if (aiConfigured) {
                null
            } else {
                ChatSetup(
                    address = aiAddress,
                    onAddressChange = { aiAddress = it },
                    onConnect = { connectHarness() },
                    workspace = aiWorkspace,
                    onWorkspaceChange = { aiWorkspace = it },
                )
            },
            busy = aiBusy,
            status = aiStatus,
            insecure = aiInsecure,
            modifier = Modifier.fillMaxSize(),
        )
    }

    // Asked at the moment the address is used, not when it is typed: the answer
    // lets this one connection proceed, and the saved permission is what keeps
    // the question from coming back for the same harness.
    aiCleartextPrompt?.let { address ->
        AlertDialog(
            onDismissRequest = { aiCleartextPrompt = null },
            title = { Text("Unencrypted connection") },
            text = {
                Text(
                    "$address is plain HTTP, so the launch token, the session cookie " +
                        "and everything you send travel over the network in the clear. " +
                        "Anyone who can reach it can read them and drive this harness. " +
                        "Use the HTTPS tailnet address if you have one.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        aiCleartextPrompt = null
                        connectHarness(cleartextAccepted = true)
                    },
                ) { Text("Connect anyway") }
            },
            dismissButton = {
                TextButton(onClick = { aiCleartextPrompt = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Prominent engine switcher: Cura (blue), PrusaSlicer (orange) or OrcaSlicer (teal). Each
 * engine is a whole Product mode - theme accent, profile formats, G-code
 * dialect and engine binary; profiles are never merged across engines.
 */
@Composable
internal fun EngineSelectorCard(
    engine: SlicerEngine,
    onEngineChange: (SlicerEngine) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Slicing engine", style = MaterialTheme.typography.titleMedium)
            Text(
                "Profiles stay separate: pick the slicer you want, the app becomes that product.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EngineOption(
                    label = "Cura",
                    tagline = "Blue",
                    accent = EngineAccent.CURA,
                    selected = engine == SlicerEngine.CURA,
                    onClick = { onEngineChange(SlicerEngine.CURA) },
                    modifier = Modifier.weight(1f),
                )
                EngineOption(
                    label = "PrusaSlicer",
                    tagline = "Orange",
                    accent = EngineAccent.PRUSA,
                    selected = engine == SlicerEngine.PRUSA,
                    onClick = { onEngineChange(SlicerEngine.PRUSA) },
                    modifier = Modifier.weight(1f),
                )
                EngineOption(
                    label = "OrcaSlicer",
                    tagline = "Teal",
                    accent = EngineAccent.ORCA,
                    selected = engine == SlicerEngine.ORCA,
                    onClick = { onEngineChange(SlicerEngine.ORCA) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private enum class EngineAccent { CURA, PRUSA, ORCA }

@Composable
private fun EngineOption(
    label: String,
    tagline: String,
    accent: EngineAccent,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor = when (accent) {
        EngineAccent.CURA -> Color(0xFF3B99FF)
        EngineAccent.PRUSA -> Color(0xFFFF8A2A)
        EngineAccent.ORCA -> Color(0xFF00A79D)
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        border = if (selected) {
            BorderStroke(2.dp, accentColor)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        modifier = modifier.height(76.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                )
                Text(label, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                tagline,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Persistent navigation: Plate · Settings · Print · More, as navigation-suite
 * items. The suite draws them as the bottom bar on a phone and as a rail down
 * the side once the window is wide enough for one - on a foldable that is the
 * difference between a bar across the bottom of a 1184dp screen and a rail that
 * gives the plate its height back.
 */
internal fun NavigationSuiteScope.AppTabItems(selected: AppTab, onSelect: (AppTab) -> Unit) {
    AppTab.entries.forEach { tab ->
        item(
            selected = selected == tab,
            onClick = { onSelect(tab) },
            icon = { Icon(tab.icon, contentDescription = null) },
            label = { Text(tab.shortLabel) },
        )
    }
}

/** More hub: grouped navigation to everything outside the plate. */
/**
 * Puts the Blender engine's token on the clipboard so a modelling agent can be
 * given it directly.
 *
 * The token file sits in app-private storage and the engine refuses every
 * command, ping included, without it. On a phone without root there is no way
 * to read that file from outside, which is exactly what the token is for, so the
 * app hands it over instead - marked sensitive so Android keeps it out of the
 * clipboard preview.
 */
private fun copyMcpToken(context: Context) {
    val token = BlenderEngine.ensureToken(File(context.applicationContext.filesDir, "blender"))
    if (token.isNullOrBlank()) {
        Toast.makeText(context, "The MCP token is not available yet", Toast.LENGTH_SHORT).show()
        return
    }
    val clip = ClipData.newPlainText("Blender MCP token", token)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    Toast.makeText(context, "MCP token copied", Toast.LENGTH_SHORT).show()
}

@Composable
private fun MoreScreen(
    state: MainUiState,
    nonPlanarEnabled: Boolean,
    conicalEnabled: Boolean,
    onProfiles: () -> Unit,
    onMachineSettings: () -> Unit,
    onExportConfig: () -> Unit,
    onImportConfig: () -> Unit,
    onExportLog: () -> Unit,
    onBumpMesh: () -> Unit,
    onNonPlanar: () -> Unit,
    onConical: () -> Unit,
    onMeshLimit: () -> Unit,
    extraItems: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        MoreSectionLabel("Configuration")
        Card(modifier = Modifier.fillMaxWidth()) {
            MoreRow(
                icon = AppIcons.Star,
                title = "Profiles & filament",
                subtitle = "Manage profiles, materials and filaments",
                enabled = !state.isBusy,
                onClick = onProfiles,
            )
            MoreDivider()
            MoreRow(
                icon = AppIcons.Machine,
                title = "Printer & G-code",
                subtitle = "Machine profile, safety and start/end G-code",
                enabled = !state.isBusy,
                onClick = onMachineSettings,
            )
            MoreDivider()
            MoreRow(
                icon = AppIcons.Swap,
                title = "Export configuration snapshot",
                subtitle = "Save the full setup to a JSON file",
                enabled = !state.isBusy,
                onClick = onExportConfig,
            )
            MoreDivider()
            MoreRow(
                icon = AppIcons.Info,
                title = "Export diagnostic log",
                subtitle = if (state.sliceLogPath == null) {
                    "The engine's log for the last slice - slice first"
                } else {
                    "The engine's log for the last slice, with the setup"
                },
                enabled = !state.isBusy && state.sliceLogPath != null,
                onClick = onExportLog,
            )
            MoreDivider()
            MoreRow(
                icon = AppIcons.Swap,
                title = "Import configuration snapshot",
                subtitle = "Restore a saved setup",
                enabled = !state.isBusy,
                onClick = onImportConfig,
            )
        }

        MoreSectionLabel("Experimental")
        Card(modifier = Modifier.fillMaxWidth()) {
            MoreRow(
                icon = AppIcons.Camera,
                title = "BumpMesh texturizer",
                subtitle = "Offline displacement texturing of the model",
                enabled = state.mesh != null && !state.isBusy,
                badge = "EXP",
                onClick = onBumpMesh,
            )
            MoreDivider()
            extraItems()
            MoreRow(
                icon = AppIcons.Layers,
                title = "Non-planar slicing",
                subtitle = if (nonPlanarEnabled) "CurviSlicer relief-field · enabled" else "CurviSlicer relief-field print",
                enabled = !state.isBusy,
                badge = if (nonPlanarEnabled) "ON" else "OFF",
                onClick = onNonPlanar,
            )
            MoreDivider()
            MoreRow(
                icon = AppIcons.Bolt,
                title = "Conical slicing",
                subtitle = if (conicalEnabled) "Cone-warped geometry · enabled" else "Cone-warped geometry modifier",
                enabled = !state.isBusy,
                badge = if (conicalEnabled) "ON" else "OFF",
                onClick = onConical,
            )
            MoreDivider()
            MoreRow(
                icon = AppIcons.Filter,
                title = "Mesh triangle limit",
                subtitle = "Max triangles for viewer and texturizer",
                enabled = !state.isBusy,
                onClick = onMeshLimit,
            )
        }

        MoreSectionLabel("About")
        Card(modifier = Modifier.fillMaxWidth()) {
            MoreRow(
                icon = AppIcons.Info,
                title = "TrioSlicer",
                // From the build, not typed: a hardcoded version goes stale
                // in the one place a user checks it.
                subtitle = "Version " + BuildConfig.VERSION_NAME + " · AGPL-3.0-or-later",
                onClick = {},
            )
            MoreDivider()
            MoreRow(
                icon = AppIcons.Shield,
                title = "Safety notes",
                subtitle = "Inspect every model, setting and generated G-code before printing",
                onClick = {},
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun MoreSectionLabel(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

@Composable
private fun MoreDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Standard row for the More hub. Also used by integrations (Smart Infill). */
@Composable
internal fun MoreRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(38.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge != null) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier.height(20.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 6.dp),
                ) {
                    Text(badge, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun TopBarTextAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .height(48.dp)
            .widthIn(min = 80.dp, max = 156.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        content()
    }
}

@Composable
private fun MenuSectionLabel(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

@Composable
private fun ViewerPanel(
    state: MainUiState,
    viewerMode: ViewerMode,
    selectedLayerIndex: Int,
    modelOrientation: ViewerOrientation?,
    onOrientationChanged: (ViewerOrientation) -> Unit,
    nonPlanarEnabled: Boolean,
    conicalEnabled: Boolean,
    supportPaintUiOpen: Boolean,
    annotationUiOpen: Boolean,
    onViewerMode: (ViewerMode) -> Unit,
    onLayerSelected: (Int) -> Unit,
    onEditLayerEvents: () -> Unit,
    onPaintHit: (MeshPicker.Hit) -> Unit,
    onSurfacePick: (MeshPicker.Hit) -> Unit,
    onPaintMode: (SupportPaintMode) -> Unit,
    /** True while a finger drag moves the model across the plate instead of orbiting. */
    dragMove: Boolean,
    /** A finished drag: the plate movement it asked for, in millimetres. */
    onModelDrag: (Float, Float) -> Unit,
    onCloseSupportPaintUi: () -> Unit,
    onAnnotationTap: (AnnotationGesture) -> Unit,
    onAnnotationAdjust: (SegmentEnd, AnnotationGesture) -> Unit,
    onAnnotationZAdjustStart: (SegmentEnd) -> Unit,
    onAnnotationZAdjust: (AnnotationGesture) -> Unit,
    onAnnotationZAdjustEnd: () -> Unit,
    annotationActions: AnnotationActions,
    onCloseAnnotationUi: () -> Unit,
    printerCardExpanded: Boolean,
    onPrinterCardToggle: () -> Unit,
    noticeExpanded: Boolean,
    onNoticeToggle: () -> Unit,
    hintsDismissed: Boolean,
    onDismissHints: () -> Unit,
    plateOverlayContent: @Composable BoxScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val effectivePrinter = state.printer.withSettings(state.settings)
    val gcodeAvailable = state.hasCurrentGcode()
    Box(modifier = modifier) {
        val preview = state.layerPreview.takeIf { gcodeAvailable }
        when {
            viewerMode == ViewerMode.LAYERS && preview != null -> LayerPreviewView(
                preview = preview,
                selectedLayerIndex = selectedLayerIndex,
                events = state.layerEvents,
                onLayerSelected = onLayerSelected,
                onEditEvents = onEditLayerEvents,
                modifier = Modifier.fillMaxSize(),
            )
            viewerMode == ViewerMode.NOZZLE_PATH && gcodeAvailable -> if (state.sliceEngine != SlicerEngine.CURA) {
                PrusaNozzlePathView(
                    gcodePath = requireNotNull(state.gcodePath),
                    // PrusaSlicer spells an "auto" extrusion width as 0 and the
                    // importer stores that verbatim, so a non-positive width is an
                    // unknown one: falling through to the next choice, and finally
                    // to the Cura line width, is what keeps the flow readout from
                    // dividing by zero and reporting "flow Infinity%".
                    beadLineWidthMm = state.prusaSettings.perimeterExtrusionWidthMm
                        ?.takeIf { it > 0.0 }
                        ?: state.prusaSettings.firstLayerExtrusionWidthMm?.takeIf { it > 0.0 }
                        ?: state.settings.lineWidthMm,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                NozzlePathView(
                    gcodePath = requireNotNull(state.gcodePath),
                    beadHeightMm = state.settings.layerHeightMm,
                    beadLineWidthMm = state.settings.lineWidthMm,
                    filamentDiameterMm = state.settings.filamentDiameterMm,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> key(effectivePrinter) {
                var modelView by remember(effectivePrinter) { mutableStateOf<ModelSurfaceView?>(null) }
                LaunchedEffect(modelView) {
                    modelView?.let { view ->
                        // A recreated surface view starts at the default camera;
                        // restore the last orbit when returning to the Plate tab.
                        modelOrientation?.let { view.restoreOrientation(it) }
                        onOrientationChanged(view.currentOrientation())
                    }
                }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, modelView) {
                    val view = modelView
                    if (view == null) {
                        onDispose { }
                    } else {
                        val observer = LifecycleEventObserver { _, event ->
                            when (event) {
                                Lifecycle.Event.ON_RESUME -> view.onResume()
                                Lifecycle.Event.ON_PAUSE,
                                Lifecycle.Event.ON_STOP,
                                Lifecycle.Event.ON_DESTROY,
                                -> view.onPause()
                                else -> Unit
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            view.onResume()
                        }
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                            view.onPause()
                        }
                    }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        ModelSurfaceView(context, effectivePrinter).also { modelView = it }
                    },
                    update = { view ->
                        view.setMesh(state.mesh)
                        view.paintMode = state.paintMode
                        view.surfacePickActive = state.smartInfillPicking
                        view.setSmartInfillOverlay(state.smartInfillOverlay)
                        view.setPaintState(state.supportPaint)
                        view.dragMoveActive = dragMove
                        view.onModelDragCommitted = onModelDrag
                        view.onPaintHit = onPaintHit
                        view.onSurfacePick = onSurfacePick
                        view.annotationActive = state.annotationActive
                        view.onAnnotationTap = onAnnotationTap
                        view.onAnnotationAdjust = onAnnotationAdjust
                        view.onAnnotationZAdjustStart = onAnnotationZAdjustStart
                        view.onAnnotationZAdjust = onAnnotationZAdjust
                        view.onAnnotationZAdjustEnd = onAnnotationZAdjustEnd
                        view.setAnnotationOverlay(state.annotationOverlay)
                        view.onOrientationChanged = onOrientationChanged
                    },
                )
            }
        }

        if (supportPaintUiOpen && viewerMode == ViewerMode.MODEL) {
            SupportPaintOverlay(
                activeMode = state.paintMode,
                onPaintMode = onPaintMode,
                onClose = onCloseSupportPaintUi,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            )
        }

        if (annotationUiOpen && viewerMode == ViewerMode.MODEL) {
            AnnotationToolbar(
                state = state,
                actions = annotationActions,
                onClose = onCloseAnnotationUi,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            )
        }

        // Content the host app floats over the Plate — the native Smart Infill
        // workflow, which needs the model surface above it to stay tappable.
        plateOverlayContent()

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        ) {
            CollapsibleCard(
                // Open, this is the one header line that identifies the printer:
                // name plus build volume, and 340dp is what keeps it on one
                // line; the old header could stay at 290dp because the
                // dimensions had a second line to wrap onto. Folded, the card
                // is the chevron alone, so it stops holding the plate's
                // top-left corner while the model is being inspected.
                title = "%s · %.0f × %.0f × %.0f mm".format(
                    effectivePrinter.name,
                    effectivePrinter.widthMm,
                    effectivePrinter.depthMm,
                    effectivePrinter.heightMm,
                ),
                expanded = printerCardExpanded,
                onToggle = onPrinterCardToggle,
                modifier = Modifier.widthIn(max = 340.dp),
            ) {
                Text(
                    "%.2f mm nozzle".format(effectivePrinter.nozzleSizeMm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (nonPlanarEnabled || conicalEnabled) {
                    HorizontalDivider()
                }
                if (nonPlanarEnabled) {
                    Text(
                        "Non-planar printing enabled",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (conicalEnabled) {
                    Text(
                        "Conical slicing enabled",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                val mesh = state.mesh
                if (mesh == null) {
                    Text("Import an STL from the Import button", style = MaterialTheme.typography.bodySmall)
                } else {
                    HorizontalDivider()
                    SummaryRow("Model", mesh.displayName)
                    SummaryRow("Triangles", "${mesh.triangleCount}")
                    SummaryRow(
                        "Size",
                        "%.1f × %.1f × %.1f mm".format(
                            mesh.bounds.width,
                            mesh.bounds.depth,
                            mesh.bounds.height,
                        ),
                    )
                    state.modelPlacement?.let { placement ->
                        SummaryRow(
                            "Center",
                            "%.2f, %.2f · Z %.2f mm".format(
                                placement.centerXmm,
                                placement.centerYmm,
                                placement.baseZmm,
                            ),
                        )
                        SummaryRow("Placement", placement.source)
                    }
                }
                state.estimatedPrintSeconds?.takeIf { gcodeAvailable }?.let { seconds ->
                    HorizontalDivider()
                    SummaryRow("Estimated print", formatPrintTime(seconds))
                }
                if (state.warnings.isNotEmpty()) {
                    Text(
                        "Cura compatibility warnings: ${state.warnings.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            if (viewerMode == ViewerMode.MODEL) {
                // The status banner sits under the printer card instead of in
                // the plate's bottom-left corner: the bottom is where the Smart
                // Infill workflow and the Slice block live, and the banner was
                // landing on top of the panel there. Folded, the two cards are
                // a column of chevrons the model view can be read around.
                //
                // The plate's XYZ marker is not in this column any more: the
                // renderer draws it on the bed's own corner, where it belongs.
                Spacer(modifier = Modifier.height(8.dp))
                NoticeBanner(
                    statusMessage = state.statusMessage,
                    expanded = noticeExpanded,
                    onToggle = onNoticeToggle,
                    hintsDismissed = hintsDismissed,
                    onDismissHints = onDismissHints,
                    modifier = Modifier.widthIn(max = 340.dp),
                )
            }
        }

        if (preview != null || gcodeAvailable) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ViewerModeButton("Model", ViewerMode.MODEL, viewerMode, true, onViewerMode)
                    ViewerModeButton("Layers", ViewerMode.LAYERS, viewerMode, preview != null, onViewerMode)
                    ViewerModeButton("Path", ViewerMode.NOZZLE_PATH, viewerMode, gcodeAvailable, onViewerMode)
                }
            }
        }
    }
}

/**
 * The status banner in the plate's top-left corner: the ViewModel's status line
 * as the header, the gesture help as the content. Folded, the banner is the
 * chevron alone, so the status line comes back only while it is open - the same
 * trade the other Plate cards make.
 *
 * It is the second card of the printer column, under the printer card. It used
 * to be the lower cell of the plate's bottom stack, where it sat on top of the
 * Smart Infill panel; the bottom now belongs to that panel and the Slice block.
 */
@Composable
private fun NoticeBanner(
    statusMessage: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    hintsDismissed: Boolean,
    onDismissHints: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CollapsibleCard(
        // The notice is the header and the gesture help is the body, so the
        // banner's two halves fold and unfold together.
        title = statusMessage,
        titleStyle = MaterialTheme.typography.bodySmall,
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        if (!hintsDismissed) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Drag orbit · Pinch zoom · Two-finger pan · Double-tap reset",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Dismiss",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable(onClick = onDismissHints),
                )
            }
        }
    }
}

/**
 * Right-hand session pane for the expanded (unfolded foldable / large
 * screen) Plate layout: print summary, quick settings and actions next to
 * the viewer instead of below it. See docs/ux-redesign/mockups/08-foldable.png.
 */
/**
 * What the top bar says about the slice.
 *
 * The status has to outlive the cards: the Plate's columns fold, and on a phone
 * the action bar folds with them, so the top bar is the one line always drawn.
 * A wide window has room for the numbers behind the state as well.
 */
internal fun sliceStatusLabel(state: MainUiState, detailed: Boolean): String {
    if (state.isBusy) {
        // The engines that report progress say how far along they are.
        return state.sliceProgressPercent?.let { "Slicing… $it%" } ?: "Slicing…"
    }
    if (!state.hasCurrentGcode()) return "Not sliced"
    if (!detailed) return "Sliced"
    val layers = state.layerPreview?.layers?.size
    val seconds = state.estimatedPrintSeconds
    return when {
        layers != null && seconds != null -> "Sliced · $layers layers · ${formatPrintTime(seconds)}"
        layers != null -> "Sliced · $layers layers"
        seconds != null -> "Sliced · ${formatPrintTime(seconds)}"
        else -> "Sliced"
    }
}

/**
 * The slice, at a glance, in the top bar: the state, a spinner while the engine
 * works, and this slice's warnings. Nothing on the Plate can cover or collapse
 * it, which is the point - the panels over the model can all be folded away.
 */
@Composable
internal fun SliceStatus(
    state: MainUiState,
    detailed: Boolean,
    modifier: Modifier = Modifier,
) {
    val gcodeAvailable = state.hasCurrentGcode()
    val color = if (state.isBusy || gcodeAvailable) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier.padding(end = EnderSlicerDimens.Space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space6),
    ) {
        if (state.isBusy) {
            val percent = state.sliceProgressPercent
            if (percent == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                CircularProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }
        } else {
            Icon(
                imageVector = if (gcodeAvailable) Icons.Filled.CheckCircle else Icons.Filled.Info,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = sliceStatusLabel(state, detailed),
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (gcodeAvailable && state.warnings.isNotEmpty()) {
            Text(
                text = "${state.warnings.size} warnings",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
            )
        }
    }
}

/**
 * The navigation rail: the four destinations, then the printing session, then
 * the Plate's actions - all in one column, which is the only column a fold has.
 *
 * The session is the state chip, this slice's numbers, and the four values that
 * used to be cards over the plate (layer height, infill, supports, adhesion),
 * each opening the editor that changes it. The buttons write through the same
 * callbacks the phone's action bar uses and follow whichever engine is active:
 * Slice slices with it, Export saves its G-code, Model tools is the same hub.
 */
@Composable
internal fun SessionRail(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    engine: SlicerEngine,
    state: MainUiState,
    gcodeAvailable: Boolean,
    sliceBlockedReason: String?,
    onSlice: () -> Unit,
    onExportGcode: () -> Unit,
    onTools: () -> Unit,
    onSettings: (String, (SlicerSettings) -> SlicerSettings) -> Unit,
    onPrusaSettings: (String, (PrusaSliceSettings) -> PrusaSliceSettings) -> Unit,
    onOrcaSettings: (String, (OrcaSliceSettings) -> OrcaSliceSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = plateSessionSummary(engine, state)
    val values = sessionValues(engine, summary, state, onSettings, onPrusaSettings, onOrcaSettings)
    var editing by remember { mutableStateOf<SessionValueSlot?>(null) }

    // A reason that lasts is worth showing; one that is gone before the eye finds
    // it is a flash. The second kind is real: a restored workspace has Smart
    // Infill validating its package for a moment at launch, which holds Slice and
    // then clears, and the line was appearing and vanishing with it.
    var shownReason by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sliceBlockedReason) {
        if (sliceBlockedReason == null) {
            shownReason = null
            return@LaunchedEffect
        }
        delay(SLICE_REASON_REVEAL_MILLIS)
        shownReason = sliceBlockedReason
    }

    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .width(SessionRailWidth)
                .verticalScroll(rememberScrollState())
                .padding(vertical = EnderSlicerDimens.Space12),
            verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppTab.entries.forEach { tab ->
                RailDestination(tab, selected == tab) { onSelect(tab) }
            }
            HorizontalDivider(
                modifier = Modifier.padding(
                    horizontal = EnderSlicerDimens.Space8,
                    vertical = EnderSlicerDimens.Space8,
                ),
            )
            SessionChip(
                text = when {
                    state.isBusy -> "Slicing…"
                    gcodeAvailable -> "Ready"
                    else -> "Not sliced"
                },
                color = if (state.isBusy || gcodeAvailable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            // What the Print session card used to carry: this slice's own numbers.
            state.layerPreview?.let { preview ->
                SessionChip(preview.layers.size.toString() + " layers")
            }
            state.estimatedPrintSeconds?.takeIf { gcodeAvailable }?.let { seconds ->
                SessionChip(formatPrintTime(seconds))
            }
            state.warnings.takeIf { it.isNotEmpty() }?.let { warnings ->
                SessionChip(warnings.size.toString() + " warnings", MaterialTheme.colorScheme.error)
            }
            shownReason?.let { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = EnderSlicerDimens.Space8),
                )
            }
            // The three actions the panel on the right used to carry. They are
            // engine-agnostic on purpose: the Slice callback slices with whichever
            // engine is active, and Export saves that engine's G-code.
            Button(
                onClick = onSlice,
                enabled = state.engineAvailable &&
                    state.modelPath != null &&
                    !state.isBusy &&
                    sliceBlockedReason == null,
                contentPadding = PaddingValues(
                    horizontal = EnderSlicerDimens.Space4,
                    vertical = EnderSlicerDimens.Space4,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EnderSlicerDimens.Space8),
            ) {
                RailButtonLabel(if (gcodeAvailable) "Slice again" else "Slice")
            }
            OutlinedButton(
                onClick = onExportGcode,
                enabled = gcodeAvailable && !state.isBusy,
                contentPadding = PaddingValues(
                    horizontal = EnderSlicerDimens.Space4,
                    vertical = EnderSlicerDimens.Space4,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EnderSlicerDimens.Space8),
            ) {
                RailButtonLabel("Export")
            }
            OutlinedButton(
                onClick = onTools,
                enabled = state.mesh != null && !state.isBusy,
                contentPadding = PaddingValues(
                    horizontal = EnderSlicerDimens.Space4,
                    vertical = EnderSlicerDimens.Space4,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EnderSlicerDimens.Space8),
            ) {
                RailButtonLabel("Model tools")
            }

            // Stacked rather than laid out as a row: 80dp cannot hold "Layer
            // height 0,20 mm" on one line, and this is Material's rail width.
            SessionValueTile(values.layerHeight, enabled = !state.isBusy) {
                editing = SessionValueSlot.LAYER_HEIGHT
            }
            SessionValueTile(values.infill, enabled = !state.isBusy) { editing = SessionValueSlot.INFILL }
            SessionValueTile(values.supports, enabled = !state.isBusy) { editing = SessionValueSlot.SUPPORTS }
            SessionValueTile(values.adhesion, enabled = !state.isBusy) { editing = SessionValueSlot.ADHESION }

        }
    }

    // The slot, not the value: the dialog reads it again on every recomposition,
    // so a choice that applies at once moves the check mark while it stays open.
    editing?.let { slot -> SessionValueDialog(values[slot]) { editing = null } }
}

/** Material's own rail width: the destinations and the session share one column. */
internal val SessionRailWidth = 80.dp

/**
 * How long a blocked-slice reason has to stand before the rail shows it.
 *
 * Long enough that a launch-time validation, which holds Slice for a fraction of
 * a second, never draws a red line that immediately disappears; short enough
 * that a reason the user has to act on is there by the time they look.
 */
private const val SLICE_REASON_REVEAL_MILLIS = 600L

/**
 * A rail button's label: centred, wrapped and full width.
 *
 * The rail is 80dp, so "Model tools" takes two lines. Left to itself a wrapped
 * label hugs the leading edge of a button it no longer fills, which reads as a
 * broken button; this centres each line and lets the button centre the block.
 */
@Composable
private fun RailButtonLabel(text: String) {
    Text(
        text = text,
        maxLines = 2,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** One destination, drawn the way the suite's own rail draws it. */
@Composable
private fun RailDestination(tab: AppTab, selected: Boolean, onSelect: () -> Unit) {
    val color = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EnderSlicerDimens.Space8)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onSelect)
            .padding(vertical = EnderSlicerDimens.Space6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space4),
    ) {
        Icon(tab.icon, contentDescription = null, tint = color)
        Text(tab.shortLabel, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun SessionChip(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = color,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun ViewerModeButton(
    label: String,
    mode: ViewerMode,
    selected: ViewerMode,
    enabled: Boolean,
    onSelected: (ViewerMode) -> Unit,
) {
    val content = @Composable { Text(label, style = MaterialTheme.typography.labelMedium) }
    if (selected == mode) {
        Button(
            onClick = { onSelected(mode) },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
        ) { content() }
    } else {
        OutlinedButton(
            onClick = { onSelected(mode) },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
        ) { content() }
    }
}

/**
 * Phone bottom action block: the Slice button, Export, the Model tools entry
 * and the export hint.
 *
 * [expanded] is hoisted to the root of [EnderSlicerApp] under
 * "plate-action-bar-expanded" for the same reason the floating cards hoist
 * theirs: selecting another tab disposes the Plate subtree, so a flag
 * remembered inside the block would be lost on the way back. Collapsed, the
 * primary row is all that is left - the Slice button and the chevron - so the
 * plate gets back the height of the Export button, the Model tools row and the
 * hint, while the slice state, the callbacks and the enabled rules are the
 * ones the expanded block has always used.
 */
@Composable
private fun ActionBar(
    state: MainUiState,
    nonPlanarEnabled: Boolean,
    conicalEnabled: Boolean,
    sliceBlockedReason: String?,
    onSlice: () -> Unit,
    onExportGcode: () -> Unit,
    onTools: () -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val gcodeAvailable = state.hasCurrentGcode()
    Surface(tonalElevation = 4.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (expanded) {
                // The blocked reason and the estimate describe the rows below
                // them, so they fold away with the rest: collapsed is one slim
                // row.
                sliceBlockedReason?.let { reason ->
                    Text(reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                state.estimatedPrintSeconds?.takeIf { gcodeAvailable }?.let { seconds ->
                    Text(
                        "Estimated print time: ${formatPrintTime(seconds)}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isBusy) CircularProgressIndicator(modifier = Modifier.height(28.dp))
                Button(
                    onClick = onSlice,
                    enabled = state.engineAvailable && state.modelPath != null && !state.isBusy && sliceBlockedReason == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when {
                            state.isBusy -> "Working…"
                            nonPlanarEnabled -> "Slice non-planar"
                            conicalEnabled -> "Slice conical"
                            else -> "Slice"
                        },
                    )
                }
                if (expanded) {
                    OutlinedButton(
                        onClick = onExportGcode,
                        enabled = gcodeAvailable && !state.isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Export G-code")
                    }
                }
                // The collapse control rides in the row that survives, so the
                // same tap target folds and unfolds the block.
                IconButton(onClick = onToggle) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse actions" else "Expand actions",
                    )
                }
            }
            if (expanded) {
                OutlinedButton(
                    onClick = onTools,
                    enabled = state.mesh != null && !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Model tools · move, rotate, scale, paint")
                }
                if (!gcodeAvailable) {
                    Text(
                        "Slice a model first to export validated G-code",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }
}

@Composable
private fun SupportPaintOverlay(
    activeMode: SupportPaintMode,
    onPaintMode: (SupportPaintMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(Unit) {
        onDispose { onPaintMode(SupportPaintMode.NONE) }
    }
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Support painting", style = MaterialTheme.typography.titleSmall)
            Text(
                "Tap Draw, Block or Erase, then drag on the model. Use two fingers to rotate and zoom.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaintModeButton("Draw", SupportPaintMode.ENFORCER, activeMode, onPaintMode, Modifier.weight(1f))
                PaintModeButton("Block", SupportPaintMode.BLOCKER, activeMode, onPaintMode, Modifier.weight(1f))
                PaintModeButton("Erase", SupportPaintMode.ERASE, activeMode, onPaintMode, Modifier.weight(1f))
            }
            OutlinedButton(onClick = onClose) { Text("Stop painting") }
        }
    }
}

@Composable
private fun PaintModeButton(
    label: String,
    mode: SupportPaintMode,
    activeMode: SupportPaintMode,
    onPaintMode: (SupportPaintMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (activeMode == mode) {
        Button(onClick = { onPaintMode(SupportPaintMode.NONE) }, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = { onPaintMode(mode) }, modifier = modifier) { Text(label) }
    }
}

/** Annotation tool callbacks, grouped so the viewer signature stays readable. */
private class AnnotationActions(
    val onLockSegment: () -> Unit,
    val onLockSeries: () -> Unit,
    val onUndo: () -> Unit,
    val onClear: () -> Unit,
    val onSave: () -> Unit,
    val onThickness: (Float) -> Unit,
    val onPlaneZ: (Float) -> Unit,
)

/**
 * Point-to-point annotation toolbar.
 *
 * Tap to place an end, drag an end to adjust it, and drag anywhere else to
 * orbit. Keeping one-finger orbit available is the point: judging whether a
 * point sits on the surface is impossible without turning the model.
 */
@Composable
private fun AnnotationToolbar(
    state: MainUiState,
    actions: AnnotationActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Leaves annotation mode whenever the toolbar does, so a tab change cannot
    // strand the viewer capturing single-finger drags that should rotate.
    DisposableEffect(Unit) {
        onDispose { onClose() }
    }
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(
                horizontal = EnderSlicerDimens.SheetPadding,
                vertical = EnderSlicerDimens.Space8,
            ),
            verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space4),
        ) {
            // One dense line carries the state, the measurements and the way out.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        state.annotationCanLockSegment -> "Drag an end, then Lock"
                        state.annotationAwaitingSecondPoint -> "Tap the other end"
                        state.annotationSeriesPoints > 0 -> "Tap to continue"
                        else -> "Tap to start"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.width(EnderSlicerDimens.Space8))
                Text(
                    text = buildString {
                        state.annotationMeasureMm?.let { append("%.1f".format(it) + " mm") }
                        state.annotationChainMm?.takeIf { it > 0f }?.let {
                            if (isNotEmpty()) append("  ·  ")
                            append("series %.1f".format(it))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                TextButton(
                    onClick = onClose,
                    contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                ) { Text("Stop") }
            }

            CompactSliderRow(
                label = "Width",
                value = state.annotationThicknessPx,
                range = AnnotationState.MIN_THICKNESS_PX..AnnotationState.MAX_THICKNESS_PX,
                onValueChange = actions.onThickness,
            )
            CompactSliderRow(
                label = "Height",
                value = state.annotationWorkPlaneZ,
                range = state.annotationZMin..state.annotationZMax.coerceAtLeast(state.annotationZMin + 1f),
                onValueChange = actions.onPlaneZ,
                valueText = if (state.annotationZAdjusting) {
                    "%.1f".format(state.annotationWorkPlaneZ)
                } else {
                    null
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space6)) {
                Button(
                    onClick = actions.onLockSegment,
                    enabled = state.annotationCanLockSegment,
                    contentPadding = PaddingValues(vertical = EnderSlicerDimens.Space4),
                    modifier = Modifier.weight(1f),
                ) { Text("Lock line", maxLines = 1) }
                OutlinedButton(
                    onClick = actions.onLockSeries,
                    enabled = state.annotationCanLockSeries,
                    contentPadding = PaddingValues(vertical = EnderSlicerDimens.Space4),
                    modifier = Modifier.weight(1f),
                ) { Text("End series", maxLines = 1) }
                OutlinedButton(
                    onClick = actions.onUndo,
                    contentPadding = PaddingValues(vertical = EnderSlicerDimens.Space4),
                    modifier = Modifier.weight(1f),
                ) { Text("Undo", maxLines = 1) }
                OutlinedButton(
                    onClick = actions.onSave,
                    enabled = state.annotationChainCount > 0,
                    contentPadding = PaddingValues(vertical = EnderSlicerDimens.Space4),
                    modifier = Modifier.weight(1f),
                ) { Text("Save", maxLines = 1) }
            }
        }
    }
}

/**
 * A labelled slider on one dense line.
 *
 * Material's Slider carries 48dp of vertical padding for a comfortable target,
 * which is most of a toolbar when two of them stack. The touch target is kept
 * here by the row height rather than by the slider's own padding.
 */
@Composable
private fun CompactSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    valueText: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier
                .weight(1f)
                .height(32.dp),
        )
        valueText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .width(44.dp)
                    .padding(start = EnderSlicerDimens.Space6),
                maxLines = 1,
            )
        }
    }
}
