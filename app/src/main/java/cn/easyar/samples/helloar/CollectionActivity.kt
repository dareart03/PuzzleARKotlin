package cn.easyar.samples.helloar

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Коллекция фигурок: собранные пазлы показаны в цвете, остальные — тёмным силуэтом.
class CollectionActivity : Activity() {

    companion object {
        private const val SILHOUETTE_COLOR = 0xFF1B2430.toInt()

        fun open(from: Activity) {
            from.startActivity(Intent(from, CollectionActivity::class.java))
        }
    }

    private class Item(val def: PuzzleDef) {
        var title: String = def.id

        @Volatile
        var bitmap: Bitmap? = null
    }

    private lateinit var progress: ProgressStore
    private lateinit var adapter: CollectionAdapter
    private lateinit var progressText: TextView
    private var items: List<Item> = emptyList()
    private var collected: Set<String> = emptySet()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    private inner class CollectionAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: layoutInflater.inflate(R.layout.item_collection, parent, false)

            val item = items[position]
            val image = view.findViewById<ImageView>(R.id.collection_image)
            val name = view.findViewById<TextView>(R.id.collection_name)
            val recordsButton = view.findViewById<Button>(R.id.button_record_table)

            image.setImageBitmap(item.bitmap)

            if (item.def.id in collected) {
                image.clearColorFilter()
                name.text = item.title
            } else {
                // Тёмный силуэт: цвет накладывается только на непрозрачные пиксели.
                image.setColorFilter(SILHOUETTE_COLOR, PorterDuff.Mode.SRC_ATOP)
                name.setText(R.string.collection_locked)
            }

            // Таблица рекордов доступна только для уже собранных фигурок.
            // Для закрытых фигурок кнопка остаётся видимой, но выглядит неактивной.
            val isCollected = item.def.id in collected
            recordsButton.visibility = View.VISIBLE
            recordsButton.isEnabled = isCollected
            recordsButton.isClickable = isCollected
            recordsButton.alpha = if (isCollected) 1.0f else 0.45f
            recordsButton.setOnClickListener {
                if (item.def.id in collected) {
                    RecordsActivity.open(this@CollectionActivity, item.def.id)
                }
            }

            return view
        }
    }

    private fun refreshProgress() {
        collected = progress.collected()
        progressText.text = getString(
            R.string.collection_progress,
            items.count { it.def.id in collected },
            items.size
        )
        adapter.notifyDataSetChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collection)

        // Учитываем высоту системной панели навигации, чтобы кнопка «Назад»
        // всегда оставалась выше неё, в том числе в edge-to-edge режиме.
        val root = findViewById<View>(R.id.collection_root)
        val initialBottomPadding = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                initialBottomPadding + insets.systemWindowInsetBottom
            )
            insets
        }
        root.requestApplyInsets()

        progress = ProgressStore.forContext(this)
        progressText = findViewById(R.id.text_progress)

        items = PuzzleCatalog.load(assets)
            .filter { it.modelPath != null }
            .map { Item(it) }

        adapter = CollectionAdapter()

        val grid = findViewById<GridView>(R.id.grid_collection)
        grid.emptyView = findViewById(R.id.text_empty)
        grid.adapter = adapter

        findViewById<View>(R.id.button_back).setOnClickListener {
            finish()
        }

        refreshProgress()

        // Миниатюры готовятся в фоне и появляются по мере загрузки.
        val thumbnails = CollectionThumbnails(this, assets)
        for (item in items) {
            worker.execute {
                item.title = thumbnails.info(item.def).title
                    ?: item.def.id.replaceFirstChar { it.uppercase() }
                item.bitmap = thumbnails.get(item.def)

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::progress.isInitialized && ::adapter.isInitialized) {
            refreshProgress()
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
