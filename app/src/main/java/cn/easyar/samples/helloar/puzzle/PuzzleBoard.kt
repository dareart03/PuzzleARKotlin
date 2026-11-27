package cn.easyar.samples.helloar.puzzle

import cn.easyar.samples.helloar.ui.*
import cn.easyar.samples.helloar.ar.*
import cn.easyar.samples.helloar.model.*
import cn.easyar.samples.helloar.render.*
import cn.easyar.samples.helloar.data.*
import cn.easyar.samples.helloar.util.*

import kotlin.random.Random

// Логика пазла без OpenGL и EasyAR. Клетки нумеруются построчно сверху вниз: cell = row * n + col.
class PuzzleBoard(val n: Int, private val random: Random = Random.Default) {
    // order[cell] = номер кусочка, который сейчас лежит в этой клетке.
    // Кусочек i - это клетка i исходной картинки, пазл собран, когда order[cell] == cell для всех клеток.
    val order = IntArray(n * n) { it }

    // выбранная клетка (первый тап) или -1
    var selected = -1
        private set

    var moves = 0
        private set

    // время (мс) первого тапа после перемешивания; 0 - игра ещё не началась
    var startedAtMs = 0L
        private set

    // время (мс), когда пазл собран; 0 - ещё не собран
    var finishedAtMs = 0L
        private set

    // Устанавливается при завершении уровня, если улучшено хотя бы одно достижение.
    var isNewRecord = false

    val solved: Boolean
        get() = order.indices.all { order[it] == it }

    fun status(targetId: String = ""): PuzzleStatus = PuzzleStatus(moves, startedAtMs, finishedAtMs, solved, targetId, isNewRecord)

    // перемешивает кусочки так, чтобы пазл не оказался уже собранным
    fun shuffle() {
        do {
            for (i in order.size - 1 downTo 1) {
                val j = random.nextInt(i + 1)
                val t = order[i]
                order[i] = order[j]
                order[j] = t
            }
        } while (order.size > 1 && solved)
        selected = -1
        moves = 0
        startedAtMs = 0L
        finishedAtMs = 0L
        isNewRecord = false
    }

    // тап по клетке: первый тап выбирает, второй меняет выбранную и нажатую клетки местами,
    // повторный тап по выбранной снимает выбор. Тапы по собранному пазлу игнорируются.
    // nowMs - текущее время в мс (SystemClock.elapsedRealtime()), по нему идёт таймер.
    // Возвращает true, если кусочки поменялись местами.
    fun tap(cell: Int, nowMs: Long): Boolean {
        if (cell < 0 || cell >= order.size || solved) {
            return false
        }
        if (startedAtMs == 0L) {
            startedAtMs = nowMs
        }
        if (selected == -1) {
            selected = cell
            return false
        }
        if (selected == cell) {
            selected = -1
            return false
        }
        val t = order[selected]
        order[selected] = order[cell]
        order[cell] = t
        selected = -1
        moves++
        if (solved) {
            finishedAtMs = nowMs
        }
        return true
    }
}

// неизменяемый снимок состояния пазла, его можно безопасно передавать из GL-потока в UI-поток
data class PuzzleStatus(
    val moves: Int,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val solved: Boolean,
    val targetId: String = "",
    val isNewRecord: Boolean = false
) {
    // сколько времени идёт (или шла до победы) игра, мс
    fun elapsedMs(nowMs: Long): Long = when {
        startedAtMs == 0L -> 0L
        finishedAtMs != 0L -> finishedAtMs - startedAtMs
        else -> nowMs - startedAtMs
    }
}

object PuzzleMath {
    // Точка плоскости картинки (z = 0 в системе координат цели), в которую попадает касание.
    // proj и pose - матрицы 4x4, 16 чисел по строкам (как Matrix44F.data); nx, ny - касание в NDC
    // (от -1 до 1, ось y направлена вверх). Возвращает (x, y) или null, если пересечения нет.
    fun touchToTarget(proj: FloatArray, pose: FloatArray, nx: Float, ny: Float): FloatArray? {
        fun m(r: Int, c: Int): Double {
            var s = 0.0
            for (k in 0 until 4) {
                s += proj[r * 4 + k].toDouble() * pose[k * 4 + c].toDouble()
            }
            return s
        }
        // clip = proj * pose * (x, y, 0, 1); нужно clip.x / clip.w = nx и clip.y / clip.w = ny,
        // это система двух линейных уравнений относительно x и y
        val a = m(0, 0) - nx * m(3, 0)
        val b = m(0, 1) - nx * m(3, 1)
        val e = nx * m(3, 3) - m(0, 3)
        val c = m(1, 0) - ny * m(3, 0)
        val d = m(1, 1) - ny * m(3, 1)
        val f = ny * m(3, 3) - m(1, 3)
        val det = a * d - b * c
        if (Math.abs(det) < 1e-12) {
            return null
        }
        val x = (e * d - b * f) / det
        val y = (a * f - e * c) / det
        val w = m(3, 0) * x + m(3, 1) * y + m(3, 3)
        if (w <= 0.0) {
            return null // точка за камерой
        }
        return floatArrayOf(x.toFloat(), y.toFloat())
    }

    // Номер клетки сетки n x n по точке (x, y) в системе цели (центр картинки - начало координат,
    // y вверх), либо -1, если точка вне картинки. width и height - размер картинки в тех же единицах.
    fun cellAt(x: Float, y: Float, width: Float, height: Float, n: Int): Int {
        val u = (x + width / 2) / width
        val v = (height / 2 - y) / height
        if (u < 0f || u >= 1f || v < 0f || v >= 1f) {
            return -1
        }
        return (v * n).toInt() * n + (u * n).toInt()
    }
}
