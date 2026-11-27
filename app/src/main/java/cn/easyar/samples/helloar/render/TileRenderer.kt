package cn.easyar.samples.helloar.render

import cn.easyar.samples.helloar.ui.*
import cn.easyar.samples.helloar.ar.*
import cn.easyar.samples.helloar.puzzle.*
import cn.easyar.samples.helloar.model.*
import cn.easyar.samples.helloar.data.*
import cn.easyar.samples.helloar.util.*

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log

import java.nio.FloatBuffer

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLContext

import cn.easyar.Matrix44F
import cn.easyar.Vec2F

// Draws textured rectangles ("tiles") in the coordinate system of an image target.
// All methods of this class can only be called on one thread with the same OpenGLES context
// (the same rule as for BoxRenderer).
class TileRenderer {
    private var current_context: EGLContext? = null
    private val program: Int
    private val pos_corner: Int
    private val pos_trans: Int
    private val pos_proj: Int
    private val pos_center: Int
    private val pos_size: Int
    private val pos_uvrect: Int
    private val pos_tex: Int
    private val pos_color: Int
    private val pos_solid: Int
    private val vbo_corner: Int
    private val textures = HashMap<String, Int>()

    // corner is in [-0.5, 0.5]; position = center + corner * size (target space, z = 0)
    // uvrect = (u0, v0, u1, v1), v0 is the TOP edge of the texture (v = 0 is the top row of the bitmap)
    private val tile_vert = ("uniform mat4 trans;\n"
            + "uniform mat4 proj;\n"
            + "uniform vec2 center;\n"
            + "uniform vec2 size;\n"
            + "uniform vec4 uvrect;\n"
            + "attribute vec2 corner;\n"
            + "varying vec2 vuv;\n"
            + "\n"
            + "void main(void)\n"
            + "{\n"
            + "    vec2 t = corner + vec2(0.5);\n"
            + "    vuv = vec2(mix(uvrect.x, uvrect.z, t.x), mix(uvrect.w, uvrect.y, t.y));\n"
            + "    gl_Position = proj * trans * vec4(center + corner * size, 0.0, 1.0);\n"
            + "}\n"
            + "\n")

    private val tile_frag = ("#ifdef GL_ES\n"
            + "precision highp float;\n"
            + "#endif\n"
            + "uniform sampler2D tex;\n"
            + "uniform vec4 color;\n"
            + "uniform float solid;\n"
            + "varying vec2 vuv;\n"
            + "\n"
            + "void main(void)\n"
            + "{\n"
            + "    gl_FragColor = mix(texture2D(tex, vuv), color, solid);\n"
            + "}\n"
            + "\n")

