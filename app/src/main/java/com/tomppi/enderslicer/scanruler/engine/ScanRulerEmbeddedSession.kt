package com.tomppi.enderslicer.scanruler.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * The embedded ScanRuler engine for the plate workspace.
 *
 * One instance lives for the plate workspace's lifetime. It owns:
 *  - the loopback HTTP origin the page is served from (http://127.0.0.1:<pagePort>/), so the
 *    page counts as "on this computer", skips the http hello fetch and pairs by #agent=...
 *  - the loopback WebSocket page link (ws://127.0.0.1:<websocketPort>/) with the pairing token
 *  - the app-side MCP server on 127.0.0.1:9879 (files/scanruler/scanruler_mcp_token.txt)
 *  - the persistent WebView the plate hosts in an AndroidView, and the chrome-hiding adapter
 *  - the in-app agent host/tool catalogue and the plate-scan handoff (scan.open).
 *
 * Plate <-> ScanRuler is setVisible() only; the page session is never reloaded by a switch.
 * The full More -> Experimental screen keeps its own WebViewAssetLoader origin untouched.
 */
class ScanRulerEmbeddedSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) : Closeable {

    companion object {
        const val DEFAULT_PAGE_PORT = ScanRulerAssetServer.DEFAULT_PORT
        const val DEFAULT_WEBSOCKET_PORT = ScanRulerPageLink.DEFAULT_PORT
        const val DEFAULT_MCP_PORT = ScanRulerMcpServer.DEFAULT_PORT
        private const val TAG = "ScanRulerEngine"
        /** The context sentence the in-app agent needs when the ScanRuler view is active. */
        val AGENT_INSTRUCTIONS = """
            The active tool host for this conversation is ScanRuler (CNC Kitchen) - the embedded
            3D scan measurement workspace, not the plate and not SolidCraft. The viewport shows the
            live ScanRuler page; the user can see the same notes and measurements you act on.
            Work through the ScanRuler tools only: they are the page's own commands. Scans and
            measures are taken on the loaded scan; notes are the user's pinned instructions, so
            read them with reverse_note_list before building anything and answer/resolve them with
            reverse_note_add / reverse_note_resolve as you go. Files you export are saved by the
            app into Download/ScanRuler and the tool result names the content URI. Never invent a
            scan or a project file: if the page has no scan, ask the user to load one in the
            ScanRuler view. Keep replies short and refer to the page's own numbers.
        """.trimIndent()
    }

    private val _state = MutableStateFlow(ScanRulerEmbeddedState())
    val state: StateFlow<ScanRulerEmbeddedState> = _state.asStateFlow()

    private val pageToken = UUID.randomUUID().toString().replace("-", "")
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    @Volatile private var _webView: WebView? = null
    /** Created by [start] on the main thread; safe to read once [state] is not IDLE. */
    val webView: WebView
        get() = _webView ?: throw IllegalStateException("ScanRulerEmbeddedSession.start() has not been called.")

    private var assetServer: ScanRulerAssetServer? = null
    private var pageLink: ScanRulerPageLink? = null
    private var mcpServer: ScanRulerMcpServer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val host = ScanRulerAgentHost(this, log)

    val agentHost: com.tomppi.enderslicer.cadagent.AgentToolHost get() = host
    val agentInstructions: String get() = AGENT_INSTRUCTIONS

    /** Starts the loopback servers and the WebView, then loads the paired page. */
    suspend fun start() {
        if (started.getAndSet(true)) return
        val view = withContext(Dispatchers.Main.immediate) { buildWebView() }
        _webView = view
        try {
            val assets = ScanRulerAssetServer(
                assetBytes = scanRulerAssetReader(context),
                bootstrapHtml = { bootstrapHtml() },
                preferredPort = DEFAULT_PAGE_PORT,
                log = log,
            )
            assets.start()
            val link = ScanRulerPageLink(
                token = pageToken,
                expectedOrigins = { setOf(assets.pageOrigin) },
                port = DEFAULT_WEBSOCKET_PORT,
                log = log,
            )
            link.onCommands = { commands -> onCommands(commands) }
            link.onPageChanged = { paired -> onPageChanged(paired) }
            link.start()
            val mcp = ScanRulerMcpServer(
                token = ScanRulerTokens.ensureMcpToken(context),
                pageLink = link,
                status = { _state.value },
                binarySink = { bytes, name, mimeType ->
                    val written = ScanRulerFileExports.write(context, bytes, name, mimeType)
                    JSONObject()
                        .put("name", written.name)
                        .put("mimeType", written.mimeType)
                        .put("size", written.size)
                        .put("uri", written.uri)
                },
                afterCommand = {
                    scope.launch(Dispatchers.IO) {
                        runCatching { restoreEmbeddedWorkspace() }
                    }
                },
                log = log,
            )
            mcp.start()
            assetServer = assets
            pageLink = link
            mcpServer = mcp
            _state.value = ScanRulerEmbeddedState(
                phase = ScanRulerPhase.PREPARING,
                pageUrl = assets.pageOrigin + ScanRulerAssetServer.BOOTSTRAP_PATH,
                pagePort = assets.boundPort,
                websocketPort = link.boundPort,
                message = "Preparing ScanRuler...",
            )
            withContext(Dispatchers.Main.immediate) {
                view.loadUrl(assets.pageOrigin + ScanRulerAssetServer.BOOTSTRAP_PATH)
            }
            log("ScanRuler embedded engine started (page " + assets.boundPort + ", link " + link.boundPort + ")")
        } catch (error: Throwable) {
            log("ScanRuler engine start failed: " + (error.message ?: error.javaClass.simpleName))
            _state.value = ScanRulerEmbeddedState(
                phase = ScanRulerPhase.FAILED,
                pageUrl = _state.value.pageUrl,
                pagePort = _state.value.pagePort,
                websocketPort = _state.value.websocketPort,
                message = "ScanRuler could not start: " + (error.message ?: error.javaClass.simpleName),
            )
            runCatching { mcpServer?.close() }
            runCatching { pageLink?.close() }
            runCatching { assetServer?.close() }
            mcpServer = null
            pageLink = null
            assetServer = null
        }
    }

    /** Waits for the page to pair and expose its commands; false on timeout, phase unchanged. */
    suspend fun awaitReady(timeoutMillis: Long = 25_000): Boolean =
        withTimeoutOrNull(timeoutMillis.coerceAtLeast(1)) {
            state.first { it.ready || it.phase == ScanRulerPhase.FAILED }
            state.value.ready
        } ?: false

    /** Hides/shows the page without reloading or stopping it. Call from the main thread. */
    fun setVisible(visible: Boolean) {
        val view = _webView ?: return
        val action = { view.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    /**
     * Switches to the notes (reverse) workspace and installs the chrome-hiding adapter: the 3D
     * viewport and the note UI stay, the instrument's chrome goes. Idempotent and fail-soft.
     */
    suspend fun showEmbeddedWorkspace(): Boolean {
        if (!awaitReady()) return false
        suspend fun tryCommand(name: String, input: JSONObject): JSONObject? = try {
            command(name, input)
        } catch (error: ScanRulerCommandException) {
            log("ScanRuler workspace command " + name + " was not applied: " + (error.message ?: error.code))
            null
        } catch (error: ScanRulerLinkException) {
            log("ScanRuler workspace command " + name + " skipped: " + (error.message ?: "no page"))
            null
        }
        tryCommand("workspace.set", JSONObject().put("workspace", ScanRulerChromeAdapter.EMBEDDED_WORKSPACE))
        evaluateJavascript(ScanRulerChromeAdapter.installScript())
        _state.value = _state.value.copy(embedded = true)
        return true
    }

    /** Puts the full UI back; the standalone screen has its own engine and is not affected. */
    suspend fun setEmbeddedMode(enabled: Boolean) {
        if (enabled) {
            showEmbeddedWorkspace()
        } else {
            evaluateJavascript(ScanRulerChromeAdapter.removeScript())
            _state.value = _state.value.copy(embedded = false)
        }
    }

    /**
     * Runs a page command, retrying while the page reports it is busy with its CAD kernel
     * loading (switching to the notes workspace starts that load; its first scan.open can
     * arrive while it runs). A busy page says "Try again once it has finished" - this is that
     * retry, bounded, for the one command the UI cannot wait on itself.
     */
    /**
     * The page's own commands can put their workspace on screen (element.fit shows measure,
     * deviation.measure shows deviation, ...). The embedded face hides the workspaces' chrome
     * and keeps the notes panel, which only the reverse workspace renders, so after any command
     * the embedded session puts the reverse workspace back. It changes only what is on screen;
     * the element, dimension or note the command made stays.
     */
    private suspend fun restoreEmbeddedWorkspace() {
        if (!_state.value.embedded) return
        val link = pageLink ?: return
        repeat(2) {
            try {
                link.run("workspace.set", JSONObject().put("workspace", ScanRulerChromeAdapter.EMBEDDED_WORKSPACE), timeoutMillis = 10_000)
                return
            } catch (_: ScanRulerCommandException) {
                // Busy or gone; one quiet retry is enough - the next command restores it too.
            } catch (_: ScanRulerLinkException) {
                return
            }
        }
    }

    private suspend fun runWhenPageIdle(
        name: String,
        input: JSONObject,
        binaryPath: String? = null,
        timeoutMillis: Long,
        busyTimeoutMillis: Long = 90_000,
    ): JSONObject {
        val link = pageLink ?: throw ScanRulerLinkException("The ScanRuler page link is not running.")
        val busyDeadline = System.currentTimeMillis() + busyTimeoutMillis
        var waitMillis = 400L
        while (true) {
            try {
                return link.run(name = name, input = input, binaryPath = binaryPath, timeoutMillis = timeoutMillis)
            } catch (error: ScanRulerCommandException) {
                if (error.code != "busy" || System.currentTimeMillis() >= busyDeadline) throw error
                _state.value = _state.value.copy(message = "Wait for ScanRuler's CAD kernel...")
                delay(waitMillis)
                waitMillis = (waitMillis * 2).coerceAtMost(2_000L)
            }
        }
    }

    /** Gives the page a plate mesh as its scan (bytes through scan.open), then waits for it. */
    suspend fun loadScan(file: File): JSONObject = withContext(Dispatchers.IO) {
        loadScan(file.readBytes(), file.name)
    }

    /** Bytes variant of [loadScan]; name is used by the page for its title/export names. */
    suspend fun loadScan(bytes: ByteArray, name: String = "scan.stl"): JSONObject {
        if (bytes.isEmpty()) throw ScanRulerCommandException("empty", "The scan file is empty.")
        if (!awaitReady()) throw ScanRulerLinkException("The ScanRuler page is not ready yet.")
        val input = JSONObject().put("bytes", bytes).put("name", name)
        _state.value = _state.value.copy(message = "Loading scan into ScanRuler...")
        try {
            return runWhenPageIdle(
                name = "scan.open",
                input = input,
                binaryPath = "input.bytes",
                timeoutMillis = 10 * 60_000L,
            )
        } finally {
            _state.value = _state.value.copy(message = null)
            runCatching { restoreEmbeddedWorkspace() }
        }
    }

    /** Runs one page command (catalog names as the page lists them: e.g. "scan.open"). */
    suspend fun command(name: String, input: JSONObject = JSONObject()): JSONObject {
        if (!awaitReady()) throw ScanRulerLinkException("The ScanRuler page is not ready yet.")
        try {
            return pageLink?.run(name = name, input = input, timeoutMillis = 5 * 60_000L)
                ?: throw ScanRulerLinkException("The ScanRuler page link is not running.")
        } finally {
            if (name != "workspace.set") restoreEmbeddedWorkspace()
        }
    }

    /** The MCP tool catalogue for the current page; status/wait always present. */
    fun toolDefinitions(): JSONArray = pageLink?.mcpTools()
        ?: ScanRulerCommandCatalog.mcpTools(emptyList())

    internal fun commandNames(): List<String> = pageLink?.catalog?.map { it.name }.orEmpty()

    internal fun statusSummary(): String = JSONObject()
        .put("phase", _state.value.phase.name.lowercase())
        .put("paired", _state.value.paired)
        .put("toolsReady", _state.value.toolsReady)
        .put("toolCount", _state.value.toolCount)
        .put("embedded", _state.value.embedded)
        .put("message", _state.value.message)
        .toString()

    internal fun waitForPageText(): String {
        val ready = runCatching { pageLink?.awaitPage(20_000) == true }.getOrDefault(false)
        val current = _state.value
        return JSONObject()
            .put("ready", ready && current.ready)
            .put("phase", current.phase.name.lowercase())
            .put("toolCount", current.toolCount)
            .put("message", current.message)
            .toString()
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { mcpServer?.close() }
        mcpServer = null
        runCatching { pageLink?.close() }
        pageLink = null
        runCatching { assetServer?.close() }
        assetServer = null
        _state.value = ScanRulerEmbeddedState(phase = ScanRulerPhase.IDLE, message = "ScanRuler stopped.")
        val view = _webView
        _webView = null
        if (view != null) {
            val action = {
                runCatching {
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    view.destroy()
                }
                Unit
            }
            if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
        }
    }

    private fun buildWebView(): WebView {
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                log("ScanRuler console: " + message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")")
                return true
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url
                val host = target.host.orEmpty()
                if (host == "127.0.0.1" || host == "localhost") return false
                // The embedded workspace is single-app: an external link belongs in the browser.
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.toString())).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                log("ScanRuler page finished: " + url)
                if (_state.value.embedded) {
                    view.evaluateJavascript(ScanRulerChromeAdapter.installScript(), null)
                }
            }
        }
        return view
    }

    private suspend fun evaluateJavascript(script: String) {
        val view = _webView ?: throw ScanRulerLinkException("The ScanRuler WebView is not running.")
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                try {
                    view.evaluateJavascript(script) { continuation.resume(Unit) }
                } catch (error: Throwable) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }
    }

    private fun onCommands(commands: List<ScanRulerCommandCatalog.Command>) {
        runCatching { mcpServer?.notifyToolsChanged() }
        val current = _state.value
        _state.value = current.copy(
            toolsReady = true,
            toolCount = commands.size,
            phase = if (current.paired) ScanRulerPhase.READY else ScanRulerPhase.PREPARING,
            message = if (current.paired) null else current.message,
        )
    }

    private fun onPageChanged(paired: Boolean) {
        val current = _state.value
        _state.value = if (paired) {
            current.copy(
                paired = true,
                phase = if (current.toolsReady) ScanRulerPhase.READY else ScanRulerPhase.PREPARING,
                message = if (current.toolsReady) null else "ScanRuler page connected...",
            )
        } else {
            current.copy(
                paired = false,
                toolsReady = false,
                toolCount = 0,
                phase = if (current.phase == ScanRulerPhase.FAILED) ScanRulerPhase.FAILED else ScanRulerPhase.PREPARING,
                message = "ScanRuler page disconnected; it reconnects automatically...",
            )
        }
    }

    private fun bootstrapHtml(): String {
        val link = pageLink ?: throw IllegalStateException("page link is not started")
        val port = link.boundPort
        val escaped = pageToken.replace("\\", "\\\\").replace("'", "\\'")
        return """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>ScanRuler</title></head>
            <body style="background:#101215">
            <script>
            try {
              localStorage.setItem('scanruler.agentdataok', '1');
              localStorage.setItem('scanruler.agentlink', '1');
              localStorage.setItem('scanruler.agentport', '$port');
              localStorage.setItem('scanruler.agenttoken', '$escaped');
            } catch (e) {}
            location.replace('/assets/scanruler/index.html#agent=$port:$pageToken');
            </script>
            </body></html>
        """.trimIndent()
    }
}