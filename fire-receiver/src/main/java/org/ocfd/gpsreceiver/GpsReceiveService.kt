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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GpsReceiveService : Service() {
    companion object {
        const val ACTION_STATUS = "org.ocfd.gpsreceiver.STATUS"
        const val EXTRA_STATUS = "status"
        const val PREFS_NAME = "receiver_state"
        const val PREF_RUNNING = "running"
        const val PREF_STATUS = "status"
        const val PREF_LAST_PACKET = "last_packet"

        private const val CHANNEL_ID = "gps_receiver"
        private const val NOTIFICATION_ID = 201
        private const val PORT = 42424
        private const val PROVIDER_NAME = LocationManager.GPS_PROVIDER
    }

    private lateinit var locationManager: LocationManager
    private var socket: DatagramSocket? = null
    private var executor: ExecutorService? = null
    @Volatile private var running = false
    private var packetCount = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        locationManager = getSystemService(LocationManager::class.java)
        startForeground(NOTIFICATION_ID, makeNotification("Starting receiver"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY

        running = true
        saveState(true, "Waiting for GPS packets on UDP port $PORT")
        executor = Executors.newSingleThreadExecutor()
        executor?.execute { receiveLoop() }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun configureMockProvider() {
        try {
            locationManager.addTestProvider(
                PROVIDER_NAME,
                false, false, false, false,
                true, true, true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )
        } catch (_: IllegalArgumentException) {
            // GPS provider already exists. Selecting this app as the mock-location app
            // grants permission to replace its reported position.
        }
        locationManager.setTestProviderEnabled(PROVIDER_NAME, true)
    }

    private fun receiveLoop() {
        try {
            configureMockProvider()
            socket = DatagramSocket(PORT).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 5_000
            }
            val buffer = ByteArray(2_048)

            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)

                    val json = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                    val location = jsonToLocation(json)
                    locationManager.setTestProviderLocation(PROVIDER_NAME, location)
                    packetCount++

                    val status = buildStatus(location, packet.address.hostAddress ?: "Unknown")
                    saveState(true, status)
                } catch (_: java.net.SocketTimeoutException) {
                    val lastPacket = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getLong(PREF_LAST_PACKET, 0L)
                    if (lastPacket > 0L && System.currentTimeMillis() - lastPacket > 10_000L) {
                        saveState(true, "Waiting for transmitter\nNo GPS packet received for 10+ seconds\nPackets received: $packetCount")
                    }
                }
            }
        } catch (_: SecurityException) {
            saveState(
                false,
                "Mock-location permission required\nOpen Developer Options → Select mock location app → GPS Receiver, then press Start"
            )
            stopSelf()
        } catch (_: SocketException) {
            if (running) saveState(false, "Receiver socket stopped unexpectedly")
        } catch (exception: Exception) {
            saveState(false, "Receiver error: ${exception.message ?: exception.javaClass.simpleName}")
        }
    }

    private fun buildStatus(location: Location, sourceIp: String): String {
        val updated = SimpleDateFormat("h:mm:ss a", Locale.US).format(Date())
        val speedMph = if (location.hasSpeed()) location.speed * 2.2369363 else null
        val altitudeFeet = if (location.hasAltitude()) location.altitude * 3.28084 else null

        return buildString {
            append("CONNECTED\n")
            append(String.format(Locale.US, "Latitude: %.6f\n", location.latitude))
            append(String.format(Locale.US, "Longitude: %.6f\n", location.longitude))
            append(String.format(Locale.US, "Accuracy: ±%.0f m\n", location.accuracy))
            if (speedMph != null) append(String.format(Locale.US, "Speed: %.1f mph\n", speedMph))
            if (location.hasBearing()) append(String.format(Locale.US, "Heading: %.0f°\n", location.bearing))
            if (altitudeFeet != null) append(String.format(Locale.US, "Altitude: %.0f ft\n", altitudeFeet))
            append("Source IP: $sourceIp\n")
            append("Last update: $updated\n")
            append("Packets received: $packetCount")
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

    private fun saveState(isRunning: Boolean, status: String) {
        val now = System.currentTimeMillis()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_RUNNING, isRunning)
            .putString(PREF_STATUS, status)
            .apply {
                if (status.startsWith("CONNECTED")) putLong(PREF_LAST_PACKET, now)
            }
            .apply()

        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, makeNotification(status))

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
        saveState(false, "Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun makeNotification(message: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("GPS Receiver")
            .setContentText(message.lineSequence().firstOrNull() ?: message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GPS receiver", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
