//================================================================================================================================
//
// Copyright (c) 2015-2025 VisionStar Information Technology (Shanghai) Co., Ltd. All Rights Reserved.
// EasyAR is the registered trademark or trademark of VisionStar Information Technology (Shanghai) Co., Ltd in China
// and other countries for the augmented reality technology developed by VisionStar Information Technology (Shanghai) Co., Ltd.
//
//================================================================================================================================

package cn.easyar.samples.helloar

import android.Manifest
import android.annotation.TargetApi
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import android.view.MotionEvent
import cn.easyar.CameraDevice

import java.util.HashMap
import java.util.Locale

import cn.easyar.Engine
import cn.easyar.ImageTracker

class ARActivity : Activity() {
    companion object {
        private val key = BuildConfig.EASYAR_KEY
    }
    private var glView: GLView? = null

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var hudTime: TextView
    private lateinit var hudMoves: TextView
    private lateinit var hudFind: TextView
    private lateinit var resultPanel: View
    private lateinit var resultStats: TextView
    private var dismissedFinishedAtMs = 0L   // победа, экран результатов которой уже закрыт

    // раз в 200 мс обновляет время, ходы и экран результатов по состоянию пазла
    private val ticker = object : Runnable {
        override fun run() {
            updateHud()
            handler.postDelayed(this, 200)
        }
    }

    // кнопка «Меню» (android:onClick в activity_ar.xml): закрывает AR и возвращает в главное меню
    // кнопки с android:onClick в activity_ar.xml
    fun onSettingsClick(view: View) {
        SettingsActivity.openFromAr(this)
    }

    fun onHistoryClick(view: View) {
        HistoryActivity.open(this)
    }

    fun onPhotoClick(view: View) {
        glView?.takePhoto { saved ->
            runOnUiThread {
                Toast.makeText(this, if (saved) R.string.photo_saved else R.string.photo_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }

    private fun updateHud() {
        val status = glView?.puzzleStatus()
        val elapsed = status?.elapsedMs(SystemClock.elapsedRealtime()) ?: 0L
        hudTime.text = formatTime(elapsed)
        hudMoves.text = getString(R.string.hud_moves, status?.moves ?: 0)
        hudFind.visibility = if (glView?.targetVisible() == true || status?.solved == true) View.GONE else View.VISIBLE
        if (status != null && status.solved && status.finishedAtMs != dismissedFinishedAtMs) {
            if (resultPanel.visibility != View.VISIBLE) {
                resultStats.text = getString(
                    if (status.isNewRecord) R.string.result_stats_new_record else R.string.result_stats,
                    formatTime(elapsed), status.moves
                )
                resultPanel.visibility = View.VISIBLE
            }
        } else {
            resultPanel.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar)
        window.setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        hudTime = findViewById(R.id.hud_time)
        hudMoves = findViewById(R.id.hud_moves)
        hudFind = findViewById(R.id.hud_find)
        resultPanel = findViewById(R.id.result_panel)
        resultStats = findViewById(R.id.result_stats)
        findViewById<View>(R.id.button_shuffle).setOnClickListener {
            glView?.reshufflePuzzle()
        }
        // подсказка работает, пока кнопка удерживается
        findViewById<View>(R.id.button_hint).setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> glView?.setHintVisible(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> glView?.setHintVisible(false)
            }
            false
        }
        findViewById<View>(R.id.button_again).setOnClickListener {
            glView?.puzzleStatus()?.let { dismissedFinishedAtMs = it.finishedAtMs }
            resultPanel.visibility = View.GONE
            glView?.reshufflePuzzle()
        }

        if (!Engine.initialize(this, key)) {
            Log.e("HelloAR", "Initialization Failed.")
            Toast.makeText(this@ARActivity, Engine.errorMessage(), Toast.LENGTH_LONG).show()
            return
        }

        if (!CameraDevice.isAvailable()) {
            Toast.makeText(this@ARActivity, "CameraDevice not available.", Toast.LENGTH_LONG).show()
            return
        }
        if (!ImageTracker.isAvailable()) {
            Toast.makeText(this@ARActivity, "ImageTracker not available.", Toast.LENGTH_LONG).show()
            return
        }

        glView = GLView(this)

        requestCameraPermission(object : PermissionCallback {
            override fun onSuccess() {
                findViewById<ViewGroup>(R.id.preview).addView(glView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }

            override fun onFailure() {}
        })
    }

    private interface PermissionCallback {
        fun onSuccess()
        fun onFailure()
    }

    private val permissionCallbacks = HashMap<Int, PermissionCallback>()
    private var permissionRequestCodeSerial = 0
    @TargetApi(23)
    private fun requestCameraPermission(callback: PermissionCallback) {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                val requestCode = permissionRequestCodeSerial
                permissionRequestCodeSerial += 1
                permissionCallbacks[requestCode] = callback
                requestPermissions(arrayOf(Manifest.permission.CAMERA), requestCode)
            } else {
                callback.onSuccess()
            }
        } else {
            callback.onSuccess()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (permissionCallbacks.containsKey(requestCode)) {
            val callback = permissionCallbacks[requestCode]!!
            permissionCallbacks.remove(requestCode)
            var executed = false
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    executed = true
                    callback.onFailure()
                }
            }
            if (!executed) {
                callback.onSuccess()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onResume() {
        super.onResume()
        if (glView != null) {
            glView!!.onResume()
        }
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        glView?.setHintVisible(false)
        if (glView != null) {
            glView!!.onPause()
        }
        super.onPause()
    }
}
