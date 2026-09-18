package com.familyconnect.app.cloud

import android.content.Context
import android.util.Base64
import com.familyconnect.app.state.AppPrefs
import com.familyconnect.app.state.RoadRoute
import com.familyconnect.app.state.RoutePoint
import com.familyconnect.app.state.SavedPlace
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

data class CloudMember(
    val id: String,
    val name: String,
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Float?,
    val speed: Int,
    val avgSpeed: Int,
    val maxSpeed: Int,
    val battery: Int,
    val motion: String,
    val updatedAt: Long,
    val destinationName: String?,
    val destinationLat: Double?,
    val destinationLon: Double?,
    val remainingM: Float?,
    val etaMinutes: Int?,
    val tripActive: Boolean,
    val routeDistanceM: Float?,
    val routeDurationS: Int?,
    val routeUpdatedAt: Long
)

data class CloudEvent(
    val id: String,
    val memberId: String,
    val memberName: String,
    val type: String,
    val title: String,
    val body: String,
    val createdAt: Long
)

data class CloudState(
    val familyName: String,
    val members: List<CloudMember>,
    val events: List<CloudEvent>,
    val places: List<SavedPlace>
)

object FamilyCloud {
    private const val API = "https://fvpwjzgmqdtmdtfquvmi.supabase.co/functions/v1/family-api"
    private const val PUBLISHABLE_KEY = "sb_publishable_kZsea5gWYdoA8fY4-1onyQ_ambvq8ZS"
    private const val CONNECT_TIMEOUT = 9000
    private const val READ_TIMEOUT = 11000

    fun createFamily(context: Context, personName: String, familyName: String): Result<String> = runCatching {
        val secret = randomSecret()
        val response = call(
            JSONObject()
                .put("action", "create_family")
                .put("familyName", familyName.trim())
                .put("memberName", personName.trim())
                .put("secret", secret)
        )
        val circleId = response.getString("circleId")
        val memberId = response.getString("memberId")
        val actualFamily = response.optString("familyName", familyName.trim())
        AppPrefs.saveCloudSetup(context, personName.trim(), actualFamily, memberId, circleId, secret, true)
        AppPrefs.inviteCode(context)
    }

    fun joinFamily(context: Context, personName: String, code: String): Result<String> = runCatching {
        val invite = AppPrefs.decodeInvite(code)
        val response = call(
            JSONObject()
                .put("action", "join_family")
                .put("circleId", invite.blobId)
                .put("secret", invite.keyB64)
                .put("memberName", personName.trim())
        )
        val memberId = response.getString("memberId")
        val actualFamily = response.optString("familyName", invite.familyName)
        AppPrefs.saveCloudSetup(context, personName.trim(), actualFamily, memberId, invite.blobId, invite.keyB64, false)
        actualFamily
    }

    fun pull(context: Context): Result<CloudState> = runCatching {
        val response = call(authPayload(context, "get_state"))
        parseState(response.getJSONObject("state"), response.optString("familyName", AppPrefs.familyName(context)))
    }

