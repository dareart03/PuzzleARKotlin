package cn.easyar.samples.helloar

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class GlbException(message: String) : Exception(message)

// Один кусок модели с одним материалом. Вершины уже в общей системе координат модели (с учётом узлов и скелета).
class ModelPart(
    val positions: FloatArray,   // x, y, z на вершину
    val normals: FloatArray,     // x, y, z на вершину, единичной длины
    val uvs: FloatArray,         // u, v на вершину (начало - верхний левый угол картинки, как в glTF)
    val indices: IntArray,       // треугольники
    val imageIndex: Int,         // номер картинки в GlbModel.images или -1, если материал однотонный
    val color: FloatArray,       // множитель цвета r, g, b, a
    val alphaMode: Int,          // ALPHA_OPAQUE, ALPHA_MASK или ALPHA_BLEND
    val alphaCutoff: Float,
    val wrapS: Int,
    val wrapT: Int,
    val colors: FloatArray = FloatArray(positions.size / 3 * 4) { 1f } // RGBA на вершину
) {
    companion object {
        const val ALPHA_OPAQUE = 0
        const val ALPHA_MASK = 1
        const val ALPHA_BLEND = 2
    }
}

class GlbModel(
    val parts: List<ModelPart>,
    val images: Map<Int, ByteArray>   // сжатые PNG/JPEG из файла
) {
    val triangleCount: Int get() = parts.sumOf { it.indices.size / 3 }
}

// Читает бинарный glTF (.glb): иерархию узлов, меши, материалы с базовым цветом и текстурой, скелет в позе по умолчанию.
// Анимации, морфинг, сжатие геометрии (Draco, meshopt) не поддерживаются: такие файлы дают GlbException.
object GlbParser {
    private val SUPPORTED_EXTENSIONS = setOf(
        "KHR_materials_pbrSpecularGlossiness", "KHR_materials_unlit", "KHR_texture_transform",
        "KHR_mesh_quantization", "KHR_materials_emissive_strength", "KHR_lights_punctual", "KHR_materials_ior",
        "KHR_materials_specular", "KHR_materials_clearcoat", "KHR_materials_sheen", "KHR_materials_transmission",
        "KHR_materials_volume", "KHR_materials_variants", "KHR_xmp_json_ld"
    )
    private const val MAX_NODE_DEPTH = 64

