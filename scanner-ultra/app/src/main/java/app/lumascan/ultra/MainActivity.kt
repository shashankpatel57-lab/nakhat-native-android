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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.common.api.ApiException
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
    private var sessionBatch = false
    private val io = Executors.newSingleThreadExecutor()

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            ?: return@registerForActivityResult
        saveScan(scan)
    }

    private val driveAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        try {
            val authorization = DriveAuth.client(this)
                .getAuthorizationResultFromIntent(result.data)
            finishDriveConnection(authorization)
        } catch (e: ApiException) {
            processing = false
            toast("Google Drive authorization failed: " + (e.message ?: "unknown error"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        docs = AppStore.loadDocs(this)
        folders = AppStore.loadFolders(this)
        tags = AppStore.loadTags(this)
        settings = AppStore.loadSettings(this)
        if (settings.autoDriveUpload && settings.driveApiConnected && settings.driveFolderId.isNotBlank()) {
            DriveBackupWorker.ensurePeriodic(this)
        }

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
                    onSettings = ::applySettings,
                    onConnectDrive = ::connectGoogleDrive,
                    onDisconnectDrive = ::disconnectGoogleDrive,
                    onOpenDrive = ::openGoogleDrive,
                    onBackupAll = ::backupAllNow
                )
            }
        }
    }

    private fun launchScanner(batch: Boolean, format: OutputFormat, color: ColorMode) {
        sessionFormat = format
        sessionColor = color
        sessionBatch = batch
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
        val capturedBatch = sessionBatch
        val settingsSnapshot = settings
        val id = UUID.randomUUID().toString()
        val created = System.currentTimeMillis()

        io.execute {
            try {
                val dir = File(filesDir, "scans/" + id).apply { mkdirs() }
                val processed = pages.mapIndexed { index, page ->
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

                val scored = processed.map { it to ScanProcessing.sharpnessScore(it) }
                val minimumSharpness = 18.0
                val imageFiles = if (capturedBatch && scored.size > 1) {
                    scored.filter { it.second >= minimumSharpness }.map { it.first }
                } else {
                    scored.map { it.first }
                }
                val skippedFrames = processed.size - imageFiles.size

                if (!capturedBatch && scored.firstOrNull()?.second?.let { it < minimumSharpness } == true) {
                    dir.deleteRecursively()
                    error("The page looks blurry. Hold the phone steady, let focus settle, and retake it.")
                }
                if (imageFiles.isEmpty()) {
                    dir.deleteRecursively()
                    error("All captured pages were too blurry. Please rescan with the phone steady.")
                }
                processed.filterNot { imageFiles.contains(it) }.forEach { it.delete() }

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
                    if (skippedFrames > 0) {
                        toast(skippedFrames.toString() + " motion-blurred page-turn frame" +
                            if (skippedFrames == 1) " was skipped" else "s were skipped")
                    }
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

        if (settingsSnapshot.autoDriveUpload &&
            settingsSnapshot.driveApiConnected &&
            settingsSnapshot.driveFolderId.isNotBlank()
        ) {
            DriveBackupWorker.enqueueDocument(this, updated.id)
            DriveBackupWorker.ensurePeriodic(this)
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
        try {
            File(doc.pdfPath).delete()
            doc.imagePaths.forEach { path -> runCatching { File(path).delete() } }
            val scanRoot = File(filesDir, "scans").canonicalFile
            val parent = File(doc.pdfPath).parentFile?.canonicalFile
            if (parent != null && parent.path.startsWith(scanRoot.path) && parent != scanRoot) {
                parent.deleteRecursively()
            }
        } catch (_: Exception) {
        }
        docs = docs.filterNot { it.id == doc.id }
        AppStore.saveDocs(this, docs)
        toast("Scan deleted")
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
        if (!settings.driveApiConnected || settings.driveFolderId.isBlank()) {
            toast("Connect Google Drive in Settings first")
            return
        }
        DriveBackupWorker.enqueueDocument(this, doc.id)
        toast("Drive backup queued")
    }

    private fun backupAllNow() {
        if (!settings.driveApiConnected || settings.driveFolderId.isBlank()) {
            toast("Connect Google Drive first")
            return
        }
        DriveBackupWorker.enqueueAll(this)
        DriveBackupWorker.ensurePeriodic(this)
        toast("All documents queued for Drive backup")
    }

    private fun applySettings(updated: AppSettings) {
        settings = updated
        AppStore.saveSettings(this, updated)
        if (updated.autoDriveUpload &&
            updated.driveApiConnected &&
            updated.driveFolderId.isNotBlank()
        ) {
            DriveBackupWorker.ensurePeriodic(this)
            DriveBackupWorker.enqueueAll(this)
        } else {
            DriveBackupWorker.cancelPeriodic(this)
        }
    }

    private fun connectGoogleDrive() {
        processing = true
        DriveAuth.client(this)
            .authorize(DriveAuth.request())
            .addOnSuccessListener { authorization ->
                if (authorization.hasResolution()) {
                    val pending = authorization.pendingIntent
                    if (pending == null) {
                        processing = false
                        toast("Google Drive authorization is unavailable")
                    } else {
                        driveAuthorizationLauncher.launch(
                            IntentSenderRequest.Builder(pending.intentSender).build()
                        )
                    }
                } else {
                    finishDriveConnection(authorization)
                }
            }
            .addOnFailureListener { error ->
                processing = false
                toast(
                    "Google Drive authorization failed. Check the app's Google OAuth setup. " +
                        (error.message ?: "")
                )
            }
    }

    private fun finishDriveConnection(authorization: AuthorizationResult) {
        val token = authorization.accessToken
        if (token.isNullOrBlank()) {
            processing = false
            toast("Google did not return Drive access. Please try Connect again.")
            return
        }

        io.execute {
            try {
                val connection = DriveRestApi.completeConnection(token)
                runOnUiThread {
                    processing = false
                    settings = settings.copy(
                        driveApiConnected = true,
                        driveFolderId = connection.folderId,
                        driveAccountEmail = connection.accountEmail,
                        autoDriveUpload = true
                    )
                    AppStore.saveSettings(this, settings)
                    DriveBackupWorker.ensurePeriodic(this)
                    DriveBackupWorker.enqueueAll(this)
                    toast("Google Drive connected. Automatic backup is on.")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    processing = false
                    toast(
                        "Drive connection failed: " +
                            (e.message ?: "Google Drive API is not available for this app")
                    )
                }
            }
        }
    }

    private fun disconnectGoogleDrive() {
        settings = settings.copy(
            autoDriveUpload = false,
            driveApiConnected = false,
            driveFolderId = "",
            driveAccountEmail = ""
        )
        AppStore.saveSettings(this, settings)
        DriveBackupWorker.cancelPeriodic(this)
        toast("Google Drive disconnected")
    }

    private fun openGoogleDrive() {
        val launch = packageManager.getLaunchIntentForPackage("com.google.android.apps.docs")
        if (launch != null) {
            startActivity(launch)
        } else {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com")))
            } catch (_: Exception) {
                toast("Google Drive is not available on this device")
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
