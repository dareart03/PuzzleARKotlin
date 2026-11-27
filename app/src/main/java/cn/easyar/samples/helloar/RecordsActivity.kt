package cn.easyar.samples.helloar

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Отдельный экран рекордов выбранной фигурки. */
class RecordsActivity : Activity() {

    companion object {
        private const val EXTRA_PUZZLE_ID = "puzzle_id"

        fun open(from: Activity, puzzleId: String) {
            val intent = Intent(from, RecordsActivity::class.java)
                .putExtra(EXTRA_PUZZLE_ID, puzzleId)
            from.startActivity(intent)
        }
    }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var modelBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_records)

        // Добавляем нижний системный inset к отступу корневого контейнера,
        // чтобы закреплённая кнопка не попадала под системную навигацию.
        val root = findViewById<View>(R.id.records_root)
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

        findViewById<View>(R.id.button_records_back).setOnClickListener {
            finish()
        }

        val puzzleId = intent.getStringExtra(EXTRA_PUZZLE_ID)
        if (puzzleId.isNullOrBlank()) {
            finish()
            return
        }

        val titleView = findViewById<TextView>(R.id.records_character_name)
        val imageView = findViewById<ImageView>(R.id.records_character_image)
        titleView.text = puzzleId.replaceFirstChar { it.uppercase() }

        // Рекорды показываются для всех трёх размеров, даже если пазл ещё не проходили.
        val recordStore = RecordStore.forContext(this)
        val rows = listOf(
            Triple(3, R.id.record_3_time, R.id.record_3_moves),
            Triple(4, R.id.record_4_time, R.id.record_4_moves),
            Triple(5, R.id.record_5_time, R.id.record_5_moves)
        )

        for ((grid, timeId, movesId) in rows) {
            val record = recordStore.get(puzzleId, grid)

            findViewById<TextView>(timeId).text = record?.bestTimeMs
                ?.takeIf { it > 0L }
                ?.let { formatTime(it) }
                ?: "??:??"

            findViewById<TextView>(movesId).text = record?.bestMoves
                ?.takeIf { it > 0 }
                ?.toString()
                ?: "?"
        }

        val def = PuzzleCatalog.load(assets).firstOrNull { it.id == puzzleId }
        if (def != null && def.modelPath != null) {
            worker.execute {
                val thumbnails = CollectionThumbnails(this, assets)
                val title = thumbnails.info(def).title
                    ?: puzzleId.replaceFirstChar { it.uppercase() }
                val bitmap = thumbnails.get(def)

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        titleView.text = title
                        if (bitmap != null) {
                            modelBitmap = bitmap
                            imageView.setImageBitmap(bitmap)
                        }
                    } else {
                        bitmap?.recycle()
                    }
                }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000L
        return String.format(
            Locale.US,
            "%d:%02d",
            totalSeconds / 60L,
            totalSeconds % 60L
        )
    }

    override fun onDestroy() {
        worker.shutdownNow()
        // Bitmap кэшируется в файле; не recycle, чтобы не затронуть ImageView.
        modelBitmap = null
        super.onDestroy()
    }
}
