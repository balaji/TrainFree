package dev.balaji.moonrose.trainfree.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import dev.balaji.moonrose.trainfree.backup.DriveBackupAvailability
import dev.balaji.moonrose.trainfree.backup.DriveDownloadFailure
import dev.balaji.moonrose.trainfree.backup.DriveDownloadResult
import dev.balaji.moonrose.trainfree.backup.DriveErrorKind
import dev.balaji.moonrose.trainfree.backup.DriveUploadFailure
import dev.balaji.moonrose.trainfree.backup.DriveUploadSuccess
import dev.balaji.moonrose.trainfree.backup.GoogleDriveBackupProvider
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Snapshot of the device-local Drive sync rows in user_settings. */
internal data class DriveSyncSnapshot(
    val enabled: Boolean = false,
    val accountEmail: String? = null,
    val fileId: String? = null,
    val lastSyncedAt: Long? = null,
    val pendingSync: Boolean = false,
    val lastError: DriveErrorKind? = null,
)

/** Persistence + backup seam so the sync engine can be unit tested without Room. */
internal interface DriveSyncStore {
    suspend fun read(): DriveSyncSnapshot
    suspend fun write(snapshot: DriveSyncSnapshot)
    suspend fun clear()
    suspend fun exportJson(): String
    fun inspect(json: String): BackupInspectResult
    suspend fun restore(json: String): BackupError?
}

/** Operations [GoogleDriveBackupState] drives; implemented by [DriveBackupEngine]. */
internal interface DriveBackupOps {
    suspend fun inspectRemote(useStoredFileId: Boolean): DriveDownloadResult
    fun inspect(json: String): BackupInspectResult
    suspend fun enableAndUpload(knownFileId: String?, accountEmail: String?): DriveErrorKind?
    suspend fun restoreFromDrive(
        json: String,
        fileId: String,
        accountEmail: String?,
        enableAutoBackup: Boolean,
    ): BackupError?
    suspend fun syncNow(): DriveErrorKind?
    suspend fun disableAndDisconnect()
    suspend fun disconnectProvider(accountEmail: String?)
}

private object RoomDriveSyncStore : DriveSyncStore {
    override suspend fun read(): DriveSyncSnapshot {
        val byKey = AppDb.db.userSettingDao().getAll()
            .filter { it.key in GOOGLE_DRIVE_SETTING_KEYS }
            .associate { it.key to it.value }
        return DriveSyncSnapshot(
            enabled = byKey[SETTING_GOOGLE_DRIVE_SYNC_ENABLED].toBoolean(),
            accountEmail = byKey[SETTING_GOOGLE_DRIVE_ACCOUNT_EMAIL]?.takeIf { it.isNotBlank() },
            fileId = byKey[SETTING_GOOGLE_DRIVE_FILE_ID]?.takeIf { it.isNotBlank() },
            lastSyncedAt = byKey[SETTING_GOOGLE_DRIVE_LAST_SYNCED_AT]?.toLongOrNull(),
            pendingSync = byKey[SETTING_GOOGLE_DRIVE_PENDING_SYNC].toBoolean(),
            lastError = DriveErrorKind.fromValue(byKey[SETTING_GOOGLE_DRIVE_LAST_ERROR]),
        )
    }

