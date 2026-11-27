package cn.easyar.samples.helloar

import android.content.Context

// Настройки пазла. Хранятся в SharedPreferences; AR-сессия читает их при запуске и при возврате на экран.
class PuzzleSettings(
    val grid: Int,              // пазл grid x grid
    val dim: Float              // затемнение под плитками, от 0 (нет) до 1 (чёрное)
) {
    companion object {
        const val MIN_GRID = 3
        const val MAX_GRID = 5
        const val DEFAULT_GRID = 3
        const val DEFAULT_DIM = 0.75f

        private const val PREFS = "puzzle_settings"
        private const val KEY_GRID = "grid"
        private const val KEY_DIM = "dim"

        fun load(context: Context): PuzzleSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return PuzzleSettings(
                prefs.getInt(KEY_GRID, DEFAULT_GRID).coerceIn(MIN_GRID, MAX_GRID),
                prefs.getFloat(KEY_DIM, DEFAULT_DIM).coerceIn(0f, 1f))
        }

        fun save(context: Context, settings: PuzzleSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_GRID, settings.grid)
                .putFloat(KEY_DIM, settings.dim)
                .apply()
        }
    }
}
