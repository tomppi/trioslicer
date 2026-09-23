package com.tomppi.enderslicer.texturizer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.content.FileProvider
import com.tomppi.enderslicer.storage.OneShotExportFileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.tomppi.enderslicer.BuildConfig
import com.tomppi.enderslicer.R
import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BumpMeshActivity : ComponentActivity() {
    private lateinit var sourceFile: File
    private lateinit var webView: WebView
    private lateinit var exportBridge: ExportBridge
    private var maxOutputTriangles: Int = MeshTriangleLimits.DEFAULT_TRIANGLES

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The strips the system bars sit over are this window's own black
        // background inside a light app theme, so the light system-bar icons are
        // the legible ones over them. targetSdk 36 has no edge-to-edge opt-out,
        // which is why the theme's transparent bars are the whole story here:
        // the insets applied at the end of onCreate keep the content out from
        // under them.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        maxOutputTriangles = MeshTriangleLimits.initialize(this)
        pruneExportCache()

        val sourcePath = intent.getStringExtra(EXTRA_MODEL_PATH)
        sourceFile = sourcePath?.let(::File) ?: run {
            finishWithError("No STL was supplied to BumpMesh")
            return
        }
        if (!sourceFile.isFile || sourceFile.length() < STL_HEADER_BYTES) {
            finishWithError("The STL supplied to BumpMesh is missing or empty")
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(4))
        }
        toolbar.addView(
            TextView(this).apply {
                text = getString(R.string.bumpmesh_title, MeshTriangleLimits.formatCount(maxOutputTriangles))
                textSize = 17f
                setTextColor(Color.WHITE)
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER_VERTICAL },
        )
        toolbar.addView(
            Button(this).apply {
                text = getString(android.R.string.cancel)
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)),
        )
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/model/") { path ->
                if (path == "current.stl") {
                    WebResourceResponse("model/stl", null, sourceFile.inputStream().buffered())
                } else {
                    null
                }
            }
            .build()

        exportBridge = ExportBridge(
            activity = this,
            sourceName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: sourceFile.name,
            maxOutputTriangles = maxOutputTriangles,
        )
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            addJavascriptInterface(exportBridge, JS_BRIDGE_NAME)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                    // This callback sees the resource requests of every frame,
                    // which is what makes it the place to hold the line: the
                    // bridge above is injected into all frames, so a request
                    // that is not this app's own asset origin is a document (or
                    // a script) the bridge must never reach.
                    if (isRemoteHttp(request.url)) return blocked()
                    return assetLoader.shouldInterceptRequest(request.url)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                    val target = request.url
                    if (target.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
                    if (target.scheme == "about" || target.scheme == "blob") return true
                    // The framework documents this callback as one that may
                    // arrive for a subframe too, and a framed navigation is not
                    // a user clicking a link: only the top frame may leave for
                    // another app.
                    if (!request.isForMainFrame) return true
                    openExternal(target)
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("window.EnderSlicerBridge?.loadModelFromAndroid?.()", null)
                }
            }
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // A plain LinearLayout applies no window insets by itself, so the 56 dp
        // toolbar was drawn under the status bar and the bottom of the page
        // under the navigation bar. Padding the root by the bars (and any
        // display cutout) puts both inside the safe area and moves nothing
        // else: the toolbar keeps its height and the page keeps the space
        // between them.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        webView.loadUrl(BUMPMESH_URL)
    }

    override fun onDestroy() {
        if (::exportBridge.isInitialized) exportBridge.cancelAll()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface(JS_BRIDGE_NAME)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun openExternal(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show() }
    }

    /**
     * True for an http(s) URL that is not this app's own asset origin.
     *
     * Non-http schemes are deliberately excluded: about:, blob: and data: are
     * resolved inside the WebView (the bundled page's stylesheet uses data:
     * URLs) and never reach the network stack.
     */
    private fun isRemoteHttp(uri: Uri): Boolean =
        (uri.scheme == "http" || uri.scheme == "https") &&
            uri.host != WebViewAssetLoader.DEFAULT_DOMAIN

    /** What an off-origin request gets: no body, no scheme handler, no explanation. */
    private fun blocked(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Blocked: not the bundled app origin",
        emptyMap<String, String>(),
        ByteArray(0).inputStream(),
    )

    private fun finishWithError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun pruneExportCache() {
        val directory = File(cacheDir, EXPORT_DIRECTORY)
        if (!directory.isDirectory) return
        val cutoff = System.currentTimeMillis() - ORPHAN_MAX_AGE_MILLIS
        var retainedCount = 0
        var retainedBytes = 0L
        directory.listFiles().orEmpty()
            .filter(File::isFile)
            .sortedByDescending(File::lastModified)
            .forEach { file ->
                val keep = !file.name.endsWith(PARTIAL_SUFFIX) &&
                    file.lastModified() >= cutoff &&
                    retainedCount < MAX_RETAINED_ORPHANS &&
                    retainedBytes + file.length() <= MAX_RETAINED_ORPHAN_BYTES
                if (keep) {
                    retainedCount++
                    retainedBytes += file.length()
                } else {
                    file.delete()
                }
            }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class ExportBridge(
        private val activity: BumpMeshActivity,
        private val sourceName: String,
        private val maxOutputTriangles: Int,
    ) {
        private val maxExportBytes = STL_HEADER_BYTES + maxOutputTriangles.toLong() * STL_TRIANGLE_BYTES
        private var output: File? = null
        private var stream: OutputStream? = null
        private var expectedBytes: Long = 0
        private var writtenBytes: Long = 0

        @JavascriptInterface
        fun sourceFileName(): String = sourceName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .let { name -> if (name.lowercase().endsWith(".stl")) name else "$name.stl" }

        @JavascriptInterface
        fun maxOutputTriangles(): Double = maxOutputTriangles.toDouble()

        @JavascriptInterface
        @Synchronized
        fun beginExport(filename: String, sizeBytes: Double): Boolean {
            cancelLocked()
            activity.pruneExportCache()
            if (!sizeBytes.isFinite()) return false
            val size = sizeBytes.toLong()
            if (size !in STL_HEADER_BYTES..maxExportBytes) return false

            val directory = File(activity.cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }
            val safeBase = filename
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "textured.stl" }
                .let { if (it.lowercase().endsWith(".stl")) it else "$it.stl" }
            val target = File(directory, "${System.currentTimeMillis()}-$safeBase$PARTIAL_SUFFIX")

            return runCatching {
                expectedBytes = size
                writtenBytes = 0
                output = target
                stream = BufferedOutputStream(target.outputStream(), 128 * 1024)
                true
            }.getOrElse {
                cancelLocked()
                false
            }
        }

        @JavascriptInterface
        @Synchronized
        fun appendExportChunk(encoded: String): Boolean {
            val active = stream ?: return false
            return runCatching {
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                check(writtenBytes + bytes.size <= expectedBytes)
                check(writtenBytes + bytes.size <= maxExportBytes)
                active.write(bytes)
                writtenBytes += bytes.size
                true
            }.getOrElse {
                cancelLocked()
                false
            }
        }

        @JavascriptInterface
        fun finishExport(): Boolean {
            val completed = synchronized(this) {
                val file = output ?: return false
                val active = stream ?: return false
                runCatching {
                    active.flush()
                    active.close()
                }.onFailure {
                    cancelLocked()
                    return false
                }
                stream = null
                output = null
                if (writtenBytes != expectedBytes || !isValidBinaryStl(file)) {
                    file.delete()
                    expectedBytes = 0
                    writtenBytes = 0
                    return false
                }
                val published = File(file.parentFile, file.name.removeSuffix(PARTIAL_SUFFIX))
                if (!file.renameTo(published)) {
                    file.delete()
                    expectedBytes = 0
                    writtenBytes = 0
                    return false
                }
                expectedBytes = 0
                writtenBytes = 0
                published
            }

            activity.runOnUiThread {
                val uri = FileProvider.getUriForFile(
                    activity,
                    "${BuildConfig.APPLICATION_ID}.files",
                    completed,
                )
                val result = Intent()
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                activity.setResult(Activity.RESULT_OK, result)
                activity.finish()
            }
            return true
        }

        @JavascriptInterface
        @Synchronized
        fun cancelExport() {
            cancelLocked()
        }

        @Synchronized
        fun cancelAll() {
            cancelLocked()
        }

        private fun cancelLocked() {
            runCatching { stream?.close() }
            stream = null
            output?.delete()
            output = null
            expectedBytes = 0
            writtenBytes = 0
        }

        private fun isValidBinaryStl(file: File): Boolean {
            if (!file.isFile || file.length() < STL_HEADER_BYTES || file.length() > maxExportBytes) return false
            return runCatching {
                RandomAccessFile(file, "r").use { input ->
                    input.seek(80)
                    val countBytes = ByteArray(4)
                    input.readFully(countBytes)
                    val triangleCount = ByteBuffer.wrap(countBytes)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .int
                        .toLong() and 0xffffffffL
                    triangleCount in 1L..maxOutputTriangles.toLong() &&
                        file.length() == STL_HEADER_BYTES + triangleCount * STL_TRIANGLE_BYTES
                }
            }.getOrDefault(false)
        }
    }

    companion object {
        const val EXTRA_MODEL_PATH = "com.tomppi.enderslicercura.extra.MODEL_PATH"
        const val EXTRA_MODEL_NAME = "com.tomppi.enderslicercura.extra.MODEL_NAME"

        private const val JS_BRIDGE_NAME = "EnderSlicerAndroid"
        private const val BUMPMESH_URL =
            "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/bumpmesh/index.html?android=1"
        // The directory the one-shot provider serves; see OneShotExportFileProvider.
        private const val EXPORT_DIRECTORY = OneShotExportFileProvider.BUMPMESH_DIRECTORY
        private const val PARTIAL_SUFFIX = ".part"
        private const val STL_HEADER_BYTES = 84L
        private const val STL_TRIANGLE_BYTES = 50L
        private const val MAX_RETAINED_ORPHANS = 2
        private const val MAX_RETAINED_ORPHAN_BYTES = 800L * 1024L * 1024L
        private const val ORPHAN_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
