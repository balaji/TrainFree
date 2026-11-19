package dev.balaji.moonrose.trainfree.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import dev.balaji.moonrose.trainfree.model.SetType
import dev.balaji.moonrose.trainfree.model.WeightUnit
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

const val BACKUP_FORMAT_VERSION = 1
const val BACKUP_APP_SCHEMA_VERSION = 20
const val MAX_BACKUP_FILE_BYTES = 50 * 1024 * 1024

@Serializable
internal data class TrainFreeBackup(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val exportedAt: Long,
    val appSchemaVersion: Int = BACKUP_APP_SCHEMA_VERSION,
    val profile: BackupProfile? = null,
    val settings: List<BackupUserSetting> = emptyList(),
    val exercises: List<BackupExercise> = emptyList(),
    val programs: List<BackupProgram> = emptyList(),
    val routines: List<BackupRoutine> = emptyList(),
    val workSets: List<BackupWorkSet> = emptyList(),
    val workSetDetails: List<BackupWorkSetDetail> = emptyList(),
    val workoutSessions: List<BackupWorkoutSession> = emptyList(),
    val workoutSessionDetails: List<BackupWorkoutSessionDetail> = emptyList(),
    val bodyMetrics: List<BackupBodyMetric> = emptyList(),
    val journalEntries: List<BackupJournalEntry> = emptyList(),
)

