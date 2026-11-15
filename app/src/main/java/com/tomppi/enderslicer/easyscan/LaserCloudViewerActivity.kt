package com.tomppi.enderslicer.easyscan

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import com.tomppi.enderslicer.splat.SplatLibrary
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Native viewer for laser scans: the EasyScanner on-phone cloud renderer.
 * Opens a scanner PLY or one of our .splat files from the splat library folder.
 */
class LaserCloudViewerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_FILE) ?: run { finish(); return }
        val file = File(SplatLibrary.directory(this), name)
        val root = FrameLayout(this)
        val view = CloudView(this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        val bar = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            textSize = 13f
            setPadding(24, 24, 24, 24)
            text = name + " - loading"
        }
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP))
        setContentView(root)
        Thread {
            val cloud = try { load(file) } catch (ex: Exception) { null }
            runOnUiThread {
                if (cloud == null) {
                    bar.text = name + " - could not read"
                } else {
                    bar.text = name + " - " + cloud.count + " points - double-tap to reframe"
                    view.show(cloud)
                }
            }
        }.start()
    }

    private fun load(file: File): PlyCloud? = when (file.extension.lowercase()) {
        "ply" -> PlyCloud.read(file, MAX_POINTS)
        "splat" -> readSplat(file)
        else -> null
    }

    /** 32-byte .splat rows: position xyz float, scale xyz, RGBA bytes, quaternion wxyz. */
    private fun readSplat(file: File): PlyCloud? {
        val bytes = file.readBytes()
        if (bytes.size < 32) return null
        val n = bytes.size / 32
        val stride = maxOf(1, n / MAX_POINTS)
        val keep = (n + stride - 1) / stride
        val xyz = FloatArray(keep * 3)
        val rgb = ByteArray(keep * 3)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var k = 0
        var i = 0
        while (i < n && k < keep) {
            val o = i * 32
            val x = bb.getFloat(o); val y = bb.getFloat(o + 4); val z = bb.getFloat(o + 8)
            // our .splat is Y-up; the renderer is Z-up: (x, z, y)
            xyz[k * 3] = x
            xyz[k * 3 + 1] = z
            xyz[k * 3 + 2] = y
            rgb[k * 3] = bytes[o + 24]
            rgb[k * 3 + 1] = bytes[o + 25]
            rgb[k * 3 + 2] = bytes[o + 26]
            k++
            i += stride
        }
        return PlyCloud(xyz.copyOf(k * 3), rgb.copyOf(k * 3), n)
    }

    companion object {
        const val EXTRA_FILE = "file"
        private const val MAX_POINTS = 400_000
    }
}