    private fun getGLMatrix(m: Matrix44F): FloatArray {
        val d = m.data
        return floatArrayOf(d[0], d[4], d[8], d[12], d[1], d[5], d[9], d[13], d[2], d[6], d[10], d[14], d[3], d[7], d[11], d[15])
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e("TileRenderer", "shader compile failed: " + GLES30.glGetShaderInfoLog(shader))
        }
        return shader
    }

    init {
        current_context = (EGLContext.getEGL() as EGL10).eglGetCurrentContext()
        program = GLES30.glCreateProgram()
        val vertShader = compile(GLES30.GL_VERTEX_SHADER, tile_vert)
        val fragShader = compile(GLES30.GL_FRAGMENT_SHADER, tile_frag)
        GLES30.glAttachShader(program, vertShader)
        GLES30.glAttachShader(program, fragShader)
        GLES30.glLinkProgram(program)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e("TileRenderer", "program link failed: " + GLES30.glGetProgramInfoLog(program))
        }
        GLES30.glDeleteShader(vertShader)
        GLES30.glDeleteShader(fragShader)
        pos_corner = GLES30.glGetAttribLocation(program, "corner")
        pos_trans = GLES30.glGetUniformLocation(program, "trans")
        pos_proj = GLES30.glGetUniformLocation(program, "proj")
        pos_center = GLES30.glGetUniformLocation(program, "center")
        pos_size = GLES30.glGetUniformLocation(program, "size")
        pos_uvrect = GLES30.glGetUniformLocation(program, "uvrect")
        pos_tex = GLES30.glGetUniformLocation(program, "tex")
        pos_color = GLES30.glGetUniformLocation(program, "color")
        pos_solid = GLES30.glGetUniformLocation(program, "solid")

        val buffer = intArrayOf(0)
        GLES30.glGenBuffers(1, buffer, 0)
        vbo_corner = buffer[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo_corner)
        val corners = FloatBuffer.wrap(floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f))
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, corners.limit() * 4, corners, GLES30.GL_STATIC_DRAW)
    }

    fun dispose() {
        if ((EGLContext.getEGL() as EGL10).eglGetCurrentContext() == current_context) { //destroy resources unless the context has lost
            GLES30.glDeleteProgram(program)
            GLES30.glDeleteBuffers(1, intArrayOf(vbo_corner), 0)
            for (id in textures.values) {
                GLES30.glDeleteTextures(1, intArrayOf(id), 0)
            }
        }
        textures.clear()
    }

    fun hasTexture(key: String): Boolean {
        return textures.containsKey(key)
    }

    // uploads the bitmap to a new texture; the caller still owns (and may recycle) the bitmap
    fun setTexture(key: String, bitmap: Bitmap) {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        textures[key] = id[0]
    }

    // draws a part of the texture (uvrect: u0, v0 = top-left, u1, v1 = bottom-right, all in 0..1)
    // as a rectangle of the given size centered at (cx, cy) in the target coordinate system
    fun renderTile(projectionMatrix: Matrix44F, cameraview: Matrix44F, key: String,
                   cx: Float, cy: Float, w: Float, h: Float,
                   u0: Float, v0: Float, u1: Float, v1: Float) {
        val tex = textures[key] ?: return

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(pos_tex, 0)
        GLES30.glUniform1f(pos_solid, 0f)
        drawQuad(projectionMatrix, cameraview, cx, cy, w, h, u0, v0, u1, v1)
    }

    // однотонный прямоугольник (например, затемнение под плитками); в буфер глубины не пишет,
    // поэтому плитки в той же плоскости рисуются поверх него. Рисовать нужно до плиток.
    fun renderSolid(projectionMatrix: Matrix44F, cameraview: Matrix44F,
                    cx: Float, cy: Float, w: Float, h: Float,
                    r: Float, g: Float, b: Float, a: Float) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(program)
        GLES30.glUniform4f(pos_color, r, g, b, a)
        GLES30.glUniform1f(pos_solid, 1f)
        drawQuad(projectionMatrix, cameraview, cx, cy, w, h, 0f, 0f, 1f, 1f)
        GLES30.glDepthMask(true)
    }

    private fun drawQuad(projectionMatrix: Matrix44F, cameraview: Matrix44F,
                         cx: Float, cy: Float, w: Float, h: Float,
                         u0: Float, v0: Float, u1: Float, v1: Float) {
        GLES30.glUniformMatrix4fv(pos_trans, 1, false, getGLMatrix(cameraview), 0)
        GLES30.glUniformMatrix4fv(pos_proj, 1, false, getGLMatrix(projectionMatrix), 0)
        GLES30.glUniform2f(pos_center, cx, cy)
        GLES30.glUniform2f(pos_size, w, h)
        GLES30.glUniform4f(pos_uvrect, u0, v0, u1, v1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo_corner)
        GLES30.glEnableVertexAttribArray(pos_corner)
        GLES30.glVertexAttribPointer(pos_corner, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(pos_corner)
    }

    // the whole texture stretched over the target (size = target size), optionally shifted by (cx, cy)
    fun renderImage(projectionMatrix: Matrix44F, cameraview: Matrix44F, key: String, size: Vec2F, cx: Float, cy: Float) {
        renderTile(projectionMatrix, cameraview, key, cx, cy, size.data[0], size.data[1], 0f, 0f, 1f, 1f)
    }
}
