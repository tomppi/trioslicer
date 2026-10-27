package com.tomppi.enderslicer.texturizer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The BumpMesh engine behind the native studio: one warm, hidden WebView driving the vendored BumpMesh page through
 * its own DOM controls, with the export streamed back through a JS bridge to a native STL file.
 *
 * The class owns the whole headless-engine lifecycle: creating and warming the hidden WebView, injecting the studio
 * driver (res/raw/bumpmesh_studio_driver.js), serving the source STL and .bumpmesh project files to the page, applying
 * the request (texture, mapping, tile size, height, smoothing, resolution, triangle cap, optional painted mask),
 * streaming the exported STL back into a cache file, mapping page progress into named phases, and cancelling/closing.
 *
 * Threading: construct it and call [warmUp]/[close] from the plate's UI context; [apply] is a suspend function that
 * runs the file work off the main thread and only touches the WebView on the main thread. Progress is delivered on the
 * main thread. There is at most one apply in flight; a second one fails with a named [StudioException].
 *
 * The one-author rule for any file in the plate belongs to the caller: this class only produces a new STL file and
 * never touches the plate, the document or the app's undo stack.
 */
class BumpMeshStudio(private val context: Context, @Suppress("unused") private val scope: CoroutineScope) {

    /**
     * One bundled texture.
     *
     * [assetPath] is relative to the app's assets and is what the engine fetches and hands the page: the canonical
     * library form is "bumpmesh/library/textures/armor.png" (the pinned 101-texture catalogue), and the legacy
     * "bumpmesh/textures/brick.png" folder remains accepted for builds without the staged library.
     * [category] and [defaultScale] come from the library manifest; [thumbnailAssetPath] is the gallery's webp thumbnail
     * and is never used as a displacement map.
     */
    data class TextureRef(
        val assetPath: String,
        val displayName: String,
        val category: String? = null,
        val defaultScale: Float? = null,
        val thumbnailAssetPath: String? = null,
    )

    /** The page's projection modes, with the numeric values its select control uses. */
    enum class MappingMode(val pageValue: Int) {
        TRIPLANAR(5),
        PLANAR_XZ(1),
        PLANAR_XY(0),
        CYLINDRICAL(3),
        SPHERICAL(4),
    }

    /**
     * One apply request.
     *
     * [sourceStl] is the selected plate object already written as a binary STL, [sourceTriangles] its triangle count.
     * [mask] is a per-triangle include mask (true = texture this face) indexed exactly like the source STL; null means
     * the whole object. [maxTriangles] is the output triangle cap the user chose and must not exceed
     * [MeshTriangleLimits.current] - the caller raises that limit first.
     */
    data class ApplyRequest(
        val sourceStl: File,
        val sourceTriangles: Int,
        val texture: TextureRef,
        val mapping: MappingMode,
        val tileMm: Float,
        val heightMm: Float,
        val smoothing: Float,
        val refineMm: Float,
        val maxTriangles: Int,
        val mask: BooleanArray?,
    )

    /** The finished textured mesh plus the page's own mesh-quality warnings. */
    data class ApplyResult(val stl: File, val triangles: Int, val repairWarnings: List<String>)

