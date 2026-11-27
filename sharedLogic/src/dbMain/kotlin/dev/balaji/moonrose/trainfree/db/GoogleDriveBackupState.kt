package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.backup.DriveDownloadFailure
import dev.balaji.moonrose.trainfree.backup.DriveDownloadFound
import dev.balaji.moonrose.trainfree.backup.DriveDownloadNotFound
import dev.balaji.moonrose.trainfree.backup.DriveErrorKind
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** A valid backup found in the user's Google Drive, awaiting a decision. */
data class PendingDriveBackup(
    val summary: BackupSummary,
    val rawJson: String,
    val fileId: String,
    val accountEmail: String?,
)

enum class DriveBackupNotice {
    ENABLED,
    SYNCED,
    RESTORED,
}

data class GoogleDriveBackupUiState(
    val working: Boolean = false,
    /** Enabling found an existing Drive backup: restore it, overwrite it, or cancel. */
    val pendingEnableConflict: PendingDriveBackup? = null,
    /** Explicit restore from Drive, awaiting confirmation. */
    val pendingRestore: PendingDriveBackup? = null,
    val driveError: DriveErrorKind? = null,
    val backupError: BackupError? = null,
    val notice: DriveBackupNotice? = null,
)

/** Local date/time parts of a sync timestamp, for "Last backup" labels. */
data class DriveSyncTimeParts(val epochDay: Long, val hour: Int, val minute: Int)

@OptIn(ExperimentalTime::class)
fun driveSyncTimeParts(
    millis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): DriveSyncTimeParts {
    val local = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
    return DriveSyncTimeParts(local.date.toEpochDays(), local.hour, local.minute)
}

/**
 * Shared Google Drive backup state holder consumed by both Android Compose
 * and iOS SwiftUI. Interactive Google sign-in happens in each shell, which
 * then reports the outcome through the `on…AuthResult` methods.
 */
