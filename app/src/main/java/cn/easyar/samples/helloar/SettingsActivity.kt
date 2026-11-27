package cn.easyar.samples.helloar

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView

// Экран настроек пазла. Каждое изменение сразу сохраняется; AR-сессия подхватывает его при возврате.
// Если экран открыт из AR, на нём есть кнопка возврата в главное меню.
class SettingsActivity : Activity() {
    companion object {
        private const val EXTRA_FROM_AR = "from_ar"

        // открыть настройки из AR-сессии
        fun openFromAr(from: Activity) {
            from.startActivity(Intent(from, SettingsActivity::class.java).putExtra(EXTRA_FROM_AR, true))
        }
    }

    private var grid = PuzzleSettings.DEFAULT_GRID
    private var dim = PuzzleSettings.DEFAULT_DIM

    private fun save() {
        PuzzleSettings.save(this, PuzzleSettings(grid, dim))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val current = PuzzleSettings.load(this)
        grid = current.grid
        dim = current.dim

        val radio = findViewById<RadioGroup>(R.id.radio_grid)
        radio.check(when (grid) {
            4 -> R.id.radio_grid_4
            5 -> R.id.radio_grid_5
            else -> R.id.radio_grid_3
        })
        radio.setOnCheckedChangeListener { _, checkedId ->
            grid = when (checkedId) {
                R.id.radio_grid_4 -> 4
                R.id.radio_grid_5 -> 5
                else -> 3
            }
            save()
        }

        val dimValue = findViewById<TextView>(R.id.text_dim_value)
        val seek = findViewById<SeekBar>(R.id.seek_dim)
        seek.progress = Math.round(dim * 100)
        dimValue.text = getString(R.string.settings_dim_value, seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                dim = progress / 100f
                dimValue.text = getString(R.string.settings_dim_value, progress)
                if (fromUser) {
                    save()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<View>(R.id.button_back).setOnClickListener {
            finish()
        }

        val toMenu = findViewById<View>(R.id.button_to_menu)
        if (intent.getBooleanExtra(EXTRA_FROM_AR, false)) {
            toMenu.setOnClickListener {
                // CLEAR_TOP закрывает всё, что лежит над главным меню: настройки и AR-сессию
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        } else {
            toMenu.visibility = View.GONE
        }
    }
}
