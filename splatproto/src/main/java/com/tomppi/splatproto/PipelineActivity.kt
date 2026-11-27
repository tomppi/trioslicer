package com.tomppi.splatproto

import com.rawmechatronics.easyscanner.VioRunner

import android.Manifest
import android.content.Intent
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
import android.os.Handler
import android.os.Looper
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.compose.foundation.horizontalScroll
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
 * Scan -> SfM -> Cross3DGS Vulkan train, in one workflow.
 *
 * Capture is Camera2 + SuperVINS VIO (no ARCore). The vendored Parallax core reconstructs
 * metric cameras + points from the frames (COLMAP project in the scan folder), then Cross3DGS
 * trains the 3D Gaussian Splat on the phone's Vulkan GPU.
 */
data class TrackTrail(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float)


class PipelineActivity : ComponentActivity() {

    private data class State(
        val phase: String = "idle", // idle | capturing | reconstructing | ready | training | done | error
        val scanKind: ScanKind = ScanKind.SPLAT_ONLY,
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
        val hasMasks: Boolean = false,
        val fusedStl: String? = null,
        val fusedCloudSplat: String? = null,
        val laserOnlyPly: String? = null,
    )

    private fun scanHasMasks(dir: File?): Boolean =
        dir != null && File(dir, "masks").listFiles { f -> f.isFile && f.name.endsWith(".png", true) }?.isNotEmpty() == true

    private var launchedFromEasyScanner = false

    private val state = MutableStateFlow(State())
    private val trailFlow = MutableStateFlow<List<TrackTrail>>(emptyList())

    private data class VioUi(
        val tracking: Boolean = false,
        val ready: Boolean = false,
        val settled: Boolean = false,
        val moving: Boolean = false,
        val drifted: Boolean = false,
        val speed: Double = 0.0,
        val trail: Array<FloatArray> = emptyArray(),
    )
    private val vioUi = MutableStateFlow(VioUi())
    /** Centered banner, exactly like the scanner's VIO INIT / VIO TRACKING popup. */
    private val vioBanner = MutableStateFlow<String?>(null)
    private var lastVioUiAt = 0L

