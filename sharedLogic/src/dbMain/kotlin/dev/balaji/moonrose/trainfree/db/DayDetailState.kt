package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.calendar.WeekMath
import dev.balaji.moonrose.trainfree.model.SetType
import dev.balaji.moonrose.trainfree.model.WeightUnit
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

data class DaySessionSet(
    val targetWeight: String,
    val targetReps: String,
    // "1:05"-style hh:mm, blank when the set has no duration.
    val targetDuration: String,
    val targetDistance: String,
    val actualWeight: String,
    val actualReps: String,
    val actualDuration: String,
    val actualDistance: String,
    val setType: SetType,
    val completed: Boolean,
)

data class DaySessionExercise(
    val name: String,
    val sets: List<DaySessionSet>,
)

data class DaySession(
    val id: Long,
    val title: String,
    val exercises: List<DaySessionExercise>,
)

data class DayDetailUiState(
    val loading: Boolean = true,
    val sessions: List<DaySession> = emptyList(),
    val journalContent: String? = null,
    val isFuture: Boolean = false,
    val weightUnit: WeightUnit = WeightUnit.METRIC,
)

class DayDetailState(private val epochDay: Long) {
    /**
     * Cold: each collector observes the database while it is collecting, so
     * the page stays current when a session finishes, is edited/deleted, or a
     * journal entry is saved.
     */
    @OptIn(ExperimentalTime::class)
    val uiState: Flow<DayDetailUiState> = run {
        val zone = TimeZone.currentSystemDefault()
        val startMillis = LocalDate.fromEpochDays(epochDay).atStartOfDayIn(zone).toEpochMilliseconds()
        val endMillis = LocalDate.fromEpochDays(epochDay + 1).atStartOfDayIn(zone).toEpochMilliseconds()
        combine(
            AppDb.db.workoutSessionDao().observeCompletedInRange(PROFILE_ID, startMillis, endMillis),
            AppDb.db.journalEntryDao().observeForDay(PROFILE_ID, epochDay),
            UserSettingsRepository.settings,
        ) { sessions, journal, settings ->
            DayDetailUiState(
                loading = false,
                sessions = sessions.map { toDaySession(it, settings.weightUnit) },
                journalContent = journal?.content?.takeIf { it.isNotBlank() },
                isFuture = epochDay > WeekMath.todayEpochDay(),
                weightUnit = settings.weightUnit,
            )
        }
    }

    suspend fun deleteSession(sessionId: Long) {
        WorkoutSessionRepository.deleteCompletedSession(sessionId)
    }

    suspend fun renameSession(sessionId: Long, title: String) {
        WorkoutSessionRepository.renameSession(sessionId, title)
    }
}

@OptIn(ExperimentalTime::class)
internal suspend fun loadDaySessions(
    epochDay: Long,
    displayUnit: WeightUnit,
): List<DaySession> {
    val zone = TimeZone.currentSystemDefault()
    val startMillis = LocalDate.fromEpochDays(epochDay).atStartOfDayIn(zone).toEpochMilliseconds()
    val endMillis = LocalDate.fromEpochDays(epochDay + 1).atStartOfDayIn(zone).toEpochMilliseconds()
    return AppDb.db.workoutSessionDao()
        .getCompletedInRange(PROFILE_ID, startMillis, endMillis)
        .map { toDaySession(it, displayUnit) }
}

internal suspend fun toDaySession(
    session: WorkoutSessionEntity,
    displayUnit: WeightUnit,
): DaySession {
    val routine = session.routineId?.let { AppDb.db.routineDao().findById(it) }
    val details = AppDb.db.workoutSessionDetailDao().getForSession(session.id)
    return DaySession(
        id = session.id,
        title = resolveWorkoutTitle(session.title, routine?.name, session.performedAt),
        exercises = groupByExercise(details, displayUnit),
    )
}

/** Details arrive in flat position order with sets of the same exercise adjacent. */
private suspend fun groupByExercise(
    details: List<WorkoutSessionDetailEntity>,
    displayUnit: WeightUnit,
): List<DaySessionExercise> {
    val grouped = mutableListOf<DaySessionExercise>()
    var currentExerciseId = -1L
    var currentName = ""
    var currentSets = mutableListOf<DaySessionSet>()
    fun flush() {
        if (currentSets.isNotEmpty()) grouped.add(DaySessionExercise(currentName, currentSets.toList()))
    }
    for (detail in details) {
        if (detail.exerciseId != currentExerciseId) {
            flush()
            currentExerciseId = detail.exerciseId
            currentName = ExerciseRepository.findById(detail.exerciseId)?.name.orEmpty()
            currentSets = mutableListOf()
        }
        currentSets.add(
            DaySessionSet(
                targetWeight = detail.targetWeight?.let { WeightUnit.formatWeight(it, detail.weightUnit, displayUnit) }.orEmpty(),
                targetReps = detail.targetReps?.toString().orEmpty(),
                targetDuration = detail.targetDuration?.let(::formatDurationHhMm).orEmpty(),
                targetDistance = detail.targetDistance?.let { WeightUnit.formatDistance(it, detail.weightUnit, displayUnit) }.orEmpty(),
                actualWeight = detail.actualWeight?.let { WeightUnit.formatWeight(it, detail.weightUnit, displayUnit) }.orEmpty(),
                actualReps = detail.actualReps?.toString().orEmpty(),
                actualDuration = detail.actualDuration?.let(::formatDurationHhMm).orEmpty(),
                actualDistance = detail.actualDistance?.let { WeightUnit.formatDistance(it, detail.weightUnit, displayUnit) }.orEmpty(),
                setType = detail.setType,
                completed = detail.completed,
            ),
        )
    }
    flush()
    return grouped
}
