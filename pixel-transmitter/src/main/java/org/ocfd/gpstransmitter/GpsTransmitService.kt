package org.ocfd.gpstransmitter

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GpsTransmitService : Service(), LocationListener {
    companion object {
        private const val CHANNEL_ID = "gps_transmitter"
        private const val NOTIFICATION_ID = 101
        private const val PORT = 42424
    }

    private lateinit var locationManager: LocationManager
    private var socket: DatagramSocket? = null
    private var executor: ExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, makeNotification("Waiting for GPS fix"))
        locationManager = getSystemService(LocationManager::class.java)
        socket = DatagramSocket().apply {
            broadcast = true
            reuseAddress = true
        }
        executor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1_000L,
            0f,
            this,
            Looper.getMainLooper()
        )
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        val packetBytes = JSONObject().apply {
            put("version", 1)
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("altitude", if (location.hasAltitude()) location.altitude else JSONObject.NULL)
            put("accuracy", if (location.hasAccuracy()) location.accuracy else 10f)
            put("speed", if (location.hasSpeed()) location.speed else JSONObject.NULL)
            put("bearing", if (location.hasBearing()) location.bearing else JSONObject.NULL)
            put("time", location.time)
        }.toString().toByteArray(Charsets.UTF_8)

        executor?.execute {
            try {
                val datagram = DatagramPacket(
                    packetBytes,
                    packetBytes.size,
                    InetAddress.getByName("192.168.173.14"),
                    PORT
                )
                socket?.send(datagram)
                val text = String.format(
                    Locale.US,
                    "%.6f, %.6f (±%d m)",
                    location.latitude,
                    location.longitude,
                    location.accuracy.toInt()
                )
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, makeNotification(text))
            } catch (_: Exception) {
                // The next GPS update will retry automatically.
            }
        }
    }

    override fun onDestroy() {
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
        socket?.close()
        executor?.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun makeNotification(message: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("GPS Transmitter")
            .setContentText(message)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "GPS transmitter",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
