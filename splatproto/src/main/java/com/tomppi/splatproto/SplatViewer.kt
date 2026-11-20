package com.tomppi.splatproto

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import java.io.File

/**
 * Splat viewer based on the Aholo viewer (manycoretech/aholo-viewer), served from app assets.
 *
 * viewer.js fetches the trained .ply through a WebViewAssetLoader path handler, and the Aholo
 * viewer itself provides orbit/pinch/pan camera controls plus its own settings panel (splat
 * scale, render options, reset), so no custom renderer is involved here.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun SplatViewer(ply: File, modifier: Modifier = Modifier) {
    key(ply.absolutePath) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                val assetLoader = WebViewAssetLoader.Builder()
                    // Explicitly register the asset handler: the page itself is loaded through
                    // this loader (appassets.androidplatform.net), so a missing /assets/ handler
                    // shows up as net::ERR_CACHE_MISS.
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .addPathHandler(
                        "/splat/",
                        WebViewAssetLoader.InternalStoragePathHandler(
                            context,
                            ply.parentFile ?: context.filesDir,
                        ),
                    )
                    .build()
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(Color.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ) = request?.url?.let { assetLoader.shouldInterceptRequest(it) }
                    }
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun log(message: String) {
                                Log.i("SplatViewer", message)
                            }
                        },
                        "SplatHost",
                    )
                    val page = Uri.parse(
                        "https://appassets.androidplatform.net/assets/splatviewer/index.html",
                    ).buildUpon()
                        .appendQueryParameter("splat", "/splat/" + ply.name)
                        .appendQueryParameter("name", ply.name)
                        .build()
                        .toString()
                    loadUrl(page)
                }
            },
            onRelease = { webView ->
                webView.stopLoading()
                webView.destroy()
            },
        )
    }
}
