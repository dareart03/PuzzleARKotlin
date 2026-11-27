package cn.easyar.samples.helloar.puzzle

import cn.easyar.samples.helloar.ui.*
import cn.easyar.samples.helloar.ar.*
import cn.easyar.samples.helloar.model.*
import cn.easyar.samples.helloar.render.*
import cn.easyar.samples.helloar.data.*
import cn.easyar.samples.helloar.util.*

import android.content.res.AssetManager
import java.io.IOException

// Один пазл из папки assets/puzzles/<id>/:
//   target.jpg (или .jpeg/.png) - картинка, которую показывают камере (обязательна)
//   model.glb                   - фигурка-награда (необязательна)
//   info.txt                    - название и настройки фигурки (необязателен, см. PuzzleInfo)
data class PuzzleDef(
    val id: String,
    val targetPath: String,
    val modelPath: String?,
    val infoPath: String?
)

// Находит пазлы по содержимому assets/puzzles: чтобы добавить пазл, достаточно положить туда новую папку.
object PuzzleCatalog {
    const val ROOT = "puzzles"
    private val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png")

    // list(path) должен возвращать имена внутри папки assets (для файла или отсутствующего пути - пусто или null)
    fun discover(list: (String) -> Array<String>?): List<PuzzleDef> {
        val result = ArrayList<PuzzleDef>()
        val ids = (list(ROOT) ?: emptyArray()).sorted()
        for (id in ids) {
            val files = list("$ROOT/$id") ?: continue
            val byLowerName = HashMap<String, String>()
            for (f in files) {
                byLowerName[f.lowercase()] = f
            }
            val target = findImage(byLowerName, "target") ?: continue
            val model = byLowerName["model.glb"]
            val info = byLowerName["info.txt"]
            result.add(PuzzleDef(
                id,
                "$ROOT/$id/$target",
                if (model != null) "$ROOT/$id/$model" else null,
                if (info != null) "$ROOT/$id/$info" else null))
        }
        return result
    }

    private fun findImage(byLowerName: Map<String, String>, base: String): String? {
        for (ext in IMAGE_EXTENSIONS) {
            val name = byLowerName["$base.$ext"]
            if (name != null) {
                return name
            }
        }
        return null
    }

    fun load(assets: AssetManager): List<PuzzleDef> = discover { path ->
        try {
            assets.list(path)
        } catch (e: IOException) {
            null
        }
    }
}
