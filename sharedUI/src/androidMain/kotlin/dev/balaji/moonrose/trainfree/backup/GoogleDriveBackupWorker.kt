package dev.balaji.moonrose.trainfree.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.balaji.moonrose.trainfree.db.DbContextHolder
import dev.balaji.moonrose.trainfree.db.GoogleDriveBackupManager

private const val MAX_WORKER_ATTEMPTS = 5

class GoogleDriveBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        DbContextHolder.appContext = applicationContext
        GoogleDriveBackupManager.registerIfAbsent { AndroidGoogleDriveBackupProvider(applicationContext) }
        val error = GoogleDriveBackupManager.retryPendingSyncIfNeeded()
        return when {
            error == null -> Result.success()
            error.isTransient && runAttemptCount < MAX_WORKER_ATTEMPTS -> Result.retry()
            else -> Result.success()
        }
    }
}
