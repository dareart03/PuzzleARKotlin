package cn.easyar.samples.helloar.data

import cn.easyar.samples.helloar.ui.*
import cn.easyar.samples.helloar.ar.*
import cn.easyar.samples.helloar.puzzle.*
import cn.easyar.samples.helloar.model.*
import cn.easyar.samples.helloar.render.*
import cn.easyar.samples.helloar.util.*

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

/** Лучшие результаты отдельно для каждой пары «изображение + размер сетки». */
data class PuzzleRecord(val bestTimeMs: Long, val bestMoves: Int)
data class RecordUpdate(val record: PuzzleRecord, val isNewTimeRecord: Boolean, val isNewMovesRecord: Boolean) {
    val isNewRecord: Boolean get() = isNewTimeRecord || isNewMovesRecord
}

class RecordStore(private val prefs: SharedPreferences) {
    companion object {
        private const val PREFS = "puzzle_records"
        private val LOCK = Any()
        fun forContext(context: Context): RecordStore = RecordStore(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        )
        private fun prefix(target: String, grid: Int): String {
            val encoded = Base64.encodeToString(target.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
            return "${encoded}_$grid"
        }
    }

    fun get(target: String, grid: Int): PuzzleRecord? = synchronized(LOCK) {
        val p = prefix(target, grid)
        val time = prefs.getLong("${p}_time", 0L)
        val moves = prefs.getInt("${p}_moves", 0)
        if (time <= 0L && moves <= 0) null else PuzzleRecord(time, moves)
    }

    /** Обновляет оба рекорда независимо. Возвращает, какие показатели улучшены этим прохождением. */
    fun update(target: String, grid: Int, elapsedMs: Long, moves: Int): RecordUpdate = synchronized(LOCK) {
        val p = prefix(target, grid)
        val oldTime = prefs.getLong("${p}_time", 0L)
        val oldMoves = prefs.getInt("${p}_moves", 0)
        val newTime = elapsedMs > 0L && (oldTime == 0L || elapsedMs < oldTime)
        val newMoves = moves > 0 && (oldMoves == 0 || moves < oldMoves)
        val bestTime = if (newTime) elapsedMs else oldTime
        val bestMoves = if (newMoves) moves else oldMoves
        if (newTime || newMoves) {
            prefs.edit()
                .putLong("${p}_time", bestTime)
                .putInt("${p}_moves", bestMoves)
                .apply()
        }
        RecordUpdate(PuzzleRecord(bestTime, bestMoves), newTime, newMoves)
    }

    /** Рекорды для всех размеров сетки, где игрок уже проходил этот пазл. */
    fun summary(target: String, grids: IntRange = 3..5): List<Pair<Int, PuzzleRecord>> =
        grids.mapNotNull { grid -> get(target, grid)?.let { grid to it } }
}
