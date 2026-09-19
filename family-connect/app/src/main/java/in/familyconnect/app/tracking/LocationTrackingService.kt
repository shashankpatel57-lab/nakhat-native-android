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
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.familyconnect.app.MainActivity
import com.familyconnect.app.R
import com.familyconnect.app.cloud.FamilyCloud
import com.familyconnect.app.state.AppPrefs
import com.familyconnect.app.state.RoutePoint
import com.google.android.gms.location.*
import kotlin.math.roundToInt

class LocationTrackingService : Service() {
    private lateinit var client: FusedLocationProviderClient
    private var overSince = 0L
    private var overspeedSent = false
    private var lastAccepted: Location? = null
    private val speedSamples = ArrayDeque<Float>()
    private var stationarySamples = 0
    @Volatile private var cloudSyncRunning = false
    @Volatile private var rerouteRunning = false

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
            val speedAccuracyKmh = if (android.os.Build.VERSION.SDK_INT >= 26 && l.hasSpeedAccuracy()) {
                (l.speedAccuracyMetersPerSecond * 3.6f).coerceAtLeast(0f)
            } else 3f
            val movementM = previous?.distanceTo(l) ?: 999f
            val zeroThreshold = maxOf(3.0f, minOf(6.0f, speedAccuracyKmh))
            val candidate = if (
                rawKmh < zeroThreshold ||
                (previous != null && movementM < 6f && rawKmh < 8f)
            ) 0f else rawKmh

            if (candidate == 0f) {
                stationarySamples++
                if (stationarySamples >= 1) speedSamples.clear()
            } else {
                stationarySamples = 0
                speedSamples.addLast(candidate)
                while (speedSamples.size > 3) speedSamples.removeFirst()
            }

            val smoothed = if (candidate == 0f || speedSamples.isEmpty()) 0f
                else speedSamples.sorted()[speedSamples.size / 2]
            val speed = if (smoothed < 3.5f) 0 else smoothed.roundToInt().coerceAtLeast(0)

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

            updateAltitudeFloor(l, speed)
            updateTrip(l, previous, speed)
            checkPlaces(l, speed)
            checkUnscheduledStop(l, speed)
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
        val interval = if (trip.active) 2000L else 6000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(if (trip.active) 1200L else 3500L)
            .setMaxUpdateDelayMillis(if (trip.active) 3000L else 10000L)
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
        val trip = AppPrefs.trip(this)
        if (!trip.active || trip.destinationLat == null || trip.destinationLon == null) return

        val prefs = getSharedPreferences("family_connect_state", MODE_PRIVATE)
        val destination = Location("destination").apply {
            latitude = trip.destinationLat
            longitude = trip.destinationLon
        }
        val arrivalDistance = current.distanceTo(destination)

        var totalDistanceTravelled = prefs.getFloat("trip_distance_m", 0f)
        var movingSeconds = prefs.getLong("trip_moving_seconds", 0L)
        if (previous != null) {
            val dtSec = ((current.time - previous.time) / 1000L).coerceIn(0L, 30L)
            val segment = previous.distanceTo(current)
            if (segment in 1f..500f && dtSec > 0 && speedKmh >= 3) {
                totalDistanceTravelled += segment
                movingSeconds += dtSec
            }
        }

        val avg = if (movingSeconds > 0) {
            ((totalDistanceTravelled / movingSeconds) * 3.6f).roundToInt().coerceAtLeast(0)
        } else speedKmh
        val maxSpeed = maxOf(trip.maxSpeed, speedKmh)

        val progress = roadProgress(current, trip.routePoints)
        val remainingRoad = progress?.remainingM ?: trip.remainingM
        val offRouteM = progress?.distanceFromRouteM ?: Float.MAX_VALUE

        val routeDistance = trip.routeDistanceM
        val routeDuration = trip.routeDurationS
        val roadEtaMin = if (remainingRoad != null && routeDistance != null && routeDistance > 0f &&
            routeDuration != null && routeDuration > 0) {
            ((routeDuration * (remainingRoad / routeDistance)) / 60f).roundToInt().coerceAtLeast(1)
        } else {
            val referenceSpeed = when {
                avg >= 8 -> avg
                speedKmh >= 8 -> speedKmh
                else -> 0
            }
            if (remainingRoad != null && referenceSpeed > 0) {
                ((remainingRoad / 1000f) / referenceSpeed * 60f).roundToInt().coerceAtLeast(1)
            } else -1
        }

