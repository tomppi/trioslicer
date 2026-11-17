package com.rawmechatronics.easyscanner

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.rawmechatronics.easyscanner.databinding.ActivityPhotoBinding
import java.io.File
import java.util.concurrent.Executors

/** One session photo full screen: tap the right side for the next, the left for the previous, the middle to close. */
class PhotoActivity : AppCompatActivity() {
    private lateinit var b: ActivityPhotoBinding
    private lateinit var photos: List<File>
    private var index = 0
    private var rotation = 0
    private val decoder = Executors.newSingleThreadExecutor()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPhotoBinding.inflate(layoutInflater)
        setContentView(b.root)
        padBelowBars(b.root)
        photos = intent.getStringExtra(EXTRA_DIR)?.let { ScanLibrary.photos(File(it)) } ?: emptyList()
        if (photos.isEmpty()) {
            finish()
            return
        }
        rotation = intent.getIntExtra(EXTRA_ROTATION, 0)
        index = (savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0)).coerceIn(photos.indices)
        b.image.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                when {
                    e.x > v.width * 2 / 3 -> show(index + 1)
                    e.x < v.width / 3 -> show(index - 1)
                    else -> finish()
                }
            }
            true
        }
        show(index)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
    }

    override fun onDestroy() {
        super.onDestroy()
        decoder.shutdownNow()
    }

    private fun show(i: Int) {
        if (i !in photos.indices) return
        index = i
        b.counter.text = getString(R.string.photo_counter, i + 1, photos.size, photos[i].name)
        val px = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        decoder.execute {
            val bmp = Photos.decode(photos[i], px, rotation)
            runOnUiThread { if (index == i && !isDestroyed) b.image.setImageBitmap(bmp) }
        }
    }

    companion object {
        const val EXTRA_DIR = "dir"
        const val EXTRA_INDEX = "index"
        const val EXTRA_ROTATION = "rotation"
    }
}
