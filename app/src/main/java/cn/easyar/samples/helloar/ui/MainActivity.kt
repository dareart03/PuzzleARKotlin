//================================================================================================================================
//
// Copyright (c) 2015-2025 VisionStar Information Technology (Shanghai) Co., Ltd. All Rights Reserved.
// EasyAR is the registered trademark or trademark of VisionStar Information Technology (Shanghai) Co., Ltd in China
// and other countries for the augmented reality technology developed by VisionStar Information Technology (Shanghai) Co., Ltd.
//
//================================================================================================================================

package cn.easyar.samples.helloar.ui

import cn.easyar.samples.helloar.ar.*
import cn.easyar.samples.helloar.puzzle.*
import cn.easyar.samples.helloar.model.*
import cn.easyar.samples.helloar.render.*
import cn.easyar.samples.helloar.data.*
import cn.easyar.samples.helloar.util.*
import cn.easyar.samples.helloar.R

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.button_start).setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                val intent = Intent(this@MainActivity, ARActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(intent)
            }
        })

        findViewById<View>(R.id.button_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.button_collection).setOnClickListener {
            CollectionActivity.open(this)
        }
        findViewById<View>(R.id.button_history).setOnClickListener {
            HistoryActivity.open(this)
        }
    }
}
