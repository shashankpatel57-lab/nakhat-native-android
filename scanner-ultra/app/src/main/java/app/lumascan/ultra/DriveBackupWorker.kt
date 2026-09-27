package app.lumascan.ultra

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class DriveBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = AppStore.loadSettings(applicationContext)
        if (!settings.autoDriveUpload || settings.driveTreeUri.isBlank()) {
            return@withContext Result.success()
        }

        val root = runCatching {
            DocumentFile.fromTreeUri(applicationContext, Uri.parse(settings.driveTreeUri))
        }.getOrNull() ?: return@withContext Result.retry()

        if (!root.exists() || !root.canWrite() || !root.isDirectory) {
            return@withContext Result.retry()
        }

        val requestedId = inputData.getString(KEY_DOC_ID)
        val docs = AppStore.loadDocs(applicationContext)
            .filter { requestedId.isNullOrBlank() || it.id == requestedId }

        if (requestedId != null && docs.isEmpty()) {
            return@withContext Result.success()
        }

        var failed = false
        docs.forEach { doc ->
            if (BackupLedger.isBackedUp(applicationContext, settings.driveTreeUri, doc)) {
                return@forEach
            }
            val ok = DriveBackupStorage.upload(applicationContext, root, doc)
            if (ok) {
                BackupLedger.markBackedUp(applicationContext, settings.driveTreeUri, doc)
            } else {
                failed = true
            }
        }

        if (failed) Result.retry() else Result.success()
    }

    companion object {
        private const val KEY_DOC_ID = "doc_id"
        private const val PERIODIC_NAME = "scantantra_drive_periodic"

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueueDocument(context: Context, docId: String) {
            val request = OneTimeWorkRequestBuilder<DriveBackupWorker>()
                .setInputData(Data.Builder().putString(KEY_DOC_ID, docId).build())
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "scantantra_drive_doc_" + docId,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun enqueueAll(context: Context) {
            val request = OneTimeWorkRequestBuilder<DriveBackupWorker>()
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "scantantra_drive_backup_all",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }
    }
}

object DriveBackupStorage {
    fun upload(context: Context, root: DocumentFile, doc: ScanDoc): Boolean = try {
        val safeName = SmartNamer.safePart(doc.title).ifBlank { "Scanned_Document" }

        if (doc.preferredFormat == OutputFormat.PDF || doc.imagePaths.isEmpty()) {
            val source = File(doc.pdfPath)
            if (!source.exists()) return false
            val fileName = safeName + ".pdf"
            root.findFile(fileName)?.delete()
            val dest = root.createFile("application/pdf", fileName) ?: return false
            context.contentResolver.openOutputStream(dest.uri, "w")?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } ?: return false
        } else if (doc.imagePaths.size == 1) {
            val source = File(doc.imagePaths.first())
            if (!source.exists()) return false
            val fileName = safeName + ".jpg"
            root.findFile(fileName)?.delete()
            val dest = root.createFile("image/jpeg", fileName) ?: return false
            context.contentResolver.openOutputStream(dest.uri, "w")?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
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
                val dest = folder.createFile("image/jpeg", fileName) ?: return false
                context.contentResolver.openOutputStream(dest.uri, "w")?.use { out ->
                    source.inputStream().use { input -> input.copyTo(out) }
                } ?: return false
            }
        }
        true
    } catch (_: Exception) {
        false
    }
}

object BackupLedger {
    private const val PREFS = "drive_backup_ledger_v1"

    private fun key(treeUri: String, doc: ScanDoc): String {
        val signature = doc.pdfPath + "|" + doc.title + "|" + doc.pageCount + "|" + doc.preferredFormat.name
        return treeUri.hashCode().toString() + ":" + doc.id + ":" + signature.hashCode()
    }

    fun isBackedUp(context: Context, treeUri: String, doc: ScanDoc): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key(treeUri, doc), false)

    fun markBackedUp(context: Context, treeUri: String, doc: ScanDoc) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key(treeUri, doc), true).apply()
    }
}
