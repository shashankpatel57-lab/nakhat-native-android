package app.lumascan.ultra

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

enum class OutputFormat { PDF, JPG }
enum class ColorMode { COLOR, GRAYSCALE, BLACK_WHITE }

data class ScanDoc(
    val id: String,
    val title: String,
    val pdfPath: String,
    val imagePaths: List<String>,
    val pageCount: Int,
    val createdAt: Long,
    val ocrText: String,
    val folderId: String = "",
    val tags: List<String> = emptyList(),
    val preferredFormat: OutputFormat = OutputFormat.PDF,
    val colorMode: ColorMode = ColorMode.COLOR
)

data class ScanFolder(val id: String, val name: String, val color: Long)
data class ScanTag(val name: String, val color: Long)

data class AppSettings(
    val defaultFormat: OutputFormat = OutputFormat.PDF,
    val defaultColorMode: ColorMode = ColorMode.COLOR,
    val smartNaming: Boolean = true,
    val smartCleanup: Boolean = true,
    val cleanupStrength: Int = 65,
    val ocrEnabled: Boolean = true,
    val autoDriveUpload: Boolean = false,
    val driveTreeUri: String = "",
    val smartEmailSubject: Boolean = true,
    val jpegQuality: Int = 97
)

object AppStore {
    private const val LIB = "library_v2"
    private const val SETTINGS = "settings_v2"

    fun loadDocs(context: Context): List<ScanDoc> = try {
        val prefs = context.getSharedPreferences(LIB, Context.MODE_PRIVATE)
        val raw = prefs.getString("docs", null)
            ?: context.getSharedPreferences("library", Context.MODE_PRIVATE).getString("docs", "[]")
            ?: "[]"
        val a = JSONArray(raw)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val images = mutableListOf<String>()
            val imageArray = o.optJSONArray("images")
            if (imageArray != null) {
                for (j in 0 until imageArray.length()) images += imageArray.getString(j)
            }
            val oldPath = o.optString("path", "")
            val pdfPath = o.optString("pdfPath", oldPath)
            ScanDoc(
                id = o.getString("id"),
                title = o.optString("title", "Scanned document"),
                pdfPath = pdfPath,
                imagePaths = images,
                pageCount = o.optInt("pages", images.size.coerceAtLeast(1)),
                createdAt = o.optLong("created", System.currentTimeMillis()),
                ocrText = decodeOcr(o.optString("ocr", "")),
                folderId = o.optString("folderId", ""),
                tags = jsonStrings(o.optJSONArray("tags")),
                preferredFormat = enumOr(o.optString("format", "PDF"), OutputFormat.PDF),
                colorMode = enumOr(o.optString("colorMode", "COLOR"), ColorMode.COLOR)
            )
        }
    } catch (_: Exception) { emptyList() }

    fun saveDocs(context: Context, docs: List<ScanDoc>) {
        val a = JSONArray()
        docs.forEach { d ->
            a.put(JSONObject().apply {
                put("id", d.id)
                put("title", d.title)
                put("pdfPath", d.pdfPath)
                put("images", JSONArray(d.imagePaths))
                put("pages", d.pageCount)
                put("created", d.createdAt)
                put("ocr", Base64.encodeToString(d.ocrText.toByteArray(), Base64.NO_WRAP))
                put("folderId", d.folderId)
                put("tags", JSONArray(d.tags))
                put("format", d.preferredFormat.name)
                put("colorMode", d.colorMode.name)
            })
        }
        context.getSharedPreferences(LIB, Context.MODE_PRIVATE)
            .edit().putString("docs", a.toString()).apply()
    }

    fun loadFolders(context: Context): List<ScanFolder> = try {
        val raw = context.getSharedPreferences(LIB, Context.MODE_PRIVATE).getString("folders", "[]") ?: "[]"
        val a = JSONArray(raw)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            ScanFolder(o.getString("id"), o.getString("name"), o.getLong("color"))
        }
    } catch (_: Exception) { emptyList() }

    fun saveFolders(context: Context, folders: List<ScanFolder>) {
        val a = JSONArray()
        folders.forEach { f -> a.put(JSONObject().put("id", f.id).put("name", f.name).put("color", f.color)) }
        context.getSharedPreferences(LIB, Context.MODE_PRIVATE).edit().putString("folders", a.toString()).apply()
    }

    fun loadTags(context: Context): List<ScanTag> = try {
        val raw = context.getSharedPreferences(LIB, Context.MODE_PRIVATE).getString("tags", "[]") ?: "[]"
        val a = JSONArray(raw)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            ScanTag(o.getString("name"), o.getLong("color"))
        }
    } catch (_: Exception) { emptyList() }

    fun saveTags(context: Context, tags: List<ScanTag>) {
        val a = JSONArray()
        tags.forEach { t -> a.put(JSONObject().put("name", t.name).put("color", t.color)) }
        context.getSharedPreferences(LIB, Context.MODE_PRIVATE).edit().putString("tags", a.toString()).apply()
    }

    fun loadSettings(context: Context): AppSettings {
        val p = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        return AppSettings(
            defaultFormat = enumOr(p.getString("format", "PDF") ?: "PDF", OutputFormat.PDF),
            defaultColorMode = enumOr(p.getString("color", "COLOR") ?: "COLOR", ColorMode.COLOR),
            smartNaming = p.getBoolean("smartNaming", true),
            smartCleanup = p.getBoolean("cleanup", true),
            cleanupStrength = p.getInt("cleanupStrength", 65),
            ocrEnabled = p.getBoolean("ocr", true),
            autoDriveUpload = p.getBoolean("autoDrive", false),
            driveTreeUri = p.getString("driveUri", "") ?: "",
            smartEmailSubject = p.getBoolean("emailSubject", true),
            jpegQuality = p.getInt("jpegQuality", 97)
        )
    }

    fun saveSettings(context: Context, s: AppSettings) {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit()
            .putString("format", s.defaultFormat.name)
            .putString("color", s.defaultColorMode.name)
            .putBoolean("smartNaming", s.smartNaming)
            .putBoolean("cleanup", s.smartCleanup)
            .putInt("cleanupStrength", s.cleanupStrength)
            .putBoolean("ocr", s.ocrEnabled)
            .putBoolean("autoDrive", s.autoDriveUpload)
            .putString("driveUri", s.driveTreeUri)
            .putBoolean("emailSubject", s.smartEmailSubject)
            .putInt("jpegQuality", s.jpegQuality)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)

    private fun jsonStrings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    private fun decodeOcr(raw: String): String = try {
        if (raw.isBlank()) "" else String(Base64.decode(raw, Base64.DEFAULT))
    } catch (_: Exception) { raw }
}
