package cn.easyar.samples.helloar.model

import cn.easyar.samples.helloar.ui.*
import cn.easyar.samples.helloar.ar.*
import cn.easyar.samples.helloar.puzzle.*
import cn.easyar.samples.helloar.render.*
import cn.easyar.samples.helloar.data.*
import cn.easyar.samples.helloar.util.*

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// Ставит модель на картинку-цель. Система координат цели: x вправо, y вверх по картинке, z от картинки к зрителю;
// ширина картинки принимается за 1 (при рисовании умножается на её настоящий размер).
// В glTF вертикаль - Y, лицо смотрит в +Z; после поворота фигурка стоит на картинке (z = 0 - её основание),
// центр по x и y совпадает с центром картинки, лицом к нижнему краю картинки (к зрителю).
object ModelFit {
    const val HEIGHT = 0.6f      // желаемая высота фигурки в ширинах картинки
    const val FOOTPRINT = 0.5f   // наибольший размер основания в ширинах картинки

    fun fit(model: GlbModel, rotateYDeg: Float, userScale: Float): GlbModel {
        val rad = Math.toRadians(rotateYDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        val mapped = model.parts.map { part ->
            val pos = FloatArray(part.positions.size)
            val nor = FloatArray(part.normals.size)
            for (i in 0 until pos.size / 3) {
                val x = part.positions[i * 3]; val y = part.positions[i * 3 + 1]; val z = part.positions[i * 3 + 2]
                val rx = x * c + z * s
                val rz = -x * s + z * c
                pos[i * 3] = rx; pos[i * 3 + 1] = -rz; pos[i * 3 + 2] = y
                minX = minOf(minX, rx); maxX = max(maxX, rx)
                minY = minOf(minY, -rz); maxY = max(maxY, -rz)
                minZ = minOf(minZ, y); maxZ = max(maxZ, y)
                val nx = part.normals[i * 3]; val ny = part.normals[i * 3 + 1]; val nz = part.normals[i * 3 + 2]
                val rnx = nx * c + nz * s
                val rnz = -nx * s + nz * c
                nor[i * 3] = rnx; nor[i * 3 + 1] = -rnz; nor[i * 3 + 2] = ny
            }
            Pair(pos, nor)
        }
        val height = maxZ - minZ
        val footprint = max(maxX - minX, maxY - minY)
        var scale = Float.MAX_VALUE
        if (height > 1e-9f) scale = minOf(scale, HEIGHT / height)
        if (footprint > 1e-9f) scale = minOf(scale, FOOTPRINT / footprint)
        if (scale == Float.MAX_VALUE) scale = 1f
        scale *= userScale
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        val parts = model.parts.mapIndexed { index, part ->
            val pos = mapped[index].first
            for (i in 0 until pos.size / 3) {
                pos[i * 3] = (pos[i * 3] - cx) * scale
                pos[i * 3 + 1] = (pos[i * 3 + 1] - cy) * scale
                pos[i * 3 + 2] = (pos[i * 3 + 2] - minZ) * scale
            }
            ModelPart(pos, mapped[index].second, part.uvs, part.indices, part.imageIndex, part.color,
                part.alphaMode, part.alphaCutoff, part.wrapS, part.wrapT, part.colors)
        }
        return GlbModel(parts, model.images)
    }
}
