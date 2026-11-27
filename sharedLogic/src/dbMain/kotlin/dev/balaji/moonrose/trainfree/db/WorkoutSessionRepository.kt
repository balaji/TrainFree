package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.insights.InsightMath
import dev.balaji.moonrose.trainfree.insights.OneRmFormula
import dev.balaji.moonrose.trainfree.model.ExerciseType
import dev.balaji.moonrose.trainfree.model.NewOneRepMaxAchievement
import dev.balaji.moonrose.trainfree.model.SetType
import dev.balaji.moonrose.trainfree.model.WeightUnit
import dev.balaji.moonrose.trainfree.model.WorkoutCompletionCelebration
import dev.balaji.moonrose.trainfree.model.buildWorkoutCompletionCelebration
import dev.balaji.moonrose.trainfree.model.isSetComplete
import kotlin.math.round
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Minimal snapshot of the in-progress session, for chrome that lives outside the session screen itself. */
data class ActiveWorkoutSession(
    val sessionId: Long,
    val routineId: Long,
    val title: String,
    val startedAt: Long,
)

internal fun detectNewOneRepMaxes(
    keptDetails: List<WorkoutSessionDetailEntity>,
    priorSets: List<ExerciseSetLoadRow>,
    exerciseNames: Map<Long, String>,
    formula: OneRmFormula,
    displayUnit: WeightUnit,
): List<NewOneRepMaxAchievement> {
    val priorBestByExercise = priorSets
        .filter { it.actualWeight > 0.0 && it.actualReps > 0 }
        .groupBy { it.exerciseId }
        .mapValues { (_, sets) ->
            sets.maxOf { row ->
                val weight = row.weightUnit.convertWeight(row.actualWeight, displayUnit)
                formula.estimate(weight, row.actualReps)
            }
        }
    if (priorBestByExercise.isEmpty()) return emptyList()

    val currentByExercise = keptDetails
        .filter { detail ->
            detail.setType == SetType.NORMAL &&
                (detail.actualWeight ?: 0.0) > 0.0 &&
                (detail.actualReps ?: 0) > 0
        }
        .groupBy { it.exerciseId }

    val achievements = mutableListOf<NewOneRepMaxAchievement>()
    for ((exerciseId, sets) in currentByExercise) {
        val priorBest = priorBestByExercise[exerciseId] ?: continue
        val exerciseName = exerciseNames[exerciseId]?.takeIf { it.isNotBlank() } ?: continue
        val currentBest = sets.maxOf { detail ->
            val weight = detail.weightUnit.convertWeight(detail.actualWeight!!, displayUnit)
            formula.estimate(weight, detail.actualReps!!)
        }
        val currentTenths = round(currentBest * 10.0).toLong()
        val priorTenths = round(priorBest * 10.0).toLong()
        if (currentTenths > priorTenths) {
            val gainDisplay = (currentTenths - priorTenths) / 10.0
            achievements += NewOneRepMaxAchievement(
                exerciseId = exerciseId,
                exerciseName = exerciseName,
                formattedNewOneRm = InsightMath.formatNumber(currentBest, 1),
                formattedGain = InsightMath.formatNumber(gainDisplay, 1),
                newOneRm = currentBest,
                previousOneRm = priorBest,
            )
        }
    }
    return achievements
}

internal fun summarizeCompletedSession(
    sessionId: Long,
    workoutTitle: String,
    performedAtMillis: Long,
    completedAtMillis: Long,
    keptDetails: List<WorkoutSessionDetailEntity>,
    newOneRepMaxes: List<NewOneRepMaxAchievement> = emptyList(),
    weightUnit: WeightUnit = WeightUnit.METRIC,
): WorkoutCompletionCelebration {
    val nonWarmupDetails = keptDetails.filter { it.setType != SetType.WARMUP }
    val normalDetails = nonWarmupDetails.filter { it.setType == SetType.NORMAL }
    val setCount = when {
        normalDetails.isNotEmpty() -> normalDetails.size
        nonWarmupDetails.isNotEmpty() -> nonWarmupDetails.size
        else -> keptDetails.size
    }
    val exerciseSource = nonWarmupDetails.ifEmpty { keptDetails }
    val durationSeconds = ((completedAtMillis - performedAtMillis) / 1000L).coerceAtLeast(0L)
    return buildWorkoutCompletionCelebration(
        sessionId = sessionId,
        workoutTitle = workoutTitle,
        completedSets = setCount,
        exerciseCount = exerciseSource.map { it.exerciseId }.distinct().size,
        durationSeconds = durationSeconds,
        seed = completedAtMillis,
        newOneRepMaxes = newOneRepMaxes,
        weightUnit = weightUnit,
    )
}

