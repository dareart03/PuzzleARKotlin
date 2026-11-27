package cn.easyar.samples.helloar

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

// Картинки фигурок для экрана коллекции. Рисуются программно (ModelThumbnail) и кэшируются PNG-файлами;
// кэш привязан ко времени обновления приложения, поэтому после установки новой версии пересоздаётся.
class CollectionThumbnails(context: Context, private val assets: AssetManager) {
    companion object {
        const val WIDTH = 360
        const val HEIGHT = 480
        private const val PREFIX = "thumbs_v2_"
    }

    private val dir: File

    init {
        val app = context.applicationContext
        val stamp = try {
            app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        } catch (e: Exception) {
            0L
        }
        dir = File(app.filesDir, PREFIX + stamp)
        // кэши от старых версий приложения не нужны
        app.filesDir.listFiles()?.forEach {
            if (it.isDirectory && it.name.startsWith(PREFIX) && it != dir) {
                it.deleteRecursively()
            }
        }
    }

    fun info(def: PuzzleDef): PuzzleInfo = ModelLoader.loadInfo(assets, def)

    // готовая картинка (цветная, с прозрачным фоном) или null, если модель не читается; вызывать не в UI-потоке
    fun get(def: PuzzleDef): Bitmap? {
        val file = File(dir, def.id.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".png")
        if (file.isFile) {
            BitmapFactory.decodeFile(file.path)?.let { return it }
        }
        val bitmap = try {
            render(def)
        } catch (e: Throwable) {
            Log.e("CollectionThumbnails", "thumbnail failed: " + def.id, e)
            null
        } ?: return null
        try {
            dir.mkdirs()
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            Log.e("CollectionThumbnails", "thumbnail cache write failed: " + def.id, e)
        }
        return bitmap
    }

    private fun render(def: PuzzleDef): Bitmap? {
        val loaded = ModelLoader.load(assets, def)
        try {
            val textures = HashMap<Int, TextureImage>()
            for ((index, bitmap) in loaded.bitmaps) {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                textures[index] = TextureImage(bitmap.width, bitmap.height, pixels)
            }
            val argb = ModelThumbnail.render(loaded.model, textures, WIDTH, HEIGHT)
            return Bitmap.createBitmap(argb, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        } finally {
            for (bitmap in loaded.bitmaps.values) {
                bitmap.recycle()
            }
        }
    }
}
