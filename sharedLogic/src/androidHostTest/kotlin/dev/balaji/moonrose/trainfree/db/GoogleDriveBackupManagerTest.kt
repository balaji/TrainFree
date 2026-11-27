package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.backup.DriveBackupAvailability
import dev.balaji.moonrose.trainfree.backup.DriveDownloadFound
import dev.balaji.moonrose.trainfree.backup.DriveDownloadNotFound
import dev.balaji.moonrose.trainfree.backup.DriveDownloadResult
import dev.balaji.moonrose.trainfree.backup.DriveErrorKind
import dev.balaji.moonrose.trainfree.backup.DriveUploadFailure
import dev.balaji.moonrose.trainfree.backup.DriveUploadResult
import dev.balaji.moonrose.trainfree.backup.DriveUploadSuccess
import dev.balaji.moonrose.trainfree.backup.GoogleDriveBackupProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class GoogleDriveBackupManagerTest {

    private class FakeDriveStore : DriveSyncStore {
        var snapshot = DriveSyncSnapshot()
        var exportedJson = "{\"valid\":true}"
        var restoredJson: String? = null
        var restoreError: BackupError? = null

        override suspend fun read(): DriveSyncSnapshot = snapshot
        override suspend fun write(snapshot: DriveSyncSnapshot) {
            this.snapshot = snapshot
        }
        override suspend fun clear() {
            snapshot = DriveSyncSnapshot()
        }
        override suspend fun exportJson(): String = exportedJson
        override fun inspect(json: String): BackupInspectResult =
            if (json.contains("\"valid\":true")) {
                BackupInspectResult.Valid(
                    BackupSummary(
                        exportedAt = 1_763_328_000_000L,
                        exportedEpochDay = 20_408L,
                        programCount = 2,
                        routineCount = 4,
                        workoutCount = 12,
                        journalEntryCount = 3,
                        bodyMetricCount = 5,
                    ),
                )
            } else {
                BackupInspectResult.Invalid(BackupError.INVALID_FILE)
            }
        override suspend fun restore(json: String): BackupError? {
            restoredJson = json
            return restoreError
        }
    }

    private class FakeDriveProvider : GoogleDriveBackupProvider {
        var downloadResult: DriveDownloadResult = DriveDownloadNotFound("athlete@example.com")
        var uploadHandler: suspend (json: String, knownFileId: String?) -> DriveUploadResult = { _, knownId ->
            DriveUploadSuccess(
                fileId = knownId ?: "file-123",
                accountEmail = "athlete@example.com",
                syncedAtMillis = 9_999L,
            )
        }
        var uploadCalls = 0
        var disconnectCalls = 0
        var lastDisconnectedEmail: String? = null
        var scheduleRetryCalls = 0
        var cancelRetryCalls = 0

        override suspend fun availability() = DriveBackupAvailability.AVAILABLE
        override suspend fun downloadBackup(knownFileId: String?) = downloadResult
        override suspend fun uploadBackup(json: String, knownFileId: String?): DriveUploadResult {
            uploadCalls++
            return uploadHandler(json, knownFileId)
        }
        override suspend fun disconnect(accountEmail: String?) {
            disconnectCalls++
            lastDisconnectedEmail = accountEmail
        }
        override fun scheduleBackgroundRetry() {
            scheduleRetryCalls++
        }
        override fun cancelBackgroundRetry() {
            cancelRetryCalls++
        }
    }

    private fun harness(): Triple<FakeDriveStore, FakeDriveProvider, DriveBackupEngine> {
        val store = FakeDriveStore()
        val provider = FakeDriveProvider()
        val engine = DriveBackupEngine(store, { provider }, CoroutineScope(Dispatchers.Unconfined))
        return Triple(store, provider, engine)
    }

    @Test
    fun enableWithNoRemoteBackupUploadsImmediatelyAndPersistsMetadata() {
        val (store, provider, engine) = harness()
        val state = GoogleDriveBackupState(engine, CoroutineScope(Dispatchers.Unconfined))

        state.onEnableAuthResult(authorized = true, failure = null)

        assertEquals(1, provider.uploadCalls)
        assertTrue(store.snapshot.enabled)
        assertEquals("file-123", store.snapshot.fileId)
        assertEquals("athlete@example.com", store.snapshot.accountEmail)
        assertEquals(9_999L, store.snapshot.lastSyncedAt)
        assertFalse(store.snapshot.pendingSync)
        assertNull(store.snapshot.lastError)
        assertEquals(DriveBackupNotice.ENABLED, state.uiState.value.notice)
        assertNull(state.uiState.value.pendingEnableConflict)
    }

    @Test
    fun enableWithExistingRemotePromptsAndSupportsRestoreOverwriteAndCancel() {
        val remoteJson = "{\"valid\":true,\"from\":\"drive\"}"

        // 1. Restore branch
        run {
            val (store, provider, engine) = harness()
            provider.downloadResult = DriveDownloadFound("remote-1", remoteJson, "athlete@example.com")
            val state = GoogleDriveBackupState(engine, CoroutineScope(Dispatchers.Unconfined))

            state.onEnableAuthResult(authorized = true, failure = null)
            assertEquals(0, provider.uploadCalls)
            assertNotNull(state.uiState.value.pendingEnableConflict)
            assertFalse(store.snapshot.enabled)

            state.confirmEnableRestore()
            assertEquals(remoteJson, store.restoredJson)
            assertTrue(store.snapshot.enabled)
            assertEquals("remote-1", store.snapshot.fileId)
            assertEquals(DriveBackupNotice.RESTORED, state.uiState.value.notice)
            assertNull(state.uiState.value.pendingEnableConflict)
        }

        // 2. Overwrite branch
        run {
            val (store, provider, engine) = harness()
            provider.downloadResult = DriveDownloadFound("remote-1", remoteJson, "athlete@example.com")
            val state = GoogleDriveBackupState(engine, CoroutineScope(Dispatchers.Unconfined))

            state.onEnableAuthResult(authorized = true, failure = null)
            state.confirmEnableOverwrite()
            assertEquals(1, provider.uploadCalls)
            assertTrue(store.snapshot.enabled)
            assertEquals("remote-1", store.snapshot.fileId)
            assertEquals(DriveBackupNotice.ENABLED, state.uiState.value.notice)
        }

        // 3. Cancel branch
        run {
            val (store, provider, engine) = harness()
            provider.downloadResult = DriveDownloadFound("remote-1", remoteJson, "athlete@example.com")
            val state = GoogleDriveBackupState(engine, CoroutineScope(Dispatchers.Unconfined))

            state.onEnableAuthResult(authorized = true, failure = null)
            state.cancelEnableConflict()
            assertEquals(0, provider.uploadCalls)
            assertFalse(store.snapshot.enabled)
            assertEquals(1, provider.disconnectCalls)
            assertNull(state.uiState.value.pendingEnableConflict)
        }
    }

    @Test
    fun dataChangeIsNoOpWhenDisabledAndCoalescesOverlappingTriggers() = runBlocking {
        val (store, provider, _) = harness()
        val engine = DriveBackupEngine(store, { provider }, this)

        // Disabled -> no-op (yield so the launched coroutine actually runs while disabled)
        engine.onDataChanged()
        kotlinx.coroutines.yield()
        assertEquals(0, provider.uploadCalls)

        // Enabled + overlapping requestSync calls -> coalesced into one follow-up upload
        store.snapshot = DriveSyncSnapshot(enabled = true, fileId = "file-1")
        val firstGate = CompletableDeferred<Unit>()
        provider.uploadHandler = { _, knownId ->
            if (provider.uploadCalls == 1) {
                firstGate.await()
            }
            DriveUploadSuccess(knownId ?: "file-1", "athlete@example.com", 5_000L)
        }

        val job1 = launch { engine.requestSync() }
        val job2 = launch { engine.requestSync() }
        val job3 = launch { engine.requestSync() }
        // Yield until first upload hits the gate, then let queued dirty flag flush once.
        while (provider.uploadCalls == 0) kotlinx.coroutines.yield()
        job2.join()
        job3.join()
        firstGate.complete(Unit)
        job1.join()

        assertEquals(2, provider.uploadCalls)
        assertFalse(store.snapshot.pendingSync)
    }

    @Test
    fun offlineFailureMarksPendingAndSchedulesRetryUntilForegroundRetrySucceeds() = runBlocking {
        val (store, provider, engine) = harness()
        store.snapshot = DriveSyncSnapshot(enabled = true, fileId = "file-1", accountEmail = "athlete@example.com")
        provider.uploadHandler = { _, _ -> DriveUploadFailure(DriveErrorKind.OFFLINE) }

        val firstError = engine.syncNow()
        assertEquals(DriveErrorKind.OFFLINE, firstError)
        assertTrue(store.snapshot.pendingSync)
        assertEquals(DriveErrorKind.OFFLINE, store.snapshot.lastError)
        assertEquals(1, provider.scheduleRetryCalls)
        assertEquals(0, provider.cancelRetryCalls)

        // Connectivity returns
        provider.uploadHandler = { _, knownId ->
            DriveUploadSuccess(knownId ?: "file-1", "athlete@example.com", 12_345L)
        }
        val retryError = engine.retryPendingSyncIfNeeded()
        assertNull(retryError)
        assertFalse(store.snapshot.pendingSync)
        assertNull(store.snapshot.lastError)
        assertEquals(12_345L, store.snapshot.lastSyncedAt)
        assertEquals(1, provider.cancelRetryCalls)
    }

    @Test
    fun authRequiredDoesNotScheduleBackgroundRetryOrAutoRetryOnForeground() = runBlocking {
        val (store, provider, engine) = harness()
        store.snapshot = DriveSyncSnapshot(enabled = true, fileId = "file-1", accountEmail = "athlete@example.com")
        provider.uploadHandler = { _, _ -> DriveUploadFailure(DriveErrorKind.AUTH_REQUIRED) }

        val err = engine.syncNow()
        assertEquals(DriveErrorKind.AUTH_REQUIRED, err)
        assertTrue(store.snapshot.pendingSync)
        assertEquals(DriveErrorKind.AUTH_REQUIRED, store.snapshot.lastError)
        assertEquals(0, provider.scheduleRetryCalls)

        val retryErr = engine.retryPendingSyncIfNeeded()
        assertEquals(DriveErrorKind.AUTH_REQUIRED, retryErr)
        assertEquals(1, provider.uploadCalls)
    }

    @Test
    fun disableAndDisconnectClearsSettingsAndRevokesProvider() {
        val (store, provider, engine) = harness()
        store.snapshot = DriveSyncSnapshot(
            enabled = true,
            accountEmail = "athlete@example.com",
            fileId = "file-1",
            lastSyncedAt = 10_000L,
            pendingSync = true,
            lastError = DriveErrorKind.OFFLINE,
        )
        val state = GoogleDriveBackupState(engine, CoroutineScope(Dispatchers.Unconfined))

        state.disable()

        assertEquals(DriveSyncSnapshot(), store.snapshot)
        assertEquals(1, provider.cancelRetryCalls)
        assertEquals(1, provider.disconnectCalls)
        assertEquals("athlete@example.com", provider.lastDisconnectedEmail)
    }

    @Test
    fun onboardingRestoreLeavesAutoBackupOffAndDisconnectsAfterwards() {
        val (store, provider, engine) = harness()
        val remoteJson = "{\"valid\":true,\"from\":\"drive\"}"
        provider.downloadResult = DriveDownloadFound("remote-1", remoteJson, "athlete@example.com")
        val state = GoogleDriveBackupState(engine, CoroutineScope(Dispatchers.Unconfined))

        state.onRestoreAuthResult(authorized = true, failure = null, fromOnboarding = true)
        assertNotNull(state.uiState.value.pendingRestore)

        state.confirmRestore()
        assertEquals(remoteJson, store.restoredJson)
        assertFalse(store.snapshot.enabled)
        assertEquals(1, provider.disconnectCalls)
        assertEquals(DriveBackupNotice.RESTORED, state.uiState.value.notice)
    }
}
