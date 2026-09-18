package com.familyconnect.app.cloud

import android.content.Context
import android.util.Base64
import com.familyconnect.app.state.AppPrefs
import com.familyconnect.app.state.SavedPlace
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class CloudMember(
    val id: String,
    val name: String,
    val lat: Double?,
    val lon: Double?,
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
    val etaMinutes: Int?
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
    val events: List<CloudEvent>
)

object FamilyCloud {
    private const val BASE = "https://jsonblob.com/api/jsonBlob"
    private const val CONNECT_TIMEOUT = 9000
    private const val READ_TIMEOUT = 9000

    fun createFamily(context: Context, personName: String, familyName: String): Result<String> = runCatching {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val memberId = UUID.randomUUID().toString()
        val state = JSONObject()
            .put("version", 2)
            .put("familyName", familyName.trim())
            .put("members", JSONArray().put(baseMember(memberId, personName.trim())))
            .put("events", JSONArray())

        val envelope = JSONObject().put("data", encrypt(state.toString(), key))
        val connection = open(BASE, "POST")
        connection.outputStream.use { it.write(envelope.toString().toByteArray()) }
        val code = connection.responseCode
        if (code !in 200..299) throw IllegalStateException("Cloud service returned HTTP " + code)
        val location = connection.getHeaderField("Location") ?: connection.getHeaderField("location")
            ?: throw IllegalStateException("Cloud service did not return a family ID")
        val blobId = location.substringAfterLast("/")
        val keyB64 = Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        AppPrefs.saveCloudSetup(context, personName.trim(), familyName.trim(), memberId, blobId, keyB64, true)
        AppPrefs.inviteCode(context)
    }

