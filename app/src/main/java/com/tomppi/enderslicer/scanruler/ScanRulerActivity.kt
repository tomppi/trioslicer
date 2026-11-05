package com.tomppi.enderslicer.scanruler

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.tomppi.enderslicer.BuildConfig
import org.json.JSONObject
import java.io.OutputStream

/**
 * ScanRuler (CNC Kitchen) as an offline, vendored web app inside the APK.
 *
 * The page is served by [WebViewAssetLoader] from `assets/scanruler/` at the app's own
 * https origin, so no network is involved. Its `<input type=file>` opens the system picker
 * ([WebChromeClient.onShowFileChooser]); its Blob downloads are streamed through
 * [SCANRULER_BRIDGE_SCRIPT] into the SAF document the human chose - WebView's
 * DownloadListener cannot see `blob:` URLs. Nothing is handed to the plate or the slicing
 * engine in this release.
 */
class ScanRulerActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: DownloadBridge
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var loadingLabel: TextView
    private lateinit var errorOverlay: LinearLayout
    private lateinit var errorLabel: TextView

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pageReady = false
    private var errorShown = false
    private var blankChecks = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadTimeout = Runnable {
        if (!pageReady && !errorShown) {
            showError(
                "ScanRuler is taking too long to load. Retry, or close and open it again.",
            )
        }
    }

    private val openFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> deliverFileChooser(uris) }

    private val openFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> deliverFileChooser(listOfNotNull(uri)) }

    private val saveDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> if (::bridge.isInitialized) bridge.onDestinationChosen(uri) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        root.addView(buildToolbar(), LinearLayout.LayoutParams.MATCH_PARENT, dp(56))

        val content = FrameLayout(this)
        // WebViewAssetLoader guesses a few MIME types by extension, but wasm/fonts are not
        // all known to it: WebAssembly.instantiateStreaming refuses a wasm body served as
        // text/plain or octet-stream. The assets are still read by the standard
        // AssetsPathHandler; this only names the types the page cannot load without.
        val assetsHandler = WebViewAssetLoader.AssetsPathHandler(this)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.PathHandler { path ->
                val response = assetsHandler.handle(path) ?: return@PathHandler null
                val mime = scanRulerMimeFor(path) ?: return@PathHandler response
                WebResourceResponse(mime, response.encoding ?: "utf-8", response.data)
            })
            .build()
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (isRemoteHttp(request.url)) return blocked()
                    return assetLoader.shouldInterceptRequest(request.url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest,
                ): Boolean {
                    val target = request.url
                    if (target.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
                    if (target.scheme in setOf("about", "blob", "data")) return true
                    if (!request.isForMainFrame) return true
                    openExternal(target)
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (url != null && isScanRulerUrl(url)) bridge.onPageStarted()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!isScanRulerUrl(url)) return
                    view.evaluateJavascript(SCANRULER_BRIDGE_SCRIPT, null)
                    checkPageContent()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        showError(
                            "Could not load ScanRuler: " + error.description +
                                " (error " + error.errorCode + ").",
                        )
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                        showError(
                            "ScanRuler could not be loaded (HTTP " +
                                errorResponse.statusCode + ").",
                        )
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: WebChromeClient.FileChooserParams?,
                ): Boolean {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback
                    val accept = fileChooserParams?.acceptTypes
                        ?.filter { it.contains("/") }
                        ?.toTypedArray()
                        ?.takeIf { it.isNotEmpty() }
                        ?: arrayOf("*/*")
                    return try {
                        if (fileChooserParams?.mode ==
                            WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                        ) {
                            openFiles.launch(accept)
                        } else {
                            openFile.launch(accept)
                        }
                        true
                    } catch (error: Throwable) {
                        fileChooserCallback = null
                        filePathCallback?.onReceiveValue(null)
                        false
                    }
                }
            }
        }
        bridge = DownloadBridge()
        webView.addJavascriptInterface(bridge, JS_BRIDGE_NAME)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        content.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        loadingOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            addView(ProgressBar(this@ScanRulerActivity))
            loadingLabel = TextView(this@ScanRulerActivity).apply {
                text = "Loading ScanRuler…"
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, 0)
            }
            addView(loadingLabel)
        }
        content.addView(
            loadingOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        errorOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            errorLabel = TextView(this@ScanRulerActivity).apply {
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
            }
            addView(errorLabel)
            val buttons = LinearLayout(this@ScanRulerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, 0)
            }
            buttons.addView(
                Button(this@ScanRulerActivity).apply {
                    text = "Retry"
                    setOnClickListener { retry() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                    marginEnd = dp(12)
                },
            )
            buttons.addView(
                Button(this@ScanRulerActivity).apply {
                    text = "Close"
                    setOnClickListener { finish() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)),
            )
            addView(buttons)
        }
        errorOverlay.visibility = View.GONE
        content.addView(
            errorOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        root.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }

        mainHandler.postDelayed(loadTimeout, LOAD_TIMEOUT_MILLIS)
        webView.loadUrl(SCANRULER_URL)
    }

    private fun buildToolbar(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(4))
            addView(
                TextView(this@ScanRulerActivity).apply {
                    text = "ScanRuler"
                    setTextColor(Color.WHITE)
                    textSize = 17f
                },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    gravity = Gravity.CENTER_VERTICAL
                },
            )
            addView(
                Button(this@ScanRulerActivity).apply {
                    text = "Close"
                    setOnClickListener { finish() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)),
            )
        }

    private fun deliverFileChooser(uris: List<Uri>) {
        val callback = fileChooserCallback ?: return
        fileChooserCallback = null
        callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    private fun isScanRulerUrl(url: String): Boolean =
        url.startsWith("https://" + WebViewAssetLoader.DEFAULT_DOMAIN + "/assets/scanruler/")

    private fun isRemoteHttp(uri: Uri): Boolean =
        (uri.scheme == "http" || uri.scheme == "https") &&
            uri.host != WebViewAssetLoader.DEFAULT_DOMAIN

    private fun blocked(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Blocked: not the bundled app origin",
        emptyMap<String, String>(),
        ByteArray(0).inputStream(),
    )

    private fun openExternal(uri: Uri) {
        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
            .onFailure {
                Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
            }
    }

    private fun retry() {
        showLoading()
        webView.loadUrl(SCANRULER_URL)
    }

    private fun showLoading() {
        pageReady = false
        errorShown = false
        blankChecks = 0
        loadingOverlay.visibility = View.VISIBLE
        errorOverlay.visibility = View.GONE
        mainHandler.removeCallbacks(loadTimeout)
        mainHandler.postDelayed(loadTimeout, LOAD_TIMEOUT_MILLIS)
    }

    private fun markReady() {
        if (pageReady) return
        pageReady = true
        mainHandler.removeCallbacks(loadTimeout)
        loadingOverlay.visibility = View.GONE
        errorOverlay.visibility = View.GONE
    }

    private fun showError(message: String) {
        if (errorShown) return
        errorShown = true
        mainHandler.removeCallbacks(loadTimeout)
        loadingOverlay.visibility = View.GONE
        errorOverlay.visibility = View.VISIBLE
        errorLabel.text = message
    }

    /**
     * A blank page after onPageFinished means the assets did not arrive, not that the page is
     * still painting: check for body content a few times before naming it.
     */
    private fun checkPageContent() {
        if (pageReady || errorShown) return
        webView.evaluateJavascript(PAGE_READY_PROBE) { result ->
            if (result != null && result.contains("ready")) {
                markReady()
            } else {
                blankChecks += 1
                if (blankChecks >= MAX_BLANK_CHECKS) {
                    showError(
                        "ScanRuler loaded but its page is blank. This build may be missing " +
                            "its web assets; retry, or close and open it again.",
                    )
                } else {
                    mainHandler.postDelayed({ checkPageContent() }, BLANK_RECHECK_MILLIS)
                }
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        if (::bridge.isInitialized) bridge.cancelAll()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface(JS_BRIDGE_NAME)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun notifyPage(script: String) {
        if (::webView.isInitialized) webView.evaluateJavascript(script, null)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** The MIME types the ScanRuler page cannot load without being named. */
    private fun scanRulerMimeFor(path: String): String? = when (
        path.substringAfterLast('.', "").lowercase()
    ) {
        "wasm" -> "application/wasm"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "svg" -> "image/svg+xml"
        else -> null
    }

    /**
     * The Kotlin half of the export bridge: every byte arrives from the page's adapter as a
     * bounded base64 chunk and is written, in order, to the SAF document the human chose.
     */
    private inner class DownloadBridge {
        private val transfer = ScanRulerTransfer(MAX_DOWNLOAD_BYTES, MAX_CHUNK_BYTES)
        private var destination: Uri? = null

        @Suppress("UNUSED_PARAMETER")
        @JavascriptInterface
        fun beginDownload(filename: String, mime: String, expectedBytes: Double): Boolean {
            val begin = synchronized(transfer) { transfer.begin(filename, expectedBytes) }
            if (!begin.accepted) {
                // A refused begin must not touch the export in flight: the shared complete
                // and report path would delete its destination and broadcast the failure
                // whose page half clears the pending stream the in-flight export is
                // sending. The refusal goes to the page as the return value and to the
                // user as a toast.
                val message = begin.message ?: "The export could not be started."
                mainHandler.post {
                    Toast.makeText(this@ScanRulerActivity, message, Toast.LENGTH_LONG).show()
                }
                return false
            }
            mainHandler.post {
                if (!synchronized(transfer) { transfer.waitingForDestination }) return@post
                try {
                    saveDocument.launch(begin.suggestedName)
                } catch (error: Throwable) {
                    failTransfer(
                        "Could not open the save dialog, so no file was written: " +
                            (error.message ?: "the picker failed"),
                    )
                }
            }
            return true
        }

        fun onDestinationChosen(uri: Uri?) {
            if (uri == null) {
                synchronized(transfer) { transfer.cancel() }
                complete(false, "Cancelled - no file was written.")
                return
            }
            val stream = runCatching { contentResolver.openOutputStream(uri, "w") }.getOrNull()
            if (stream == null) {
                deleteDestination(uri)
                failTransfer("Could not open the chosen destination, so no file was written.")
                return
            }
            val attached = synchronized(transfer) { transfer.attach(stream) }
            if (!attached) {
                runCatching { stream.close() }
                deleteDestination(uri)
                failTransfer("The export could not be started, so no file was written.")
                return
            }
            destination = uri
            notifyPage("window.__scanRulerBridgeReady && window.__scanRulerBridgeReady()")
        }

        @JavascriptInterface
        fun appendDownloadChunk(encoded: String): Boolean {
            val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
            if (bytes == null) {
                failTransfer("The export sent bytes this app could not read, so no file was written.")
                return false
            }
            val accepted = synchronized(transfer) { transfer.append(bytes) }
            if (!accepted) {
                complete(false, synchronized(transfer) { transfer.failureMessage }
                    ?: "The export could not be written, so no file was written.")
            }
            return accepted
        }

        @JavascriptInterface
        fun finishDownload() {
            val finished = synchronized(transfer) { transfer.finish() }
            if (finished) {
                complete(
                    true,
                    "Saved " + transfer.fileName + " (" + transfer.writtenBytes +
                        " bytes) to the chosen destination",
                )
            } else {
                complete(
                    false,
                    synchronized(transfer) { transfer.failureMessage }
                        ?: "The export did not finish, so no complete file was written.",
                )
            }
        }

        @JavascriptInterface
        fun cancelDownload() {
            synchronized(transfer) { transfer.cancel() }
            complete(false, "Cancelled - no file was written.")
        }

        fun onPageStarted() {
            val active = synchronized(transfer) {
                transfer.waitingForDestination || transfer.writing
            }
            if (!active) return
            synchronized(transfer) { transfer.cancel() }
            destination?.let(::deleteDestination)
            destination = null
        }

        fun cancelAll() {
            synchronized(transfer) { transfer.cancel() }
            destination?.let(::deleteDestination)
            destination = null
        }

        private fun failTransfer(message: String) {
            synchronized(transfer) { transfer.fail(message) }
            complete(false, message)
        }

        private fun complete(ok: Boolean, message: String) {
            if (!ok) {
                destination?.let(::deleteDestination)
            }
            destination = null
            mainHandler.post {
                Toast.makeText(this@ScanRulerActivity, message, Toast.LENGTH_LONG).show()
                notifyPage(
                    "window.__scanRulerBridgeResult && window.__scanRulerBridgeResult(" +
                        (if (ok) "true" else "false") + "," +
                        JSONObject.quote(message) + ")",
                )
            }
        }

        private fun deleteDestination(uri: Uri) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
        }
    }

    companion object {
        private const val JS_BRIDGE_NAME = "ScanRulerAndroid"
        private val SCANRULER_URL =
            "https://" + WebViewAssetLoader.DEFAULT_DOMAIN +
                "/assets/scanruler/index.html"
        private const val PAGE_READY_PROBE =
            "(function(){try{return (document.body && " +
                "(document.body.childElementCount > 0 || document.body.innerText.trim().length > 0))" +
                " ? 'ready' : 'blank';}catch(e){return 'blank';}})()"
        private const val MAX_DOWNLOAD_BYTES = 256L * 1024L * 1024L
        private const val MAX_CHUNK_BYTES = 256 * 1024
        private const val LOAD_TIMEOUT_MILLIS = 20_000L
        private const val BLANK_RECHECK_MILLIS = 1_200L
        private const val MAX_BLANK_CHECKS = 4
    }
}
