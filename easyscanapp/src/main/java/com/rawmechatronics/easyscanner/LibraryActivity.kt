package com.rawmechatronics.easyscanner

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.rawmechatronics.easyscanner.databinding.ActivityLibraryBinding

/** The scans on this phone, newest first; a tap opens the cloud and the photos. */
class LibraryActivity : AppCompatActivity() {
    private lateinit var b: ActivityLibraryBinding
    private var items: List<ScanLibrary.Item> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        padBelowBars(b.root)
        b.list.adapter = adapter
        b.list.setOnItemClickListener { _, _, pos, _ ->
            val it = items[pos]
            startActivity(Intent(this, ScanViewActivity::class.java).apply {
                putExtra(ScanViewActivity.EXTRA_NAME, it.name)
                it.dir?.let { d -> putExtra(ScanViewActivity.EXTRA_DIR, d.absolutePath) }
                it.cloud?.let { c -> putExtra(ScanViewActivity.EXTRA_CLOUD, c.absolutePath) }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        val files = getExternalFilesDir(null) ?: return
        Thread {
            val found = ScanLibrary.list(files)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                items = found
                adapter.notifyDataSetChanged()
                b.empty.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val it = items[position]
            v.findViewById<TextView>(android.R.id.text1).text = it.name
            val parts = mutableListOf<String>(
                DateUtils.getRelativeTimeSpanString(it.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            )
            if (it.cloud != null) parts += it.points?.let { n -> getString(R.string.library_points, n) } ?: getString(R.string.library_cloud)
            if (it.photos.isNotEmpty()) parts += resources.getQuantityString(R.plurals.photos, it.photos.size, it.photos.size)
            v.findViewById<TextView>(android.R.id.text2).text = parts.joinToString(" · ")
            return v
        }
    }
}