    fun joinFamily(context: Context, personName: String, code: String): Result<String> = runCatching {
        val invite = AppPrefs.decodeInvite(code)
        val key = Base64.decode(invite.keyB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val root = fetchRoot(invite.blobId, key)
        val family = root.optString("familyName", invite.familyName)
        val memberId = UUID.randomUUID().toString()
        val members = root.optJSONArray("members") ?: JSONArray()
        members.put(baseMember(memberId, personName.trim()))
        root.put("members", dedupeMembers(members))
        putRoot(invite.blobId, key, root)
        AppPrefs.saveCloudSetup(context, personName.trim(), family, memberId, invite.blobId, invite.keyB64, false)
        family
    }

    fun pull(context: Context): Result<CloudState> = runCatching {
        val blobId = AppPrefs.cloudBlobId(context)
        val keyB64 = AppPrefs.cloudKey(context)
        require(blobId.isNotBlank() && keyB64.isNotBlank()) { "Family cloud is not configured" }
        val key = Base64.decode(keyB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        parseState(fetchRoot(blobId, key))
    }

    fun syncMyState(
        context: Context,
        lat: Double?,
        lon: Double?,
        speed: Int,
        battery: Int,
        motion: String
    ): Result<CloudState> = runCatching {
        val blobId = AppPrefs.cloudBlobId(context)
        val keyB64 = AppPrefs.cloudKey(context)
        val myId = AppPrefs.memberId(context)
        val myName = AppPrefs.profileName(context)
        require(blobId.isNotBlank() && keyB64.isNotBlank() && myId.isNotBlank()) { "Family cloud is not configured" }
        val key = Base64.decode(keyB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val root = fetchRoot(blobId, key)
        val members = root.optJSONArray("members") ?: JSONArray()
        val trip = AppPrefs.trip(context)

        val me = JSONObject()
            .put("id", myId)
            .put("name", myName)
            .put("lat", lat ?: JSONObject.NULL)
            .put("lon", lon ?: JSONObject.NULL)
            .put("speed", speed)
            .put("avgSpeed", trip.averageSpeed)
            .put("maxSpeed", trip.maxSpeed)
            .put("battery", battery)
            .put("motion", motion)
            .put("updatedAt", System.currentTimeMillis())
            .put("tripActive", trip.active)
            .put("destinationName", trip.destinationName ?: JSONObject.NULL)
            .put("destinationLat", trip.destinationLat ?: JSONObject.NULL)
            .put("destinationLon", trip.destinationLon ?: JSONObject.NULL)
            .put("remainingM", trip.remainingM ?: JSONObject.NULL)
            .put("etaMinutes", trip.etaMinutes ?: JSONObject.NULL)

        val next = JSONArray()
        var replaced = false
        for (i in 0 until members.length()) {
            val m = members.optJSONObject(i) ?: continue
            if (m.optString("id") == myId) {
                next.put(me)
                replaced = true
            } else {
                next.put(m)
            }
        }
        if (!replaced) next.put(me)
        root.put("members", dedupeMembers(next))
        putRoot(blobId, key, root)
        parseState(root)
    }

    fun publishEvent(context: Context, type: String, title: String, body: String): Result<Unit> = runCatching {
        val blobId = AppPrefs.cloudBlobId(context)
        val keyB64 = AppPrefs.cloudKey(context)
        if (blobId.isBlank() || keyB64.isBlank()) return@runCatching
        val key = Base64.decode(keyB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val root = fetchRoot(blobId, key)
        val events = root.optJSONArray("events") ?: JSONArray()
        events.put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("memberId", AppPrefs.memberId(context))
                .put("memberName", AppPrefs.profileName(context))
                .put("type", type)
                .put("title", title)
                .put("body", body)
                .put("createdAt", System.currentTimeMillis())
        )
        val trimmed = JSONArray()
        val start = (events.length() - 50).coerceAtLeast(0)
        for (i in start until events.length()) trimmed.put(events.get(i))
        root.put("events", trimmed)
        putRoot(blobId, key, root)
    }

    private fun baseMember(id: String, name: String): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("lat", JSONObject.NULL)
        .put("lon", JSONObject.NULL)
        .put("speed", 0)
        .put("avgSpeed", 0)
        .put("maxSpeed", 0)
        .put("battery", 0)
        .put("motion", "Not sharing")
        .put("updatedAt", System.currentTimeMillis())
        .put("tripActive", false)
        .put("destinationName", JSONObject.NULL)
        .put("destinationLat", JSONObject.NULL)
        .put("destinationLon", JSONObject.NULL)
        .put("remainingM", JSONObject.NULL)
        .put("etaMinutes", JSONObject.NULL)

    private fun dedupeMembers(input: JSONArray): JSONArray {
        val map = LinkedHashMap<String, JSONObject>()
        for (i in 0 until input.length()) {
            val o = input.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isNotBlank()) map[id] = o
        }
        return JSONArray().also { out -> map.values.forEach { out.put(it) } }
    }

    private fun parseState(root: JSONObject): CloudState {
        val membersJson = root.optJSONArray("members") ?: JSONArray()
        val members = buildList {
            for (i in 0 until membersJson.length()) {
                val m = membersJson.optJSONObject(i) ?: continue
                add(
                    CloudMember(
                        id = m.optString("id"),
                        name = m.optString("name", "Member"),
                        lat = nullableDouble(m, "lat"),
                        lon = nullableDouble(m, "lon"),
                        speed = m.optInt("speed", 0),
                        avgSpeed = m.optInt("avgSpeed", 0),
                        maxSpeed = m.optInt("maxSpeed", 0),
                        battery = m.optInt("battery", 0),
                        motion = m.optString("motion", "Unknown"),
                        updatedAt = m.optLong("updatedAt", 0L),
                        destinationName = nullableString(m, "destinationName"),
                        destinationLat = nullableDouble(m, "destinationLat"),
                        destinationLon = nullableDouble(m, "destinationLon"),
                        remainingM = nullableDouble(m, "remainingM")?.toFloat(),
                        etaMinutes = nullableDouble(m, "etaMinutes")?.toInt()
                    )
                )
            }
        }
        val eventsJson = root.optJSONArray("events") ?: JSONArray()
        val events = buildList {
            for (i in 0 until eventsJson.length()) {
                val e = eventsJson.optJSONObject(i) ?: continue
                add(
                    CloudEvent(
                        id = e.optString("id"),
                        memberId = e.optString("memberId"),
                        memberName = e.optString("memberName"),
                        type = e.optString("type"),
                        title = e.optString("title"),
                        body = e.optString("body"),
                        createdAt = e.optLong("createdAt")
                    )
                )
            }
        }
        return CloudState(root.optString("familyName", "Family"), members, events)
    }

    private fun nullableDouble(o: JSONObject, key: String): Double? =
        if (!o.has(key) || o.isNull(key)) null else o.optDouble(key).takeUnless { it.isNaN() }

    private fun nullableString(o: JSONObject, key: String): String? =
        if (!o.has(key) || o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }

    private fun fetchRoot(blobId: String, key: ByteArray): JSONObject {
        val c = open(BASE + "/" + blobId, "GET")
        if (c.responseCode !in 200..299) throw IllegalStateException("Family not reachable (HTTP " + c.responseCode + ")")
        val body = read(c)
        val envelope = JSONObject(body)
        return JSONObject(decrypt(envelope.getString("data"), key))
    }

    private fun putRoot(blobId: String, key: ByteArray, root: JSONObject) {
        val c = open(BASE + "/" + blobId, "PUT")
        val envelope = JSONObject().put("data", encrypt(root.toString(), key))
        c.outputStream.use { it.write(envelope.toString().toByteArray()) }
        if (c.responseCode !in 200..299) throw IllegalStateException("Family update failed (HTTP " + c.responseCode + ")")
        c.inputStream.close()
    }

    private fun open(url: String, method: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            doInput = true
            doOutput = method == "POST" || method == "PUT"
        }
    }

    private fun read(c: HttpURLConnection): String {
        return BufferedReader(InputStreamReader(c.inputStream)).use { it.readText() }
    }

    private fun encrypt(plain: String, key: ByteArray): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, packed, 0, iv.size)
        System.arraycopy(encrypted, 0, packed, iv.size, encrypted.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(packedB64: String, key: ByteArray): String {
        val packed = Base64.decode(packedB64, Base64.NO_WRAP)
        require(packed.size > 12) { "Invalid encrypted family data" }
        val iv = packed.copyOfRange(0, 12)
        val encrypted = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}