object WorkoutSessionRepository {
    private val repositoryScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _activeRestTimer = MutableStateFlow<RestTimerTarget?>(null)
    /**
     * Tracks the active 2-minute rest timer across the entire app so navigating
     * away from the workout screen (to view other tabs/pages) neither drops the
     * countdown nor misses its completion alert.
     */
    val activeRestTimer: StateFlow<RestTimerTarget?> = _activeRestTimer.asStateFlow()

    private val _completionCelebration = MutableStateFlow<WorkoutCompletionCelebration?>(null)
    /** Emitted when a live workout session finishes; cleared once dismissed or a new session starts. */
    val completionCelebration: StateFlow<WorkoutCompletionCelebration?> = _completionCelebration.asStateFlow()

    fun dismissCompletionCelebration() {
        _completionCelebration.value = null
    }

    private var cachedFormState: WorkoutSessionFormState? = null

    /**
     * Returns the shared [WorkoutSessionFormState] for [sessionId], reusing it
     * across back/forward navigation so in-memory state (including the active
     * rest timer) survives leaving and re-entering the workout screen.
     */
    fun formState(sessionId: Long): WorkoutSessionFormState {
        val existing = cachedFormState
        if (existing != null && existing.sessionId == sessionId && !existing.isClosed) {
            return existing
        }
        existing?.closeQuietly()
        return WorkoutSessionFormState(sessionId).also { cachedFormState = it }
    }

    /** Fresh in-memory form state for logging a completed workout on [epochDay] without timers. */
    fun newCompletedLogFormState(epochDay: Long): WorkoutSessionFormState =
        WorkoutSessionFormState(WorkoutSessionMode.LogCompleted(epochDay))

    /** Fresh in-memory form state for editing an existing completed [sessionId] without timers. */
    fun editCompletedFormState(sessionId: Long): WorkoutSessionFormState =
        WorkoutSessionFormState(WorkoutSessionMode.EditCompleted(sessionId))

    internal fun updateActiveRestTimer(timer: RestTimerTarget?) {
        _activeRestTimer.value = timer
    }

    /** Clears the rest timer once its countdown finishes. */
    fun clearActiveRestTimer() {
        _activeRestTimer.value = null
        cachedFormState?.clearRestTimer()
    }

    internal fun evictFormState(sessionId: Long) {
        if (cachedFormState?.sessionId == sessionId) {
            cachedFormState = null
        }
    }

    internal fun clearInMemorySessionState() {
        cachedFormState?.closeQuietly()
        cachedFormState = null
        _activeRestTimer.value = null
        _completionCelebration.value = null
    }

    /**
     * Tracks whichever session is currently in progress, so a persistent mini
     * bar (bottom overlay on Android/Desktop, safe-area inset on iOS) can be
     * shown while the user navigates away from the session screen. Backed by
     * Room's `observeInProgress` query, so it updates automatically on
     * start/finish/discard without any manual bookkeeping here.
     */
    val activeSession: StateFlow<ActiveWorkoutSession?> =
        AppDb.db.workoutSessionDao().observeInProgress(PROFILE_ID)
            .map { entity ->
                entity?.let {
                    val routineId = it.routineId ?: 0L
                    val routineName = if (routineId > 0L) {
                        AppDb.db.routineDao().findById(routineId)?.name
                    } else {
                        null
                    }
                    val title = resolveWorkoutTitle(it.title, routineName, it.performedAt)
                    ActiveWorkoutSession(
                        sessionId = it.id,
                        routineId = routineId,
                        title = title,
                        startedAt = it.performedAt,
                    )
                }
            }
            .stateIn(repositoryScope, SharingStarted.Eagerly, null)

