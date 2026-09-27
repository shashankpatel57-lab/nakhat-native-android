package app.lumascan.ultra

import android.content.Context
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
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class DriveBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = AppStore.loadSettings(applicationContext)
        if (!settings.autoDriveUpload ||
            !settings.driveApiConnected ||
            settings.driveFolderId.isBlank()
        ) {
            return@withContext Result.success()
        }

        val authResult = try {
            Tasks.await(DriveAuth.client(applicationContext).authorize(DriveAuth.request()))
        } catch (_: Exception) {
            return@withContext Result.retry()
        }

        if (authResult.hasResolution()) {
            // User interaction is required again; foreground Settings will handle it.
            return@withContext Result.retry()
        }

        val token = authResult.accessToken ?: return@withContext Result.retry()
        val requestedId = inputData.getString(KEY_DOC_ID)
        val docs = AppStore.loadDocs(applicationContext)
            .filter { requestedId.isNullOrBlank() || it.id == requestedId }

        if (requestedId != null && docs.isEmpty()) {
            return@withContext Result.success()
        }

        var failed = false
        docs.forEach { doc ->
            if (BackupLedger.isBackedUp(applicationContext, settings.driveFolderId, doc)) {
                return@forEach
            }

            val ok = DriveRestApi.uploadDocument(
                token = token,
                folderId = settings.driveFolderId,
                doc = doc
            )
            if (ok) {
                BackupLedger.markBackedUp(
                    applicationContext,
                    settings.driveFolderId,
                    doc
                )
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

object BackupLedger {
    private const val PREFS = "drive_backup_ledger_v2"

    private fun key(folderId: String, doc: ScanDoc): String {
        val signature = doc.pdfPath + "|" + doc.title + "|" +
            doc.pageCount + "|" + doc.preferredFormat.name
        return folderId.hashCode().toString() + ":" +
            doc.id + ":" + signature.hashCode()
    }

    fun isBackedUp(context: Context, folderId: String, doc: ScanDoc): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key(folderId, doc), false)

    fun markBackedUp(context: Context, folderId: String, doc: ScanDoc) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key(folderId, doc), true).apply()
    }
}
