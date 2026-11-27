//================================================================================================================================
//
// Copyright (c) 2015-2025 VisionStar Information Technology (Shanghai) Co., Ltd. All Rights Reserved.
// EasyAR is the registered trademark or trademark of VisionStar Information Technology (Shanghai) Co., Ltd in China
// and other countries for the augmented reality technology developed by VisionStar Information Technology (Shanghai) Co., Ltd.
//
//================================================================================================================================

package cn.easyar.samples.helloar.ar

import cn.easyar.samples.helloar.ui.*
import cn.easyar.samples.helloar.puzzle.*
import cn.easyar.samples.helloar.model.*
import cn.easyar.samples.helloar.render.*
import cn.easyar.samples.helloar.data.*
import cn.easyar.samples.helloar.util.*

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import javax.microedition.khronos.opengles.GL10

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager

import cn.easyar.Engine

class GLView(context: Context) : GLSurfaceView(context) {
    private val lock = Any()
    private var finishing = false

    private var initialized = false
    private var width_ = 1
    private var height_ = 1

    private var helloAR: HelloAR? = null

    private val activity: Activity?
        get() {
            var context = context
            while (context is ContextWrapper) {
                if (context is Activity) {
                    return context
                }
                context = context.baseContext
            }
            return null
        }

    init {
        //setPreserveEGLContextOnPause(true) //uncomment if EGL context need to be preserved on pause
        setEGLContextFactory(ContextFactory())
        setEGLWindowSurfaceFactory(WindowSurfaceFactory())
        setEGLConfigChooser(ConfigChooser())

        this.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
                if (!initialized) {
                    initialized = true
                    helloAR = HelloAR(context, PuzzleSettings.load(context))
                    helloAR!!.initialize()
                } else {
                    helloAR!!.recreate_context()
                }
                helloAR!!.start()
            }

            override fun onSurfaceChanged(gl: GL10, w: Int, h: Int) {
                width_ = w
                height_ = h
            }

            override fun onDrawFrame(gl: GL10) {
                if (!initialized) {
                    return
                }
                helloAR!!.render(width_, height_, GetScreenRotation())
            }
        })
        this.setZOrderMediaOverlay(true)
    }

    // состояние активного пазла (время, ходы, собран ли) для интерфейса; null, пока пазла нет
    fun puzzleStatus(): PuzzleStatus? = helloAR?.status

    // true, пока камера видит картинку-цель
    fun targetVisible(): Boolean = helloAR?.targetVisible() ?: false

    fun reshufflePuzzle() {
        helloAR?.requestReshuffle()
    }

    // сохраняет фото того, что сейчас на экране AR; callback вызывается позже, не в UI-потоке
    fun takePhoto(callback: (Boolean) -> Unit) {
        val ar = helloAR
        if (ar != null) {
            ar.requestPhoto(callback)
        } else {
            callback(false)
        }
    }

    // подсказка: пока true, поверх плиток показывается целая картинка
    fun setHintVisible(visible: Boolean) {
        helloAR?.hintVisible = visible
    }

    // тап по экрану передаётся в HelloAR в нормализованных координатах (NDC: -1..1, y вверх)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (width > 0 && height > 0) {
                    helloAR?.onTouch(2f * event.x / width - 1f, 1f - 2f * event.y / height)
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun onSurfaceDestroyed() {
        val b: Boolean
        synchronized(lock) {
            b = finishing
        }
        if (initialized && b) {
            initialized = false
            helloAR!!.stop()
            helloAR!!.dispose()
            helloAR = null
        }
    }

    override fun onResume() {
        super.onResume()
        Engine.onResume()
        // вернулись, например, с экрана настроек: подхватываем изменения
        helloAR?.settings = PuzzleSettings.load(context)
    }

    override fun onPause() {
        val a = activity
        if (a != null) {
            val b = a.isFinishing
            synchronized(lock) {
                finishing = b
            }
        }
        Engine.onPause()
        super.onPause()
    }

    private fun GetScreenRotation(): Int {
        val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val orientation: Int
        when (rotation) {
            Surface.ROTATION_0 -> orientation = 0
            Surface.ROTATION_90 -> orientation = 90
            Surface.ROTATION_180 -> orientation = 180
            Surface.ROTATION_270 -> orientation = 270
            else -> orientation = 0
        }
        return orientation
    }

    private class ContextFactory : GLSurfaceView.EGLContextFactory {

        override fun createContext(egl: EGL10, display: EGLDisplay, eglConfig: EGLConfig): EGLContext {
            val context: EGLContext
            val attrib = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE)
            context = egl.eglCreateContext(display, eglConfig, EGL10.EGL_NO_CONTEXT, attrib)
            return context
        }

        override fun destroyContext(egl: EGL10, display: EGLDisplay, context: EGLContext) {
            egl.eglDestroyContext(display, context)
        }

        companion object {
            private val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        }
    }

    private inner class WindowSurfaceFactory : GLSurfaceView.EGLWindowSurfaceFactory {
        override fun createWindowSurface(egl: EGL10, display: EGLDisplay, config: EGLConfig, nativeWindow: Any): EGLSurface? {
            var result: EGLSurface? = null
            try {
                result = egl.eglCreateWindowSurface(display, config, nativeWindow, null)
            } catch (e: IllegalArgumentException) {
                // This exception indicates that the surface flinger surface
                // is not valid. This can happen if the surface flinger surface has
                // been torn down, but the application has not yet been
                // notified via SurfaceHolder.Callback.surfaceDestroyed.
                // In theory the application should be notified first,
                // but in practice sometimes it is not. See b/4588890
                Log.e("GLSurfaceView", "eglCreateWindowSurface", e)
            }

            return result
        }

        override fun destroySurface(egl: EGL10, display: EGLDisplay, surface: EGLSurface) {
            onSurfaceDestroyed()
            egl.eglDestroySurface(display, surface)
        }
    }

    private class ConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val EGL_OPENGL_ES2_BIT = 0x0004
            val attrib = intArrayOf(EGL10.EGL_RED_SIZE, 4, EGL10.EGL_GREEN_SIZE, 4, EGL10.EGL_BLUE_SIZE, 4, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL10.EGL_NONE)

            val num_config = IntArray(1)
            egl.eglChooseConfig(display, attrib, null, 0, num_config)

            val numConfigs = num_config[0]
            if (numConfigs <= 0)
                throw IllegalArgumentException("fail to choose EGL configs")

            val configs = arrayOfNulls<EGLConfig>(numConfigs)
            egl.eglChooseConfig(display, attrib, configs, numConfigs, num_config)

            for (config in configs) {
                val `val` = IntArray(1)
                var r = 0
                var g = 0
                var b = 0
                var a = 0
                var d = 0
                if (egl.eglGetConfigAttrib(display, config, EGL10.EGL_DEPTH_SIZE, `val`))
                    d = `val`[0]
                if (d < 16)
                    continue

                if (egl.eglGetConfigAttrib(display, config, EGL10.EGL_RED_SIZE, `val`))
                    r = `val`[0]
                if (egl.eglGetConfigAttrib(display, config, EGL10.EGL_GREEN_SIZE, `val`))
                    g = `val`[0]
                if (egl.eglGetConfigAttrib(display, config, EGL10.EGL_BLUE_SIZE, `val`))
                    b = `val`[0]
                if (egl.eglGetConfigAttrib(display, config, EGL10.EGL_ALPHA_SIZE, `val`))
                    a = `val`[0]
                if (r == 8 && g == 8 && b == 8 && a == 0)
                    return config!!
            }

            return configs[0]!!
        }
    }
}
