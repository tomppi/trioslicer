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

        val fileName = intent.getStringExtra(EXTRA_FILE)
        val file = fileName?.let { File(SplatLibrary.directory(this), it) }
        if (file == null || !file.isFile || !SplatLibrary.isSupported(file.name)) {
            Toast.makeText(this, "That splat is no longer here.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: SplatLibrary.titleOf(file)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SplatViewerScreen(title = title, file = file, onClose = { finish() })
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
                factory = { context -> splatWebView(context, file) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }

    private fun splatWebView(context: android.content.Context, file: File): WebView {
        val assets = WebViewAssetLoader.AssetsPathHandler(this)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.PathHandler { path ->
                val response = assets.handle(path) ?: return@PathHandler null
                val mime = textAssetMime(path) ?: return@PathHandler response
                WebResourceResponse(mime, response.encoding ?: "utf-8", response.data)
            })
            .addPathHandler("/splats/", WebViewAssetLoader.PathHandler { path ->
                splatResponse(File(SplatLibrary.directory(this), path))
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
                        "&name=" + android.net.Uri.encode(SplatLibrary.titleOf(file)),
                )
            }
        }
    }

    /** The response for one scene file, or null when the name points outside the folder. */
    private fun splatResponse(file: File): WebResourceResponse? {
        val directory = SplatLibrary.directory(this).canonicalFile
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!canonical.path.startsWith(directory.path + File.separator)) return null
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
        const val EXTRA_TITLE = "splat_title"
    }
}
