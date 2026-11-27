package cn.easyar.samples.helloar.model

import cn.easyar.samples.helloar.ui.*
import cn.easyar.samples.helloar.ar.*
import cn.easyar.samples.helloar.puzzle.*
import cn.easyar.samples.helloar.render.*
import cn.easyar.samples.helloar.data.*
import cn.easyar.samples.helloar.util.*

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException

// Готовая к показу модель: геометрия уже подогнана под картинку-цель, текстуры распакованы в Bitmap
class LoadedModel(val model: GlbModel, val bitmaps: Map<Int, Bitmap>, val info: PuzzleInfo)

object ModelLoader {
    private const val MAX_TEXTURE_SIDE = 2048

    // настройки пазла из info.txt (если файла нет или он не читается - значения по умолчанию)
    fun loadInfo(assets: AssetManager, def: PuzzleDef): PuzzleInfo {
        val path = def.infoPath ?: return PuzzleInfo()
        return try {
            PuzzleInfo.parse(assets.open(path).use { String(it.readBytes(), Charsets.UTF_8) })
        } catch (e: IOException) {
            PuzzleInfo()
        }
    }

    // читает assets/puzzles/<имя>/model.glb и info.txt; тяжёлая работа, вызывать не в GL-потоке.
    // Кидает GlbException или IOException, если модель прочитать нельзя
    fun load(assets: AssetManager, def: PuzzleDef): LoadedModel {
        val modelPath = def.modelPath ?: throw IOException("у пазла ${def.id} нет модели")
        val info = loadInfo(assets, def)
        val bytes = assets.open(modelPath).use { it.readBytes() }
        val parsed = GlbParser.parse(bytes, info.hide)
        val fitted = ModelFit.fit(parsed, info.rotateDeg, info.scale)
        val bitmaps = HashMap<Int, Bitmap>()
        for ((index, data) in fitted.images) {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_TEXTURE_SIDE) {
                sample *= 2
            }
            val options = BitmapFactory.Options()
            options.inSampleSize = sample
            options.inScaled = false
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options)
            if (bitmap != null) {
                bitmaps[index] = bitmap
            }
        }
        return LoadedModel(fitted, bitmaps, info)
    }
}
