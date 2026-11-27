package cn.easyar.samples.helloar.model

import cn.easyar.samples.helloar.ui.*
import cn.easyar.samples.helloar.ar.*
import cn.easyar.samples.helloar.puzzle.*
import cn.easyar.samples.helloar.render.*
import cn.easyar.samples.helloar.data.*
import cn.easyar.samples.helloar.util.*

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class TextureImage(val width: Int, val height: Int, val argb: IntArray)

// Рисует картинку фигурки (вид спереди) программно, без OpenGL: для экрана коллекции.
// Модель должна быть подогнана ModelFit: x - вправо, z - вверх, лицо смотрит в -y (на камеру).
object ModelThumbnail {
    private const val SUPERSAMPLE = 2

    // возвращает width*height пикселей ARGB (фон прозрачный)
    fun render(model: GlbModel, textures: Map<Int, TextureImage>, width: Int, height: Int): IntArray {
        val w = width * SUPERSAMPLE
        val h = height * SUPERSAMPLE
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (p in model.parts) {
            for (i in 0 until p.positions.size / 3) {
                val x = p.positions[i * 3]; val z = p.positions[i * 3 + 2]
                minX = min(minX, x); maxX = max(maxX, x)
                minZ = min(minZ, z); maxZ = max(maxZ, z)
            }
        }
        if (minX > maxX) return IntArray(width * height)
        // масштаб: вся фигурка с небольшим запасом по краям, по центру кадра
        val pad = 0.06f
        val scale = max((maxX - minX) / (w * (1 - 2 * pad)), (maxZ - minZ) / (h * (1 - 2 * pad)))
        val cx = (minX + maxX) / 2
        val cz = (minZ + maxZ) / 2
        val colors = IntArray(w * h)
        val depth = FloatArray(w * h) { Float.MAX_VALUE }
        // источник света спереди, слева и сверху
        val lx = -0.30f; val ly = -0.70f; val lz = 0.55f
        val ln = sqrt(lx * lx + ly * ly + lz * lz)

        for (pass in 0..1) {
            for (part in model.parts) {
                if ((part.alphaMode == ModelPart.ALPHA_BLEND) != (pass == 1)) continue
                val tex = textures[part.imageIndex]
                val idx = part.indices
                val pos = part.positions
                for (t in 0 until idx.size / 3) {
                    val i0 = idx[t * 3]; val i1 = idx[t * 3 + 1]; val i2 = idx[t * 3 + 2]
                    val x0 = (pos[i0 * 3] - cx) / scale + w / 2f; val y0 = h / 2f - (pos[i0 * 3 + 2] - cz) / scale
                    val x1 = (pos[i1 * 3] - cx) / scale + w / 2f; val y1 = h / 2f - (pos[i1 * 3 + 2] - cz) / scale
                    val x2 = (pos[i2 * 3] - cx) / scale + w / 2f; val y2 = h / 2f - (pos[i2 * 3 + 2] - cz) / scale
                    val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
                    if (abs(area) < 1e-9f) continue
                    val bx0 = max(floor(min(x0, min(x1, x2))).toInt(), 0)
                    val bx1 = min(floor(max(x0, max(x1, x2))).toInt(), w - 1)
                    val by0 = max(floor(min(y0, min(y1, y2))).toInt(), 0)
                    val by1 = min(floor(max(y0, max(y1, y2))).toInt(), h - 1)
                    for (py in by0..by1) {
                        for (px in bx0..bx1) {
                            val sx = px + 0.5f; val sy = py + 0.5f
                            val a = ((x1 - sx) * (y2 - sy) - (x2 - sx) * (y1 - sy)) / area
                            val b = ((x2 - sx) * (y0 - sy) - (x0 - sx) * (y2 - sy)) / area
                            val c = 1f - a - b
                            if (a < 0f || b < 0f || c < 0f) continue
                            val z = a * pos[i0 * 3 + 1] + b * pos[i1 * 3 + 1] + c * pos[i2 * 3 + 1]   // глубина: чем меньше y, тем ближе к камере
                            val o = py * w + px
                            if (z >= depth[o]) continue
                            // Цвет материала умножается на интерполированный цвет вершин COLOR_0.
                            // В GLB цвета заданы отдельно для каждой вершины, поэтому используем
                            // барицентрические коэффициенты текущего пикселя.
                            val vertexColors = part.colors
                            val cr = a * vertexColors[i0 * 4] + b * vertexColors[i1 * 4] + c * vertexColors[i2 * 4]
                            val cg = a * vertexColors[i0 * 4 + 1] + b * vertexColors[i1 * 4 + 1] + c * vertexColors[i2 * 4 + 1]
                            val cb = a * vertexColors[i0 * 4 + 2] + b * vertexColors[i1 * 4 + 2] + c * vertexColors[i2 * 4 + 2]
                            val ca = a * vertexColors[i0 * 4 + 3] + b * vertexColors[i1 * 4 + 3] + c * vertexColors[i2 * 4 + 3]
                            var r = part.color[0] * cr
                            var g = part.color[1] * cg
                            var bl = part.color[2] * cb
                            var al = part.color[3] * ca
                            if (tex != null) {
                                val u = a * part.uvs[i0 * 2] + b * part.uvs[i1 * 2] + c * part.uvs[i2 * 2]
                                val v = a * part.uvs[i0 * 2 + 1] + b * part.uvs[i1 * 2 + 1] + c * part.uvs[i2 * 2 + 1]
                                val texel = sample(tex, u, v, part.wrapS, part.wrapT)
                                al *= (texel ushr 24) / 255f
                                r *= ((texel shr 16) and 0xFF) / 255f
                                g *= ((texel shr 8) and 0xFF) / 255f
                                bl *= (texel and 0xFF) / 255f
                            }
                            if (part.alphaMode == ModelPart.ALPHA_MASK && al < part.alphaCutoff) continue
                            if (part.alphaMode == ModelPart.ALPHA_OPAQUE) al = 1f
                            // освещение; у двусторонних поверхностей нормаль разворачивается к камере
                            var nx = a * part.normals[i0 * 3] + b * part.normals[i1 * 3] + c * part.normals[i2 * 3]
                            var ny = a * part.normals[i0 * 3 + 1] + b * part.normals[i1 * 3 + 1] + c * part.normals[i2 * 3 + 1]
                            var nz = a * part.normals[i0 * 3 + 2] + b * part.normals[i1 * 3 + 2] + c * part.normals[i2 * 3 + 2]
                            if (ny > 0f) { nx = -nx; ny = -ny; nz = -nz }
                            val nl = sqrt(nx * nx + ny * ny + nz * nz)
                            val diffuse = if (nl > 1e-6f) max((nx * lx + ny * ly + nz * lz) / (nl * ln), 0f) else 0f
                            val light = 0.55f + 0.45f * diffuse
                            r = min(r * light, 1f); g = min(g * light, 1f); bl = min(bl * light, 1f)
                            if (part.alphaMode == ModelPart.ALPHA_BLEND) {
                                // полупрозрачное смешивается с тем, что уже нарисовано, и не пишет в буфер глубины
                                val dst = colors[o]
                                val da = (dst ushr 24) / 255f
                                val outA = al + da * (1 - al)
                                if (outA <= 0f) continue
                                val outR = (r * al + ((dst shr 16) and 0xFF) / 255f * da * (1 - al)) / outA
                                val outG = (g * al + ((dst shr 8) and 0xFF) / 255f * da * (1 - al)) / outA
                                val outB = (bl * al + (dst and 0xFF) / 255f * da * (1 - al)) / outA
                                colors[o] = pack(outR, outG, outB, outA)
                            } else {
                                colors[o] = pack(r, g, bl, 1f)
                                depth[o] = z
                            }
                        }
                    }
                }
            }
        }
        return downsample(colors, w, h)
    }

