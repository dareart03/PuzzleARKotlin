package cn.easyar.samples.helloar.ui

import cn.easyar.samples.helloar.ar.*
import cn.easyar.samples.helloar.puzzle.*
import cn.easyar.samples.helloar.model.*
import cn.easyar.samples.helloar.render.*
import cn.easyar.samples.helloar.data.*
import cn.easyar.samples.helloar.util.*
import cn.easyar.samples.helloar.R

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Экран истории: собранные пазлы и сохранённые фото, новые сверху. Тап по фото открывает его на весь экран.
class HistoryActivity : Activity() {
    companion object {
        fun open(from: Activity) {
            from.startActivity(Intent(from, HistoryActivity::class.java))
        }
    }

    private lateinit var store: HistoryStore
    private lateinit var adapter: HistoryAdapter
    private lateinit var fullPhoto: ImageView
    private var items: List<HistoryEntry> = emptyList()
    private val thumbnails = LruCache<String, Bitmap>(30)
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }

    private fun thumbnail(path: String): Bitmap? {
        var bitmap = thumbnails.get(path)
        if (bitmap == null) {
            val options = BitmapFactory.Options()
            options.inSampleSize = 8
            bitmap = BitmapFactory.decodeFile(path, options)
            if (bitmap != null) {
                thumbnails.put(path, bitmap)
            }
        }
        return bitmap
    }

    private inner class HistoryAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_history, parent, false)
            val entry = items[position]
            val thumb = view.findViewById<ImageView>(R.id.history_thumb)
            val path = entry.photoPath
            if (entry.kind == HistoryEntry.KIND_PHOTO && path != null) {
                thumb.visibility = View.VISIBLE
                thumb.setImageBitmap(thumbnail(path))
            } else {
                thumb.visibility = View.GONE
                thumb.setImageDrawable(null)
            }
            view.findViewById<TextView>(R.id.history_title).setText(when {
                entry.kind == HistoryEntry.KIND_PHOTO && entry.solved -> R.string.history_photo_solved
                entry.kind == HistoryEntry.KIND_PHOTO -> R.string.history_photo
                else -> R.string.history_result
            })
            view.findViewById<TextView>(R.id.history_details).text = getString(R.string.history_details,
                dateFormat.format(Date(entry.timeMs)), entry.grid, formatTime(entry.elapsedMs), entry.moves)
            return view
        }
    }

    private fun reload() {
        items = store.load()
        adapter.notifyDataSetChanged()
    }

    private fun showPhoto(entry: HistoryEntry) {
        val path = entry.photoPath ?: return
        val options = BitmapFactory.Options()
        options.inSampleSize = 2
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return
        fullPhoto.setImageBitmap(bitmap)
        fullPhoto.visibility = View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        store = HistoryStore.forContext(this)

        fullPhoto = findViewById(R.id.full_photo)
        fullPhoto.setOnClickListener {
            fullPhoto.visibility = View.GONE
            fullPhoto.setImageDrawable(null)
        }

        adapter = HistoryAdapter()
        val list = findViewById<ListView>(R.id.list_history)
        list.emptyView = findViewById(R.id.text_empty)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            showPhoto(items[position])
        }

        findViewById<View>(R.id.button_back).setOnClickListener {
            finish()
        }
        findViewById<View>(R.id.button_clear).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.history_clear_confirm)
                .setPositiveButton(R.string.button_clear) { _, _ ->
                    store.clear()
                    thumbnails.evictAll()
                    reload()
                }
                .setNegativeButton(R.string.button_cancel, null)
                .show()
        }
        reload()
    }
}
