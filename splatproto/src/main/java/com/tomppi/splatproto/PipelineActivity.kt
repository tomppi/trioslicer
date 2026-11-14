package com.tomppi.splatproto

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ExifInterface
import android.media.ToneGenerator
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Bundle
import android.view.MotionEvent
import android.util.Log
import android.util.Size
import android.util.SizeF
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.TextButton
import android.os.StatFs
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.SIFT
import org.opencv.video.Video
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.coroutines.resume

/**
 * Scan -> SfM -> train, in one app.
 *
 * Capture is Camera2 via CameraX (no ARCore). The vendored Parallax core reconstructs
 * cameras + points from the frames (COLMAP project in the scan folder), then Brush's
 * trainer consumes that project directly.
 */
data class TrackTrail(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float)


class PipelineActivity : ComponentActivity() {

    private data class State(
        val phase: String = "idle", // idle | capturing | reconstructing | ready | training | done | error
        val status: String = "Scan the object, then train it on the phone",
        val frames: Int = 0,
        val progress: Float = 0f,
        val detail: String = "",
        val hint: String = "",
        val headingDeg: Float = 0f,
        val tilted: Boolean = false,
        val levelMask: Int = 0,
        val aboveMask: Int = 0,
        val trails: List<TrackTrail> = emptyList(),
        val maskCells: List<Pair<Float, Float>> = emptyList(),
        val analysisWidth: Int = 0,
        val analysisHeight: Int = 0,
        val analysisRotation: Int = 0,
        val trackProgress: Float = 0f,
        val ply: String? = null,
    )

    private val state = MutableStateFlow(State())
    private val trailFlow = MutableStateFlow<List<TrackTrail>>(emptyList())
    private val debugLock = Any()

    @Volatile private var hasRoot: Boolean? = null

    /** True when su works; masking needs it because the NPU masker runs outside the app sandbox. */
    private fun checkRoot(onResult: (Boolean) -> Unit) {
        hasRoot?.let { onResult(it); return }
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor() == 0 && out.contains("uid=0")
            }.getOrDefault(false)
            if (ok) hasRoot = true
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    private fun dbg(message: String) {
        try {
            synchronized(debugLock) {
                File(filesDir, "debug.log").appendText(
                    "[" + System.currentTimeMillis() + "] " + message + System.lineSeparator(),
                )
            }
        } catch (_: Throwable) {
        }
    }
    private var settings = PipelineSettings()
    private val showSettings = mutableStateOf(false)
    /** Prompt points on the first keyframe, in normalised image coordinates. */
    private val maskPoints = mutableStateOf<List<Pair<Float, Float>>>(emptyList())
    private val maskNegatives = mutableStateOf<List<Pair<Float, Float>>>(emptyList())
    /** Full-resolution RMBG-2 seed mask for the first keyframe (null until auto-masked). */
    private val seedMask = mutableStateOf<File?>(null)
    private val maskPreview = mutableStateOf<android.graphics.Bitmap?>(null)
    private val showScans = mutableStateOf(false)
    private val scanItems = mutableStateOf<List<ScanItem>>(emptyList())
    private val scansLoading = mutableStateOf(false)
    private val scanFreeBytes = mutableStateOf(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanDir: File? = null
    private var controller: CaptureController? = null
    private var captureModes: List<CaptureMode> = emptyList()
    private var previewImage by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    private var live = LiveState()
    private var lastScan: Scan? = null
    private var maskFirstFrame by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    private var maskPromptPoint by mutableStateOf<Pair<Float, Float>?>(null)
    private var torchMode = TorchMode.OFF

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openViewfinder()
            else state.value = state.value.copy(status = "Camera permission is required")
        }

