package com.familyconnect.app.tracking

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.familyconnect.app.MainActivity
import com.familyconnect.app.R
import com.familyconnect.app.cloud.FamilyCloud
import com.familyconnect.app.state.AppPrefs
import kotlin.math.roundToInt

class LocationTrackingService : Service() {
    private lateinit var client: FusedLocationProviderClient
    private var overSince = 0L
    private var overspeedSent = false
    private var lastAccepted: Location? = null
    private val speedSamples = ArrayDeque<Float>()
    @Volatile private var cloudSyncRunning = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val l = result.lastLocation ?: return
            if (l.accuracy > 80f || l.time <= 0L) return

            val previous = lastAccepted
            if (previous != null) {
                val dt = (l.time - previous.time).coerceAtLeast(1L) / 1000f
                val impliedMps = previous.distanceTo(l) / dt
                if (impliedMps > 75f && l.accuracy > 25f) return
            }

            val rawKmh = l.speed.coerceAtLeast(0f) * 3.6f
            speedSamples.addLast(rawKmh)
            while (speedSamples.size > 5) speedSamples.removeFirst()
            val smoothed = if (speedSamples.isNotEmpty()) speedSamples.average().toFloat() else rawKmh
            val speed = smoothed.roundToInt().coerceAtLeast(0)

            getSharedPreferences("tracking", MODE_PRIVATE).edit()
                .putLong("lat", java.lang.Double.doubleToRawLongBits(l.latitude))
                .putLong("lon", java.lang.Double.doubleToRawLongBits(l.longitude))
                .putInt("speed", speed)
                .putFloat("accuracy", l.accuracy)
                .putLong("time", System.currentTimeMillis())
                .putString("motion", when {
                    speed >= 15 -> "Driving"
                    speed >= 6 -> "Cycling / fast movement"
                    speed >= 2 -> "Walking"
                    else -> "Stationary"
                })
                .apply()

            updateTrip(l, previous, speed)
            checkPlaces(l, speed)
            validateSpeed(speed)
            updateNotification(speed)
            syncCloudAsync(l, speed)
            lastAccepted = l
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

        val trip = AppPrefs.trip(this)
        val interval = if (trip.active) 4000L else 8000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(if (trip.active) 2500L else 4500L)
            .setMaxUpdateDelayMillis(if (trip.active) 7000L else 15000L)
            .build()

