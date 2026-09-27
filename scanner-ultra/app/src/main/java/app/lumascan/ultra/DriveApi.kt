package app.lumascan.ultra

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object DriveAuth {
    val scopes: List<Scope> = listOf(
        Scope("https://www.googleapis.com/auth/drive.file")
    )

    const val OAUTH_PACKAGE = "app.lumascan.ultra"
    const val OAUTH_SHA1 = "42:60:C5:94:A1:C8:C5:B8:E0:9B:6C:3A:7F:E5:0C:95:8E:7D:BE:59"

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .build()

    fun client(context: Context) = Identity.getAuthorizationClient(context)
}

data class DriveConnection(
    val folderId: String,
    val accountEmail: String
)

object DriveRestApi {
    private const val DRIVE_BASE = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
    const val BACKUP_FOLDER_NAME = "SCANTANTRA Backup"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"

    fun completeConnection(token: String): DriveConnection {
        val folderId = findBackupFolder(token) ?: createFolder(token, BACKUP_FOLDER_NAME, null)
        verifyWriteAccess(token, folderId)
        return DriveConnection(folderId, "")
    }

    fun uploadDocument(token: String, folderId: String, doc: ScanDoc): Boolean {
        return try {
        val safeName = SmartNamer.safePart(doc.title).ifBlank { "Scanned_Document" }

        if (doc.preferredFormat == OutputFormat.PDF || doc.imagePaths.isEmpty()) {
            val source = File(doc.pdfPath)
            if (!source.exists()) return false
            uploadFileResumable(
                token = token,
                file = source,
                parentId = folderId,
                fileName = safeName + ".pdf",
                mimeType = "application/pdf"
            )
        } else if (doc.imagePaths.size == 1) {
            val source = File(doc.imagePaths.first())
            if (!source.exists()) return false
            uploadFileResumable(
                token = token,
                file = source,
                parentId = folderId,
                fileName = safeName + ".jpg",
                mimeType = "image/jpeg"
            )
        } else {
            val pageFolder = findFolder(token, safeName, folderId)
                ?: createFolder(token, safeName, folderId)
            doc.imagePaths.forEachIndexed { index, path ->
                val source = File(path)
                if (!source.exists()) return@forEachIndexed
                uploadFileResumable(
                    token = token,
                    file = source,
                    parentId = pageFolder,
                    fileName = "page_" + (index + 1).toString().padStart(3, '0') + ".jpg",
                    mimeType = "image/jpeg"
                )
            }
        }
        true
        } catch (_: Exception) {
            false
        }
    }

    private fun findBackupFolder(token: String): String? =
        findFolder(token, BACKUP_FOLDER_NAME, null)

    private fun findFolder(token: String, name: String, parentId: String?): String? {
        val escaped = name.replace("'", "\\'")
        val q = buildString {
            append("name='").append(escaped).append("' and mimeType='")
                .append(FOLDER_MIME).append("' and trashed=false")
            if (!parentId.isNullOrBlank()) append(" and '").append(parentId).append("' in parents")
        }
        val url = DRIVE_BASE + "/files?q=" +
            URLEncoder.encode(q, "UTF-8") +
            "&spaces=drive&pageSize=20&fields=files(id,name)"
        val json = JSONObject(http(token, "GET", url))
        val files = json.optJSONArray("files") ?: JSONArray()
        return if (files.length() > 0) files.getJSONObject(0).optString("id").takeIf { it.isNotBlank() } else null
    }

    private fun createFolder(token: String, name: String, parentId: String?): String {
        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME)
        if (!parentId.isNullOrBlank()) metadata.put("parents", JSONArray().put(parentId))

        val response = JSONObject(
            http(
                token,
                "POST",
                DRIVE_BASE + "/files?fields=id,name",
                "application/json; charset=UTF-8",
                metadata.toString().toByteArray()
            )
        )
        return response.getString("id")
    }

    private fun verifyWriteAccess(token: String, folderId: String) {
        val temp = File.createTempFile("scantantra_drive_test_", ".txt")
        try {
            temp.writeText("SCANTANTRA Google Drive backup verification")
            val id = uploadFileResumable(
                token = token,
                file = temp,
                parentId = folderId,
                fileName = ".scantantra_connection_test.txt",
                mimeType = "text/plain"
            )
            http(token, "DELETE", DRIVE_BASE + "/files/" + id)
        } finally {
            temp.delete()
        }
    }

    private fun uploadFileResumable(
        token: String,
        file: File,
        parentId: String,
        fileName: String,
        mimeType: String
    ): String {
        val metadata = JSONObject()
            .put("name", fileName)
            .put("mimeType", mimeType)
            .put("parents", JSONArray().put(parentId))

        val initUrl = URL(UPLOAD_BASE + "/files?uploadType=resumable&fields=id")
        val init = (initUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", mimeType)
            setRequestProperty("X-Upload-Content-Length", file.length().toString())
        }
        init.outputStream.use { it.write(metadata.toString().toByteArray()) }
        val initBody = readResponse(init)
        if (init.responseCode !in 200..299) {
            throw IOException("Drive upload start failed " + init.responseCode + ": " + initBody.take(300))
        }
        val location = init.getHeaderField("Location")
            ?: throw IOException("Drive did not return an upload URL")
        init.disconnect()

        val upload = (URL(location).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 60_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Content-Type", mimeType)
            setFixedLengthStreamingMode(file.length())
        }
        file.inputStream().use { input ->
            upload.outputStream.use { output -> input.copyTo(output, 128 * 1024) }
        }
        val body = readResponse(upload)
        val code = upload.responseCode
        upload.disconnect()
        if (code !in 200..299) {
            throw IOException("Drive upload failed " + code + ": " + body.take(300))
        }
        return JSONObject(body).getString("id")
    }

    private fun http(
        token: String,
        method: String,
        url: String,
        contentType: String? = null,
        body: ByteArray? = null
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Accept", "application/json")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            if (body != null) doOutput = true
        }
        if (body != null) connection.outputStream.use { it.write(body) }
        val response = readResponse(connection)
        val code = connection.responseCode
        connection.disconnect()
        if (code !in 200..299) {
            throw IOException("Google Drive API " + code + ": " + response.take(500))
        }
        return response
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }
}