    private fun pack(r: Float, g: Float, b: Float, a: Float): Int =
        ((a * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or ((r * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((g * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or (b * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun wrap(i: Int, size: Int, mode: Int): Int = when (mode) {
        33071 -> i.coerceIn(0, size - 1)                                        // CLAMP_TO_EDGE
        33648 -> { val m = Math.floorMod(i, size * 2); if (m < size) m else size * 2 - 1 - m }   // MIRRORED_REPEAT
        else -> Math.floorMod(i, size)                                          // REPEAT
    }

    // билинейная выборка; v = 0 - верх картинки
    private fun sample(tex: TextureImage, u: Float, v: Float, wrapS: Int, wrapT: Int): Int {
        val fx = u * tex.width - 0.5f
        val fy = v * tex.height - 0.5f
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val tx = fx - x0; val ty = fy - y0
        val xa = wrap(x0, tex.width, wrapS); val xb = wrap(x0 + 1, tex.width, wrapS)
        val ya = wrap(y0, tex.height, wrapT); val yb = wrap(y0 + 1, tex.height, wrapT)
        val p00 = tex.argb[ya * tex.width + xa]; val p10 = tex.argb[ya * tex.width + xb]
        val p01 = tex.argb[yb * tex.width + xa]; val p11 = tex.argb[yb * tex.width + xb]
        fun ch(shift: Int): Int {
            val top = ((p00 shr shift) and 0xFF) * (1 - tx) + ((p10 shr shift) and 0xFF) * tx
            val bottom = ((p01 shr shift) and 0xFF) * (1 - tx) + ((p11 shr shift) and 0xFF) * tx
            return (top * (1 - ty) + bottom * ty + 0.5f).toInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // усреднение блоков SUPERSAMPLE x SUPERSAMPLE с учётом прозрачности
    private fun downsample(src: IntArray, w: Int, h: Int): IntArray {
        val ow = w / SUPERSAMPLE
        val oh = h / SUPERSAMPLE
        val out = IntArray(ow * oh)
        val n = SUPERSAMPLE * SUPERSAMPLE
        for (y in 0 until oh) {
            for (x in 0 until ow) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (dy in 0 until SUPERSAMPLE) {
                    for (dx in 0 until SUPERSAMPLE) {
                        val p = src[(y * SUPERSAMPLE + dy) * w + x * SUPERSAMPLE + dx]
                        val pa = (p ushr 24) / 255f
                        a += pa
                        r += ((p shr 16) and 0xFF) * pa
                        g += ((p shr 8) and 0xFF) * pa
                        b += (p and 0xFF) * pa
                    }
                }
                if (a > 0f) {
                    out[y * ow + x] = pack(r / a / 255f, g / a / 255f, b / a / 255f, a / n)
                }
            }
        }
        return out
    }
}