@Serializable
internal data class BackupProfile(
    val age: Int? = null,
    val gender: String? = null,
    val weight: Double? = null,
    val height: Double? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class BackupUserSetting(
    val key: String,
    val value: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class BackupExercise(
    val id: Long,
    val name: String,
    val exerciseType: String? = null,
    val bodyPart: String? = null,
    val otherBodyParts: List<String>? = null,
    val equipment: String? = null,
    val builtin: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class BackupProgram(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class BackupRoutine(
    val id: Long,
    val programId: Long,
    val name: String,
    val position: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class BackupWorkSet(
    val id: Long,
    val routineId: Long,
    val exerciseId: Long,
    val position: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class BackupWorkSetDetail(
    val id: Long,
    val workSetId: Long,
    val position: Int,
    val targetWeight: Double? = null,
    val targetReps: Int? = null,
    val targetDuration: Int? = null,
    val targetDistance: Double? = null,
    val setType: String = SetType.NORMAL.key,
    val weightUnit: String = WeightUnit.METRIC.value,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class BackupWorkoutSession(
    val id: Long,
    val routineId: Long? = null,
    val performedAt: Long,
    val completedAt: Long? = null,
    val title: String = "",
    val testData: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class BackupWorkoutSessionDetail(
    val id: Long,
    val workoutSessionId: Long,
    val workSetDetailId: Long? = null,
    val exerciseId: Long,
    val position: Int,
    val targetWeight: Double? = null,
    val targetReps: Int? = null,
    val targetDuration: Int? = null,
    val targetDistance: Double? = null,
    val actualWeight: Double? = null,
    val actualReps: Int? = null,
    val actualDuration: Int? = null,
    val actualDistance: Double? = null,
    val setType: String = SetType.NORMAL.key,
    val weightUnit: String = WeightUnit.METRIC.value,
    val completed: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class BackupBodyMetric(
    val weight: Double,
    val weightUnit: String = WeightUnit.METRIC.value,
    val loggedAt: Long,
    val testData: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class BackupJournalEntry(
    val epochDay: Long,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class BackupSummary(
    val exportedAt: Long,
    val exportedEpochDay: Long,
    val programCount: Int,
    val routineCount: Int,
    val workoutCount: Int,
    val journalEntryCount: Int,
    val bodyMetricCount: Int,
)

enum class BackupError {
    INVALID_FILE,
    UNSUPPORTED_VERSION,
    MISSING_PROFILE,
    EXPORT_FAILED,
    IMPORT_FAILED,
}

sealed class BackupInspectResult {
    data class Valid(val summary: BackupSummary) : BackupInspectResult()
    data class Invalid(val error: BackupError) : BackupInspectResult()
}

private val backupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

object BackupRepository {
    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        encodeBackupJson(AppDb.db.exportBackup())
    }

    @OptIn(ExperimentalTime::class)
    fun suggestedFilename(
        millis: Long = nowMillis(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date
        return "trainfree-backup-$date.json"
    }

    fun inspect(json: String): BackupInspectResult {
        val (backup, error) = parseAndValidateBackup(json)
        if (error != null || backup == null) {
            return BackupInspectResult.Invalid(error ?: BackupError.INVALID_FILE)
        }
        return BackupInspectResult.Valid(backup.toSummary())
    }

    suspend fun restore(json: String): BackupError? {
        val (backup, error) = parseAndValidateBackup(json)
        if (error != null || backup == null) {
            return error ?: BackupError.INVALID_FILE
        }
        return runCatching {
            withContext(Dispatchers.IO) {
                AppDb.db.importBackup(backup)
            }
            WorkoutSessionRepository.clearInMemorySessionState()
            null
        }.getOrElse {
            BackupError.IMPORT_FAILED
        }
    }
}

@OptIn(ExperimentalTime::class)
internal fun TrainFreeBackup.toSummary(
    zone: TimeZone = TimeZone.currentSystemDefault(),
): BackupSummary = BackupSummary(
    exportedAt = exportedAt,
    exportedEpochDay = Instant.fromEpochMilliseconds(exportedAt).toLocalDateTime(zone).date.toEpochDays(),
    programCount = programs.size,
    routineCount = routines.size,
    workoutCount = workoutSessions.size,
    journalEntryCount = journalEntries.size,
    bodyMetricCount = bodyMetrics.size,
)

internal fun encodeBackupJson(backup: TrainFreeBackup): String =
    backupJson.encodeToString(TrainFreeBackup.serializer(), backup)

internal fun parseAndValidateBackup(json: String): Pair<TrainFreeBackup?, BackupError?> {
    if (json.isBlank()) return null to BackupError.INVALID_FILE
    val backup = try {
        backupJson.decodeFromString(TrainFreeBackup.serializer(), json)
    } catch (_: SerializationException) {
        return null to BackupError.INVALID_FILE
    } catch (_: IllegalArgumentException) {
        return null to BackupError.INVALID_FILE
    }

    if (backup.formatVersion < 1 || backup.formatVersion > BACKUP_FORMAT_VERSION) {
        return null to BackupError.UNSUPPORTED_VERSION
    }
    if (backup.profile == null) {
        return null to BackupError.MISSING_PROFILE
    }
    if (!hasValidReferences(backup)) {
        return null to BackupError.INVALID_FILE
    }
    return backup to null
}

private fun hasValidReferences(backup: TrainFreeBackup): Boolean {
    val exerciseIds = backup.exercises.map { it.id }.toSet()
    if (exerciseIds.size != backup.exercises.size) return false
    if (backup.exercises.any { it.name.isBlank() }) return false
    if (backup.exercises.map { it.name }.distinct().size != backup.exercises.size) return false

    if (backup.settings.map { it.key }.distinct().size != backup.settings.size) return false

    val programIds = backup.programs.map { it.id }.toSet()
    if (programIds.size != backup.programs.size) return false

    val routineIds = backup.routines.map { it.id }.toSet()
    if (routineIds.size != backup.routines.size) return false
    if (backup.routines.any { it.programId !in programIds }) return false
    if (backup.routines.map { it.programId to it.position }.distinct().size != backup.routines.size) return false

    val workSetIds = backup.workSets.map { it.id }.toSet()
    if (workSetIds.size != backup.workSets.size) return false
    if (backup.workSets.any { it.routineId !in routineIds || it.exerciseId !in exerciseIds }) return false
    if (backup.workSets.map { it.routineId to it.position }.distinct().size != backup.workSets.size) return false

    val workSetDetailIds = backup.workSetDetails.map { it.id }.toSet()
    if (workSetDetailIds.size != backup.workSetDetails.size) return false
    if (backup.workSetDetails.any { it.workSetId !in workSetIds }) return false
    if (backup.workSetDetails.map { it.workSetId to it.position }.distinct().size != backup.workSetDetails.size) {
        return false
    }

    val sessionIds = backup.workoutSessions.map { it.id }.toSet()
    if (sessionIds.size != backup.workoutSessions.size) return false
    if (backup.workoutSessions.any { it.routineId != null && it.routineId !in routineIds }) return false

    val sessionDetailIds = backup.workoutSessionDetails.map { it.id }.toSet()
    if (sessionDetailIds.size != backup.workoutSessionDetails.size) return false
    if (backup.workoutSessionDetails.any {
            it.workoutSessionId !in sessionIds ||
                it.exerciseId !in exerciseIds ||
                (it.workSetDetailId != null && it.workSetDetailId !in workSetDetailIds)
        }
    ) {
        return false
    }
    if (backup.workoutSessionDetails.map { it.workoutSessionId to it.position }.distinct().size !=
        backup.workoutSessionDetails.size
    ) {
        return false
    }

    if (backup.journalEntries.map { it.epochDay }.distinct().size != backup.journalEntries.size) return false
    return true
}

internal fun List<UserSettingEntity>.toBackupUserSettings(): List<BackupUserSetting> =
    filterNot { it.key == SETTING_AI_CONTENT_REPORTS }
        .map {
            BackupUserSetting(
                key = it.key,
                value = it.value,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
            )
        }

internal suspend fun AppDatabase.exportBackup(exportedAt: Long = nowMillis()): TrainFreeBackup {
    val profile = profileDao().get()?.let {
        BackupProfile(
            age = it.age,
            gender = normalizeGender(it.gender),
            weight = it.weight,
            height = it.height,
            createdAt = it.createdAt,
            updatedAt = it.updatedAt,
        )
    }
    val settings = userSettingDao().getAll().toBackupUserSettings()
    val exercises = exerciseDao().getAll().map {
        BackupExercise(
            id = it.id,
            name = it.name,
            exerciseType = it.exerciseType,
            bodyPart = it.bodyPart,
            otherBodyParts = it.otherBodyParts,
            equipment = it.equipment,
            builtin = it.builtin,
            createdAt = it.createdAt,
            updatedAt = it.updatedAt,
        )
    }
    val programs = programDao().getAll().map {
        BackupProgram(
            id = it.id,
            name = it.name,
            createdAt = it.createdAt,
            updatedAt = it.updatedAt,
        )
    }
    val routines = routineDao().getAll().map {
        BackupRoutine(
            id = it.id,
            programId = it.programId,
            name = it.name,
            position = it.position,
            createdAt = it.createdAt,
            updatedAt = it.updatedAt,
        )
    }
    val workSets = workSetDao().getAll().map {
        BackupWorkSet(
            id = it.id,
            routineId = it.routineId,
            exerciseId = it.exerciseId,
            position = it.position,
            createdAt = it.createdAt,
            updatedAt = it.updatedAt,
        )
    }
    val workSetDetails = workSetDetailDao().getAll().map {
        BackupWorkSetDetail(
            id = it.id,
            workSetId = it.workSetId,
            position = it.position,
            targetWeight = it.targetWeight,
            targetReps = it.targetReps,
            targetDuration = it.targetDuration,
            targetDistance = it.targetDistance,
            setType = it.setType.key,
            weightUnit = it.weightUnit.value,
            createdAt = it.createdAt,
            updatedAt = it.updatedAt,
        )
    }
    val workoutSessions = workoutSessionDao().getAll().map {
        BackupWorkoutSession(
            id = it.id,
            routineId = it.routineId,
            performedAt = it.performedAt,
            completedAt = it.completedAt,
            title = it.title,
            testData = it.testData,
            createdAt = it.createdAt,
            updatedAt = it.updatedAt,
        )
    }
    val workoutSessionDetails = workoutSessionDetailDao().getAll().map {
        BackupWorkoutSessionDetail(
            id = it.id,
            workoutSessionId = it.workoutSessionId,
            workSetDetailId = it.workSetDetailId,
            exerciseId = it.exerciseId,
            position = it.position,
            targetWeight = it.targetWeight,
            targetReps = it.targetReps,
            targetDuration = it.targetDuration,
            targetDistance = it.targetDistance,
            actualWeight = it.actualWeight,
            actualReps = it.actualReps,
            actualDuration = it.actualDuration,
            actualDistance = it.actualDistance,
            setType = it.setType.key,
            weightUnit = it.weightUnit.value,
            completed = it.completed,
            createdAt = it.createdAt,
            updatedAt = it.updatedAt,
        )
    }
    val bodyMetrics = bodyMetricDao().getAll().map {
        BackupBodyMetric(
            weight = it.weight,
            weightUnit = it.weightUnit.value,
            loggedAt = it.loggedAt,
            testData = it.testData,
            createdAt = it.createdAt,
            updatedAt = it.updatedAt,
        )
    }
    val journalEntries = journalEntryDao().getAll().map {
        BackupJournalEntry(
            epochDay = it.epochDay,
            content = it.content,
            createdAt = it.createdAt,
            updatedAt = it.updatedAt,
        )
    }

    return TrainFreeBackup(
        formatVersion = BACKUP_FORMAT_VERSION,
        exportedAt = exportedAt,
        appSchemaVersion = BACKUP_APP_SCHEMA_VERSION,
        profile = profile,
        settings = settings,
        exercises = exercises,
        programs = programs,
        routines = routines,
        workSets = workSets,
        workSetDetails = workSetDetails,
        workoutSessions = workoutSessions,
        workoutSessionDetails = workoutSessionDetails,
        bodyMetrics = bodyMetrics,
        journalEntries = journalEntries,
    )
}

internal suspend fun AppDatabase.importBackup(backup: TrainFreeBackup) {
    val profile = requireNotNull(backup.profile) { "Backup profile is required" }

    useWriterConnection { transactor ->
        transactor.immediateTransaction {
            // Clear user tables before deleting custom exercises because work_sets
            // and workout_session_details reference exercises with NO ACTION.
            workoutSessionDao().deleteAll()
            programDao().deleteAll()
            bodyMetricDao().deleteAll()
            journalEntryDao().deleteAll()
            userSettingDao().deleteAll()
            aiInsightCacheDao().clearAll()
            profileDao().deleteAll()
            exerciseDao().deleteCustomExercises()

            profileDao().upsert(
                ProfileEntity(
                    id = PROFILE_ID,
                    age = profile.age,
                    gender = normalizeGender(profile.gender),
                    weight = profile.weight,
                    height = profile.height,
                    createdAt = profile.createdAt,
                    updatedAt = profile.updatedAt,
                ),
            )

            val existingExercisesByName = exerciseDao().getAll().associateBy { it.name }.toMutableMap()
            val exerciseIdMap = mutableMapOf<Long, Long>()
            for (exercise in backup.exercises) {
                val existing = existingExercisesByName[exercise.name]
                if (existing != null) {
                    exerciseIdMap[exercise.id] = existing.id
                } else {
                    val insertedId = exerciseDao().insert(
                        ExerciseEntity(
                            name = exercise.name,
                            exerciseType = exercise.exerciseType,
                            bodyPart = exercise.bodyPart,
                            otherBodyParts = exercise.otherBodyParts,
                            equipment = exercise.equipment,
                            builtin = false,
                            createdAt = exercise.createdAt,
                            updatedAt = exercise.updatedAt,
                        ),
                    )
                    exerciseIdMap[exercise.id] = insertedId
                }
            }

            val programIdMap = mutableMapOf<Long, Long>()
            for (program in backup.programs) {
                programIdMap[program.id] = programDao().insert(
                    ProgramEntity(
                        profileId = PROFILE_ID,
                        name = program.name,
                        createdAt = program.createdAt,
                        updatedAt = program.updatedAt,
                    ),
                )
            }

            val routineIdMap = mutableMapOf<Long, Long>()
            for (routine in backup.routines) {
                routineIdMap[routine.id] = routineDao().insert(
                    RoutineEntity(
                        programId = programIdMap.getValue(routine.programId),
                        name = routine.name,
                        position = routine.position,
                        createdAt = routine.createdAt,
                        updatedAt = routine.updatedAt,
                    ),
                )
            }

            val workSetIdMap = mutableMapOf<Long, Long>()
            for (workSet in backup.workSets) {
                workSetIdMap[workSet.id] = workSetDao().insert(
                    WorkSetEntity(
                        routineId = routineIdMap.getValue(workSet.routineId),
                        exerciseId = exerciseIdMap.getValue(workSet.exerciseId),
                        position = workSet.position,
                        createdAt = workSet.createdAt,
                        updatedAt = workSet.updatedAt,
                    ),
                )
            }

            val workSetDetailIdMap = mutableMapOf<Long, Long>()
            for (detail in backup.workSetDetails) {
                workSetDetailIdMap[detail.id] = workSetDetailDao().insert(
                    WorkSetDetailEntity(
                        workSetId = workSetIdMap.getValue(detail.workSetId),
                        position = detail.position,
                        targetWeight = detail.targetWeight,
                        targetReps = detail.targetReps,
                        targetDuration = detail.targetDuration,
                        targetDistance = detail.targetDistance,
                        setType = SetType.fromKey(detail.setType),
                        weightUnit = WeightUnit.fromValue(detail.weightUnit),
                        createdAt = detail.createdAt,
                        updatedAt = detail.updatedAt,
                    ),
                )
            }

            val sessionIdMap = mutableMapOf<Long, Long>()
            for (session in backup.workoutSessions) {
                sessionIdMap[session.id] = workoutSessionDao().insert(
                    WorkoutSessionEntity(
                        profileId = PROFILE_ID,
                        routineId = session.routineId?.let { routineIdMap.getValue(it) },
                        performedAt = session.performedAt,
                        completedAt = session.completedAt,
                        title = session.title,
                        testData = session.testData,
                        createdAt = session.createdAt,
                        updatedAt = session.updatedAt,
                    ),
                )
            }

            for (detail in backup.workoutSessionDetails) {
                workoutSessionDetailDao().insert(
                    WorkoutSessionDetailEntity(
                        workoutSessionId = sessionIdMap.getValue(detail.workoutSessionId),
                        workSetDetailId = detail.workSetDetailId?.let { workSetDetailIdMap.getValue(it) },
                        exerciseId = exerciseIdMap.getValue(detail.exerciseId),
                        position = detail.position,
                        targetWeight = detail.targetWeight,
                        targetReps = detail.targetReps,
                        targetDuration = detail.targetDuration,
                        targetDistance = detail.targetDistance,
                        actualWeight = detail.actualWeight,
                        actualReps = detail.actualReps,
                        actualDuration = detail.actualDuration,
                        actualDistance = detail.actualDistance,
                        setType = SetType.fromKey(detail.setType),
                        weightUnit = WeightUnit.fromValue(detail.weightUnit),
                        completed = detail.completed,
                        createdAt = detail.createdAt,
                        updatedAt = detail.updatedAt,
                    ),
                )
            }

            for (metric in backup.bodyMetrics) {
                bodyMetricDao().insert(
                    BodyMetricEntity(
                        profileId = PROFILE_ID,
                        weight = metric.weight,
                        weightUnit = WeightUnit.fromValue(metric.weightUnit),
                        loggedAt = metric.loggedAt,
                        testData = metric.testData,
                        createdAt = metric.createdAt,
                        updatedAt = metric.updatedAt,
                    ),
                )
            }

            for (entry in backup.journalEntries) {
                journalEntryDao().insert(
                    JournalEntryEntity(
                        profileId = PROFILE_ID,
                        epochDay = entry.epochDay,
                        content = entry.content,
                        createdAt = entry.createdAt,
                        updatedAt = entry.updatedAt,
                    ),
                )
            }

            for (setting in backup.settings) {
                val value = if (setting.key == SETTING_BARBELL_BAR_WEIGHTS) {
                    val remapped = decodeBarWeights(setting.value)
                        .mapNotNull { (key, weight) ->
                            val mappedId = exerciseIdMap[key.exerciseId] ?: return@mapNotNull null
                            BarWeightKey(mappedId, key.unit) to weight
                        }
                        .toMap()
                    if (remapped.isEmpty()) continue
                    encodeBarWeights(remapped)
                } else {
                    setting.value
                }
                userSettingDao().upsert(
                    UserSettingEntity(
                        key = setting.key,
                        value = value,
                        createdAt = setting.createdAt,
                        updatedAt = setting.updatedAt,
                    ),
                )
            }
        }
    }
}
