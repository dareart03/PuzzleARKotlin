package cn.easyar.samples.helloar.model

import cn.easyar.samples.helloar.ui.*
import cn.easyar.samples.helloar.ar.*
import cn.easyar.samples.helloar.puzzle.*
import cn.easyar.samples.helloar.render.*
import cn.easyar.samples.helloar.data.*
import cn.easyar.samples.helloar.util.*

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ModelPlacement(
    val matrix: FloatArray,   // модель -> система картинки, по столбцам, как для OpenGL
    val light: FloatArray     // направление на источник света в системе картинки
)

// Положение фигурки-награды: она висит над центром картинки и всегда обращена к зрителю лицом, головой к верху экрана
// (как наклейка, повёрнутая к камере), поэтому видна анфас, как бы телефон ни был наклонён.
// Оси экрана в системе картинки находятся по самой проекции (куда смещается точка на экране при сдвиге по каждой оси
// картинки), поэтому результат не зависит от того, как EasyAR ориентирует оси камеры и как повёрнут экран телефона.
object ModelPose {
    const val CENTER_Z = 0.35f      // на какой высоте над картинкой (в ширинах) находится середина фигурки
    private const val CLEARANCE = 0.05f   // запас, чтобы фигурка не уходила под плоскость картинки (в ширинах)
    private const val GRADIENT_STEP = 0.05f

    // proj, pose - матрицы 4x4 по строкам (Matrix44F.data): на экране точка p картинки оказывается в proj * pose * p
    private fun screenPoint(proj: FloatArray, pose: FloatArray, x: Double, y: Double, z: Double, out: DoubleArray): Boolean {
        val c = DoubleArray(4)
        for (i in 0 until 4) {
            c[i] = pose[i * 4] * x + pose[i * 4 + 1] * y + pose[i * 4 + 2] * z + pose[i * 4 + 3]
        }
        val clip = DoubleArray(4)
        for (i in 0 until 4) {
            clip[i] = proj[i * 4] * c[0] + proj[i * 4 + 1] * c[1] + proj[i * 4 + 2] * c[2] + proj[i * 4 + 3] * c[3]
        }
        if (abs(clip[3]) < 1e-12) return false
        out[0] = clip[0] / clip[3]
        out[1] = clip[1] / clip[3]
        return true
    }

    private fun normalize(v: DoubleArray): DoubleArray? {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (len > 1e-12) doubleArrayOf(v[0] / len, v[1] / len, v[2] / len) else null
    }

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    // width - реальная ширина картинки, pop - масштаб появления (0..~1,1), pivotZ - середина высоты фигурки
    // (в ширинах, как её сделал ModelFit), swayDeg - покачивание вокруг вертикали, lift - дополнительный подъём.
    // Возвращает null, если проекция вырождена
    fun placement(proj: FloatArray, pose: FloatArray, width: Float, pop: Float, pivotZ: Float, swayDeg: Float, lift: Float): ModelPlacement? {
        val s = width * pop
        val cx = 0.0
        val cy = 0.0
        val cz = (width * CENTER_Z).toDouble()
        val step = (width * GRADIENT_STEP).toDouble()
        val grad = Array(2) { DoubleArray(3) }   // grad[0] - куда растёт x на экране, grad[1] - куда растёт y (вверх)
        val a = DoubleArray(2)
        val b = DoubleArray(2)
        for (k in 0 until 3) {
            val dx = if (k == 0) step else 0.0
            val dy = if (k == 1) step else 0.0
            val dz = if (k == 2) step else 0.0
            if (!screenPoint(proj, pose, cx + dx, cy + dy, cz + dz, a) || !screenPoint(proj, pose, cx - dx, cy - dy, cz - dz, b)) return null
            grad[0][k] = (a[0] - b[0]) / (2 * step)
            grad[1][k] = (a[1] - b[1]) / (2 * step)
        }
        val r = normalize(grad[0]) ?: return null
        val upRaw = doubleArrayOf(
            grad[1][0] - (grad[1][0] * r[0] + grad[1][1] * r[1] + grad[1][2] * r[2]) * r[0],
            grad[1][1] - (grad[1][0] * r[0] + grad[1][1] * r[1] + grad[1][2] * r[2]) * r[1],
            grad[1][2] - (grad[1][0] * r[0] + grad[1][1] * r[1] + grad[1][2] * r[2]) * r[2])
        val u = normalize(upRaw) ?: return null
        val f = cross(r, u)   // к зрителю: вправо x вверх

        // покачивание вокруг вертикали фигурки
        val ang = Math.toRadians(swayDeg.toDouble())
        val ca = cos(ang)
        val sa = sin(ang)
        val fs = DoubleArray(3) { f[it] * ca + r[it] * sa }
        val rs = DoubleArray(3) { r[it] * ca - f[it] * sa }

        // середина фигурки в cz, но не ниже, чем нужно, чтобы она целиком была над картинкой
        val halfHeight = pivotZ.toDouble() * s
        val centerZ = maxOf(cz, halfHeight * abs(u[2]) + CLEARANCE * s) + lift
        val tx = cx - u[0] * halfHeight
        val ty = cy - u[1] * halfHeight
        val tz = centerZ - u[2] * halfHeight
        val m = floatArrayOf(
            (rs[0] * s).toFloat(), (rs[1] * s).toFloat(), (rs[2] * s).toFloat(), 0f,
            (-fs[0] * s).toFloat(), (-fs[1] * s).toFloat(), (-fs[2] * s).toFloat(), 0f,
            (u[0] * s).toFloat(), (u[1] * s).toFloat(), (u[2] * s).toFloat(), 0f,
            tx.toFloat(), ty.toFloat(), tz.toFloat(), 1f)
        val l = doubleArrayOf(0.6 * f[0] + 0.5 * u[0] - 0.3 * r[0], 0.6 * f[1] + 0.5 * u[1] - 0.3 * r[1], 0.6 * f[2] + 0.5 * u[2] - 0.3 * r[2])
        val ln = normalize(l) ?: doubleArrayOf(0.0, 0.0, 1.0)
        return ModelPlacement(m, floatArrayOf(ln[0].toFloat(), ln[1].toFloat(), ln[2].toFloat()))
    }
}
