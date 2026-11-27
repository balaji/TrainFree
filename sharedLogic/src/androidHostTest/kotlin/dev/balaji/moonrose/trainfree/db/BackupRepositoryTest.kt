package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.model.SetType
import dev.balaji.moonrose.trainfree.model.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.TimeZone

class BackupRepositoryTest {

    private fun sampleBackup(): TrainFreeBackup = TrainFreeBackup(
        formatVersion = BACKUP_FORMAT_VERSION,
        exportedAt = 1_763_328_000_000L, // 2025-11-16T21:20:00Z
        appSchemaVersion = BACKUP_APP_SCHEMA_VERSION,
        profile = BackupProfile(
            age = 31,
            gender = "male",
            weight = 82.5,
            height = 180.0,
            createdAt = 1_000L,
            updatedAt = 2_000L,
        ),
        settings = listOf(
            BackupUserSetting("weight_unit", "metric", 1_000L, 2_000L),
            BackupUserSetting(
                SETTING_BARBELL_BAR_WEIGHTS,
                encodeBarWeights(mapOf(BarWeightKey(10L, WeightUnit.METRIC) to 20.0)),
                1_000L,
                2_000L,
            ),
        ),
        exercises = listOf(
            BackupExercise(
                id = 10L,
                name = "Barbell Back Squat",
                exerciseType = "strength",
                bodyPart = "legs",
                otherBodyParts = listOf("core"),
                equipment = "barbell",
                builtin = true,
                createdAt = 1_000L,
                updatedAt = 1_000L,
            ),
            BackupExercise(
                id = 200L,
                name = "Belt Squat",
                exerciseType = "strength",
                bodyPart = "legs",
                otherBodyParts = emptyList(),
                equipment = "machine",
                builtin = false,
                createdAt = 1_500L,
                updatedAt = 1_500L,
            ),
        ),
        programs = listOf(
            BackupProgram(id = 1L, name = "Strength Block", createdAt = 1_000L, updatedAt = 1_000L),
        ),
        routines = listOf(
            BackupRoutine(id = 2L, programId = 1L, name = "Lower A", position = 1, createdAt = 1_000L, updatedAt = 1_000L),
        ),
        workSets = listOf(
            BackupWorkSet(id = 3L, routineId = 2L, exerciseId = 10L, position = 1, createdAt = 1_000L, updatedAt = 1_000L),
        ),
        workSetDetails = listOf(
            BackupWorkSetDetail(
                id = 4L,
                workSetId = 3L,
                position = 1,
                targetWeight = 100.0,
                targetReps = 5,
                setType = SetType.NORMAL.key,
                weightUnit = WeightUnit.METRIC.value,
                createdAt = 1_000L,
                updatedAt = 1_000L,
            ),
        ),
        workoutSessions = listOf(
            BackupWorkoutSession(
                id = 5L,
                routineId = 2L,
                performedAt = 3_000L,
                completedAt = 4_000L,
                title = "Heavy Lower",
                testData = false,
                createdAt = 3_000L,
                updatedAt = 4_000L,
            ),
            BackupWorkoutSession(
                id = 6L,
                routineId = null,
                performedAt = 5_000L,
                completedAt = 6_000L,
                title = "",
                testData = true,
                createdAt = 5_000L,
                updatedAt = 6_000L,
            ),
        ),
        workoutSessionDetails = listOf(
            BackupWorkoutSessionDetail(
                id = 7L,
                workoutSessionId = 5L,
                workSetDetailId = 4L,
                exerciseId = 10L,
                position = 1,
                targetWeight = 100.0,
                targetReps = 5,
                actualWeight = 102.5,
                actualReps = 5,
                setType = SetType.NORMAL.key,
                weightUnit = WeightUnit.METRIC.value,
                completed = true,
                createdAt = 3_000L,
                updatedAt = 4_000L,
            ),
            BackupWorkoutSessionDetail(
                id = 8L,
                workoutSessionId = 6L,
                workSetDetailId = null,
                exerciseId = 200L,
                position = 1,
                actualWeight = 80.0,
                actualReps = 10,
                setType = SetType.DROP.key,
                weightUnit = WeightUnit.METRIC.value,
                completed = true,
                createdAt = 5_000L,
                updatedAt = 6_000L,
            ),
        ),
        bodyMetrics = listOf(
            BackupBodyMetric(
                weight = 82.1,
                weightUnit = WeightUnit.METRIC.value,
                loggedAt = 3_500L,
                testData = false,
                createdAt = 3_500L,
                updatedAt = 3_500L,
            ),
        ),
        journalEntries = listOf(
            BackupJournalEntry(
                epochDay = 20_408L,
                content = "Felt strong on squats",
                createdAt = 4_000L,
                updatedAt = 4_000L,
            ),
        ),
    )

    @Test
    fun encodeAndParseRoundTripsAllBackupData() {
        val original = sampleBackup()
        val json = encodeBackupJson(original)

        val (parsed, error) = parseAndValidateBackup(json)
        assertNull(error)
        assertEquals(original, parsed)

        val inspectResult = BackupRepository.inspect(json)
        assertTrue(inspectResult is BackupInspectResult.Valid)
        assertEquals(1, inspectResult.summary.programCount)
        assertEquals(1, inspectResult.summary.routineCount)
        assertEquals(2, inspectResult.summary.workoutCount)
        assertEquals(1, inspectResult.summary.journalEntryCount)
        assertEquals(1, inspectResult.summary.bodyMetricCount)
    }