    /** One-off network diagnostic so download failures can be told apart from DNS failures. */
    private fun logNetworkDiagnostics() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val net = cm.activeNetwork
                val caps = cm.getNetworkCapabilities(net)
                val lp = cm.getLinkProperties(net)
                dbg("net: active=" + net + " dns=" + lp?.dnsServers + " iface=" + lp?.interfaceName +
                    " validated=" + (caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) +
                    " internet=" + (caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true))
            }.onFailure { dbg("net: connectivity info failed " + it) }
            val dns = runCatching {
                java.net.InetAddress.getByName("huggingface.co").hostAddress
            }.getOrElse { "ERR " + it.javaClass.simpleName + ": " + it.message }
            dbg("net: dns huggingface.co -> " + dns)
            val socket = runCatching {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("1.1.1.1", 443), 5000)
                    "tcp 1.1.1.1:443 ok"
                }
            }.getOrElse { "ERR " + it.javaClass.simpleName + ": " + it.message }
            dbg("net: " + socket)
            val socket2 = runCatching {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("18.165.122.120", 443), 5000)
                    "tcp hf-ip:443 ok"
                }
            }.getOrElse { "ERR " + it.javaClass.simpleName + ": " + it.message }
            dbg("net: " + socket2)
            val socket3 = runCatching {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("1.1.1.1", 80), 5000)
                    "tcp 1.1.1.1:80 ok"
                }
            }.getOrElse { "ERR " + it.javaClass.simpleName + ": " + it.message }
            dbg("net: " + socket3)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Long on-device stages (capture, masking, reconstruction, training) must not be frozen:
        // keep the screen on while the pipeline is visible.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = PipelineSettings.load(this)
        // This module is a library, so the host app owns the version string: report it in scan.json.
        BuildConfigCompat.versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        verifyNativeLibraries()

        setContent {
            val s by state.collectAsState()
            LaunchedEffect(s.phase) { dbg("ui phase=" + s.phase) }
            LaunchedEffect(s.hint.isEmpty()) {
                dbg("ui hintEmpty=" + s.hint.isEmpty() + " detailEmpty=" + s.detail.isEmpty() + " phase=" + s.phase)
            }
            val trails by trailFlow.collectAsState()
            val reconstructionJob by ReconstructionService.state.collectAsState()
            LaunchedEffect(reconstructionJob) {
                when (val job = reconstructionJob) {
                    is com.tomppi.splatproto.Job.Done -> {
                        dbg("reconstruction done frames=" + job.scan.imageCount())
                        scanDir = job.scan.dir
                        ReconstructionService.acknowledge()
                        state.value = state.value.copy(
                            phase = "ready", progress = 1f,
                            status = "Reconstructed", detail = "Ready to train",
                        )
                    }
                    is com.tomppi.splatproto.Job.Failed -> {
                        dbg("reconstruction failed: " + job.message)
                        state.value = state.value.copy(
                            phase = "error", status = "Reconstruction failed: " + job.message,
                        )
                    }
                    is com.tomppi.splatproto.Job.Running -> {
                        dbg("reconstruction " + job.stage + " " + (job.fraction * 100).toInt() + "%")
                        state.value = state.value.copy(
                            phase = "reconstructing", progress = job.fraction,
                            status = "Reconstructing",
                            detail = job.stage + " " + (job.fraction * 100).toInt() + "%",
                        )
                    }
                    com.tomppi.splatproto.Job.Idle -> {}
                }
            }
            MaterialTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val plyPath = s.ply
                        when {
                            s.phase == "done" && plyPath != null -> SplatViewer(File(plyPath))
                            s.phase == "capturing" || s.phase == "idle" -> CapturePreview()
                            s.phase == "maskPrompt" -> MaskPromptScreen(
                                image = maskFirstFrame,
                                points = maskPoints.value,
                                negatives = maskNegatives.value,
                                previewBitmap = maskPreview.value,
                                onTap = { tap ->
                                    maskPreview.value = null
                                    val seed = seedMask.value
                                    if (seed != null) {
                                        scope.launch(Dispatchers.IO) {
                                            val stored = storedPoint(tap.first, tap.second)
                                            val changed = MaskParts.togglePartAt(stored.first, stored.second)
                                            val keyframe = lastScan?.let { firstKeyframe(it) }
                                            val bitmap = if (changed && keyframe != null) {
                                                MaskParts.previewBitmap(seed, keyframe)
                                            } else null
                                            withContext(Dispatchers.Main) {
                                                if (bitmap != null) maskPreview.value = bitmap
                                                state.value = state.value.copy(
                                                    status = "Auto mask parts: " +
                                                        String.format("%.1f", MaskParts.keptArea() * 100) +
                                                        "% kept - tap more parts",
                                                )
                                            }
                                        }
                                    } else {
                                        autoMask()
                                    }
                                },
                                onLongPress = { },
                            )
                            else -> {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Text(
                                        text = s.status,
                                        color = androidx.compose.ui.graphics.Color.White,
                                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                    )
                                }
                            }
                        }
                        if (s.phase == "capturing") {
                            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                                CoverageDial(s.levelMask, s.aboveMask, s.headingDeg, s.tilted)
                            }
                        }
                        if (s.phase != "done") {
                            Column(
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (s.phase == "reconstructing" || s.phase == "training" || s.phase == "masking") {
                                    LinearProgressIndicator(
                                        progress = { s.progress },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                OutlinedText(s.detail.ifEmpty { s.status })
                                OutlinedText(s.hint, style = MaterialTheme.typography.bodySmall)
                                if (s.phase == "idle" || s.phase == "capturing") {
                                    OutlinedText(
                                        "Put the object DOWN on a textured surface (do not hold it) - " +
                                            "keep books/table/pattern in frame - orbit the camera around it - " +
                                            "slow and steady - 20-40 frames",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when (s.phase) {
                            "capturing" -> Button(onClick = ::stopAndReconstruct) {
                                Text("Stop & reconstruct")
                            }
                            "ready" -> Button(onClick = { train() }) { Text("Train") }
                            "done" -> {
                                Button(onClick = {
                                    state.value = State()
                                    openViewfinder()
                                }) { Text("New scan") }
                                Button(onClick = { train(false) }) { Text("Retrain unmasked") }
                                Button(onClick = { train(null, true) }) { Text("Refine splat") }
                            }
                            "maskPrompt" -> {
                                Button(
                                    onClick = ::startMasking,
                                    enabled = maskPoints.value.isNotEmpty() || seedMask.value != null,
                                ) { Text("Mask & continue") }
                                Button(onClick = ::autoMask) { Text("Auto mask") }
                                Button(onClick = {
                                    maskPoints.value = emptyList(); maskNegatives.value = emptyList()
                                    seedMask.value = null
                                    maskPreview.value = null
                                }) { Text("Clear mask") }
                                Button(onClick = ::skipMasking) { Text("Skip masks") }
                            }
                            "masking", "reconstructing", "training" -> Button(onClick = {}, enabled = false) {
                                Text("Working...")
                            }
                            else -> {
                                Button(onClick = ::startCapture) { Text("Start scan") }
                            }
                        }
                        if (s.phase == "idle" || s.phase == "capturing") {
                            Button(onClick = ::cycleTorch) { Text(torchLabel()) }
                        }
                        if (s.phase == "idle" || s.phase == "capturing" || s.phase == "ready" || s.phase == "error") {
                            Button(onClick = { loadScans(); showScans.value = true }) { Text("Scans") }
                        }
                        Button(onClick = { showSettings.value = true }) { Text("Settings") }
                    }
                }
                if (showScans.value) {
                    ScansDialog(
                        items = scanItems.value,
                        loading = scansLoading.value,
                        freeBytes = scanFreeBytes.value,
                        onOpen = { item ->
                            showScans.value = false
                            resumeScan(item.scan)
                        },
                        onTrain = { item ->
                            showScans.value = false
                            openScanForTrain(item.scan)
                        },
                        onView = { item ->
                            showScans.value = false
                            val ply = item.splat ?: return@ScansDialog
                            closeCamera()
                            state.value = state.value.copy(
                                phase = "done", progress = 1f, status = "Trained",
                                detail = "Exported " + ply.name, ply = ply.absolutePath,
                            )
                        },
                        onDelete = { item ->
                            item.scan.dir.deleteRecursively()
                            loadScans()
                        },
                        onDismiss = { showScans.value = false },
                    )
                }
                if (showSettings.value) {
                    SettingsDialog(
                        current = settings,
                        onSave = { updated ->
                            settings = updated
                            updated.save(this@PipelineActivity)
                            showSettings.value = false
                        },
                        onDismiss = { showSettings.value = false },
                    )
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            openViewfinder()
        }
    }

    /** Loads both native libraries up front so a missing .so fails visibly, not mid-run. */
    private fun verifyNativeLibraries() {
        dbg("onCreate")
        logNetworkDiagnostics()
        try {
            NativeCore.buildInfo()
            BrushTrainer
            Log.i("Pipeline", "parallaxcore + brush_jni loaded")
        } catch (error: Throwable) {
            Log.e("Pipeline", "native library load failed", error)
        }
    }

    @Composable
    private fun CapturePreview() {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val image = previewImage
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
            val points = live.points
            Canvas(modifier = Modifier.fillMaxSize()) {
                val scanning = state.value.phase == "capturing"
                val radius = 2.2.dp.toPx()
                val stroke = 1.2.dp.toPx()
                for (k in 0 until points.size / 4) {
                    val x = points[4 * k] * size.width
                    val y = points[4 * k + 1] * size.height
                    val ax = points[4 * k + 2] * size.width
                    val ay = points[4 * k + 3] * size.height
                    if (scanning) {
                        drawLine(Color(0x736BE3C3), Offset(ax, ay), Offset(x, y), stroke)
                    }
                    drawCircle(
                        color = if (scanning) Color(0xFF6BE3C3) else Color(0x8CFFFFFF),
                        radius = radius,
                        center = Offset(x, y),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        dbg("lifecycle onResume phase=" + state.value.phase)
        when (state.value.phase) {
            "idle", "" -> openViewfinder()
            "capturing" -> {
                // The camera was closed while paused, so this scan cannot continue.
                state.value = state.value.copy(phase = "idle", status = "Scan interrupted", frames = 0)
                openViewfinder()
            }
            else -> {}
        }
    }

    override fun onPause() {
        super.onPause()
        dbg("lifecycle onPause phase=" + state.value.phase)
        closeCamera()
    }


    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        closeCamera()
    }

    // ---------------------------------------------------------------- capture

    private fun openViewfinder() {
        if (state.value.phase == "capturing") return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (controller == null) {
            val manager = getSystemService(CameraManager::class.java)
            val lenses = CameraProbe.lenses(manager)
            captureModes = CameraProbe.modes(lenses)
            if (captureModes.isEmpty()) {
                state.value = state.value.copy(phase = "error", status = "No usable rear camera found")
                return
            }
            controller = CaptureController(
                this,
                onPreview = { previewImage = it.asImageBitmap() },
                onState = { liveState ->
                    live = liveState
                    if (state.value.phase == "capturing") {
                        state.value = state.value.copy(
                            frames = liveState.keyframes,
                            hint = liveState.hint,
                            detail = "Frames: " + liveState.keyframes,
                        )
                    }
                },
                onError = { message -> state.value = state.value.copy(status = message) },
            )
            controller?.torchMode = torchMode
            // CameraProbe lists the dual-lens rig first, but rig frames from two different
            // lenses do not reconstruct as one scan here (previous scan: 2/30 registered).
            // Use a single lens; it is the same mode Parallax offers first for regular scans.
            val mode = captureModes.firstOrNull { it is CaptureMode.Single } ?: captureModes.first()
            dbg("capture mode=" + mode.label + " rig=" + (mode is CaptureMode.Rig))
            controller?.open(mode)
        }
        state.value = state.value.copy(
            phase = "idle",
            status = "Walk slowly around the object",
            hint = "Fill about half the frame, then press Start scan",
        )
    }

    /** OFF -> AUTO -> ON -> OFF. Default is OFF so the flashlight is never enabled implicitly. */
    private fun cycleTorch() {
        // Manual torch only: a button press switches it on or off and it stays that way.
        torchMode = if (torchMode == TorchMode.ON) TorchMode.OFF else TorchMode.ON
        controller?.torchMode = torchMode
    }

    private fun torchLabel(): String = if (torchMode == TorchMode.ON) "Torch: on" else "Torch: off"

    private fun startCapture() {
        if (state.value.phase == "capturing") return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (controller == null) openViewfinder()
        val capture = controller ?: return
        capture.startScan()
        state.value = State(phase = "capturing", status = "Walk slowly around the object")
    }

    private fun stopAndReconstruct() {
        val capture = controller
        if (capture == null) {
            state.value = state.value.copy(phase = "error", status = "Camera is not open")
            return
        }
        state.value = state.value.copy(phase = "reconstructing", progress = 0f, status = "Stopping scan")
        capture.stopScan { scan ->
            if (scan == null) {
                state.value = state.value.copy(
                    phase = "error",
                    status = "Too few keyframes - move further around the object",
                )
            } else {
                dbg("scan finished keyframes=" + scan.imageCount())
                lastScan = scan
                scanDir = scan.dir
                maskPromptPoint = null
                maskFirstFrame = null
                maskPoints.value = emptyList(); maskNegatives.value = emptyList()
                state.value = state.value.copy(
                    phase = "maskPrompt",
                    progress = 0f,
                    status = "Auto mask the object, then tap parts to adjust",
                    detail = "Auto mask segments the first keyframe on the NPU; taps toggle the " +
                        "parts it kept. For a whole room press Skip masks",
                )
                scope.launch(Dispatchers.IO) {
                    val file = firstKeyframe(scan)
                    val bitmap = file?.let { loadPreviewBitmap(it) }
                    val texture = scanTextureScore(scan)
                    dbg("scan texture corners=" + texture.first + " lapvar=" + String.format("%.1f", texture.second))
                    withContext(Dispatchers.Main) {
                        maskFirstFrame = bitmap
                        if (texture.first < 500 || texture.second < 50.0) {
                            state.value = state.value.copy(
                                detail = "Low texture: only " + texture.first +
                                    " SIFT features/frame - for good reconstruction put the object on a " +
                                    "textured surface, light it well and orbit the camera around it",
                            )
                        }
                    }
                }
            }
        }
    }

    /** Runs the separate NPU masking step, then reconstruction. */
    /** Maps a tap on the upright preview back to normalised coordinates of the stored JPEG. */
    private fun storedPoint(u: Float, v: Float): Pair<Float, Float> {
        val orientation = lastScan?.let { firstKeyframe(it) }?.let { exifOrientation(it) }
            ?: ExifInterface.ORIENTATION_NORMAL
        val stored = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> v to 1f - u
            ExifInterface.ORIENTATION_ROTATE_270 -> 1f - v to u
            ExifInterface.ORIENTATION_ROTATE_180 -> 1f - u to 1f - v
            else -> u to v
        }
        return stored.first.coerceIn(0f, 1f) to stored.second.coerceIn(0f, 1f)
    }

    /** Auto seed from U2Net on the NPU: segments the first keyframe and shows the mask. */
    private fun autoMask() {
        val scan = lastScan ?: return
        if (hasRoot == false) {
            state.value = state.value.copy(
                status = "Masking needs root (NPU masker cannot run inside the app sandbox)",
                detail = "Masking disabled on this device - press Skip masks",
            )
            return
        }
        val keyframe = firstKeyframe(scan) ?: return
        state.value = state.value.copy(
            status = "Segmenting first keyframe (NPU)...", detail = "U2Net on the NPU",
        )
        scope.launch(Dispatchers.IO) {
            val logger: (String) -> Unit = { message -> dbg("u2net: " + message) }
            MaskParts.rememberContext(this@PipelineActivity)
            val segmented = NpuMasker.u2netSegmentFirstFrame(this@PipelineActivity, scan, logger)
            segmented?.let { MaskParts.prepareParts(it.first, keyframe) }
            val bitmap = segmented?.let { MaskParts.previewBitmap(it.first, keyframe) }
            withContext(Dispatchers.Main) {
                if (segmented == null || bitmap == null) {
                    state.value = state.value.copy(
                        status = "Auto mask failed - check debug.log",
                        detail = "Auto mask failed - check debug.log",
                    )
                } else {
                    seedMask.value = segmented.first
                    maskPreview.value = bitmap
                    state.value = state.value.copy(
                        status = "Auto mask: U2Net " + String.format("%.1f", segmented.second * 100) + "%",
                        detail = "Auto mask ready - tap parts to add/remove, press Mask & continue",
                    )
                }
            }
        }
    }

    private fun startMasking() {
        val scan = lastScan ?: return
        if (hasRoot == false) {
            state.value = state.value.copy(
                status = "Masking needs root (NPU masker cannot run inside the app sandbox)",
                detail = "Masking disabled - press Skip masks",
            )
            return
        }
        val seed = seedMask.value
        val images = (scan.imagesDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg", true) } ?: emptyArray())
            .sortedBy { it.name }
        if (images.isEmpty()) {
            state.value = state.value.copy(status = "No keyframes to mask")
            return
        }
        dbg("mask: u2net scan frames=" + images.size + " seed=" + (seed != null))
        state.value = state.value.copy(
            phase = "masking", progress = 0f,
            status = "Masking frames", detail = "U2Net on the NPU",
        )
        scope.launch(Dispatchers.IO) {
            val logger: (String) -> Unit = { message -> dbg("mask: " + message) }
            MaskParts.releaseParts()
            val result = NpuMasker.maskScanU2Net(
                this@PipelineActivity,
                scan,
                { fraction, message ->
                    runOnUiThread {
                        state.value = state.value.copy(progress = fraction, status = message, detail = message)
                    }
                },
                logger,
            )
            dbg("mask: u2net ok=" + result.ok + " msg=" + result.message +
                " rss=" + MaskParts.rssMb() + " MB")
            withContext(Dispatchers.Main) {
                if (!result.ok) {
                    state.value = state.value.copy(
                        status = "Masks failed: " + result.message,
                        detail = "Training without masks",
                    )
                }
                ReconstructionService.start(this@PipelineActivity, scan)
                state.value = state.value.copy(
                    phase = "reconstructing", progress = 0f,
                    status = "Reconstructing", detail = "",
                )
            }
        }
    }

    /** Continues to reconstruction without masks. */
    private fun skipMasking() {
        val scan = lastScan ?: return
        ReconstructionService.start(this, scan)
        state.value = state.value.copy(
            phase = "reconstructing", progress = 0f,
            status = "Reconstructing", detail = "No masks",
        )
    }

    private fun firstKeyframe(scan: Scan): File? {
        val name = runCatching {
            org.json.JSONObject(scan.meta.readText()).optJSONArray("images")
                ?.optJSONObject(0)?.optString("file").orEmpty()
        }.getOrNull()
        if (!name.isNullOrEmpty()) {
            val f = File(scan.imagesDir, name)
            if (f.isFile) return f
        }
        return scan.imagesDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg", true) }
            ?.minByOrNull { it.name }
    }

    /** Average trackable-corner count and Laplacian variance over a few keyframes (low = bad SfM). */
    private fun scanTextureScore(scan: Scan): Pair<Int, Double> = runCatching {
        val frames = scan.imagesDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg", true) }
            ?.sortedBy { it.name } ?: return@runCatching 0 to 0.0
        if (frames.isEmpty()) return@runCatching 0 to 0.0
        val picks = listOf(frames.first(), frames[frames.size / 2], frames.last()).distinct()
        var corners = 0
        var lap = 0.0
        for (file in picks) {
            val bgr = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
            if (bgr.empty()) continue
            val gray = Mat()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            val scaled = Mat()
            Imgproc.resize(gray, scaled, org.opencv.core.Size(1024.0, 1024.0 * bgr.rows() / bgr.cols()))
            val kps = MatOfKeyPoint()
            val descriptors = Mat()
            val sift = SIFT.create(4000, 3, 0.02, 10.0, 1.6)
            sift.detectAndCompute(scaled, Mat(), kps, descriptors)
            corners += kps.toArray().size
            val lapMat = Mat()
            Imgproc.Laplacian(scaled, lapMat, CvType.CV_64F)
            val mean = org.opencv.core.MatOfDouble()
            val std = org.opencv.core.MatOfDouble()
            Core.meanStdDev(lapMat, mean, std)
            lap += std.get(0, 0)[0] * std.get(0, 0)[0]
            kps.release(); descriptors.release(); bgr.release(); gray.release(); scaled.release(); lapMat.release()
        }
        (corners / picks.size) to (lap / picks.size)
    }.getOrElse { 0 to 0.0 }

    private fun loadPreviewBitmap(file: File): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
        val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        rotateForExif(decoded, exifOrientation(file)).asImageBitmap()
    }.getOrNull()

    /** EXIF orientation tag of a keyframe (ORIENTATION_NORMAL when unreadable). */
    private fun exifOrientation(file: File): Int = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** Applies the EXIF orientation so the preview is upright, like the camera showed it. */
    private fun rotateForExif(bitmap: android.graphics.Bitmap, orientation: Int): android.graphics.Bitmap {
        val matrix = android.graphics.Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Rotates a Mat from display orientation back to the stored JPEG orientation (or the reverse). */
    private fun rotateForOrientation(src: Mat, orientation: Int, toStored: Boolean): Mat {
        val code = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> if (toStored) Core.ROTATE_90_COUNTERCLOCKWISE else Core.ROTATE_90_CLOCKWISE
            ExifInterface.ORIENTATION_ROTATE_180 -> Core.ROTATE_180
            ExifInterface.ORIENTATION_ROTATE_270 -> if (toStored) Core.ROTATE_90_CLOCKWISE else Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return src
        }
        val out = Mat()
        Core.rotate(src, out, code)
        return out
    }

    private data class ScanItem(
        val scan: Scan,
        val frames: Int,
        val masks: Int,
        val reconstructed: Boolean,
        val splat: File?,
        val bytes: Long,
        val modified: Long,
    )

    /** Lists every scan on disk with size and pipeline state. */
    private fun loadScans() {
        scansLoading.value = true
        scope.launch(Dispatchers.IO) {
            val dir = File(filesDir, "scans")
            val items = (dir.listFiles()?.filter { it.isDirectory } ?: emptyList())
                .mapNotNull { d ->
                    val frames = File(d, "images").listFiles { f -> f.isFile && f.name.endsWith(".jpg", true) }?.size ?: 0
                    if (frames == 0) return@mapNotNull null
                    val masks = File(d, "masks").listFiles { f -> f.isFile && f.name.endsWith(".png", true) }?.size ?: 0
                    val report = File(d, "report.json").isFile
                    val splat = File(d, "exports").listFiles { f -> f.isFile && f.extension == "ply" }
                        ?.maxByOrNull { it.lastModified() }
                    ScanItem(
                        Scan(d), frames, masks, report, splat,
                        d.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                        d.lastModified(),
                    )
                }
                .sortedByDescending { it.modified }
            val free = runCatching { StatFs(filesDir.absolutePath).availableBytes }.getOrDefault(0L)
            withContext(Dispatchers.Main) {
                scanItems.value = items
                scanFreeBytes.value = free
                scansLoading.value = false
            }
        }
    }

    /** Most recent scan folder on disk that still has keyframes. */
    private fun latestScan(): Scan? = runCatching {
        val scan = File(filesDir, "scans").listFiles()
            ?.filter { it.isDirectory }
            ?.filter { dir ->
                // Keyframes live in images/, not directly in the scan folder.
                File(dir, "images").listFiles { f -> f.isFile && f.name.endsWith(".jpg", true) }?.isNotEmpty() == true
            }
            ?.maxByOrNull { it.name }
            ?.let { Scan(it) }
        dbg("resume candidate=" + (scan?.name ?: "none"))
        scan
    }.getOrNull()

    /** Reopens the newest scan on disk at the masking step (e.g. after the app was killed). */
    private fun resumeScan(scan: Scan) {
        closeCamera()
        lastScan = scan
        scanDir = scan.dir
        maskPromptPoint = null
        maskFirstFrame = null
        maskPoints.value = emptyList(); maskNegatives.value = emptyList()
        state.value = state.value.copy(
            phase = "maskPrompt",
            progress = 0f,
            status = "Auto mask the object, then tap parts to adjust",
            detail = "Resuming scan " + scan.name + " - press Auto mask to segment the object",
        )
        scope.launch(Dispatchers.IO) {
            val file = firstKeyframe(scan)
            val bitmap = file?.let { loadPreviewBitmap(it) }
            withContext(Dispatchers.Main) { maskFirstFrame = bitmap }
        }
    }

    /** Releases the camera; the next scan reopens it. */
    private fun closeCamera() {
        controller?.close()
        controller = null
        previewImage = null
        live = LiveState()
    }

    private fun train(useMasksOverride: Boolean? = null, warmStart: Boolean = false) {
        val dir = scanDir ?: return
        closeCamera()
        val exports = File(dir, "exports").apply { mkdirs() }
        val masksDir = File(dir, "masks")
        val masksOff = File(dir, "masks.disabled")
        val useMasks = useMasksOverride ?: settings.useMasks
        val hideMasks = !useMasks && masksDir.isDirectory
        if (hideMasks) {
            masksOff.deleteRecursively()
            masksDir.renameTo(masksOff)
        }
        val restoreMasks = {
            if (hideMasks) {
                masksDir.deleteRecursively()
                masksOff.renameTo(masksDir)
                Unit
            }
        }
        val steps = settings.trainSteps
        state.value = state.value.copy(
            phase = "training",
            progress = 0f,
            status = "Training",
            detail = "Loading Brush...",
        )
        val initTarget = File(dir, "init.ply")
        if (warmStart) {
            val source = state.value.ply?.let { File(it) }?.takeIf { it.isFile }
                ?: File(filesDir, "splats").listFiles { f -> f.isFile && f.extension == "ply" }
                ?.maxByOrNull { it.lastModified() }
                ?: exports.listFiles { f -> f.extension == "ply" }?.maxByOrNull { it.lastModified() }
            if (source != null) {
                runCatching { source.copyTo(initTarget, overwrite = true) }
                dbg("train init=warm start " + source.name + " " + source.length() + " bytes")
            } else {
                dbg("train init=warm start requested but no splat found")
            }
        } else {
            val denseInit = File(dir, "dense.ply")
            if (denseInit.isFile) {
                runCatching { denseInit.copyTo(initTarget, overwrite = true) }
                dbg("train init=dense.ply " + denseInit.length() + " bytes")
            } else {
                dbg("train init=dataset default")
            }
        }
        dbg("train steps=" + steps + " res=" + settings.maxResolution + " useMasks=" + useMasks + " hidden=" + hideMasks)
        scope.launch(Dispatchers.Default) {
            val exit = BrushTrainer.nativeTrain(
                datasetPath = dir.absolutePath,
                totalSteps = steps,
                refineEvery = settings.refineEvery,
                maxResolution = settings.maxResolution,
                exportEvery = settings.exportEvery,
                maxSplats = settings.maxSplats,
                shDegree = settings.shDegree,
                lrMean = settings.lrMean.toDouble(),
                lrCoeffsDc = settings.lrCoeffsDc.toDouble(),
                lrOpac = settings.lrOpac.toDouble(),
                lrScale = settings.lrScale.toDouble(),
                growthGradThreshold = settings.growthGradThreshold,
                growthSelectFraction = settings.growthSelectFraction,
                minScaleFactor = settings.minScaleFactor,
                ssimWeight = settings.ssimWeight,
                growthStopIter = settings.growthStopIter,
                outputPath = exports.absolutePath,
                progress = object : BrushProgress {
                    override fun onProgress(stage: Int, iter: Int): Boolean {
                        if (iter % 500 == 0) Log.i("Pipeline", "train: stage=" + stage + " iter=" + iter)
                        val fraction = (iter.toFloat() / steps).coerceIn(0f, 1f)
                        state.value = state.value.copy(
                            progress = fraction,
                            detail = when (stage) {
                                0 -> "Starting Brush"
                                2 -> "Finishing and exporting"
                                else -> "Training: " + iter + " / " + steps
                            },
                        )
                        return true
                    }
                },
            )
            Log.i("Pipeline", "train finished: exit=" + exit)
            if (exit != 0) {
                restoreMasks()
                state.value = state.value.copy(
                    phase = "error",
                    status = "Training failed (code " + exit + ")",
                )
                return@launch
            }
            val ply = exports.listFiles { f -> f.extension == "ply" }
                ?.sortedBy { it.lastModified() }
                ?.lastOrNull()
            if (ply == null) {
                restoreMasks()
                state.value = state.value.copy(
                    phase = "error",
                    status = "Training finished without a PLY",
                )
                return@launch
            }
            val splatsDir = File(filesDir, "splats").apply { mkdirs() }
            // The host app's Splat viewer lists every file in filesDir/splats, so the trained
            // scene lands there under the scan's own name instead of overwriting one shared
            // "trained.ply" on every run.
            val target = File(splatsDir, dir.name + "-splat.ply")
            ply.copyTo(target, overwrite = true)
            restoreMasks()
            state.value = state.value.copy(
                phase = "done",
                progress = 1f,
                status = "Trained",
                detail = "Exported " + ply.name,
                ply = target.absolutePath,
            )
        }
    }

    private fun mb(bytes: Long): String = String.format("%.0f MB", bytes / 1048576.0)

    private fun scanSummary(item: ScanItem): String {
        val parts = ArrayList<String>(5)
        parts.add(item.frames.toString() + " frames")
        parts.add(item.masks.toString() + " masks")
        parts.add(if (item.reconstructed) "reconstructed" else "not reconstructed")
        parts.add(mb(item.bytes))
        return parts.joinToString(" - ")
    }

    /** Loads an existing scan into the ready-to-train state. */
    private fun openScanForTrain(scan: Scan) {
        closeCamera()
        lastScan = scan
        scanDir = scan.dir
        maskPromptPoint = null
        state.value = state.value.copy(
            phase = "ready", progress = 1f, status = "Reconstructed",
            detail = "Ready to train " + scan.name,
        )
    }

    @Composable
    private fun ScansDialog(
        items: List<ScanItem>,
        loading: Boolean,
        freeBytes: Long,
        onOpen: (ScanItem) -> Unit,
        onTrain: (ScanItem) -> Unit,
        onView: (ScanItem) -> Unit,
        onDelete: (ScanItem) -> Unit,
        onDismiss: () -> Unit,
    ) {
        var pendingDelete by remember { mutableStateOf<ScanItem?>(null) }
        val target = pendingDelete
        if (target != null) {
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Delete scan?") },
                text = {
                    Text(
                        "Deletes " + target.scan.name + " (" + mb(target.bytes) + ") including images, " +
                            "masks and exports. This cannot be undone.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { onDelete(target); pendingDelete = null }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
            )
            return
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Scans") },
            text = {
                Column {
                    Text(
                        mb(items.sumOf { it.bytes }) + " in " + items.size + " scans - " + mb(freeBytes) + " free",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (loading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Loading...", style = MaterialTheme.typography.bodySmall)
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(items) { item ->
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(item.scan.name)
                                Text(scanSummary(item), style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { onOpen(item) }) { Text("Open") }
                                    if (item.reconstructed) {
                                        TextButton(onClick = { onTrain(item) }) { Text("Train") }
                                    }
                                    if (item.splat != null) {
                                        TextButton(onClick = { onView(item) }) { Text("View") }
                                    }
                                    TextButton(onClick = { pendingDelete = item }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
    }

    @Composable
    private fun SettingsDialog(
        current: PipelineSettings,
        onSave: (PipelineSettings) -> Unit,
        onDismiss: () -> Unit,
    ) {
        var s by remember { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Settings") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text("SfM (Parallax)", style = MaterialTheme.typography.titleSmall)
                    SettingsNumber("Feature side", s.sfmFeatureSide.toFloat()) { v ->
                        s = s.copy(sfmFeatureSide = v.toInt().coerceAtLeast(256))
                    }
                    SettingsNumber("Max features", s.sfmMaxFeatures.toFloat()) { v ->
                        s = s.copy(sfmMaxFeatures = v.toInt().coerceAtLeast(100))
                    }
                    SettingsNumber("Match window", s.sfmMatchWindow.toFloat()) { v ->
                        s = s.copy(sfmMatchWindow = v.toInt().coerceIn(1, 50))
                    }
                    SettingsNumber("Ratio test", s.sfmRatio) { v -> s = s.copy(sfmRatio = v) }
                    SettingsNumber("Max reproj (px)", s.sfmMaxReproj) { v -> s = s.copy(sfmMaxReproj = v) }
                    SettingsNumber("Min tri angle", s.sfmMinTriAngle) { v -> s = s.copy(sfmMinTriAngle = v) }
                    SettingsToggle("Dense stereo", s.sfmDense) { v -> s = s.copy(sfmDense = v) }
                    SettingsNumber("Dense side", s.sfmDenseMaxSide.toFloat()) { v ->
                        s = s.copy(sfmDenseMaxSide = v.toInt().coerceAtLeast(256))
                    }
                    SettingsNumber("Dense images", s.sfmDenseMaxImages.toFloat()) { v ->
                        s = s.copy(sfmDenseMaxImages = v.toInt().coerceIn(1, 200))
                    }
                    SettingsToggle("Rolling shutter", s.sfmRollingShutter) { v ->
                        s = s.copy(sfmRollingShutter = v)
                    }
                    SettingsNumber("Marker (mm)", s.sfmMarkerMm) { v -> s = s.copy(sfmMarkerMm = v) }

                    Text("Brush training", style = MaterialTheme.typography.titleSmall)
                    SettingsNumber("Steps", s.trainSteps.toFloat()) { v ->
                        s = s.copy(trainSteps = v.toInt().coerceIn(100, 100000))
                    }
                    SettingsNumber("Refine every", s.refineEvery.toFloat()) { v ->
                        s = s.copy(refineEvery = v.toInt().coerceIn(1, 5000))
                    }
                    SettingsNumber("Max splats", s.maxSplats.toFloat()) { v ->
                        s = s.copy(maxSplats = v.toInt().coerceIn(1000, 10_000_000))
                    }
                    SettingsNumber("Max resolution", s.maxResolution.toFloat()) { v ->
                        s = s.copy(maxResolution = v.toInt().coerceIn(128, 4096))
                    }
                    SettingsNumber("Export every", s.exportEvery.toFloat()) { v ->
                        s = s.copy(exportEvery = v.toInt().coerceIn(100, 100000))
                    }
                    SettingsNumber("SH degree", s.shDegree.toFloat()) { v ->
                        s = s.copy(shDegree = v.toInt().coerceIn(0, 4))
                    }
                    SettingsToggle("Use masks when training", s.useMasks) { v ->
                        s = s.copy(useMasks = v)
                    }
                    SettingsNumber("LR mean", s.lrMean) { v -> s = s.copy(lrMean = v) }
                    SettingsNumber("LR color", s.lrCoeffsDc) { v -> s = s.copy(lrCoeffsDc = v) }
                    SettingsNumber("LR opacity", s.lrOpac) { v -> s = s.copy(lrOpac = v) }
                    SettingsNumber("LR scale", s.lrScale) { v -> s = s.copy(lrScale = v) }
                    SettingsNumber("Growth threshold", s.growthGradThreshold) { v ->
                        s = s.copy(growthGradThreshold = v)
                    }
                    SettingsNumber("Growth fraction", s.growthSelectFraction) { v ->
                        s = s.copy(growthSelectFraction = v)
                    }
                    SettingsNumber("Growth stop iter", s.growthStopIter.toFloat()) { v ->
                        s = s.copy(growthStopIter = v.toInt().coerceIn(100, 100000))
                    }
                    SettingsNumber("Mip scale floor", s.minScaleFactor) { v ->
                        s = s.copy(minScaleFactor = v)
                    }
                    SettingsNumber("SSIM weight", s.ssimWeight) { v -> s = s.copy(ssimWeight = v) }
                }
            },
            confirmButton = { TextButton(onClick = { onSave(s) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }

    /** White text with a thin black outline so it stays readable on any camera image. */
    @Composable
    private fun OutlinedText(
        text: String,
        style: TextStyle = MaterialTheme.typography.bodyMedium,
        color: Color = Color.White,
    ) {
        // White text with a soft black halo. A drawn stroke eats thin glyph stems at phone text
        // sizes, so the dark contrast comes from a shadow behind the glyphs instead.
        Text(
            text = text,
            style = style.copy(
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = Color.Black,
                    offset = Offset(0f, 0f),
                    blurRadius = 6f,
                ),
            ),
            color = color,
        )
    }

    @Composable
    private fun SettingsNumber(label: String, value: Float, onChange: (Float) -> Unit) {
        val initial = if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
        var textValue by remember(value) { mutableStateOf(initial) }
        OutlinedTextField(
            value = textValue,
            onValueChange = { input: String ->
                textValue = input
                input.toFloatOrNull()?.let(onChange)
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun SettingsToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f))
            Switch(checked = value, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun CoverageDial(levelMask: Int, aboveMask: Int, headingDeg: Float, tilted: Boolean) {
        Canvas(modifier = Modifier.size(104.dp)) {
            val c = center
            val outer = size.minDimension / 2f
            val inner = outer * 0.62f
            for (i in 0 until 12) {
                val start = i * 30f - 90f
                val level = (levelMask shr i) and 1 == 1
                drawArc(
                    color = if (level) Color(0xFF6BE3C3) else Color(0x33FFFFFF),
                    startAngle = start, sweepAngle = 26f, useCenter = false,
                    topLeft = Offset(c.x - outer, c.y - outer),
                    size = androidx.compose.ui.geometry.Size(outer * 2f, outer * 2f),
                    style = Stroke(width = 9f),
                )
                val above = (aboveMask shr i) and 1 == 1
                drawArc(
                    color = if (above) Color(0xFF6BE3C3) else Color(0x33FFFFFF),
                    startAngle = start, sweepAngle = 26f, useCenter = false,
                    topLeft = Offset(c.x - inner, c.y - inner),
                    size = androidx.compose.ui.geometry.Size(inner * 2f, inner * 2f),
                    style = Stroke(width = 7f),
                )
            }
            val markRadius = if (tilted) (inner + outer) / 2f else outer - 5f
            val rad = Math.toRadians((headingDeg - 90f).toDouble())
            drawCircle(
                color = Color.White,
                radius = 4f,
                center = Offset(
                    c.x + (markRadius * kotlin.math.cos(rad)).toFloat(),
                    c.y + (markRadius * kotlin.math.sin(rad)).toFloat(),
                ),
            )
        }
    }

    /** Tracks feature points with pyramidal Lucas-Kanade and draws trails back to the last keyframe. */
    /**
     * Masking step: shows the first keyframe and lets the user tap the object to keep.
     * Everything except the resulting mask is ignored by Brush training.
     */
    @Composable
    private fun MaskPromptScreen(
        image: androidx.compose.ui.graphics.ImageBitmap?,
        points: List<Pair<Float, Float>>,
        negatives: List<Pair<Float, Float>>,
        previewBitmap: android.graphics.Bitmap?,
        onTap: (Pair<Float, Float>) -> Unit,
        onLongPress: (Pair<Float, Float>) -> Unit,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            val boxWidth = constraints.maxWidth.toFloat()
            val boxHeight = constraints.maxHeight.toFloat()
            if (image == null) {
                Text(
                    "Loading keyframe...",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                val scale = minOf(boxWidth / image.width, boxHeight / image.height)
                val displayWidth = image.width * scale
                val displayHeight = image.height * scale
                val offsetX = (boxWidth - displayWidth) / 2f
                val offsetY = (boxHeight - displayHeight) / 2f
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "Mask preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { tap ->
                                    onTap(
                                        ((tap.x - offsetX) / displayWidth).coerceIn(0f, 1f) to
                                            ((tap.y - offsetY) / displayHeight).coerceIn(0f, 1f),
                                    )
                                },
                                onLongPress = { tap ->
                                    onLongPress(
                                        ((tap.x - offsetX) / displayWidth).coerceIn(0f, 1f) to
                                            ((tap.y - offsetY) / displayHeight).coerceIn(0f, 1f),
                                    )
                                },
                            )
                        },
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    for (point in negatives) {
                        val center = Offset(
                            offsetX + point.first * displayWidth,
                            offsetY + point.second * displayHeight,
                        )
                        drawCircle(color = Color(0xCCFF4B4B), radius = 10f, center = center)
                        drawCircle(color = Color.White, radius = 22f, center = center, style = Stroke(width = 4f))
                        drawLine(
                            Color.White,
                            Offset(center.x - 10f, center.y),
                            Offset(center.x + 10f, center.y),
                            strokeWidth = 4f,
                        )
                    }
                    for ((index, point) in points.withIndex()) {
                        val center = Offset(
                            offsetX + point.first * displayWidth,
                            offsetY + point.second * displayHeight,
                        )
                        drawCircle(color = Color(0xCC58C4BB), radius = 10f, center = center)
                        drawCircle(
                            color = Color.White,
                            radius = 22f,
                            center = center,
                            style = Stroke(width = 4f),
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            (index + 1).toString(),
                            center.x + 26f,
                            center.y - 18f,
                            android.graphics.Paint().apply {
                                color = android.graphics.Color.WHITE
                                textSize = 42f
                                isAntiAlias = true
                            },
                        )
                    }
                }
                Text(
                    "Press Auto mask first, then tap parts to adjust",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 18.dp),
                )
            }
        }
    }

    private companion object {
        const val MAX_FRAMES = 150
        const val CAPTURE_INTERVAL_MS = 700L
        const val TRAIN_STEPS = 10_000
        const val REFINE_EVERY = 100
    }
}