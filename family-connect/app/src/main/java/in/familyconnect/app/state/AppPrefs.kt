package com.familyconnect.app.state

import android.content.Context
import android.util.Base64
import org.json.JSONObject

data class SavedPlace(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Float = 180f
)

data class TripSnapshot(
    val active: Boolean = false,
    val destinationName: String? = null,
    val destinationLat: Double? = null,
    val destinationLon: Double? = null,
    val remainingM: Float? = null,
    val currentSpeed: Int = 0,
    val averageSpeed: Int = 0,
    val maxSpeed: Int = 0,
    val etaMinutes: Int? = null,
    val startedAt: Long = 0L
)

data class InvitePayload(
    val blobId: String,
    val keyB64: String,
    val familyName: String
)

object AppPrefs {
    private const val PREF = "family_connect_state"
    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun profileName(c: Context): String = p(c).getString("profile_name", "") ?: ""
    fun familyName(c: Context): String = p(c).getString("family_name", "") ?: ""
    fun memberId(c: Context): String = p(c).getString("member_id", "") ?: ""
    fun cloudBlobId(c: Context): String = p(c).getString("cloud_blob_id", "") ?: ""
    fun cloudKey(c: Context): String = p(c).getString("cloud_key", "") ?: ""
    fun isOwner(c: Context): Boolean = p(c).getBoolean("family_owner", false)
    fun setupComplete(c: Context): Boolean =
        profileName(c).isNotBlank() && familyName(c).isNotBlank() && memberId(c).isNotBlank() &&
            cloudBlobId(c).isNotBlank() && cloudKey(c).isNotBlank()

    fun saveCloudSetup(
        c: Context,
        personName: String,
        familyName: String,
        memberId: String,
        blobId: String,
        keyB64: String,
        owner: Boolean
    ) {
        p(c).edit()
            .putString("profile_name", personName)
            .putString("family_name", familyName)
            .putString("member_id", memberId)
            .putString("cloud_blob_id", blobId)
            .putString("cloud_key", keyB64)
            .putBoolean("family_owner", owner)
            .apply()
    }

    fun inviteCode(c: Context): String {
        if (!setupComplete(c)) return ""
        val payload = JSONObject()
            .put("v", 2)
            .put("blob", cloudBlobId(c))
            .put("key", cloudKey(c))
            .put("family", familyName(c))
        val b64 = Base64.encodeToString(
            payload.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "FC2-" + b64
    }

    fun decodeInvite(codeRaw: String): InvitePayload {
        val code = codeRaw.trim().replace("\n", "").replace(" ", "")
        require(code.startsWith("FC2-")) { "Invalid Family Connect invitation code" }
        val json = String(
            Base64.decode(code.removePrefix("FC2-"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
            Charsets.UTF_8
        )
        val obj = JSONObject(json)
        require(obj.optInt("v") == 2) { "Unsupported invitation version" }
        return InvitePayload(
            blobId = obj.getString("blob"),
            keyB64 = obj.getString("key"),
            familyName = obj.optString("family", "Family")
        )
    }

    fun savePlace(c: Context, place: SavedPlace) {
        val list = places(c).toMutableList()
        val index = list.indexOfFirst { it.id == place.id || it.name.equals(place.name, ignoreCase = true) }
        if (index >= 0) list[index] = place else list.add(place)
        val arr = org.json.JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("lat", it.lat)
                    .put("lon", it.lon)
                    .put("radius", it.radiusM.toDouble())
            )
        }
        p(c).edit().putString("places", arr.toString()).apply()
    }

    fun deletePlace(c: Context, id: String) {
        val arr = org.json.JSONArray()
        places(c).filterNot { it.id == id }.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("lat", it.lat)
                    .put("lon", it.lon)
                    .put("radius", it.radiusM.toDouble())
            )
        }
        p(c).edit().putString("places", arr.toString()).apply()
    }

    fun places(c: Context): List<SavedPlace> {
        return try {
            val arr = org.json.JSONArray(p(c).getString("places", "[]") ?: "[]")
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        SavedPlace(
                            o.getString("id"),
                            o.getString("name"),
                            o.getDouble("lat"),
                            o.getDouble("lon"),
                            o.optDouble("radius", 180.0).toFloat()
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun startTrip(c: Context, place: SavedPlace) {
        p(c).edit()
            .putBoolean("trip_active", true)
            .putString("trip_dest_name", place.name)
            .putLong("trip_dest_lat", java.lang.Double.doubleToRawLongBits(place.lat))
            .putLong("trip_dest_lon", java.lang.Double.doubleToRawLongBits(place.lon))
            .putLong("trip_started", System.currentTimeMillis())
            .putFloat("trip_remaining", Float.NaN)
            .putInt("trip_current", 0)
            .putInt("trip_avg", 0)
            .putInt("trip_max", 0)
            .putFloat("trip_distance_m", 0f)
            .putLong("trip_moving_seconds", 0L)
            .putInt("trip_eta", -1)
            .putBoolean("trip_1km_alert", false)
            .putBoolean("trip_arrived_alert", false)
            .apply()
    }

    fun stopTrip(c: Context) {
        p(c).edit()
            .putBoolean("trip_active", false)
            .putInt("trip_current", 0)
            .apply()
    }

    fun trip(c: Context): TripSnapshot {
        val prefs = p(c)
        val active = prefs.getBoolean("trip_active", false)
        val hasDest = prefs.contains("trip_dest_lat") && prefs.contains("trip_dest_lon")
        val rem = prefs.getFloat("trip_remaining", Float.NaN)
        return TripSnapshot(
            active = active,
            destinationName = prefs.getString("trip_dest_name", null),
            destinationLat = if (hasDest) java.lang.Double.longBitsToDouble(prefs.getLong("trip_dest_lat", 0L)) else null,
            destinationLon = if (hasDest) java.lang.Double.longBitsToDouble(prefs.getLong("trip_dest_lon", 0L)) else null,
            remainingM = if (rem.isNaN()) null else rem,
            currentSpeed = prefs.getInt("trip_current", 0),
            averageSpeed = prefs.getInt("trip_avg", 0),
            maxSpeed = prefs.getInt("trip_max", 0),
            etaMinutes = prefs.getInt("trip_eta", -1).takeIf { it >= 0 },
            startedAt = prefs.getLong("trip_started", 0L)
        )
    }

    fun lastSeenEventTime(c: Context): Long = p(c).getLong("last_event_time", 0L)
    fun setLastSeenEventTime(c: Context, time: Long) = p(c).edit().putLong("last_event_time", time).apply()

    fun resetAll(c: Context) {
        p(c).edit().clear().apply()
        c.getSharedPreferences("tracking", Context.MODE_PRIVATE).edit().clear().apply()
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().apply()
    }
}