        progress?.index?.let { prefs.edit().putInt("trip_route_index", it).apply() }

        prefs.edit()
            .putInt("trip_current", speedKmh)
            .putInt("trip_max", maxSpeed)
            .putInt("trip_avg", avg)
            .putFloat("trip_distance_m", totalDistanceTravelled)
            .putLong("trip_moving_seconds", movingSeconds)
            .apply {
                if (remainingRoad != null) putFloat("trip_remaining", remainingRoad)
                if (roadEtaMin >= 0) putInt("trip_eta", roadEtaMin)
            }
            .apply()

        val now = System.currentTimeMillis()
        val shouldReroute = trip.routePoints.size < 2 ||
            (offRouteM > 300f && now - trip.lastRerouteAt > 45_000L) ||
            (now - trip.lastRerouteAt > 10 * 60_000L)

        if (shouldReroute) requestReroute(current, trip.destinationLat, trip.destinationLon)

        val latest = AppPrefs.trip(this)
        val remainingForAlert = latest.remainingM
        if (remainingForAlert != null &&
            remainingForAlert <= 1000f && remainingForAlert > 220f &&
            !prefs.getBoolean("trip_1km_alert", false)) {
            val destinationName = latest.destinationName ?: "destination"
            val body = "About " + "%.1f".format(remainingForAlert / 1000f) +
                " km by road remaining" +
                (latest.etaMinutes?.let { " • ETA " + it + " min" } ?: "")
            notifyEvent(7410, "TRIP_APPROACHING", "Approaching " + destinationName, body)
            prefs.edit().putBoolean("trip_1km_alert", true).apply()
        }