    // hide: имена узлов (или их начала), которые нужно пропустить вместе с потомками
    fun parse(data: ByteArray, hide: List<String> = emptyList()): GlbModel {
        if (data.size < 20) throw GlbException("файл слишком короткий")
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != 0x46546C67) throw GlbException("это не GLB (нет заголовка glTF)")
        if (bb.getInt(4) != 2) throw GlbException("поддерживается только glTF 2.0")
        var json: String? = null
        var binStart = -1
        var binLen = 0
        var off = 12
        while (off + 8 <= data.size) {
            val len = bb.getInt(off)
            val type = bb.getInt(off + 4)
            val start = off + 8
            if (len < 0 || start.toLong() + len > data.size) throw GlbException("повреждённый блок в GLB")
            if (type == 0x4E4F534A && json == null) {
                json = String(data, start, len, Charsets.UTF_8)
            } else if (type == 0x004E4942 && binStart < 0) {
                binStart = start
                binLen = len
            }
            off = start + len
        }
        if (json == null) throw GlbException("нет JSON-блока")
        val root = try {
            @Suppress("UNCHECKED_CAST")
            MiniJson.parse(json) as Map<String, Any?>
        } catch (e: IllegalArgumentException) {
            throw GlbException("JSON не читается: " + e.message)
        }
        return Reader(root, data, binStart, binLen, hide).read()
    }

    // ---------- вспомогательные функции для разбора JSON ----------

    private fun Map<String, Any?>.int(key: String, default: Int): Int = (this[key] as? Double)?.toInt() ?: default
    private fun Map<String, Any?>.dbl(key: String, default: Double): Double = (this[key] as? Double) ?: default

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(key: String): List<Any?> = (this[key] as? List<Any?>) ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObj(): Map<String, Any?>? = this as? Map<String, Any?>

    private fun Any?.asNum(): Double = (this as? Double) ?: 0.0

    // ---------- матрицы 4x4: столбцами, как в glTF ----------

    private fun identity(): DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) {
                    sum += a[k * 4 + row] * b[col * 4 + k]
                }
                r[col * 4 + row] = sum
            }
        }
        return r
    }

    private fun fromTrs(t: List<Any?>, q: List<Any?>, s: List<Any?>): DoubleArray {
        val x = if (q.size >= 4) q[0].asNum() else 0.0
        val y = if (q.size >= 4) q[1].asNum() else 0.0
        val z = if (q.size >= 4) q[2].asNum() else 0.0
        var w = if (q.size >= 4) q[3].asNum() else 1.0
        val n = sqrt(x * x + y * y + z * z + w * w)
        val qx: Double; val qy: Double; val qz: Double
        if (n > 0.0) {
            qx = x / n; qy = y / n; qz = z / n; w /= n
        } else {
            qx = 0.0; qy = 0.0; qz = 0.0; w = 1.0
        }
        val sx = if (s.size >= 3) s[0].asNum() else 1.0
        val sy = if (s.size >= 3) s[1].asNum() else 1.0
        val sz = if (s.size >= 3) s[2].asNum() else 1.0
        val tx = if (t.size >= 3) t[0].asNum() else 0.0
        val ty = if (t.size >= 3) t[1].asNum() else 0.0
        val tz = if (t.size >= 3) t[2].asNum() else 0.0
        return doubleArrayOf(
            (1 - 2 * (qy * qy + qz * qz)) * sx, (2 * (qx * qy + qz * w)) * sx, (2 * (qx * qz - qy * w)) * sx, 0.0,
            (2 * (qx * qy - qz * w)) * sy, (1 - 2 * (qx * qx + qz * qz)) * sy, (2 * (qy * qz + qx * w)) * sy, 0.0,
            (2 * (qx * qz + qy * w)) * sz, (2 * (qy * qz - qx * w)) * sz, (1 - 2 * (qx * qx + qy * qy)) * sz, 0.0,
            tx, ty, tz, 1.0
        )
    }

    private class Reader(
        val root: Map<String, Any?>,
        val data: ByteArray,
        val binStart: Int,
        val binLen: Int,
        val hide: List<String>
    ) {
        val bb: ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val nodes = root.list("nodes")
        val meshes = root.list("meshes")
        val materials = root.list("materials")
        val textures = root.list("textures")
        val images = root.list("images")
        val samplers = root.list("samplers")
        val accessors = root.list("accessors")
        val bufferViews = root.list("bufferViews")
        val skins = root.list("skins")
        val usedImages = HashMap<Int, ByteArray>()

        fun read(): GlbModel {
            for (ext in root.list("extensionsRequired")) {
                if (ext !is String || ext !in SUPPORTED_EXTENSIONS) {
                    throw GlbException("не поддерживается расширение $ext (например, сжатие геометрии)")
                }
            }
            if (nodes.isEmpty() || meshes.isEmpty()) throw GlbException("в файле нет моделей")
            val globals = computeGlobals()
            val parts = ArrayList<ModelPart>()
            val sceneIndex = root.int("scene", 0)
            val sceneRoots: List<Int> = root.list("scenes").getOrNull(sceneIndex).asObj()?.list("nodes")?.map { it.asNum().toInt() }
                ?: rootNodes()
            for (n in sceneRoots) {
                visit(n, 0, globals, parts)
            }
            if (parts.isEmpty()) throw GlbException("после фильтрации не осталось геометрии")
            return GlbModel(parts, usedImages)
        }

        private fun nodeAt(i: Int): Map<String, Any?> = nodes.getOrNull(i).asObj() ?: throw GlbException("неверный номер узла $i")

        private fun rootNodes(): List<Int> {
            val children = HashSet<Int>()
            for (n in nodes) {
                for (c in n.asObj()?.list("children") ?: emptyList()) {
                    children.add(c.asNum().toInt())
                }
            }
            return nodes.indices.filter { it !in children }
        }

        private fun localMatrix(node: Map<String, Any?>): DoubleArray {
            val m = node.list("matrix")
            if (m.size == 16) return DoubleArray(16) { m[it].asNum() }
            return fromTrs(node.list("translation"), node.list("rotation"), node.list("scale"))
        }

        // мировые матрицы всех узлов, включая скрытые (кости скелета могут лежать в любом месте иерархии)
        private fun computeGlobals(): Array<DoubleArray?> {
            val g = arrayOfNulls<DoubleArray>(nodes.size)
            fun walk(i: Int, parent: DoubleArray, depth: Int) {
                if (depth > MAX_NODE_DEPTH) throw GlbException("слишком глубокая иерархия узлов")
                val node = nodeAt(i)
                val m = mul(parent, localMatrix(node))
                g[i] = m
                for (c in node.list("children")) {
                    walk(c.asNum().toInt(), m, depth + 1)
                }
            }
            for (r in rootNodes()) {
                walk(r, identity(), 0)
            }
            return g
        }

        private fun isHidden(name: Any?): Boolean {
            if (name !is String) return false
            return hide.any { name.startsWith(it, ignoreCase = true) }
        }

        private fun visit(i: Int, depth: Int, globals: Array<DoubleArray?>, out: MutableList<ModelPart>) {
            if (depth > MAX_NODE_DEPTH) throw GlbException("слишком глубокая иерархия узлов")
            val node = nodeAt(i)
            if (isHidden(node["name"])) return
            val meshIndex = (node["mesh"] as? Double)?.toInt()
            if (meshIndex != null) {
                val mesh = meshes.getOrNull(meshIndex).asObj() ?: throw GlbException("неверный номер меша $meshIndex")
                if (!isHidden(mesh["name"])) {
                    for (p in mesh.list("primitives")) {
                        val prim = p.asObj() ?: continue
                        buildPrimitive(i, node, prim, globals)?.let { out.add(it) }
                    }
                }
            }
            for (c in node.list("children")) {
                visit(c.asNum().toInt(), depth + 1, globals, out)
            }
        }

        // ---------- аксессоры ----------

        private class Acc(val count: Int, val comps: Int, val ctype: Int, val normalized: Boolean, val offset: Int, val stride: Int)

        private fun componentSize(ctype: Int): Int = when (ctype) {
            5120, 5121 -> 1
            5122, 5123 -> 2
            5125, 5126 -> 4
            else -> throw GlbException("неизвестный тип компонент $ctype")
        }

        private fun accessor(index: Int): Acc {
            val acc = accessors.getOrNull(index).asObj() ?: throw GlbException("неверный номер аксессора $index")
            if (acc.containsKey("sparse")) throw GlbException("разреженные аксессоры не поддерживаются")
            val count = acc.int("count", 0)
            val comps = when (acc["type"]) {
                "SCALAR" -> 1
                "VEC2" -> 2
                "VEC3" -> 3
                "VEC4", "MAT2" -> 4
                "MAT3" -> 9
                "MAT4" -> 16
                else -> throw GlbException("неизвестный тип аксессора")
            }
            val ctype = acc.int("componentType", 0)
            val csize = componentSize(ctype)
            val viewIndex = (acc["bufferView"] as? Double)?.toInt()
            if (viewIndex == null || count == 0) {
                return Acc(count, comps, ctype, acc["normalized"] == true, -1, 0)
            }
            val view = bufferViews.getOrNull(viewIndex).asObj() ?: throw GlbException("неверный bufferView $viewIndex")
            if (view.int("buffer", 0) != 0 || binStart < 0) throw GlbException("внешние буферы не поддерживаются")
            val stride = view.int("byteStride", 0).takeIf { it > 0 } ?: (comps * csize)
            val offset = binStart + view.int("byteOffset", 0) + acc.int("byteOffset", 0)
            val end = offset.toLong() + (count - 1).toLong() * stride + comps * csize
            if (offset < binStart || end > binStart.toLong() + binLen) throw GlbException("аксессор выходит за пределы буфера")
            return Acc(count, comps, ctype, acc["normalized"] == true, offset, stride)
        }

        private fun component(pos: Int, ctype: Int): Double = when (ctype) {
            5120 -> bb.get(pos).toDouble()
            5121 -> (bb.get(pos).toInt() and 0xFF).toDouble()
            5122 -> bb.getShort(pos).toDouble()
            5123 -> (bb.getShort(pos).toInt() and 0xFFFF).toDouble()
            5125 -> (bb.getInt(pos).toLong() and 0xFFFFFFFFL).toDouble()
            else -> bb.getFloat(pos).toDouble()
        }

        private fun normalize(v: Double, ctype: Int): Double = when (ctype) {
            5120 -> maxOf(v / 127.0, -1.0)
            5121 -> v / 255.0
            5122 -> maxOf(v / 32767.0, -1.0)
            5123 -> v / 65535.0
            else -> v
        }

        fun readFloats(index: Int): FloatArray {
            val a = accessor(index)
            val out = FloatArray(a.count * a.comps)
            if (a.offset < 0) return out
            val csize = componentSize(a.ctype)
            for (i in 0 until a.count) {
                for (c in 0 until a.comps) {
                    val v = component(a.offset + i * a.stride + c * csize, a.ctype)
                    out[i * a.comps + c] = (if (a.normalized) normalize(v, a.ctype) else v).toFloat()
                }
            }
            return out
        }

        // Читает COLOR_0 (VEC3 или VEC4) и приводит его к RGBA на каждую вершину.
        // Нормализованные BYTE/UNSIGNED_BYTE/SHORT/UNSIGNED_SHORT переводятся в диапазон 0..1.
        fun readColors(index: Int, vertexCount: Int): FloatArray {
            val a = accessor(index)
            if (a.comps != 3 && a.comps != 4) {
                throw GlbException("COLOR_0 должен иметь тип VEC3 или VEC4")
            }
            if (a.count != vertexCount) {
                throw GlbException("количество цветов COLOR_0 не совпадает с количеством вершин")
            }
            val out = FloatArray(vertexCount * 4) { 1f }
            if (a.offset < 0) return out
            val csize = componentSize(a.ctype)
            for (i in 0 until vertexCount) {
                for (c in 0 until a.comps) {
                    val raw = component(a.offset + i * a.stride + c * csize, a.ctype)
                    val value = if (a.normalized) normalize(raw, a.ctype) else raw
                    out[i * 4 + c] = value.toFloat().coerceIn(0f, 1f)
                }
            }
            return out
        }

        fun readInts(index: Int): IntArray {
            val a = accessor(index)
            val out = IntArray(a.count * a.comps)
            if (a.offset < 0) return out
            val csize = componentSize(a.ctype)
            for (i in 0 until a.count) {
                for (c in 0 until a.comps) {
                    out[i * a.comps + c] = component(a.offset + i * a.stride + c * csize, a.ctype).toInt()
                }
            }
            return out
        }

        // ---------- материалы и картинки ----------

        private class Material(
            val color: FloatArray, val imageIndex: Int, val texCoord: Int,
            val alphaMode: Int, val alphaCutoff: Float, val wrapS: Int, val wrapT: Int
        )

        private fun colorFactor(list: List<Any?>): FloatArray =
            if (list.size >= 4) FloatArray(4) { list[it].asNum().toFloat() } else floatArrayOf(1f, 1f, 1f, 1f)

        private fun material(index: Int?): Material {
            val m = if (index != null) materials.getOrNull(index).asObj() else null
            val white = floatArrayOf(1f, 1f, 1f, 1f)
            if (m == null) return Material(white, -1, 0, ModelPart.ALPHA_OPAQUE, 0.5f, 10497, 10497)
            val pbr = m.obj("pbrMetallicRoughness")
            val specGloss = m.obj("extensions")?.obj("KHR_materials_pbrSpecularGlossiness")
            val color: FloatArray
            val texInfo: Map<String, Any?>?
            if (pbr != null) {
                color = colorFactor(pbr.list("baseColorFactor"))
                texInfo = pbr.obj("baseColorTexture")
            } else if (specGloss != null) {
                color = colorFactor(specGloss.list("diffuseFactor"))
                texInfo = specGloss.obj("diffuseTexture")
            } else {
                color = white
                texInfo = null
            }
            var imageIndex = -1
            var wrapS = 10497
            var wrapT = 10497
            if (texInfo != null) {
                val tex = textures.getOrNull(texInfo.int("index", -1)).asObj()
                val source = (tex?.get("source") as? Double)?.toInt()
                if (source != null) {
                    imageIndex = loadImage(source)
                    val sampler = samplers.getOrNull(tex.int("sampler", -1)).asObj()
                    if (sampler != null) {
                        wrapS = sampler.int("wrapS", 10497)
                        wrapT = sampler.int("wrapT", 10497)
                    }
                }
            }
            val mode = when (m["alphaMode"]) {
                "BLEND" -> ModelPart.ALPHA_BLEND
                "MASK" -> ModelPart.ALPHA_MASK
                else -> ModelPart.ALPHA_OPAQUE
            }
            return Material(color, imageIndex, texInfo?.int("texCoord", 0) ?: 0, mode,
                m.dbl("alphaCutoff", 0.5).toFloat(), wrapS, wrapT)
        }

        // сжатые байты картинки из bufferView; картинки по внешней ссылке не поддерживаются (материал будет однотонным)
        private fun loadImage(source: Int): Int {
            if (usedImages.containsKey(source)) return source
            val image = images.getOrNull(source).asObj() ?: return -1
            val viewIndex = (image["bufferView"] as? Double)?.toInt() ?: return -1
            val view = bufferViews.getOrNull(viewIndex).asObj() ?: return -1
            val start = binStart + view.int("byteOffset", 0)
            val len = view.int("byteLength", 0)
            if (binStart < 0 || len <= 0 || start < binStart || start.toLong() + len > binStart.toLong() + binLen) return -1
            usedImages[source] = data.copyOfRange(start, start + len)
            return source
        }

        // ---------- геометрия ----------

        private fun buildPrimitive(nodeIndex: Int, node: Map<String, Any?>, prim: Map<String, Any?>, globals: Array<DoubleArray?>): ModelPart? {
            val attrs = prim.obj("attributes") ?: return null
            val posIndex = (attrs["POSITION"] as? Double)?.toInt() ?: return null
            val positions = readFloats(posIndex)
            val vertexCount = positions.size / 3
            if (vertexCount == 0) return null
            val mat = material((prim["material"] as? Double)?.toInt())
            val colorAccessor = (attrs["COLOR_0"] as? Double)?.toInt()
            val vertexColors = colorAccessor?.let { readColors(it, vertexCount) }
                ?: FloatArray(vertexCount * 4) { 1f }

            var indices = when (val ii = (prim["indices"] as? Double)?.toInt()) {
                null -> IntArray(vertexCount) { it }
                else -> readInts(ii)
            }
            indices = when (prim.int("mode", 4)) {
                4 -> indices
                5 -> IntArray(maxOf(indices.size - 2, 0) * 3).also { t ->
                    for (i in 0 until indices.size - 2) {
                        val even = i % 2 == 0
                        t[i * 3] = indices[i]
                        t[i * 3 + 1] = indices[if (even) i + 1 else i + 2]
                        t[i * 3 + 2] = indices[if (even) i + 2 else i + 1]
                    }
                }
                6 -> IntArray(maxOf(indices.size - 2, 0) * 3).also { t ->
                    for (i in 0 until indices.size - 2) {
                        t[i * 3] = indices[0]
                        t[i * 3 + 1] = indices[i + 1]
                        t[i * 3 + 2] = indices[i + 2]
                    }
                }
                else -> return null
            }
            // треугольники с несуществующими вершинами выбрасываются
            var good = 0
            val checked = IntArray(indices.size / 3 * 3)
            for (t in 0 until indices.size / 3) {
                val a = indices[t * 3]; val b = indices[t * 3 + 1]; val c = indices[t * 3 + 2]
                if (a in 0 until vertexCount && b in 0 until vertexCount && c in 0 until vertexCount) {
                    checked[good * 3] = a; checked[good * 3 + 1] = b; checked[good * 3 + 2] = c
                    good++
                }
            }
            if (good == 0) return null
            indices = checked.copyOf(good * 3)

            var normals = (attrs["NORMAL"] as? Double)?.toInt()?.let { readFloats(it) }
            if (normals != null && normals.size != positions.size) normals = null
            val uvAccessor = (attrs["TEXCOORD_${mat.texCoord}"] as? Double)?.toInt()
            var uvs = uvAccessor?.let { readFloats(it) }
            if (uvs == null || uvs.size != vertexCount * 2) uvs = FloatArray(vertexCount * 2)

            val skinIndex = (node["skin"] as? Double)?.toInt()
            val jointsAcc = (attrs["JOINTS_0"] as? Double)?.toInt()
            val weightsAcc = (attrs["WEIGHTS_0"] as? Double)?.toInt()
            val worldPos = FloatArray(positions.size)
            val worldNor = FloatArray(positions.size)
            var flip = false
            if (skinIndex != null && jointsAcc != null && weightsAcc != null) {
                skinVertices(skinIndex, positions, normals, readInts(jointsAcc), readFloats(weightsAcc), globals, worldPos, worldNor)
            } else {
                val m = globals.getOrNull(nodeIndex) ?: identity()
                flip = transformVertices(m, positions, normals, worldPos, worldNor)
            }
            if (normals == null) {
                computeNormals(worldPos, indices, worldNor)
            }
            if (flip) {
                for (t in 0 until indices.size / 3) {
                    val tmp = indices[t * 3 + 1]
                    indices[t * 3 + 1] = indices[t * 3 + 2]
                    indices[t * 3 + 2] = tmp
                }
            }
            return ModelPart(worldPos, worldNor, uvs, indices, mat.imageIndex, mat.color,
                mat.alphaMode, mat.alphaCutoff, mat.wrapS, mat.wrapT, vertexColors)
        }

        // применяет матрицу узла; возвращает true, если она зеркальная (тогда порядок вершин в треугольниках надо поменять)
        private fun transformVertices(m: DoubleArray, pos: FloatArray, nor: FloatArray?, outPos: FloatArray, outNor: FloatArray): Boolean {
            val det = m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])
            // матрица нормалей = обратная транспонированная = матрица алгебраических дополнений / det
            val nm = DoubleArray(9)
            if (kotlin.math.abs(det) > 1e-30) {
                nm[0] = (m[5] * m[10] - m[9] * m[6]) / det; nm[1] = -(m[1] * m[10] - m[9] * m[2]) / det; nm[2] = (m[1] * m[6] - m[5] * m[2]) / det
                nm[3] = -(m[4] * m[10] - m[8] * m[6]) / det; nm[4] = (m[0] * m[10] - m[8] * m[2]) / det; nm[5] = -(m[0] * m[6] - m[4] * m[2]) / det
                nm[6] = (m[4] * m[9] - m[8] * m[5]) / det; nm[7] = -(m[0] * m[9] - m[8] * m[1]) / det; nm[8] = (m[0] * m[5] - m[4] * m[1]) / det
            } else {
                nm[0] = 1.0; nm[4] = 1.0; nm[8] = 1.0
            }
            // nm[r*3+c] - строка r, столбец c; нормаль n' = nm * n
            for (i in 0 until pos.size / 3) {
                val x = pos[i * 3].toDouble(); val y = pos[i * 3 + 1].toDouble(); val z = pos[i * 3 + 2].toDouble()
                outPos[i * 3] = (m[0] * x + m[4] * y + m[8] * z + m[12]).toFloat()
                outPos[i * 3 + 1] = (m[1] * x + m[5] * y + m[9] * z + m[13]).toFloat()
                outPos[i * 3 + 2] = (m[2] * x + m[6] * y + m[10] * z + m[14]).toFloat()
                if (nor != null) {
                    val nx = nor[i * 3].toDouble(); val ny = nor[i * 3 + 1].toDouble(); val nz = nor[i * 3 + 2].toDouble()
                    putNormalized(outNor, i, nm[0] * nx + nm[1] * ny + nm[2] * nz, nm[3] * nx + nm[4] * ny + nm[5] * nz, nm[6] * nx + nm[7] * ny + nm[8] * nz)
                }
            }
            return det < 0
        }

        private fun putNormalized(out: FloatArray, i: Int, x: Double, y: Double, z: Double) {
            val len = sqrt(x * x + y * y + z * z)
            if (len > 1e-20) {
                out[i * 3] = (x / len).toFloat(); out[i * 3 + 1] = (y / len).toFloat(); out[i * 3 + 2] = (z / len).toFloat()
            } else {
                out[i * 3] = 0f; out[i * 3 + 1] = 0f; out[i * 3 + 2] = 1f
            }
        }

        // поза по умолчанию: вершина = сумма весов * (мировая матрица кости * обратная bind-матрица) * вершина.
        // По спецификации матрица самого узла с мешем при этом не применяется
        private fun skinVertices(skinIndex: Int, pos: FloatArray, nor: FloatArray?, joints: IntArray, weights: FloatArray,
                                 globals: Array<DoubleArray?>, outPos: FloatArray, outNor: FloatArray) {
            val skin = skins.getOrNull(skinIndex).asObj() ?: throw GlbException("неверный номер скина $skinIndex")
            val jointNodes = skin.list("joints").map { it.asNum().toInt() }
            val ibm = (skin["inverseBindMatrices"] as? Double)?.toInt()?.let { readFloats(it) }
            val jm = Array(jointNodes.size) { j ->
                val g = globals.getOrNull(jointNodes[j]) ?: identity()
                if (ibm != null && ibm.size >= (j + 1) * 16) {
                    mul(g, DoubleArray(16) { ibm[j * 16 + it].toDouble() })
                } else {
                    g
                }
            }
            val n = pos.size / 3
            if (joints.size < n * 4 || weights.size < n * 4) throw GlbException("данные скелета короче числа вершин")
            for (i in 0 until n) {
                val x = pos[i * 3].toDouble(); val y = pos[i * 3 + 1].toDouble(); val z = pos[i * 3 + 2].toDouble()
                var px = 0.0; var py = 0.0; var pz = 0.0
                var nx = 0.0; var ny = 0.0; var nz = 0.0
                var total = 0.0
                for (k in 0 until 4) {
                    val w = weights[i * 4 + k].toDouble()
                    if (w <= 0.0) continue
                    val j = joints[i * 4 + k]
                    if (j < 0 || j >= jm.size) continue
                    val m = jm[j]
                    total += w
                    px += w * (m[0] * x + m[4] * y + m[8] * z + m[12])
                    py += w * (m[1] * x + m[5] * y + m[9] * z + m[13])
                    pz += w * (m[2] * x + m[6] * y + m[10] * z + m[14])
                    if (nor != null) {
                        val ax = nor[i * 3].toDouble(); val ay = nor[i * 3 + 1].toDouble(); val az = nor[i * 3 + 2].toDouble()
                        nx += w * (m[0] * ax + m[4] * ay + m[8] * az)
                        ny += w * (m[1] * ax + m[5] * ay + m[9] * az)
                        nz += w * (m[2] * ax + m[6] * ay + m[10] * az)
                    }
                }
                if (total > 1e-9) {
                    outPos[i * 3] = (px / total).toFloat(); outPos[i * 3 + 1] = (py / total).toFloat(); outPos[i * 3 + 2] = (pz / total).toFloat()
                    if (nor != null) putNormalized(outNor, i, nx, ny, nz)
                } else {
                    outPos[i * 3] = x.toFloat(); outPos[i * 3 + 1] = y.toFloat(); outPos[i * 3 + 2] = z.toFloat()
                    if (nor != null) putNormalized(outNor, i, nor[i * 3].toDouble(), nor[i * 3 + 1].toDouble(), nor[i * 3 + 2].toDouble())
                }
            }
        }

        private fun computeNormals(pos: FloatArray, indices: IntArray, out: FloatArray) {
            java.util.Arrays.fill(out, 0f)
            for (t in 0 until indices.size / 3) {
                val a = indices[t * 3] * 3; val b = indices[t * 3 + 1] * 3; val c = indices[t * 3 + 2] * 3
                val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
                val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                for (o in intArrayOf(a, b, c)) {
                    out[o] += nx; out[o + 1] += ny; out[o + 2] += nz
                }
            }
            for (i in 0 until out.size / 3) {
                putNormalized(out, i, out[i * 3].toDouble(), out[i * 3 + 1].toDouble(), out[i * 3 + 2].toDouble())
            }
        }
    }
}
