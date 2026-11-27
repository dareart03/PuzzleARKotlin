package cn.easyar.samples.helloar

import android.annotation.TargetApi
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Сохраняет снимок экрана AR-сессии: файл в папке приложения (для истории) и копию в галерею (Android 10+).
class PhotoSaver(private val context: Context, private val store: HistoryStore) {
    // rgba - результат glReadPixels. Тяжёлый метод: вызывать не в GL-потоке.
    // info - запись истории без пути к файлу. Возвращает true, если фото сохранено.
    fun save(rgba: ByteBuffer, width: Int, height: Int, info: HistoryEntry): Boolean {
        val pixels = PixelUtils.rgbaBottomUpToArgb(rgba, width, height)
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            val name = "ar_puzzle_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(info.timeMs)) + ".jpg"
            val dir = store.photoDir
            dir.mkdirs()
            val file = File(dir, name)
            try {
                val ok = FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                if (!ok) {
                    file.delete()
                    return false
                }
            } catch (e: IOException) {
                Log.e("PhotoSaver", "can not write photo", e)
                file.delete()
                return false
            }
            store.add(info.copy(photoPath = file.absolutePath))
            exportToGallery(bitmap, name)
            return true
        } finally {
            bitmap.recycle()
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun exportToGallery(bitmap: Bitmap, name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        try {
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name)
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AR-Puzzle")
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        } catch (e: Exception) {
            Log.w("PhotoSaver", "gallery export failed", e)   // копия в галерею - необязательная часть
        }
    }
}
