package cn.easyar.samples.helloar

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log

import java.nio.ByteBuffer
import java.nio.ByteOrder

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLContext

import cn.easyar.Matrix44F

// Рисует 3D-модели (GlbModel) в системе координат картинки-цели.
// Все методы вызываются только в GL-потоке с одним и тем же контекстом (как у TileRenderer).
class ModelRenderer {
    private class GpuPart(
        val vbo: Int, val ibo: Int, val indexCount: Int, val texture: Int,
        val color: FloatArray, val alphaMode: Int, val alphaCutoff: Float
    )

    private class GpuModel(val parts: List<GpuPart>, val textures: List<Int>, val pivotZ: Float)

    private var currentContext: EGLContext? = null
    private val program: Int
    private val posPos: Int
    private val posNormal: Int
    private val posUv: Int
    private val posColor: Int
    private val locTrans: Int
    private val locProj: Int
    private val locModel: Int
    private val locTex: Int
    private val locHasTex: Int
    private val locColor: Int
    private val locAlphaMode: Int
    private val locCutoff: Int
    private val locLight: Int
    private val models = HashMap<String, GpuModel>()

    private val vert = ("uniform mat4 trans;\n"
            + "uniform mat4 proj;\n"
            + "uniform mat4 model;\n"
            + "attribute vec3 aPos;\n"
            + "attribute vec3 aNormal;\n"
            + "attribute vec2 aUv;\n"
            + "attribute vec4 aColor;\n"
            + "varying vec3 vNormal;\n"
            + "varying vec2 vUv;\n"
            + "varying vec4 vColor;\n"
            + "\n"
            + "void main(void)\n"
            + "{\n"
            + "    vNormal = (model * vec4(aNormal, 0.0)).xyz;\n"
            + "    vUv = aUv;\n"
            + "    vColor = aColor;\n"
            + "    gl_Position = proj * trans * (model * vec4(aPos, 1.0));\n"
            + "}\n")

