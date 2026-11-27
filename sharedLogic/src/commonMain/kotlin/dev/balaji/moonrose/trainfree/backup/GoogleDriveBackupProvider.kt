package dev.balaji.moonrose.trainfree.backup

/** OAuth scope: access only to files Train Free itself created in the user's Drive. */
const val DRIVE_BACKUP_SCOPE = "https://www.googleapis.com/auth/drive.file"

/** Visible folder in My Drive that holds the rolling backup file. */
const val DRIVE_BACKUP_FOLDER_NAME = "TrainFree"

/** Single rolling backup file, updated in place so Drive keeps revision history. */
const val DRIVE_BACKUP_FILENAME = "trainfree-backup.json"

/** Drive `appProperties` tag used to find the backup file even if renamed or moved. */
const val DRIVE_APP_PROPERTY_KEY = "trainfree_kind"
const val DRIVE_APP_PROPERTY_VALUE = "backup_v1"

enum class DriveBackupAvailability {
    AVAILABLE,

    /** The build has no OAuth client configured (e.g. iOS without Secrets.xcconfig). */
    NOT_CONFIGURED,

    /** The platform can't use Google sign-in (e.g. no Google Play services). */
    UNAVAILABLE,
}

/**
 * Language-neutral Drive failure categories. Persisted as the lowercase
 * [value] in user_settings; each shell maps them to localized text.
 */
enum class DriveErrorKind(val value: String) {
    AUTH_REQUIRED("auth_required"),
    CANCELLED("cancelled"),
    OFFLINE("offline"),
    NOT_FOUND("not_found"),
    NOT_CONFIGURED("not_configured"),
    QUOTA_EXCEEDED("quota_exceeded"),
    UNKNOWN("unknown"),
    ;

    /** Whether a background retry can plausibly succeed without user action. */
    val isTransient: Boolean get() = this == OFFLINE || this == UNKNOWN

    companion object {
        fun fromValue(value: String?): DriveErrorKind? = entries.firstOrNull { it.value == value }
    }
}

sealed class DriveUploadResult

data class DriveUploadSuccess(
    val fileId: String,
    val accountEmail: String?,
    val syncedAtMillis: Long,
) : DriveUploadResult()

data class DriveUploadFailure(val kind: DriveErrorKind) : DriveUploadResult()

sealed class DriveDownloadResult

data class DriveDownloadFound(
    val fileId: String,
    val json: String,
    val accountEmail: String?,
) : DriveDownloadResult()

data class DriveDownloadNotFound(val accountEmail: String?) : DriveDownloadResult()

data class DriveDownloadFailure(val kind: DriveErrorKind) : DriveDownloadResult()

/**
 * Platform bridge to the user's Google Drive (Play services authorization on
 * Android, ASWebAuthenticationSession + PKCE on iOS; Drive REST v3 on both).
 *
 * Interactive sign-in is a UI concern handled by each shell; these calls only
 * use already-granted credentials and report [DriveErrorKind.AUTH_REQUIRED]
 * when the user has to sign in again. Implementations must never throw on
 * network/auth failures — return a failure result instead.
 */
interface GoogleDriveBackupProvider {
    suspend fun availability(): DriveBackupAvailability

    /**
     * Downloads Train Free's backup file. [knownFileId] is tried first; when
     * null or stale the provider searches the user's Drive.
     */
    suspend fun downloadBackup(knownFileId: String?): DriveDownloadResult

    /** Creates or updates (in place) the single rolling backup file. */
    suspend fun uploadBackup(json: String, knownFileId: String?): DriveUploadResult

    /** Forgets/revokes this device's Drive credentials. Best-effort. */
    suspend fun disconnect(accountEmail: String?)

    /** Retry a pending upload once connectivity returns. */
    fun scheduleBackgroundRetry()

    fun cancelBackgroundRetry()
}
