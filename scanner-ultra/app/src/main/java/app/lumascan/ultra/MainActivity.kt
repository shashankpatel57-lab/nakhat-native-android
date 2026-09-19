package app.lumascan.ultra

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private var docs by androidx.compose.runtime.mutableStateOf<List<ScanDoc>>(emptyList())
    private var folders by androidx.compose.runtime.mutableStateOf<List<ScanFolder>>(emptyList())
    private var tags by androidx.compose.runtime.mutableStateOf<List<ScanTag>>(emptyList())
    private var settings by androidx.compose.runtime.mutableStateOf(AppSettings())
    private var processing by androidx.compose.runtime.mutableStateOf(false)

    private var sessionFormat = OutputFormat.PDF
    private var sessionColor = ColorMode.COLOR
    private val io = Executors.newSingleThreadExecutor()

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            ?: return@registerForActivityResult
        saveScan(scan)
    }

    private val drivePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        settings = settings.copy(driveTreeUri = uri.toString(), autoDriveUpload = true)
        AppStore.saveSettings(this, settings)
        toast("Drive folder connected")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        docs = AppStore.loadDocs(this)
        folders = AppStore.loadFolders(this)
        tags = AppStore.loadTags(this)
        settings = AppStore.loadSettings(this)

        setContent {
            LumaTheme {
                ScannerApp(
                    docs = docs,
                    folders = folders,
                    tags = tags,
                    settings = settings,
                    processing = processing,
                    onScan = { batch, format, mode -> launchScanner(batch, format, mode) },
                    onView = ::openPdf,
                    onShare = { doc, format -> shareDocument(doc, format, false) },
                    onEmail = { doc -> shareDocument(doc, doc.preferredFormat, true) },
                    onDelete = ::deleteDocument,
                    onOrganize = ::organizeDocument,
                    onUpload = ::manualDriveUpload,
                    onAddFolder = ::addFolder,
                    onAddTag = ::addTag,
                    onSettings = {
                        settings = it
                        AppStore.saveSettings(this, it)
                    },
                    onConnectDrive = { drivePicker.launch(null) }
                )
            }
        }
    }

    private fun launchScanner(batch: Boolean, format: OutputFormat, color: ColorMode) {
        sessionFormat = format
        sessionColor = color
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(if (batch) 50 else 1)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { toast("Unable to open scanner") }
    }

    private fun saveScan(result: GmsDocumentScanningResult) {
        val pages = result.pages.orEmpty()
        if (pages.isEmpty()) {
            toast("No pages returned")
            return
        }

        processing = true
        val capturedFormat = sessionFormat
        val capturedColor = sessionColor
        val settingsSnapshot = settings
        val id = UUID.randomUUID().toString()
        val created = System.currentTimeMillis()

        io.execute {
            try {
                val dir = File(filesDir, "scans/" + id).apply { mkdirs() }
                val imageFiles = pages.mapIndexed { index, page ->
                    File(dir, "page_" + (index + 1).toString().padStart(3, '0') + ".jpg").also { target ->
                        ScanProcessing.processPage(
                            context = this,
                            source = page.imageUri,
                            target = target,
                            mode = capturedColor,
                            cleanup = settingsSnapshot.smartCleanup,
                            cleanupStrength = settingsSnapshot.cleanupStrength,
                            jpegQuality = settingsSnapshot.jpegQuality
                        )
                    }
                }

                val fallbackTitle = SimpleDateFormat("'Scan'_yyyy-MM-dd_HH-mm", Locale.US).format(Date(created))
                val pdf = File(dir, fallbackTitle + ".pdf")
                ScanProcessing.buildPdf(imageFiles, pdf)

                val doc = ScanDoc(
                    id = id,
                    title = fallbackTitle,
                    pdfPath = pdf.absolutePath,
                    imagePaths = imageFiles.map { it.absolutePath },
                    pageCount = imageFiles.size,
                    createdAt = created,
                    ocrText = "",
                    preferredFormat = capturedFormat,
                    colorMode = capturedColor
                )

                runOnUiThread {
                    docs = listOf(doc) + docs
                    AppStore.saveDocs(this, docs)
                    processing = false
                    if (settingsSnapshot.ocrEnabled) runOcr(doc, settingsSnapshot)
                    else finalizeDocument(doc, "", settingsSnapshot)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    processing = false
                    toast("Could not finish scan: " + (e.message ?: "unknown error"))
                }
            }
        }
    }

    private fun runOcr(doc: ScanDoc, settingsSnapshot: AppSettings) {
        if (doc.imagePaths.isEmpty()) {
            finalizeDocument(doc, "", settingsSnapshot)
            return
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val chunks = MutableList(doc.imagePaths.size) { "" }
        var remaining = doc.imagePaths.size

        doc.imagePaths.forEachIndexed { index, path ->
            val image = InputImage.fromFilePath(this, Uri.fromFile(File(path)))
            recognizer.process(image)
                .addOnSuccessListener { chunks[index] = it.text }
                .addOnCompleteListener {
                    remaining--
                    if (remaining == 0) {
                        recognizer.close()
                        finalizeDocument(doc, chunks.joinToString("\n"), settingsSnapshot)
                    }
                }
        }
    }

    private fun finalizeDocument(base: ScanDoc, text: String, settingsSnapshot: AppSettings) {
        val generated = if (settingsSnapshot.smartNaming && text.isNotBlank()) {
            SmartNamer.name(text, base.createdAt)
        } else base.title

        val title = generated.ifBlank { base.title }
        val oldPdf = File(base.pdfPath)
        val safe = SmartNamer.safePart(title).ifBlank { "Scanned_Document" }
        val renamed = File(oldPdf.parentFile, safe + ".pdf")
        val finalPdf = if (oldPdf.absolutePath == renamed.absolutePath || oldPdf.renameTo(renamed)) renamed else oldPdf

        val updated = base.copy(
            title = title.replace('_', ' '),
            pdfPath = finalPdf.absolutePath,
            ocrText = text
        )
        docs = docs.map { if (it.id == updated.id) updated else it }
        AppStore.saveDocs(this, docs)

        if (settingsSnapshot.autoDriveUpload && settingsSnapshot.driveTreeUri.isNotBlank()) {
            io.execute {
                val ok = uploadToDrive(updated, settingsSnapshot)
                runOnUiThread { toast(if (ok) "Saved to Drive" else "Drive upload failed") }
            }
        }
    }

    private fun openPdf(doc: ScanDoc) {
        val file = File(doc.pdfPath)
        if (!file.exists()) {
            toast("PDF is no longer available")
            return
        }
        val uri = FileProvider.getUriForFile(this, packageName + ".files", file)
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: ActivityNotFoundException) {
            shareDocument(doc, OutputFormat.PDF, false)
        }
    }

    private fun shareDocument(doc: ScanDoc, format: OutputFormat, email: Boolean) {
        val subject = if (settings.smartEmailSubject) doc.title else "Scanned document"
        if (format == OutputFormat.PDF || doc.imagePaths.isEmpty()) {
            val file = File(doc.pdfPath)
            if (!file.exists()) return
            val uri = FileProvider.getUriForFile(this, packageName + ".files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, if (email) "Email scan" else "Share PDF"))
        } else {
            val uris = ArrayList(doc.imagePaths.mapNotNull { path ->
                val file = File(path)
                if (file.exists()) FileProvider.getUriForFile(this, packageName + ".files", file) else null
            })
            if (uris.isEmpty()) return
            val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, if (email) "Email scan" else "Share JPG"))
        }
    }

    private fun deleteDocument(doc: ScanDoc) {
        val pdf = File(doc.pdfPath)
        val parent = pdf.parentFile
        if (parent?.name == doc.id) {
            parent.deleteRecursively()
        } else {
            pdf.delete()
            doc.imagePaths.forEach { File(it).delete() }
        }
        docs = docs.filterNot { it.id == doc.id }
        AppStore.saveDocs(this, docs)
    }

    private fun organizeDocument(doc: ScanDoc, folderId: String, selectedTags: List<String>) {
        docs = docs.map {
            if (it.id == doc.id) it.copy(folderId = folderId, tags = selectedTags) else it
        }
        AppStore.saveDocs(this, docs)
    }

    private fun addFolder(name: String) {
        if (name.isBlank()) return
        val colors = UiPalette.folderColors
        val folder = ScanFolder(
            UUID.randomUUID().toString(),
            name.trim(),
            colors[folders.size % colors.size]
        )
        folders = folders + folder
        AppStore.saveFolders(this, folders)
    }

    private fun addTag(name: String) {
        if (name.isBlank() || tags.any { it.name.equals(name.trim(), true) }) return
        val colors = UiPalette.folderColors
        val tag = ScanTag(name.trim(), colors[tags.size % colors.size])
        tags = tags + tag
        AppStore.saveTags(this, tags)
    }

    private fun manualDriveUpload(doc: ScanDoc) {
        if (settings.driveTreeUri.isBlank()) {
            toast("Connect a Google Drive folder in Settings first")
            return
        }
        io.execute {
            val ok = uploadToDrive(doc, settings)
            runOnUiThread { toast(if (ok) "Saved to Drive" else "Drive upload failed") }
        }
    }

    private fun uploadToDrive(doc: ScanDoc, s: AppSettings): Boolean = try {
        val root = DocumentFile.fromTreeUri(this, Uri.parse(s.driveTreeUri)) ?: return false
        val safeName = SmartNamer.safePart(doc.title).ifBlank { "Scanned_Document" }

        if (doc.preferredFormat == OutputFormat.PDF || doc.imagePaths.isEmpty()) {
            val source = File(doc.pdfPath)
            if (!source.exists()) return false
            val fileName = safeName + ".pdf"
            root.findFile(fileName)?.delete()
            val dest = root.createFile("application/pdf", fileName) ?: return false
            contentResolver.openOutputStream(dest.uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: return false
        } else if (doc.imagePaths.size == 1) {
            val source = File(doc.imagePaths.first())
            val fileName = safeName + ".jpg"
            root.findFile(fileName)?.delete()
            val dest = root.createFile("image/jpeg", fileName) ?: return false
            contentResolver.openOutputStream(dest.uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: return false
        } else {
            val folder = root.findFile(safeName)?.takeIf { it.isDirectory }
                ?: root.createDirectory(safeName)
                ?: return false
            doc.imagePaths.forEachIndexed { index, path ->
                val source = File(path)
                if (!source.exists()) return@forEachIndexed
                val fileName = "page_" + (index + 1).toString().padStart(3, '0') + ".jpg"
                folder.findFile(fileName)?.delete()
                val dest = folder.createFile("image/jpeg", fileName) ?: return@forEachIndexed
                contentResolver.openOutputStream(dest.uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                }
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
