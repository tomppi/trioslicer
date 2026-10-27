package com.tomppi.enderslicer.ui

import android.util.Log
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import com.tomppi.enderslicer.cadagent.AgentException
import com.tomppi.enderslicer.cadagent.AgentMode
import com.tomppi.enderslicer.cadagent.AgentQuestion
import com.tomppi.enderslicer.cadagent.AgentRunbook
import com.tomppi.enderslicer.cadagent.AgentSettings
import com.tomppi.enderslicer.cadagent.AgentSettingsStore
import com.tomppi.enderslicer.cadagent.AgentToolCatalog
import com.tomppi.enderslicer.cadagent.AgentToolTrace
import com.tomppi.enderslicer.cadagent.AgentView
import com.tomppi.enderslicer.cadagent.CadAgent
import com.tomppi.enderslicer.cadagent.PlateImageSource
import com.tomppi.enderslicer.cadagent.InAppTurnKeeper
import com.tomppi.enderslicer.cadagent.StoredAgentSettings
import com.tomppi.enderslicer.conical.ConicalSettingsStore
import com.tomppi.enderslicer.engine.GcodeDialect
import com.tomppi.enderslicer.harness.EngineMode
import com.tomppi.enderslicer.harness.HarnessChat
import com.tomppi.enderslicer.harness.HarnessClient
import com.tomppi.enderslicer.harness.HarnessConfig
import com.tomppi.enderslicer.harness.HarnessConfigStore
import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.AllSettingsCatalogs
import com.tomppi.enderslicer.profile.AllSettingsGroups
import com.tomppi.enderslicer.profile.CuraSettingDelta
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
import com.tomppi.enderslicer.viewer.ModelCutPlane
import com.tomppi.enderslicer.viewer.ModelSurfaceView
import com.tomppi.enderslicer.viewer.PlateImage
import com.tomppi.enderslicer.viewer.PlateImageCodec
import com.tomppi.enderslicer.viewer.PreviewMemoryProfile
import com.tomppi.enderslicer.viewer.SnapFacing
import com.tomppi.enderslicer.viewer.SnapFit
import com.tomppi.enderslicer.viewer.SnapFitParameters
import com.tomppi.enderslicer.viewer.SnapJoint
import com.tomppi.enderslicer.viewer.SnapJointStyle
import com.tomppi.enderslicer.viewer.SnapLibrary
import com.tomppi.enderslicer.viewer.SnapLibraryShape
import com.tomppi.enderslicer.viewer.SnapLip
import com.tomppi.enderslicer.viewer.SnapTightness
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.Vec3
import com.tomppi.enderslicer.viewer.StlParser
import com.tomppi.enderslicer.viewer.ViewerOrientation
import android.content.ClipboardManager
import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.material.icons.filled.Lock
import com.tomppi.enderslicer.modelling.EnginePreviewClient
import com.tomppi.enderslicer.nativebridge.BlenderEngine
import com.tomppi.enderslicer.modelling.CadPick
import com.tomppi.enderslicer.modelling.CadPreviewClient
import com.tomppi.enderslicer.modelling.CadViewerPreference
import com.tomppi.enderslicer.modelling.CadViewerSettings
import com.tomppi.enderslicer.engine.EnginePreparation
import com.tomppi.enderslicer.modelling.CadViewport
import com.tomppi.enderslicer.nativebridge.CadEngine
import com.tomppi.enderslicer.agent.AgentBridgeCopy
import com.tomppi.enderslicer.agent.AgentBridgeTarget
import com.tomppi.enderslicer.agent.AgentBridgeTokens
import com.tomppi.enderslicer.solidcraft.SelectedFace
import com.tomppi.enderslicer.solidcraft.SolidCraftAgentHost
import com.tomppi.enderslicer.solidcraft.rewordedFor
import com.tomppi.enderslicer.solidcraft.SolidCraftEngine
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import com.tomppi.enderslicer.viewer.TransformGizmoMode
import java.io.File
import kotlin.math.roundToInt
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
    // The destination offers both, and the built-in host is the default: it said
    // "OctoPrint session" while showing a printer this device was driving itself.
    PRINT("Print", "Print", { "Phone Klipper, PC Klipper, or OctoPrint" }),
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
    uiScalePercent: Int = UiScale.DEFAULT_PERCENT,
    onUiScaleChange: (percent: Int, commit: Boolean) -> Unit = { _, _ -> },
    sliceBlockedReason: String? = null,
    plateOverflowItems: @Composable (() -> Unit) -> Unit = { _ -> },
    moreExtraItems: @Composable (onShowPlate: () -> Unit) -> Unit = {},
    printTabContent: @Composable () -> Unit = {},
    plateOverlayContent: @Composable BoxScope.() -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val solidCraftSheetOpen by viewModel.solidCraftSheetOpen.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.PLATE) }

    // The end of a live repaint: the first frame that draws a new display revision. The
    // latency reported for an agent edit runs from the call's answer to this frame.
    val cadDisplayRevision = state.cadDocument?.displayRevision
    LaunchedEffect(cadDisplayRevision) {
        val revision = cadDisplayRevision ?: return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        viewModel.onCadFrame(revision)
    }


    // A failure lands in the corner of the model viewer, and that corner is on
    // the Plate. Everything the app does from another tab - importing a
    // configuration snapshot, most of all - therefore reported into a surface
    // the user was not looking at, which is how "importing an unusable snapshot
    // is a silent no-op" survived a fix that gave it a log line. Anywhere but
    // the Plate, the app says it out loud instead.
    val latestAlert by Diagnostics.latestAlert.collectAsStateWithLifecycle()
    var announcedAlertAt by remember { mutableStateOf(0L) }
    LaunchedEffect(latestAlert, selectedTab) {
        val entry = latestAlert ?: return@LaunchedEffect
        if (selectedTab == AppTab.PLATE || entry.atMillis == announcedAlertAt) return@LaunchedEffect
        announcedAlertAt = entry.atMillis
        Toast.makeText(
            context,
            (if (entry.level == Diagnostics.Level.FAILURE) "Failed: " else "Warning: ") + entry.message,
            Toast.LENGTH_LONG,
        ).show()
    }
    var importMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var plateOverflowExpanded by rememberSaveable { mutableStateOf(false) }
    var profilesOpen by rememberSaveable { mutableStateOf(false) }
    var printerScreenOpen by rememberSaveable { mutableStateOf(false) }
    val printerChecklistStore = remember(context) { PrinterChecklistStore(context.applicationContext) }
    var printerChecklistDone by remember(printerChecklistStore) { mutableStateOf(printerChecklistStore.load()) }
    var modelToolsOpen by rememberSaveable { mutableStateOf(false) }
    var multiObjectOpen by rememberSaveable { mutableStateOf(false) }
    // Dragging the model across the plate is a mode on the viewer, held here
    // because the Transform panel that turns it on is not the plate itself.
    var modelDragMove by rememberSaveable { mutableStateOf(false) }
    // The on-model gizmo: which mode long-pressing the model put up, where it
    // was asked for, the value it is reporting, and the scale the slider holds.
    var gizmoMode by rememberSaveable { mutableStateOf(TransformGizmoMode.NONE) }
    var gizmoScalePercent by rememberSaveable { mutableStateOf(100) }
    var gizmoReadout by remember { mutableStateOf<String?>(null) }
    var gizmoHandleDrag by remember { mutableStateOf(false) }
    var supportPaintUiOpen by rememberSaveable { mutableStateOf(false) }
    var annotationUiOpen by rememberSaveable { mutableStateOf(false) }
    // Hoisted out of the layout branches: the gesture help folds away for good
    // once dismissed, and a flag remembered inside a branch starts over when the
    // device folds or unfolds and that branch leaves the composition.
    var modelHintsDismissed by rememberSaveable { mutableStateOf(false) }
    var layerEventsOpen by rememberSaveable { mutableStateOf(false) }
    var meshLimitOpen by rememberSaveable { mutableStateOf(false) }
    var uiScaleOpen by rememberSaveable { mutableStateOf(false) }
    var diagnosticsLogOpen by rememberSaveable { mutableStateOf(false) }
    val diagnosticsOn by Diagnostics.enabled.collectAsStateWithLifecycle()
    var nonPlanarOpen by rememberSaveable { mutableStateOf(false) }
    var allSettingsOpen by rememberSaveable { mutableStateOf(false) }
    // The sidebar picker, raised by any engine's own sheet: one dialog, following
    // whichever engine would slice.
    var sidebarPickerOpen by rememberSaveable { mutableStateOf(false) }
    var conicalOpen by rememberSaveable { mutableStateOf(false) }
    var blenderMenuExpanded by rememberSaveable { mutableStateOf(false) }
    // The CAD menu, beside Blender's. Two menus rather than one with a mode switch,
    // because the menu the user opens is the thing that tells the agent which engine it
    // is working with - an entry inside the Blender menu could not say that.
    var cadMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var blenderFilesOpen by rememberSaveable { mutableStateOf(false) }
    // The CAD engine's exports accumulate the same way Blender's do - every render it writes
    // lands in the same folder as every model - so they get the same screen.
    var cadFilesOpen by rememberSaveable { mutableStateOf(false) }
    // The SolidCraft workspace's designs (the engine's own .solidcraft documents) get a screen
    // of their own, like Blender's exports: load one onto the plate, or delete one.
    var solidCraftFilesOpen by rememberSaveable { mutableStateOf(false) }
    // The SolidCraft menu, beside Blender's and CAD's: the designs, the plate's Convert press,
    // and the engine that owns the live document.
    var solidCraftMenuExpanded by rememberSaveable { mutableStateOf(false) }
    // The menu's engine line, polled so it tells the truth when the sheet is not open.
    var solidCraftEngineLine by remember { mutableStateOf("Engine: not running") }
    var aiChatOpen by rememberSaveable { mutableStateOf(false) }
    // The floating chat's size, remembered for the session: a chat left full screen reopens full
    // screen, one left as a panel reopens as a panel, and the bubble is a size - not a close.
    var chatSize by rememberSaveable { mutableStateOf(ChatSize.PANEL) }
    var chatExpandedSize by rememberSaveable { mutableStateOf(ChatSize.PANEL) }
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
    // The phone's sidebar panel. Expanding it folds the action bar away, and
    // bringing the bar back folds it; each remembers its own state, and only one of
    // the two is expanded at a time.
    var plateRailOpen by rememberSaveable(key = "plate-session-rail-open") { mutableStateOf(false) }
    val platePanels = PlatePanelState(plateRailOpen, plateActionBarExpanded)
    // One place the two flags move, so they cannot both be expanded at once.
    val showPlatePanels: (PlatePanelState) -> Unit = { next ->
        plateRailOpen = next.railOpen
        plateActionBarExpanded = next.actionBarExpanded
    }
    // The harness connect attempt is session-scoped, and two taps must not race; the
    // conversations themselves live in CadConversations (CAD), viewModel.modellingMessages (Blender
    // modelling) and the harness's own session.
    var aiBusy by remember { mutableStateOf(false) }
    // The harness connection outlives a rotation: a composed remember was discarded by the
    // activity recreate, so the harness chat looked unconnected (and its turns were never
    // re-adopted). These are saveable strings/flags, not the sessions themselves - the chats are
    // re-adopted from their stored session ids on the new composition.
    var aiAddress by rememberSaveable { mutableStateOf("") }
    var aiWorkspace by rememberSaveable { mutableStateOf("") }
    var aiConfigured by rememberSaveable { mutableStateOf(false) }
    // True while the connected harness is reached over plain HTTP to another
    // machine, so the panel can keep saying so rather than only warning once.
    var aiInsecure by rememberSaveable { mutableStateOf(false) }
    // Address waiting for the user to accept an unencrypted connection.
    var aiCleartextPrompt by rememberSaveable { mutableStateOf<String?>(null) }

    // Modelling from scratch is its own conversation with its own agent: it
    // shares the harness, the Blender engine and nothing else with the
    // image-to-model chat, whose transcript is about somebody's photograph.
    var modellingOpen by rememberSaveable { mutableStateOf(false) }
    var modellingOwner by remember { mutableStateOf(CameraOwner.AGENT) }
    var modellingCamera by remember { mutableStateOf<ModellingCamera?>(null) }
    var modellingBootstrapped by rememberSaveable { mutableStateOf(false) }
    // The standing brief is owed from the first visit to the modelling screen,
    // but the connection that screen starts in the same frame is asynchronous:
    // the brief waits here until a chat exists to carry it.
    var modellingIntroPending by remember { mutableStateOf(false) }
    // True once a modelling chat has been adopted. Read only to know when the
    // deferred brief can be delivered.
    var modellingChatReady by remember { mutableStateOf(false) }
    val modellingChat = remember { java.util.concurrent.atomic.AtomicReference<HarnessChat?>(null) }

    // The CAD conversations, one per backend and never mixed: the in-app agent's own messages
    // (tool rows included) and the harness session's messages. See [CadConversations].
    // The plate's own GL view. The agent's plate_screenshot tool renders the picture the user is
    // looking at from it - the app's renderer, the user's camera, never the engine's screenshot.
    var plateView by remember { mutableStateOf<ModelSurfaceView?>(null) }
    // The user's camera, kept with the plate state: the agent's plate render and the face
    // selection's plain-words description both need the orientation the user is looking through.
    var modelOrientation by rememberSaveable(stateSaver = ViewerOrientationSaver) {
        mutableStateOf<ViewerOrientation?>(null)
    }

    // The CAD screen is the harness CAD session's view; it opens on its own.
    var cadOpen by rememberSaveable { mutableStateOf(false) }
    // How this device draws the CAD viewport. Read once per composition and handed to the
    // viewport as a lambda, so a saved change reaches the very next frame.
    var cadViewerOpen by rememberSaveable { mutableStateOf(false) }
    var cadViewerSettings by remember { mutableStateOf(CadViewerPreference.load(context)) }
    var cadChatReady by remember { mutableStateOf(false) }
    var cadEngineStatus by remember { mutableStateOf("CAD engine not started") }
    // True while the engine is unpacking or starting, so the empty view spins only when
    // something is actually happening; a failure keeps the same view without it.
    var cadEngineBusy by remember { mutableStateOf(false) }
    // True when the engine is up and its scene is genuinely empty: the panel that offers
    // the default part back.
    var cadSceneEmpty by remember { mutableStateOf(false) }
    // The first-init screen, reachable from Settings after onboarding: an engine added later,
    // or a failure to retry, is prepared from here.
    var engineSetupOpen by rememberSaveable { mutableStateOf(false) }
    var enginePrepSelection by rememberSaveable { mutableStateOf("") }
    // The engine's latest render. It has a GL viewer and no window, so its picture of the
    // model arrives as a file - the same handoff that carries STEP and STL.
    var cadRender by remember { mutableStateOf<java.io.File?>(null) }
    // The engine's most recent STL, loaded for the viewer. A render is the engine's
    // chosen angle; the mesh is the part, and the user can turn it to the side they
    // actually want to see.
    // The engine's own viewport. The app does not draw the model at all; it asks the
    // engine to render and shows the frame that comes back.
    var cadViewport by remember { mutableStateOf<CadViewport?>(null) }
    var cadFrame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var cadFramePending by remember { mutableStateOf(false) }
    var cadPick by remember { mutableStateOf<CadPick?>(null) }
    val cadChat = remember { java.util.concurrent.atomic.AtomicReference<HarnessChat?>(null) }
    // The CAD document's author can be this phone's own process: an OpenAI-compatible endpoint
    // called directly from the app, with the engine's tools run in process. In-app is the
    // default; the harness above stays as the advanced path. Each backend owns its own
    // conversation (CadConversations) - the same source switch chooses which one is shown and
    // which one the sheet's ask box drives.
    val agentSettingsStore = remember(context) { AgentSettingsStore(context.applicationContext) }
    var agentSettings by remember { mutableStateOf(agentSettingsStore.loadSettings()) }
    // Who may edit the document, for the floating chat's header: polled only while that chat is
    // open, so an external agent that attached reads there as "in-app agent paused".
    var chatAuthorship by remember { mutableStateOf(SolidCraftEngine.authorship().state()) }

    // The sheet's mode chips and the chat's source switch are one selection; the ViewModel holds
    // it, and the persisted mode is kept in step so a rotation restores the same source.
    LaunchedEffect(agentSettings.mode) {
        viewModel.chatSource = agentSettings.mode.chatSource()
    }
    // A finished CAD turn asks the plate for a fresh frame and scene read through a ViewModel
    // tick, not a composition callback: the composition that started the turn may have been
    // recreated by a rotation before the answer arrived.
    LaunchedEffect(viewModel.cadRefreshTick) {
        cadViewport?.refresh()
        cadViewport?.refreshSceneState()
    }

    LaunchedEffect(aiChatOpen) {
        if (!aiChatOpen) return@LaunchedEffect
        // Reopening never lands on the bubble: minimising is not closing, and the bubble is a
        // state you leave by tapping it - back to the size the user was at.
        chatSize = ChatSizes.reopened(chatSize, chatExpandedSize)
        while (true) {
            chatAuthorship = SolidCraftEngine.authorship().state()
            delay(500)
        }
    }

    // The question notification is only right while the app is in the background: in the
    // foreground the rail's Ask AI pulse is the attention, and a notification would be noise.
    val appLifecycle = LocalLifecycleOwner.current.lifecycle
    var appResumed by remember {
        mutableStateOf(appLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(appLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> appResumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> appResumed = false
                else -> Unit
            }
        }
        appLifecycle.addObserver(observer)
        onDispose { appLifecycle.removeObserver(observer) }
    }
    // The question notification is attention for a question the user cannot see yet. It is
    // posted only while the app is in the background, and whatever source the chat was last
    // left on: the question exists only in the in-app conversation, so a chat open on the
    // Harness pane must not silence it. In the foreground the rail's Ask AI badge is the
    // attention instead. A question that is gone removes the notification, so it can never
    // advertise a wait the app no longer holds. MainActivity cancels it too at a cold start,
    // before any of this composition exists.
    LaunchedEffect(viewModel.pendingQuestion, appResumed) {
        val waiting = viewModel.pendingQuestion
        if (waiting != null && !appResumed) {
            CadQuestionNotification.post(context, waiting)
        } else {
            CadQuestionNotification.cancel(context)
        }
    }
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
    val harnessStore = remember(context) { HarnessConfigStore(context.applicationContext) }
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
            viewModel.harnessCadStatus = null
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
                viewModel.modellingMessages = withContext(Dispatchers.IO) { modelling.messages() }
                    .map { AiChatMessage(fromUser = it.fromUser, text = it.text) }
                // And a third for CAD, on its own session for the same reason.
                val cad = HarnessChat(client, workspace)
                val adoptedCad = withContext(Dispatchers.IO) {
                    cad.connect(harnessStore.loadCadSession())
                }
                cadChat.set(cad)
                cadChatReady = true
                harnessStore.saveCadSession(adoptedCad.sessionId)
                viewModel.cadConversations = viewModel.cadConversations.replaceHarness(
                    withContext(Dispatchers.IO) { cad.messages() }
                        .map { AiChatMessage(fromUser = it.fromUser, text = it.text) },
                )
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
                // A turn that outlived the activity is still running on the
                // harness, and nothing else reads its reply back: pick the
                // modelling and CAD conversations up again instead of losing the
                // answers to a rotation and starting a second generation on the
                // next send.
                resumeReplyIfRunning(
                    modelling,
                    { viewModel.modellingMessages = it },
                    { viewModel.modellingStatus = it },
                ) { viewModel.modellingBusy = it }
                // And the CAD conversation, for the same reason: a turn that outlived the
                // activity is still running on the harness and nothing else reads its reply back.
                resumeReplyIfRunning(
                    cad,
                    { viewModel.cadConversations = viewModel.cadConversations.replaceHarness(it) },
                    { viewModel.harnessCadStatus = it },
                ) { viewModel.harnessCadBusy = it }
                // The harness source's own status line: its first connection shows here, and
                // it is the same "Harness - ..." state the header reports.
                viewModel.harnessCadStatus = when {
                    !adopted.reused -> "Started a new harness conversation"
                    workspace.isBlank() -> "No workspace set - the agent cannot see your skills"
                    else -> null
                }
            } catch (error: Throwable) {
                viewModel.harnessCadStatus = error.message?.take(200) ?: "Could not reach the harness"
                aiConfigured = false
                aiInsecure = false
            } finally {
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
            viewModel.modellingBusy = true
            viewModel.modellingStatus = null
            try {
                val chat = modellingChat.get() ?: error("Connect to the harness first")
                // The modelling screen is Blender's. A CAD screen sends CAD.prompt instead.
                withContext(Dispatchers.IO) { chat.send(EngineMode.BLENDER.prompt(text)) }
                viewModel.modellingMessages = viewModel.modellingMessages + AiChatMessage(fromUser = true, text = text)
                pollForReply(chat, { viewModel.modellingMessages = it }, { viewModel.modellingStatus = it })
            } catch (error: Throwable) {
                viewModel.modellingStatus = error.message?.take(160) ?: "Harness call failed"
            } finally {
                viewModel.modellingBusy = false
            }
        }
    }

    /**
     * Sends to the CAD agent from the CAD menu.
     *
     * The prompt carries the engine, because the agent cannot see which menu the user is
     * standing in and "make the wall 2 mm thicker" reads the same for a mesh and a solid.
     * What the transcript shows is the user's own words.
     */
    fun askCadAgent(text: String) {
        aiScope.launch {
            viewModel.harnessCadBusy = true
            viewModel.harnessCadStatus = null
            try {
                val chat = cadChat.get() ?: error("Connect to the harness first")
                // The agent is told which surface the engine named, not a coordinate to
                // resolve: the pick already came back as the exact B-rep face. The user
                // sees their own words, not this preamble.
                val pick = cadPick
                val outgoing = if (pick == null) {
                    text
                } else {
                    // The engine named the surface, so tell the agent what it named rather than
                    // making it resolve a point. It is already the exact B-rep face.
                    pick.asPrompt(pick.x, pick.y) + text
                }
                withContext(Dispatchers.IO) { chat.send(EngineMode.CAD.prompt(outgoing)) }
                viewModel.cadConversations = viewModel.cadConversations.appendHarness(
                    AiChatMessage(fromUser = true, text = text),
                )
                cadPick = null
                pollForReply(
                    chat,
                    { viewModel.cadConversations = viewModel.cadConversations.replaceHarness(it) },
                    { viewModel.harnessCadStatus = it },
                )
            } catch (error: Throwable) {
                viewModel.harnessCadStatus = error.message?.take(160) ?: "Harness call failed"
            } finally {
                viewModel.harnessCadBusy = false
                // The turn is over and the agent has most likely changed the scene, so ask for a
                // frame. Nothing else did: a command that added or moved a part left the screen
                // showing the model as it was before, and the only way to see the new one was to
                // poke the view - which in an empty scene was not even composed to be poked.
                cadViewport?.refresh()
                // And ask what is in the scene now: an agent that cleared it must leave the
                // screen offering the default part rather than a grid with nothing on it.
                cadViewport?.refreshSceneState()
            }
        }
    }

    /**
     * The face the next message carries, worded for the camera the user is looking through now.
     *
     * The selection is a face, not a screen position: an orbit keeps it, and only the plain-word
     * description ("the face facing you") follows the camera. Recomputed when the message is
     * built, so the block never says "top" about a face the user has since orbited to the side.
     */
    fun faceForNextMessage(): SelectedFace? {
        val current = viewModel.uiState.value
        val face = current.selectedFace ?: return null
        val objectId = current.cadDocument?.parts
            ?.firstOrNull { it.body == face.body }
            ?.objectId
            ?: return face
        val plateObject = current.models.firstOrNull { it.id == objectId } ?: return face
        val map = plateObject.placement.documentPlacement(plateObject.sourceMesh)
        return face.rewordedFor(map, modelOrientation)
    }

    /**
     * The plate as the agent will see it: the frame at the user's current camera, the selected
     * face's amber tint included, encoded small enough to travel in a chat message.
     *
     * [view] is passed in rather than read from the composition here because this runs on the
     * tool thread during a turn; the view is captured when the message is sent. A null view (the
     * Plate tab is not showing a model) and a plate that is not drawing both answer null, and
     * the tool says so rather than sending a picture that would not be the truth.
     */
    fun platePicture(view: ModelSurfaceView? = plateView): PlateImage? {
        if (view == null) return null
        val current = viewModel.uiState.value
        val bitmap = view.capturePlate() ?: return null
        return PlateImageCodec.encode(
            bitmap = bitmap,
            bodies = current.cadDocument?.bodyCount ?: current.models.size,
            triangles = current.models.sumOf { it.mesh.triangleCount },
        )
    }

    /**
     * Opens face picking from the CAD agent sheet.
     *
     * The sheet closes because the plate's tap is the input from here on, and every other tool
     * that owns the plate's touches closes with it - one tool at a time is what keeps a selection
     * from painting and a stroke from selecting.
     */
    fun beginFaceSelect() {
        viewModel.closeSolidCraftSheet()
        modelToolsOpen = false
        supportPaintUiOpen = false
        annotationUiOpen = false
        // The next input is a tap on the model, so the phone's rail folds away: the plate is the
        // subject and the face chip must not stand under the rail.
        showPlatePanels(platePanels.closedRail())
        viewModel.setAnnotationActive(false)
        viewModel.setCutActive(false)
        viewModel.setSnapActive(false)
        // One tool at a time: a face tap must not be shadowed by a transform gizmo, and a gizmo
        // drag must not take a tap meant for the face chip (audit VIEW-5).
        gizmoMode = TransformGizmoMode.NONE
        gizmoScalePercent = 100
        gizmoReadout = null
        viewModel.startFaceSelectMode()
    }

    /**
     * Runs one turn of the in-app agent against the live engine.
     *
     * The tool catalogue is the engine's own `tools/list` answer, read over its stdio every turn -
     * plus the app's web tools when the endpoint offers them and the user left them on - and every
     * engine call runs in process through [SolidCraftAgentHost], which hands the live view its
     * DocumentDelta so the plate changes as the agent works.
     *
     * The one-author boundary is claimed before any request leaves the phone:
     * [SolidCraftAuthorship.beginInAppTurn] is atomic, so an external connection cannot slip in
     * between the check and an edit, and the bridge refuses new clients while this turn runs.
     */
    fun askInAppAgent(text: String, stored: StoredAgentSettings) {
        // The turn runs in the ViewModel's scope, not this composition's: rotating the phone
        // cancels a rememberCoroutineScope, and a running turn must not be cancelled by a
        // rotation - nor may the rows it already produced be lost. The conversation itself lives
        // in the ViewModel (see MainViewModel.cadConversations).
        viewModel.runChatTurn {
            // A new prompt ends any wait: the pending question is history once the user writes
            // again (the card stays in the transcript as what the agent asked).
            viewModel.clearPendingQuestion()
            // A turn may run for minutes of model time and longer still of tool time; a
            // foreground service keeps this process from being cached and frozen if the user
            // puts the phone down or leaves the app. It holds its own bounded partial wake
            // lock, and ends on every exit path below. No screen-on flag: the screen may sleep.
            InAppTurnKeeper.begin(context.applicationContext)
            viewModel.cadBusy = true
            viewModel.cadStatus = null
            viewModel.inAppWebNotice = viewModel.inAppCadAgent?.webToolsNotice
            viewModel.cadConversations = viewModel.cadConversations.appendInApp(
                AiChatMessage(fromUser = true, text = text),
            )
            val ownership = SolidCraftEngine.authorship()
            var turnBegan = false
            try {
                if (!stored.settings.isConfigured) {
                    throw AgentException(
                        "not-configured",
                        stored.settings.baseUrlProblem()?.let {
                            "The in-app agent is not ready: " + it + "."
                        } ?: "The in-app agent has no model name yet; add one in this sheet.",
                    )
                }
                // The tools are the engine's, so the engine has to be up before a turn. Nothing
                // is started here: Connect agent is the user's button, and an engine that was
                // stopped mid-conversation must read as stopped, not as a dead phone.
                if (!viewModel.attachCadLiveView()) {
                    throw AgentException(
                        "engine-not-running",
                        "The CAD engine is not running - press Connect agent, then ask again.",
                    )
                }
                val session = SolidCraftEngine.session()
                    ?: throw AgentException(
                        "engine-not-running",
                        "The CAD engine is not running - press Connect agent, then ask again.",
                    )
                val catalog = withContext(Dispatchers.IO) {
                    try {
                        AgentToolCatalog.fromEngine(
                            engineTools = session.toolDefinitions(),
                            includeWebTools = stored.settings.webSearch,
                        )
                    } catch (error: com.tomppi.enderslicer.solidcraft.McpException) {
                        throw AgentException(
                            "engine-unavailable",
                            "The CAD engine did not answer its tool list: " +
                                (error.message ?: "no reason given"),
                            error,
                        )
                    }
                }
                val runbook = withContext(Dispatchers.IO) {
                    AgentRunbook.load(context.applicationContext)
                }
                val agent = viewModel.inAppCadAgent ?: CadAgent(
                    toolHost = SolidCraftAgentHost(
                        session = { SolidCraftEngine.session()?.takeIf { SolidCraftEngine.isReady() } },
                        onDocumentChanged = { delta -> SolidCraftEngine.onDocumentChanged?.invoke(delta) },
                        log = { message -> Log.i("CadAgent", message) },
                    ),
                    runbook = runbook,
                    log = { message -> Log.i("CadAgent", message) },
                ).also { viewModel.inAppCadAgent = it }
                // The boundary, claimed once for the whole turn. A false answer means an
                // external agent attached after the sheet's last poll: the sheet's paused state
                // is the way through (Take over), not a silent edit.
                turnBegan = ownership.beginInAppTurn()
                if (!turnBegan) {
                    throw AgentException(
                        "external-agent-attached",
                        ownership.inAppBlockedReason()
                            ?: "An external agent is editing this document; press Take over first.",
                    )
                }
                // What this message carries beyond the user's words: the SELECTED FACE block,
                // and - with the sheet's switch on - a render of the plate with that face tinted.
                // Both are read now, on the UI thread, so the turn carries the state the user
                // sent from.
                val faceForTurn = faceForNextMessage()
                // The plate view is looked up per call, never captured: a rotation recreates
                // the composition and its view while this turn keeps running in the ViewModel's
                // scope, and the captured one would be detached - capturePlate() then answers
                // "no frame" for the rest of the turn.
                val plate = PlateImageSource { platePicture(viewModel.cadPlateView) }
                val reply = withContext(Dispatchers.IO) {
                    agent.run(
                        settings = stored,
                        catalog = catalog,
                        userText = text,
                        view = AgentView(
                            selectionBlock = faceForTurn?.promptBlock().orEmpty(),
                            plate = plate,
                            attachPlateToSelection = stored.settings.lendView,
                            faceTinted = faceForTurn != null,
                        ),
                        // These callbacks arrive on the agent's background thread after the
                        // turn started; they post through the ViewModel's scope, not the
                        // composition's, so a rotation cannot drop a row.
                        onActivity = { activity ->
                            // Plain words for the sheet's compact transcript, which has no rows
                            // of its own; the floating chat shows the tool rows instead.
                            viewModel.postChatUpdate {
                                viewModel.cadConversations = viewModel.cadConversations.appendInApp(
                                    AiChatMessage(
                                        fromUser = false,
                                        text = activity,
                                        kind = AiChatEntryKind.ACTIVITY,
                                    ),
                                )
                            }
                        },
                        onToolTrace = { trace -> viewModel.postChatUpdate { viewModel.applyCadToolTrace(trace) } },
                        // The endpoint went quiet before any tool ran and the call is about to be
                        // re-sent once: say so in the transcript rather than looking frozen.
                        onRetry = { notice ->
                            viewModel.postChatUpdate {
                                viewModel.cadConversations = viewModel.cadConversations.appendInApp(
                                    AiChatMessage(fromUser = false, text = notice),
                                )
                            }
                        },
                        // The model could not see the picture and the request went again as text:
                        // one line, once, in the transcript - never a claim that it saw a render.
                        onVisionNotice = { notice ->
                            viewModel.postChatUpdate {
                                viewModel.cadConversations = viewModel.cadConversations.appendInApp(
                                    AiChatMessage(fromUser = false, text = notice),
                                )
                            }
                        },
                        // A render was attached: named by its size and dimensions, so a copied
                        // transcript says which picture went without carrying its bytes.
                        onImageAttached = { line ->
                            viewModel.postChatUpdate {
                                viewModel.cadConversations = viewModel.cadConversations.appendInApp(
                                    AiChatMessage(fromUser = false, text = line),
                                )
                            }
                        },
                    )
                }
                val question = agent.lastQuestion
                if (question != null) {
                    // The app's ask_user tool ended the turn: show its card and mark the agent
                    // as waiting. No turn is running and nothing is spinning - the answer starts
                    // the next turn as a normal user message.
                    viewModel.onInAppQuestion(question)
                } else {
                    viewModel.cadConversations = viewModel.cadConversations.appendInApp(
                        AiChatMessage(fromUser = false, text = reply),
                    )
                }
                viewModel.inAppWebNotice = agent.webToolsNotice
            } catch (error: AgentException) {
                if (error.code == "cancelled") {
                    viewModel.cadStatus = "Stopped"
                } else {
                    // The failure is a row of the conversation itself, not only a status line
                    // that a copied transcript would not carry. Its text already names the
                    // endpoint host and the round for model-call failures.
                    viewModel.cadConversations = viewModel.cadConversations.appendInApp(
                        AiChatMessage(
                            fromUser = false,
                            text = error.message ?: "The in-app agent failed.",
                            kind = AiChatEntryKind.ERROR,
                        ),
                    )
                    viewModel.cadStatus = null
                }
                viewModel.inAppWebNotice = viewModel.inAppCadAgent?.webToolsNotice ?: viewModel.inAppWebNotice
            } catch (error: Throwable) {
                viewModel.cadConversations = viewModel.cadConversations.appendInApp(
                    AiChatMessage(
                        fromUser = false,
                        text = "The in-app agent failed: " +
                            (error.message ?: error.javaClass.simpleName).take(200),
                        kind = AiChatEntryKind.ERROR,
                    ),
                )
                viewModel.cadStatus = null
            } finally {
                // Every exit path - answer, error, cancel, and a turn that never began - ends
                // the keeper, so no wake lock can outlive its turn.
                InAppTurnKeeper.end(context.applicationContext)
                if (turnBegan) ownership.endInAppTurn()
                viewModel.cadBusy = false
                // The engine's scene has moved under the plate; ask for a frame and a scene
                // read. A tick rather than a call: the composable that can refresh the viewport
                // may be the one created by the next rotation.
                viewModel.noteCadTurnFinished()
            }
        }
    }

    /**
     * Sends from the floating chat's in-app source.
     *
     * The same conversation, turn and cancel as the CAD agent sheet's ask box: the chat is
     * another view of that agent, not a second one. The endpoint settings are read at send time,
     * so a change made in the sheet applies to the next message here.
     */
    fun askInAppAgentFromChat(text: String) {
        askInAppAgent(text, agentSettingsStore.load())
    }

    /**
     * Copies the chat transcript - messages and tool rows, no credential - and says so.
     *
     * This is the thing to hand to a human or another agent when a turn looks wrong: what was
     * asked, what the agent said, and every tool call with its arguments, duration and outcome.
     */
    fun copyChatTranscript(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("CAD agent chat", text))
        Toast.makeText(
            context,
            "Transcript copied · " + text.length + " characters",
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * Sends one request to whichever agent authors the CAD document.
     *
     * In-app is the default: the endpoint is called from this phone and the engine's tools run in
     * process. The harness is the advanced path and keeps its own conversation on the harness;
     * each backend's messages live in their own list ([CadConversations]).
     */
    fun askSolidCraftAgent(text: String) {
        // Any new prompt ends a wait, whichever backend it goes to.
        viewModel.clearPendingQuestion()
        val stored = agentSettingsStore.load()
        if (stored.settings.mode == AgentMode.IN_APP) {
            askInAppAgent(text, stored)
            return
        }
        val faceForTurn = faceForNextMessage()
        val outgoing = faceForTurn?.promptBlock().orEmpty() + text
        aiScope.launch {
            viewModel.harnessCadBusy = true
            viewModel.harnessCadStatus = null
            try {
                val chat = cadChat.get()
                    ?: error("Connect the harness first - Ask AI is in the sidebar rail")
                withContext(Dispatchers.IO) { chat.send(EngineMode.SOLIDCRAFT.prompt(outgoing)) }
                viewModel.cadConversations = viewModel.cadConversations.appendHarness(
                    AiChatMessage(fromUser = true, text = text),
                )
                pollForReply(
                    chat,
                    { viewModel.cadConversations = viewModel.cadConversations.replaceHarness(it) },
                    { viewModel.harnessCadStatus = it },
                )
            } catch (error: Throwable) {
                viewModel.harnessCadStatus = error.message?.take(160) ?: "Harness call failed"
            } finally {
                viewModel.harnessCadBusy = false
            }
        }
    }

    /**
     * Take over from an attached external agent (the sheet's one button while paused).
     *
     * The authorship closes the live bridge connections and detaches them before returning, so
     * the sheet's next poll reads "Author: nobody" and the in-app composer is usable again. The
     * outbound agent has to reconnect; the confirmation in the sheet says so.
     */
    fun takeOverFromExternalAgent() {
        SolidCraftEngine.takeOverFromExternalAgents()
        viewModel.inAppWebNotice = viewModel.inAppCadAgent?.webToolsNotice
    }

    /** Persists the agent choice; in-app stays the default and the harness the advanced path. */
    fun setAgentMode(mode: AgentMode) {
        if (agentSettings.mode == mode) return
        agentSettingsStore.saveMode(mode)
        agentSettings = agentSettingsStore.loadSettings()
        viewModel.inAppWebNotice = null
        viewModel.harnessCadStatus = when {
            mode == AgentMode.HARNESS && !aiConfigured ->
                "The harness is not connected yet. Connect it from Ask AI in the sidebar rail, " +
                    "or switch back to the in-app agent - it needs no computer."
            else -> null
        }
    }

    // How the last waiting question ended. The answer and Dismiss callbacks say so themselves;
    // any other clearing path - a new prompt in the sheet, Convert, closing the document - is
    // caught by the effect below and recorded as "not answered", so a dismissed or abandoned
    // card can never be mislabelled as answered (AGENT-9).
    var questionEndedByUser by remember { mutableStateOf(false) }
    var lastWaitingQuestion by remember { mutableStateOf<AgentQuestion?>(null) }

    /**
     * Records how the transcript's waiting question ended; the card's history line reports it.
     *
     * Called by the three human routes (a chip answer, a typed answer, Dismiss) before the wait
     * is cleared. [openChatForAttention] and the notification path do not resolve anything -
     * they only make the live card visible.
     */
    fun resolvePendingQuestion(outcome: AiQuestionOutcome) {
        viewModel.cadConversations = viewModel.cadConversations.copy(
            inApp = viewModel.cadConversations.inApp.markQuestionResolved(
                viewModel.pendingQuestion,
                outcome,
            ),
        )
        questionEndedByUser = true
    }

    // A wait cleared by anything other than the answer/Dismiss callbacks is still recorded, so
    // its card says "Not answered - the wait was cleared" instead of the old blanket
    // "Answered.". The flag is set by [resolvePendingQuestion] before the clear, so the answer
    // paths are not overwritten here.
    LaunchedEffect(viewModel.pendingQuestion) {
        val waiting = viewModel.pendingQuestion
        if (waiting != null) {
            lastWaitingQuestion = waiting
            questionEndedByUser = false
        } else {
            val previous = lastWaitingQuestion
            if (previous != null && !questionEndedByUser) {
                viewModel.cadConversations = viewModel.cadConversations.copy(
                    inApp = viewModel.cadConversations.inApp.markQuestionResolved(
                        previous,
                        AiQuestionOutcome.IGNORED,
                    ),
                )
            }
            lastWaitingQuestion = null
            questionEndedByUser = false
        }
    }

    /**
     * Opens the floating chat for something the user must see: the question notification's tap
     * or the rail's pulsing Ask AI badge.
     *
     * A pending in-app question exists only in the in-app conversation, so opening the chat on
     * whatever source was last shown can land on the Harness pane with the question card hidden
     * in the other transcript - and the badge swallowed by the now-open chat. When a question is
     * waiting the door switches the source to the in-app agent first (and persists the mode, as
     * the source chips do), so the card is always on screen. The request itself is still
     * one-shot: [ChatOpenRequest.takeUnhandled] records the tick it consumed, so an Activity
     * recreation does not reopen a chat the user has closed.
     */
    fun openChatForAttention() {
        if (viewModel.pendingQuestion != null) {
            viewModel.chatSource = ChatSource.IN_APP
            setAgentMode(AgentMode.IN_APP)
        }
        aiChatOpen = true
    }

    // A tap on that notification asks for the chat itself, not only for the app. The tick is
    // consumed exactly once: the same value on a recreated composition has already been handled,
    // so a chat the user closed stays closed; a new tap advances the counter and opens again.
    val chatOpenTicks by ChatOpenRequest.ticks.collectAsStateWithLifecycle()
    LaunchedEffect(chatOpenTicks) {
        if (ChatOpenRequest.takeUnhandled() != null) openChatForAttention()
    }

    /**
     * Saves the in-app endpoint. [apiKey] null keeps the stored key - the field is never bound to
     * it - and a blank string clears it.
     */
    fun saveAgentSettings(baseUrl: String, model: String, apiKey: String?) {
        val previous = agentSettingsStore.loadSettings()
        agentSettingsStore.save(
            AgentSettings(
                mode = previous.mode,
                baseUrl = baseUrl.trim(),
                model = model.trim(),
                hasApiKey = previous.hasApiKey,
                webSearch = previous.webSearch,
                lendView = previous.lendView,
            ),
            apiKey,
        )
        agentSettings = agentSettingsStore.loadSettings()
        val endpointChanged = previous.baseUrl.trim() != agentSettings.baseUrl.trim() ||
            previous.model != agentSettings.model
        if (endpointChanged) {
            // A different endpoint or model is a different agent: the old conversation and what
            // the old server said about search do not apply to it.
            viewModel.inAppCadAgent?.reset()
            viewModel.cadConversations = viewModel.cadConversations.copy(inApp = emptyList())
        } else if (apiKey != null) {
            // A new key can have different tool-route permission, so re-learn what search offers.
            viewModel.inAppCadAgent?.reset()
        }
        viewModel.inAppWebNotice = null
        viewModel.cadStatus = when {
            !agentSettings.isConfigured -> agentSettings.baseUrlProblem()?.let {
                "The in-app agent is not ready: " + it + "."
            }
            else -> null
        }
    }

    fun clearAgentApiKey() {
        agentSettingsStore.clearApiKey()
        agentSettings = agentSettingsStore.loadSettings()
        viewModel.inAppCadAgent?.reset()
        viewModel.inAppWebNotice = null
    }

    /** Persists the web-tools switch; the next turn's catalogue follows it. */
    fun setAgentWebSearch(enabled: Boolean) {
        val current = agentSettingsStore.loadSettings()
        agentSettingsStore.save(current.copy(webSearch = enabled, hasApiKey = current.hasApiKey))
        agentSettings = agentSettingsStore.loadSettings()
        viewModel.inAppCadAgent?.reset()
        viewModel.inAppWebNotice = null
        viewModel.cadStatus = null
    }

    /**
     * Persists **Lend the agent my view**.
     *
     * On, a message that carries a face selection also carries the plate render of that tinted
     * face; off, the message carries the SELECTED FACE block alone. The next turn follows it -
     * nothing is re-sent and no conversation is reset.
     */
    fun setAgentLendView(enabled: Boolean) {
        val current = agentSettingsStore.loadSettings()
        agentSettingsStore.save(current.copy(lendView = enabled))
        agentSettings = agentSettingsStore.loadSettings()
    }

    /** Cancels the turn in flight, whichever agent is driving it. */
    fun cancelAgentTurn() {
        when (agentSettings.mode) {
            AgentMode.IN_APP -> viewModel.cancelInAppTurn()
            AgentMode.HARNESS -> {
                val chat = cadChat.get() ?: return
                aiScope.launch {
                    try {
                        withContext(Dispatchers.IO) { chat.stop() }
                        viewModel.harnessCadStatus = "Stopped"
                    } catch (error: Throwable) {
                        viewModel.harnessCadStatus = error.message?.take(160)
                            ?: "Could not stop the harness turn"
                    } finally {
                        viewModel.harnessCadBusy = false
                    }
                }
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
        // The modelling view shows the engine's own scene, so opening it is the
        // request that starts the engine - nothing boots it at app launch now.
        viewModel.startBlenderEngine()
        if (modellingChat.get() == null) {
            if (aiConfigured) {
                connectHarness()
            } else {
                // There is no connect button on this screen, so the way to make
                // it work has to be said here.
                viewModel.modellingStatus = "The harness is not set up yet - connect it in the AI chat " +
                    "(Ask AI in the sidebar rail) first"
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

    /**
     * Opens the CAD menu.
     *
     * The mirror of [openModelling], and the click is the whole signal: opening this menu
     * starts the CAD engine and makes every prompt sent from here say CAD, exactly as the
     * modelling menu starts Blender and says Blender. The user never has to tell the agent
     * which engine they mean - the menu they are standing in already did.
     */
    fun openCad() {
        cadOpen = true
        // Opening the screen is the request that starts the engine; nothing boots it at
        // launch. Off the main thread, because this one extracts, writes a token and waits
        // for a port, and the engine imports build123d on the way up.
        viewModel.startCadEngine()
        cadEngineStatus = CadEngine.status(context)
        if (cadChat.get() == null) {
            if (aiConfigured) {
                connectHarness()
            } else {
                viewModel.harnessCadStatus = "The harness is not set up yet - connect it in the AI chat " +
                    "(Ask AI in the sidebar rail) first"
            }
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
    LaunchedEffect(aiConfigured, aiChatOpen, modellingOpen, cadOpen) {
        // The modelling screen is a conversation too, and it has no connect UI of
        // its own: without this a rotation left it empty and every send failed
        // with "Connect to the harness first" and no way back. CAD is the same
        // conversation on its own session, and it was missing from both the keys and
        // the test - so a rotation with the CAD screen open left every send failing
        // from a chat that had nothing to send on, until the screen was left and
        // re-entered.
        val missingClient = (aiChatOpen && cadChat.get() == null) ||
            (modellingOpen && modellingChat.get() == null) ||
            (cadOpen && cadChat.get() == null)
        if (aiConfigured && missingClient) connectHarness()
        // The engine needs the same treatment for the same reason, and it is the
        // one thing that does NOT come back on its own: modellingOpen is
        // rememberSaveable, so the screen is restored after the process is killed,
        // while the start that opened it - openModelling - is not re-run. Before
        // the engine stopped being booted at launch this was invisible; now a
        // restored screen showed "failed to connect to /127.0.0.1 (port 9876)
        // ... ECONNREFUSED" and stayed that way until it was left and re-entered.
        if (modellingOpen) viewModel.startBlenderEngine()
        // Same for CAD, and the same reason: cadOpen is rememberSaveable, so a restored
        // screen outlives the click that opened it and would otherwise sit on a dead port.
        if (cadOpen) viewModel.startCadEngine()
    }

    // The CAD engine comes up asynchronously - it extracts, writes a token, waits for a
    // port and imports build123d on the way - so the caption follows it rather than being
    // read once when the menu opened and left saying "not started" while it served.
    LaunchedEffect(cadOpen) {
        if (!cadOpen) return@LaunchedEffect
        // Renders are pushed rather than polled: the watcher already knows the moment one
        // lands, and re-decoding the directory every second to find it would be work the
        // engine has already done.
        // One viewport for the life of the screen. It owns the camera and the frame loop,
        // and it is closed with the screen so a render in flight cannot outlive it.
        val viewport = CadViewport(
            scope = aiScope,
            client = CadPreviewClient(
                tokenFile = File(context.filesDir, "cad/cad_mcp_token.txt"),
            ),
            frameFile = File(CadEngine.exportsDirectory(context), "viewport.png"),
            // The engine extracts 566 MB before it binds, so the screen waits on it
            // rather than on a retry count.
            engineReady = { runCatching { CadEngine.isReady(context) }.getOrDefault(false) },
            // What the engine has said about why it is not up, if anything. The viewport
            // stops asking on a named problem instead of spinning "Rendering..." over an
            // engine that will never answer - a frozen strip is how the missing Python
            // stayed invisible.
            engineProblem = { runCatching { CadEngine.problem(context) }.getOrNull() },
            // Read live: a setting saved in the sheet has to reach the next frame, not the
            // next launch.
            settings = { cadViewerSettings },
        )
        cadViewport = viewport
        // The engine draws from its own copy of these, so it is told every time the screen
        // opens rather than only when the sheet is saved.
        viewport.applyViewerSettings(cadViewerSettings)
        viewport.start()
        val frameJob = launch { viewport.frame.collect { cadFrame = it } }
        val pickJob = launch { viewport.pick.collect { cadPick = it } }
        val busyJob = launch { viewport.busy.collect { cadFramePending = it } }
        val sceneJob = launch { viewport.sceneEmpty.collect { cadSceneEmpty = it == true } }
        // The engine extracts 566 MB and binds its port after this effect starts, so the
        // settings sent above arrive before there is anything to receive them - which is why a
        // saved setting looked forgotten the next time the app was opened. They are sent again
        // the moment the engine says it is ready; the call is idempotent and the viewport
        // redraws with the camera where the user left it.
        var viewerSettingsSent = false
        try {
            while (true) {
                val status = CadEngine.status(context)
                cadEngineStatus = status
                cadEngineBusy = CadEngine.isWorking()
                if (!viewerSettingsSent && CadEngine.isReady(context)) {
                    viewerSettingsSent = true
                    viewport.applyViewerSettings(cadViewerSettings)
                    // The engine seeds nothing, so ask what its scene really holds: an
                    // empty one is said, not shown as a bare grid.
                    viewport.refreshSceneState()
                }
                delay(1_000)
            }
        } finally {
            frameJob.cancel()
            pickJob.cancel()
            busyJob.cancel()
            sceneJob.cancel()
            viewport.close()
            cadViewport = null
        }
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
    BackHandler(enabled = engineSetupOpen) { engineSetupOpen = false }
    BackHandler(enabled = allSettingsOpen) { allSettingsOpen = false }
    BackHandler(enabled = printerScreenOpen) { printerScreenOpen = false }
    BackHandler(enabled = modelToolsOpen) { modelToolsOpen = false }
    BackHandler(enabled = nonPlanarOpen) { nonPlanarOpen = false }
    BackHandler(enabled = conicalOpen) { conicalOpen = false }
    BackHandler(enabled = meshLimitOpen) { meshLimitOpen = false }
    BackHandler(enabled = uiScaleOpen) { uiScaleOpen = false }
    BackHandler(enabled = diagnosticsLogOpen) { diagnosticsLogOpen = false }
    BackHandler(enabled = profilesOpen) { profilesOpen = false }
    BackHandler(
        enabled = layerEventsOpen && state.layerPreview != null && state.hasCurrentGcode(),
    ) { layerEventsOpen = false }
    // The floating chat registers the Back handler itself while it is composed: full screen
    // collapses to the panel, a second back closes it (see AiChatOverlay's onBack).
    BackHandler(enabled = modellingOpen) { modellingOpen = false }
    BackHandler(enabled = blenderFilesOpen && !modellingOpen) { blenderFilesOpen = false }
    // Back closes the CAD files screen when it is up, and the CAD screen itself otherwise: without
    // the second, system back on a full-screen destination walked straight out of the app.
    BackHandler(enabled = solidCraftFilesOpen) { solidCraftFilesOpen = false }
    BackHandler(enabled = cadFilesOpen) { cadFilesOpen = false }
    BackHandler(enabled = cadOpen && !cadFilesOpen) { cadOpen = false }
    // The paint brush and the annotation tools own the model's gestures while they
    // are open, so back leaves them the way their own Close action does.
    BackHandler(enabled = supportPaintUiOpen) {
        viewModel.setPaintMode(SupportPaintMode.NONE)
        supportPaintUiOpen = false
    }
    // Face select mode owns the plate's taps; Back ends it and leaves the selected face alone.
    BackHandler(enabled = state.faceSelectMode) { viewModel.exitFaceSelectMode() }
    BackHandler(enabled = annotationUiOpen) {
        viewModel.setAnnotationActive(false)
        annotationUiOpen = false
    }
    // The split preview is over the model, so back leaves it the way Close does.
    BackHandler(enabled = state.cutActive) { viewModel.setCutActive(false) }
    // The snap fit owns the plate's taps while it is open, so back leaves it too.
    BackHandler(enabled = state.snapActive) { viewModel.setSnapActive(false) }
    var viewerMode by rememberSaveable { mutableStateOf(ViewerMode.MODEL) }
    var selectedLayerIndex by rememberSaveable { mutableStateOf(0) }
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
            gizmoMode = TransformGizmoMode.NONE
            gizmoScalePercent = 100
            gizmoReadout = null
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
    // The CAD document's exports: the file is the engine's document written out, which is a
    // different verb from Slice and leaves the document unchanged.
    val cadStepExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/step"),
    ) { uri: Uri? -> uri?.let { viewModel.exportCadDocument(it, "step") } }
    val cadStlExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("model/stl"),
    ) { uri: Uri? -> uri?.let { viewModel.exportCadDocument(it, "stl") } }
    val cad3mfExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("model/3mf"),
    ) { uri: Uri? -> uri?.let { viewModel.exportCadDocument(it, "3mf") } }
    // The CAD route into the app: STEP/IGES/3MF/OBJ/glTF through the engine's own io crate,
    // not through OCCT's STEP-to-mesh converter. Import model keeps the mesh route.
    val cadDocumentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { picked ->
            // The ViewModel resets the in-app conversation, the agent's memory and a pending
            // question when the open succeeds (and says so in the status); the screen only
            // hands it the file.
            viewModel.openCadDocument(picked)
        }
    }

    // The plate's Slice gate is a fact about the document, read from MainUiState (never from
    // this composition's own state): a rotation must not change what Slice decides, and a live
    // CAD document refuses Slice by name until the human presses Convert to STL.
    val effectiveSliceBlockedReason = state.cadSliceBlockedReason
        ?: sliceBlockedReason
        ?: if (nonPlanarSettings.enabled && conicalSettings.enabled) {
            "Non-planar and conical slicing are mutually exclusive; disable one before slicing"
        } else {
            null
        }

    // The SolidCraft menu's engine line, polled so it is current whenever the menu opens.
    LaunchedEffect(Unit) {
        while (true) {
            solidCraftEngineLine = when {
                SolidCraftEngine.isReady() -> "Engine: running · " + SolidCraftEngine.status(context)
                SolidCraftEngine.isStarting() -> "Engine: starting…"
                else -> "Engine: not running"
            }
            delay(1_000)
        }
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
        // One rail for both window sizes: the fold keeps it as its permanent column
        // and the phone opens it as a panel, so the two cannot drift apart - the
        // same call, the same rows, the same editors.
        val sessionRail: @Composable () -> Unit = {
            SessionRail(
                selected = selectedTab,
                onSelect = { selectedTab = it },
                engine = engine,
                state = state,
                gcodeAvailable = state.hasCurrentGcode(),
                sliceBlockedReason = effectiveSliceBlockedReason,
                sliceTappable = state.cadNeedsConversion,
                cadConvertLabel = state.cadConvertLabel,
                onConvertCad = {
                    viewModel.clearPendingQuestion()
                    viewModel.convertCadToStl()
                },
                onSlice = viewModel::sliceModel,
                onExportGcode = { gcodeExportPicker.launch(GcodeExportName.suggest()) },
                onTools = { modelToolsOpen = true },
                cadDocument = state.cadDocument != null,
                // The one human edit surface a CAD document has: start the engine and open
                // the sheet the chat and the exports live in.
                onCadAgent = {
                    viewModel.connectCadAgent()
                    viewModel.openSolidCraftSheet()
                },
                // The chat's only door. It opens the overlay at the size last chosen and
                // leaves the selected tab alone. The badge is the one time it is not silent:
                // the in-app agent is waiting on an answer and that answer's card is not on
                // screen - the chat is closed, or it is open on the Harness pane, which never
                // draws the in-app conversation's card. The door switches to the in-app source
                // when a question waits, so one tap always lands on the card.
                onAskAi = { openChatForAttention() },
                askAiBadge = viewModel.pendingQuestion != null &&
                    !(aiChatOpen && viewModel.chatSource == ChatSource.IN_APP),
                onSettings = viewModel::updateSettings,
                onPrusaSettings = viewModel::updatePrusaSettings,
                onOrcaSettings = viewModel::updateOrcaSettings,
                onExtraSetting = { key, value -> viewModel.setExtraSetting(engine, key, value) },
                // One picker, two doors: this is the rail's, and the settings sheet's
                // "Add settings to the sidebar" row raises the same state.
                onAddSettings = { sidebarPickerOpen = true },
            )
        }
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
                    if (modellingOpen || cadOpen) return@Scaffold
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
                                        // Not a mesh import and not OCCT's converter: the file
                                        // becomes the engine's live document, and Slice is what
                                        // turns it into the STL a slicer reads.
                                        DropdownMenuItem(
                                            text = { Text("Open CAD document") },
                                            leadingIcon = { Icon(AppIcons.Cube, contentDescription = null) },
                                            onClick = {
                                                importMenuExpanded = false
                                                cadDocumentPicker.launch(
                                                    arrayOf(
                                                        "application/step",
                                                        "application/octet-stream",
                                                        "model/3mf",
                                                        "model/obj",
                                                        "model/gltf-binary",
                                                        "model/gltf+json",
                                                        "*/*",
                                                    ),
                                                )
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
                                                // Blender's token is generated on first use, so
                                                // this call site asks the engine for it - only
                                                // once the user has authenticated; the CAD
                                                // engine writes its own when it starts, so the CAD
                                                // item below only reads.
                                                requestMcpTokenCopy(
                                                    context = context,
                                                    clipLabel = "Blender MCP token",
                                                    engineName = "Blender",
                                                    tokenFile = {
                                                        BlenderEngine.ensureToken(File(context.filesDir, "blender"))
                                                        BlenderEngine.tokenFile(File(context.filesDir, "blender"))
                                                    },
                                                )
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
                                                viewModel.setCutActive(false)
                                                viewModel.setAnnotationActive(true)
                                                annotationUiOpen = true
                                            },
                                            enabled = state.mesh != null && !state.isBusy,
                                        )
                                    }
                                }
                                // SolidCraft's own menu: the design the agent owns, the plate's
                                // Convert press, and the engine that holds the document. Its items
                                // read the document state, so it cannot offer a load, a save or an
                                // export that would do nothing.
                                Box {
                                    TopBarTextAction(
                                        label = "SolidCraft",
                                        onClick = { solidCraftMenuExpanded = true },
                                    )
                                    DropdownMenu(
                                        expanded = solidCraftMenuExpanded,
                                        onDismissRequest = { solidCraftMenuExpanded = false },
                                        modifier = Modifier.widthIn(min = 280.dp, max = 340.dp),
                                    ) {
                                        MenuSectionLabel("Design")
                                        DropdownMenuItem(
                                            text = { Text("New design") },
                                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                            onClick = {
                                                solidCraftMenuExpanded = false
                                                viewModel.newCadDesign()
                                            },
                                            enabled = !state.isBusy,
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Open STEP/IGES/3MF/OBJ/glTF…") },
                                            leadingIcon = { Icon(AppIcons.Cube, contentDescription = null) },
                                            onClick = {
                                                solidCraftMenuExpanded = false
                                                cadDocumentPicker.launch(
                                                    arrayOf(
                                                        "application/step",
                                                        "application/octet-stream",
                                                        "model/3mf",
                                                        "model/obj",
                                                        "model/gltf-binary",
                                                        "model/gltf+json",
                                                        "*/*",
                                                    ),
                                                )
                                            },
                                            enabled = !state.isBusy,
                                        )
                                        DropdownMenuItem(
                                            text = { Text("SolidCraft files") },
                                            leadingIcon = { Icon(AppIcons.Cube, contentDescription = null) },
                                            onClick = {
                                                solidCraftMenuExpanded = false
                                                solidCraftFilesOpen = true
                                            },
                                            enabled = !state.isBusy,
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Save design") },
                                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                            onClick = {
                                                solidCraftMenuExpanded = false
                                                viewModel.saveCadDesign()
                                            },
                                            enabled = state.cadDocument != null && !state.isBusy,
                                        )
                                        HorizontalDivider()
                                        MenuSectionLabel("Export")
                                        DropdownMenuItem(
                                            text = { Text("Export STEP") },
                                            onClick = {
                                                solidCraftMenuExpanded = false
                                                cadStepExportPicker.launch(cadExportBase(state) + ".step")
                                            },
                                            enabled = state.cadDocument != null && !state.isBusy,
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Export 3MF") },
                                            onClick = {
                                                solidCraftMenuExpanded = false
                                                cad3mfExportPicker.launch(cadExportBase(state) + ".3mf")
                                            },
                                            enabled = state.cadDocument != null && !state.isBusy,
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Export STL") },
                                            onClick = {
                                                solidCraftMenuExpanded = false
                                                cadStlExportPicker.launch(cadExportBase(state) + ".stl")
                                            },
                                            enabled = state.cadDocument != null && !state.isBusy,
                                        )
                                        HorizontalDivider()
                                        MenuSectionLabel("Plate")
                                        DropdownMenuItem(
                                            text = { Text(state.cadConvertLabel ?: "Convert to STL") },
                                            leadingIcon = { Icon(AppIcons.Cube, contentDescription = null) },
                                            onClick = {
                                                solidCraftMenuExpanded = false
                                                viewModel.clearPendingQuestion()
                                                viewModel.convertCadToStl()
                                            },
                                            enabled = state.cadDocument != null && !state.isBusy,
                                        )
                                        HorizontalDivider()
                                        MenuSectionLabel("Engine")
                                        DropdownMenuItem(
                                            text = { Text(solidCraftEngineLine) },
                                            onClick = {},
                                            enabled = false,
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    state.cadDocument?.let { "CAD agent · " + it.name }
                                                        ?: "Connect CAD agent",
                                                )
                                            },
                                            leadingIcon = { Icon(AppIcons.Sparkle, contentDescription = null) },
                                            onClick = {
                                                solidCraftMenuExpanded = false
                                                viewModel.connectCadAgent()
                                                viewModel.openSolidCraftSheet()
                                            },
                                            enabled = !state.isBusy,
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Copy MCP token") },
                                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                                            onClick = {
                                                solidCraftMenuExpanded = false
                                                requestMcpTokenCopy(
                                                    context = context,
                                                    clipLabel = "SolidCraft MCP token",
                                                    engineName = "SolidCraft CAD agent",
                                                    tokenFile = {
                                                        SolidCraftEngine.ensureToken(context)
                                                        SolidCraftEngine.tokenFile(context)
                                                    },
                                                )
                                            },
                                            enabled = !state.isBusy,
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Stop CAD agent") },
                                            leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                                            onClick = {
                                                solidCraftMenuExpanded = false
                                                viewModel.stopCadAgent()
                                            },
                                            enabled = !state.isBusy && SolidCraftEngine.isReady(),
                                        )
                                    }
                                }
                                // CAD gets its own menu rather than an entry in the Blender
                                // one, and that is the whole mechanism: which menu the user
                                // opened is what tells the agent which engine it is working
                                // with. An entry inside the Blender menu could not say that.
                                Box {
                                    TopBarTextAction(
                                        label = "CAD",
                                        onClick = { cadMenuExpanded = true },
                                    )
                                    DropdownMenu(
                                        expanded = cadMenuExpanded,
                                        onDismissRequest = { cadMenuExpanded = false },
                                        modifier = Modifier.widthIn(min = 280.dp, max = 340.dp),
                                    ) {
                                        MenuSectionLabel("Model")
                                        DropdownMenuItem(
                                            text = { Text("Model with CAD") },
                                            leadingIcon = { Icon(AppIcons.Cube, contentDescription = null) },
                                            onClick = {
                                                cadMenuExpanded = false
                                                openCad()
                                            },
                                            enabled = !state.isBusy,
                                        )
                                        // The same handover as the Blender menu's item, for this
                                        // engine's own token: the CAD engine can be driven from
                                        // outside the app on a phone without root, and its token
                                        // file is as unreadable from outside as Blender's is.
                                        DropdownMenuItem(
                                            text = { Text("Copy MCP token") },
                                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                                            onClick = {
                                                cadMenuExpanded = false
                                                requestMcpTokenCopy(
                                                    context = context,
                                                    clipLabel = "CAD MCP token",
                                                    engineName = "CAD",
                                                    tokenFile = {
                                                        CadEngine.tokenFile(File(context.filesDir, "cad"))
                                                    },
                                                )
                                            },
                                            enabled = !state.isBusy,
                                        )
                                        MenuSectionLabel("Viewer")
                                        DropdownMenuItem(
                                            text = { Text("Viewer settings") },
                                            leadingIcon = { Icon(AppIcons.Filter, contentDescription = null) },
                                            onClick = {
                                                cadMenuExpanded = false
                                                cadViewerOpen = true
                                            },
                                        )
                                        MenuSectionLabel("Model")
                                        DropdownMenuItem(
                                            text = { Text("Stop CAD engine") },
                                            leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                                            onClick = {
                                                cadMenuExpanded = false
                                                viewModel.stopCadEngine()
                                            },
                                            enabled = !state.isBusy,
                                        )
                                        HorizontalDivider()
                                        MenuSectionLabel("Storage")
                                        DropdownMenuItem(
                                            text = { Text("CAD files") },
                                            leadingIcon = { Icon(AppIcons.Cube, contentDescription = null) },
                                            onClick = {
                                                cadMenuExpanded = false
                                                cadFilesOpen = true
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
                    // Only while the bar is expanded: collapsed, the plate draws
                    // the small floating handle instead and this bar takes no
                    // height from it at all.
                    if (selectedTab == AppTab.PLATE && !expandedLayout && platePanels.actionBarShowsRows) {
                        ActionBar(
                            state = state,
                            nonPlanarEnabled = nonPlanarSettings.enabled,
                            conicalEnabled = conicalSettings.enabled,
                            sliceBlockedReason = effectiveSliceBlockedReason,
                            sliceTappable = state.cadNeedsConversion,
                            cadConvertLabel = state.cadConvertLabel,
                            onConvertCad = {
                                viewModel.clearPendingQuestion()
                                viewModel.convertCadToStl()
                            },
                            onSlice = viewModel::sliceModel,
                            onExportGcode = { gcodeExportPicker.launch(GcodeExportName.suggest()) },
                            onTools = { modelToolsOpen = true },
                            onToggle = { showPlatePanels(platePanels.toggledActionBar()) },

                        )
                    }
                },
            ) { padding ->
                // One picker, two doors: the settings sheet's "Add settings to the
                // sidebar" row and the rail's own + both raise this state, so the
                // screen is drawn here - over whichever tab it was opened from - and
                // its back label names that tab.
                if (sidebarPickerOpen) {
                    SidebarPickerScreen(
                        engine = engine,
                        state = state,
                        onAdd = { viewModel.addSidebarSetting(engine, it) },
                        onRemove = { viewModel.removeSidebarSetting(engine, it) },
                        onMove = { key, delta -> viewModel.moveSidebarSetting(engine, key, delta) },
                        onClose = { sidebarPickerOpen = false },
                        backLabel = sidebarBackLabel(selectedTab),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                } else
                // Outside the tab switch on purpose: the Plate menu is what opens this,
                // so tying it to the More tab meant the tap set a flag and nothing drew.
                if (engineSetupOpen) {
                    // Collected here rather than at the top of the app: a row changes at
                    // 10 Hz while a tree is written, and only this screen draws them.
                    val enginePrepRows by EnginePreparation.rows.collectAsStateWithLifecycle()
                    EngineSetupScreen(
                        rows = enginePrepRows,
                        selected = decodeEngineSelection(enginePrepSelection),
                        busy = enginePrepRows.any { it.phase == EnginePreparation.Phase.RUNNING },
                        freeBytes = EnginePreparation.freeBytes(context),
                        onPrepare = {
                            // Its own scope, so leaving this screen does not cancel a tree
                            // half way through being written.
                            EnginePreparation.start(
                                context,
                                decodeEngineSelection(enginePrepSelection),
                            )
                        },
                        onToggle = { toggled ->
                            val next = decodeEngineSelection(enginePrepSelection).toMutableSet()
                            if (!next.remove(toggled)) {
                                next.add(toggled)
                                // CAD cannot run without the Python the Klipper payload brings,
                                // so a CAD tick ticks it too rather than extracting 533 MB
                                // behind an unticked row.
                                if (toggled == EnginePreparation.Engine.CAD) {
                                    next.add(EnginePreparation.Engine.KLIPPER)
                                }
                            }
                            enginePrepSelection = encodeEngineSelection(next)
                        },
                        onSelectAll = {
                            enginePrepSelection =
                                encodeEngineSelection(EnginePreparation.Engine.entries.toSet())
                        },
                        onDone = { engineSetupOpen = false },
                        onSkip = null,
                        onBack = { engineSetupOpen = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                } else if (modellingOpen) {
                    ModellingScreen(
                        state = state,
                        messages = viewModel.modellingMessages,
                        busy = viewModel.modellingBusy,
                        status = viewModel.modellingStatus,
                        owner = modellingOwner,
                        // Locked while the agent works: the camera is the agent's until
                        // it stops, which is the whole point of the lock.
                        canTakeCamera = !viewModel.modellingBusy,
                        agentConnected = modellingChatReady,
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
                } else if (cadOpen && !cadFilesOpen && !blenderFilesOpen) {
                    CadScreen(
                        messages = viewModel.cadConversations.harness,
                        busy = viewModel.harnessCadBusy,
                        status = viewModel.harnessCadStatus,
                        agentConnected = cadChatReady,
                        engineStatus = cadEngineStatus,
                        engineBusy = cadEngineBusy,
                        sceneEmpty = cadSceneEmpty,
                        exportsPath = CadEngine.exportsDirectory(context).absolutePath,
                        showStatus = cadViewerSettings.showStatus,
                        frame = cadFrame,
                        pick = cadPick,
                        framePending = cadFramePending,
                        onOrbit = { dx, dy -> cadViewport?.orbit(dx, dy) },
                        onPan = { dx, dy -> cadViewport?.pan(dx, dy) },
                        onZoom = { factor -> cadViewport?.zoomBy(factor) },
                        onSelect = { x, y -> cadViewport?.select(x, y) },
                        onViewSize = { w, h -> cadViewport?.setViewSize(w, h) },
                        onResetView = { cadViewport?.reset() },
                        onOrientation = { cadViewport?.setOrientation(it) },
                        onPickUsed = { cadViewport?.clearPick() },
                        onSend = ::askCadAgent,
                        onAddDefaultCube = {
                            cadViewport?.addDefaultCube { added ->
                                if (!added) {
                                    Toast.makeText(
                                        context,
                                        "The CAD engine did not add the cube",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        onExportAll = {
                            val viewport = cadViewport
                            if (viewport == null) {
                                Toast.makeText(
                                    context,
                                    "The CAD engine is still starting",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                viewport.exportEveryShape(CadEngine.exportsDirectory(context)) { count ->
                                    Toast.makeText(
                                        context,
                                        when {
                                            count == null -> "The CAD engine did not export the scene"
                                            count == 0 -> "The CAD scene has no shapes to send"
                                            count == 1 -> "Exported 1 shape; it is joining the plate"
                                            else -> "Exported $count shapes; they are joining the plate"
                                        },
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        onExit = { cadOpen = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                } else if (blenderFilesOpen) {
                    EngineFilesScreen(
                        title = "Blender files",
                        engine = "blender",
                        extensions = setOf("stl"),
                        note = "Every model the Blender engine exports is written here and " +
                            "picked up once. Nothing removes them afterwards, so this is " +
                            "where they accumulate.",
                        onBack = { blenderFilesOpen = false },
                        // Load puts a file back in front of the agent. This importer is the
                        // patient one - it waits for the engine to boot and retries, because a
                        // single attempt loses that race silently - and it replaces the scene,
                        // which is what "load this model" means.
                        onLoad = { file ->
                            scope.launch(Dispatchers.IO) {
                                val client = EnginePreviewClient(
                                    tokenFile = BlenderEngine.tokenFile(blenderDir))
                                try {
                                    val ok = client.importModelWhenReady(file)
                                    // Main thread, like the CAD handler's: a Toast from this
                                    // Dispatchers.IO coroutine throws before it is ever shown.
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            if (ok) "Loaded " + file.name + " into Blender"
                                            else "Blender did not accept " + file.name,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                } catch (error: Throwable) {
                                    Log.w("EnderSlicerApp", "blender load failed: " + error.message)
                                } finally {
                                    client.close()
                                }
                            }
                        },
                        loadLabel = "Load",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                } else if (solidCraftFilesOpen) {
                    // The engine's own designs, not its exports: load one onto the plate or
                    // delete one. The plate is only touched by Load to plate.
                    SolidCraftFilesScreen(
                        workspace = viewModel.solidCraftWorkspace(),
                        // The design is the plate's document only while it is LIVE. A
                        // converted plate holds STL models made from it, so Delete is allowed
                        // then - the refusal sentence itself says "close or convert it first".
                        plateDesignPath = state.cadDocument?.takeIf { !it.converted }?.designPath,
                        plateHasContent = state.cadDocument != null || state.models.isNotEmpty(),
                        platePartCount = state.cadDocument?.let { document ->
                            if (document.converted) document.convertedParts.size else document.parts.size
                        },
                        onBack = { solidCraftFilesOpen = false },
                        onLoad = { entry -> viewModel.loadCadDesign(entry.designFile.absolutePath) },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                } else if (cadFilesOpen) {
                    EngineFilesScreen(
                        title = "CAD files",
                        engine = "cad",
                        // Everything the engine writes for the user: models, drawings and the
                        // renders the agent asks for. The viewport's own frame file is excluded
                        // - it is rewritten whenever the camera moves, and it is not an export.
                        extensions = setOf("stl", "step", "stp", "brep", "svg", "dxf", "png"),
                        exclude = setOf("viewport.png"),
                        note = "Every render and every model the CAD engine writes lands here, " +
                            "and the app picks each one up once. Nothing removes them " +
                            "afterwards, so this is where they accumulate.",
                        onBack = { cadFilesOpen = false },
                        // A file here is something the engine wrote, and loading it puts it back
                        // in the scene the viewport draws - the one place the agent and the user
                        // both work. Without this the list is read-only history.
                        //
                        // Its own client rather than the viewport's, because this screen
                        // *replaces* the CAD screen in the composition: the viewport is disposed
                        // when it opens, so cadViewport is null here and the tap did nothing at
                        // all - no log line, no error, nothing to see. The engine is a separate
                        // process and does not care which screen is showing.
                        onLoad = { file ->
                            scope.launch(Dispatchers.IO) {
                                val client = CadPreviewClient(
                                    tokenFile = java.io.File(context.filesDir,
                                        "cad/cad_mcp_token.txt"))
                                try {
                                    // Waits for the engine, which takes tens of seconds to bind its
                                    // port: a load asked for before then used to do nothing at all
                                    // and say nothing about it.
                                    val loaded = client.importFileWhenReady(
                                        file = file,
                                        name = file.nameWithoutExtension,
                                        replace = true,
                                    )
                                    // On the main thread: this coroutine is on Dispatchers.IO,
                                    // where Toast.makeText throws "Can't toast on a thread that
                                    // has not called Looper.prepare()" - which killed the process
                                    // on the one tap this message exists to report.
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            if (loaded) "Loaded " + file.name + " into CAD"
                                            // The engine comes back with its own reason when it
                                            // refuses - an unsupported format, a missing file, a
                                            // bad token - and blaming a slow start for that is a
                                            // lie the log cannot correct for the user reading the
                                            // toast.
                                            else "Could not load " + file.name +
                                                " - the engine refused it or never came up; " +
                                                "logcat has the reason",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                } catch (error: Throwable) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            "Could not load " + file.name + ": " + error.message,
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                    Log.w("EnderSlicerApp", "cad load failed: " + error.message)
                                } finally {
                                    client.close()
                                }
                            }
                        },
                        loadLabel = "Load",
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
                        // One close for the long-press card: Done, Split and Remove all
                        // take it down the same way, so no readout is left standing over
                        // whatever the plate shows next.
                        val closeGizmo: () -> Unit = {
                            gizmoMode = TransformGizmoMode.NONE
                            gizmoScalePercent = 100
                            gizmoReadout = null
                        }
                        // The one way into the Split tool, whether it is opened from
                        // Model tools or from the long-press card: one tool owns the
                        // bottom of the model view, and the cut and the joint are
                        // mutually exclusive by design, so opening one closes the other.
                        val openCutUi: () -> Unit = {
                            modelToolsOpen = false
                            // One tool owns the bottom of the model view: the
                            // split slider lands where the brush and the
                            // annotation toolbar would be.
                            supportPaintUiOpen = false
                            annotationUiOpen = false
                            viewModel.setPaintMode(SupportPaintMode.NONE)
                            viewModel.setAnnotationActive(false)
                            viewModel.setCutActive(false)
                            viewModel.setSnapActive(false)
                            viewModel.setCutActive(true)
                        }
                        // One call for both window sizes: the expanded and phone plates
                        // differ only in the chrome around the viewer, and that is
                        // decided from the window width above. Branching again here on
                        // this box's width - which the rail has already narrowed - let
                        // the two decisions disagree on a 600-680dp window, laying the
                        // model tools out for a phone beside a rail meant for a fold.
                        ViewerPanel(
                            state = state,
                            viewerMode = viewerMode,
                            onPreviewMemoryProfileChange = viewModel::setPreviewMemoryProfile,
                            onEnsureLayerPreview = viewModel::ensureLayerPreview,
                            expandedLayout = expandedLayout,
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
                            onSurfacePick = viewModel::brushSurfaceAt,
                            onSurfacePickEnd = viewModel::endSmartInfillStroke,
                            onFacePick = { hit -> viewModel.selectFaceAt(hit, modelOrientation) },
                            onClearFaceSelection = viewModel::clearFaceSelection,
                            onExitFaceSelectMode = viewModel::exitFaceSelectMode,
                            onPlateViewChanged = { view ->
                                plateView = view
                                viewModel.cadPlateView = view
                            },
                            onModelPicked = { index ->
                                state.models.getOrNull(index)?.let { model -> viewModel.selectModel(model.id) }
                            },
                            dragMove = modelDragMove,
                            onModelDrag = { deltaX, deltaY ->
                                viewModel.nudgeModel(deltaX.toDouble(), deltaY.toDouble())
                                gizmoReadout = null
                            },
                            onModelDragPreview = { deltaX, deltaY ->
                                gizmoReadout = "Move · X " + String.format(java.util.Locale.ROOT, "%.1f", deltaX) +
                                    " mm, Y " + String.format(java.util.Locale.ROOT, "%.1f", deltaY) + " mm"
                            },
                            onModelAxisMove = { axis, millimetres ->
                                gizmoReadout = null
                                when (axis) {
                                    ModelPlacement.Axis.X -> viewModel.nudgeModel(millimetres.toDouble(), 0.0)
                                    ModelPlacement.Axis.Y -> viewModel.nudgeModel(0.0, millimetres.toDouble())
                                    ModelPlacement.Axis.Z -> viewModel.liftModel(millimetres.toDouble())
                                }
                            },
                            onModelAxisMovePreview = { axis, millimetres ->
                                gizmoReadout = "Move " + axis.name + " · " +
                                    String.format(java.util.Locale.ROOT, "%.1f", millimetres) + " mm"
                            },
                            onHandleDragActive = { dragging -> gizmoHandleDrag = dragging },
                            gizmoMode = gizmoMode,
                            scalePreview = gizmoScalePercent / 100f,
                            onTransformRequested = { _, _ ->
                                gizmoMode = TransformGizmoMode.ROTATE
                                gizmoScalePercent = 100
                                gizmoReadout = null
                            },
                            onRotatePreview = { axis, degrees ->
                                gizmoReadout = "Rotate " + axis.name + " · " + degrees.roundToInt() + "°"
                            },
                            onRotateCommitted = { axis, degrees ->
                                gizmoReadout = null
                                viewModel.rotateModel(axis, degrees.toDouble())
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
                            onOpenDiagnosticsLog = { diagnosticsLogOpen = true },
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
                            onJointPick = { hit ->
                                // The picker answers in plate coordinates, and
                                // names the object the ray hit. Both matter: the
                                // plate moved the halves apart after the split,
                                // so the tap is only a place in the joint's own
                                // frame once the half it landed on is known.
                                viewModel.placeSnapJoint(
                                    Vec3(hit.x, hit.y, hit.z),
                                    state.models.getOrNull(hit.objectIndex)?.id,
                                )
                            },
                            onJointStroke = { hit, direction ->
                                // One drag, one cut: the point is where the
                                // finger went down and the direction is its own
                                // travel, so the slit runs across the stroke
                                // rather than along the automatic guess.
                                viewModel.placeSnapCutStroke(
                                    Vec3(hit.x, hit.y, hit.z),
                                    direction,
                                    state.models.getOrNull(hit.objectIndex)?.id,
                                )
                            },
                            onJointAxisGrab = viewModel::beginSnapJointAxis,
                            onJointAxisDrag = viewModel::dragSnapJointAxis,
                            onJointAxisRelease = viewModel::endSnapJointAxis,
                            cutActions = CutActions(
                                onAxis = viewModel::setCutAxis,
                                onOffset = viewModel::setCutOffset,
                                onSplit = viewModel::splitModel,
                                onClose = { viewModel.setCutActive(false) },
                            ),
                            snapActions = SnapActions(
                                onAdd = viewModel::addSnapJoint,
                                onCancelPlacement = viewModel::cancelSnapPlacement,
                                onScale = viewModel::setSnapScale,
                                onBeamHalf = viewModel::setSnapBeamHalf,
                                onLipInsertHalf = viewModel::setSnapLipInsertHalf,
                                onFullJoint = viewModel::setSnapFullJoint,
                                onHookLength = viewModel::setSnapHookLength,
                                onHookThickness = viewModel::setSnapHookThickness,
                                onHookLip = viewModel::setSnapHookLip,
                                onBarbs = viewModel::setSnapBarbs,
                                onStyle = viewModel::setSnapStyle,
                                onLibraryShape = viewModel::setSnapLibraryShape,
                                onLipEngagement = viewModel::setSnapLipEngagement,
                                onLipToothHeight = viewModel::setSnapLipToothHeight,
                                onLipWall = viewModel::setSnapLipWall,
                                onLipDepth = viewModel::setSnapLipDepth,
                                onLipCutWidth = viewModel::setSnapLipCutWidth,
                                onLipCutDepth = viewModel::setSnapLipCutDepth,
                                onCutWall = viewModel::cutWall,
                                onCutEveryBend = viewModel::cutEveryBend,
                                onCancelCut = viewModel::cancelCutWall,
                                onRemoveCut = viewModel::removeLipCut,
                                onTightness = viewModel::setSnapJointTightness,
                                onFlipAll = viewModel::flipSnapFacing,
                                onMixedFacing = viewModel::setSnapMixedFacing,
                                onJointFacing = viewModel::setSnapJointFacing,
                                onSocketRamp = viewModel::setSnapSocketRamp,
                                onSelect = viewModel::selectSnapJoint,
                                onMove = viewModel::moveSnapJoint,
                                onRemove = viewModel::removeSnapJoint,
                                onClear = viewModel::clearSnapJoints,
                                onAuto = viewModel::autoSpreadSnapJoints,
                                onCommit = viewModel::commitSnapPreview,
                                onApply = viewModel::applySnapJoint,
                            ),
                            onCloseSnapUi = { viewModel.setSnapActive(false) },
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
                        // The card is about the selected object, and that object can leave
                        // the plate while the card is up - removed from the model tools,
                        // or replaced by a join. A card over nothing would offer transforms
                        // of an object that is not there, so it closes with the object.
                        val gizmoModelId = state.selectedModel?.id
                        LaunchedEffect(gizmoModelId) {
                            if (gizmoModelId == null) closeGizmo()
                        }
                        if (gizmoMode != TransformGizmoMode.NONE && gizmoModelId != null) {
                            TransformGizmoOverlay(
                                mode = gizmoMode,
                                readout = gizmoReadout,
                                scalePercent = gizmoScalePercent,
                                compact = gizmoHandleDrag,
                                canUndo = state.canUndoPlacement,
                                undoLabel = state.undoPlacementLabel,
                                onUndo = viewModel::undoPlacement,
                                onMode = { mode ->
                                    gizmoMode = mode
                                    gizmoReadout = null
                                    gizmoScalePercent = 100
                                },
                                onScalePercent = { gizmoScalePercent = it },
                                onScaleFinished = {
                                    if (gizmoScalePercent != 100) {
                                        viewModel.scaleModel(gizmoScalePercent.toDouble())
                                    }
                                    gizmoScalePercent = 100
                                    gizmoReadout = null
                                },
                                onDone = closeGizmo,
                                // The model tools' own drop-to-plate action, surfaced where the hand
                                // already is: it acts on the selected object, exactly as
                                // ViewModel.dropModelToBed does, and its undo line is the one the
                                // panel then shows. Nothing to drop (no selection) or a running
                                // operation disables it with a reason instead of a dead button.
                                onDrop = viewModel::dropModelToBed,
                                dropEnabled = state.selectedModel != null && !state.isBusy,
                                dropDisabledReason = when {
                                    state.selectedModel == null ->
                                        "Nothing to drop: no model is selected"
                                    state.isBusy -> "Wait for the running operation, then drop"
                                    else -> null
                                },
                                // The plate's own settings, from the menu the long press
                                // opens. multiObjectOpen is the one piece of state behind
                                // this and the engine card's button, so both open the one
                                // sheet rather than two that can disagree.
                                objectCount = state.models.size,
                                onOpenMultiObject = { multiObjectOpen = true },
                                // Split goes through the one entry point the model tools
                                // use, and the card closes first: the cut toolbar takes
                                // over the bottom of the model view from there.
                                modelName = state.selectedModel?.name,
                                actionsEnabled = !state.isBusy,
                                onSplit = {
                                    closeGizmo()
                                    openCutUi()
                                },
                                // The card goes with the object it belongs to, before the
                                // removal is asked for: a card left standing would point at
                                // whatever the plate selects next.
                                onRemove = {
                                    val id = state.selectedModel?.id
                                    closeGizmo()
                                    id?.let(viewModel::removeModel)
                                },
                                // Above the model and to the right of it, where it
                                // covers the plate rather than the part being moved.
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp),
                            )
                        }

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
                                    onSelectModel = viewModel::selectModel,
                                    onRemoveModel = viewModel::removeModel,
                                    onArrangeNow = viewModel::arrangePlate,
                                    onOpenMultiObjectSettings = { multiObjectOpen = true },
                                    onMove = viewModel::moveModel,
                                    onRotate = viewModel::rotateModel,
                                    onScale = viewModel::scaleModelTo,
                                    onDropToBed = viewModel::dropModelToBed,
                                    onLayFlat = viewModel::layModelFlat,
                                    onReset = viewModel::resetModelTransform,
                                    onApplyImportedTransform = viewModel::applyImportedSceneTransform,
                                    // The same entry the long-press card's Split uses.
                                    onOpenCutUi = openCutUi,
                                    onOpenSnapUi = {
                                        // The joint and the cut preview are two
                                        // screens of one workflow, and only one
                                        // owns the model's taps: the split defines
                                        // the axis the joint is built on, so the
                                        // cut preview steps aside here.
                                        modelToolsOpen = false
                                        supportPaintUiOpen = false
                                        annotationUiOpen = false
                                        viewModel.setPaintMode(SupportPaintMode.NONE)
                                        viewModel.setAnnotationActive(false)
                                        viewModel.setCutActive(false)
                                        viewModel.setSnapActive(true)
                                    },
                                    onOpenSupportPaintUi = {
                                        modelToolsOpen = false
                                        viewModel.setCutActive(false)
                                        supportPaintUiOpen = true
                                    },
                                    onBrushRadius = viewModel::setBrushRadius,
                                    onClearPaint = viewModel::clearSupportPaint,
                                    dragMove = modelDragMove,
                                    onToggleDragMove = { modelDragMove = !modelDragMove },
                                    canUndoPlacement = state.canUndoPlacement,
                                    undoPlacementLabel = state.undoPlacementLabel,
                                    onUndoPlacement = viewModel::undoPlacement,
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
                        // The phone's sidebar: a handle on the plate's left edge while
                        // it is closed, and the same rail the fold keeps while it is
                        // open. Only one of it and the action bar is expanded.
                        if (!expandedLayout) {
                            PhoneSessionPanel(
                                open = platePanels.railOpen,
                                onExpand = { showPlatePanels(platePanels.openedRail()) },
                                onCollapse = { showPlatePanels(platePanels.closedRail()) },
                                modifier = Modifier.align(Alignment.CenterStart),
                                rail = sessionRail,
                            )
                        }
                        // The collapsed action bar: a small arrow over the model
                        // where the bar's own bottom edge was, with no surface around
                        // it, so the plate keeps the strip the expanded bar stands on.
                        if (!expandedLayout && !platePanels.actionBarShowsRows) {
                            ActionBarHandle(
                                onExpand = { showPlatePanels(platePanels.toggledActionBar()) },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(12.dp),
                            )
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
                                // What the engine will actually be given, so Cura's enabled
                                // expressions are judged against the truth rather than against
                                // the definitions. Only Cura has them; the other two get an
                                // empty map and the sheet leaves their lists ungrouped.
                                values = remember(engine, state.settings, state.extraCuraSettings) {
                                    if (engine == SlicerEngine.CURA) {
                                        AllSettingsGroups.effectiveValues(
                                            specs = catalogSpecs,
                                            appValues = CuraSettingDelta.standaloneValues(state.settings),
                                            extras = state.extraCuraSettings ?: emptyMap(),
                                        )
                                    } else {
                                        emptyMap()
                                    }
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
                            onOpenMultiObject = { multiObjectOpen = true },
                            objectCount = state.models.size,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // The way back to the first init, for an engine added later or a
                        // failure to retry - and the place the CAD screen's own status sends
                        // a user whose Python has never been unpacked.
                        val enginePrepRows by EnginePreparation.rows.collectAsStateWithLifecycle()
                        // What is already prepared is part of the summary, and this is the
                        // first place a user looks after a previous run.
                        LaunchedEffect(Unit) { EnginePreparation.refresh(context) }
                        EnginePreparationCard(
                            rows = enginePrepRows,
                            onOpen = {
                                if (enginePrepSelection.isEmpty()) {
                                    enginePrepSelection = encodeEngineSelection(
                                        EnginePreparation.defaultSelection(engine.name.lowercase()),
                                    )
                                }
                                EnginePreparation.refresh(context)
                                engineSetupOpen = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (engine == SlicerEngine.PRUSA) {
                            LaunchedEffect(Unit) { viewModel.loadPrusaPresets() }
                            PrusaSettingsSheet(
                                state = state,
                                onSettings = viewModel::updatePrusaSettings,
                                onImportConfig = { prusaConfigImportPicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
                                onOpenAllSettings = { allSettingsOpen = true },
                                onOpenSidebarPicker = { sidebarPickerOpen = true },
                                onPrusaPrinter = viewModel::selectPrusaPrinter,
                                onPrusaPrint = viewModel::selectPrusaPrint,
                                onPrusaFilament = viewModel::selectPrusaFilament,
                                onResetToSnapshot = viewModel::resetPrusaToSnapshot,
                                expandedLayout = expandedLayout,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            )
                        } else if (engine == SlicerEngine.ORCA) {
                            OrcaSettingsSheet(
                                state = state,
                                onSettings = viewModel::updateOrcaSettings,
                                onOpenAllSettings = { allSettingsOpen = true },
                                onOpenSidebarPicker = { sidebarPickerOpen = true },
                                onImportProfile = {
                                    orcaProfileImportPicker.launch(
                                        arrayOf("application/json", "application/zip", "*/*"),
                                    )
                                },
                                onResetToSnapshot = viewModel::resetOrcaToSnapshot,
                                expandedLayout = expandedLayout,
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
                                // The window's own decision, above: a fold gets the
                                // two panes, a phone the one column.
                                expandedLayout = expandedLayout,
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
                                onOpenSidebarPicker = { sidebarPickerOpen = true },
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
                        onCadViewer = { cadViewerOpen = true },
                        uiScalePercent = uiScalePercent,
                        onUiScale = { uiScaleOpen = true },
                        diagnosticsOn = diagnosticsOn,
                        onToggleDiagnostics = {
                            // Applied at once rather than through a sheet: it is one
                            // switch, and the badge on the row is its state.
                            Diagnostics.setEnabled(context, !diagnosticsOn)
                        },
                        onOpenDiagnosticsLog = { diagnosticsLogOpen = true },
                        // The slot can send the user to the Plate: what it adds there is
                        // a control for a panel that lives over that surface.
                        extraItems = { moreExtraItems { selectedTab = AppTab.PLATE } },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                }
            }
        }

        if (expandedLayout) {
            Row(modifier = Modifier.fillMaxSize()) {
                sessionRail()
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

    if (uiScaleOpen) {
        AppBottomSheet(
            onDismissRequest = { uiScaleOpen = false },
        ) {
            UiScaleSheet(
                currentPercent = uiScalePercent,
                onChange = onUiScaleChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (multiObjectOpen) {
        AppBottomSheet(
            onDismissRequest = { multiObjectOpen = false },
        ) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                MultiObjectSheet(
                    preferences = state.platePreferences,
                    onPreferences = viewModel::setPlatePreferences,
                    onArrangeNow = viewModel::arrangePlate,
                    objectCount = state.models.size,
                    engine = engine,
                    onDismiss = { multiObjectOpen = false },
                )
            }
        }
    }

    if (diagnosticsLogOpen) {
        AppBottomSheet(
            onDismissRequest = { diagnosticsLogOpen = false },
        ) {
            DiagnosticsLogSheet(onDismiss = { diagnosticsLogOpen = false })
        }
    }

    if (cadViewerOpen) {
        AppBottomSheet(
            onDismissRequest = { cadViewerOpen = false },
        ) {
            CadViewerSettingsSheet(
                current = cadViewerSettings,
                // Stored and applied on every edit rather than on Save: the point of a viewer
                // setting is watching it happen, and a sheet that only took effect on Save meant
                // the one place you could see the change was the one place it had not happened.
                // No toast per toggle either - the viewport is the feedback.
                onChange = { next ->
                    CadViewerPreference.save(context, next)
                    cadViewerSettings = next
                    cadViewport?.applyViewerSettings(next)
                },
                onDone = { cadViewerOpen = false },
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

    // Every tab: the rail's Ask AI door is available wherever the rail is, and opening the
    // chat must never move the selected destination. The overlay floats over whatever the tab
    // is showing; the plate still gets the paint-and-describe workflow it always had.
    if (aiChatOpen) {
        AiChatOverlay(
            // The harness session's own messages - never the in-app agent's.
            messages = viewModel.cadConversations.harness,
            // Sending goes to the backend the shared selection says is shown.
            onSend = ::askSolidCraftAgent,
            // The same stop the CAD agent sheet's Cancel uses, for whichever backend is shown.
            onStop = ::cancelAgentTurn,
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
            busy = viewModel.harnessCadBusy,
            status = viewModel.harnessCadStatus,
            insecure = aiInsecure,
            // One shared selection with the CAD agent sheet: the sheet's mode chips and these
            // chips are the same state, so typing in either box always reaches the backend the
            // chat is showing.
            // The ViewModel holds the selection, so a rotation keeps the source; the persisted
            // agent mode is written too, so the sheet's chips and the next launch agree.
            source = viewModel.chatSource,
            onSourceChange = {
                viewModel.chatSource = it
                setAgentMode(it.agentMode())
            },
            authorLine = chatAuthorship.authorLine,
            authorPausedReason = if (chatAuthorship.inAppPaused) {
                chatAuthorship.pausedLine + " - take the document back in the CAD agent sheet."
            } else {
                null
            },
            harnessLine = if (aiConfigured) {
                "Harness - session connected"
            } else {
                "Harness - not connected"
            },
            // The in-app agent's own conversation, tool rows included.
            inApp = InAppChat(
                messages = viewModel.cadConversations.inApp,
                busy = viewModel.cadBusy,
                status = viewModel.cadStatus,
                backendLine = "In-app - " + agentSettings.endpointLabel(),
                configured = agentSettings.isConfigured,
                onSend = ::askInAppAgentFromChat,
                // The question card: while waiting, a chip or a typed answer becomes the next
                // user message and clears the wait; Dismiss clears it without a turn. Each
                // route records how it ended before clearing the wait, so the card's history
                // line says what actually happened - a chip answer, a typed answer, or no
                // answer at all.
                question = viewModel.pendingQuestion,
                onAnswerQuestion = { answer, fromOptions ->
                    resolvePendingQuestion(
                        if (fromOptions) AiQuestionOutcome.ANSWERED_FROM_OPTIONS
                        else AiQuestionOutcome.ANSWERED,
                    )
                    viewModel.clearPendingQuestion()
                    askInAppAgentFromChat(answer)
                },
                onDismissQuestion = {
                    resolvePendingQuestion(AiQuestionOutcome.DISMISSED)
                    viewModel.clearPendingQuestion()
                },
            ),
            onCopyTranscript = ::copyChatTranscript,
            // The three sizes live here so they survive the chat being closed and reopened; a
            // resize is state only - the engine and a running turn are untouched by it.
            size = chatSize,
            lastExpandedSize = chatExpandedSize,
            onSizeChange = { wanted ->
                chatSize = wanted
                chatExpandedSize = ChatSizes.rememberExpanded(chatExpandedSize, wanted)
            },
            // Back from full screen is a collapse; a second back closes the chat. The action
            // comes from ChatSizes, the same transition the header buttons use.
            onBack = { action ->
                when (action) {
                    ChatBackAction.TO_PANEL -> {
                        chatSize = ChatSize.PANEL
                        chatExpandedSize = ChatSize.PANEL
                    }
                    ChatBackAction.CLOSE -> aiChatOpen = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    // The CAD agent sheet: the whole human surface on a live CAD document besides looking at
    // it, slicing it and exporting it. Kept above the AI chat so the sheet is never behind it.
    if (solidCraftSheetOpen && selectedTab == AppTab.PLATE) {
        AppBottomSheet(onDismissRequest = { viewModel.closeSolidCraftSheet() }) {
            val exportBase = state.cadDocument?.name
                ?.replace(Regex("[^A-Za-z0-9._-]"), "-")
                ?.take(64)
                ?.ifBlank { null }
                ?: "cad-document"
            SolidCraftSheet(
                document = state.cadDocument,
                selectedFace = state.selectedFace,
                faceSelectMode = state.faceSelectMode,
                plateObjectName = state.selectedModel?.name,
                onStartFaceSelect = ::beginFaceSelect,
                onClearFaceSelection = viewModel::clearFaceSelection,
                onAgentLendViewChange = ::setAgentLendView,
                // The same shared selection the chat's source switch drives.
                messages = viewModel.cadConversations.messagesFor(agentSettings.mode.chatSource()),
                agentSettings = agentSettings,
                chatConfigured = aiConfigured,
                // The sheet shows the active backend's own status and busy flag.
                chatBusy = if (agentSettings.mode == AgentMode.IN_APP) {
                    viewModel.cadBusy
                } else {
                    viewModel.harnessCadBusy
                },
                chatStatus = if (agentSettings.mode == AgentMode.IN_APP) {
                    viewModel.cadStatus
                } else {
                    viewModel.harnessCadStatus
                },
                onAgentModeChange = ::setAgentMode,
                onAgentSettingsSave = ::saveAgentSettings,
                onAgentKeyClear = ::clearAgentApiKey,
                onAgentWebSearchChange = ::setAgentWebSearch,
                inAppWebNotice = viewModel.inAppWebNotice,
                authorship = SolidCraftEngine.authorship(),
                onTakeOver = ::takeOverFromExternalAgent,
                onCancelAgent = ::cancelAgentTurn,
                onConnect = viewModel::connectCadAgent,
                onStop = viewModel::stopCadAgent,
                onCloseDocument = {
                    // closeCadDocument ends the document's in-app conversation, agent memory
                    // and pending question (and says so); this clear keeps the sheet's own
                    // contract explicit and is idempotent.
                    viewModel.clearPendingQuestion()
                    viewModel.closeCadDocument()
                },
                onRebuild = viewModel::rebuildCadPlateFromDocument,
                onCopyToken = {
                    requestMcpTokenCopy(
                        context = context,
                        clipLabel = "SolidCraft MCP token",
                        engineName = "SolidCraft CAD agent",
                        tokenFile = {
                            SolidCraftEngine.ensureToken(context)
                            SolidCraftEngine.tokenFile(context)
                        },
                    )
                },
                onNetworkReachableChange = viewModel::setCadBridgeNetworkReachable,
                onCopyConnectLine = { target, address ->
                    requestConnectLineCopy(context, target, address)
                },
                onExport = { format ->
                    when (format) {
                        "step" -> cadStepExportPicker.launch(exportBase + ".step")
                        "stl" -> cadStlExportPicker.launch(exportBase + ".stl")
                        "3mf" -> cad3mfExportPicker.launch(exportBase + ".3mf")
                    }
                },
                onOpenFile = {
                    cadDocumentPicker.launch(
                        arrayOf(
                            "application/step",
                            "application/octet-stream",
                            "model/3mf",
                            "model/obj",
                            "model/gltf-binary",
                            "model/gltf+json",
                            "*/*",
                        ),
                    )
                },
                onAsk = ::askSolidCraftAgent,
                onDismiss = { viewModel.closeSolidCraftSheet() },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            )
        }
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
    onOpenMultiObject: () -> Unit,
    objectCount: Int,
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
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Multi-object printing", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (objectCount > 1) {
                            "$objectCount objects on the plate: placement, spacing and object labels."
                        } else {
                            "Placement, spacing and object labels for a plate holding several models."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onOpenMultiObject) { Text("Settings") }
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
 * The only way an engine's credential leaves the app: ask for the user's fingerprint - or, on a
 * phone with no reader, their screen lock - and copy nothing unless that succeeds.
 *
 * Both hand-overs go through here: the token-only copy in the Blender and CAD menus, and the
 * CAD agent sheet row's one-line copy that carries the connect line AND that engine's token. The
 * token is a credential for an engine that runs code inside this app, so a stray tap must not be
 * able to hand it to whoever is holding the phone. That is the same gesture a banking app puts
 * in front of a payment.
 *
 * [content] is evaluated after authentication, never before it: the token file is read (and the
 * ready-to-paste line built) only on the far side of the prompt. The clip is marked sensitive so
 * Android keeps it out of the clipboard preview, and nothing here logs the value.
 */
private fun requestSensitiveClipCopy(
    context: Context,
    clipLabel: String,
    promptTitle: String,
    engineName: String,
    copiedMessage: String,
    cancelledMessage: String,
    unavailableMessage: String,
    content: (Context) -> String?,
) {
    // BiometricPrompt hosts itself in a fragment, so its host has to be a
    // FragmentActivity - MainActivity is one. Anything else cannot be asked, so it
    // gets no token.
    val activity = context as? FragmentActivity
    if (activity == null) {
        Toast.makeText(
            context,
            "This needs the app's own screen to unlock",
            Toast.LENGTH_SHORT,
        ).show()
        return
    }

    // BIOMETRIC_WEAK already carries BIOMETRIC_STRONG's bits; all three are named to
    // say what the prompt accepts - any fingerprint (or face), or the device
    // credential behind it.
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    // With DEVICE_CREDENTIAL allowed, canAuthenticate reports success whenever the phone
    // has a lock - fingerprint enrolled or not - so the refusal below is the phone with
    // no lock at all. The device's own answer is read as well, because a lock the
    // framework will not confirm is still the user's way in.
    val canAuthenticate = BiometricManager.from(activity).canAuthenticate(authenticators)
    val deviceSecure =
        activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS && !deviceSecure) {
        // Short enough that the toast shows all of it: the longer "Set up a fingerprint or
        // screen lock in Settings to protect this token" was cut off mid-word on the phone,
        // and there is no tap-through to that Settings page to carry the rest.
        Toast.makeText(
            activity,
            "Set up a screen lock or fingerprint in Settings",
            Toast.LENGTH_LONG,
        ).show()
        return
    }

    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val text = runCatching { content(activity) }.getOrNull()
                if (text.isNullOrBlank()) {
                    Toast.makeText(activity, unavailableMessage, Toast.LENGTH_SHORT).show()
                    return
                }
                // Marked sensitive: the clipboard holds a credential, and the OS must not
                // preview it in the clipboard overlay or the share sheet.
                val clip = AgentBridgeCopy.sensitiveClip(clipLabel, text)
                activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                Toast.makeText(activity, copiedMessage, Toast.LENGTH_SHORT).show()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Cancel, back, a locked-out sensor, nothing enrolled: every one of them
                // leaves the clipboard alone and says so.
                Toast.makeText(activity, cancelledMessage, Toast.LENGTH_SHORT).show()
            }
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(promptTitle)
            .setSubtitle("Anyone you give this to can run code in the app's $engineName engine")
            // No setNegativeButtonText: with DEVICE_CREDENTIAL among the allowed
            // authenticators the builder rejects one ("Negative text must not be set if
            // device credential authentication is allowed"), and the system prompt puts
            // its own Cancel beside the "Use PIN/pattern/password" button.
            .setAllowedAuthenticators(authenticators)
            .build(),
    )
}

/**
 * Copies an engine's token alone: the Blender and CAD menus' **Copy MCP token** items.
 *
 * [tokenFile] is evaluated after authentication, never before it, because Blender's token is
 * generated on first use - asking the engine for it stays on the far side of the prompt.
 */
private fun requestMcpTokenCopy(
    context: Context,
    clipLabel: String,
    engineName: String,
    tokenFile: () -> File,
) {
    requestSensitiveClipCopy(
        context = context,
        clipLabel = clipLabel,
        promptTitle = "Copy MCP token",
        engineName = engineName,
        copiedMessage = "MCP token copied",
        cancelledMessage = "MCP token not copied",
        unavailableMessage = "The MCP token is not available yet",
    ) {
        runCatching { tokenFile().takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
    }
}

/**
 * Copies one CAD agent sheet row's hand-over: the connect line AND that engine's own token, one
 * line, so the user authenticates once and pastes once.
 *
 * The token is read here - after the prompt, never before - and [AgentBridgeCopy] builds the
 * single line. An engine that has never run has no token file, so there is nothing honest to
 * copy: the toast names that instead of putting half a line on the clipboard. The sheet already
 * disables Copy for that row, so this is the race-path answer.
 */
private fun requestConnectLineCopy(
    context: Context,
    target: AgentBridgeTarget,
    address: String,
) {
    requestSensitiveClipCopy(
        context = context,
        clipLabel = target.name + " connect line",
        promptTitle = "Copy connect line",
        engineName = target.engineName,
        copiedMessage = "Connect line and token for " + target.name + " copied",
        cancelledMessage = "Connect line not copied",
        unavailableMessage = AgentBridgeCopy.MISSING_TOKEN_REASON,
    ) { ctx ->
        when (
            val ready = AgentBridgeCopy.line(
                address,
                target.port,
                AgentBridgeTokens.read(ctx, target.port),
            )
        ) {
            is AgentBridgeCopy.Ready.Line -> ready.text
            is AgentBridgeCopy.Ready.MissingToken -> null
        }
    }
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
    onCadViewer: () -> Unit,
    uiScalePercent: Int,
    onUiScale: () -> Unit,
    diagnosticsOn: Boolean,
    onToggleDiagnostics: () -> Unit,
    onOpenDiagnosticsLog: () -> Unit,
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
                subtitle = if (nonPlanarEnabled) "Non-planar slicing · enabled" else "Non-planar slicing print",
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
            MoreDivider()
            MoreRow(
                icon = AppIcons.Cube,
                title = "CAD viewer",
                subtitle = "Render size, shading and the status line",
                onClick = onCadViewer,
            )
        }

        MoreSectionLabel("Display")
        Card(modifier = Modifier.fillMaxWidth()) {
            MoreRow(
                icon = AppIcons.Settings,
                title = "Interface scale",
                subtitle = "Draw text, controls and spacing at $uiScalePercent%",
                enabled = !state.isBusy,
                badge = if (uiScalePercent == UiScale.DEFAULT_PERCENT) null else "$uiScalePercent%",
                onClick = onUiScale,
            )
        }

        MoreSectionLabel("Diagnostics")
        Card(modifier = Modifier.fillMaxWidth()) {
            MoreRow(
                icon = AppIcons.Wrench,
                title = "App log",
                subtitle = if (diagnosticsOn) {
                    "Recording everything the app reports, failures in red"
                } else {
                    "Off · failures still appear in the viewer's corner"
                },
                enabled = !state.isBusy,
                badge = if (diagnosticsOn) "ON" else "OFF",
                onClick = onToggleDiagnostics,
            )
            MoreDivider()
            MoreRow(
                icon = AppIcons.Console,
                title = "Open the app log",
                subtitle = "Everything the app reported since it was last cleared",
                onClick = onOpenDiagnosticsLog,
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
                // Nothing to open: a chevron and a ripple promise otherwise.
                enabled = false,
            )
            MoreDivider()
            MoreRow(
                icon = AppIcons.Shield,
                title = "Safety notes",
                subtitle = "Inspect every model, setting and generated G-code before printing",
                onClick = {},
                // Nothing to open: a chevron and a ripple promise otherwise.
                enabled = false,
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

/** The name a SolidCraft menu Export writes: the document's own, made file-safe. */
internal fun cadExportBase(state: MainUiState): String =
    state.cadDocument?.name
        ?.replace(Regex("[^A-Za-z0-9._-]"), "-")
        ?.take(64)
        ?.ifBlank { null }
        ?: "design"

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

/**
 * The Settings entry to the first-init screen.
 *
 * One sentence of state - how many engines are ready, or that one failed - so the answer is
 * visible without opening anything, and a button to open the screen that does the work.
 */
@Composable
private fun EnginePreparationCard(
    rows: List<EnginePreparation.Row>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = rows.count { it.phase == EnginePreparation.Phase.READY }
    val failed = rows.count { it.phase == EnginePreparation.Phase.FAILED }
    val running = rows.any { it.phase == EnginePreparation.Phase.RUNNING }
    Card(modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text("Engine files", style = MaterialTheme.typography.titleMedium)
            Text(
                text = when {
                    running -> "Preparing engines…"
                    failed > 0 -> "$ready ready, $failed failed - open to see why and retry"
                    ready == rows.size -> "All ${rows.size} engines are prepared"
                    else -> "$ready of ${rows.size} engines prepared"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpen) {
                Text("Prepare the engines")
            }
        }
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
    /** A tap on the path view's memory readout: the next preview memory profile. */
    onPreviewMemoryProfileChange: (PreviewMemoryProfile) -> Unit,
    /** Reads the layer preview of a restored artifact, which no slice has parsed this session. */
    onEnsureLayerPreview: () -> Unit,
    /** True on the app's expanded (unfolded) layout; the snap panel splits on it. */
    expandedLayout: Boolean,
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
    onSurfacePickEnd: () -> Unit,
    /** A tap that picked a face of the live CAD document (select mode). */
    onFacePick: (MeshPicker.Hit) -> Unit,
    /** One tap: the amber tint, the HUD chip and the prompt block all go. */
    onClearFaceSelection: () -> Unit,
    /** Ends select mode; a selected face stays selected, tint and all. */
    onExitFaceSelectMode: () -> Unit,
    /** The plate's GL view, for the agent's plate render; null when it goes. */
    onPlateViewChanged: (ModelSurfaceView?) -> Unit,
    /** A tap on a part: the index in the plate's model list the viewer was given. */
    onModelPicked: (Int) -> Unit,
    /** A tap while the Snap fit tool is open: the model point the ray hit. */
    onJointPick: (MeshPicker.Hit) -> Unit,
    /** A drag while "Cut wall" is armed: the point the stroke began on, and its plate direction. */
    onJointStroke: (MeshPicker.Hit, Vec3) -> Unit,
    /** An arrow of the joint's own gizmo went down: that axis is live from here. */
    onJointAxisGrab: (ModelPlacement.Axis) -> Unit,
    /** The same arrow, held: millimetres along it since the grab. */
    onJointAxisDrag: (ModelPlacement.Axis, Float) -> Unit,
    /** The arrow came up: the axis goes dark. */
    onJointAxisRelease: () -> Unit,
    onPaintMode: (SupportPaintMode) -> Unit,
    /** True while a finger drag moves the model across the plate instead of orbiting. */
    dragMove: Boolean,
    /** A finished drag: the plate movement it asked for, in millimetres. */
    onModelDrag: (Float, Float) -> Unit,
    /** Live plate movement while a drag is in progress, for the readout. */
    onModelDragPreview: (Float, Float) -> Unit,
    /** A drag on a gizmo arrow: millimetres along that axis, and the same while down. */
    onModelAxisMove: (ModelPlacement.Axis, Float) -> Unit,
    onModelAxisMovePreview: (ModelPlacement.Axis, Float) -> Unit,
    /** True while a handle is held, so the menu can step aside. */
    onHandleDragActive: (Boolean) -> Unit,
    /** The on-model gizmo: which mode is up, and what it reports. */
    gizmoMode: TransformGizmoMode,
    scalePreview: Float,
    onTransformRequested: (Float, Float) -> Unit,
    onRotatePreview: (ModelPlacement.Axis, Float) -> Unit,
    onRotateCommitted: (ModelPlacement.Axis, Float) -> Unit,
    onCloseSupportPaintUi: () -> Unit,
    onOpenDiagnosticsLog: () -> Unit,
    onAnnotationTap: (AnnotationGesture) -> Unit,
    onAnnotationAdjust: (SegmentEnd, AnnotationGesture) -> Unit,
    onAnnotationZAdjustStart: (SegmentEnd) -> Unit,
    onAnnotationZAdjust: (AnnotationGesture) -> Unit,
    onAnnotationZAdjustEnd: () -> Unit,
    annotationActions: AnnotationActions,
    onCloseAnnotationUi: () -> Unit,
    /** The split tool: the cut plane and the action that commits it. */
    cutActions: CutActions,
    /** The snap fit tool, over the pair the split left behind. */
    snapActions: SnapActions,
    onCloseSnapUi: () -> Unit,
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
    // The selection is a face, not a screen position: an orbit keeps it, and only the plain-word
    // description changes, so the HUD is re-worded for the camera the user has now.
    val hudFace = state.selectedFace?.let { face ->
        val objectId = state.cadDocument?.parts
            ?.firstOrNull { it.body == face.body }
            ?.objectId
        val plateObject = state.models.firstOrNull { it.id == objectId }
        if (plateObject == null || modelOrientation == null) {
            face
        } else {
            face.rewordedFor(
                plateObject.placement.documentPlacement(plateObject.sourceMesh),
                modelOrientation,
            )
        }
    }
    Box(modifier = modifier) {
        val preview = state.layerPreview.takeIf { gcodeAvailable }
        when {
            // A restored artifact has no preview until it is read: the Layers tab says so and
            // reads it, rather than quietly showing the model instead.
            viewerMode == ViewerMode.LAYERS && gcodeAvailable && preview == null -> {
                LaunchedEffect(state.gcodePath) { onEnsureLayerPreview() }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Reading the file's layers…", modifier = Modifier.padding(24.dp))
                }
            }
            viewerMode == ViewerMode.LAYERS && preview != null -> LayerPreviewView(
                preview = preview,
                engineLabel = state.sliceEngine?.label,
                selectedLayerIndex = selectedLayerIndex,
                events = state.layerEvents,
                onLayerSelected = onLayerSelected,
                onEditEvents = onEditLayerEvents,
                memoryProfile = state.previewMemoryProfile,
                onMemoryProfileChange = onPreviewMemoryProfileChange,
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
                    memoryProfile = state.previewMemoryProfile,
                    onMemoryProfileChange = onPreviewMemoryProfileChange,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                NozzlePathView(
                    gcodePath = requireNotNull(state.gcodePath),
                    beadHeightMm = state.settings.layerHeightMm,
                    beadLineWidthMm = state.settings.lineWidthMm,
                    filamentDiameterMm = state.settings.filamentDiameterMm,
                    memoryProfile = state.previewMemoryProfile,
                    onMemoryProfileChange = onPreviewMemoryProfileChange,
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
                // A refused move never replaces the geometry, so its preview has to
                // be dropped when the operation ends without one.
                LaunchedEffect(state.isBusy, modelView) {
                    if (!state.isBusy) modelView?.clearDragPreview()
                }
                DisposableEffect(modelView) {
                    onPlateViewChanged(modelView)
                    onDispose { onPlateViewChanged(null) }
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
                // The plate's face tint is state (a face and its triangles), and the renderer
                // holds the buffer that draws it. Re-applying it on every layout as well as on
                // every recomposition is what keeps it on screen when the plate RESIZES - the
                // agent sheet opening and closing changes the viewport, and a frame the agent
                // renders during that must show the tint the user sees.
                fun applyFaceTint(view: ModelSurfaceView) {
                    // A face stays tinted while its own part is on the plate - not only while that
                    // part is the selected model. Selecting another object used to drop the amber
                    // while the chip and the agent's SELECTED FACE block still named the face
                    // (audit VIEW-3).
                    val tintMesh = state.faceTintMesh
                    val onPlate = tintMesh != null && state.models.any { it.sourceMesh === tintMesh }
                    view.setFaceTint(
                        if (state.selectedFace != null && onPlate) state.faceTintTriangles else null,
                    )
                }
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { modelView?.let(::applyFaceTint) },
                    factory = { context ->
                        ModelSurfaceView(context, effectivePrinter).also { modelView = it }
                    },
                    update = { view ->
                        // Everything on the plate is drawn; the tools stay on the selected one.
                        //
                        // The snap fit's live preview is drawn in place of the
                        // halves it was booleaned from. The plate's own list is
                        // untouched - what changes is only what this view puts
                        // on the screen, and the preview meshes travel with the
                        // state that produced them.
                        val preview = state.snapShownMeshes
                        val previewIndices = if (preview == null) {
                            emptyMap()
                        } else {
                            listOf(
                                state.snapLowHalfId to preview.lowMesh,
                                state.snapHighHalfId to preview.highMesh,
                            ).mapNotNull { (id, mesh) ->
                                val index = state.models.indexOfFirst { it.id == id }
                                if (index >= 0) index to mesh else null
                            }.toMap()
                        }
                        view.setMeshes(
                            meshes = state.models.map { it.mesh },
                            selectedIndex = if (preview != null) {
                                previewIndices.keys.minOrNull()
                                    ?: state.models.indexOfFirst { it.id == state.selectedModel?.id }
                                        .coerceAtLeast(0)
                            } else {
                                state.models.indexOfFirst { it.id == state.selectedModel?.id }
                                    .coerceAtLeast(0)
                            },
                        )
                        view.setPreviewObjects(previewIndices)
                        view.jointPickActive = state.snapActive
                        view.onJointPick = onJointPick
                        // A stroke is only a cut stroke while one is armed: with
                        // the panel merely open a drag still turns the model.
                        view.jointStrokeActive = state.snapActive && state.snapCuttingWall
                        view.onJointStroke = onJointStroke
                        // The joint being moved: the plate's own move arrows on the
                        // hook, and a drag that slides it along the seam. The arrows
                        // are the state's, so they can never be left standing with no
                        // joint under them.
                        view.jointGizmo = state.snapMoveGizmo
                        view.jointMoveActive = state.snapActive && state.snapMovingJoint
                        view.onJointAxisGrab = onJointAxisGrab
                        view.onJointAxisDrag = onJointAxisDrag
                        view.onJointAxisRelease = onJointAxisRelease
                        view.onModelPicked = onModelPicked
                        view.paintMode = state.paintMode
                        // One tap is either a face pick or a Smart Infill surface, never both;
                        // the face pick is reported once per gesture (see faceSelectActive).
                        view.surfacePickActive = state.smartInfillPicking || state.faceSelectMode
                        view.faceSelectActive = state.faceSelectMode
                        view.setSmartInfillOverlay(state.smartInfillOverlay)
                        // Every object's paint, not just the selected one's: the viewer draws the whole plate.
                        view.setPaintStates(state.models.map { it.supportPaint })
                        view.dragMoveActive = dragMove
                        view.onModelDragCommitted = onModelDrag
                        view.onModelDragPreview = onModelDragPreview
                        view.onModelAxisMove = onModelAxisMove
                        view.onModelAxisMovePreview = onModelAxisMovePreview
                        view.onHandleDragActive = onHandleDragActive
                        view.gizmoMode = gizmoMode
                        view.scalePreview = scalePreview
                        view.onTransformRequested = onTransformRequested
                        view.onRotatePreview = onRotatePreview
                        view.onRotateCommitted = onRotateCommitted
                        view.onPaintHit = onPaintHit
                        view.onSurfacePick = if (state.faceSelectMode) onFacePick else onSurfacePick
                        view.onSurfacePickEnd = onSurfacePickEnd
                        // The face tint is the renderer's own buffer; the paint state beside it
                        // is untouched by a selection. It is only drawn for the mesh whose
                        // triangles it names: a repaint replaces that mesh, so a stale index can
                        // never tint a triangle that now belongs to something else.
                        applyFaceTint(view)
                        view.annotationActive = state.annotationActive
                        view.onAnnotationTap = onAnnotationTap
                        view.onAnnotationAdjust = onAnnotationAdjust
                        view.onAnnotationZAdjustStart = onAnnotationZAdjustStart
                        view.onAnnotationZAdjust = onAnnotationZAdjust
                        view.onAnnotationZAdjustEnd = onAnnotationZAdjustEnd
                        view.setAnnotationOverlay(state.annotationOverlay)
                        // The cut preview is a shader clip plane on the selected
                        // object: moving the slider changes a uniform, and no
                        // vertex is rebuilt until the split is committed.
                        view.setCutPlane(
                            plane = if (state.cutActive) {
                                ModelCutPlane(state.cutAxis, state.cutOffsetMm.toFloat())
                            } else {
                                null
                            },
                            objectIndex = state.models.indexOfFirst { it.id == state.selectedModel?.id },
                        )
                        view.onOrientationChanged = onOrientationChanged
                    },
                )
            }
        }

        if (viewerMode == ViewerMode.MODEL && (state.faceSelectMode || hudFace != null)) {
            FaceSelectHud(
                face = hudFace,
                picking = state.faceSelectMode,
                onClear = onClearFaceSelection,
                onDone = onExitFaceSelectMode,
                // Top-right, below the CAD badge and the status chevrons: the badge opens the
                // agent sheet and owns the top-start corner, and the phone's sidebar can stand
                // open down the left edge - the chip must be readable under either.
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 88.dp, start = 12.dp, end = 12.dp),
            )
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

        if (state.cutActive && viewerMode == ViewerMode.MODEL && state.mesh != null) {
            CutToolbar(
                state = state,
                actions = cutActions,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            )
        }

        if (state.snapActive && viewerMode == ViewerMode.MODEL) {
            SnapToolbar(
                state = state,
                actions = snapActions,
                onClose = onCloseSnapUi,
                // The same width breakpoint the model tools sheet splits on: a
                // fold puts the three groups side by side, a phone stacks them.
                expandedLayout = expandedLayout,
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

        // A failure lands in the corner of the viewer, which is where the user is
        // looking when one happens - and it lands there whether or not the app log
        // is switched on. It used to be written only to the status line, which
        // lives in a card folded down to a chevron, so the outcome of every
        // operation was invisible by default: a slice that failed looked exactly
        // like a slice that was never asked for.
        DiagnosticsCorner(
            onOpenLog = onOpenDiagnosticsLog,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
        )



        // The Plate's floating cards live on the right now, clear of the session
        // rail's column on the left: the viewer's own mode switch first, then the
        // printer card and the notice banner under it. They are translucent - the
        // model is what the plate is for - and they are content of this box, while
        // a menu opened from the top bar is a popup in its own window, so the menu
        // always draws over them.
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (preview != null || gcodeAvailable) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = PLATE_CARD_ALPHA),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ViewerModeButton("Model", ViewerMode.MODEL, viewerMode, true, onViewerMode)
                        ViewerModeButton("Layers", ViewerMode.LAYERS, viewerMode, preview != null || gcodeAvailable, onViewerMode)
                        ViewerModeButton("Path", ViewerMode.NOZZLE_PATH, viewerMode, gcodeAvailable, onViewerMode)
                    }
                }
            }
            CollapsibleCard(
                // Open, this is the one header line that identifies the printer:
                // name plus build volume, and 340dp is what keeps it on one
                // line; the old header could stay at 290dp because the
                // dimensions had a second line to wrap onto. Folded, the card
                // is the chevron alone, so it stops holding the plate's
                // top-right corner while the model is being inspected.
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
                if (state.belowBedCutMm > 0.0) {
                    // A caution, not a refusal: the move is allowed and this is
                    // what it costs. Amber rather than the red the compatibility
                    // warnings below use, and it carries the amount so the user
                    // knows how much of the model is going in the bin.
                    Text(
                        state.belowBedNotice,
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnAmber,
                        modifier = Modifier.padding(top = 3.dp),
                    )
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

    }
}

/**
 * The status banner in the plate's top-right corner: the ViewModel's status line
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
 * The navigation rail: the four destinations and the chat's own door (an action, not a
 * destination), then the printing session, then the Plate's actions - all in one column, which
 * is the only column a fold has.
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
    /** True when a blocked Slice is still the tappable one: a live CAD document. */
    sliceTappable: Boolean = false,
    /** "Convert to STL" or "Convert again" while a document is loaded; null when there is none. */
    cadConvertLabel: String? = null,
    onConvertCad: () -> Unit = {},
    onSlice: () -> Unit,
    onExportGcode: () -> Unit,
    onTools: () -> Unit,
    /** True when the plate is drawing the engine's CAD document. */
    cadDocument: Boolean = false,
    /** Starts the CAD agent and opens its sheet, where a change can be asked for. */
    onCadAgent: () -> Unit = {},
    /** Opens the AI chat overlay. An action, not a destination: the tab stays put. */
    onAskAi: () -> Unit = {},
    /**
     * The one time Ask AI carries a badge: the in-app agent asked a question and the chat is
     * closed. Idle it is a plain action with no badge, by design.
     */
    askAiBadge: Boolean = false,
    onSettings: (String, (SlicerSettings) -> SlicerSettings) -> Unit,
    onPrusaSettings: (String, (PrusaSliceSettings) -> PrusaSliceSettings) -> Unit,
    onOrcaSettings: (String, (OrcaSliceSettings) -> OrcaSliceSettings) -> Unit,
    /** Writes one of an engine's own settings, which is how an added sidebar row edits. */
    onExtraSetting: (String, String) -> Unit,
    /** Opens the sidebar picker: the rail's + is the plate's own door into it. */
    onAddSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = plateSessionSummary(engine, state)
    val context = LocalContext.current
    val chosenRows = state.sidebarSettings.forEngine(engine)
    // The catalogue is parsed only when a row needs it: the built-in four are the
    // hand-written editors they always were, and a sidebar nobody has edited costs
    // nothing. A sidebar that holds an added setting pays for it once per engine.
    val sidebarSpecs = remember(engine, chosenRows) {
        if (chosenRows.all(::isBuiltInSidebarRow)) {
            emptyList()
        } else {
            when (engine) {
                SlicerEngine.CURA -> AllSettingsCatalogs.cura(context.assets)
                SlicerEngine.PRUSA -> AllSettingsCatalogs.prusa(context.assets)
                SlicerEngine.ORCA -> AllSettingsCatalogs.orca(context.assets)
            }
        }
    }
    // Built once per settings change, not once per recomposition: the rail redraws
    // on every state update and the map covers the whole catalogue.
    val sidebarValues = remember(engine, state.settings, state.extraCuraSettings, state.extraPrusaSettings, state.extraOrcaSettings, sidebarSpecs) {
        if (sidebarSpecs.isEmpty()) emptyMap() else sidebarEffectiveValues(engine, sidebarSpecs, state)
    }
    val values = sessionSidebarRows(
        engine = engine,
        summary = summary,
        state = state,
        chosen = chosenRows,
        specs = sidebarSpecs,
        values = sidebarValues,
        onSettings = onSettings,
        onPrusaSettings = onPrusaSettings,
        onOrcaSettings = onOrcaSettings,
        onExtraSetting = onExtraSetting,
    )
    // The row's own id, not the value: the dialog reads the row again on every
    // recomposition, so a choice that applies at once moves the check mark while it
    // stays open.
    var editing by remember { mutableStateOf<String?>(null) }

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
        Column(modifier = Modifier.width(SessionRailWidth).fillMaxHeight()) {
            // Everything above the + scrolls; the + itself does not, so the
            // sidebar's own settings are one tap away on any window height.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = EnderSlicerDimens.Space12),
                verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space4),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            AppTab.entries.forEach { tab ->
                RailDestination(tab, selected == tab) { onSelect(tab) }
            }
            // The AI chat's own door: in the same list as the destinations, drawn the same
            // way, but an action - it opens the chat overlay and never changes the tab or
            // shows a selected state.
            RailAction(
                icon = AppIcons.Sparkle,
                label = "Ask AI",
                onClick = onAskAi,
                badge = askAiBadge,
            )
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
            // The below-bed caution is its own line, amber and not a refusal: the
            // warnings chip below counts the red compatibility warnings alone, so
            // the cut is not counted twice under a colour that says "error".
            if (state.belowBedCutMm > 0.0) {
                Text(
                    state.belowBedNotice,
                    style = MaterialTheme.typography.labelSmall,
                    color = WarnAmber,
                    modifier = Modifier.padding(horizontal = EnderSlicerDimens.Space8),
                )
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
                // A live CAD document greys Slice out but keeps it tappable: the tap names the
                // reason, and the button is exactly where the user looks for the answer.
                enabled = state.engineAvailable &&
                    state.modelPath != null &&
                    !state.isBusy &&
                    (sliceBlockedReason == null || sliceTappable),
                colors = if (sliceBlockedReason != null) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
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
            // The one press that turns the live document into the STL models Slice needs. It is
            // never a side effect of Slice: the user asked for the two to be separate presses.
            cadConvertLabel?.let { label ->
                // Convert is deliberate and it owns the document: while the CAD agent is working
                // the button greys out and names why, instead of running a conversion under a turn
                // that is still editing the document (audit cad #10).
                val convertBlocked = state.cadConvertBlockedReason
                OutlinedButton(
                    onClick = onConvertCad,
                    enabled = !state.isBusy && convertBlocked == null,
                    contentPadding = PaddingValues(
                        horizontal = EnderSlicerDimens.Space4,
                        vertical = EnderSlicerDimens.Space4,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = EnderSlicerDimens.Space8),
                ) {
                    RailButtonLabel(label)
                }
                if (convertBlocked != null) {
                    Text(
                        text = convertBlocked,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = EnderSlicerDimens.Space8,
                                end = EnderSlicerDimens.Space8,
                                bottom = EnderSlicerDimens.Space4,
                            ),
                    )
                }
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
            // The agent is the CAD document's author: this is where a human asks for a
            // change, and it is beside the model tools because those still work - they edit
            // the plate's derived mesh, never the document.
            OutlinedButton(
                onClick = onCadAgent,
                enabled = !state.isBusy,
                contentPadding = PaddingValues(
                    horizontal = EnderSlicerDimens.Space4,
                    vertical = EnderSlicerDimens.Space4,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EnderSlicerDimens.Space8),
            ) {
                RailButtonLabel(if (cadDocument) "CAD agent · live" else "CAD agent")
            }

            // Stacked rather than laid out as a row: 80dp cannot hold "Layer
            // height 0,20 mm" on one line, and this is Material's rail width. The
            // rows are the user's own list now, in the order the picker set.
            values.forEach { row ->
                SessionValueTile(
                    row.value,
                    modifier = Modifier.testTag(SESSION_SIDEBAR_ROW_TAG_PREFIX + row.id),
                    enabled = !state.isBusy,
                ) {
                    editing = row.id
                }
            }
            }

            // Under the values and outside their scroll: the rail's own door into the
            // sidebar picker, beside the buttons the rail already carries.
            OutlinedButton(
                onClick = onAddSettings,
                contentPadding = PaddingValues(
                    horizontal = EnderSlicerDimens.Space4,
                    vertical = EnderSlicerDimens.Space4,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EnderSlicerDimens.Space8)
                    .padding(bottom = EnderSlicerDimens.Space8)
                    .testTag(SESSION_RAIL_ADD_TAG),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add", maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    editing?.let { id ->
        values.firstOrNull { it.id == id }?.let { row ->
            SessionValueDialog(row.value) { editing = null }
        }
    }
}

/**
 * The phone plate's two collapsible surfaces - the sidebar panel and the action bar
 * - as one state, because only one of them is ever expanded.
 *
 * Expanding the panel folds the bar away: the panel carries Slice, Export and Model
 * tools, so the bar becomes its own arrow until it is brought back, and that same
 * tap folds the panel. Folding the panel with its own arrow leaves the bar where it
 * was - [actionBarExpanded] is the bar's own remembered state, and the panel is the
 * other's.
 */
internal data class PlatePanelState(
    val railOpen: Boolean = false,
    val actionBarExpanded: Boolean = true,
) {
    /** True while the bar draws its rows. The panel being open always wins. */
    val actionBarShowsRows: Boolean get() = actionBarExpanded && !railOpen

    /** True while the bar is only its own arrow. */
    val actionBarHandleOnly: Boolean get() = railOpen

    fun openedRail(): PlatePanelState = PlatePanelState(railOpen = true, actionBarExpanded = false)

    fun closedRail(): PlatePanelState = copy(railOpen = false)

    /** The bar's own arrow: it brings the bar back and folds the panel in one tap. */
    fun toggledActionBar(): PlatePanelState = if (railOpen) {
        PlatePanelState(railOpen = false, actionBarExpanded = true)
    } else {
        copy(actionBarExpanded = !actionBarExpanded)
    }
}

/**
 * The phone's sidebar: a slim handle on the plate's left edge while it is closed,
 * and the rail itself - the same composable the fold keeps as a column - while it
 * is open.
 *
 * Opening it is what folds the plate's action bar away: the rail carries Slice,
 * Export and Model tools, so the bar becomes its own arrow until it is brought
 * back, and the plate keeps that strip of height while the panel is up. The handle
 * is the only thing on the plate's left edge, so the viewer's own gestures - orbit,
 * pinch, a tap on the model - are never behind something that would swallow them.
 */
@Composable
internal fun PhoneSessionPanel(
    open: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    rail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (open) {
            Column(modifier = Modifier.fillMaxHeight()) {
                SidebarHandle(
                    onClick = onCollapse,
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    label = "Hide the sidebar",
                )
                Box(modifier = Modifier.weight(1f)) { rail() }
            }
        } else {
            SidebarHandle(
                onClick = onExpand,
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                label = "Show the sidebar",
            )
        }
    }
}

/**
 * The sidebar's handle, closed and open alike: a small arrow in a small circle,
 * not a slab. The 48dp box is the touch target Material asks for; what is drawn is
 * the 32dp circle inside it, so the plate keeps the rest of the corner.
 */
@Composable
private fun SidebarHandle(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            tonalElevation = 4.dp,
            color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.9f),
            shape = CircleShape,
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** The rail's own + row, tagged so a test can find it in either layout. */
internal const val SESSION_RAIL_ADD_TAG = "session-rail-add"

/**
 * What the sidebar picker's back button says: where it returns to, named by the tab
 * it was opened over. The rail's + can raise the picker from any of them, and the
 * settings sheet's row raises it from Print settings.
 */
internal fun sidebarBackLabel(tab: AppTab): String =
    if (tab == AppTab.PLATE) "Back to the plate" else "Back to " + tab.label

/**
 * The sidebar picker as a screen: the same [SessionSidebarPicker] the settings sheet
 * opens, with the one button back that names the place it came from.
 */
@Composable
private fun SidebarPickerScreen(
    engine: SlicerEngine,
    state: MainUiState,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onClose: () -> Unit,
    backLabel: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val specs = remember(engine) {
        when (engine) {
            SlicerEngine.CURA -> AllSettingsCatalogs.cura(context.assets)
            SlicerEngine.PRUSA -> AllSettingsCatalogs.prusa(context.assets)
            SlicerEngine.ORCA -> AllSettingsCatalogs.orca(context.assets)
        }
    }
    Column(modifier = modifier) {
        SessionSidebarPicker(
            engineLabel = engine.label,
            specs = specs,
            chosen = state.sidebarSettings.forEngine(engine),
            values = remember(engine, specs, state) {
                sidebarEffectiveValues(engine, specs, state)
            },
            blockedKeys = when (engine) {
                SlicerEngine.CURA -> AllSettingsCatalogs.CURA_BLOCKED_KEYS
                SlicerEngine.PRUSA -> AllSettingsCatalogs.PRUSA_BLOCKED_KEYS
                SlicerEngine.ORCA -> AllSettingsCatalogs.ORCA_BLOCKED_KEYS
            },
            onAdd = onAdd,
            onRemove = onRemove,
            onMove = onMove,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(backLabel)
        }
    }
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

/**
 * One action in the rail's destination list.
 *
 * Drawn like a destination - same width, padding, shape, icon size and label - because it
 * sits in the same column, but it is never selected and never carries a badge: opening the
 * chat is a door, not a place the app is standing in.
 */
@Composable
private fun RailAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    badge: Boolean = false,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EnderSlicerDimens.Space8)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = EnderSlicerDimens.Space6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space4),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = color)
            if (badge) {
                // The user's "blinking ai bubble": a small pulsing dot, the one state that
                // earns a badge. Accessibility is told what it means; there is no text on it.
                val transition = rememberInfiniteTransition(label = "ask-ai-pulse")
                val alpha by transition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                    label = "ask-ai-pulse-alpha",
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = alpha))
                        .semantics {
                            contentDescription = "CAD agent is waiting for an answer"
                        },
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
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
 * Phone bottom action block: the Slice button, Export, the Model tools entry and
 * the export hint.
 *
 * This is the **expanded** bar, and only that: collapsed, the plate draws
 * [ActionBarHandle] - a small floating arrow over the model - and no bottom bar at
 * all, so the strip the bar stood on goes back to the plate. The expand decision is
 * hoisted to the root of [EnderSlicerApp] in [PlatePanelState] for the same reason
 * the floating cards hoist theirs: selecting another tab disposes the Plate subtree,
 * so a flag remembered inside the block would be lost on the way back.
 */
@Composable
internal fun ActionBar(
    state: MainUiState,
    nonPlanarEnabled: Boolean,
    conicalEnabled: Boolean,
    sliceBlockedReason: String?,
    /** True when a blocked Slice is still the tappable one: a live CAD document. */
    sliceTappable: Boolean = false,
    /** "Convert to STL" or "Convert again" while a document is loaded; null when there is none. */
    cadConvertLabel: String? = null,
    onConvertCad: () -> Unit = {},
    onSlice: () -> Unit,
    onExportGcode: () -> Unit,
    onTools: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gcodeAvailable = state.hasCurrentGcode()
    Surface(tonalElevation = 4.dp, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isBusy) CircularProgressIndicator(modifier = Modifier.height(28.dp))
                Button(
                    onClick = onSlice,
                    // Greyed while a live CAD document holds it, but the tap still lands: the
                    // status line names the reason, and Convert to STL is the press that clears it.
                    enabled = state.engineAvailable && state.modelPath != null && !state.isBusy &&
                        (sliceBlockedReason == null || sliceTappable),
                    colors = if (sliceBlockedReason != null) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
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
                OutlinedButton(
                    onClick = onExportGcode,
                    enabled = gcodeAvailable && !state.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Export G-code")
                }
                // Folding the block leaves the handle in its place, so the same tap
                // target unfolds it again.
                IconButton(onClick = onToggle) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Hide the actions")
                }
            }
            cadConvertLabel?.let { label ->
                OutlinedButton(
                    onClick = onConvertCad,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(label)
                }
            }
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

/**
 * The collapsed action bar: a small arrow over the model, and nothing else.
 *
 * Collapsed used to be the bar itself with its Slice button still on it, which spent
 * the strip the expand gesture was meant to give back. This is what is left instead -
 * the same small arrow in a small circle the sidebar's handle uses, with nothing
 * between it and the model, and the plate's full height above it.
 */
@Composable
internal fun ActionBarHandle(onExpand: () -> Unit, modifier: Modifier = Modifier) {
    SidebarHandle(
        onClick = onExpand,
        icon = Icons.Filled.KeyboardArrowUp,
        label = "Show the actions",
        modifier = modifier,
    )
}

/** Split tool callbacks, grouped so the viewer signature stays readable. */
private class CutActions(
    val onAxis: (ModelPlacement.Axis) -> Unit,
    val onOffset: (Double) -> Unit,
    val onSplit: () -> Unit,
    val onClose: () -> Unit,
)

// The hook's own controls, in millimetres. The slider's top end is generous on
// purpose: the real limit is the mate's own material, and a ceiling a user can
// hit with the slider would leave him stuck. The panel says when the material
// clamped the value.
private val HOOK_LENGTH_RANGE = 1f..60f
private val HOOK_THICKNESS_RANGE = 0.4f..6f
private val HOOK_LIP_RANGE = 0.2f..4f

// The wall lip's own two controls, in millimetres. The engagement is the hold -
// how far the tooth and its ledge overlap - and it is also the print's
// compensation, because the tooth's holding face is the joint's one overhang and
// it comes out deeper than drawn. The top ends are generous on purpose: the real
// limits are the tooth's own height and the material's strain, and both refuse
// with a sentence rather than a slider that stops moving.
private val LIP_ENGAGEMENT_RANGE = 0.2f..3f
private val LIP_TOOTH_RANGE = 0.6f..6f

/** The wall the ring leaves: under a millimetre it will not bend, over three is a block. */
private val LIP_WALL_RANGE = 1f..3f

/** How far the recess floor may go past the step: deeper is a longer, softer spring. */
private val LIP_DEPTH_RANGE = 0f..8f

/** How wide a cut's slit is: enough to part the wall, narrow enough to keep it whole. */
private val LIP_CUT_RANGE = 0.3f..2f

/**
 * How deep into the insert a cut's slit would run. Zero is at the part's own
 * surface, which is where every slit stops whatever is asked - the top end is
 * only how far past it the ask goes before the geometry refuses to follow.
 */
private val LIP_CUT_DEPTH_RANGE = 0f..8f

/** Where the strain readout starts warning, in per cent: the usual PLA design limit. */
private const val ROOT_STRAIN_HINT_PERCENT = 2f

/** Snap fit callbacks, grouped the same way. */
/** Snap fit callbacks, grouped the same way. */
private class SnapActions(
    val onScale: (Float) -> Unit,
    val onBeamHalf: (SnapJoint.JointHalf) -> Unit,
    /** The lip's own choice: which half is the embossed insert whose ring is cut. */
    val onLipInsertHalf: (SnapJoint.JointHalf) -> Unit,
    val onFullJoint: (Boolean) -> Unit,
    val onHookLength: (Float) -> Unit,
    val onHookThickness: (Float) -> Unit,
    val onHookLip: (Float) -> Unit,
    /** How many teeth the selected joint's beam carries, one to three. */
    val onBarbs: (Int) -> Unit,
    /** Which joint the selected one is, and what the next one will be. */
    val onStyle: (SnapJointStyle) -> Unit,
    /** Which bundled library shape the selected (and the next) library joint is grafted from. */
    val onLibraryShape: (SnapLibraryShape) -> Unit,
    /** The wall lip's engagement and tooth height, in millimetres, or null for the scale's own. */
    val onLipEngagement: (Float?) -> Unit,
    val onLipToothHeight: (Float?) -> Unit,
    val onLipWall: (Float?) -> Unit,
    val onLipDepth: (Float?) -> Unit,
    val onLipCutWidth: (Float?) -> Unit,
    /** How deep a cut's slit runs below the mating face, or null for the whole band. */
    val onLipCutDepth: (Float?) -> Unit,
    /** Arms the next tap on the model to cut the lip's ring where it lands. */
    val onCutWall: () -> Unit,
    /** Cuts every bend of the seam's contour in one go. */
    val onCutEveryBend: () -> Unit,
    val onCancelCut: () -> Unit,
    val onRemoveCut: (Int) -> Unit,
    /** Loose or tight for one joint: clearance, and nothing else. */
    val onTightness: (Int, SnapTightness) -> Unit,
    /** The pair-level flip: every joint on the seam turns together. */
    val onFlipAll: () -> Unit,
    /** Whether the joints may face different ways at all. */
    val onMixedFacing: (Boolean) -> Unit,
    val onJointFacing: (Int, SnapFacing) -> Unit,
    /** The socket mouth chamfer, for every joint at once. */
    val onSocketRamp: (Boolean) -> Unit,
    val onSelect: (Int) -> Unit,
    /** Arms the next tap on the model to PLACE a joint. */
    val onAdd: () -> Unit,
    /** Takes back an armed Add or Move. */
    val onCancelPlacement: () -> Unit,
    val onMove: (Int) -> Unit,
    val onRemove: (Int) -> Unit,
    val onClear: () -> Unit,
    /** The app's proposal for where along the seam's rim a set of joints goes. */
    val onAuto: () -> Unit,
    /** Runs when a slider is released: the preview follows once, not per frame. */
    val onCommit: () -> Unit,
    val onApply: () -> Unit,
)

/**
 * What the snap panel's controls may take of the plate before they scroll: a
 * share of the viewer, with a ceiling so that a tall window does not simply
 * hand them the model they are used on. The three-column panel needs a fraction
 * of what the single column does, which is the point of splitting it.
 *
 * The share is the binding limit for the wide panel, and it is a share of the
 * PLATE rather than a number of dp: at 150% the interface draws the same
 * controls a half larger again, and a 300 dp ceiling then took a third of the
 * plate back from the model the joints are tapped on.
 */
private const val SNAP_WIDE_CONTROLS_FRACTION = 0.28f
private const val SNAP_PHONE_CONTROLS_FRACTION = 0.5f
private val SNAP_WIDE_CONTROLS_MAX = 300.dp
private val SNAP_PHONE_CONTROLS_MAX = 360.dp

/** How much of a seam's joint list is shown before the list scrolls itself. */
private val SNAP_JOINT_LIST_MAX = 150.dp
private val SNAP_JOINT_LIST_COLUMN_MAX = 132.dp

/**
 * The snap fit, at the bottom of the model view in the split toolbar's own
 * style: the joints placed on the seam, the controls for the selected one, and
 * the action that joins the halves.
 *
 * A tap adds a joint rather than moving one, because a complicated seam needs
 * several and the material varies along it - so each joint carries its own
 * rung, its own teeth, its own facing and its own clearance step, and the panel
 * says what each one came out as. The scale is still one control for the whole
 * joint; the hook's own sliders override their dimension in millimetres.
 *
 * The controls are grouped by purpose - Setup, then the Hook's dimensions, then
 * the Fits - and on a window with the app's expanded layout the three groups
 * stand side by side as three columns of the one panel, the way the Smart
 * Infill sheet splits its workflow. That is the point of the split: the joints
 * are placed by tapping the model, so a panel that covers the model cannot be
 * used at all. A phone-shaped window keeps the single column, and in both the
 * controls scroll under a status line and a Join button that stay put. The
 * status line, Join and Close span the panel's width along the bottom.
 */
@Composable
private fun SnapToolbar(
    state: MainUiState,
    actions: SnapActions,
    onClose: () -> Unit,
    expandedLayout: Boolean,
    modifier: Modifier = Modifier,
) {
    val selected = state.snapSelectedJoint
    val specs = state.snapJoints
    val shown = state.snapPreview?.takeIf { state.snapShownMeshes != null }
    val defaults = SnapFitParameters()
    val hookLength = state.snapHookLengthMm
        ?: shown?.joint?.dimensions?.beamLengthMm
        ?: (defaults.beamLengthMm * state.snapScale)
    val hookThickness = state.snapHookThicknessMm
        ?: shown?.joint?.dimensions?.beamThicknessMm
        ?: (defaults.beamThicknessMm * state.snapScale)
    val hookLip = state.snapHookLipMm
        ?: shown?.joint?.dimensions?.lipDepthMm
        ?: (defaults.lipDepthMm * state.snapScale)
    val beamHalf = state.snapSelectedSpec?.beamHalf ?: state.snapBeamHalf
    // The style the controls act on: the selected joint's, or the one the next
    // joint will be placed with. The rung ladder, the teeth, the facing and the
    // socket ramp belong to the cantilever, and the wall lip's own two sizes -
    // and the wall they are measured against - belong to the lip.
    val style = state.snapSelectedSpec?.style ?: state.snapStyle
    // The bundled shape the controls act on: the selected library joint's, or the
    // one the next tapped joint is grafted from. One picker both places it and
    // re-cuts the selected one, the way the style control does.
    val libraryShape = state.snapSelectedSpec
        ?.takeIf { it.style == SnapJointStyle.LIBRARY_HOOK }
        ?.libraryShape
        ?: state.snapLibraryShape
    val lipEngagement = state.snapSelectedSpec?.lipEngagementMm
        ?: state.snapLipEngagementMm
        ?: (defaults.lipEngagementMm * state.snapScale)
    val lipToothHeight = state.snapSelectedSpec?.lipToothHeightMm
        ?: state.snapLipToothHeightMm
        ?: (defaults.lipToothHeightMm * state.snapScale)
    val lipWall = state.snapSelectedSpec?.lipWallMm
        ?: state.snapLipWallMm
        ?: (defaults.lipWallMm * state.snapScale)
    val lipDepth = state.snapSelectedSpec?.lipRecessExtraMm
        ?: state.snapLipDepthMm
        ?: (defaults.lipRecessExtraMm * state.snapScale)
    val lipCutWidth = state.snapSelectedSpec?.lipCutWidthMm
        ?: state.snapLipCutWidthMm
        ?: (defaults.lipCutWidthMm * state.snapScale)
    // The depth has no scaled default: null is the part's own surface, so what
    // the row shows when nothing is set is the depth the shown joint built with -
    // zero when the surface stopped the slits there, which is the usual case.
    val lipCutDepth = state.snapSelectedSpec?.lipCutDepthMm
        ?: state.snapLipCutDepthMm
        ?: shown?.joint?.dimensions?.lipCutDepthMm
        ?: 0f
    // One call per group, so the three-column panel and the single-column one
    // cannot drift apart: both are these three, in this order.
    val setup: @Composable () -> Unit = {
        SnapSetupGroup(
            state = state,
            actions = actions,
            specs = specs,
            shown = shown,
            selected = selected,
            stacked = expandedLayout,
            style = style,
            libraryShape = libraryShape,
        )
    }
    val hook: @Composable () -> Unit = {
        SnapHookGroup(
            actions = actions,
            shown = shown,
            style = style,
            libraryShape = libraryShape,
            hookLength = hookLength,
            hookThickness = hookThickness,
            hookLip = hookLip,
            lipEngagement = lipEngagement,
            lipToothHeight = lipToothHeight,
            lipWall = lipWall,
            lipDepth = lipDepth,
            lipCutWidth = lipCutWidth,
            lipCutDepth = lipCutDepth,
        )
    }
    val fits: @Composable () -> Unit = {
        SnapFitsGroup(
            state = state,
            actions = actions,
            specs = specs,
            selected = selected,
            placed = shown?.joints?.getOrNull(selected),
            beamHalf = beamHalf,
            stacked = expandedLayout,
            style = style,
        )
    }
    BoxWithConstraints(modifier = modifier) {
        // The controls never take the whole plate: the model above the panel is
        // what a joint is tapped on, and it has to stay visible and reachable.
        val controlsMax = (
            maxHeight * if (expandedLayout) SNAP_WIDE_CONTROLS_FRACTION else SNAP_PHONE_CONTROLS_FRACTION
            ).coerceAtMost(if (expandedLayout) SNAP_WIDE_CONTROLS_MAX else SNAP_PHONE_CONTROLS_MAX)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(
                    horizontal = EnderSlicerDimens.SheetPadding,
                    vertical = EnderSlicerDimens.Space6,
                ),
                verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space4),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.snapStyle == SnapJointStyle.WALL_LIP) {
                            "Snap fit · the whole seam"
                        } else if (specs.isEmpty()) {
                            "Snap fit joint"
                        } else {
                            "Snap fit joints (" + specs.size + ")"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.width(EnderSlicerDimens.Space8))
                    Text(
                        state.snapLowHalf?.name.orEmpty() + " and " + state.snapHighHalf?.name.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // The single column keeps Close beside the title; the wide
                    // panel puts it across the bottom with the other exits.
                    if (!expandedLayout) {
                        TextButton(
                            onClick = onClose,
                            contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                        ) { Text("Close") }
                    }
                }
                Text(
                    when {
                        // The move is the live mode, so what it asks for comes first:
                        // every style is moved the same way, the lip's ring included.
                        state.snapMovingJoint ->
                            "Joint " + (selected + 1) + ": drag one of its arrows and it moves along " +
                                "that axis only; press Move again when it is where you want it."
                        state.snapStyle == SnapJointStyle.WALL_LIP ->
                            "The wall lip is the whole seam: one tooth ring on the lip half and one " +
                                "ledge ring on the mate, following the seam's own edge. The two sizes " +
                                "size the ring."
                        state.snapAddingJoint -> "Tap the model to place it."
                        // Every joint removed from a pair that remembers its own halves: the
                        // plate still carries the joints, and Apply is what takes them off.
                        state.snapPlainHalvesInstead ->
                            "No joints left on the list; Apply puts the pair's plain halves back."
                        specs.isEmpty() && state.snapStyle == SnapJointStyle.LIBRARY_HOOK ->
                            "A library shape is a ready-made pair: pick one of the eight, press " +
                                "Add joint, and tap the model. Both pieces land with the author's " +
                                "own mating plane on the seam - nothing to line up - and the one " +
                                "Scale slider sizes them together."
                        specs.isEmpty() ->
                            "Add joint, then tap the model where a joint should sit."
                        else ->
                            "Tap a joint to select it, or Add joint to place another; Auto " +
                                "spreads them along the seam."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = controlsMax)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (expandedLayout) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space12),
                        ) {
                            SnapColumn("Setup", Modifier.weight(1f)) { setup() }
                            SnapColumn("Hook", Modifier.weight(1f)) { hook() }
                            SnapColumn("Fits", Modifier.weight(1f)) { fits() }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
                        ) {
                            SnapColumn("Setup", Modifier.fillMaxWidth()) { setup() }
                            SnapColumn("Hook", Modifier.fillMaxWidth()) { hook() }
                            SnapColumn("Fits", Modifier.fillMaxWidth()) { fits() }
                        }
                    }
                }
                state.snapFailure?.let { failure ->
                    Text(
                        failure,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.snapShownMeshes != null) WarnAmber else MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = actions.onApply,
                    // A pair whose joints have all been removed has no preview - there is
                    // nothing to build - but Apply still means something there: it puts the
                    // plain halves back from the pair's own source.
                    // A preview standing with a joint the material refused is not a pair to
                    // write: the button is off and the line under it names the joint, so the
                    // joined file is never the list minus one.
                    enabled = (state.snapShownMeshes != null || state.snapPlainHalvesInstead) &&
                        !state.isBusy && state.snapFailedJoints.isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            state.snapPlainHalvesInstead -> "Put the plain halves back"
                            specs.size > 1 -> "Join the halves with " + specs.size + " joints"
                            else -> "Join the halves"
                        },
                    )
                }
                state.snapBlockedReason?.let { reason ->
                    Text(
                        reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnAmber,
                    )
                }
                if (expandedLayout) {
                    TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                }
            }
        }
    }
}

/**
 * One column of the wide panel: its own label and its own stack of controls.
 * Three of these sit side by side on a foldable and one above the other on a
 * phone, from the same content either way.
 */
@Composable
private fun SnapColumn(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space4),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

/**
 * A labelled row of choices with its helper line. In a column the line goes
 * under the controls instead of beside them, where a caption in the same row
 * would squeeze the buttons and still be cut off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnapChoiceRow(
    label: String?,
    /** Null where the controls say the same thing and the column has no room. */
    caption: String?,
    stacked: Boolean,
    content: @Composable () -> Unit,
) {
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // A column is narrow, and the interface can be scaled up on top
            // of that: the choices wrap onto a second line rather than being
            // cut off at the column edge, which is what the third tooth
            // button did at 150%.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space6),
                verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space4),
            ) {
                label?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                content()
            }
            caption?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space6),
        ) {
            label?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            content()
            caption?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Setup: the joints already on the seam, the spread that proposes them, the
 * rung the ladder is asked for and the scale every dimension follows.
 */
@Composable
private fun SnapSetupGroup(
    state: MainUiState,
    actions: SnapActions,
    specs: List<SnapJointSpec>,
    shown: SnapPreview?,
    selected: Int,
    stacked: Boolean,
    style: SnapJointStyle,
    libraryShape: SnapLibraryShape,
) {
    // Every joint, and which one the controls act on. Its own scroll: a seam
    // can carry four, and each one carries its own style, its own sizes and its
    // own reasons.
    if (specs.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (stacked) SNAP_JOINT_LIST_COLUMN_MAX else SNAP_JOINT_LIST_MAX)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            specs.forEachIndexed { index, spec ->
                SnapJointRow(
                    index = index,
                    spec = spec,
                    placed = shown?.joints?.getOrNull(index),
                    // A joint the material refused says so on its own row, by name, while
                    // the others stand: it is that joint's answer, not the set's.
                    failed = shown?.failed?.firstOrNull { it.index == index },
                    selected = index == selected,
                    // The one being moved: its Move button is filled, because the
                    // same press is also how the user stops.
                    moving = state.snapMovingJoint && index == selected,
                    compact = stacked,
                    actions = actions,
                )
            }
        }
    }
    // The joint's own depth, and which arrow drives it: it is a number of the
    // joint rather than of the style, and a style that HAS a depth of its own
    // says so here, so the user is never guessing which depth the arrow moved.
    state.snapDepthOf(state.snapSelectedSpec)?.let { depth ->
        val own = when (style) {
            SnapJointStyle.WALL_LIP ->
                " The ring's own recess depth is the Depth row in Hook - not this one."
            SnapJointStyle.LIBRARY_HOOK ->
                " The shape's own depth is its size at the Scale, not this one."
            SnapJointStyle.CANTILEVER -> ""
        }
        Text(
            // The whole sentence is formatted, not just its last fragment: the
            // concatenation used to take the .format on the tail alone, and the
            // panel printed the format string where its numbers belong.
            (
                "   Depth %.2f mm along the seam's axis: the %s arrow moves it, 0 is the seam's own " +
                    "plane, below is deeper into the part.%s"
                ).format(depth, state.snapAxis.name, own),
            style = MaterialTheme.typography.labelSmall,
            color = if (state.snapMoveAxis == state.snapAxis) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
    // The wide panel's list is capped at a row's height: the lip's readout is
    // longer than that and is drawn here, under the list, where nothing clips it.
    if (stacked) {
        shown?.joints?.getOrNull(selected)?.let { one ->
            // The wide panel draws the selected joint's whole readout here,
            // under the list, because the list's own height cap is sized for
            // rows and would clip the last line of either of these.
            if (one.joint.style == SnapJointStyle.WALL_LIP) SnapLipReadout(one)
            else if (one.joint.style == SnapJointStyle.LIBRARY_HOOK) SnapLibraryReadout(one)
        }
    }
    // Placing is deliberate: this arms the next tap, and the line under the title says so
    // while it is armed. A tap with nothing armed selects the joint nearest the finger
    // instead of adding one, which is what a model being turned used to do.
    if (state.snapStyle != SnapJointStyle.WALL_LIP) {
    SnapChoiceRow(
        label = null,
        caption = when {
            state.snapAddingJoint -> "tap the model to place it"
            state.snapMovingJoint ->
                "moving joint " + (selected + 1) + ": drag one of its arrows, Move again to stop"
            else -> "place a joint, or tap one to select it"
        },
        stacked = stacked,
    ) {
        if (state.snapAddingJoint || state.snapMovingJoint) {
            Button(
                onClick = actions.onCancelPlacement,
                contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
            ) { Text("Cancel") }
        } else {
            Button(
                onClick = actions.onAdd,
                contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
            ) { Text("Add joint") }
        }
    }
    // The spread is the cantilever's own rim solver: it places beams along the
    // seam's outline. The library's joints go where they are tapped - the whole
    // point of the style - so it is not offered for them. Clear is, either way.
    if (style != SnapJointStyle.LIBRARY_HOOK) {
        SnapChoiceRow(
            label = null,
            // The wide panel keeps its captions to one line: a column is narrow
            // enough that a long one wraps and eats the height the split saved.
            caption = if (stacked) {
                "2-4 joints, along the seam's own rim"
            } else {
                "2-4 joints, even along the rim of the seam's own material"
            },
            stacked = stacked,
        ) {
            OutlinedButton(
                onClick = actions.onAuto,
                contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
            ) { Text("Auto spread") }
            if (specs.isNotEmpty()) {
                TextButton(
                    onClick = actions.onClear,
                    contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                ) { Text("Clear") }
            }
        }
    } else if (specs.isNotEmpty()) {
        SnapChoiceRow(label = null, caption = null, stacked = stacked) {
            TextButton(
                onClick = actions.onClear,
                contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
            ) { Text("Clear") }
        }
    }
    }
    if (state.snapStyle == SnapJointStyle.WALL_LIP) {
        // Cutting the ring: a tap places one cut, and the bends of the contour can
        // all be cut at once, which is the shape the user drew - the O opened into
        // brackets at its own corners.
        SnapChoiceRow(
            label = null,
            caption = if (state.snapCuttingWall) {
                "tap the ring to cut it, or drag across it to aim the slit"
            } else {
                "a cut parts the insert's ring; each one gives it an end to bend from"
            },
            stacked = stacked,
        ) {
            if (state.snapCuttingWall) {
                Button(
                    onClick = actions.onCancelCut,
                    contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                ) { Text("Cancel") }
            } else {
                Button(
                    onClick = actions.onCutWall,
                    contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                ) { Text("Cut wall") }
            }
        }
        SnapChoiceRow(
            label = null,
            caption = if (stacked) "one per corner" else "one cut at every bend of the seam's own edge",
            stacked = stacked,
        ) {
            OutlinedButton(
                onClick = actions.onCutEveryBend,
                contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
            ) { Text("Cut every bend") }
        }
        val lipSpec = specs.firstOrNull { it.style == SnapJointStyle.WALL_LIP }
        val cuts = lipSpec?.lipCuts.orEmpty()
        if (cuts.isNotEmpty()) {
            Text(
                cuts.size.toString() + (if (cuts.size == 1) " cut" else " cuts") + " on the ring",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            cuts.forEachIndexed { index, _ ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Cut " + (index + 1),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { actions.onRemoveCut(index) },
                        contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space6),
                    ) { Text("Remove") }
                }
            }
        }
    }
    // The style. The cantilever is the joint the tool has always built - a beam
    // across the seam with a barb on it, fitted by the ladder. The wall lip is
    // the other design: the tooth stands on the half's own wall, the mate is
    // solid opposite it, and nothing hangs in the air. One control places it and
    // re-tunes the selected joint, the way the teeth stepper does.
    SnapChoiceRow(
        label = "Style:",
        caption = when {
            stacked -> "cantilever, wall lip, or a shape from the library"
            style == SnapJointStyle.WALL_LIP ->
                "one tooth ring on the lip half, one ledge ring on the mate - the whole seam"
            style == SnapJointStyle.LIBRARY_HOOK ->
                "a ready-made pair from the bundled library, tapped into place"
            else -> "a beam across the seam with a barb on it"
        },
        stacked = stacked,
    ) {
        SnapJointStyle.entries.forEach { wanted ->
            val on = style == wanted
            if (on) {
                Button(
                    onClick = { actions.onStyle(wanted) },
                    contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                ) { Text(wanted.label) }
            } else {
                OutlinedButton(
                    onClick = { actions.onStyle(wanted) },
                    contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                ) { Text(wanted.label) }
            }
        }
    }
    // The library's own picker: eight names from the bundled assets, each with
    // the README's line about what it is. Eight buttons do not fit a column and
    // barely fit a phone, so it is a dropdown - and the shape it is on is what a
    // tapped joint is grafted from.
    if (style == SnapJointStyle.LIBRARY_HOOK) {
        SnapChoiceRow(
            label = "Shape:",
            caption = libraryShape.label + " · " + libraryShape.summary,
            stacked = stacked,
        ) {
            SnapLibraryPicker(libraryShape, actions.onLibraryShape)
        }
    }
    // The ladder's switch, and the ladder belongs to the cantilever: the lip has
    // one shape, and its own requirements are what it answers with.
    if (style == SnapJointStyle.CANTILEVER) {
        SnapChoiceRow(
            label = "Joint:",
            caption = if (state.snapFullJoint) {
                "the full joint, as asked for"
            } else {
                "the fullest joint each place on the seam takes"
            },
            stacked = stacked,
        ) {
            if (state.snapFullJoint) {
                Button(
                    onClick = { actions.onFullJoint(false) },
                    contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                ) { Text("Full") }
            } else {
                OutlinedButton(
                    onClick = { actions.onFullJoint(true) },
                    contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                ) { Text("Auto") }
            }
        }
    }
    CompactSliderRow(
        label = "Scale",
        value = state.snapScale,
        range = MIN_JOINT_SCALE..MAX_JOINT_SCALE,
        onValueChange = actions.onScale,
        valueText = "%.2f×".format(state.snapScale),
        onValueChangeFinished = actions.onCommit,
    )
}

/**
 * The library hook's whole readout: which shape it is, how big it was built,
 * the clearance its author drew into it at the scale it is being printed at,
 * and the one line this app will not invent.
 *
 * The strain of a beam we did not design is not computed - the panel says so
 * rather than printing a number that would be made up - and the clearance is
 * the pair's own, measured from the files when the shape was loaded, so it
 * scales with the pair and warns when the scale takes it under what FDM
 * resolves.
 */
@Composable
private fun SnapLibraryReadout(one: SnapPlacedJoint) {
    val dimensions = one.joint.dimensions
    Text(
        "   " + SnapLibrary.describe(dimensions),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "   hook in " + one.beamHalfName + ", catch in " + one.socketHalfName + " \u00b7 " +
            SnapLibrary.sizeSummary(dimensions),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "   " + SnapLibrary.gapSummary(dimensions),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SnapLibrary.gapWarning(dimensions)?.let { warning ->
        Text("   " + warning, style = MaterialTheme.typography.labelSmall, color = WarnAmber)
    }
    Text(
        "   " + SnapLibrary.STRAIN_NOTE,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (warning in one.joint.warnings) {
        Text(
            "   " + warning,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * The eight bundled shapes, one dropdown: the README's own name for the shape,
 * and its own line about what the shape is, under it in the menu.
 */
@Composable
private fun SnapLibraryPicker(current: SnapLibraryShape, onPick: (SnapLibraryShape) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
        ) { Text(current.label) }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 260.dp, max = 340.dp),
        ) {
            SnapLibraryShape.entries.forEach { shape ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(shape.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                shape.summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onPick(shape)
                    },
                )
            }
        }
    }
}

/**
 * The wall lip's whole readout: the hold its two sizes set, the wall they are
 * measured against, the step the ring forced and why, and whatever the material
 * could not give. It is shown beside the joint's own row in the single column,
 * and under the list in the wide panel - where the list's height cap, sized for
 * rows, would otherwise clip the last line off it.
 */
@Composable
private fun SnapLipReadout(one: SnapPlacedJoint) {
    Text(
        "   " + SnapJointStyle.WALL_LIP.label + " · " + one.joint.lipSummary +
            " · " + one.joint.lipFitSummary,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "   tooth ring in " + one.beamHalfName + ", ledge ring in " + one.socketHalfName +
            " · worst wall strain %.1f%% at %.0f mm along the ring".format(
                one.joint.dimensions.lipStrainPercent,
                one.joint.dimensions.lipWorstAtMm,
            ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "   " + one.joint.lipStepSummary,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "   " + if (one.joint.dimensions.lipCuts == 0) {
            "no cuts yet: they land on " + one.socketHalfName + ", the embossed insert"
        } else {
            "cuts on " + one.socketHalfName + ", the embossed insert - " + one.beamHalfName +
                "'s own wall stays whole"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "   " + one.joint.lipCutSummary,
        style = MaterialTheme.typography.labelSmall,
        color = if (one.joint.dimensions.lipCuts == 0) {
            WarnAmber
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    for (warning in one.joint.warnings) {
        Text(
            "   " + warning,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * One joint in the list, with what it came out as and the two edits it takes.
 * In a column the row's own label is shortened: the teeth and the facing it
 * used to carry are on the joint's first line of detail either way.
 */
@Composable
private fun SnapJointRow(
    index: Int,
    spec: SnapJointSpec,
    placed: SnapPlacedJoint?,
    /** What refused this joint, when the material did - it has no placement then. */
    failed: SnapJointFailure?,
    selected: Boolean,
    /** True while this joint is the one being moved: Move is then the way out. */
    moving: Boolean,
    compact: Boolean,
    actions: SnapActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { actions.onSelect(index) },
            contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space6),
        ) {
            Text(
                if (spec.style == SnapJointStyle.WALL_LIP) {
                    // The lip is not one joint among several: it is the seam.
                    (if (selected) "▸ " else "") + SnapJointStyle.WALL_LIP.label + " · the whole seam"
                } else if (spec.style == SnapJointStyle.LIBRARY_HOOK) {
                    (if (selected) "▸ " else "") + "Joint " + (index + 1) + " · " +
                        spec.libraryShape.label
                } else {
                    (if (selected) "▸ " else "") + "Joint " + (index + 1) + " · " + spec.tightness.label +
                        if (compact) {
                            ""
                        } else {
                            " · " + spec.barbs + (if (spec.barbs == 1) " tooth" else " teeth") +
                                " · " + if (spec.facing == SnapFacing.SAME) "same way" else "other way"
                        }
                },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        // Move is a mode, not a one-shot: while it is on the button is filled the
        // way the app's other toggles show their state, because pressing it again
        // is how the move stops and the arrows go away.
        if (moving) {
            Button(
                onClick = { actions.onMove(index) },
                contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space6),
            ) { Text("Move") }
        } else {
            TextButton(
                onClick = { actions.onMove(index) },
                contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space6),
            ) { Text("Move") }
        }
        TextButton(
            onClick = { actions.onRemove(index) },
            contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space6),
        ) { Text("Remove") }
    }
    placed?.let { one ->
        // The joint's own rung and reason: the material here, not on the seam
        // as a whole. And the click it holds at, because a beam with several
        // teeth holds at several depths and the panel has to say which.
        if (one.joint.style == SnapJointStyle.WALL_LIP) {
            // Beside its row in the single column. The wide panel draws it below
            // the list instead, because this readout is longer than a row and the
            // list's own height cap was clipping its last line.
            if (!compact) SnapLipReadout(one)
        } else if (one.joint.style == SnapJointStyle.LIBRARY_HOOK) {
            if (!compact) SnapLibraryReadout(one)
        } else {
            Text(
                "   " + one.joint.rung.label + " · " + one.joint.clickSummary + " · " +
                    one.joint.dimensions.barbCount +
                    (if (one.joint.dimensions.barbCount == 1) " tooth" else " teeth") +
                    " · " + (if (spec.facing == SnapFacing.SAME) "same way" else "other way") +
                    (if (one.joint.dimensions.mouthChamferMm > 0f) {
                        " · mouth chamfer %.0f°".format(one.joint.dimensions.mouthChamferAngleDeg)
                    } else {
                        ""
                    }),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "   in " + one.beamHalfName + ": " + one.joint.fitSummary +
                    (one.joint.rungReason?.let { " · " + it } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        one.joint.clamps.forEach { clamp ->
            Text(
                "   " + clamp.reason,
                style = MaterialTheme.typography.labelSmall,
                color = WarnAmber,
            )
        }
    }
    failed?.let { refusal ->
        Text(
            "   Could not be built: " + refusal.reason,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * The selected joint's own dimensions: the cantilever's hook, or the wall lip's
 * tooth - and what the geometry does when it is bent, with the numbers measured
 * from the halves themselves.
 */
@Composable
private fun SnapHookGroup(
    actions: SnapActions,
    shown: SnapPreview?,
    style: SnapJointStyle,
    libraryShape: SnapLibraryShape,
    hookLength: Float,
    hookThickness: Float,
    hookLip: Float,
    lipEngagement: Float,
    lipToothHeight: Float,
    lipWall: Float,
    lipDepth: Float,
    lipCutWidth: Float,
    lipCutDepth: Float,
) {
    if (style == SnapJointStyle.LIBRARY_HOOK) {
        // The library hook has no hook, thickness or lip of its own to set: the
        // shape is exactly the shape its author drew. One slider - the Scale in
        // Setup - sizes the pair, uniformly and together, because the register
        // between the hook and its catch is the whole reason the pair can be
        // tapped into place at all.
        Text(
            "Both pieces follow the one Scale slider together, so their register - the fit the " +
                "author designed into them - is the same at every size.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (shown?.joint?.style != SnapJointStyle.LIBRARY_HOOK) {
            Text(
                libraryShape.label + " · " + libraryShape.summary +
                    " - Add joint, then tap the model where it should sit.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        shown?.takeIf { it.joint.style == SnapJointStyle.LIBRARY_HOOK }?.let { preview ->
            val dimensions = preview.joint.dimensions
            Text(
                SnapLibrary.describe(dimensions) + " · " + SnapLibrary.sizeSummary(dimensions),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                SnapLibrary.gapSummary(dimensions),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SnapLibrary.gapWarning(dimensions)?.let { warning ->
                Text(warning, style = MaterialTheme.typography.labelSmall, color = WarnAmber)
            }
            // The honest line: this joint's spring is not this app's design.
            Text(
                SnapLibrary.STRAIN_NOTE,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            preview.repairNote?.let { note ->
                Text(
                    "Repaired before joining: " + note,
                    style = MaterialTheme.typography.labelSmall,
                    color = WarnAmber,
                )
            }
        }
        return
    }
    if (style == SnapJointStyle.WALL_LIP) {
        // The engagement is the hold: how far the tooth and the ledge overlap.
        CompactSliderRow(
            label = "Engage",
            value = lipEngagement,
            range = LIP_ENGAGEMENT_RANGE,
            onValueChange = { actions.onLipEngagement(it) },
            valueText = "%.2f mm".format(lipEngagement),
            onValueChangeFinished = actions.onCommit,
        )
        // The tooth's height: how far it stands off the wall's face, which is
        // also the ceiling on the hold.
        CompactSliderRow(
            label = "Tooth",
            value = lipToothHeight,
            range = LIP_TOOTH_RANGE,
            onValueChange = { actions.onLipToothHeight(it) },
            valueText = "%.2f mm".format(lipToothHeight),
            onValueChangeFinished = actions.onCommit,
        )
        // The wall the ring leaves where the part is solid: the spring itself, so
        // thinner bends more easily and closes with less effort, and the strain
        // readout is computed over whatever this is.
        CompactSliderRow(
            label = "Wall",
            value = lipWall,
            range = LIP_WALL_RANGE,
            onValueChange = { actions.onLipWall(it) },
            valueText = "%.2f mm".format(lipWall),
            onValueChangeFinished = actions.onCommit,
        )
        // How far the recess floor goes past what the tooth and its ramp need:
        // the free length is measured from that floor, so a deeper band is a
        // longer, softer spring and the strain falls with it.
        CompactSliderRow(
            label = "Depth",
            value = lipDepth,
            range = LIP_DEPTH_RANGE,
            onValueChange = { actions.onLipDepth(it) },
            valueText = "%.2f mm".format(lipDepth),
            onValueChangeFinished = actions.onCommit,
        )
        // How wide a cut's slit is. Cutting is what makes the wall bend: a closed
        // ring is the stiffest shape there is, and every cut gives it an end.
        CompactSliderRow(
            label = "Cut",
            value = lipCutWidth,
            range = LIP_CUT_RANGE,
            onValueChange = { actions.onLipCutWidth(it) },
            valueText = "%.2f mm".format(lipCutWidth),
            onValueChangeFinished = actions.onCommit,
        )
        // How deep into the insert's face the slits would run. Past the ledge's
        // root is what severs the ring, but the part's own surface - the roof the
        // emboss stands on - stops every slit there whatever this says, and the
        // clamp line below tells the user when it did.
        CompactSliderRow(
            label = "Cut depth",
            value = lipCutDepth,
            range = LIP_CUT_DEPTH_RANGE,
            onValueChange = { actions.onLipCutDepth(it) },
            valueText = "%.2f mm".format(lipCutDepth),
            onValueChangeFinished = actions.onCommit,
        )
        shown?.takeIf { it.joint.style == SnapJointStyle.WALL_LIP }?.let { preview ->
            val lip = preview.joint.dimensions
            Text(
                "Wall strain %.1f%% at %.2f mm of travel - the tooth's height less the clearance".format(
                    lip.lipStrainPercent,
                    lip.lipDeflectionMm,
                ) + "; PLA's own figure is %.0f%% and a setting past it is refused".format(
                    SnapLip.MATERIAL_STRAIN_PERCENT,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (lip.lipStrainPercent > SnapLip.MATERIAL_STRAIN_PERCENT) {
                    WarnAmber
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                (
                    "The wall is the spring: %.2f mm thick (asked %.2f, and the part decides " +
                        "where it is thinner), %.1f mm free behind the seam, with the recess floor " +
                        "%.2f mm behind it"
                    ).format(
                        lip.wallThicknessMm,
                        lip.lipWallAskedMm,
                        lip.wallFreeLengthMm,
                        lip.lipRecessMm,
                    ) + "; it bends outward as the ledge rides past and springs back behind it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Printed with the joint faces up the ledge is a top layer on solid material and " +
                    "comes out solid, and the lip prints as a wall. The tooth's holding face is the " +
                    "one face that hangs, so it prints deeper than drawn - the engagement is the " +
                    "compensation for that.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            preview.repairNote?.let { note ->
                Text(
                    "Repaired before joining: " + note,
                    style = MaterialTheme.typography.labelSmall,
                    color = WarnAmber,
                )
            }
        }
        return
    }
    // Each slider previews when the thumb is let go, not on every frame.
    CompactSliderRow(
        label = "Hook",
        value = hookLength,
        range = HOOK_LENGTH_RANGE,
        onValueChange = actions.onHookLength,
        valueText = "%.1f mm".format(hookLength),
        onValueChangeFinished = actions.onCommit,
    )
    CompactSliderRow(
        label = "Thick",
        value = hookThickness,
        range = HOOK_THICKNESS_RANGE,
        onValueChange = actions.onHookThickness,
        valueText = "%.2f mm".format(hookThickness),
        onValueChangeFinished = actions.onCommit,
    )
    CompactSliderRow(
        label = "Lip",
        value = hookLip,
        range = HOOK_LIP_RANGE,
        onValueChange = actions.onHookLip,
        valueText = "%.2f mm".format(hookLip),
        onValueChangeFinished = actions.onCommit,
    )
    shown?.takeIf { it.joint.style == SnapJointStyle.CANTILEVER }?.let { preview ->
        val strain = preview.joint.dimensions.rootStrainPercent
        Text(
            "Root strain %.1f%% at full deflection".format(strain) +
                // The worst case is one JOINT's teeth: the readout already sums
                // the whole joint's lips, and another joint on the seam is a
                // different beam.
                (if (preview.joint.dimensions.barbCount > 1) " (worst case, every tooth's lip)" else "") +
                "; PLA is happy to about %.0f%%".format(ROOT_STRAIN_HINT_PERCENT),
            style = MaterialTheme.typography.labelSmall,
            color = if (strain > ROOT_STRAIN_HINT_PERCENT) WarnAmber else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Deflection room %.2f mm on the side the beam bends towards (away from the hook); the pocket floor is cut that deep".format(
                preview.joint.dimensions.deflectionRoomMm,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        preview.repairNote?.let { note ->
            Text(
                "Repaired before joining: " + note,
                style = MaterialTheme.typography.labelSmall,
                color = WarnAmber,
            )
        }
    }
}

/**
 * Fits: how loose each joint is cut, how many catches its beam carries, which
 * half carries the beam, which way the hook faces and what the socket's mouth
 * does - plus the click the selected joint is holding at.
 */
@Composable
private fun SnapFitsGroup(
    state: MainUiState,
    actions: SnapActions,
    specs: List<SnapJointSpec>,
    selected: Int,
    placed: SnapPlacedJoint?,
    beamHalf: SnapJoint.JointHalf,
    stacked: Boolean,
    style: SnapJointStyle,
) {
    val hasSelected = selected in specs.indices
    // The click the selected joint is holding at rides on the fit row in a
    // column: a line of its own was the one thing that fell below the
    // column, and the fit is where that clearance is decided anyway.
    val click = placed?.joint?.clickSummary
    // The Loose/Tight stepping: clearance only, 0.06 mm apart, assigned by
    // placement order and changeable one joint at a time. It shapes the joint
    // this app builds; a library shape brings its own clearances with it, so the
    // step would be a control that changes nothing.
    if (style != SnapJointStyle.LIBRARY_HOOK) SnapChoiceRow(
        label = "Fit:",
        caption = when {
            !stacked -> "0.06 mm of clearance apart, nothing else"
            click != null -> "0.06 mm apart · " + click
            else -> "0.06 mm apart"
        },
        stacked = stacked,
    ) {
        SnapTightness.entries.forEach { step ->
            val on = hasSelected && specs[selected].tightness == step
            if (on) {
                Button(
                    onClick = { actions.onTightness(selected, step) },
                    contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                ) { Text(step.label) }
            } else {
                OutlinedButton(
                    onClick = { actions.onTightness(selected, step) },
                    enabled = hasSelected,
                    contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                ) { Text(step.label) }
            }
        }
    }
    // The beam: one tooth by default, up to three with one pawl on the mate.
    // The teeth are the clicks; the pawl is the step. All of this belongs to the
    // cantilever: the lip's tooth is one tooth on the wall, and its hold is the
    // engagement the style's own control sets.
    if (style == SnapJointStyle.CANTILEVER) SnapChoiceRow(
        label = "Teeth:",
        caption = if (stacked) {
            "one pawl on the mate"
        } else {
            "one pawl on the mate, several catches on the beam"
        },
        stacked = stacked,
    ) {
        (1..SnapFit.MAX_BARBS).forEach { count ->
            val on = state.snapBarbs == count
            if (on) {
                Button(
                    onClick = { actions.onBarbs(count) },
                    contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                ) { Text(count.toString()) }
            } else {
                OutlinedButton(
                    onClick = { actions.onBarbs(count) },
                    contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                ) { Text(count.toString()) }
            }
        }
    }
    // Which half the ring is built around. For the cantilever that is which half
    // the beam goes in; for the lip it is which half carries the embossed LEDGE
    // ring - the insert whose ring the cuts open - and the tooth goes in the
    // other one, because the tooth is rooted in its half's own wall and that wall
    // is the part's visible skin. Two plain solid halves cannot be told apart by
    // the material, so this row is where the user says which is the insert, and
    // the readout names the half the cuts land on.
    val lipInsert = if (beamHalf == SnapJoint.JointHalf.LOW) SnapJoint.JointHalf.HIGH else SnapJoint.JointHalf.LOW
    // The library's own version of the same choice: the chosen half takes the
    // hook, and the mate takes the catch. The pair is one author's assembly, so
    // there is nothing else to decide about where either piece goes.
    if (style == SnapJointStyle.LIBRARY_HOOK) {
        val hookHalfName = if (beamHalf == SnapJoint.JointHalf.LOW) {
            state.snapLowHalf?.name ?: "the lower half"
        } else {
            state.snapHighHalf?.name ?: "the upper half"
        }
        val catchHalfName = if (beamHalf == SnapJoint.JointHalf.LOW) {
            state.snapHighHalf?.name ?: "the upper half"
        } else {
            state.snapLowHalf?.name ?: "the lower half"
        }
        Text(
            "Hook in " + hookHalfName + ", catch in " + catchHalfName +
                ": the pair lands with its own mating plane on the seam, so the hook and the " +
                "catch stay in register.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (style == SnapJointStyle.WALL_LIP) {
        Text(
            "Cuts on " + (if (lipInsert == SnapJoint.JointHalf.LOW) {
                state.snapLowHalf?.name ?: "the lower half"
            } else {
                state.snapHighHalf?.name ?: "the upper half"
            }) + ": the insert's embossed ring may be opened, and the other half's own wall is never cut",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space6)) {
        BeamHalfButton(
            (if (style == SnapJointStyle.WALL_LIP) {
                "Insert "
            } else if (style == SnapJointStyle.LIBRARY_HOOK) {
                "Hook in "
            } else {
                "Beam in "
            }) + (state.snapLowHalf?.name ?: "lower"),
            SnapJoint.JointHalf.LOW,
            if (style == SnapJointStyle.WALL_LIP) lipInsert else beamHalf,
            if (style == SnapJointStyle.WALL_LIP) actions.onLipInsertHalf else actions.onBeamHalf,
            Modifier.weight(1f),
        )
        BeamHalfButton(
            (if (style == SnapJointStyle.WALL_LIP) {
                "Insert "
            } else if (style == SnapJointStyle.LIBRARY_HOOK) {
                "Hook in "
            } else {
                "Beam in "
            }) + (state.snapHighHalf?.name ?: "upper"),
            SnapJoint.JointHalf.HIGH,
            if (style == SnapJointStyle.WALL_LIP) lipInsert else beamHalf,
            if (style == SnapJointStyle.WALL_LIP) actions.onLipInsertHalf else actions.onBeamHalf,
            Modifier.weight(1f),
        )
    }
    // Facing: every joint the same way by default, flipped as a pair. Mixing is
    // an explicit decision, and only then can one joint turn. The lip has no
    // facing: the wall's inboard side is where the tooth goes, and that is
    // measured rather than chosen.
    if (style == SnapJointStyle.CANTILEVER) SnapChoiceRow(
        label = "Facing:",
        caption = when {
            stacked -> null
            state.snapMixedFacing -> "opposite hooks lock the slide both ways"
            else -> "all the same way"
        },
        stacked = stacked,
    ) {
        OutlinedButton(
            onClick = actions.onFlipAll,
            enabled = specs.isNotEmpty(),
            contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
        ) { Text("Flip all") }
        if (state.snapMixedFacing) {
            Button(
                onClick = { actions.onMixedFacing(false) },
                contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
            ) { Text("Mixed") }
        } else {
            OutlinedButton(
                onClick = { actions.onMixedFacing(true) },
                contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
            ) { Text("Mix facing") }
        }
        if (state.snapMixedFacing && hasSelected) {
            OutlinedButton(
                onClick = { actions.onJointFacing(selected, specs[selected].facing.flipped()) },
                contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
            ) { Text("Turn this one") }
        }
    }
    // The socket mouth chamfer: the lead-in in the hole at 45 degrees, so a
    // square-faced hook cams in and either facing assembles. It does not replace
    // the room the beam needs to bend - and it is the cantilever's: the lip's
    // lead-in is the tooth's own ramp.
    if (style == SnapJointStyle.CANTILEVER) SnapChoiceRow(
        label = "Socket:",
        caption = when {
            stacked -> null
            state.snapSocketRamp -> "45° chamfer on the pocket mouth; the hook still needs its room to bend"
            else -> "the hook's own ramp is the lead-in"
        },
        stacked = stacked,
    ) {
        if (state.snapSocketRamp) {
            Button(
                onClick = { actions.onSocketRamp(false) },
                contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
            ) { Text("Ramped") }
        } else {
            OutlinedButton(
                onClick = { actions.onSocketRamp(true) },
                contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
            ) { Text("Square mouth") }
        }
    }
    // In the single column there is width for the readout on its own line.
    if (!stacked) {
        placed?.let { one ->
            Text(
                (if (one.joint.style == SnapJointStyle.WALL_LIP) "Hold: " else "Click: ") +
                    if (one.joint.style == SnapJointStyle.WALL_LIP) {
                        one.joint.lipSummary + " · " + one.joint.lipFitSummary
                    } else {
                        one.joint.clickSummary
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One half's claim on the beam: the one in force is filled, as the cut's axis is. */
@Composable
private fun BeamHalfButton(
    label: String,
    half: SnapJoint.JointHalf,
    selected: SnapJoint.JointHalf,
    onHalf: (SnapJoint.JointHalf) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (half == selected) {
        Button(onClick = { onHalf(half) }, modifier = modifier, contentPadding = PaddingValues(horizontal = 2.dp)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(
            onClick = { onHalf(half) },
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * The split tool, at the bottom of the model view where the layer viewer keeps
 * its own controls: which way the cut runs, where the plane sits, and the one
 * action that commits it.
 *
 * The slider is the whole preview - the model above it is clipped by the shader
 * as the value moves, so what the user sees is what Split will cut.
 */
@Composable
private fun CutToolbar(
    state: MainUiState,
    actions: CutActions,
    modifier: Modifier = Modifier,
) {
    val span = state.cutSpanMm ?: return
    val names = state.cutHalfNames
    val inside = state.cutInsideModel
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(
                horizontal = EnderSlicerDimens.SheetPadding,
                vertical = EnderSlicerDimens.Space8,
            ),
            verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space6),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Split model", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(EnderSlicerDimens.Space8))
                Text(
                    "Cut ${state.selectedModel?.name.orEmpty()} in two",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = actions.onClose,
                    contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                ) { Text("Close") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space6)) {
                CutAxisButton("Top", ModelPlacement.Axis.Z, state.cutAxis, actions.onAxis, Modifier.weight(1f))
                CutAxisButton("Side X", ModelPlacement.Axis.X, state.cutAxis, actions.onAxis, Modifier.weight(1f))
                CutAxisButton("Side Y", ModelPlacement.Axis.Y, state.cutAxis, actions.onAxis, Modifier.weight(1f))
            }
            CompactSliderRow(
                label = "Cut " + state.cutAxis.name,
                value = state.cutOffsetMm.toFloat(),
                range = span.start..span.endInclusive.coerceAtLeast(span.start + 1f),
                onValueChange = { actions.onOffset(it.toDouble()) },
                valueText = "%.1f".format(state.cutOffsetMm),
            )
            Text(
                "Split into " + names.first + " and " + names.second,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Button(
                onClick = actions.onSplit,
                enabled = inside && !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Split into two parts") }
            if (!inside) {
                Text(
                    "Move the cut inside the model: one side of the plane is empty.",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarnAmber,
                )
            }
        }
    }
}

/** One direction of the cut: the one in force is a filled button, as the paint modes are. */
@Composable
private fun CutAxisButton(
    label: String,
    axis: ModelPlacement.Axis,
    selected: ModelPlacement.Axis,
    onAxis: (ModelPlacement.Axis) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (axis == selected) {
        Button(onClick = { onAxis(axis) }, modifier = modifier) { Text(label, maxLines = 1) }
    } else {
        OutlinedButton(onClick = { onAxis(axis) }, modifier = modifier) { Text(label, maxLines = 1) }
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
    /** Runs once when the thumb is let go: the expensive follow-up goes here. */
    onValueChangeFinished: (() -> Unit)? = null,
    labelWidth: androidx.compose.ui.unit.Dp = 52.dp,
    valueWidth: androidx.compose.ui.unit.Dp = 44.dp,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(labelWidth),
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
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
                    .width(valueWidth)
                    .padding(start = EnderSlicerDimens.Space6),
                maxLines = 1,
            )
        }
    }
}