    private val frag = ("#ifdef GL_ES\n"
            + "precision highp float;\n"
            + "#endif\n"
            + "uniform sampler2D tex;\n"
            + "uniform float hasTex;\n"
            + "uniform vec4 color;\n"
            + "uniform float alphaMode;\n"
            + "uniform float cutoff;\n"
            + "uniform vec3 lightDir;\n"
            + "varying vec3 vNormal;\n"
            + "varying vec2 vUv;\n"
            + "varying vec4 vColor;\n"
            + "\n"
            + "void main(void)\n"
            + "{\n"
            + "    vec4 base = color * vColor;\n"
            + "    if (hasTex > 0.5) {\n"
            + "        base = base * texture2D(tex, vUv);\n"
            + "    }\n"
            + "    if (alphaMode > 0.5 && alphaMode < 1.5 && base.a < cutoff) {\n"
            + "        discard;\n"
            + "    }\n"
            + "    vec3 n = normalize(vNormal);\n"
            + "    if (!gl_FrontFacing) {\n"
            + "        n = -n;\n"
            + "    }\n"
            + "    float light = 0.55 + 0.45 * max(dot(n, lightDir), 0.0);\n"
            + "    float alpha = alphaMode > 1.5 ? base.a : 1.0;\n"
            + "    gl_FragColor = vec4(base.rgb * light, alpha);\n"
            + "}\n")

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e("ModelRenderer", "shader compile failed: " + GLES30.glGetShaderInfoLog(shader))
        }
        return shader
    }

    init {
        currentContext = (EGLContext.getEGL() as EGL10).eglGetCurrentContext()
        program = GLES30.glCreateProgram()
        val v = compile(GLES30.GL_VERTEX_SHADER, vert)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, frag)
        GLES30.glAttachShader(program, v)
        GLES30.glAttachShader(program, f)
        GLES30.glLinkProgram(program)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e("ModelRenderer", "program link failed: " + GLES30.glGetProgramInfoLog(program))
        }
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        posPos = GLES30.glGetAttribLocation(program, "aPos")
        posNormal = GLES30.glGetAttribLocation(program, "aNormal")
        posUv = GLES30.glGetAttribLocation(program, "aUv")
        posColor = GLES30.glGetAttribLocation(program, "aColor")
        locTrans = GLES30.glGetUniformLocation(program, "trans")
        locProj = GLES30.glGetUniformLocation(program, "proj")
        locModel = GLES30.glGetUniformLocation(program, "model")
        locTex = GLES30.glGetUniformLocation(program, "tex")
        locHasTex = GLES30.glGetUniformLocation(program, "hasTex")
        locColor = GLES30.glGetUniformLocation(program, "color")
        locAlphaMode = GLES30.glGetUniformLocation(program, "alphaMode")
        locCutoff = GLES30.glGetUniformLocation(program, "cutoff")
        locLight = GLES30.glGetUniformLocation(program, "lightDir")
    }

    fun dispose() {
        if ((EGLContext.getEGL() as EGL10).eglGetCurrentContext() == currentContext) { // если контекст потерян, ресурсы уже освобождены
            GLES30.glDeleteProgram(program)
            for (m in models.values) {
                release(m)
            }
        }
        models.clear()
    }

    private fun release(m: GpuModel) {
        for (p in m.parts) {
            GLES30.glDeleteBuffers(2, intArrayOf(p.vbo, p.ibo), 0)
        }
        if (m.textures.isNotEmpty()) {
            GLES30.glDeleteTextures(m.textures.size, m.textures.toIntArray(), 0)
        }
    }

    fun has(key: String): Boolean = models.containsKey(key)

    // середина высоты фигурки (ширина картинки = 1)
    fun pivotZ(key: String): Float = models[key]?.pivotZ ?: 0f

    private fun getGLMatrix(m: Matrix44F): FloatArray {
        val d = m.data
        return floatArrayOf(d[0], d[4], d[8], d[12], d[1], d[5], d[9], d[13], d[2], d[6], d[10], d[14], d[3], d[7], d[11], d[15])
    }

    // загружает модель в память видеокарты; картинки из loaded.bitmaps остаются у вызывающего (их можно сразу recycle)
    fun upload(key: String, loaded: LoadedModel) {
        models.remove(key)?.let { release(it) }
        val textureIds = HashMap<Int, Int>()
        for ((index, bitmap) in loaded.bitmaps) {
            val part = loaded.model.parts.firstOrNull { it.imageIndex == index }
            textureIds[index] = createTexture(bitmap, part?.wrapS ?: GLES30.GL_REPEAT, part?.wrapT ?: GLES30.GL_REPEAT)
        }
        val parts = ArrayList<GpuPart>()
        for (p in loaded.model.parts) {
            val vertexCount = p.positions.size / 3
            // На вершину: position(3) + normal(3) + uv(2) + color(4) = 12 float.
            val interleaved = ByteBuffer.allocateDirect(vertexCount * 12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in 0 until vertexCount) {
                interleaved.put(p.positions, i * 3, 3)
                interleaved.put(p.normals, i * 3, 3)
                interleaved.put(p.uvs, i * 2, 2)
                interleaved.put(p.colors, i * 4, 4)
            }
            interleaved.position(0)
            val indices = ByteBuffer.allocateDirect(p.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            indices.put(p.indices)
            indices.position(0)
            val ids = IntArray(2)
            GLES30.glGenBuffers(2, ids, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexCount * 12 * 4, interleaved, GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[1])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, p.indices.size * 4, indices, GLES30.GL_STATIC_DRAW)
            parts.add(GpuPart(ids[0], ids[1], p.indices.size, textureIds[p.imageIndex] ?: 0, p.color, p.alphaMode, p.alphaCutoff))
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (p in loaded.model.parts) {
            for (i in 0 until p.positions.size / 3) {
                minZ = minOf(minZ, p.positions[i * 3 + 2])
                maxZ = maxOf(maxZ, p.positions[i * 3 + 2])
            }
        }
        models[key] = GpuModel(parts, textureIds.values.toList(), if (minZ <= maxZ) (minZ + maxZ) / 2 else 0f)
    }

    private fun createTexture(bitmap: Bitmap, wrapS: Int, wrapT: Int): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrapS)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrapT)
        return id[0]
    }

    // Рисует модель key; placement (см. ModelPose) переводит подогнанную модель в систему картинки и задаёт свет
    fun render(projectionMatrix: Matrix44F, cameraview: Matrix44F, key: String, placement: ModelPlacement) {
        val modelMatrix = placement.matrix
        val model = models[key] ?: return

        GLES30.glUseProgram(program)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glUniformMatrix4fv(locTrans, 1, false, getGLMatrix(cameraview), 0)
        GLES30.glUniformMatrix4fv(locProj, 1, false, getGLMatrix(projectionMatrix), 0)
        GLES30.glUniformMatrix4fv(locModel, 1, false, modelMatrix, 0)
        GLES30.glUniform3f(locLight, placement.light[0], placement.light[1], placement.light[2])
        GLES30.glUniform1i(locTex, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        // сначала непрозрачные куски, потом полупрозрачные (они не пишут в буфер глубины)
        for (pass in 0..1) {
            if (pass == 1) {
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glDepthMask(false)
            } else {
                GLES30.glDisable(GLES30.GL_BLEND)
            }
            for (p in model.parts) {
                if ((p.alphaMode == ModelPart.ALPHA_BLEND) != (pass == 1)) continue
                drawPart(p)
            }
        }
        GLES30.glDepthMask(true)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun drawPart(p: GpuPart) {
        GLES30.glUniform4f(locColor, p.color[0], p.color[1], p.color[2], p.color[3])
        GLES30.glUniform1f(locAlphaMode, p.alphaMode.toFloat())
        GLES30.glUniform1f(locCutoff, p.alphaCutoff)
        GLES30.glUniform1f(locHasTex, if (p.texture != 0) 1f else 0f)
        if (p.texture != 0) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, p.texture)
        }
        val stride = 12 * 4
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, p.vbo)
        GLES30.glEnableVertexAttribArray(posPos)
        GLES30.glVertexAttribPointer(posPos, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(posNormal)
        GLES30.glVertexAttribPointer(posNormal, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glEnableVertexAttribArray(posUv)
        GLES30.glVertexAttribPointer(posUv, 2, GLES30.GL_FLOAT, false, stride, 6 * 4)
        GLES30.glEnableVertexAttribArray(posColor)
        GLES30.glVertexAttribPointer(posColor, 4, GLES30.GL_FLOAT, false, stride, 8 * 4)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, p.ibo)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, p.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glDisableVertexAttribArray(posPos)
        GLES30.glDisableVertexAttribArray(posNormal)
        GLES30.glDisableVertexAttribArray(posUv)
        GLES30.glDisableVertexAttribArray(posColor)
    }
}
