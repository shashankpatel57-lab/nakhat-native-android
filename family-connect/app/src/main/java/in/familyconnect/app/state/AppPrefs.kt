package com.familyconnect.app.state

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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

object AppPrefs {
    private const val PREF = "family_connect_state"
    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun profileName(c: Context): String = p(c).getString("profile_name", "") ?: ""
    fun familyName(c: Context): String = p(c).getString("family_name", "") ?: ""
    fun familyId(c: Context): String = p(c).getString("family_id", "") ?: ""
    fun inviteCode(c: Context): String = p(c).getString("invite_code", "") ?: ""
    fun isOwner(c: Context): Boolean = p(c).getBoolean("family_owner", false)
    fun setupComplete(c: Context): Boolean = profileName(c).isNotBlank() && familyName(c).isNotBlank()

    fun createFamily(c: Context, personName: String, familyName: String): String {
        val fid = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("v", 2)
            .put("fid", fid)
            .put("family", familyName.trim())
            .put("owner", personName.trim())
        val encoded = Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val code = "FC2-$encoded"
        p(c).edit()
            .putString("profile_name", personName.trim())
            .putString("family_name", familyName.trim())
            .putString("family_id", fid)
            .putString("invite_code", code)
            .putBoolean("family_owner", true)
            .putString("known_members", JSONArray().toString())
            .apply()
        return code
    }

    fun joinFamily(c: Context, personName: String, codeRaw: String): Result<String> {
        return try {
            val code = codeRaw.trim()
            require(code.startsWith("FC2-")) { "Invalid Family Connect invitation" }
            val json = String(Base64.decode(code.removePrefix("FC2-"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val obj = JSONObject(json)
            require(obj.optInt("v") == 2) { "Unsupported invitation" }
            val family = obj.getString("family")
            val fid = obj.getString("fid")
            val owner = obj.optString("owner", "Family owner")
            val members = JSONArray().put(JSONObject().put("name", owner).put("role", "Owner"))
            p(c).edit()
                .putString("profile_name", personName.trim())
                .putString("family_name", family)
                .putString("family_id", fid)
                .putString("invite_code", code)
                .putBoolean("family_owner", false)
                .putString("known_members", members.toString())
                .apply()
            Result.success(family)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun knownMembers(c: Context): List<Pair<String,String>> {
        val arr = JSONArray(p(c).getString("known_members", "[]") ?: "[]")
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(o.optString("name") to o.optString("role", "Member"))
            }
        }
    }

    fun savePlace(c: Context, place: SavedPlace) {
        val list = places(c).toMutableList()
        val index = list.indexOfFirst { it.name.equals(place.name, ignoreCase = true) }
        if (index >= 0) list[index] = place else list.add(place)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("radius", it.radiusM.toDouble()))
        }
        p(c).edit().putString("places", arr.toString()).apply()
    }

    fun deletePlace(c: Context, id: String) {
        val arr = JSONArray()
        places(c).filterNot { it.id == id }.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("radius", it.radiusM.toDouble()))
        }
        p(c).edit().putString("places", arr.toString()).apply()
    }

    fun places(c: Context): List<SavedPlace> {
        return try {
            val arr = JSONArray(p(c).getString("places", "[]") ?: "[]")
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(SavedPlace(o.getString("id"), o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), o.optDouble("radius",180.0).toFloat()))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun startTrip(c: Context, place: SavedPlace) {
        p(c).edit()
            .putBoolean("trip_active", true)
            .putString("trip_dest_name", place.name)
            .putLong("trip_dest_lat", java.lang.Double.doubleToRawLongBits(place.lat))
            .putLong("trip_dest_lon", java.lang.Double.doubleToRawLongBits(place.lon))
            .putLong("trip_started", System.currentTimeMillis())
            .putFloat("trip_remaining", Float.NaN)
            .putInt("trip_avg", 0)
            .putInt("trip_max", 0)
            .putBoolean("trip_1km_alert", false)
            .putBoolean("trip_arrived_alert", false)
            .apply()
    }

    fun stopTrip(c: Context) {
        p(c).edit().putBoolean("trip_active", false).apply()
    }

    fun trip(c: Context): TripSnapshot {
        val prefs = p(c)
        val active = prefs.getBoolean("trip_active", false)
        val hasDest = prefs.contains("trip_dest_lat") && prefs.contains("trip_dest_lon")
        val rem = prefs.getFloat("trip_remaining", Float.NaN)
        return TripSnapshot(
            active = active,
            destinationName = prefs.getString("trip_dest_name", null),
            destinationLat = if (hasDest) java.lang.Double.longBitsToDouble(prefs.getLong("trip_dest_lat",0)) else null,
            destinationLon = if (hasDest) java.lang.Double.longBitsToDouble(prefs.getLong("trip_dest_lon",0)) else null,
            remainingM = if (rem.isNaN()) null else rem,
            currentSpeed = prefs.getInt("trip_current", 0),
            averageSpeed = prefs.getInt("trip_avg", 0),
            maxSpeed = prefs.getInt("trip_max", 0),
            etaMinutes = prefs.getInt("trip_eta", -1).takeIf { it >= 0 },
            startedAt = prefs.getLong("trip_started", 0L)
        )
    }

    fun resetAll(c: Context) {
        p(c).edit().clear().apply()
        c.getSharedPreferences("tracking", Context.MODE_PRIVATE).edit().clear().apply()
    }
}