        try {
            client.removeLocationUpdates(callback)
            client.requestLocationUpdates(request, callback, mainLooper)
        } catch (_: SecurityException) {
            stopSelf()
        }
        return START_STICKY
    }

    private fun updateTrip(current: Location, previous: Location?, speedKmh: Int) {
        val prefs = getSharedPreferences("family_connect_state", MODE_PRIVATE)
        if (!prefs.getBoolean("trip_active", false)) return
        if (!prefs.contains("trip_dest_lat") || !prefs.contains("trip_dest_lon")) return

        val destLat = java.lang.Double.longBitsToDouble(prefs.getLong("trip_dest_lat", 0L))
        val destLon = java.lang.Double.longBitsToDouble(prefs.getLong("trip_dest_lon", 0L))
        val destination = Location("destination").apply {
            latitude = destLat
            longitude = destLon
        }
        val remaining = current.distanceTo(destination)
        val maxSpeed = maxOf(prefs.getInt("trip_max", 0), speedKmh)

        var totalDistance = prefs.getFloat("trip_distance_m", 0f)
        var movingSeconds = prefs.getLong("trip_moving_seconds", 0L)
        if (previous != null) {
            val dtSec = ((current.time - previous.time) / 1000L).coerceIn(0L, 30L)
            val segment = previous.distanceTo(current)
            if (segment in 1f..500f && dtSec > 0) {
                totalDistance += segment
                if (speedKmh >= 3) movingSeconds += dtSec
            }
        }
        val avg = if (movingSeconds > 0) ((totalDistance / movingSeconds) * 3.6f).roundToInt() else speedKmh
        val referenceSpeed = when {
            speedKmh >= 8 -> speedKmh
            avg >= 8 -> avg
            else -> 0
        }
        val eta = if (referenceSpeed > 0) ((remaining / 1000f) / referenceSpeed * 60f).roundToInt().coerceAtLeast(1) else -1

        prefs.edit()
            .putInt("trip_current", speedKmh)
            .putInt("trip_max", maxSpeed)
            .putInt("trip_avg", avg.coerceAtLeast(0))
            .putFloat("trip_remaining", remaining)
            .putFloat("trip_distance_m", totalDistance)
            .putLong("trip_moving_seconds", movingSeconds)
            .putInt("trip_eta", eta)
            .apply()

        if (remaining <= 1000f && remaining > 220f && !prefs.getBoolean("trip_1km_alert", false)) {
            val destinationName = prefs.getString("trip_dest_name", "destination") ?: "destination"
            val body = "About " + "%.1f".format(remaining / 1000f) + " km remaining" + if (eta > 0) " • ETA " + eta + " min" else ""
            notifyEvent(7410, "TRIP_APPROACHING", "Approaching " + destinationName, body)
            prefs.edit().putBoolean("trip_1km_alert", true).apply()
        }

        if (remaining <= 180f && !prefs.getBoolean("trip_arrived_alert", false)) {
            val name = prefs.getString("trip_dest_name", "destination") ?: "destination"
            val body = "Trip completed • max " + maxSpeed + " km/h • average " + avg.coerceAtLeast(0) + " km/h"
            notifyEvent(7411, "TRIP_ARRIVED", "Arrived at " + name, body)
            prefs.edit()
                .putBoolean("trip_arrived_alert", true)
                .putBoolean("trip_active", false)
                .apply()
        }
    }

    private fun checkPlaces(location: Location, speedKmh: Int) {
        val tracking = getSharedPreferences("tracking", MODE_PRIVATE)
        val myMemberId = AppPrefs.memberId(this)
        AppPrefs.places(this)
            .filter { it.watchMemberId == null || it.watchMemberId == myMemberId }
            .forEach { place ->
            val target = Location(place.name).apply {
                latitude = place.lat
                longitude = place.lon
            }
            val distance = location.distanceTo(target)
            val insideKey = "inside_" + place.id
            val nearKey = "near1k_" + place.id
            val wasInside = tracking.getBoolean(insideKey, false)
            val wasNear = tracking.getBoolean(nearKey, false)
            val isInside = distance <= place.radiusM

            if (!wasInside && isInside) {
                notifyEvent(7600 + (place.id.hashCode() and 0x3FF), "PLACE_ENTER", place.name + " reached", "Arrived at " + place.name)
            }
            if (wasInside && distance > place.radiusM + 80f) {
                notifyEvent(7700 + (place.id.hashCode() and 0x3FF), "PLACE_EXIT", "Left " + place.name, "Left " + place.name)
            }
            if (!wasNear && distance <= 1000f && distance > place.radiusM && speedKmh >= 5) {
                notifyEvent(
                    7800 + (place.id.hashCode() and 0x3FF),
                    "PLACE_APPROACH",
                    "Approaching " + place.name,
                    "Approximately " + "%.1f".format(distance / 1000f) + " km away"
                )
            }

            val edit = tracking.edit().putBoolean(insideKey, isInside)
            if (distance <= 1000f) edit.putBoolean(nearKey, true)
            if (distance >= 1500f) edit.putBoolean(nearKey, false)
            edit.apply()
        }
    }

    private fun validateSpeed(speed: Int) {
        val threshold = getSharedPreferences("settings", MODE_PRIVATE).getInt("speed_limit", 80)
        if (speed > threshold) {
            if (overSince == 0L) overSince = System.currentTimeMillis()
            if (!overspeedSent && System.currentTimeMillis() - overSince >= 10_000L) {
                overspeedSent = true
                notifyEvent(7302, "OVERSPEED", "Speed alert", "Estimated speed is " + speed + " km/h, above your " + threshold + " km/h family threshold.")
            }
        } else if (speed < threshold - 5) {
            overSince = 0L
            overspeedSent = false
        }
    }

    private fun trackingNotification(speed: Int): android.app.Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val trip = AppPrefs.trip(this)
        val text = when {
            trip.active && trip.remainingM != null -> (trip.destinationName ?: "Trip") + " • " + "%.1f".format(trip.remainingM / 1000f) + " km • " + speed + " km/h"
            speed > 0 -> "Location active • " + speed + " km/h"
            else -> "Location sharing is active"
        }
        return NotificationCompat.Builder(this, "location_tracking")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (trip.active) "Family Connect trip active" else "Family Connect live tracking")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(speed: Int) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(7301, trackingNotification(speed))
    }

    private fun notifyEvent(id: Int, type: String, title: String, body: String) {
        showAlert(id, title, body)
        Thread {
            FamilyCloud.publishEvent(this, type, title, body)
        }.start()
    }

    private fun showAlert(id: Int, title: String, body: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
            id,
            NotificationCompat.Builder(this, "family_alerts")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun syncCloudAsync(location: Location, speed: Int) {
        if (cloudSyncRunning || !AppPrefs.setupComplete(this)) return
        cloudSyncRunning = true
        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        val shareSpeed = settings.getBoolean("share_speed", true)
        val shareBattery = settings.getBoolean("share_battery", true)
        val motion = getSharedPreferences("tracking", MODE_PRIVATE).getString("motion", "Unknown") ?: "Unknown"
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        Thread {
            try {
                val state = FamilyCloud.syncMyState(
                    context = this,
                    lat = location.latitude,
                    lon = location.longitude,
                    speed = if (shareSpeed) speed else 0,
                    battery = if (shareBattery) battery else -1,
                    motion = motion
                ).getOrNull() ?: return@Thread

                AppPrefs.replacePlaces(this, state.places)
                val myId = AppPrefs.memberId(this)
                val lastSeen = AppPrefs.lastSeenEventTime(this)
                var newest = lastSeen
                state.events
                    .filter { it.memberId != myId && it.createdAt > lastSeen }
                    .sortedBy { it.createdAt }
                    .forEach { event ->
                        showAlert(8200 + (event.id.hashCode() and 0x3FF), event.title, event.memberName + " • " + event.body)
                        if (event.createdAt > newest) newest = event.createdAt
                    }
                if (newest > lastSeen) AppPrefs.setLastSeenEventTime(this, newest)
            } finally {
                cloudSyncRunning = false
            }
        }.start()
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