    /** Snapshot for the minimap: SuperVINS is the only thing that knows whether the pose is usable. */
    private fun refreshVioUi(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastVioUiAt < 150L) return
        lastVioUiAt = now
        val v = vio
        if (v == null) { vioUi.value = VioUi(); return }
        val speed = runCatching { v.velocityNorm() }.getOrDefault(0.0)
        val trail = v.trajectoryPoints()
        vioUi.value = VioUi(
            tracking = v.tracking,
            ready = v.initialized,
            settled = v.initialized && speed < 0.6,
            moving = v.initialized && speed >= 0.6 && speed <= 2.0,
            drifted = v.initialized && speed > 2.0,
            speed = speed,
            trail = trail,
        )
    }
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
    /** Full-resolution U2Net seed mask for the first keyframe (null until auto-masked). */
    private val seedMask = mutableStateOf<File?>(null)
    private val maskPreview = mutableStateOf<android.graphics.Bitmap?>(null)
    private val showScans = mutableStateOf(false)
    private val scanItems = mutableStateOf<List<ScanItem>>(emptyList())
    private val scansLoading = mutableStateOf(false)
    private val scanFreeBytes = mutableStateOf(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanDir: File? = null
    private var controller: CaptureController? = null
    /** VIO pose source: the same SuperVINS (SuperPoint + LightGlue + Ceres) runner the scanner uses. */
    private var vio: VioRunner? = null
    private var warmupActive = false
    private var warmupTicker: Runnable? = null
    private var warmupStartMs = 0L
    private var pendingCaptureStart = false
    private val mainHandler = Handler(Looper.getMainLooper())
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

        val incomingSession = intent?.getStringExtra(EXTRA_EASYSCAN_SESSION)
            ?.let { File(it) }
            ?.takeIf {
                it.isDirectory && (
                    File(it, "splat_session.json").isFile ||
                        File(it, "frames.csv").isFile ||
                        File(it, "session.json").isFile
                    )
            }
        val incomingSplatOnly = intent?.getBooleanExtra(EXTRA_SPLAT_ONLY, false) == true ||
            (incomingSession != null && File(incomingSession, "splat_session.json").isFile)
        launchedFromEasyScanner = incomingSession != null
        if (incomingSession != null) {
            state.value = state.value.copy(
                phase = "reconstructing",
                scanKind = if (incomingSplatOnly) ScanKind.SPLAT_ONLY else ScanKind.FUSED,
                progress = 0f,
                status = if (incomingSplatOnly) "Importing high-res Splat session…" else "Importing EasyScanner fusion session…",
                detail = incomingSession.name,
            )
            importEasyScannerSessionDir(incomingSession, autoOpen = true)
        }

        setContent {
            val s by state.collectAsState()
            val vioState by vioUi.collectAsState()
            val vioBannerText by vioBanner.collectAsState()
            LaunchedEffect(s.phase) { dbg("ui phase=" + s.phase) }
            LaunchedEffect(s.hint.isEmpty()) {
                dbg("ui hintEmpty=" + s.hint.isEmpty() + " detailEmpty=" + s.detail.isEmpty() + " phase=" + s.phase)
            }
            val trails by trailFlow.collectAsState()
            val reconstructionJob by ReconstructionService.state.collectAsState()
            LaunchedEffect(reconstructionJob) {
                when (val job = reconstructionJob) {
                    is com.tomppi.splatproto.Job.Done -> {
                        val report = job.scan.reportJson()
                        val poseSource = report?.optString("poseSource", "?") ?: "?"
                        val sparse = report?.optInt("sparsePoints", -1) ?: -1
                        val registered = report?.optInt("imagesRegistered", -1) ?: -1
                        val total = report?.optInt("imagesTotal", -1) ?: -1
                        Log.i("Pipeline", "reconstruction done poseSource=" + poseSource +
                            " images=" + registered + "/" + total + " sparsePoints=" + sparse)
                        dbg("reconstruction done frames=" + job.scan.imageCount() +
                            " poseSource=" + poseSource + " points=" + sparse)
                        scanDir = job.scan.dir
                        lastScan = job.scan
                        val kind = job.scan.kind()
                        ReconstructionService.acknowledge()
                        val actionHint = when (kind) {
                            ScanKind.FUSED -> "Ready to fuse Laser + MVS (STL)"
                            ScanKind.LASER_ONLY -> "Laser scan ready"
                            ScanKind.SPLAT_ONLY -> "Ready to train Cross3DGS"
                        }
                        state.value = state.value.copy(
                            phase = "ready",
                            scanKind = kind,
                            progress = 1f,
                            status = "Reconstructed · pose " + poseSource,
                            detail = "$actionHint · $registered/$total images, $sparse sparse points",
                            ply = null,
                            fusedStl = null,
                            fusedCloudSplat = null,
                            laserOnlyPly = null,
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
                            s.phase == "capturing" || s.phase == "idle" -> CapturePreview(vioState, vioBannerText)
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (s.phase == "idle" || s.phase == "capturing") {
                            Button(onClick = ::toggleVio, enabled = vio != null) {
                                Text(if (vioState.tracking) "Stop VIO" else "Start VIO")
                            }
                        }
                        when (s.phase) {
                            "capturing" -> Button(onClick = ::stopAndReconstruct) {
                                Text("Stop & reconstruct")
                            }
                            "ready" -> {
                                when (s.scanKind) {
                                    ScanKind.SPLAT_ONLY -> {
                                        Button(onClick = { train() }) { Text("Train Splat") }
                                        s.ply?.let { plyPath ->
                                            Button(onClick = {
                                                state.value = state.value.copy(
                                                    phase = "done",
                                                    progress = 1f,
                                                    status = "Trained",
                                                    ply = plyPath,
                                                    hasMasks = scanHasMasks(scanDir),
                                                )
                                            }) { Text("View Splat") }
                                        }
                                    }
                                    ScanKind.FUSED -> {
                                        Button(onClick = { lastScan?.let { fuseAndMesh(it) } }) { Text("Fuse & Mesh (STL)") }
                                        s.fusedStl?.let { stlPath ->
                                            Button(onClick = { sendStlToPlate(stlPath) }) { Text("Send to Plate") }
                                        }
                                        s.fusedCloudSplat?.let { splatName ->
                                            Button(onClick = { openCloudView(splatName) }) { Text("View Cloud") }
                                        }
                                        s.laserOnlyPly?.let { laserPlyPath ->
                                            Button(onClick = { openLaserOnlyCloud(File(laserPlyPath)) }) { Text("Laser only") }
                                        }
                                    }
                                    ScanKind.LASER_ONLY -> {
                                        s.fusedStl?.let { stlPath ->
                                            Button(onClick = { sendStlToPlate(stlPath) }) { Text("Send to Plate") }
                                        }
                                        s.laserOnlyPly?.let { laserPlyPath ->
                                            Button(onClick = { openLaserOnlyCloud(File(laserPlyPath)) }) { Text("View Cloud") }
                                        }
                                    }
                                }
                                if (launchedFromEasyScanner) {
                                    Button(onClick = { finish() }) { Text("Back to Scanner") }
                                }
                            }
                            "done" -> {
                                if (launchedFromEasyScanner) {
                                    Button(onClick = { finish() }) { Text("Back to Scanner") }
                                } else {
                                    Button(onClick = {
                                        state.value = State()
                                        openViewfinder()
                                    }) { Text("New scan") }
                                }
                                Button(onClick = { train(null, true) }) { Text("Refine splat") }
                                if (s.hasMasks) {
                                    Button(onClick = { train(false) }) { Text("Retrain unmasked") }
                                }
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
                                if (launchedFromEasyScanner) {
                                    Button(onClick = { finish() }) { Text("Back") }
                                }
                            }
                            "masking", "reconstructing", "training" -> Button(onClick = {}, enabled = false) {
                                Text("Working...")
                            }
                            "error" -> {
                                if (launchedFromEasyScanner) {
                                    val retryScan = lastScan?.takeIf { it.kind() != ScanKind.LASER_ONLY }
                                    if (retryScan != null) {
                                        Button(onClick = { resumeScan(retryScan) }) { Text("Retry") }
                                    }
                                    Button(onClick = { finish() }) { Text("Back to Scanner") }
                                } else {
                                    Button(onClick = ::startCapture) { Text("Start scan") }
                                }
                            }
                            else -> {
                                Button(onClick = ::startCapture) { Text("Start scan") }
                            }
                        }
                        if (s.phase == "idle" || s.phase == "capturing") {
                            Button(onClick = ::cycleTorch) { Text(torchLabel()) }
                        }
                        if (s.phase == "idle" || s.phase == "capturing" || s.phase == "ready" ||
                            s.phase == "error" || s.phase == "done") {
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
                        onFuse = { item ->
                            showScans.value = false
                            fuseAndMesh(item.scan)
                        },
                        onView = { item ->
                            showScans.value = false
                            viewScanItem(item)
                        },
                        onSendToPlate = { stl ->
                            showScans.value = false
                            sendStlToPlate(stl.absolutePath)
                        },
                        onViewCloud = { splatName ->
                            showScans.value = false
                            openCloudView(splatName)
                        },
                        onDelete = { item ->
                            item.scan.dir.deleteRecursively()
                            loadScans()
                        },
                        onImport = { importEasyScannerSession() },
                        onLaserPreview = { item ->
                            showScans.value = false
                            previewLaser(item)
                        },
                        onDismiss = { showScans.value = false },
                    )
                }
                if (showSettings.value) {
                    SettingsDialog(
                        current = settings,
                        activeKind = if (s.phase == "idle") null else s.scanKind,
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

        if (incomingSession == null) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                openViewfinder()
            }
        }
    }

    /** Loads both native libraries up front so a missing .so fails visibly, not mid-run. */
    private fun verifyNativeLibraries() {
        dbg("onCreate")
        logNetworkDiagnostics()
        try {
            NativeCore.buildInfo()
            val caps = org.unclearness.cross3dgs.NativeBridge.queryDeviceCaps()
            Log.i("Pipeline", "parallaxcore + cross3dgs_android loaded caps=" + caps.take(180))
        } catch (error: Throwable) {
            Log.e("Pipeline", "native library load failed", error)
        }
    }

    /** Small trajectory minimap: its movement is the live proof that VIO is tracking. */
    @Composable
    private fun VioMiniMap(ui: VioUi, modifier: Modifier = Modifier) {
        val label = when {
            ui.drifted -> "VIO DRIFT"
            ui.moving -> "VIO MOVING"
            ui.ready -> "VIO TRACKING"
            ui.trail.size >= 2 -> "VIO WARM-UP"
            else -> "VIO OFF"
        }
        val tone = when {
            ui.drifted -> Color(0xFFFF6B6B)
            ui.moving -> Color(0xFFFFC857)
            ui.ready -> Color(0xFF6BE3C3)
            else -> Color(0x9CFFFFFF)
        }
        Box(modifier = modifier.size(148.dp).background(Color(0xCC080A10))) {
            Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                val pts = ui.trail
                if (pts.size >= 2) {
                    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
                    var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                    var n = 0
                    for (p in pts) {
                        if (p.size < 2 || !p[0].isFinite() || !p[1].isFinite()) continue
                        n++
                        minX = minOf(minX, p[0]); maxX = maxOf(maxX, p[0])
                        minY = minOf(minY, p[1]); maxY = maxOf(maxY, p[1])
                    }
                    if (n >= 2) {
                        val span = maxOf(maxX - minX, maxY - minY, 0.005f)
                        val scale = minOf(size.width, size.height) * 0.78f / span
                        val cx = (minX + maxX) * 0.5f
                        val cy = (minY + maxY) * 0.5f
                        fun sx(x: Float) = size.width * 0.5f + (x - cx) * scale
                        fun sy(y: Float) = size.height * 0.5f - (y - cy) * scale
                        val path = Path()
                        var started = false
                        for (p in pts) {
                            if (p.size < 2 || !p[0].isFinite() || !p[1].isFinite()) continue
                            val x = sx(p[0]); val y = sy(p[1])
                            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                        }
                        drawPath(path, tone.copy(alpha = 0.9f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        val last = pts.lastOrNull { it.size >= 2 && it[0].isFinite() && it[1].isFinite() }
                        if (last != null) {
                            drawCircle(tone, radius = 3.2.dp.toPx(), center = Offset(sx(last[0]), sy(last[1])))
                        }
                    }
                }
            }
            Text(
                text = label,
                color = tone,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            )
        }
    }

    @Composable
    private fun CapturePreview(vioState: VioUi, bannerText: String?) {
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
            if (bannerText != null) {
                Box(
                    modifier = Modifier.align(Alignment.Center)
                        .background(Color(0xCC080A10))
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Text(
                        text = bannerText,
                        color = Color.White,
                        fontSize = 18.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            VioMiniMap(vioState, Modifier.align(Alignment.BottomStart).padding(12.dp))
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
        runCatching { vio?.release() }
        vio = null
    }

    // ---------------------------------------------------------------- capture

    private fun openViewfinder() {
        val phase = state.value.phase
        if (phase != "idle" && phase.isNotEmpty()) return
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
                    refreshVioUi()
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
            refreshVioUi(true)
            // CameraProbe lists the dual-lens rig first, but rig frames from two different
            // lenses do not reconstruct as one scan here (previous scan: 2/30 registered).
            // Use a single lens; it is the same mode Parallax offers first for regular scans.
            val mode = captureModes.firstOrNull { it is CaptureMode.Single } ?: captureModes.first()
            dbg("capture mode=" + mode.label + " rig=" + (mode is CaptureMode.Rig))
            controller?.open(mode)
            if (vio == null || vio?.released == true) {
                runCatching {
                    val v = VioRunner(this)
                    v.start(controller?.cameraHandler ?: android.os.Handler(android.os.Looper.getMainLooper()))
                    vio = v
                }.onFailure { dbg("VIO start failed: " + it.message) }
            }
            controller?.vio = vio
            controller?.torchMode = torchMode
            vioBanner.value = null
            if (vio != null) {
                vio?.startTrackingSilent()
                startWarmup()
            } else {
                state.value = state.value.copy(
                    phase = "idle",
                    status = "SuperVINS unavailable",
                    detail = "Capturing without VIO poses - reconstruction will use image-only SfM",
                    hint = "Fill about half the frame, then press Start scan",
                )
            }
            refreshVioUi(true)
        }
        if (vio == null) {
            state.value = state.value.copy(
                phase = "idle",
                status = "Walk slowly around the object",
                hint = "Fill about half the frame, then press Start scan",
            )
        }
    }

    /** OFF -> AUTO -> ON -> OFF. Default is OFF so the flashlight is never enabled implicitly. */
    private fun cycleTorch() {
        // Manual torch only: a button press switches it on or off and it stays that way.
        torchMode = if (torchMode == TorchMode.ON) TorchMode.OFF else TorchMode.ON
        controller?.torchMode = torchMode
    }

    private fun torchLabel(): String = if (torchMode == TorchMode.ON) "Torch: on" else "Torch: off"

    /** Scanner-equivalent VIO control: Start VIO begins SuperVINS tracking + warm-up, Stop VIO pauses it. */
    private fun toggleVio() {
        val v = vio ?: return
        if (v.tracking) {
            v.tracking = false
            v.warmupDone = false
            stopWarmup()
            vioBanner.value = null
            state.value = state.value.copy(
                phase = "idle",
                status = "VIO stopped",
                detail = "Start VIO to record SuperVINS poses with the scan",
                hint = "Image-only SfM is used without VIO poses",
            )
        } else {
            v.startTrackingSilent()
            startWarmup()
        }
        refreshVioUi(true)
    }

    private fun startCapture() {
        if (state.value.phase == "capturing") return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (controller == null) openViewfinder()
        // SuperVINS needs its 10-frame sliding window + smooth sideways translation
        // before captured keyframes have metric VIO poses.
        if (vio != null && vio?.tracking == true && vio?.warmupDone != true) {
            pendingCaptureStart = true
            startWarmup()
            state.value = state.value.copy(
                phase = "idle",
                status = "Initialising SuperVINS",
                detail = "Move the phone smoothly sideways (~20 cm) until tracking starts",
                hint = "The scan starts by itself once SuperVINS is tracking",
            )
            return
        }
        startCaptureNow()
    }

    private fun startCaptureNow() {
        val capture = controller ?: return
        capture.startScan()
        state.value = State(phase = "capturing", status = "Walk slowly around the object")
    }

    /** SuperVINS sliding-window initialisation: fill buffer briefly, then translate smoothly sideways. */
    private fun startWarmup() {
        val v = vio ?: return
        if (v.initialized) {
            v.warmupDone = true
            if (pendingCaptureStart) {
                pendingCaptureStart = false
                startCaptureNow()
            }
            return
        }
        if (warmupActive) return
        warmupActive = true
        warmupStartMs = System.currentTimeMillis()
        v.warmupDone = false
        if (!v.tracking) v.startTrackingSilent()
        vioBanner.value = "SUPERVINS INIT\nmove smoothly sideways (~20 cm)"
        val tick = object : Runnable {
            override fun run() {
                if (!warmupActive) return
                val vv = vio ?: return
                val init = vv.initialized
                val ready = vv.initReadyToMove()
                val line = when {
                    init -> "SUPERVINS TRACKING"
                    ready -> "Move smoothly sideways (~20 cm) to initialise"
                    else -> "Filling SuperVINS window..."
                }
                val now = System.currentTimeMillis()
                if (init) {
                    vv.warmupDone = true
                    warmupActive = false
                    vioBanner.value = "SUPERVINS TRACKING\nminimap movement confirms metric tracking"
                    mainHandler.postDelayed({
                        if (!warmupActive && vio?.tracking == true) vioBanner.value = null
                    }, 3000L)
                    state.value = state.value.copy(
                        phase = "idle",
                        status = "SuperVINS tracking ready",
                        detail = "Orbit slowly around the object, then press Start scan",
                        hint = "Keyframes now carry metric SuperVINS poses",
                    )
                    if (pendingCaptureStart) {
                        pendingCaptureStart = false
                        startCaptureNow()
                    }
                    return
                }
                if (now - warmupStartMs > 25_000L) {
                    warmupActive = false
                    vv.warmupDone = true
                    vioBanner.value = "SUPERVINS NOT READY\ncapturing without VIO poses"
                    mainHandler.postDelayed({
                        if (!warmupActive) vioBanner.value = null
                    }, 4000L)
                    state.value = state.value.copy(
                        phase = "idle",
                        status = "SuperVINS did not initialise",
                        detail = "Capturing without VIO poses - use a textured scene and move smoothly",
                        hint = "Reconstruction will fall back to image-only SfM",
                    )
                    if (pendingCaptureStart) {
                        pendingCaptureStart = false
                        startCaptureNow()
                    }
                    return
                }
                vioBanner.value = "SUPERVINS INIT\n" + line.lowercase()
                state.value = state.value.copy(
                    phase = "idle",
                    status = line,
                    detail = "SuperVINS initialisation - move smoothly sideways with parallax",
                    hint = if (pendingCaptureStart) "The scan begins automatically once tracking starts"
                           else "Press Start scan once tracking is green",
                )
                mainHandler.postDelayed(this, 100L)
            }
        }
        warmupTicker = tick
        mainHandler.postDelayed(tick, 100L)
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
                // Capture is done: release Camera2 + SuperVINS/QNN immediately before
                // U2Net masking, Parallax reconstruction, and Cross3DGS training.
                closeCamera()
                lastScan = scan
                scanDir = scan.dir
                maskPromptPoint = null
                maskFirstFrame = null
                maskPoints.value = emptyList(); maskNegatives.value = emptyList()
                state.value = state.value.copy(
                    phase = "maskPrompt",
                    scanKind = scan.kind(),
                    progress = 0f,
                    status = "Auto mask the object, then tap parts to adjust",
                    detail = "Auto mask segments the first keyframe on the NPU; taps toggle the " +
                        "parts it kept. For a whole room press Skip masks",
                    ply = null,
                    fusedStl = null,
                    fusedCloudSplat = null,
                    laserOnlyPly = null,
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

    /** The U2Net mask configuration chosen in the settings sheet. */
    private fun u2netConfig() = NpuMasker.Config(
        tta = settings.maskTta,
        twoPass = settings.maskTwoPass,
        cropMargin = settings.maskCropMargin,
        threshold = settings.maskThreshold,
        guidedRadius = settings.maskGuidedRadius,
        closePx = settings.maskClosePx,
    )

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
            val segmented = NpuMasker.u2netSegmentFirstFrame(this@PipelineActivity, scan, logger, u2netConfig())
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
                seedMask = seedMask.value,
                config = u2netConfig(),
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
        val kind: ScanKind,
        val frames: Int,
        val masks: Int,
        val reconstructed: Boolean,
        val splat: File?,
        val fusedStl: File?,
        val fusedCloudSplat: File?,
        val laserOnlyPly: File?,
        val hybrid: Boolean,
        val hasVioPoses: Boolean,
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
                    val scan = Scan(d)
                    val frames = File(d, "images").listFiles { f -> f.isFile && f.name.endsWith(".jpg", true) }?.size ?: 0
                    val hasLaser = scan.hasLaserData()
                    if (frames == 0 && !hasLaser) return@mapNotNull null
                    val kind = scan.kind()
                    val masks = File(d, "masks").listFiles { f -> f.isFile && f.name.endsWith(".png", true) }?.size ?: 0
                    val reconstructed = File(d, "sparse/0/images.txt").isFile
                    val hasVio = scan.load()?.images?.any { vp ->
                        vp.vioPose != null && Da3Dense.parsePose16(vp.vioPose) != null
                    } == true
                    // View is the trained Gaussian splat; exclude laser/fused point clouds & meshes.
                    val exportsDir = File(d, "exports")
                    val splat = File(exportsDir, "splat.ply").takeIf { it.isFile && it.length() > 0L }
                        ?: exportsDir.listFiles { f ->
                            f.isFile && f.extension == "ply" &&
                                !f.name.startsWith("laser-") && !f.name.startsWith("fused-")
                        }?.maxByOrNull { it.lastModified() }
                    val fusedStl = File(exportsDir, "fused-mesh.stl").takeIf { it.isFile && it.length() > 84L }
                        ?: File(exportsDir, "laser-only.stl").takeIf { it.isFile && it.length() > 84L }
                    val fusedCloudSplat = File(filesDir, "splats/fused-${d.name}.splat")
                        .takeIf { it.isFile && it.length() > 0L }
                    val laserOnlyPly = File(exportsDir, "laser-ba-refined.ply")
                        .takeIf { it.isFile && it.length() > 0L }
                        ?: File(exportsDir, "laser-only.ply").takeIf { it.isFile && it.length() > 0L }
                    ScanItem(
                        scan = scan,
                        kind = kind,
                        frames = frames,
                        masks = masks,
                        reconstructed = reconstructed,
                        splat = splat,
                        fusedStl = fusedStl,
                        fusedCloudSplat = fusedCloudSplat,
                        laserOnlyPly = laserOnlyPly,
                        hybrid = hasLaser && File(d, "hybrid/hybrid.json").isFile,
                        hasVioPoses = hasVio,
                        bytes = d.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                        modified = d.lastModified(),
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

    /** Reopens a scan at the masking step (or straight to laser exports for laser-only scans). */
    private fun resumeScan(scan: Scan) {
        val kind = scan.kind()
        if (kind == ScanKind.LASER_ONLY) {
            openScanForTrain(scan)
            return
        }
        closeCamera()
        lastScan = scan
        scanDir = scan.dir
        maskPromptPoint = null
        maskFirstFrame = null
        maskPoints.value = emptyList(); maskNegatives.value = emptyList()
        state.value = state.value.copy(
            phase = "maskPrompt",
            scanKind = kind,
            progress = 0f,
            status = "Auto mask the object, then tap parts to adjust",
            detail = "Resuming ${kind.badge.lowercase()} scan ${scan.name} - press Auto mask or Skip masks",
            ply = null,
            fusedStl = null,
            fusedCloudSplat = null,
            laserOnlyPly = null,
        )
        scope.launch(Dispatchers.IO) {
            val file = firstKeyframe(scan)
            val bitmap = file?.let { loadPreviewBitmap(it) }
            withContext(Dispatchers.Main) { maskFirstFrame = bitmap }
        }
    }

    /** Releases the camera; the next scan reopens it. */
    private fun closeCamera() {
        stopWarmup()
        controller?.vio = null
        controller?.close()
        controller = null
        // Release the VIO too: a second VIORunner in the same process means a second
        // QNN/SuperPoint context, which is what crashed the capture earlier.
        runCatching { vio?.release() }
        vio = null
        vioUi.value = VioUi()
        previewImage = null
        live = LiveState()
    }

    private fun stopWarmup() {
        warmupActive = false
        warmupTicker?.let { mainHandler.removeCallbacks(it) }
        warmupTicker = null
        pendingCaptureStart = false
    }

    private fun train(useMasksOverride: Boolean? = null, warmStart: Boolean = false) {
        val dir = scanDir ?: return
        closeCamera()
        val exports = File(dir, "exports").apply { mkdirs() }
        val useMasks = useMasksOverride ?: settings.useMasks
        val steps = settings.trainSteps
        state.value = state.value.copy(
            phase = "training",
            progress = 0f,
            status = "Training (Cross3DGS)",
            detail = "Preparing Cross3DGS initialization…",
        )
        scope.launch(Dispatchers.Default) {
            val initTarget = File(dir, "init.ply")
            var cross3dgsInit = ""
            if (warmStart) {
                val nativeChk = File(exports, "checkpoint.d3gs").takeIf { it.isFile && Cross3dgsCheckpoint.readCount(it) > 0 }
                if (nativeChk != null) {
                    cross3dgsInit = nativeChk.absolutePath
                    dbg("train init=warm start native checkpoint " + nativeChk.length() + " bytes")
                } else {
                    val source = state.value.ply?.let { File(it) }?.takeIf {
                        it.isFile && !it.name.startsWith("laser-") && !it.name.startsWith("fused-")
                    }
                        ?: exports.listFiles { f ->
                            f.isFile && f.extension == "ply" &&
                                !f.name.startsWith("laser-") && !f.name.startsWith("fused-")
                        }?.maxByOrNull { it.lastModified() }
                        ?: File(filesDir, "splats").listFiles { f ->
                            f.isFile && f.extension == "ply" &&
                                !f.name.startsWith("laser-") && !f.name.startsWith("fused-")
                        }?.maxByOrNull { it.lastModified() }
                    val warmChk = File(dir, "warm_init.d3gs")
                    if (source != null && Cross3dgsCheckpoint.fromPly(source, warmChk)) {
                        cross3dgsInit = warmChk.absolutePath
                        dbg("train init=warm start from " + source.name + " -> " + warmChk.length() + " bytes")
                    } else {
                        dbg("train init=warm start requested but no valid splat found; using COLMAP sparse points")
                    }
                }
            } else {
                val scanForInit = Scan(dir)
                val poseSource = scanForInit.reportJson()?.optString("poseSource", "") ?: ""
                val laserPly = LaserInit.build(scanForInit, initTarget)
                val checkpoint = File(dir, "laser_init.chk")
                val laserUsed = laserPly && Cross3dgsCheckpoint.fromPly(initTarget, checkpoint)
                Log.i("Pipeline", "cross3dgs init laserPly=" + laserPly + " converted=" + laserUsed +
                    " checkpoint=" + checkpoint.absolutePath + " bytes=" + checkpoint.length())
                if (laserUsed) {
                    cross3dgsInit = checkpoint.absolutePath
                    dbg("train init=laser checkpoint " + checkpoint.length() + " bytes (poseSource=" + poseSource + ")")
                } else {
                    dbg("train init=COLMAP sparse points (poseSource=" + poseSource + ")")
                }
            }
            dbg("train steps=" + steps + " res=" + settings.maxResolution + " useMasks=" + useMasks +
                " sh=" + settings.shDegree + " strategy=" + settings.densityStrategy)
            runCatching {
                val scan = Scan(dir)
                val loaded = scan.load()
                Log.i("Pipeline", "train dir=" + dir + " images=" + (loaded?.images?.size ?: -1) +
                    " vioPoses=" + (loaded?.images?.count { it.vioPose != null } ?: -1))
                Log.i("Pipeline", "report=" + scan.reportJson())
                for (name in listOf("scan.json", "dense.ply", "sparse.ply", "init.ply",
                        "sparse/0/cameras.txt", "sparse/0/images.txt", "sparse/0/points3D.txt")) {
                    val f = File(dir, name)
                    Log.i("Pipeline", "dataset " + name + " exists=" + f.isFile + " bytes=" + f.length())
                }
            }.onFailure { Log.w("Pipeline", "train diagnostics failed", it) }
            Log.i("Pipeline", "cross3dgs init string='" + cross3dgsInit + "'")
            val result = Cross3dgsTrainer.train(
                dir, exports, cross3dgsInit, settings, useMasks,
            ) { iter, total, loss, elapsedSec ->
                if (iter % 100 == 0 || iter == total) {
                    Log.i("Pipeline", String.format(java.util.Locale.US, "cross3dgs iter=%d/%d l1=%.4f t=%.1fs", iter, total, loss, elapsedSec))
                }
                val fraction = (iter.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
                state.value = state.value.copy(
                    progress = fraction,
                    detail = String.format(java.util.Locale.US, "Cross3DGS: %d / %d · L1 %.4f (%.0fs)", iter, total, loss, elapsedSec),
                )
                true
            }
            Log.i("Pipeline", "train finished: exit=" + result.exitCode + " " + result.message)
            if (result.exitCode != 0) {
                state.value = state.value.copy(
                    phase = "error",
                    status = "Training failed (code " + result.exitCode + ")",
                    detail = result.message,
                )
                return@launch
            }
            val ply = File(exports, "splat.ply").takeIf { it.isFile }
                ?: exports.listFiles { f ->
                    f.isFile && f.extension == "ply" &&
                        !f.name.startsWith("laser-") && !f.name.startsWith("fused-")
                }
                    ?.sortedBy { it.lastModified() }
                    ?.lastOrNull()
            if (ply == null) {
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
            state.value = state.value.copy(
                phase = "done",
                progress = 1f,
                status = "Trained (Cross3DGS)",
                detail = "Exported " + target.name,
                ply = target.absolutePath,
                hasMasks = useMasks && scanHasMasks(dir),
            )
        }
    }

    private fun mb(bytes: Long): String = String.format("%.0f MB", bytes / 1048576.0)

    /** A laser-preview PLY belongs in CloudView; a trained Gaussian PLY belongs in Aholo. */
    private fun viewScanItem(item: ScanItem) {
        val ply = item.splat ?: return
        closeCamera()
        if (ply.name.startsWith("laser-preview")) {
            val name = "laser-preview-" + item.scan.name + ".splat"
            val dir = File(filesDir, "splats").apply { mkdirs() }
            val target = File(dir, name)
            if (!target.isFile) {
                val n = runCatching {
                    com.rawmechatronics.easyscanner.SplatExporter.convert(ply, target)
                }.getOrDefault(0)
                if (n <= 0) {
                    state.value = state.value.copy(
                        status = "Laser preview failed",
                        detail = "Could not convert " + ply.name + " for CloudView",
                    )
                    return
                }
            }
            val intent = Intent().setClassName(
                packageName, "com.tomppi.enderslicer.easyscan.LaserCloudViewerActivity",
            ).putExtra("file", name)
            runCatching { startActivity(intent) }.onFailure { e ->
                state.value = state.value.copy(
                    status = "Laser preview failed",
                    detail = "Could not open CloudView: " + (e.message ?: e.javaClass.simpleName),
                )
            }
            return
        }
        lastScan = item.scan
        scanDir = item.scan.dir
        state.value = state.value.copy(
            phase = "done",
            scanKind = item.kind,
            progress = 1f,
            status = "Trained",
            detail = "Exported " + ply.name,
            ply = ply.absolutePath,
            hasMasks = item.masks > 0,
        )
    }

    /** Coarse depth pass: DA3 on the Hexagon HTP, placed by recorded VIO pose and scaled with laser. */
    private fun runDa3(item: ScanItem, firstOnly: Boolean = false) {
        closeCamera()
        state.value = state.value.copy(
            phase = "training",
            progress = 0f,
            status = if (firstOnly) "DA3 1-frame patch (NPU)" else "DA3 depth (NPU)",
            detail = "Preparing " + (if (firstOnly) "1 frame" else item.frames.toString() + " keyframes"),
        )
        Thread {
            val result = runCatching {
                Da3Dense.densifyDetailed(
                    this@PipelineActivity,
                    item.scan,
                    { progress, detail ->
                        runOnUiThread {
                            state.value = state.value.copy(progress = progress, detail = detail)
                        }
                    },
                    { message -> Log.i("Pipeline", "da3: " + message) },
                    maxFrames = if (firstOnly) 1 else 0,
                    preferVio = true,
                    preferBoard = false,
                )
            }
            runOnUiThread {
                result.onSuccess { res ->
                    val ply = if (firstOnly && res.overlayFile != null && res.overlayFile.isFile) res.overlayFile
                    else res.outFile
                    val name = (if (firstOnly) "da3-first-" else "da3-") + item.scan.name + ".splat"
                    val target = File(File(filesDir, "splats").apply { mkdirs() }, name)
                    val converted = runCatching {
                        com.rawmechatronics.easyscanner.SplatExporter.convert(ply, target, 0f)
                    }.getOrDefault(0)
                    val pm = res.placement
                    val detailParts = ArrayList<String>(6)
                    detailParts.add(res.poseSource.name)
                    detailParts.add(res.points.toString() + " pts")
                    if (pm != null && pm.meshFaces > 0) detailParts.add(pm.meshFaces.toString() + " tris")
                    if (pm != null && pm.refinedBlocks > 0) detailParts.add(pm.refinedBlocks.toString() + " fine")
                    if (pm != null && pm.preRefineMedianErrMm.isFinite()) {
                        detailParts.add(String.format(java.util.Locale.US, "laser %.1fmm", pm.preRefineMedianErrMm))
                    }
                    if (res.motionMode.mode != "unknown") detailParts.add(res.motionMode.mode)
                    if (converted > 0) detailParts.add("opening CloudView")
                    state.value = state.value.copy(
                        phase = "ready",
                        progress = 1f,
                        status = "DA3 depth ready (" + res.poseSource.wire + ")",
                        detail = detailParts.joinToString(" · "),
                    )
                    if (converted > 0) {
                        val intent = Intent().setClassName(
                            packageName, "com.tomppi.enderslicer.easyscan.LaserCloudViewerActivity",
                        ).putExtra("file", name)
                        runCatching { startActivity(intent) }.onFailure { e ->
                            state.value = state.value.copy(
                                status = "DA3 depth ready",
                                detail = "Could not open CloudView: " + (e.message ?: e.javaClass.simpleName),
                            )
                        }
                    }
                }.onFailure { error ->
                    Log.e("Pipeline", "da3 failed", error)
                    state.value = state.value.copy(
                        phase = "ready",
                        status = "DA3 depth failed",
                        detail = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }.start()
    }

    /** Phase 2 preview: colour the imported laser points from the keyframes and show them as splats. */
    private fun previewLaser(item: ScanItem) {
        closeCamera()
        state.value = state.value.copy(
            status = "Building laser preview…",
            detail = "Colouring laser points from " + item.frames + " keyframes",
        )
        Thread {
            val name = "laser-preview-" + item.scan.name + ".splat"
            val dir = File(filesDir, "splats").apply { mkdirs() }
            val splat = runCatching { HybridPreview.build(item.scan, File(dir, name)) }.getOrNull()
            runOnUiThread {
                if (splat == null) {
                    state.value = state.value.copy(
                        status = "Laser preview failed",
                        detail = "No laser points in this scan's hybrid sidecar",
                    )
                    return@runOnUiThread
                }
                // Open the app's own EasyScanner CloudView directly. It is the same
                // activity the Splat Library uses for .splat files; no Aholo fallback.
                val intent = Intent().setClassName(
                    packageName, "com.tomppi.enderslicer.easyscan.LaserCloudViewerActivity",
                ).putExtra("file", name)
                runCatching { startActivity(intent) }.onFailure { e ->
                    state.value = state.value.copy(
                        status = "Laser preview failed",
                        detail = "Could not open CloudView: " + (e.message ?: e.javaClass.simpleName),
                    )
                }
            }
        }.start()
    }

    /** Bring the newest EasyScanner photo session into the splat scan library. */
    private fun importEasyScannerSession() {
        val root = java.io.File(getExternalFilesDir(null), "sessions")
        val src = root.listFiles { f ->
            f.isDirectory && (
                java.io.File(f, "splat_session.json").exists() ||
                    java.io.File(f, "frames.csv").exists() ||
                    java.io.File(f, "session.json").exists()
                )
        }?.maxByOrNull { it.lastModified() }
        if (src == null) {
            state.value = state.value.copy(status = "No EasyScanner session to import")
            return
        }
        importEasyScannerSessionDir(src, autoOpen = false)
    }

    private fun importEasyScannerSessionDir(src: File, autoOpen: Boolean) {
        Thread {
            val r = runCatching { EasyScanImport.import(this, src) }
            runOnUiThread {
                r.onSuccess {
                    loadScans()
                    if (autoOpen) {
                        when (it.kind) {
                            ScanKind.SPLAT_ONLY -> {
                                resumeScan(it.scan)
                                val resTag = if (it.imageWidth > 0 && it.imageHeight > 0) " · ${it.imageWidth}×${it.imageHeight}" else ""
                                state.value = state.value.copy(
                                    scanKind = ScanKind.SPLAT_ONLY,
                                    status = "High-res Splat ready (${it.images} photos$resTag)",
                                    detail = "Auto mask object or press Skip masks to reconstruct & train Cross3DGS",
                                )
                            }
                            ScanKind.FUSED -> {
                                resumeScan(it.scan)
                                state.value = state.value.copy(
                                    scanKind = ScanKind.FUSED,
                                    status = "Fusion session ready (${it.images} frames, ${it.laserFrames} laser)",
                                    detail = "Auto mask object or press Skip masks to run BA + Dense MVS + Laser Fusion",
                                )
                            }
                            ScanKind.LASER_ONLY -> {
                                openScanForTrain(it.scan)
                                state.value = state.value.copy(
                                    scanKind = ScanKind.LASER_ONLY,
                                    status = "Laser-only scan ready (${it.laserFrames} sweeps)",
                                    detail = "View laser cloud or send STL mesh to the build plate",
                                )
                            }
                        }
                    } else {
                        val kind = when (it.kind) {
                            ScanKind.SPLAT_ONLY -> "high-res Splat"
                            ScanKind.FUSED -> "${it.laserFrames} laser frames"
                            ScanKind.LASER_ONLY -> "laser-only (${it.laserFrames} sweeps)"
                        }
                        state.value = state.value.copy(
                            status = "Imported ${it.scan.name} (${it.images} photos, $kind)",
                        )
                    }
                }.onFailure {
                    state.value = state.value.copy(
                        phase = if (autoOpen) "error" else state.value.phase,
                        status = "Import failed: " + (it.message ?: it.javaClass.simpleName),
                    )
                }
            }
        }.start()
    }

    /**
     * Post-processing step: tightens laser frames with BA poses, locks metric scale from the
     * laser plane, filters dense MVS by U2Net masks, fuses both in the native C++ TSDF, and
     * exports a metric binary STL ready for TrioSlicer's build plate + a CloudView preview.
     */
    private fun fuseAndMesh(scan: Scan) {
        if (scan.kind() != ScanKind.FUSED) {
            state.value = state.value.copy(
                phase = "ready",
                scanKind = scan.kind(),
                status = "Fusion unavailable for ${scan.kind().badge} scan",
                detail = "No laser sweep data was captured in ${scan.name}",
            )
            return
        }
        closeCamera()
        lastScan = scan
        scanDir = scan.dir
        state.value = state.value.copy(
            phase = "training",
            scanKind = ScanKind.FUSED,
            progress = 0f,
            status = "Fusing Laser + Dense MVS…",
            detail = "Preparing bundle-adjusted fusion for ${scan.name}",
            fusedStl = null,
            fusedCloudSplat = null,
        )
        scope.launch(Dispatchers.Default) {
            val res = runCatching {
                LaserMvsFusion.fuse(
                    scan = scan,
                    splatsDir = File(filesDir, "splats"),
                    preferredVoxelMm = settings.fusionVoxelMm,
                ) { fraction, msg ->
                    state.value = state.value.copy(progress = fraction, detail = msg)
                }
            }
            withContext(Dispatchers.Main) {
                res.onSuccess { out ->
                    val parts = ArrayList<String>(7)
                    if (out.triangles > 0) parts += "${out.triangles} tris (${String.format(java.util.Locale.US, "%.2g", out.voxelMm)} mm)"
                    if (out.laserPoints > 0) parts += "${out.laserPoints} laser pts"
                    if (out.vioAnchoredFrames > 0) parts += "${out.vioAnchoredFrames} VIO-anchored"
                    parts += "${out.mvsPoints} MVS pts"
                    if (out.laserPoints > 0) {
                        parts += String.format(java.util.Locale.US, "scale %.3fx", out.laserScale)
                    }
                    if (out.maskFiltered) parts += "masked"
                    val stlPath = out.stlFile.takeIf { it.isFile && it.length() > 84L && out.triangles > 0 }?.absolutePath
                    val splatName = out.previewSplatFile?.takeIf { it.isFile }?.name
                    val laserPlyPath = out.laserRefinedPlyFile?.takeIf { it.isFile && it.length() > 0L }?.absolutePath
                    state.value = state.value.copy(
                        phase = "ready",
                        progress = 1f,
                        status = if (stlPath != null) "Fused STL mesh ready" else "Fused cloud ready",
                        detail = parts.joinToString(" · "),
                        fusedStl = stlPath,
                        fusedCloudSplat = splatName,
                        laserOnlyPly = laserPlyPath,
                    )
                    loadScans()
                }.onFailure { err ->
                    Log.e("Pipeline", "Laser+MVS fusion failed", err)
                    state.value = state.value.copy(
                        phase = "ready",
                        progress = 0f,
                        status = "Fusion failed",
                        detail = err.message ?: err.javaClass.simpleName,
                    )
                }
            }
        }
    }

    private fun sendStlToPlate(stlPath: String) {
        val intent = Intent().setClassName(packageName, "com.tomppi.enderslicer.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_IMPORT_STL_PATH, stlPath)
        runCatching {
            startActivity(intent)
            finish()
        }.onFailure { e ->
            state.value = state.value.copy(
                status = "Could not open plate",
                detail = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    private fun openCloudView(splatName: String) {
        val intent = Intent().setClassName(
            packageName, "com.tomppi.enderslicer.easyscan.LaserCloudViewerActivity",
        ).putExtra("file", splatName)
        runCatching { startActivity(intent) }.onFailure { e ->
            state.value = state.value.copy(
                status = "Could not open CloudView",
                detail = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    /** Converts (if needed) and opens the BA-tightened laser-only point cloud in CloudView. */
    private fun openLaserOnlyCloud(laserPly: File) {
        if (!laserPly.isFile || laserPly.length() <= 0L) return
        val scanName = laserPly.parentFile?.parentFile?.name ?: "scan"
        val splatsDir = File(filesDir, "splats").apply { mkdirs() }
        val splatFile = File(splatsDir, "laser-ba-$scanName.splat")
        if (splatFile.isFile && splatFile.length() > 0L && splatFile.lastModified() >= laserPly.lastModified()) {
            openCloudView(splatFile.name)
            return
        }
        state.value = state.value.copy(detail = "Preparing laser-only cloud…")
        scope.launch(Dispatchers.IO) {
            val count = runCatching {
                com.rawmechatronics.easyscanner.SplatExporter.convert(laserPly, splatFile, voxelMm = 0f)
            }.getOrDefault(0)
            withContext(Dispatchers.Main) {
                if (count > 0) {
                    openCloudView(splatFile.name)
                } else {
                    state.value = state.value.copy(
                        status = "Laser cloud view failed",
                        detail = "Could not convert ${laserPly.name}",
                    )
                }
            }
        }
    }

    private fun scanSummary(item: ScanItem): String {
        val parts = ArrayList<String>(6)
        parts.add("[${item.kind.badge}]")
        if (item.frames > 0) parts.add("${item.frames} frames")
        if (item.masks > 0) parts.add("${item.masks} masks")
        if (item.hasVioPoses) parts.add("VIO")
        if (item.kind != ScanKind.LASER_ONLY) {
            parts.add(if (item.reconstructed) "reconstructed" else "not reconstructed")
        }
        parts.add(mb(item.bytes))
        return parts.joinToString(" · ")
    }

    /** Loads an existing scan into the ready state with only its mode-relevant outputs. */
    private fun openScanForTrain(scan: Scan) {
        closeCamera()
        lastScan = scan
        scanDir = scan.dir
        maskPromptPoint = null
        val kind = scan.kind()
        val pose = scan.reportJson()?.optString("poseSource", "")?.takeIf { it.isNotBlank() }
        val exportsDir = File(scan.dir, "exports")
        val existingSplat = if (kind == ScanKind.SPLAT_ONLY) {
            File(exportsDir, "splat.ply").takeIf { it.isFile && it.length() > 0L }?.absolutePath
                ?: File(filesDir, "splats/${scan.name}-splat.ply").takeIf { it.isFile && it.length() > 0L }?.absolutePath
        } else null
        val existingStl = if (kind != ScanKind.SPLAT_ONLY) {
            File(exportsDir, "fused-mesh.stl").takeIf { it.isFile && it.length() > 84L }?.absolutePath
                ?: File(exportsDir, "laser-only.stl").takeIf { it.isFile && it.length() > 84L }?.absolutePath
        } else null
        val existingCloud = if (kind == ScanKind.FUSED) {
            File(filesDir, "splats/fused-${scan.name}.splat").takeIf { it.isFile && it.length() > 0L }?.name
        } else null
        val existingLaser = if (kind != ScanKind.SPLAT_ONLY) {
            File(exportsDir, "laser-ba-refined.ply").takeIf { it.isFile && it.length() > 0L }?.absolutePath
                ?: File(exportsDir, "laser-only.ply").takeIf { it.isFile && it.length() > 0L }?.absolutePath
        } else null
        val readyStatus = when (kind) {
            ScanKind.LASER_ONLY -> "Laser-only scan"
            else -> if (pose != null) "Reconstructed · pose $pose" else "Reconstructed"
        }
        val readyDetail = when (kind) {
            ScanKind.SPLAT_ONLY -> "Ready to train Cross3DGS on ${scan.name}"
            ScanKind.FUSED -> "Ready to fuse Laser + MVS (STL) on ${scan.name}"
            ScanKind.LASER_ONLY -> "Laser cloud / STL exports for ${scan.name}"
        }
        state.value = state.value.copy(
            phase = "ready",
            scanKind = kind,
            progress = 1f,
            status = readyStatus,
            detail = readyDetail,
            ply = existingSplat,
            fusedStl = existingStl,
            fusedCloudSplat = existingCloud,
            laserOnlyPly = existingLaser,
        )
    }

    @Composable
    private fun ScansDialog(
        items: List<ScanItem>,
        loading: Boolean,
        freeBytes: Long,
        onOpen: (ScanItem) -> Unit,
        onTrain: (ScanItem) -> Unit,
        onFuse: (ScanItem) -> Unit,
        onView: (ScanItem) -> Unit,
        onSendToPlate: (File) -> Unit,
        onViewCloud: (String) -> Unit,
        onDelete: (ScanItem) -> Unit,
        onImport: () -> Unit,
        onLaserPreview: (ScanItem) -> Unit,
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
                    TextButton(onClick = onImport) { Text("Import EasyScanner scan") }
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(items) { item ->
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(item.scan.name)
                                Text(scanSummary(item), style = MaterialTheme.typography.bodySmall)
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    when (item.kind) {
                                        ScanKind.SPLAT_ONLY -> {
                                            TextButton(onClick = { onOpen(item) }) { Text("Open") }
                                            if (item.reconstructed) {
                                                TextButton(onClick = { onTrain(item) }) { Text("Train Splat") }
                                            }
                                            if (item.splat != null) {
                                                TextButton(onClick = { onView(item) }) { Text("View Splat") }
                                            }
                                        }
                                        ScanKind.FUSED -> {
                                            TextButton(onClick = { onOpen(item) }) { Text("Open") }
                                            if (item.reconstructed) {
                                                TextButton(onClick = { onFuse(item) }) { Text("Fuse & Mesh") }
                                            }
                                            item.fusedStl?.let { stl ->
                                                TextButton(onClick = { onSendToPlate(stl) }) { Text("Send to Plate") }
                                            }
                                            item.fusedCloudSplat?.let { cloud ->
                                                TextButton(onClick = { onViewCloud(cloud.name) }) { Text("View Cloud") }
                                            }
                                            if (item.laserOnlyPly != null) {
                                                TextButton(onClick = {
                                                    onDismiss()
                                                    openLaserOnlyCloud(item.laserOnlyPly)
                                                }) { Text("Laser only") }
                                            } else if (item.hybrid) {
                                                TextButton(onClick = { onLaserPreview(item) }) { Text("Laser preview") }
                                            }
                                        }
                                        ScanKind.LASER_ONLY -> {
                                            item.fusedStl?.let { stl ->
                                                TextButton(onClick = { onSendToPlate(stl) }) { Text("Send to Plate") }
                                            }
                                            if (item.laserOnlyPly != null) {
                                                TextButton(onClick = {
                                                    onDismiss()
                                                    openLaserOnlyCloud(item.laserOnlyPly)
                                                }) { Text("View Cloud") }
                                            } else if (item.hybrid) {
                                                TextButton(onClick = { onLaserPreview(item) }) { Text("Laser preview") }
                                            }
                                        }
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
        activeKind: ScanKind?,
        onSave: (PipelineSettings) -> Unit,
        onDismiss: () -> Unit,
    ) {
        var s by remember { mutableStateOf(current) }
        val showFusion = activeKind == null || activeKind == ScanKind.FUSED
        val showSplat = activeKind == null || activeKind == ScanKind.SPLAT_ONLY
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Settings") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (activeKind != ScanKind.LASER_ONLY) {
                        Text("Masking (U2Net NPU)", style = MaterialTheme.typography.titleSmall)
                        SettingsToggle("Flip TTA", s.maskTta) { v -> s = s.copy(maskTta = v) }
                        SettingsToggle("Two-pass crop", s.maskTwoPass) { v -> s = s.copy(maskTwoPass = v) }
                        SettingsNumber("Crop margin", s.maskCropMargin) { v ->
                            s = s.copy(maskCropMargin = v.coerceIn(1f, 3f))
                        }
                        SettingsNumber("Mask threshold", s.maskThreshold) { v ->
                            s = s.copy(maskThreshold = v.coerceIn(0.2f, 0.9f))
                        }
                        SettingsNumber("Guided radius", s.maskGuidedRadius.toFloat()) { v ->
                            s = s.copy(maskGuidedRadius = v.toInt().coerceIn(1, 20))
                        }
                        SettingsNumber("Close px", s.maskClosePx.toFloat()) { v ->
                            s = s.copy(maskClosePx = v.toInt().coerceIn(0, 9))
                        }
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
                    }

                    if (showFusion) {
                        Text("Laser + MVS Fusion (STL)", style = MaterialTheme.typography.titleSmall)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for (preset in listOf(1.0f, 1.5f, 2.0f)) {
                                val selected = kotlin.math.abs(s.fusionVoxelMm - preset) < 0.05f
                                if (selected) {
                                    Button(
                                        onClick = { s = s.copy(fusionVoxelMm = preset) },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("${preset}mm") }
                                } else {
                                    TextButton(
                                        onClick = { s = s.copy(fusionVoxelMm = preset) },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("${preset}mm") }
                                }
                            }
                        }
                        SettingsNumber("Fusion voxel (mm)", s.fusionVoxelMm) { v ->
                            s = s.copy(fusionVoxelMm = v.coerceIn(0.75f, 6.0f))
                        }
                    }

                    if (showSplat) {
                        Text("Cross3DGS training (Vulkan)", style = MaterialTheme.typography.titleSmall)
                        SettingsNumber("Steps", s.trainSteps.toFloat()) { v ->
                            s = s.copy(trainSteps = v.toInt().coerceIn(100, 100000))
                        }
                        SettingsNumber("Max splats", s.maxSplats.toFloat()) { v ->
                            s = s.copy(maxSplats = v.toInt().coerceIn(1000, 2_000_000))
                        }
                        SettingsNumber("Max resolution", s.maxResolution.toFloat()) { v ->
                            s = s.copy(maxResolution = v.toInt().coerceIn(128, 2048))
                        }
                        SettingsNumber("SH degree (0-3)", s.shDegree.toFloat()) { v ->
                            s = s.copy(shDegree = v.toInt().coerceIn(0, 3))
                        }
                        SettingsToggle("Use U2Net masks (black bg)", s.useMasks) { v ->
                            s = s.copy(useMasks = v)
                        }
                        SettingsToggle("MCMC densification", s.densityStrategy != "fixed") { v ->
                            s = s.copy(densityStrategy = if (v) "mcmc" else "fixed")
                        }
                        SettingsNumber("SSIM weight", s.ssimWeight) { v ->
                            s = s.copy(ssimWeight = v.coerceIn(0f, 1f))
                        }
                        SettingsNumber("MCMC clone offset", s.mcmcCloneOffset) { v ->
                            s = s.copy(mcmcCloneOffset = v.coerceIn(0.05f, 2f))
                        }
                        SettingsNumber("MCMC grad sampling", s.mcmcGradientSampling) { v ->
                            s = s.copy(mcmcGradientSampling = v.coerceIn(0.1f, 5f))
                        }
                    }
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
     * Everything outside the mask is composited to black for Cross3DGS training.
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

    companion object {
        const val EXTRA_EASYSCAN_SESSION = "com.tomppi.splatproto.EASYSCAN_SESSION"
        const val EXTRA_SPLAT_ONLY = "com.tomppi.splatproto.SPLAT_ONLY"
        const val EXTRA_IMPORT_STL_PATH = "com.tomppi.enderslicer.IMPORT_STL_PATH"
        private const val MAX_FRAMES = 150
        private const val CAPTURE_INTERVAL_MS = 700L
        private const val TRAIN_STEPS = 10_000
        private const val REFINE_EVERY = 100
    }
}