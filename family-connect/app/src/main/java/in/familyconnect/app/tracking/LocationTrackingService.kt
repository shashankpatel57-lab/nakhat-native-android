package in.familyconnect.app.tracking

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import in.familyconnect.app.MainActivity
import in.familyconnect.app.R
import kotlin.math.roundToInt

class LocationTrackingService : Service() {
    private lateinit var client: FusedLocationProviderClient
    private var overSince = 0L
    private var overspeedSent = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val l = result.lastLocation ?: return
            if (l.accuracy > 100f) return
            val speed = (l.speed.coerceAtLeast(0f) * 3.6f).roundToInt()
            getSharedPreferences("tracking", MODE_PRIVATE).edit()
                .putLong("lat", java.lang.Double.doubleToRawLongBits(l.latitude))
                .putLong("lon", java.lang.Double.doubleToRawLongBits(l.longitude))
                .putInt("speed", speed)
                .putLong("time", System.currentTimeMillis())
                .apply()
            validateSpeed(speed)
            updateNotification(speed)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        client = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(7301, trackingNotification(0))
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 8000L)
            .setMinUpdateIntervalMillis(4000L)
            .setMaxUpdateDelayMillis(12000L)
            .build()
        try { client.requestLocationUpdates(request, callback, mainLooper) } catch (_: SecurityException) { stopSelf() }
        return START_STICKY
    }

    private fun validateSpeed(speed: Int) {
        val threshold = getSharedPreferences("settings", MODE_PRIVATE).getInt("speed_limit", 80)
        if (speed > threshold) {
            if (overSince == 0L) overSince = System.currentTimeMillis()
            if (!overspeedSent && System.currentTimeMillis() - overSince >= 10_000L) {
                overspeedSent = true
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(7302, NotificationCompat.Builder(this, "family_alerts")
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Speed alert")
                    .setContentText("Estimated speed is $speed km/h, above your $threshold km/h family threshold.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true).build())
            }
        } else if (speed < threshold - 5) {
            overSince = 0L
            overspeedSent = false
        }
    }

    private fun trackingNotification(speed: Int): android.app.Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "location_tracking")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Family Connect live tracking")
            .setContentText(if (speed > 0) "Location active • $speed km/h" else "Location sharing is active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(speed: Int) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(7301, trackingNotification(speed))
    }

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("location_tracking", "Live location", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("family_alerts", "Family alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onDestroy() {
        if (::client.isInitialized) client.removeLocationUpdates(callback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
