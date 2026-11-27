package cn.easyar.samples.helloar

import java.nio.ByteBuffer

object PixelUtils {
    // Результат glReadPixels (RGBA, байт на канал, строки идут снизу вверх) -> массив ARGB для Bitmap
    // (строки сверху вниз). Альфа ставится 255: у экрана она может быть любой, а фото должно быть непрозрачным.
    fun rgbaBottomUpToArgb(rgba: ByteBuffer, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val srcRow = (height - 1 - y) * width
            val dstRow = y * width
            for (x in 0 until width) {
                val i = (srcRow + x) * 4
                val r = rgba.get(i).toInt() and 0xFF
                val g = rgba.get(i + 1).toInt() and 0xFF
                val b = rgba.get(i + 2).toInt() and 0xFF
                out[dstRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }
}
