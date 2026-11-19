package com.jaber.screenshare

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private val reqCapture = 1001
    private lateinit var tvState: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvAcc: TextView
    private lateinit var tvErr: TextView
    private lateinit var btnToggle: Button

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvState = findViewById(R.id.tvState)
        tvUrl = findViewById(R.id.tvUrl)
        tvAcc = findViewById(R.id.tvAcc)
        tvErr = findViewById(R.id.tvErr)
        btnToggle = findViewById(R.id.btnToggle)

        btnToggle.setOnClickListener {
            if (ScreenService.running || ScreenService.starting) stopSharing() else requestCapture()
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val first = ScreenService.urls.firstOrNull()
            if (first != null) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("url", first))
                Toast.makeText(this, "آدرس کپی شد", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnAcc).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2002)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun requestCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    private fun stopSharing() {
        startService(Intent(this, ScreenService::class.java).setAction(ScreenService.ACTION_STOP))
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, ScreenService::class.java)
                    .setAction(ScreenService.ACTION_START)
                    .putExtra(ScreenService.EXTRA_CODE, resultCode)
                    .putExtra(ScreenService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, i)
            } else {
                Toast.makeText(this, "اجازهٔ ضبط صفحه داده نشد", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refresh() {
        val run = ScreenService.running
        val starting = ScreenService.starting
        tvState.text = when {
            starting -> "در حال راه‌اندازی…"
            run -> "● اشتراک فعال — ${ScreenService.clientCount} بیننده"
            else -> "اشتراک متوقف است"
        }
        tvUrl.text = if (run) {
            ScreenService.urls.joinToString("\n")
        } else {
            "—"
        }
        btnToggle.text = if (run || starting) "توقف اشتراک" else "شروع اشتراک"
        btnToggle.setBackgroundResource(if (run || starting) R.drawable.bg_btn_stop else R.drawable.bg_btn)
        tvAcc.text = if (InputService.instance != null) "کنترل از راه دور: فعال ✓" else "کنترل از راه دور: غیرفعال"

        val err = ScreenService.lastError
        if (err != null && !run && !starting) {
            tvErr.text = "خطا: $err"
            tvErr.visibility = View.VISIBLE
        } else {
            tvErr.visibility = View.GONE
        }
    }
}