    override suspend fun write(snapshot: DriveSyncSnapshot) {
        val values = mapOf(
            SETTING_GOOGLE_DRIVE_SYNC_ENABLED to snapshot.enabled.takeIf { it }?.toString(),
            SETTING_GOOGLE_DRIVE_ACCOUNT_EMAIL to snapshot.accountEmail,
            SETTING_GOOGLE_DRIVE_FILE_ID to snapshot.fileId,
            SETTING_GOOGLE_DRIVE_LAST_SYNCED_AT to snapshot.lastSyncedAt?.toString(),
            SETTING_GOOGLE_DRIVE_PENDING_SYNC to snapshot.pendingSync.takeIf { it }?.toString(),
            SETTING_GOOGLE_DRIVE_LAST_ERROR to snapshot.lastError?.value,
        )
        val now = nowMillis()
        AppDb.db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val dao = AppDb.db.userSettingDao()
                for ((key, value) in values) {
                    if (value == null) {
                        dao.delete(key)
                    } else {
                        val existing = dao.find(key)
                        if (existing?.value != value) {
                            dao.upsert(
                                UserSettingEntity(
                                    key = key,
                                    value = value,
                                    createdAt = existing?.createdAt ?: now,
                                    updatedAt = now,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun clear() = write(DriveSyncSnapshot())

    override suspend fun exportJson(): String = BackupRepository.exportJson()

    override fun inspect(json: String): BackupInspectResult = BackupRepository.inspect(json)

    override suspend fun restore(json: String): BackupError? = BackupRepository.restore(json)
}

/**
 * Upload/restore engine behind [GoogleDriveBackupManager]. Uploads are
 * serialized by [syncMutex]; change notifications that arrive while an upload
 * is in flight are coalesced into a single follow-up upload.
 */
internal class DriveBackupEngine(
    private val store: DriveSyncStore,
    private val providerRef: () -> GoogleDriveBackupProvider?,
    private val scope: CoroutineScope,
) : DriveBackupOps {
    private val syncMutex = Mutex()
    private val loopLock = Mutex()
    private var loopRunning = false
    private var dirty = false

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    /** Fire-and-forget hook for workout/journal mutations. Never throws. */
    fun onDataChanged() {
        if (providerRef() == null) return
        scope.launch {
            try {
                val snapshot = store.read()
                if (!snapshot.enabled) return@launch
                if (!snapshot.pendingSync) store.write(snapshot.copy(pendingSync = true))
                // A dead session needs the user to reconnect; keep the change
                // pending instead of retrying against a known auth failure.
                if (snapshot.lastError == DriveErrorKind.AUTH_REQUIRED) return@launch
                requestSync()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Drive backup is best-effort; never surface into local saves.
            }
        }
    }

    internal suspend fun requestSync() {
        val start = loopLock.withLock {
            if (loopRunning) {
                dirty = true
                false
            } else {
                loopRunning = true
                dirty = false
                true
            }
        }
        if (!start) return
        try {
            while (true) {
                val error = syncNow()
                val again = loopLock.withLock {
                    if (dirty && error == null) {
                        dirty = false
                        true
                    } else {
                        loopRunning = false
                        false
                    }
                }
                if (!again) break
            }
        } catch (t: Throwable) {
            loopLock.withLock { loopRunning = false }
            throw t
        }
    }

    override suspend fun syncNow(): DriveErrorKind? = syncMutex.withLock {
        val provider = providerRef() ?: return@withLock DriveErrorKind.NOT_CONFIGURED
        val snapshot = store.read()
        if (!snapshot.enabled) return@withLock null
        _isSyncing.value = true
        try {
            val result = try {
                provider.uploadBackup(store.exportJson(), snapshot.fileId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                DriveUploadFailure(DriveErrorKind.UNKNOWN)
            }
            // Re-read: the user may have disconnected while the upload ran.
            val latest = store.read()
            when (result) {
                is DriveUploadSuccess -> {
                    if (latest.enabled) {
                        store.write(
                            latest.copy(
                                fileId = result.fileId,
                                accountEmail = result.accountEmail ?: latest.accountEmail,
                                lastSyncedAt = result.syncedAtMillis,
                                pendingSync = false,
                                lastError = null,
                            ),
                        )
                    }
                    provider.cancelBackgroundRetry()
                    null
                }
                is DriveUploadFailure -> {
                    if (latest.enabled) {
                        store.write(latest.copy(pendingSync = true, lastError = result.kind))
                        if (result.kind.isTransient) provider.scheduleBackgroundRetry()
                    }
                    result.kind
                }
            }
        } finally {
            _isSyncing.value = false
        }
    }

    /** Foreground / connectivity retry of a previously failed upload. */
    suspend fun retryPendingSyncIfNeeded(): DriveErrorKind? {
        if (providerRef() == null) return null
        val snapshot = store.read()
        if (!snapshot.enabled || !snapshot.pendingSync) return null
        if (snapshot.lastError == DriveErrorKind.AUTH_REQUIRED ||
            snapshot.lastError == DriveErrorKind.NOT_CONFIGURED
        ) {
            return snapshot.lastError
        }
        return syncNow()
    }

    override suspend fun inspectRemote(useStoredFileId: Boolean): DriveDownloadResult {
        val provider = providerRef() ?: return DriveDownloadFailure(DriveErrorKind.NOT_CONFIGURED)
        val knownFileId = if (useStoredFileId) store.read().fileId else null
        return try {
            provider.downloadBackup(knownFileId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            DriveDownloadFailure(DriveErrorKind.UNKNOWN)
        }
    }

    override fun inspect(json: String): BackupInspectResult = store.inspect(json)

    override suspend fun enableAndUpload(knownFileId: String?, accountEmail: String?): DriveErrorKind? {
        store.write(
            DriveSyncSnapshot(
                enabled = true,
                accountEmail = accountEmail,
                fileId = knownFileId,
                pendingSync = true,
            ),
        )
        return syncNow()
    }

    override suspend fun restoreFromDrive(
        json: String,
        fileId: String,
        accountEmail: String?,
        enableAutoBackup: Boolean,
    ): BackupError? {
        val error = store.restore(json)
        if (error != null) return error
        if (enableAutoBackup) {
            store.write(
                DriveSyncSnapshot(
                    enabled = true,
                    accountEmail = accountEmail,
                    fileId = fileId,
                    lastSyncedAt = nowMillis(),
                    pendingSync = false,
                    lastError = null,
                ),
            )
        }
        return null
    }

    override suspend fun disableAndDisconnect() {
        val previous = store.read()
        store.clear()
        disconnectProvider(previous.accountEmail)
    }

    override suspend fun disconnectProvider(accountEmail: String?) {
        val provider = providerRef() ?: return
        try {
            provider.cancelBackgroundRetry()
            provider.disconnect(accountEmail)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Best-effort; local state is already cleared.
        }
    }
}

/**
 * Optional automatic backup of the full local database to the user's Google
 * Drive (`My Drive/TrainFree/trainfree-backup.json`). Strictly opt-in via the
 * Settings toggle; uploads are best-effort and never block or fail local
 * workout/journal saves. Failed uploads stay pending and are retried when
 * connectivity returns or the app comes back to the foreground.
 */
object GoogleDriveBackupManager {
    @Volatile
    private var provider: GoogleDriveBackupProvider? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val engine: DriveBackupEngine by lazy {
        DriveBackupEngine(RoomDriveSyncStore, { provider }, scope)
    }

    fun register(provider: GoogleDriveBackupProvider) {
        this.provider = provider
    }

    /** Registers [provider] only if none is set yet (background worker cold start). */
    fun registerIfAbsent(provider: () -> GoogleDriveBackupProvider) {
        if (this.provider == null) this.provider = provider()
    }

    /** Whether an upload is currently running. */
    val isSyncing: StateFlow<Boolean> get() = engine.isSyncing

    suspend fun availability(): DriveBackupAvailability =
        provider?.availability() ?: DriveBackupAvailability.UNAVAILABLE

    /** Called after workout and journal mutations. */
    fun onWorkoutOrJournalChanged() {
        if (provider == null) return
        engine.onDataChanged()
    }

    suspend fun syncNow(): DriveErrorKind? = engine.syncNow()

    suspend fun retryPendingSyncIfNeeded(): DriveErrorKind? = engine.retryPendingSyncIfNeeded()
}
