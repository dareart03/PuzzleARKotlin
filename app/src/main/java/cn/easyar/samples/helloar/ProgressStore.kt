package cn.easyar.samples.helloar

import android.content.Context
import android.content.SharedPreferences

// Прогресс игрока: какие пазлы уже собраны (по ним открываются фигурки в коллекции).
// В отличие от истории, не стирается кнопкой «Очистить».
class ProgressStore(private val prefs: SharedPreferences) {
    companion object {
        private const val PREFS = "progress"
        private const val KEY_COLLECTED = "collected"
        private val LOCK = Any()

        fun forContext(context: Context): ProgressStore =
            ProgressStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }

    // копия набора: изменять набор, полученный из SharedPreferences, нельзя
    fun collected(): Set<String> = synchronized(LOCK) {
        HashSet(prefs.getStringSet(KEY_COLLECTED, null) ?: emptySet<String>())
    }

    fun markCollected(id: String) {
        synchronized(LOCK) {
            val set = HashSet(prefs.getStringSet(KEY_COLLECTED, null) ?: emptySet<String>())
            if (set.add(id)) {
                prefs.edit().putStringSet(KEY_COLLECTED, set).apply()
            }
        }
    }
}
