package cn.easyar.samples.helloar.data

import cn.easyar.samples.helloar.ui.*
import cn.easyar.samples.helloar.ar.*
import cn.easyar.samples.helloar.puzzle.*
import cn.easyar.samples.helloar.model.*
import cn.easyar.samples.helloar.render.*
import cn.easyar.samples.helloar.util.*

import android.content.Context
import java.io.File
import java.io.IOException

// Одна запись истории: собранный пазл или сохранённое фото
data class HistoryEntry(
    val timeMs: Long,          // когда создана запись (System.currentTimeMillis())
    val kind: String,          // KIND_RESULT или KIND_PHOTO
    val target: String,        // имя картинки-цели
    val grid: Int,             // размер пазла
    val elapsedMs: Long,       // время игры
    val moves: Int,            // число ходов
    val solved: Boolean,       // собран ли пазл (для фото - на момент снимка)
    val photoPath: String?     // файл фото (только для KIND_PHOTO)
) {
    companion object {
        const val KIND_RESULT = "result"
        const val KIND_PHOTO = "photo"
    }
}

// История игрока: одна запись на строку (поля через табуляцию) в файле history.txt, фото лежат в папке photos.
// Методы можно вызывать из любого потока.
class HistoryStore(private val dir: File) {
    companion object {
        private const val MAX_ENTRIES = 200
        private val LOCK = Any()   // общий замок: хранилище создаётся и в AR-сессии, и на экране истории

        fun forContext(context: Context): HistoryStore = HistoryStore(File(context.filesDir, "history"))
    }

    private val file = File(dir, "history.txt")

    val photoDir: File
        get() = File(dir, "photos")

    // записи от новых к старым
    fun load(): List<HistoryEntry> {
        synchronized(LOCK) {
            return readOldestFirst().reversed()
        }
    }

    fun add(entry: HistoryEntry) {
        synchronized(LOCK) {
            val entries = ArrayList(readOldestFirst())
            entries.add(entry)
            while (entries.size > MAX_ENTRIES) {
                entries.removeAt(0).photoPath?.let { File(it).delete() }
            }
            try {
                dir.mkdirs()
                file.writeText(entries.joinToString("") { format(it) + "\n" })
            } catch (e: IOException) {
                // история не критична: если не записалась, игра продолжается
            }
        }
    }

    // удаляет историю и фото, сохранённые в приложении
    fun clear() {
        synchronized(LOCK) {
            file.delete()
            photoDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun readOldestFirst(): List<HistoryEntry> {
        if (!file.exists()) {
            return emptyList()
        }
        return try {
            file.readLines().mapNotNull { parse(it) }
        } catch (e: IOException) {
            emptyList()
        }
    }

    private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun format(e: HistoryEntry): String =
        listOf(e.timeMs, clean(e.kind), clean(e.target), e.grid, e.elapsedMs, e.moves,
            if (e.solved) "1" else "0", clean(e.photoPath ?: "")).joinToString("\t")

    private fun parse(line: String): HistoryEntry? {
        val p = line.split('\t')
        if (p.size < 8) {
            return null
        }
        return try {
            HistoryEntry(p[0].toLong(), p[1], p[2], p[3].toInt(), p[4].toLong(), p[5].toInt(), p[6] == "1",
                p[7].ifEmpty { null })
        } catch (e: NumberFormatException) {
            null
        }
    }
}
