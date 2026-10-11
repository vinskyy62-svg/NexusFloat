package com.nexusfloat.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var tvPermission: TextView
    private lateinit var tvService: TextView
    private lateinit var btnPermission: Button
    private lateinit var btnToggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvPermission = findViewById(R.id.tvPermission)
        tvService = findViewById(R.id.tvService)
        btnPermission = findViewById(R.id.btnPermission)
        btnToggle = findViewById(R.id.btnToggle)

        btnPermission.setOnClickListener { openOverlaySettings() }
        btnToggle.setOnClickListener { toggleService() }
    }

    override fun onStart() {
        super.onStart()
        FloatingService.listener = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onStop() {
        FloatingService.listener = null
        super.onStop()
    }

    private fun refresh() {
        val granted = Settings.canDrawOverlays(this)
        val running = FloatingService.running
        tvPermission.setText(if (granted) R.string.perm_ok else R.string.perm_no)
        tvService.setText(if (running) R.string.svc_on else R.string.svc_off)
        btnPermission.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
        btnToggle.setText(if (running) R.string.btn_stop else R.string.btn_start)
    }

    private fun openOverlaySettings() {
        val uri = Uri.parse("package:$packageName")
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, R.string.need_perm, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleService() {
        val intent = Intent(this, FloatingService::class.java)
        if (FloatingService.running) {
            stopService(intent)
            refresh()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_perm, Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Gagal memulai service", Toast.LENGTH_LONG).show()
        }
    }
}
