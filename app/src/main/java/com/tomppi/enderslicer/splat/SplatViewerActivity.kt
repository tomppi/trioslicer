package com.tomppi.enderslicer.splat

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.webkit.WebViewAssetLoader
import com.tomppi.enderslicer.ui.EnderSlicerDimens
import java.io.File
import java.io.FileInputStream

/**
 * A trained Gaussian-splat scene, rendered by the vendored Aholo viewer.
 *
 * The page is an offline asset served by WebViewAssetLoader at the app's own https origin,
 * so the scene's bytes never leave the phone and the WebView gets a secure context for WebGL.
 * The model itself is served from app storage under /splats/, one file per scene; the page
 * reads its query string, fetches that URL and hands the bytes to the Aholo loader.
 *
 * The renderer is a WebGL2 page rather than a native pipeline on purpose: the Aholo viewer is
 * MIT, already ships as a browser package, and the app already embeds web workspaces this way
 * (ScanRuler). Nothing here touches the plate or the slicing engine.
 */
class SplatViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val rawPath = intent.getStringExtra(EXTRA_PATH)
        val fileName = intent.getStringExtra(EXTRA_FILE)
        val file = when {
            !rawPath.isNullOrBlank() -> File(rawPath).takeIf { SplatLibrary.isInsideAppStorage(this, it) }
            !fileName.isNullOrBlank() -> File(SplatLibrary.directory(this), fileName)
            else -> null
        }
        if (file == null || !file.isFile || !SplatLibrary.isSupported(file.name)) {
            Toast.makeText(this, "That splat is no longer here.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: SplatLibrary.titleOf(file)
        val viewFile = if (file.extension.lowercase() == "ply" && !SplatLibrary.isGaussianSplat(file)) {
            convertPointCloudPlyToSplat(file) ?: file
        } else {
            file
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SplatViewerScreen(title = title, file = viewFile, onClose = { finish() })
            }
        }
    }

    @Composable
    private fun SplatViewerScreen(title: String, file: File, onClose: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ComposeColor.Black)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EnderSlicerDimens.Space8),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = ComposeColor.White,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = EnderSlicerDimens.Space8),
                )
                TextButton(onClick = onClose) { Text("Close") }
            }
            AndroidView(
                factory = { context -> splatWebView(context, file, title) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }

    private fun splatWebView(context: android.content.Context, file: File, displayTitle: String): WebView {
        val assets = WebViewAssetLoader.AssetsPathHandler(this)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.PathHandler { path ->
                val response = assets.handle(path) ?: return@PathHandler null
                val mime = textAssetMime(path) ?: return@PathHandler response
                WebResourceResponse(mime, response.encoding ?: "utf-8", response.data)
            })
            .addPathHandler("/splats/", WebViewAssetLoader.PathHandler { path ->
                val target = if (path == file.name) file else File(SplatLibrary.directory(this), path)
                splatResponse(target, file.parentFile ?: SplatLibrary.directory(this))
            })
            .build()
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            // Every byte the page loads is an APK asset or an app file; nothing here is a
            // network resource, so a stale WebView cache is only ever a stale build.
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun log(message: String) { Log.i(TAG, "page: " + message) }
            }, "SplatHost")
            webChromeClient = object : WebChromeClient() {
                // The page reports what it is doing on its own canvas; console lines are what
                // tells a developer why a model did not appear.
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.i(TAG, message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest,
                ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest,
                ): Boolean = request.url.host != WebViewAssetLoader.DEFAULT_DOMAIN
            }
            // The page sizes its canvas from the container's layout at load time, so it is
            // loaded only once the WebView has a real height: loading it during composition
            // gave the renderer a 0-pixel viewport and a black frame forever.
            doOnLayout { view ->
                Log.i(TAG, "webview laid out " + view.width + "x" + view.height)
                loadUrl(
                    "https://" + WebViewAssetLoader.DEFAULT_DOMAIN + "/assets/splatviewer/index.html" +
                        "?splat=/splats/" + android.net.Uri.encode(file.name) +
                        "&name=" + android.net.Uri.encode(displayTitle),
                )
            }
        }
    }

    /** The response for one scene file, or null when the name points outside the allowed folder. */
    private fun splatResponse(file: File, baseDir: File = SplatLibrary.directory(this)): WebResourceResponse? {
        val directory = baseDir.canonicalFile
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!canonical.path.startsWith(directory.path + File.separator)) return null
        val cacheDirRoot = File(cacheDir, "aholo-ply").canonicalFile
        val inCache = canonical.path.startsWith(cacheDirRoot.path + File.separator)
        if (!inCache && !SplatLibrary.isInsideAppStorage(this, canonical)) return null
        if (!canonical.isFile || !SplatLibrary.isSupported(canonical.name)) return null
        val response = WebResourceResponse(
            "application/octet-stream",
            null,
            FileInputStream(canonical),
        )
        response.responseHeaders = mapOf(
            "Content-Length" to canonical.length().toString(),
            "Cache-Control" to "no-store",
        )
        return response
    }

    /**
     * Converts a plain XYZ(+RGB) point-cloud PLY into a cached 32-byte `.splat` so Aholo renders
     * laser/mesh PLYs with the exact same 360-degree viewer as trained 3DGS scenes.
     */
    private fun convertPointCloudPlyToSplat(ply: File): File? = runCatching {
        val outDir = File(cacheDir, "aholo-ply").apply { mkdirs() }
        val key = "${ply.absolutePath.hashCode().toUInt()}_${ply.length()}_${ply.lastModified()}"
        val out = File(outDir, "$key.splat")
        if (out.isFile && out.length() >= 32L) return@runCatching out

        val data = ply.readBytes()
        val endMarker = "end_header".toByteArray(Charsets.US_ASCII)
        var headerEnd = -1
        outer@ for (i in 0..(data.size - endMarker.size).coerceAtMost(65536)) {
            for (j in endMarker.indices) if (data[i + j] != endMarker[j]) continue@outer
            headerEnd = i
            break
        }
        if (headerEnd < 0) return@runCatching null
        val header = String(data, 0, headerEnd, Charsets.US_ASCII)
        val lines = header.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val isBinaryLe = lines.any { it.startsWith("format binary_little_endian") }
        val isAscii = lines.any { it.startsWith("format ascii") }
        if (!isBinaryLe && !isAscii) return@runCatching null

        var vertexCount = 0
        var inVertex = false
        data class Prop(val name: String, val type: String, val offset: Int, val size: Int)
        val props = ArrayList<Prop>()
        var stride = 0
        fun typeSize(t: String): Int = when (t) {
            "char", "uchar", "int8", "uint8" -> 1
            "short", "ushort", "int16", "uint16" -> 2
            "int", "uint", "int32", "uint32", "float", "float32" -> 4
            "double", "float64" -> 8
            else -> 0
        }
        for (line in lines) {
            if (line.startsWith("element ")) {
                val parts = line.split(Regex("\\s+"))
                inVertex = parts.size >= 3 && parts[1] == "vertex"
                if (inVertex) vertexCount = parts[2].toIntOrNull() ?: 0
            } else if (inVertex && line.startsWith("property ") && !line.startsWith("property list")) {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 3) {
                    val sz = typeSize(parts[1])
                    if (sz <= 0) return@runCatching null
                    props += Prop(parts[2], parts[1], stride, sz)
                    stride += sz
                }
            }
        }
        if (vertexCount <= 0 || props.isEmpty()) return@runCatching null

        val px = props.firstOrNull { it.name == "x" } ?: return@runCatching null
        val py = props.firstOrNull { it.name == "y" } ?: return@runCatching null
        val pz = props.firstOrNull { it.name == "z" } ?: return@runCatching null
        val pr = props.firstOrNull { it.name == "red" || it.name == "r" }
        val pg = props.firstOrNull { it.name == "green" || it.name == "g" }
        val pb = props.firstOrNull { it.name == "blue" || it.name == "b" }

        var payloadStart = headerEnd + endMarker.size
        while (payloadStart < data.size && (data[payloadStart] == '\r'.code.toByte() || data[payloadStart] == '\n'.code.toByte())) {
            payloadStart++
        }

        val maxPts = 350_000
        val step = (vertexCount / maxPts).coerceAtLeast(1)
        val estCount = (vertexCount + step - 1) / step
        val xs = FloatArray(estCount)
        val ys = FloatArray(estCount)
        val zs = FloatArray(estCount)
        val rs = ByteArray(estCount)
        val gs = ByteArray(estCount)
        val bs = ByteArray(estCount)
        var n = 0

        if (isBinaryLe) {
            val bb = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            fun readFloat(base: Int, p: Prop): Float = when (p.type) {
                "float", "float32" -> bb.getFloat(base + p.offset)
                "double", "float64" -> bb.getDouble(base + p.offset).toFloat()
                else -> 0f
            }
            fun readByte(base: Int, p: Prop?): Byte {
                if (p == null) return 0xD8.toByte()
                return when (p.type) {
                    "uchar", "uint8", "char", "int8" -> data[base + p.offset]
                    "float", "float32" -> (bb.getFloat(base + p.offset).coerceIn(0f, 1f) * 255f).toInt().toByte()
                    else -> 0xD8.toByte()
                }
            }
            var i = 0
            while (i < vertexCount && n < estCount) {
                val base = payloadStart + i * stride
                if (base + stride > data.size) break
                val x = readFloat(base, px)
                val y = readFloat(base, py)
                val z = readFloat(base, pz)
                if (x.isFinite() && y.isFinite() && z.isFinite()) {
                    xs[n] = x; ys[n] = y; zs[n] = z
                    rs[n] = readByte(base, pr)
                    gs[n] = readByte(base, pg)
                    bs[n] = readByte(base, pb)
                    n++
                }
                i += step
            }
        } else {
            val ix = props.indexOf(px); val iy = props.indexOf(py); val iz = props.indexOf(pz)
            val ir = pr?.let { props.indexOf(it) } ?: -1
            val ig = pg?.let { props.indexOf(it) } ?: -1
            val ib = pb?.let { props.indexOf(it) } ?: -1
            val body = String(data, payloadStart, data.size - payloadStart, Charsets.US_ASCII)
            var idx = 0
            for (line in body.lineSequence()) {
                if (idx >= vertexCount || n >= estCount) break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (idx % step == 0) {
                    val cols = trimmed.split(Regex("\\s+"))
                    val x = cols.getOrNull(ix)?.toFloatOrNull()
                    val y = cols.getOrNull(iy)?.toFloatOrNull()
                    val z = cols.getOrNull(iz)?.toFloatOrNull()
                    if (x != null && y != null && z != null && x.isFinite() && y.isFinite() && z.isFinite()) {
                        xs[n] = x; ys[n] = y; zs[n] = z
                        rs[n] = (cols.getOrNull(ir)?.toIntOrNull() ?: 216).coerceIn(0, 255).toByte()
                        gs[n] = (cols.getOrNull(ig)?.toIntOrNull() ?: 216).coerceIn(0, 255).toByte()
                        bs[n] = (cols.getOrNull(ib)?.toIntOrNull() ?: 216).coerceIn(0, 255).toByte()
                        n++
                    }
                }
                idx++
            }
        }
        if (n == 0) return@runCatching null

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until n) {
            if (xs[i] < minX) minX = xs[i]; if (xs[i] > maxX) maxX = xs[i]
            if (ys[i] < minY) minY = ys[i]; if (ys[i] > maxY) maxY = ys[i]
            if (zs[i] < minZ) minZ = zs[i]; if (zs[i] > maxZ) maxZ = zs[i]
        }
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val cz = (minZ + maxZ) * 0.5f
        val span = maxOf(maxX - minX, maxY - minY, maxZ - minZ).coerceAtLeast(1e-3f)
        // Adaptive splat radius proportional to cloud extent / sqrt(point density).
        val ptScale = (span / kotlin.math.sqrt(n.toFloat()).coerceAtLeast(20f) * 0.85f)
            .coerceIn(span * 0.0008f, span * 0.015f)

        val buf = java.nio.ByteBuffer.allocate(n * 32).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            buf.putFloat(xs[i] - cx)
            buf.putFloat(-(zs[i] - cz))
            buf.putFloat(-(ys[i] - cy))
            buf.putFloat(ptScale); buf.putFloat(ptScale); buf.putFloat(ptScale)
            buf.put(rs[i]); buf.put(gs[i]); buf.put(bs[i]); buf.put(0xFF.toByte())
            buf.put(128.toByte()); buf.put(128.toByte()); buf.put(128.toByte()); buf.put(0xFF.toByte())
        }
        val tmp = File(outDir, "$key.part")
        tmp.writeBytes(buf.array())
        tmp.renameTo(out)
        out
    }.onFailure { Log.w(TAG, "PLY -> .splat conversion failed for ${ply.name}", it) }.getOrNull()

    private fun textAssetMime(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
        "js", "mjs" -> "text/javascript"
        "html" -> "text/html"
        "css" -> "text/css"
        "json" -> "application/json"
        else -> null
    }

    companion object {
        private const val TAG = "SplatViewer"
        const val EXTRA_FILE = "splat_file"
        const val EXTRA_PATH = "splat_path"
        const val EXTRA_TITLE = "splat_title"
    }
}