        if (arrivalDistance <= 180f && !prefs.getBoolean("trip_arrived_alert", false)) {
            val name = latest.destinationName ?: "destination"
            val body = "Trip completed • max " + maxSpeed + " km/h • average " + avg + " km/h"
            notifyEvent(7411, "TRIP_ARRIVED", "Arrived at " + name, body)
            AppPrefs.stopTrip(this)
            Thread { FamilyCloud.clearTripRoute(this) }.start()
        }
    }

    private data class RoadProgress(
        val index: Int,
        val remainingM: Float,
        val distanceFromRouteM: Float
    )

    private fun roadProgress(current: Location, route: List<RoutePoint>): RoadProgress? {
        if (route.size < 2) return null
        val prefs = getSharedPreferences("family_connect_state", MODE_PRIVATE)
        val lastIndex = prefs.getInt("trip_route_index", 0).coerceIn(0, route.lastIndex)

        var bestIndex = lastIndex
        var bestDistance = Float.MAX_VALUE

        val localStart = (lastIndex - 8).coerceAtLeast(0)
        val localEnd = (lastIndex + 120).coerceAtMost(route.lastIndex)
        for (i in localStart..localEnd) {
            val d = distance(current.latitude, current.longitude, route[i].lat, route[i].lon)
            if (d < bestDistance) {
                bestDistance = d
                bestIndex = i
            }
        }

        if (bestDistance > 900f) {
            for (i in route.indices step 3) {
                val d = distance(current.latitude, current.longitude, route[i].lat, route[i].lon)
                if (d < bestDistance) {
                    bestDistance = d
                    bestIndex = i
                }
            }
        }

        var remaining = bestDistance.coerceAtMost(1000f)
        for (i in bestIndex until route.lastIndex) {
            remaining += distance(route[i].lat, route[i].lon, route[i + 1].lat, route[i + 1].lon)
        }
        return RoadProgress(bestIndex, remaining, bestDistance)
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0]
    }

    private fun requestReroute(current: Location, destLat: Double, destLon: Double) {
        if (rerouteRunning) return
        rerouteRunning = true
        Thread {
            try {
                val route = FamilyCloud.roadRoute(
                    this,
                    current.latitude,
                    current.longitude,
                    destLat,
                    destLon
                ).getOrNull()
                if (route != null) {
                    AppPrefs.updateTripRoute(this, route)
                    FamilyCloud.setTripRoute(this, route)
                    getSharedPreferences("family_connect_state", MODE_PRIVATE).edit()
                        .putInt("trip_route_index", 0)
                        .apply()
                } else {
                    getSharedPreferences("family_connect_state", MODE_PRIVATE).edit()
                        .putLong("trip_last_reroute", System.currentTimeMillis())
                        .apply()
                }
            } finally {
                rerouteRunning = false
            }
        }.start()
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
                    notifyEvent(
                        7600 + (place.id.hashCode() and 0x3FF),
                        "PLACE_ENTER",
                        place.name + " reached",
                        "Arrived at " + place.name
                    )
                }
                if (wasInside && distance > place.radiusM + 80f) {
                    notifyEvent(
                        7700 + (place.id.hashCode() and 0x3FF),
                        "PLACE_EXIT",
                        "Left " + place.name,
                        "Left " + place.name
                    )
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

    private fun updateAltitudeFloor(location: Location, speedKmh: Int) {
        val tracking = getSharedPreferences("tracking", MODE_PRIVATE)
        if (!location.hasAltitude()) {
            tracking.edit().remove("altitude_m").remove("floor_estimate").apply()
            return
        }

        val verticalReliable = if (android.os.Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()) {
            location.verticalAccuracyMeters <= 6f
        } else {
            location.accuracy <= 20f
        }

        tracking.edit().putFloat("altitude_m", location.altitude.toFloat()).apply()
        if (!verticalReliable) {
            tracking.edit().remove("floor_estimate").apply()
            return
        }

        val myMemberId = AppPrefs.memberId(this)
        val place = AppPrefs.places(this)
            .filter { it.watchMemberId == null || it.watchMemberId == myMemberId }
            .firstOrNull {
                distance(location.latitude, location.longitude, it.lat, it.lon) <= it.radiusM + 60f
            }

        if (place == null) {
            tracking.edit().remove("floor_estimate").apply()
            return
        }

        val baseKey = "floor_base_alt_" + place.id
        if (!tracking.contains(baseKey) && speedKmh <= 2) {
            tracking.edit().putFloat(baseKey, location.altitude.toFloat()).apply()
        }

        val base = tracking.getFloat(baseKey, Float.NaN)
        if (base.isNaN()) {
            tracking.edit().remove("floor_estimate").apply()
            return
        }

        val delta = location.altitude.toFloat() - base
        if (kotlin.math.abs(delta) > 120f) {
            tracking.edit().remove("floor_estimate").apply()
            return
        }

        val floor = (delta / 3.1f).roundToInt().coerceIn(-3, 50)
        tracking.edit().putInt("floor_estimate", floor).apply()
    }

    private fun checkUnscheduledStop(location: Location, speedKmh: Int) {
        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        if (!settings.getBoolean("unsaved_stop_alerts", false)) return

        val myMemberId = AppPrefs.memberId(this)
        val nearSavedPlace = AppPrefs.places(this)
            .filter { it.watchMemberId == null || it.watchMemberId == myMemberId }
            .any {
                distance(location.latitude, location.longitude, it.lat, it.lon) <= it.radiusM + 120f
            }

        val tracking = getSharedPreferences("tracking", MODE_PRIVATE)
        if (nearSavedPlace) {
            tracking.edit()
                .remove("stop_started_at")
                .remove("stop_anchor_lat")
                .remove("stop_anchor_lon")
                .putBoolean("stop_notified", false)
                .apply()
            return
        }

        val hasAnchor = tracking.contains("stop_anchor_lat") && tracking.contains("stop_anchor_lon")
        val anchorLat = if (hasAnchor) java.lang.Double.longBitsToDouble(tracking.getLong("stop_anchor_lat", 0L)) else location.latitude
        val anchorLon = if (hasAnchor) java.lang.Double.longBitsToDouble(tracking.getLong("stop_anchor_lon", 0L)) else location.longitude
        val moved = if (hasAnchor) distance(location.latitude, location.longitude, anchorLat, anchorLon) else 0f

        if (speedKmh > 3 || moved > 120f) {
            tracking.edit()
                .putLong("stop_started_at", System.currentTimeMillis())
                .putLong("stop_anchor_lat", java.lang.Double.doubleToRawLongBits(location.latitude))
                .putLong("stop_anchor_lon", java.lang.Double.doubleToRawLongBits(location.longitude))
                .putBoolean("stop_notified", false)
                .apply()
            return
        }

        if (!hasAnchor) {
            tracking.edit()
                .putLong("stop_started_at", System.currentTimeMillis())
                .putLong("stop_anchor_lat", java.lang.Double.doubleToRawLongBits(location.latitude))
                .putLong("stop_anchor_lon", java.lang.Double.doubleToRawLongBits(location.longitude))
                .putBoolean("stop_notified", false)
                .apply()
            return
        }

        val started = tracking.getLong("stop_started_at", System.currentTimeMillis())
        val thresholdMin = settings.getInt("unsaved_stop_minutes", 10).coerceIn(5, 30)
        val elapsed = System.currentTimeMillis() - started
        if (elapsed >= thresholdMin * 60_000L && !tracking.getBoolean("stop_notified", false)) {
            tracking.edit().putBoolean("stop_notified", true).apply()
            Thread {
                val place = FamilyCloud.reverseGeocode(this, location.latitude, location.longitude).getOrNull()
                if (place != null) {
                    val title = AppPrefs.profileName(this) + " stopped near " + place.name
                    val body = "Stationary for about " + thresholdMin + " minutes • " + place.detail
                    FamilyCloud.publishEvent(this, "UNSAVED_STOP", title, body)
                    showAlert(
                        7900 + ((location.latitude * 10000).toInt() and 0x3FF),
                        title,
                        body
                    )
                } else {
                    tracking.edit().putBoolean("stop_notified", false).apply()
                }
            }.start()
        }
    }

    private fun validateSpeed(speed: Int) {
        val threshold = getSharedPreferences("settings", MODE_PRIVATE).getInt("speed_limit", 80)
        if (speed > threshold) {
            if (overSince == 0L) overSince = System.currentTimeMillis()
            if (!overspeedSent && System.currentTimeMillis() - overSince >= 10_000L) {
                overspeedSent = true
                notifyEvent(
                    7302,
                    "OVERSPEED",
                    "Speed alert",
                    "Estimated speed is " + speed + " km/h, above your " + threshold + " km/h family threshold."
                )
            }
        } else if (speed < threshold - 5) {
            overSince = 0L
            overspeedSent = false
        }
    }

    private fun trackingNotification(speed: Int): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val trip = AppPrefs.trip(this)
        val text = when {
            trip.active && trip.remainingM != null ->
                (trip.destinationName ?: "Trip") + " • " +
                    "%.1f".format(trip.remainingM / 1000f) + " km road • " +
                    (trip.etaMinutes?.let { "ETA " + it + " min • " } ?: "") +
                    speed + " km/h"
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
        showAlert(id, title, body, emergency = type == "SOS")
        Thread { FamilyCloud.publishEvent(this, type, title, body) }.start()
    }

    private fun showAlert(id: Int, title: String, body: String, emergency: Boolean = false) {
        val openApp = PendingIntent.getActivity(
            this,
            id,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("open_family_alert", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
            id,
            NotificationCompat.Builder(this, if (emergency) "emergency_alerts" else "family_alerts")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .build()
        )
    }

    private fun syncCloudAsync(location: Location, speed: Int) {
        if (cloudSyncRunning || !AppPrefs.setupComplete(this)) return
        cloudSyncRunning = true

        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        val shareSpeed = settings.getBoolean("share_speed", true)
        val shareBattery = settings.getBoolean("share_battery", true)
        val motion = getSharedPreferences("tracking", MODE_PRIVATE)
            .getString("motion", "Unknown") ?: "Unknown"
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
                        showAlert(
                            8200 + (event.id.hashCode() and 0x3FF),
                            event.title,
                            event.memberName + " • " + event.body,
                            emergency = event.type == "SOS"
                        )
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
        nm.createNotificationChannel(
            NotificationChannel("location_tracking", "Live location", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel("family_alerts", "Family alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        val emergency = NotificationChannel(
            "emergency_alerts",
            "Emergency SOS",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Urgent family SOS alerts"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 900)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        nm.createNotificationChannel(emergency)
    }

    override fun onDestroy() {
        if (::client.isInitialized) client.removeLocationUpdates(callback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
