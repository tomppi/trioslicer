package com.rawmechatronics.easyscanner

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.rawmechatronics.easyscanner.databinding.ActivityScanViewBinding
import java.io.File
import java.util.concurrent.Executors

/** One scan: its point cloud on top (drag to orbit, pinch to zoom) and the session's photos below. */
class ScanViewActivity : AppCompatActivity() {
    private lateinit var b: ActivityScanViewBinding
    private var photos: List<File> = emptyList()
    private var rotation = 0
    private val decoder = Executors.newSingleThreadExecutor()
    private val thumbs = object : LruCache<Int, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityScanViewBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        padBelowBars(b.root)
        title = intent.getStringExtra(EXTRA_NAME) ?: getString(R.string.library)
        val dir = intent.getStringExtra(EXTRA_DIR)?.let(::File)
        val cloud = intent.getStringExtra(EXTRA_CLOUD)?.let(::File)

        if (cloud == null) b.cloudInfo.setText(R.string.cloud_none)
        else {
            b.cloudInfo.setText(R.string.cloud_loading)
            Thread {
                val result = runCatching { PlyCloud.read(cloud, MAX_POINTS) }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    result.onSuccess { c ->
                        b.cloud.show(c)
                        b.cloudInfo.text = if (c.count < c.total) getString(R.string.cloud_points_thinned, c.total, c.count)
                            else getString(R.string.library_points, c.total)
                    }.onFailure { e -> b.cloudInfo.text = getString(R.string.cloud_failed, e.message ?: e.javaClass.simpleName) }
                }
            }.start()
        }

        photos = dir?.let(ScanLibrary::photos) ?: emptyList()
        if (photos.isEmpty()) {
            b.photosTitle.visibility = View.GONE
            b.photos.visibility = View.GONE   // the cloud's weighted frame takes the whole screen
        } else {
            b.photosTitle.text = resources.getQuantityString(R.plurals.photos, photos.size, photos.size)
            Thread {
                rotation = Photos.rotation(this, dir!!, photos.first())
                runOnUiThread { b.photos.adapter = adapter }
            }.start()
            b.photos.setOnItemClickListener { _, _, pos, _ ->
                startActivity(Intent(this, PhotoActivity::class.java).apply {
                    putExtra(PhotoActivity.EXTRA_DIR, dir!!.absolutePath)
                    putExtra(PhotoActivity.EXTRA_INDEX, pos)
                    putExtra(PhotoActivity.EXTRA_ROTATION, rotation)
                })
            }
        }
    }

    override fun onPause() {
        super.onPause()
        b.cloud.onPause()
    }

    override fun onResume() {
        super.onResume()
        b.cloud.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        decoder.shutdownNow()
        thumbs.evictAll()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = photos.size
        override fun getItem(position: Int) = photos[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = (convertView as? ImageView) ?: ImageView(this@ScanViewActivity).apply {
                val side = b.photos.columnWidth
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, side)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF15181A.toInt())
            }
            v.tag = position
            val cached = thumbs.get(position)
            v.setImageBitmap(cached)
            if (cached == null) decoder.execute {
                val bmp = Photos.decode(photos[position], THUMB_PX, rotation) ?: return@execute
                thumbs.put(position, bmp)
                v.post { if (v.tag == position) v.setImageBitmap(bmp) }
            }
            return v
        }
    }

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_DIR = "dir"
        const val EXTRA_CLOUD = "cloud"
        /** Enough for a phone screen; bigger clouds are thinned by a stride. */
        const val MAX_POINTS = 1_500_000
        const val THUMB_PX = 320
    }
}
