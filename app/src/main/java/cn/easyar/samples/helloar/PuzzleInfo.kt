package cn.easyar.samples.helloar

// Необязательные настройки пазла из assets/puzzles/<имя>/info.txt, по одной "ключ=значение" в строке:
//   title=Фурина                      название для коллекции
//   hide=Plane.001,Cube.002           узлы модели, которые не показывать (имя или начало имени)
//   rotate=180                        поворот фигурки вокруг вертикали, градусы (если смотрит не на зрителя)
//   scale=1.2                         во сколько раз увеличить или уменьшить фигурку
// Строки с # и неизвестные ключи пропускаются.
data class PuzzleInfo(
    val title: String? = null,
    val hide: List<String> = emptyList(),
    val rotateDeg: Float = 0f,
    val scale: Float = 1f
) {
    companion object {
        fun parse(text: String): PuzzleInfo {
            var title: String? = null
            var hide: List<String> = emptyList()
            var rotate = 0f
            var scale = 1f
            for (rawLine in text.removePrefix("﻿").lines()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim().lowercase()
                val value = line.substring(eq + 1).trim()
                when (key) {
                    "title" -> if (value.isNotEmpty()) title = value
                    "hide" -> hide = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    "rotate" -> value.toFloatOrNull()?.let { if (it.isFinite()) rotate = it }
                    "scale" -> value.toFloatOrNull()?.let { if (it.isFinite() && it > 0f) scale = it }
                }
            }
            return PuzzleInfo(title, hide, rotate, scale)
        }
    }
}