    /**
     * Creates a new session snapshotting every set target from the routine. The
     * flat `position` ordering matches moonrose: sets belonging to the same
     * exercise are always adjacent in position order.
     */
    suspend fun startFromRoutine(routineId: Long): Long {
        cachedFormState?.closeQuietly()
        cachedFormState = null
        _activeRestTimer.value = null
        _completionCelebration.value = null

        val now = nowMillis()
        val routineName = AppDb.db.routineDao().findById(routineId)?.name
        val title = resolveWorkoutTitle(storedTitle = null, routineName = routineName, performedAtMillis = now)
        val sessionId = AppDb.db.workoutSessionDao().insert(
            WorkoutSessionEntity(
                profileId = PROFILE_ID,
                routineId = routineId,
                performedAt = now,
                completedAt = null,
                title = title,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val workSets = AppDb.db.workSetDao().getForRoutine(routineId)
        val currentUnit = UserSettingsRepository.settings.first().weightUnit
        var position = 1
        for (ws in workSets) {
            val details = AppDb.db.workSetDetailDao().getForWorkSet(ws.id)
            if (details.isEmpty()) {
                // An exercise with no set details still deserves at least one
                // logging row so the user has something to fill in.
                AppDb.db.workoutSessionDetailDao().insert(
                    WorkoutSessionDetailEntity(
                        workoutSessionId = sessionId,
                        workSetDetailId = null,
                        exerciseId = ws.exerciseId,
                        position = position++,
                        targetWeight = null,
                        targetReps = null,
                        targetDuration = null,
                        targetDistance = null,
                        actualWeight = null,
                        actualReps = null,
                        actualDuration = null,
                        actualDistance = null,
                        weightUnit = currentUnit,
                        completed = false,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } else {
                for (detail in details) {
                    AppDb.db.workoutSessionDetailDao().insert(
                        WorkoutSessionDetailEntity(
                            workoutSessionId = sessionId,
                            workSetDetailId = detail.id,
                            exerciseId = ws.exerciseId,
                            position = position++,
                            targetWeight = detail.targetWeight,
                            targetReps = detail.targetReps,
                            targetDuration = detail.targetDuration,
                            targetDistance = detail.targetDistance,
                            actualWeight = null,
                            actualReps = null,
                            actualDuration = null,
                            actualDistance = null,
                            setType = detail.setType,
                            weightUnit = detail.weightUnit,
                            completed = false,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                }
            }
        }
        return sessionId
    }

    suspend fun startEmpty(): Long {
        cachedFormState?.closeQuietly()
        cachedFormState = null
        _activeRestTimer.value = null
        _completionCelebration.value = null

        val now = nowMillis()
        return AppDb.db.workoutSessionDao().insert(
            WorkoutSessionEntity(
                profileId = PROFILE_ID,
                routineId = null,
                performedAt = now,
                completedAt = null,
                title = defaultWorkoutTitleForMillis(now),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /**
     * Resumes an in-progress session only when its context matches the caller's
     * (same routine, or both empty). Otherwise starts fresh, so tapping "Start
     * Workout" on a routine always populates the routine's exercises even if
     * an unrelated in-progress session exists.
     */
    suspend fun resumeOrStart(routineId: Long): Long {
        val existing = AppDb.db.workoutSessionDao().findInProgress(PROFILE_ID)
        if (existing != null && (existing.routineId ?: 0L) == routineId) return existing.id
        return if (routineId > 0L) startFromRoutine(routineId) else startEmpty()
    }

    /**
     * Whether the persisted session has anything worth saving. Chrome outside
     * the session screen (the mini bar) uses this to decide between finishing
     * and offering to discard; the screen itself checks its own form state.
     */
    suspend fun hasCompletedSets(sessionId: Long): Boolean {
        val cached = cachedFormState?.takeIf { it.sessionId == sessionId && !it.uiState.value.loading }
        if (cached != null) return cached.uiState.value.hasCompletedSets
        return AppDb.db.workoutSessionDetailDao().hasCompletedSets(sessionId)
    }

    /**
     * Sets missing a value their exercise type requires (reps, duration or
     * distance) are dropped so the completed workout only records work that
     * was actually performed. Pruning here (rather than in the form) keeps
     * the session-screen finish and the mini-bar finish consistent.
     */
    suspend fun finish(sessionId: Long) {
        _activeRestTimer.value = null
        val cached = cachedFormState?.takeIf { it.sessionId == sessionId }
        if (cached != null) {
            cached.flushBeforeExternalFinish()
            cachedFormState = null
        }
        finishPersisted(sessionId)
    }

    internal suspend fun finishPersisted(sessionId: Long) {
        val now = nowMillis()
        val details = AppDb.db.workoutSessionDetailDao().getForSession(sessionId)
        val exercisesById = details.map { it.exerciseId }.distinct().associateWith { id ->
            ExerciseRepository.findById(id)
        }
        val typeByExercise = exercisesById.mapValues { (_, exercise) ->
            ExerciseType.fromValue(exercise?.exerciseType) ?: ExerciseType.STRENGTH
        }
        val (keptDetails, emptyDetails) = details.partition { d ->
            val type = typeByExercise.getValue(d.exerciseId)
            d.completed && type.isSetComplete(d.actualReps, d.actualDuration, d.actualDistance)
        }
        if (emptyDetails.isNotEmpty()) {
            AppDb.db.workoutSessionDetailDao().deleteByIds(emptyDetails.map { it.id })
        }
        AppDb.db.workoutSessionDao().setCompletedAt(sessionId, now, now)
        HealthSyncManager.onWorkoutFinished(sessionId)
        GoogleDriveBackupManager.onWorkoutOrJournalChanged()
        val session = AppDb.db.workoutSessionDao().findById(sessionId)
        if (session != null && keptDetails.isNotEmpty()) {
            val routineName = session.routineId?.let { AppDb.db.routineDao().findById(it)?.name }
            val title = resolveWorkoutTitle(session.title, routineName, session.performedAt)
            val settings = UserSettingsRepository.settings.first()
            val candidateExerciseIds = keptDetails
                .filter { it.setType == SetType.NORMAL && (it.actualWeight ?: 0.0) > 0.0 && (it.actualReps ?: 0) > 0 }
                .map { it.exerciseId }
                .distinct()
            val priorSets = if (candidateExerciseIds.isNotEmpty()) {
                AppDb.db.workoutSessionDetailDao().getPriorWeightedSetsForExercises(
                    profileId = PROFILE_ID,
                    excludeSessionId = sessionId,
                    exerciseIds = candidateExerciseIds,
                )
            } else {
                emptyList()
            }
            val exerciseNames = exercisesById.mapNotNull { (id, ex) -> ex?.let { id to it.name } }.toMap()
            val newOneRepMaxes = detectNewOneRepMaxes(
                keptDetails = keptDetails,
                priorSets = priorSets,
                exerciseNames = exerciseNames,
                formula = settings.oneRmFormula,
                displayUnit = settings.weightUnit,
            )
            _completionCelebration.value = summarizeCompletedSession(
                sessionId = sessionId,
                workoutTitle = title,
                performedAtMillis = session.performedAt,
                completedAtMillis = now,
                keptDetails = keptDetails,
                newOneRepMaxes = newOneRepMaxes,
                weightUnit = settings.weightUnit,
            )
        }
    }

    /** Cascades to `workout_session_details` via the FK. */
    suspend fun discard(sessionId: Long) {
        _activeRestTimer.value = null
        val cached = cachedFormState?.takeIf { it.sessionId == sessionId }
        if (cached != null) {
            cached.closeQuietly()
            cachedFormState = null
        }
        AppDb.db.workoutSessionDao().delete(sessionId)
    }

    /** Deletes a completed historical workout without disturbing any active live session/timer. */
    suspend fun deleteCompletedSession(sessionId: Long) {
        AppDb.db.workoutSessionDao().delete(sessionId)
        HealthSyncManager.onWorkoutDeleted(sessionId)
        GoogleDriveBackupManager.onWorkoutOrJournalChanged()
    }

    /** Renames an existing workout session (used by the Journal day screen). */
    suspend fun renameSession(sessionId: Long, title: String) {
        val session = AppDb.db.workoutSessionDao().findById(sessionId) ?: return
        val routineName = session.routineId?.let { AppDb.db.routineDao().findById(it)?.name }
        val resolved = resolveWorkoutTitle(
            storedTitle = filterWorkoutTitle(title),
            routineName = routineName,
            performedAtMillis = session.performedAt,
        )
        AppDb.db.workoutSessionDao().updateTitle(sessionId, resolved, nowMillis())
        GoogleDriveBackupManager.onWorkoutOrJournalChanged()
    }
}
