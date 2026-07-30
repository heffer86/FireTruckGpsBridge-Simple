package org.ocfd.gpsreceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.ocfd.gpsreceiver.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            binding.statusText.text = intent?.getStringExtra(GpsReceiveService.EXTRA_STATUS)
                ?: "Waiting for GPS"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startButton.setOnClickListener {
            ContextCompat.startForegroundService(
                this,
                Intent(this, GpsReceiveService::class.java)
            )
            binding.statusText.text = "Listening on UDP port 42424"
        }
        binding.stopButton.setOnClickListener {
            stopService(Intent(this, GpsReceiveService::class.java))
            binding.statusText.text = "Stopped"
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
        val filter = IntentFilter(GpsReceiveService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(
                this,
                statusReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    override fun onStop() {
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }
}
