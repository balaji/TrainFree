package dev.balaji.moonrose.trainfree.backup

import android.accounts.Account
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal const val DRIVE_BACKUP_WORK_NAME = "trainfree_google_drive_backup"

class AndroidGoogleDriveBackupProvider(
    private val context: Context,
) : GoogleDriveBackupProvider {

    private val appContext = context.applicationContext

    override suspend fun availability(): DriveBackupAvailability {
        val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
        return if (status == ConnectionResult.SUCCESS) {
            DriveBackupAvailability.AVAILABLE
        } else {
            DriveBackupAvailability.UNAVAILABLE
        }
    }

    override suspend fun downloadBackup(knownFileId: String?): DriveDownloadResult = withContext(Dispatchers.IO) {
        try {
            withFreshToken { token ->
                val email = runCatching { GoogleDriveRestClient.fetchAccountEmail(token) }.getOrNull()
                val file = GoogleDriveRestClient.findBackupFile(token, knownFileId)
                    ?: return@withFreshToken DriveDownloadNotFound(accountEmail = email)
                val json = try {
                    GoogleDriveRestClient.downloadBackup(token, file.id)
                } catch (_: DriveNotFoundException) {
                    return@withFreshToken DriveDownloadNotFound(accountEmail = email)
                }
                DriveDownloadFound(fileId = file.id, json = json, accountEmail = email)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            DriveDownloadFailure(mapFailure(t))
        }
    }

    override suspend fun uploadBackup(json: String, knownFileId: String?): DriveUploadResult = withContext(Dispatchers.IO) {
        try {
            withFreshToken { token ->
                val email = runCatching { GoogleDriveRestClient.fetchAccountEmail(token) }.getOrNull()
                val meta = GoogleDriveRestClient.uploadBackup(token, json, knownFileId)
                DriveUploadSuccess(
                    fileId = meta.id,
                    accountEmail = email,
                    syncedAtMillis = System.currentTimeMillis(),
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            DriveUploadFailure(mapFailure(t))
        }
    }

    override suspend fun disconnect(accountEmail: String?) {
        withContext(Dispatchers.IO) {
            val client = Identity.getAuthorizationClient(appContext)
            val silentToken = runCatching { acquireSilentToken() }.getOrNull()
            if (!silentToken.isNullOrBlank()) {
                runCatching {
                    client.clearToken(ClearTokenRequest.builder().setToken(silentToken).build()).awaitResult()
                }
            }
            if (!accountEmail.isNullOrBlank()) {
                val request = RevokeAccessRequest.builder()
                    .setAccount(Account(accountEmail, "com.google"))
                    .setScopes(listOf(Scope(DRIVE_BACKUP_SCOPE)))
                    .build()
                runCatching { client.revokeAccess(request).awaitResult() }
            }
        }
    }

    override fun scheduleBackgroundRetry() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<GoogleDriveBackupWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            DRIVE_BACKUP_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override fun cancelBackgroundRetry() {
        WorkManager.getInstance(appContext).cancelUniqueWork(DRIVE_BACKUP_WORK_NAME)
    }

    private suspend fun <T> withFreshToken(block: (String) -> T): T {
        val token = acquireSilentToken() ?: throw AuthRequiredException()
        return try {
            block(token)
        } catch (_: DriveUnauthorizedException) {
            clearCachedToken(token)
            val refreshed = acquireSilentToken() ?: throw AuthRequiredException()
            block(refreshed)
        }
    }

    private suspend fun acquireSilentToken(): String? {
        val request = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(DRIVE_BACKUP_SCOPE)))
            .build()
        val result = Identity.getAuthorizationClient(appContext).authorize(request).awaitResult()
        if (result.hasResolution()) return null
        return result.accessToken?.takeIf { it.isNotBlank() }
    }

    private suspend fun clearCachedToken(token: String) {
        runCatching {
            Identity.getAuthorizationClient(appContext)
                .clearToken(ClearTokenRequest.builder().setToken(token).build())
                .awaitResult()
        }
    }

    private fun mapFailure(t: Throwable): DriveErrorKind = when (t) {
        is AuthRequiredException, is DriveUnauthorizedException -> DriveErrorKind.AUTH_REQUIRED
        is DriveQuotaExceededException -> DriveErrorKind.QUOTA_EXCEEDED
        is DriveNotFoundException -> DriveErrorKind.NOT_FOUND
        is IOException -> DriveErrorKind.OFFLINE
        else -> DriveErrorKind.UNKNOWN
    }
}

private class AuthRequiredException : Exception("Google Drive authorization required")

internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }
    addOnFailureListener { error ->
        if (cont.isActive) cont.resumeWithException(error)
    }
    addOnCanceledListener {
        if (cont.isActive) cont.cancel()
    }
}
