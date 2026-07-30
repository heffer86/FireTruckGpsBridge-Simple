package org.ocfd.gpsreceiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GpsReceiveService : Service() {
    companion object {
        const val ACTION_STATUS = "org.ocfd.gpsreceiver.STATUS"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL_ID = "gps_receiver"
        private const val NOTIFICATION_ID = 201
        private const val PORT = 42424
        private const val PROVIDER_NAME = LocationManager.GPS_PROVIDER
    }

    private lateinit var locationManager: LocationManager
    private var socket: DatagramSocket? = null
    private var executor: ExecutorService? = null
    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, makeNotification("Waiting for transmitter"))
        locationManager = getSystemService(LocationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true
        executor = Executors.newSingleThreadExecutor()
        executor?.execute { receiveLoop() }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun configureMockProvider() {
        try {
            locationManager.addTestProvider(
                PROVIDER_NAME,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )
        } catch (_: IllegalArgumentException) {
            // Provider already exists or is already registered as a test provider.
        }
        locationManager.setTestProviderEnabled(PROVIDER_NAME, true)
    }

    private fun receiveLoop() {
        try {
            configureMockProvider()
            socket = DatagramSocket(PORT).apply {
                reuseAddress = true
                broadcast = true
            }
            val buffer = ByteArray(2_048)

            while (running) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket?.receive(packet)
                val json = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                val location = jsonToLocation(json)
                locationManager.setTestProviderLocation(PROVIDER_NAME, location)

                val status = String.format(
                    Locale.US,
                    "Connected: %s\n%.6f, %.6f\nAccuracy ±%d m",
                    packet.address.hostAddress ?: "transmitter",
                    location.latitude,
                    location.longitude,
                    location.accuracy.toInt()
                )
                postStatus(status)
            }
        } catch (_: SecurityException) {
            postStatus("Select GPS Receiver under Developer Options → Select mock location app, then press Start again.")
        } catch (_: SocketException) {
            if (running) postStatus("Receiver socket stopped unexpectedly")
        } catch (exception: Exception) {
            postStatus("Receiver error: ${exception.message ?: exception.javaClass.simpleName}")
        }
    }

    private fun jsonToLocation(json: JSONObject): Location =
        Location(PROVIDER_NAME).apply {
            latitude = json.getDouble("latitude")
            longitude = json.getDouble("longitude")
            accuracy = json.optDouble("accuracy", 10.0).toFloat()
            if (!json.isNull("altitude")) altitude = json.getDouble("altitude")
            if (!json.isNull("speed")) speed = json.getDouble("speed").toFloat()
            if (!json.isNull("bearing")) bearing = json.getDouble("bearing").toFloat()
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

    private fun postStatus(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, makeNotification(status.replace('\n', ' ')))
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, status)
        )
    }

    override fun onDestroy() {
        running = false
        socket?.close()
        executor?.shutdownNow()
        try {
            locationManager.removeTestProvider(PROVIDER_NAME)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun makeNotification(message: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("GPS Receiver")
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
                    "GPS receiver",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
