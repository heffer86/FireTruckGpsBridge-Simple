package org.ocfd.gpsreceiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.ocfd.gpsreceiver.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var receiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            showStatus(intent?.getStringExtra(GpsReceiveService.EXTRA_STATUS))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoreStatus()
        requestNotificationPermission()

        binding.startButton.setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, GpsReceiveService::class.java))
            showStatus("Starting receiver...\nListening on UDP port 42424")
        }

        binding.stopButton.setOnClickListener {
            stopService(Intent(this, GpsReceiveService::class.java))
            getSharedPreferences(GpsReceiveService.PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(GpsReceiveService.PREF_RUNNING, false)
                .putString(GpsReceiveService.PREF_STATUS, "Stopped")
                .apply()
            showStatus("Stopped")
        }

        binding.settingsButton.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        restoreStatus()
        val filter = IntentFilter(GpsReceiveService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        receiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        restoreStatus()
    }

    override fun onStop() {
        if (receiverRegistered) {
            try { unregisterReceiver(statusReceiver) } catch (_: Exception) { }
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun restoreStatus() {
        val prefs = getSharedPreferences(GpsReceiveService.PREFS_NAME, MODE_PRIVATE)
        val running = prefs.getBoolean(GpsReceiveService.PREF_RUNNING, false)
        val fallback = if (running) "Receiver running\nWaiting for GPS data..." else "Stopped"
        showStatus(prefs.getString(GpsReceiveService.PREF_STATUS, fallback))
    }

    private fun showStatus(status: String?) {
        val text = status ?: "Waiting for GPS"
        binding.statusText.text = text
        binding.connectionText.text = when {
            text.startsWith("CONNECTED") -> "● GPS CONNECTED"
            text == "Stopped" || text.contains("error", ignoreCase = true) -> "● STOPPED"
            else -> "● WAITING"
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }
    }
}