    fun roadRoute(
        context: Context,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): Result<RoadRoute> = runCatching {
        val response = call(
            authPayload(context, "route")
                .put("fromLat", fromLat)
                .put("fromLon", fromLon)
                .put("toLat", toLat)
                .put("toLon", toLon)
        )
        val arr = response.getJSONArray("points")
        val points = buildList {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONArray(i) ?: continue
                if (p.length() >= 2) add(RoutePoint(p.optDouble(0), p.optDouble(1)))
            }
        }
        require(points.size >= 2) { "No usable road route returned" }
        RoadRoute(
            points = points,
            distanceM = response.optDouble("distanceM", 0.0).toFloat(),
            durationS = response.optInt("durationS", 0)
        )
    }

    fun setTripRoute(context: Context, route: RoadRoute): Result<Unit> = runCatching {
        val arr = JSONArray()
        route.points.forEach { arr.put(JSONArray().put(it.lat).put(it.lon)) }
        call(
            authPayload(context, "set_trip_route")
                .put("memberId", AppPrefs.memberId(context))
                .put(
                    "route",
                    JSONObject()
                        .put("points", arr)
                        .put("distanceM", route.distanceM)
                        .put("durationS", route.durationS)
                )
        )
    }

    fun clearTripRoute(context: Context): Result<Unit> = runCatching {
        call(
            authPayload(context, "clear_trip_route")
                .put("memberId", AppPrefs.memberId(context))
        )
    }

    fun getTripRoute(context: Context, memberId: String): Result<RoadRoute> = runCatching {
        val response = call(
            authPayload(context, "get_trip_route")
                .put("requestedMemberId", memberId)
        )
        val route = response.optJSONObject("route") ?: JSONObject()
        val arr = route.optJSONArray("route_points") ?: JSONArray()
        val points = buildList {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONArray(i) ?: continue
                if (p.length() >= 2) add(RoutePoint(p.optDouble(0), p.optDouble(1)))
            }
        }
        RoadRoute(
            points = points,
            distanceM = route.optDouble("route_distance_m", 0.0).toFloat(),
            durationS = route.optInt("route_duration_s", 0)
        )
    }

    fun syncMyState(
        context: Context,
        lat: Double?,
        lon: Double?,
        speed: Int,
        battery: Int,
        motion: String
    ): Result<CloudState> = runCatching {
        val trip = AppPrefs.trip(context)
        val tracking = context.getSharedPreferences("tracking", Context.MODE_PRIVATE)

        val payload = authPayload(context, "sync_state")
            .put("memberId", AppPrefs.memberId(context))
            .put(
                "state",
                JSONObject()
                    .put("latitude", lat ?: JSONObject.NULL)
                    .put("longitude", lon ?: JSONObject.NULL)
                    .put("accuracyM", tracking.getFloat("accuracy", 0f).takeIf { it > 0f } ?: JSONObject.NULL)
                    .put("speedKmh", speed)
                    .put("avgSpeedKmh", trip.averageSpeed)
                    .put("maxSpeedKmh", trip.maxSpeed)
                    .put("battery", if (battery >= 0) battery else JSONObject.NULL)
                    .put("charging", JSONObject.NULL)
                    .put("motion", motion)
                    .put("destinationName", if (trip.active) trip.destinationName ?: JSONObject.NULL else JSONObject.NULL)
                    .put("destinationLat", if (trip.active) trip.destinationLat ?: JSONObject.NULL else JSONObject.NULL)
                    .put("destinationLon", if (trip.active) trip.destinationLon ?: JSONObject.NULL else JSONObject.NULL)
                    .put("remainingM", if (trip.active) trip.remainingM ?: JSONObject.NULL else JSONObject.NULL)
                    .put("etaMinutes", if (trip.active) trip.etaMinutes ?: JSONObject.NULL else JSONObject.NULL)
                    .put("tripActive", trip.active)
            )

        val response = call(payload)
        parseState(response.getJSONObject("state"), response.optString("familyName", AppPrefs.familyName(context)))
    }

    fun publishEvent(context: Context, type: String, title: String, body: String): Result<Unit> = runCatching {
        if (!AppPrefs.setupComplete(context)) return@runCatching
        call(
            authPayload(context, "publish_event")
                .put("memberId", AppPrefs.memberId(context))
                .put("type", type)
                .put("title", title)
                .put("body", body)
        )
    }

    fun upsertPlace(context: Context, place: SavedPlace): Result<CloudState> = runCatching {
        val response = call(
            authPayload(context, "upsert_place")
                .put(
                    "place",
                    JSONObject()
                        .put("id", place.id)
                        .put("name", place.name)
                        .put("latitude", place.lat)
                        .put("longitude", place.lon)
                        .put("radiusM", place.radiusM.toDouble())
                        .put("targetMemberId", place.watchMemberId ?: JSONObject.NULL)
                        .put("approachDistanceM", 1000)
                        .put("enterAlert", true)
                        .put("exitAlert", true)
                        .put("approachAlert", true)
                )
        )
        parseState(response.getJSONObject("state"), AppPrefs.familyName(context))
    }

    fun deletePlace(context: Context, placeId: String): Result<CloudState> = runCatching {
        val response = call(authPayload(context, "delete_place").put("placeId", placeId))
        parseState(response.getJSONObject("state"), AppPrefs.familyName(context))
    }

    fun leaveFamily(context: Context): Result<Unit> = runCatching {
        call(authPayload(context, "leave_family").put("memberId", AppPrefs.memberId(context)))
    }

    private fun authPayload(context: Context, action: String): JSONObject =
        JSONObject()
            .put("action", action)
            .put("circleId", AppPrefs.cloudBlobId(context))
            .put("secret", AppPrefs.cloudKey(context))

    private fun parseState(root: JSONObject, familyName: String): CloudState {
        val membersJson = root.optJSONArray("members") ?: JSONArray()
        val members = buildList {
            for (i in 0 until membersJson.length()) {
                val m = membersJson.optJSONObject(i) ?: continue
                val rawState = m.opt("member_state")
                val state = when (rawState) {
                    is JSONObject -> rawState
                    is JSONArray -> rawState.optJSONObject(0) ?: JSONObject()
                    else -> JSONObject()
                }
                add(
                    CloudMember(
                        id = m.optString("id"),
                        name = m.optString("name", "Member"),
                        lat = nullableDouble(state, "latitude"),
                        lon = nullableDouble(state, "longitude"),
                        accuracyM = nullableDouble(state, "accuracy_m")?.toFloat(),
                        speed = state.optInt("speed_kmh", 0),
                        avgSpeed = state.optInt("avg_speed_kmh", 0),
                        maxSpeed = state.optInt("max_speed_kmh", 0),
                        battery = if (state.has("battery") && !state.isNull("battery")) state.optInt("battery", 0) else -1,
                        motion = state.optString("motion", "Not sharing").ifBlank { "Not sharing" },
                        updatedAt = parseIsoMillis(state.optString("updated_at").ifBlank { m.optString("last_seen") }),
                        destinationName = nullableString(state, "destination_name"),
                        destinationLat = nullableDouble(state, "destination_lat"),
                        destinationLon = nullableDouble(state, "destination_lon"),
                        remainingM = nullableDouble(state, "remaining_m")?.toFloat(),
                        etaMinutes = nullableDouble(state, "eta_minutes")?.toInt(),
                        tripActive = state.optBoolean("trip_active", false),
                        routeDistanceM = nullableDouble(state, "route_distance_m")?.toFloat(),
                        routeDurationS = nullableDouble(state, "route_duration_s")?.toInt(),
                        routeUpdatedAt = parseIsoMillis(state.optString("route_updated_at"))
                    )
                )
            }
        }

        val memberNames = members.associate { it.id to it.name }
        val eventsJson = root.optJSONArray("events") ?: JSONArray()
        val events = buildList {
            for (i in 0 until eventsJson.length()) {
                val e = eventsJson.optJSONObject(i) ?: continue
                val memberId = e.optString("member_id")
                add(
                    CloudEvent(
                        id = e.optString("id"),
                        memberId = memberId,
                        memberName = memberNames[memberId] ?: "Family",
                        type = e.optString("event_type"),
                        title = e.optString("title"),
                        body = e.optString("body"),
                        createdAt = parseIsoMillis(e.optString("created_at"))
                    )
                )
            }
        }

        val placesJson = root.optJSONArray("places") ?: JSONArray()
        val places = buildList {
            for (i in 0 until placesJson.length()) {
                val p = placesJson.optJSONObject(i) ?: continue
                add(
                    SavedPlace(
                        id = p.optString("id"),
                        name = p.optString("name", "Place"),
                        lat = p.optDouble("latitude"),
                        lon = p.optDouble("longitude"),
                        radiusM = p.optDouble("radius_m", 180.0).toFloat(),
                        watchMemberId = nullableString(p, "target_member_id")
                    )
                )
            }
        }

        return CloudState(familyName, members, events, places)
    }

    private fun call(payload: JSONObject): JSONObject {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("apikey", PUBLISHABLE_KEY)
        }
        c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

        val status = c.responseCode
        val stream = if (status in 200..299) c.inputStream else c.errorStream
        val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        val response = if (body.isBlank()) JSONObject() else JSONObject(body)
        if (status !in 200..299) {
            throw IllegalStateException(response.optString("error", "Cloud service returned HTTP " + status))
        }
        if (!response.optBoolean("ok", true)) {
            throw IllegalStateException(response.optString("error", "Cloud request failed"))
        }
        return response
    }

    private fun randomSecret(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun nullableDouble(o: JSONObject, key: String): Double? =
        if (!o.has(key) || o.isNull(key)) null else o.optDouble(key).takeUnless { it.isNaN() }

    private fun nullableString(o: JSONObject, key: String): String? =
        if (!o.has(key) || o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }

    private fun parseIsoMillis(value: String): Long {
        if (value.isBlank()) return 0L
        return try { java.time.Instant.parse(value).toEpochMilli() } catch (_: Exception) { 0L }
    }
}