class GoogleDriveBackupState internal constructor(
    private val ops: DriveBackupOps,
    private val scope: CoroutineScope,
) {
    constructor() : this(GoogleDriveBackupManager.engine, CoroutineScope(Dispatchers.Main))

    private val _uiState = MutableStateFlow(GoogleDriveBackupUiState())
    val uiState: StateFlow<GoogleDriveBackupUiState> = _uiState.asStateFlow()

    /** Restore started from the first-run screen: auto-backup stays off afterwards. */
    private var restoreFromOnboarding = false

    // region Enable

    /** Result of the interactive sign-in launched by the auto-backup toggle. */
    fun onEnableAuthResult(authorized: Boolean, failure: DriveErrorKind?) {
        if (!authorized) {
            reportAuthFailure(failure)
            return
        }
        if (_uiState.value.working) return
        startWork()
        scope.launch {
            when (val remote = ops.inspectRemote(useStoredFileId = false)) {
                is DriveDownloadNotFound -> {
                    val error = ops.enableAndUpload(knownFileId = null, accountEmail = remote.accountEmail)
                    finishWork(driveError = error, notice = DriveBackupNotice.ENABLED.takeIf { error == null })
                }
                is DriveDownloadFound -> when (val inspected = ops.inspect(remote.json)) {
                    is BackupInspectResult.Valid -> _uiState.update {
                        it.copy(working = false, pendingEnableConflict = remote.toPending(inspected.summary))
                    }
                    is BackupInspectResult.Invalid -> {
                        // Never clobber a backup we can't read (e.g. from a newer app version).
                        ops.disconnectProvider(remote.accountEmail)
                        finishWork(backupError = inspected.error)
                    }
                }
                is DriveDownloadFailure -> {
                    ops.disconnectProvider(null)
                    finishWork(driveError = remote.kind)
                }
            }
        }
    }

    fun confirmEnableRestore() {
        val pending = _uiState.value.pendingEnableConflict ?: return
        if (_uiState.value.working) return
        startWork()
        scope.launch {
            val error = ops.restoreFromDrive(pending.rawJson, pending.fileId, pending.accountEmail, enableAutoBackup = true)
            _uiState.update { it.copy(pendingEnableConflict = null) }
            finishWork(backupError = error, notice = DriveBackupNotice.RESTORED.takeIf { error == null })
        }
    }

    fun confirmEnableOverwrite() {
        val pending = _uiState.value.pendingEnableConflict ?: return
        if (_uiState.value.working) return
        startWork()
        scope.launch {
            val error = ops.enableAndUpload(pending.fileId, pending.accountEmail)
            _uiState.update { it.copy(pendingEnableConflict = null) }
            finishWork(driveError = error, notice = DriveBackupNotice.ENABLED.takeIf { error == null })
        }
    }

    fun cancelEnableConflict() {
        val pending = _uiState.value.pendingEnableConflict ?: return
        if (_uiState.value.working) return
        _uiState.update { it.copy(pendingEnableConflict = null) }
        scope.launch { ops.disconnectProvider(pending.accountEmail) }
    }

    // endregion

    // region Restore

    /**
     * Result of the interactive sign-in launched by "Restore from Google
     * Drive". [fromOnboarding] restores leave auto-backup off and disconnect
     * afterwards; Settings restores keep the existing connection.
     */
    fun onRestoreAuthResult(authorized: Boolean, failure: DriveErrorKind?, fromOnboarding: Boolean) {
        if (!authorized) {
            reportAuthFailure(failure)
            return
        }
        if (_uiState.value.working) return
        restoreFromOnboarding = fromOnboarding
        startWork()
        scope.launch {
            when (val remote = ops.inspectRemote(useStoredFileId = !fromOnboarding)) {
                is DriveDownloadNotFound -> {
                    if (fromOnboarding) ops.disconnectProvider(remote.accountEmail)
                    finishWork(driveError = DriveErrorKind.NOT_FOUND)
                }
                is DriveDownloadFound -> when (val inspected = ops.inspect(remote.json)) {
                    is BackupInspectResult.Valid -> _uiState.update {
                        it.copy(working = false, pendingRestore = remote.toPending(inspected.summary))
                    }
                    is BackupInspectResult.Invalid -> {
                        if (fromOnboarding) ops.disconnectProvider(remote.accountEmail)
                        finishWork(backupError = inspected.error)
                    }
                }
                is DriveDownloadFailure -> {
                    if (fromOnboarding) ops.disconnectProvider(null)
                    finishWork(driveError = remote.kind)
                }
            }
        }
    }

    fun confirmRestore() {
        val pending = _uiState.value.pendingRestore ?: return
        if (_uiState.value.working) return
        startWork()
        val onboarding = restoreFromOnboarding
        scope.launch {
            val error = ops.restoreFromDrive(
                pending.rawJson,
                pending.fileId,
                pending.accountEmail,
                enableAutoBackup = !onboarding,
            )
            if (onboarding) ops.disconnectProvider(pending.accountEmail)
            _uiState.update { it.copy(pendingRestore = null) }
            finishWork(backupError = error, notice = DriveBackupNotice.RESTORED.takeIf { error == null })
        }
    }

    fun cancelRestore() {
        val pending = _uiState.value.pendingRestore ?: return
        if (_uiState.value.working) return
        _uiState.update { it.copy(pendingRestore = null) }
        if (restoreFromOnboarding) {
            scope.launch { ops.disconnectProvider(pending.accountEmail) }
        }
    }

    // endregion

    // region Connected actions

    fun syncNow() {
        if (_uiState.value.working) return
        startWork()
        scope.launch {
            val error = ops.syncNow()
            finishWork(driveError = error, notice = DriveBackupNotice.SYNCED.takeIf { error == null })
        }
    }

    /** Result of re-running sign-in after the Drive session expired. */
    fun onReconnectAuthResult(authorized: Boolean, failure: DriveErrorKind?) {
        if (!authorized) {
            reportAuthFailure(failure)
            return
        }
        syncNow()
    }

    /** Turns auto-backup off and disconnects this device's Google account. */
    fun disable() {
        scope.launch { ops.disableAndDisconnect() }
    }

    fun dismissFeedback() {
        _uiState.update { it.copy(driveError = null, backupError = null, notice = null) }
    }

    // endregion

    private fun reportAuthFailure(failure: DriveErrorKind?) {
        if (failure == null || failure == DriveErrorKind.CANCELLED) return
        _uiState.update { it.copy(driveError = failure, backupError = null, notice = null) }
    }

    private fun startWork() {
        _uiState.update { it.copy(working = true, driveError = null, backupError = null, notice = null) }
    }

    private fun finishWork(
        driveError: DriveErrorKind? = null,
        backupError: BackupError? = null,
        notice: DriveBackupNotice? = null,
    ) {
        _uiState.update {
            it.copy(working = false, driveError = driveError, backupError = backupError, notice = notice)
        }
    }

    private fun DriveDownloadFound.toPending(summary: BackupSummary) = PendingDriveBackup(
        summary = summary,
        rawJson = json,
        fileId = fileId,
        accountEmail = accountEmail,
    )
}
