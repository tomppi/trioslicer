package com.tomppi.splatproto

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Thin JNI bridge to Brush's own renderer core (render_splats + Camera), no Brush UI. */
object BrushRenderer {
    init {
        System.loadLibrary("brush_jni")
    }

    external fun nativeLoad(path: String): Boolean

    /** Orbit/pan/zoom + render options. Returns packed RGBA8 bytes. */
    external fun nativeRender(
        width: Int,
        height: Int,
        deltaYaw: Float,
        deltaPitch: Float,
        panX: Float,
        panY: Float,
        zoom: Float,
        splatScale: Float,
        bgR: Float,
        bgG: Float,
        bgB: Float,
    ): ByteArray?

    external fun nativeResetView()

    external fun nativeSetUpFlip(flip: Boolean)

    external fun nativeViewerCenter(): String
}

/**
 * Orbitable splat view rendered by Brush's GPU renderer. Gestures match the previous viewer:
 * drag = orbit, pinch = zoom, two-finger drag = pan, double-tap = reset. A small panel exposes
 * viewer settings (splat scale, background, scene up axis, reset).
 */
@Composable
fun BrushViewer(ply: File) {
    var loaded by remember(ply) { mutableStateOf(false) }
    var failed by remember(ply) { mutableStateOf(false) }
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var pendingYaw by remember { mutableStateOf(0f) }
    var pendingPitch by remember { mutableStateOf(0f) }
    var pendingPanX by remember { mutableStateOf(0f) }
    var pendingPanY by remember { mutableStateOf(0f) }
    var pendingZoom by remember { mutableStateOf(1f) }
    var renderJob by remember { mutableStateOf<Job?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var splatScale by remember { mutableStateOf(0f) }
    var backgroundColor by remember { mutableStateOf(0) }
    var flipUp by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun requestRender(force: Boolean = false) {
        if (!loaded || viewSize.width <= 0 || viewSize.height <= 0) return
        renderJob?.cancel()
        renderJob = scope.launch {
            val yaw = pendingYaw.also { pendingYaw = 0f }
            val pitch = pendingPitch.also { pendingPitch = 0f }
            val panX = pendingPanX.also { pendingPanX = 0f }
            val panY = pendingPanY.also { pendingPanY = 0f }
            val zoom = pendingZoom.also { pendingZoom = 1f }
            val size = viewSize
            if (size.width <= 0 || size.height <= 0) return@launch
            val bg = when (backgroundColor) {
                1 -> floatArrayOf(0.10f, 0.12f, 0.16f)
                2 -> floatArrayOf(1f, 1f, 1f)
                else -> floatArrayOf(0f, 0f, 0f)
            }
            val pixels = withContext(Dispatchers.Default) {
                BrushRenderer.nativeRender(
                    size.width, size.height, yaw, pitch, panX, panY, zoom,
                    splatScale, bg[0], bg[1], bg[2],
                )
            } ?: return@launch
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels).order(ByteOrder.nativeOrder()))
            frame = bitmap.asImageBitmap()
        }
    }

    LaunchedEffect(ply) {
        val ok = withContext(Dispatchers.Default) { BrushRenderer.nativeLoad(ply.absolutePath) }
        loaded = ok
        failed = !ok
        if (ok) {
            info = withContext(Dispatchers.Default) { BrushRenderer.nativeViewerCenter() }
            requestRender()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size ->
                if (size != viewSize) {
                    viewSize = size
                    if (loaded) requestRender()
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    BrushRenderer.nativeResetView()
                    pendingYaw = 0f; pendingPitch = 0f
                    pendingPanX = 0f; pendingPanY = 0f; pendingZoom = 1f
                    requestRender(force = true)
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        if (pressed >= 2) {
                            if (kotlin.math.abs(zoom - 1f) > 0.001f) {
                                pendingZoom *= (1f / zoom).coerceIn(0.5f, 2f)
                            } else if (pan.x != 0f || pan.y != 0f) {
                                pendingPanX += pan.x / size.width
                                pendingPanY += pan.y / size.height
                            }
                        } else {
                            pendingYaw += pan.x * 0.008f
                            pendingPitch += pan.y * 0.008f
                        }
                        event.changes.forEach { it.consume() }
                        requestRender()
                    }
                }
            },
    ) {
        val image = frame
        if (image != null) {
            Image(bitmap = image, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        if (failed) {
            Text("Could not load splat", color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else if (!loaded) {
            Text("Loading splat...", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { showSettings = !showSettings }) { Text("View") }
            Button(onClick = {
                BrushRenderer.nativeResetView()
                pendingYaw = 0f; pendingPitch = 0f
                pendingPanX = 0f; pendingPanY = 0f; pendingZoom = 1f
                requestRender(force = true)
            }) { Text("Reset") }
        }

        if (showSettings) {
            Surface(
                color = Color(0xE6141820),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 10.dp)
                    .width(300.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Splat scale  " + if (splatScale <= 0f) "auto" else "%.2f".format(splatScale), color = Color.White, fontSize = 13.sp)
                    Slider(
                        value = splatScale,
                        onValueChange = { splatScale = it; requestRender() },
                        valueRange = 0f..3f,
                    )
                    Text("Background", color = Color.White, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { backgroundColor = 0; requestRender() }) { Text("Black") }
                        Button(onClick = { backgroundColor = 1; requestRender() }) { Text("Slate") }
                        Button(onClick = { backgroundColor = 2; requestRender() }) { Text("White") }
                    }
                    Text("Scene up axis", color = Color.White, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            flipUp = false
                            BrushRenderer.nativeSetUpFlip(false)
                            requestRender(force = true)
                        }) { Text("Y-up") }
                        Button(onClick = {
                            flipUp = true
                            BrushRenderer.nativeSetUpFlip(true)
                            requestRender(force = true)
                        }) { Text("Flip") }
                    }
                    if (info.isNotEmpty()) Text(info, color = Color(0xFFB0B8C8), fontSize = 11.sp)
                    Text(
                        "drag orbit · pinch zoom · two-finger drag pan · double-tap reset",
                        color = Color(0xFFB0B8C8),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}