    /** A named engine failure; the message is written for the user. */
    open class StudioException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** The apply was cancelled by [cancel] (or by the caller's coroutine scope going away). */
    class CancelledException(message: String = "BumpMesh studio apply was cancelled") : CancellationException(message)

    /** A named phase timed out; its message already names the step and the expected triangle count. */
    class EngineTimeoutException(message: String, cause: Throwable? = null) : StudioException(message, cause)

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workRoot = File(appContext.cacheDir, "bumpmesh-studio")
    private val resultsRoot = File(appContext.cacheDir, "bumpmesh-studio-results")
    private val page = BumpMeshEnginePage(context)
    private val callSlot = BumpMeshCallSlot()
    private val texturesLock = Any()

    @Volatile
    private var call: ApplyCall? = null

    @Volatile
    private var closed = false

    @Volatile
    private var cachedTextures: List<TextureRef>? = null

    // The engine's current page state, so an apply that changes only texture/settings does not reload the model.
    @Volatile
    private var loadedSource: File? = null

    @Volatile
    private var loadedSourceLength = -1L

    @Volatile
    private var loadedSourceModified = -1L

    @Volatile
    private var loadedMaskKey: String? = null

    /** Creates the hidden WebView and starts the page if that has not happened yet; idempotent and cheap afterwards. */
    fun warmUp() {
        if (closed) return
        mainHandler.post {
            if (closed) return@post
            try {
                page.ensureStarted()
            } catch (error: Throwable) {
                Log.w(TAG, "BumpMesh studio warm-up failed", error)
            }
        }
    }

    /**
     * The bundled BumpMesh textures.
     *
     * The canonical catalogue is the asset pipeline's pinned library manifest (assets/bumpmesh/library/manifest.json, 101
     * entries); it is read here so the engine and the gallery cannot disagree about what the bundle contains. The
     * pre-1.9.14 vendored preset folder is the fallback for a build that has not staged the library yet, so a broken
     * bundle still gives a working studio instead of an empty gallery.
     */
    fun textures(): List<TextureRef> {
        cachedTextures?.let { return it }
        synchronized(texturesLock) {
            cachedTextures?.let { return it }
            val assets = appContext.assets
            val library = runCatching {
                assets.open(BumpMeshLibraryManifest.MANIFEST_ASSET).bufferedReader().use { reader ->
                    BumpMeshLibraryManifest.parse(reader.readText())
                }
            }.getOrNull().orEmpty()
            val refs = if (library.isNotEmpty()) {
                library.map { entry ->
                    TextureRef(
                        assetPath = entry.assetPath,
                        displayName = entry.displayName,
                        category = entry.category,
                        defaultScale = entry.defaultScale,
                        thumbnailAssetPath = entry.thumbnailAssetPath,
                    )
                }
            } else {
                val legacyNames = runCatching { assets.list(BumpMeshLibraryManifest.LEGACY_TEXTURE_PREFIX.trimEnd('/')) }
                    .getOrNull().orEmpty().toList()
                BumpMeshLibraryManifest.legacy(legacyNames).map { entry ->
                    TextureRef(assetPath = entry.assetPath, displayName = entry.displayName)
                }
            }
            cachedTextures = refs
            return refs
        }
    }

    /**
     * The headless apply.
     *
     * Validation, the mask remap and the project ZIP happen off the main thread; the WebView is only touched on the
     * main thread. [onProgress] receives the phases in [BumpMeshProgress.phases] with a monotonically increasing
     * 0..1 fraction and is invoked on the main thread. Cancellation (this class's [cancel] or the caller's scope)
     * throws [CancelledException].
     */
    suspend fun apply(request: ApplyRequest, onProgress: (phase: String, fraction: Float) -> Unit): ApplyResult {
        if (closed) throw StudioException("The BumpMesh studio is closed")
        if (!callSlot.tryBegin()) throw StudioException("A BumpMesh apply is already running")
        val activeCall = ApplyCall(request)
        call = activeCall
        val progress: (String, Float) -> Unit = { phase, fraction ->
            mainHandler.post { onProgress(phase, fraction.coerceIn(0f, 1f)) }
        }
        try {
            validateRequest(request)
            validateTriangleLimit(request)
            pruneResults()
            progress(BumpMeshProgress.LOADING_ENGINE, 0f)
            withContext(Dispatchers.Main.immediate) { page.ensureStarted() }
            try {
                withTimeout(WARMUP_TIMEOUT_MS) { page.awaitReady() }
            } catch (timeout: TimeoutCancellationException) {
                throw StudioException(BumpMeshFailureReport.readyTimeout(), timeout)
            }
            progress(BumpMeshProgress.LOADING_ENGINE, 0.05f)

            val mask = request.mask
            val projectFile: File? = if (mask != null) {
                withContext(Dispatchers.IO) {
                    try {
                        buildProject(request, mask)
                    } catch (error: IllegalArgumentException) {
                        throw StudioException(error.message ?: "The painted mask could not be prepared", error)
                    }
                }
            } else {
                null
            }
            page.currentSource = request.sourceStl
            page.currentProject = projectFile

            val sourceKey = sourceKey(request.sourceStl)
            val maskKey = if (mask != null) maskKey(sourceKey, mask) else null
            val pageAlreadyHoldsThis = loadedSource?.canonicalPath == request.sourceStl.canonicalPath &&
                loadedSourceLength == request.sourceStl.length() &&
                loadedSourceModified == request.sourceStl.lastModified() &&
                loadedMaskKey == maskKey

            if (!pageAlreadyHoldsThis) {
                progress(BumpMeshProgress.IMPORTING, 0.05f)
                page.clearConsoleWarnings()
                // The page's loader drops non-finite and zero-area triangles, so its triangle count is the cleaned
                // count, not the raw STL count. The driver waits for that exact number.
                val expectedTriangles = withContext(Dispatchers.IO) {
                    try {
                        BumpMeshMaskRemap.cleanedTriangleCount(request.sourceStl, request.sourceTriangles)
                    } catch (error: IllegalArgumentException) {
                        throw StudioException(error.message ?: "The source STL could not be read", error)
                    }
                }
                activeCall.expectedTriangles = expectedTriangles
                openSourceWithRetry(activeCall, request, mask != null, expectedTriangles, progress)
                loadedSource = request.sourceStl
                loadedSourceLength = request.sourceStl.length()
                loadedSourceModified = request.sourceStl.lastModified()
                loadedMaskKey = maskKey
            }
            progress(BumpMeshProgress.IMPORTING, 0.20f)

            val configJson = BumpMeshDriverCommand.settingsJson(
                mappingMode = request.mapping.pageValue,
                textureAssetPath = normalizedAssetPath(request.texture),
                tileMm = request.tileMm,
                heightMm = request.heightMm,
                smoothing = request.smoothing,
                refineMm = request.refineMm,
                maxTriangles = request.maxTriangles,
            )
            withContext(Dispatchers.Main.immediate) { page.evaluate(page.configureCommand(configJson)) }
            awaitEvent(activeCall, setOf(EVENT_SETTINGS_APPLIED), SETTINGS_TIMEOUT_MS, progress)
            progress(BumpMeshProgress.SUBDIVIDING, 0.20f)

            withContext(Dispatchers.Main.immediate) { page.evaluate(page.exportCommand()) }
            val export = awaitExport(activeCall, request, progress)
            val warnings = activeCall.warnings()
            return ApplyResult(export.file, export.triangles, warnings)
        } catch (cancelled: CancelledException) {
            resetAfterAbort()
            throw cancelled
        } catch (timeout: TimeoutCancellationException) {
            resetAfterAbort()
            throw StudioException(BumpMeshFailureReport.readyTimeout(), timeout)
        } catch (cancelled: CancellationException) {
            resetAfterAbort()
            throw cancelled
        } catch (error: StudioException) {
            resetAfterAbort()
            Log.w(TAG, "BumpMesh apply failed: " + (error.message ?: error.javaClass.simpleName))
            throw error
        } catch (error: Throwable) {
            resetAfterAbort()
            throw StudioException(error.message ?: "BumpMesh studio failed", error)
        } finally {
            synchronized(callSlot) {
                if (call === activeCall) call = null
            }
            activeCall.closeFile()
            callSlot.finish()
            page.currentProject = null
        }
    }

    /**
     * Opens the model (whole object) or the .bumpmesh project (painted mask) on the hidden page.
     *
     * If the page produces no event at all (a renderer that is not running our code), the hidden WebView is built
     * fresh once and the import retried; if the fresh page is silent too, the timeout sentence names the step and the
     * triangle count instead of hanging.
     */
    private suspend fun openSourceWithRetry(
        activeCall: ApplyCall,
        request: ApplyRequest,
        masked: Boolean,
        expectedTriangles: Int,
        progress: (String, Float) -> Unit,
    ) {
        var attempt = 1
        while (true) {
            withContext(Dispatchers.Main.immediate) {
                val command = if (masked) {
                    page.openProjectCommand(expectedTriangles)
                } else {
                    page.openModelCommand(expectedTriangles, request.sourceStl.name)
                }
                page.evaluate(command)
            }
            try {
                awaitEvent(activeCall, setOf(EVENT_MODEL_LOADED, EVENT_PROJECT_LOADED), IMPORT_SILENCE_TIMEOUT_MS, progress)
                return
            } catch (stalled: EngineTimeoutException) {
                if (attempt >= 2) throw stalled
                attempt++
                withContext(Dispatchers.Main.immediate) { page.recycleForRetry() }
                try {
                    withTimeout(WARMUP_TIMEOUT_MS) { page.awaitReady() }
                } catch (timeout: TimeoutCancellationException) {
                    throw StudioException(BumpMeshFailureReport.readyTimeout(), timeout)
                }
            }
        }
    }

    /** Cancels the running apply. Safe when nothing is running. */
    fun cancel() {
        val active = call ?: return
        if (!callSlot.requestCancel()) return
        active.events.trySend(StudioEvent(EVENT_CANCELLED, "", 0f, ""))
        mainHandler.post { page.abortAndReload() }
    }

    /** Stops the engine and destroys the hidden WebView. The STL files already returned stay on disk for the undo path. */
    fun close() {
        if (closed) return
        closed = true
        val active = call
        if (active != null) {
            callSlot.requestCancel()
            active.events.trySend(StudioEvent(EVENT_CANCELLED, "", 0f, ""))
        }
        mainHandler.post {
            runCatching { page.destroy() }
        }
        synchronized(texturesLock) { cachedTextures = null }
    }

    // ---- request validation and engine plumbing -------------------------------------------------

    private fun validateRequest(request: ApplyRequest) {
        if (!request.sourceStl.isFile) {
            throw StudioException("The plate object's STL is missing: " + request.sourceStl.name)
        }
        if (request.sourceTriangles <= 0) {
            throw StudioException("The plate object has no triangles to texture")
        }
        if (request.sourceStl.length() < STL_HEADER_BYTES) {
            throw StudioException("The plate object's STL is empty or unreadable")
        }
        if (request.tileMm !in MIN_TILE_MM..MAX_TILE_MM) {
            throw StudioException("The texture tile size must be between " + MIN_TILE_MM + " and " + MAX_TILE_MM + " mm")
        }
        if (request.heightMm !in MIN_HEIGHT_MM..MAX_HEIGHT_MM) {
            throw StudioException("The displacement height must be between " + MIN_HEIGHT_MM + " and " + MAX_HEIGHT_MM + " mm")
        }
        if (request.smoothing !in MIN_SMOOTHING..MAX_SMOOTHING) {
            throw StudioException("The texture smoothing must be between " + MIN_SMOOTHING + " and " + MAX_SMOOTHING)
        }
        if (request.refineMm !in MIN_REFINE_MM..MAX_REFINE_MM) {
            throw StudioException("The export resolution must be between " + MIN_REFINE_MM + " and " + MAX_REFINE_MM + " mm")
        }
        val texture = normalizedAssetPath(request.texture)
        val refusal = BumpMeshLibraryManifest.refusalFor(texture) { path ->
            runCatching { appContext.assets.open(path).close() }.isSuccess
        }
        if (refusal != null) throw StudioException(refusal)
        request.mask?.let { mask ->
            if (mask.size != request.sourceTriangles) {
                throw StudioException(
                    "The painted mask names " + mask.size + " triangles but the plate object has " +
                        request.sourceTriangles + "; the mask belongs to another mesh",
                )
            }
        }
    }

    private fun validateTriangleLimit(request: ApplyRequest) {
        val refusal = BumpMeshStudioLimits.refusalFor(
            maxTriangles = request.maxTriangles,
            refineMm = request.refineMm,
            currentLimit = MeshTriangleLimits.current(),
        )
        if (refusal != null) throw StudioException(refusal)
    }

    private fun buildProject(request: ApplyRequest, mask: BooleanArray): File {
        val remap = BumpMeshMaskRemap.remap(request.sourceStl, request.sourceTriangles, mask)
        val project = File(workRoot, "project-" + UUID.randomUUID() + ".bumpmesh")
        BumpMeshProjectFiles.buildZip(
            destination = project,
            modelFile = request.sourceStl,
            settingsJson = BumpMeshProjectFiles.settingsJson(
                mappingMode = request.mapping.pageValue,
                tileMm = request.tileMm,
                heightMm = request.heightMm,
                smoothing = request.smoothing,
                refineMm = request.refineMm,
                maxTriangles = request.maxTriangles,
            ),
            maskJson = BumpMeshProjectFiles.maskJson(remap.mask),
        )
        return project
    }

    private fun pruneResults() {
        if (!resultsRoot.isDirectory) return
        val files = resultsRoot.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".stl") }
            .sortedByDescending { it.lastModified() }
        files.drop(MAX_RETAINED_RESULTS).forEach { runCatching { it.delete() } }
    }

    private fun resetAfterAbort() {
        loadedSource = null
        loadedSourceLength = -1L
        loadedSourceModified = -1L
        loadedMaskKey = null
        page.currentProject = null
        mainHandler.post { runCatching { page.abortAndReload() } }
    }

    private fun sourceKey(file: File): String =
        file.canonicalPath + ":" + file.length() + ":" + file.lastModified()

    private fun maskKey(sourceKey: String, mask: BooleanArray): String =
        sourceKey + ":" + mask.size + ":" + mask.contentHashCode()

    private fun normalizedAssetPath(texture: TextureRef): String =
        texture.assetPath.trimStart('/').replace("//", "/")

    private suspend fun awaitEvent(
        activeCall: ApplyCall,
        expected: Set<String>,
        timeoutMillis: Long,
        progress: (String, Float) -> Unit,
    ): StudioEvent {
        try {
            return withTimeout(timeoutMillis) {
                while (true) {
                    val event = activeCall.events.receive()
                    when {
                        event.kind in expected -> return@withTimeout event
                        event.kind == EVENT_PAGE_PROGRESS -> {
                            val (phase, fraction) = BumpMeshProgress.phaseForPageFraction(event.fraction)
                            progress(phase, fraction)
                        }
                        event.kind == EVENT_WARNING -> activeCall.addWarning(event.message)
                        event.kind == EVENT_ERROR -> throw StudioException(event.message.ifBlank { "BumpMesh failed" })
                        event.kind == EVENT_CANCELLED -> throw CancelledException()
                        else -> Unit
                    }
                }
                error("unreachable")
            }
        } catch (timeout: TimeoutCancellationException) {
            val stepName = when {
                expected.contains(EVENT_MODEL_LOADED) -> "the model load"
                expected.contains(EVENT_PROJECT_LOADED) -> "the project import"
                expected.contains(EVENT_SETTINGS_APPLIED) -> "the texture settings"
                else -> expected.joinToString("/")
            }
            throw EngineTimeoutException(
                BumpMeshFailureReport.importTimeout(step = stepName, expectedTriangleCount = activeCall.expectedTriangles),
                timeout,
            )
        }
    }

    private data class ExportReady(val file: File, val triangles: Int)

    private suspend fun awaitExport(
        activeCall: ApplyCall,
        request: ApplyRequest,
        progress: (String, Float) -> Unit,
    ): ExportReady {
        val timeout = exportTimeoutMillis(request.maxTriangles)
        try {
            return withTimeout(timeout) {
                while (true) {
                    val event = activeCall.events.receive()
                    when {
                        event.kind == EVENT_EXPORT_FILE -> {
                            val file = activeCall.outputFile
                                ?: throw StudioException("BumpMesh finished without producing an STL file")
                            val triangles = activeCall.resultTriangles
                            val refusal = resultRefusal(triangles, request)
                            if (refusal != null) {
                                runCatching { file.delete() }
                                throw StudioException(refusal)
                            }
                            return@withTimeout ExportReady(file, triangles)
                        }
                        event.kind == EVENT_PAGE_PROGRESS -> {
                            val (phase, fraction) = BumpMeshProgress.phaseForPageFraction(event.fraction)
                            progress(phase, fraction)
                        }
                        event.kind == EVENT_RECEIVING -> progress(BumpMeshProgress.RECEIVING_MESH, event.fraction)
                        event.kind == EVENT_WARNING -> activeCall.addWarning(event.message)
                        event.kind == EVENT_ERROR -> throw StudioException(event.message.ifBlank { "BumpMesh failed" })
                        event.kind == EVENT_CANCELLED -> throw CancelledException()
                        else -> Unit
                    }
                }
                error("unreachable")
            }
        } catch (timeout: TimeoutCancellationException) {
            throw EngineTimeoutException(BumpMeshFailureReport.phaseTimeout("the export"), timeout)
        }
    }

    private fun resultRefusal(triangles: Int, request: ApplyRequest): String? {
        val limit = MeshTriangleLimits.current()
        if (triangles <= 0) return "BumpMesh produced an empty mesh"
        if (triangles > limit) {
            return "The textured mesh has " + MeshTriangleLimits.formatCount(triangles) +
                " triangles, above the app's mesh triangle limit of " + MeshTriangleLimits.formatCount(limit) +
                ". Raise the limit and try again."
        }
        if (triangles > request.maxTriangles) {
            return "The textured mesh has " + MeshTriangleLimits.formatCount(triangles) +
                " triangles, above the " + MeshTriangleLimits.formatCount(request.maxTriangles) +
                " cap chosen for this apply. Lower the resolution or choose a higher cap."
        }
        return null
    }

    private fun exportTimeoutMillis(maxTriangles: Int): Long =
        EXPORT_BASE_TIMEOUT_MS + (maxTriangles.toLong() / 1_000_000L) * EXPORT_PER_MILLION_TIMEOUT_MS

    // ---- engine page and bridge -----------------------------------------------------------------

    private data class StudioEvent(val kind: String, val label: String, val fraction: Float, val message: String)

    private class ApplyCall(val request: ApplyRequest) {
        val events = Channel<StudioEvent>(Channel.UNLIMITED)
        private val warnings = LinkedHashSet<String>()
        private val lock = Any()

        @Volatile
        var outputFile: File? = null

        @Volatile
        var resultTriangles = 0

        /** The cleaned triangle count the page should report for this apply; 0 until the import is prepared. */
        @Volatile
        var expectedTriangles = 0

        private var stream: OutputStream? = null
        private var expectedBytes = 0L
        private var writtenBytes = 0L
        private var lastReportedPercent = -1

        fun beginExport(directory: File, filename: String, declaredBytes: Long, maxBytes: Long): Boolean {
            if (declaredBytes < STL_HEADER_BYTES || declaredBytes > maxBytes) return false
            return synchronized(lock) {
                closeFileLocked()
                val target = File(directory, "applied-" + UUID.randomUUID() + ".stl")
                directory.mkdirs()
                runCatching {
                    stream = target.outputStream().buffered(128 * 1024)
                    outputFile = target
                    expectedBytes = declaredBytes
                    writtenBytes = 0L
                    lastReportedPercent = -1
                    true
                }.getOrElse {
                    closeFileLocked()
                    runCatching { target.delete() }
                    false
                }
            }
        }

        fun appendExport(encoded: String): Boolean {
            val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return false
            synchronized(lock) {
                val out = stream ?: return false
                if (writtenBytes + bytes.size > expectedBytes) return false
                return runCatching {
                    out.write(bytes)
                    writtenBytes += bytes.size
                    val percent = if (expectedBytes > 0) ((writtenBytes * 100) / expectedBytes).toInt() else 0
                    if (percent >= lastReportedPercent + 2 || writtenBytes == expectedBytes) {
                        lastReportedPercent = percent
                        events.trySend(
                            StudioEvent(
                                EVENT_RECEIVING,
                                "",
                                BumpMeshProgress.receivingFraction(writtenBytes, expectedBytes),
                                "",
                            ),
                        )
                    }
                    true
                }.getOrElse { false }
            }
        }

        fun finishExport(): Boolean {
            synchronized(lock) {
                val target = outputFile ?: return false
                val out = stream ?: return false
                runCatching {
                    out.flush()
                    out.close()
                }.onFailure {
                    stream = null
                    runCatching { target.delete() }
                    return false
                }
                stream = null
                val valid = target.length() == expectedBytes && readTriangleCount(target) in 1..MeshTriangleLimits.MAX_TRIANGLES
                if (!valid) {
                    runCatching { target.delete() }
                    events.trySend(StudioEvent(EVENT_ERROR, "", 0f, "BumpMesh returned an invalid STL file"))
                    return false
                }
                resultTriangles = readTriangleCount(target)
                events.trySend(StudioEvent(EVENT_EXPORT_FILE, "", 1f, ""))
                return true
            }
        }

        fun cancelExport() {
            synchronized(lock) { closeFileLocked() }
        }

        fun closeFile() {
            synchronized(lock) { closeFileLocked() }
        }

        private fun closeFileLocked() {
            runCatching { stream?.close() }
            stream = null
            val partial = outputFile
            if (partial != null && partial.length() < expectedBytes) {
                runCatching { partial.delete() }
            }
            outputFile = null
        }

        fun addWarning(message: String) {
            if (message.isBlank()) return
            synchronized(lock) {
                if (warnings.size < MAX_WARNINGS) warnings.add(message.trim())
            }
        }

        fun warnings(): List<String> = synchronized(lock) { warnings.toList() }
    }

    private inner class StudioBridge {
        @JavascriptInterface
        fun maxOutputTriangles(): Double = MeshTriangleLimits.MAX_TRIANGLES.toDouble()

        @JavascriptInterface
        fun sourceFileName(): String = "studio-model.stl"

        @JavascriptInterface
        fun beginExport(filename: String, sizeBytes: Double): Boolean {
            val active = call ?: return false
            if (!sizeBytes.isFinite()) return false
            return active.beginExport(
                directory = resultsRoot,
                filename = filename,
                declaredBytes = sizeBytes.toLong(),
                maxBytes = MeshTriangleLimits.binaryStlBytes(MeshTriangleLimits.MAX_TRIANGLES),
            )
        }

        @JavascriptInterface
        fun appendExportChunk(encoded: String): Boolean = call?.appendExport(encoded) ?: false

        @JavascriptInterface
        fun finishExport(): Boolean = call?.finishExport() ?: false

        @JavascriptInterface
        fun cancelExport() {
            call?.cancelExport()
        }

        @JavascriptInterface
        fun onEvent(kind: String, label: String, fraction: Double, message: String) {
            onPageEvent(kind, label, fraction, message)
        }

        @JavascriptInterface
        fun onWarning(message: String) {
            onPageWarning(message)
        }

        @JavascriptInterface
        fun onError(message: String) {
            onPageError(message)
        }
    }

    private fun onPageEvent(kind: String, label: String, fraction: Double, message: String) {
        if (kind == EVENT_DRIVER_READY) {
            page.markReady()
            return
        }
        val event = StudioEvent(
            kind = kind,
            label = label,
            fraction = if (fraction.isFinite()) fraction.toFloat().coerceIn(0f, 1f) else 0f,
            message = message,
        )
        call?.events?.trySend(event)
    }

    private fun onPageWarning(message: String) {
        call?.addWarning(message)
    }

    private fun onPageError(message: String) {
        call?.events?.trySend(StudioEvent(EVENT_ERROR, "", 0f, message))
    }

    private fun recordConsoleMessage(message: ConsoleMessage) {
        val text = message.message()
        if (text.contains("mesh repair") || text.contains("safety cap") || text.contains("stlTexturizer")) {
            call?.addWarning(cleanBumpMeshWarning(text))
        }
    }

    private fun onPageFinished() {
        // Nothing here: the page owns its own ready state and driver injection.
    }

    private inner class BumpMeshEnginePage(
        private val hostContext: Context,
    ) {
        private val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(hostContext))
            .addPathHandler(
                "/model/",
                WebViewAssetLoader.PathHandler { path ->
                    when (path) {
                        "current.stl" -> fileResponse(currentSource, "model/stl")
                        "project.bumpmesh" -> fileResponse(currentProject, "application/octet-stream")
                        else -> null
                    }
                },
            )
            .build()

        private var webView: WebView? = null
        private var ready = CompletableDeferred<Unit>()
        private var driverScript: String? = null
        private val consoleWarnings = ArrayDeque<String>()

        @Volatile
        var currentSource: File? = null

        @Volatile
        var currentProject: File? = null

        @SuppressLint("SetJavaScriptEnabled")
        fun ensureStarted() {
            if (webView != null) return
            val view = WebView(hostContext)
            view.setBackgroundColor(Color.TRANSPARENT)
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = false
            }
            val debuggable = (hostContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (debuggable) WebView.setWebContentsDebuggingEnabled(true)
            val bridge = StudioBridge()
            // The pinned page's own adapter streams the STL through EnderSlicerAndroid; the studio driver talks to the
            // same object through the separate EnderSlicerStudio name.
            view.addJavascriptInterface(bridge, ADAPTER_BRIDGE_NAME)
            view.addJavascriptInterface(bridge, JAVASCRIPT_BRIDGE_NAME)
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.scheme == "http" || url.scheme == "https") {
                        if (url.host != WebViewAssetLoader.DEFAULT_DOMAIN) return blocked()
                    }
                    return assetLoader.shouldInterceptRequest(url)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                    val target = request.url
                    if (target.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    injectDriver(view)
                }
            }
            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    recordConsoleMessage(consoleMessage)
                    return true
                }

                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    onPageWarning(message.orEmpty())
                    result?.confirm()
                    return true
                }

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    result?.cancel()
                    return true
                }
            }
            attachToHost(view)
            webView = view
            ready = CompletableDeferred()
            view.loadUrl(PAGE_URL)
        }

        private fun attachToHost(view: WebView) {
            val activity = findActivity(hostContext) ?: return
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            runCatching {
                view.alpha = 0f
                view.isFocusable = false
                view.isClickable = false
                content.addView(view, ViewGroup.LayoutParams(2, 2))
            }
        }

        private fun injectDriver(view: WebView) {
            val script = runCatching { loadDriverScript() }.getOrElse { error ->
                ready.completeExceptionally(StudioException("The BumpMesh studio driver is missing from the app", error))
                return
            }
            view.evaluateJavascript(script + "\n;(window.__BumpMeshStudio ? 'ok' : 'missing');") { result ->
                // Readiness is the driver's own driver-ready event: it is sent only after the driver has verified that
                // every page control it drives is present. Injection alone is not readiness.
                if (result != "\"ok\"" && !ready.isCompleted) {
                    ready.completeExceptionally(StudioException("The BumpMesh studio driver did not initialise (" + result + ")"))
                }
            }
        }

        private fun loadDriverScript(): String {
            driverScript?.let { return it }
            val resourceId = hostContext.resources.getIdentifier(
                DRIVER_RESOURCE_NAME,
                "raw",
                hostContext.packageName,
            )
            if (resourceId == 0) throw StudioException("The BumpMesh studio driver resource is missing")
            val text = hostContext.resources.openRawResource(resourceId).bufferedReader().use { it.readText() }
            driverScript = text
            return text
        }

        suspend fun awaitReady() {
            ready.await()
        }

        fun evaluate(command: String) {
            webView?.evaluateJavascript(command, null)
        }

        fun openModelCommand(expectedTriangles: Int, name: String): String =
            "window.__BumpMeshStudio&&window.__BumpMeshStudio.openModel(" + expectedTriangles + "," +
                BumpMeshDriverCommand.jsString(name) + ")"

        fun openProjectCommand(expectedTriangles: Int): String =
            "window.__BumpMeshStudio&&window.__BumpMeshStudio.openProject(" + expectedTriangles + ")"

        fun configureCommand(configJson: String): String =
            "window.__BumpMeshStudio&&window.__BumpMeshStudio.configure(" + configJson + ")"

        fun exportCommand(): String = "window.__BumpMeshStudio&&window.__BumpMeshStudio.exportNow()"

        fun abortAndReload() {
            val view = webView ?: return
            runCatching { view.evaluateJavascript("window.__BumpMeshStudio&&window.__BumpMeshStudio.abort()", null) }
            runCatching { view.stopLoading() }
            ready = CompletableDeferred()
            runCatching { view.loadUrl(PAGE_URL) }
        }

        fun destroy() {
            val view = webView ?: return
            val parent = view.parent as? ViewGroup
            webView = null
            parent?.removeView(view)
            runCatching {
                view.removeJavascriptInterface(JAVASCRIPT_BRIDGE_NAME)
                view.stopLoading()
                view.loadUrl("about:blank")
                view.destroy()
            }
        }


        fun markReady() {
            if (!ready.isCompleted) ready.complete(Unit)
        }

        fun failReady(reason: String) {
            if (!ready.isCompleted) {
                ready.completeExceptionally(StudioException(reason.ifBlank { "The BumpMesh page failed to initialise" }))
            }
        }

        /** Destroys the hidden WebView and builds a fresh one; used once when an import produces no event at all. */
        fun recycleForRetry() {
            val old = webView ?: return
            val parent = old.parent as? ViewGroup
            webView = null
            runCatching { old.removeJavascriptInterface(JAVASCRIPT_BRIDGE_NAME) }
            runCatching { old.removeJavascriptInterface(ADAPTER_BRIDGE_NAME) }
            parent?.removeView(old)
            runCatching {
                old.stopLoading()
                old.loadUrl("about:blank")
                old.destroy()
            }
            ready = CompletableDeferred()
            ensureStarted()
        }

        fun clearConsoleWarnings() = synchronized(consoleWarnings) { consoleWarnings.clear() }

        private fun fileResponse(file: File?, mime: String): WebResourceResponse? {
            if (file == null || !file.isFile) {
                return if (mime == "model/stl") {
                    val cube = placeholderCubeStl()
                    WebResourceResponse("model/stl", null, 200, "OK", noStoreHeaders(), ByteArrayInputStream(cube))
                } else {
                    null
                }
            }
            val stream = runCatching { file.inputStream().buffered() }.getOrElse { return null }
            return WebResourceResponse(mime, null, 200, "OK", noStoreHeaders(), stream)
        }

        private fun noStoreHeaders(): Map<String, String> = mapOf("Cache-Control" to "no-store")

        private fun blocked(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked: not the bundled app origin",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )

        private fun findActivity(context: Context): Activity? {
            var candidate: Context? = context
            while (candidate is ContextWrapper) {
                if (candidate is Activity) return candidate
                candidate = candidate.baseContext
            }
            return null
        }
    }

    companion object {
        private const val TAG = "BumpMeshStudio"

        /** Fast tier: an apply at or below this many output triangles. */
        const val FAST_MAX_TRIANGLES = BumpMeshStudioLimits.FAST_MAX_TRIANGLES

        /** Detailed tier: an apply at or below this many output triangles. */
        const val DETAILED_MAX_TRIANGLES = BumpMeshStudioLimits.DETAILED_MAX_TRIANGLES

        /** Extreme tier: up to the app's mesh triangle limit. */
        const val EXTREME_MAX_TRIANGLES = BumpMeshStudioLimits.EXTREME_MAX_TRIANGLES

        /** The progress phase names in the order an apply moves through them. */
        val progressPhases: List<String> get() = BumpMeshProgress.phases

        /** Which tier [maxTriangles] falls into: "fast", "detailed" or "extreme". */
        fun tierFor(maxTriangles: Int): String = BumpMeshStudioLimits.tierFor(maxTriangles)

        /** The memory sentence the UI must show for an Extreme apply, or null for fast/detailed. */
        fun memoryWarningFor(maxTriangles: Int): String? = BumpMeshStudioLimits.memoryWarningFor(maxTriangles)

        /** The refusal sentence for an apply, or null when it may run. */
        fun refusalFor(maxTriangles: Int, refineMm: Float): String? =
            BumpMeshStudioLimits.refusalFor(maxTriangles, refineMm, MeshTriangleLimits.current())

        private const val JAVASCRIPT_BRIDGE_NAME = "EnderSlicerStudio"
        private const val ADAPTER_BRIDGE_NAME = "EnderSlicerAndroid"
        private const val DRIVER_RESOURCE_NAME = "bumpmesh_studio_driver"
        private const val PAGE_URL = "https://appassets.androidplatform.net/assets/bumpmesh/index.html?android=1&studio=1"

        private const val EVENT_MODEL_LOADED = "model-loaded"
        private const val EVENT_PROJECT_LOADED = "project-loaded"
        private const val EVENT_SETTINGS_APPLIED = "settings-applied"
        private const val EVENT_PAGE_PROGRESS = "page-progress"
        private const val EVENT_EXPORT_FILE = "export-file-ready"
        private const val EVENT_RECEIVING = "receiving"
        private const val EVENT_WARNING = "warning"
        private const val EVENT_ERROR = "error"
        private const val EVENT_CANCELLED = "cancelled"
        private const val EVENT_DRIVER_READY = "driver-ready"

        private const val STL_HEADER_BYTES = 84L
        private const val MIN_TILE_MM = 0.01f
        private const val MAX_TILE_MM = 10_000f
        private const val MIN_HEIGHT_MM = 0f
        private const val MAX_HEIGHT_MM = 100f
        private const val MIN_SMOOTHING = 0f
        private const val MAX_SMOOTHING = 20f
        private const val MIN_REFINE_MM = 0.01f
        private const val MAX_REFINE_MM = 100f
        private const val WARMUP_TIMEOUT_MS = 60_000L
        /** How long an import may produce no event before the hidden WebView is rebuilt once. */
        private const val IMPORT_SILENCE_TIMEOUT_MS = 25_000L
        private const val SETTINGS_TIMEOUT_MS = 120_000L
        private const val EXPORT_BASE_TIMEOUT_MS = 120_000L
        private const val EXPORT_PER_MILLION_TIMEOUT_MS = 60_000L
        private const val MAX_RETAINED_RESULTS = 4
        private const val MAX_WARNINGS = 20
        private val TEXTURE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

        private fun readTriangleCount(file: File): Int = runCatching {
            RandomAccessFile(file, "r").use { input ->
                input.seek(80L)
                val bytes = ByteArray(4)
                input.readFully(bytes)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
            }
        }.getOrDefault(0)

        private fun placeholderCubeStl(): ByteArray {
            // A tiny valid binary STL so the page's adapter never fails its start-up model load before the studio has
            // supplied the real source; it is replaced by the first openModel() of the first apply.
            val triangles = arrayOf(
                floatArrayOf(0f, 0f, -1f, -10f, -10f, -10f, 10f, -10f, -10f, 10f, 10f, -10f),
                floatArrayOf(0f, 0f, -1f, -10f, -10f, -10f, 10f, 10f, -10f, -10f, 10f, -10f),
                floatArrayOf(0f, 0f, 1f, -10f, -10f, 10f, 10f, -10f, 10f, 10f, 10f, 10f),
                floatArrayOf(0f, 0f, 1f, -10f, -10f, 10f, 10f, 10f, 10f, -10f, 10f, 10f),
                floatArrayOf(0f, -1f, 0f, -10f, -10f, -10f, 10f, -10f, -10f, 10f, -10f, 10f),
                floatArrayOf(0f, -1f, 0f, -10f, -10f, -10f, 10f, -10f, 10f, -10f, -10f, 10f),
                floatArrayOf(0f, 1f, 0f, -10f, 10f, -10f, 10f, 10f, -10f, 10f, 10f, 10f),
                floatArrayOf(0f, 1f, 0f, -10f, 10f, -10f, 10f, 10f, 10f, -10f, 10f, 10f),
                floatArrayOf(-1f, 0f, 0f, -10f, -10f, -10f, -10f, 10f, -10f, -10f, 10f, 10f),
                floatArrayOf(-1f, 0f, 0f, -10f, -10f, -10f, -10f, 10f, 10f, -10f, -10f, 10f),
                floatArrayOf(1f, 0f, 0f, 10f, -10f, -10f, 10f, 10f, -10f, 10f, 10f, 10f),
                floatArrayOf(1f, 0f, 0f, 10f, -10f, -10f, 10f, 10f, 10f, 10f, -10f, 10f),
            )
            val out = ByteArrayOutputStream(STL_HEADER_BYTES.toInt() + triangles.size * 50)
            DataOutputStream(out).use { data ->
                data.write(ByteArray(80))
                data.write(le32(triangles.size))
                triangles.forEach { triangle ->
                    (0 until 12).forEach { index -> data.write(le32(triangle[index].toRawBits())) }
                    data.write(0)
                    data.write(0)
                }
            }
            return out.toByteArray()
        }

        private fun le32(value: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }
}