    @Test
    fun parseIgnoresUnknownFieldsForForwardCompatibility() {
        val json = encodeBackupJson(sampleBackup())
            .replace("\"formatVersion\": 1,", "\"formatVersion\": 1,\n    \"extraFutureField\": \"ok\",")
        val (parsed, error) = parseAndValidateBackup(json)
        assertNull(error)
        assertNotNull(parsed)
    }

    @Test
    fun parseRejectsMalformedUnsupportedOrIncompleteBackups() {
        assertEquals(BackupError.INVALID_FILE, parseAndValidateBackup("").second)
        assertEquals(BackupError.INVALID_FILE, parseAndValidateBackup("{not json").second)

        val futureVersion = encodeBackupJson(sampleBackup().copy(formatVersion = BACKUP_FORMAT_VERSION + 1))
        assertEquals(BackupError.UNSUPPORTED_VERSION, parseAndValidateBackup(futureVersion).second)

        val zeroVersion = encodeBackupJson(sampleBackup().copy(formatVersion = 0))
        assertEquals(BackupError.UNSUPPORTED_VERSION, parseAndValidateBackup(zeroVersion).second)

        val missingProfile = encodeBackupJson(sampleBackup().copy(profile = null))
        assertEquals(BackupError.MISSING_PROFILE, parseAndValidateBackup(missingProfile).second)

        val danglingExercise = encodeBackupJson(
            sampleBackup().let {
                it.copy(
                    workSets = listOf(it.workSets.first().copy(exerciseId = 9999L)),
                )
            },
        )
        assertEquals(BackupError.INVALID_FILE, parseAndValidateBackup(danglingExercise).second)

        val duplicateJournalDay = encodeBackupJson(
            sampleBackup().let {
                it.copy(journalEntries = it.journalEntries + it.journalEntries.first())
            },
        )
        assertEquals(BackupError.INVALID_FILE, parseAndValidateBackup(duplicateJournalDay).second)
    }

    @Test
    fun suggestedFilenameUsesUtcDateDeterministically() {
        assertEquals(
            "trainfree-backup-2025-11-16.json",
            BackupRepository.suggestedFilename(millis = 1_763_328_000_000L, zone = TimeZone.UTC),
        )
    }

    @Test
    fun transferStateHandlesExportAndImportLifecycle() {
        var restoreCalls = 0
        var restoreResult: BackupError? = null
        val state = BackupTransferState(
            exportBackupJson = { "{\"ok\":true}" },
            suggestFilename = { "trainfree-backup-2026-11-16.json" },
            inspectBackupJson = { json ->
                if (json == "valid") {
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
            },
            restoreBackupJson = {
                restoreCalls++
                restoreResult
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        var exportedPair: Pair<String, String>? = null
        state.prepareExport { filename, json -> exportedPair = filename to json }
        assertEquals("trainfree-backup-2026-11-16.json" to "{\"ok\":true}", exportedPair)
        assertFalse(state.uiState.value.working)

        state.onExportCompleted(success = true)
        assertEquals(BackupNotice.EXPORT_SUCCEEDED, state.uiState.value.notice)
        state.dismissFeedback()
        assertNull(state.uiState.value.notice)

        state.inspectImport("bad")
        assertEquals(BackupError.INVALID_FILE, state.uiState.value.error)
        assertNull(state.uiState.value.pendingImport)
        state.dismissFeedback()

        state.inspectImport("valid")
        assertNotNull(state.uiState.value.pendingImport)
        state.cancelImport()
        assertNull(state.uiState.value.pendingImport)
        assertEquals(0, restoreCalls)

        state.inspectImport("valid")
        state.confirmImport()
        assertEquals(1, restoreCalls)
        assertNull(state.uiState.value.pendingImport)
        assertEquals(BackupNotice.IMPORT_SUCCEEDED, state.uiState.value.notice)

        restoreResult = BackupError.IMPORT_FAILED
        state.inspectImport("valid")
        state.confirmImport()
        assertEquals(BackupError.IMPORT_FAILED, state.uiState.value.error)
        assertNull(state.uiState.value.notice)
    }

    @Test
    fun backupExportExcludesLocalAiContentReportsDriveAndHealthSyncSettings() {
        val entities = listOf(
            UserSettingEntity(SETTING_WEIGHT_UNIT, "metric", 1_000L, 1_000L),
            UserSettingEntity(SETTING_AI_CONTENT_REPORTS, "[{\"source\":\"chat\"}]", 2_000L, 2_000L),
            UserSettingEntity(SETTING_GOOGLE_DRIVE_SYNC_ENABLED, "true", 2_000L, 2_000L),
            UserSettingEntity(SETTING_GOOGLE_DRIVE_ACCOUNT_EMAIL, "athlete@example.com", 2_000L, 2_000L),
            UserSettingEntity(SETTING_GOOGLE_DRIVE_FILE_ID, "drive-file-1", 2_000L, 2_000L),
            UserSettingEntity(SETTING_GOOGLE_DRIVE_LAST_SYNCED_AT, "3000", 2_000L, 2_000L),
            UserSettingEntity(SETTING_GOOGLE_DRIVE_PENDING_SYNC, "true", 2_000L, 2_000L),
            UserSettingEntity(SETTING_GOOGLE_DRIVE_LAST_ERROR, "offline", 2_000L, 2_000L),
            UserSettingEntity(SETTING_HEALTH_SYNC_WORKOUTS, "true", 2_000L, 2_000L),
            UserSettingEntity(SETTING_HEALTH_SYNC_WEIGHT, "true", 2_000L, 2_000L),
            UserSettingEntity(SETTING_HEALTH_LAST_WEIGHT_SYNC, "4000", 2_000L, 2_000L),
        )
        val exported = entities.toBackupUserSettings()
        assertEquals(listOf(SETTING_WEIGHT_UNIT), exported.map { it.key })
    }
}
