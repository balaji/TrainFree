package dev.balaji.moonrose.trainfree.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import dev.balaji.moonrose.trainfree.calendar.WeekMath
import dev.balaji.moonrose.trainfree.model.Exercise
import dev.balaji.moonrose.trainfree.model.ExerciseType
import dev.balaji.moonrose.trainfree.model.SetField
import dev.balaji.moonrose.trainfree.model.SetType
import dev.balaji.moonrose.trainfree.model.WeightUnit
import dev.balaji.moonrose.trainfree.model.isSetComplete
import dev.balaji.moonrose.trainfree.model.requiredSetFields
import kotlin.math.floor
import kotlin.math.round
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

data class DraftSessionSet(
    // Snapshots shown as placeholder to the user (never mutated by the UI).
    val targetWeight: String = "",
    val targetReps: String = "",
    // Single h:mm box text, e.g. "1:30" or bare minutes "45".
    val targetDuration: String = "",
    val targetDistance: String = "",
    val actualWeight: String = "",
    val actualReps: String = "",
    val actualDuration: String = "",
    val actualDistance: String = "",
    val setType: SetType = SetType.NORMAL,
    val completed: Boolean = false,
    // Recorded numeric values + units so toggling units mid-workout converts
    // display strings uniformly without losing precision on untouched sets.
    internal val rawTargetWeight: Double? = null,
    internal val rawTargetDistance: Double? = null,
    internal val targetUnit: WeightUnit = WeightUnit.METRIC,
    internal val rawActualWeight: Double? = null,
    internal val rawActualDistance: Double? = null,
    internal val actualUnit: WeightUnit = WeightUnit.METRIC,
)

internal fun DraftSessionSet.formattedFor(unit: WeightUnit): DraftSessionSet = copy(
    targetWeight = rawTargetWeight?.let { WeightUnit.formatWeight(it, targetUnit, unit) } ?: targetWeight,
    targetDistance = rawTargetDistance?.let { WeightUnit.formatDistance(it, targetUnit, unit) } ?: targetDistance,
    actualWeight = rawActualWeight?.let { WeightUnit.formatWeight(it, actualUnit, unit) } ?: actualWeight,
    actualDistance = rawActualDistance?.let { WeightUnit.formatDistance(it, actualUnit, unit) } ?: actualDistance,
)

/** Whether the logged values satisfy every field [type] requires. */
private fun DraftSessionSet.meetsRequirements(type: ExerciseType): Boolean =
    type.isSetComplete(
        reps = actualReps.toIntOrNull(),
        durationSeconds = parseDurationSeconds(actualDuration),
        distance = rawActualDistance ?: actualDistance.toDoubleOrNull(),
    )

data class DraftSessionExercise(
    val id: Long,
    val exercise: Exercise,
    val sets: List<DraftSessionSet> = listOf(DraftSessionSet()),
    /**
     * What the user logged the last time they did this exercise (completed
     * working sets only, warm-ups excluded), formatted ("50kg x 5") in order.
     * Use [previousSet] to look up the label for a row by index.
     */
    val previousSets: List<String> = emptyList(),
    internal val previousSetRows: List<PreviousSetRow> = emptyList(),
) {
    /**
     * Whether weight cells offer the plate keyboard. Barbell only for now —
     * deliberately excludes "smith machine", whose bars vary too much.
     */
    val usesPlateKeyboard: Boolean get() = exercise.equipment == "barbell"

    /** Which measured fields this exercise's sets take; unrecognized types fall back to strength (reps). */
    val type: ExerciseType get() = ExerciseType.fromValue(exercise.exerciseType) ?: ExerciseType.STRENGTH

    /**
     * Whether the set at [setIndex] holds everything [type] requires and may
     * be marked completed. Shared by the Compose and SwiftUI checkboxes so
     * the gating rule lives in one place.
     */
    fun isSetReadyToComplete(setIndex: Int): Boolean =
        sets.getOrNull(setIndex)?.meetsRequirements(type) ?: false

    /**
     * 1-based working-set number for the set at [setIndex] (counting normal sets
     * up to and including [setIndex]). Warm-up and drop sets display a letter
     * marker instead of this number.
     */
    fun workingSetNumber(setIndex: Int): Int {
        if (setIndex !in sets.indices) return 0
        return sets.take(setIndex + 1).count { it.setType == SetType.NORMAL }
    }

    /**
     * Formatted previous-session performance for the set at [setIndex], or null
     * when the placeholder ("—") should be shown. Warm-up sets never show a
     * previous value; normal and drop sets match against prior sets of the same
     * [SetType] by their 1-based ordinal within that type so drop sets and
     * interleaved sets align cleanly without shifting or colliding.
     */
    fun previousSet(setIndex: Int): String? {
        val set = sets.getOrNull(setIndex) ?: return null
        if (set.setType == SetType.WARMUP) return null
        val ordinal = sets.take(setIndex + 1).count { it.setType == set.setType } - 1
        if (previousSetRows.size == previousSets.size && previousSetRows.isNotEmpty()) {
            var seen = 0
            for (i in previousSetRows.indices) {
                if (previousSetRows[i].setType == set.setType) {
                    if (seen == ordinal) return previousSets[i].takeIf { it.isNotBlank() }
                    seen++
                }
            }
            return null
        }
        return if (set.setType == SetType.NORMAL) {
            previousSets.getOrNull(ordinal)?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    }
}

/**
 * Tracks the rest timer running under the most recently completed set.
 * Only one rest timer runs at a time; checking another set restarts it under
 * the new set, and unchecking or deleting the owning set cancels it.
 */
data class RestTimerTarget(
    val exerciseId: Long,
    val setIndex: Int,
    val startedAtMillis: Long,
    val endsAtMillis: Long,
) {
    /** Total duration of this timer run in seconds, derived from its endpoints. */
    val durationSeconds: Long
        get() = ((endsAtMillis - startedAtMillis) / 1000L).coerceAtLeast(1L)
}

const val REST_TIMER_DURATION_SECONDS = 120L
const val REST_TIMER_DURATION_MS = REST_TIMER_DURATION_SECONDS * 1000L

sealed interface WorkoutSessionMode {
    data class Live(val sessionId: Long) : WorkoutSessionMode
    data class LogCompleted(val epochDay: Long) : WorkoutSessionMode
    data class EditCompleted(val sessionId: Long) : WorkoutSessionMode
}

data class WorkoutSessionUiState(
    val loading: Boolean = true,
    val exercises: List<DraftSessionExercise> = emptyList(),
    val saving: Boolean = false,
    val finished: Boolean = false,
    val discarded: Boolean = false,
    val deleted: Boolean = false,
    // Epoch millis the session was started; 0 while still loading or in timerless log/edit mode.
    val startedAt: Long = 0L,
    val restTimer: RestTimerTarget? = null,
    val isLiveSession: Boolean = true,
    val isEditingExisting: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val sessionTitle: String? = null,
) {
    /** Finishing a live session is only allowed once at least one working set is checked off. */
    val hasCompletedSets: Boolean
        get() = exercises.any { ex -> ex.sets.any { it.completed && it.setType != SetType.WARMUP } }

    /**
     * Saving a timerless completed workout (log or edit) requires at least one
     * working set that holds every field its exercise type requires.
     */
    val hasValidSetsToSave: Boolean
        get() = exercises.any { ex -> ex.sets.any { it.setType != SetType.WARMUP && it.meetsRequirements(ex.type) } }

    val canSaveCompleted: Boolean
        get() = !isLiveSession && !loading && !saving && hasValidSetsToSave && hasUnsavedChanges

    /** Whether the rest timer is currently attached to [exerciseId]'s [setIndex]. */
    fun isRestTimerOn(exerciseId: Long, setIndex: Int): Boolean =
        restTimer?.let { it.exerciseId == exerciseId && it.setIndex == setIndex } == true

    internal fun removeSetAt(exerciseIndex: Int, setIndex: Int): WorkoutSessionUiState {
        val exercise = exercises.getOrNull(exerciseIndex) ?: return this
        if (setIndex !in exercise.sets.indices) return this
        if (exercise.sets.size <= 1) return removeExerciseAt(exerciseIndex)

        val targetExId = exercise.id
        val nextTimer = restTimer?.let { timer ->
            if (timer.exerciseId != targetExId) {
                timer
            } else when {
                timer.setIndex == setIndex -> null
                timer.setIndex > setIndex -> timer.copy(setIndex = timer.setIndex - 1)
                else -> timer
            }
        }
        return copy(
            exercises = exercises.mapIndexed { i, ex ->
                if (i == exerciseIndex) {
                    ex.copy(sets = ex.sets.filterIndexed { idx, _ -> idx != setIndex })
                } else {
                    ex
                }
            },
            restTimer = nextTimer,
        )
    }

    internal fun removeExerciseAt(exerciseIndex: Int): WorkoutSessionUiState {
        val removedId = exercises.getOrNull(exerciseIndex)?.id
        return copy(
            exercises = exercises.filterIndexed { i, _ -> i != exerciseIndex },
            restTimer = restTimer?.takeIf { it.exerciseId != removedId },
        )
    }

    internal fun updateActualFieldAt(
        exerciseIndex: Int,
        setIndex: Int,
        transform: (DraftSessionSet) -> DraftSessionSet,
    ): WorkoutSessionUiState {
        val exercise = exercises.getOrNull(exerciseIndex) ?: return this
        val set = exercise.sets.getOrNull(setIndex) ?: return this
        val updated = transform(set)
        val nowCompleted = updated.completed && updated.meetsRequirements(exercise.type)
        val nextSet = updated.copy(completed = nowCompleted)
        val nextTimer = if (!nowCompleted && isRestTimerOn(exercise.id, setIndex)) {
            null
        } else {
            restTimer
        }
        return copy(
            exercises = exercises.mapIndexed { i, ex ->
                if (i == exerciseIndex) {
                    ex.copy(sets = ex.sets.mapIndexed { sIdx, s -> if (sIdx == setIndex) nextSet else s })
                } else {
                    ex
                }
            },
            restTimer = nextTimer,
        )
    }

    internal fun setSetTypeAt(
        exerciseIndex: Int,
        setIndex: Int,
        setType: SetType,
        displayUnit: WeightUnit = WeightUnit.METRIC,
    ): WorkoutSessionUiState {
        val exercise = exercises.getOrNull(exerciseIndex) ?: return this
        val set = exercise.sets.getOrNull(setIndex) ?: return this
        if (set.setType == setType) return this

        var nextSet = set.copy(setType = setType)
        if (setType == SetType.DROP && set.actualWeight.isBlank() && set.rawActualWeight == null) {
            val prev = exercise.sets.getOrNull(setIndex - 1)?.takeIf { it.setType != SetType.WARMUP }
            val prevWeight = prev?.let { p ->
                (p.rawActualWeight ?: p.actualWeight.toDoubleOrNull())?.let { p.actualUnit.convertWeight(it, displayUnit) }
                    ?: (p.rawTargetWeight ?: p.targetWeight.toDoubleOrNull())?.let { p.targetUnit.convertWeight(it, displayUnit) }
            }
            val suggested = prevWeight?.let { suggestDropSetWeight(it, displayUnit) }
            if (suggested != null) {
                val convertedDistance = if (nextSet.actualUnit != displayUnit) {
                    nextSet.rawActualDistance?.let { nextSet.actualUnit.convertDistance(it, displayUnit) }
                } else {
                    nextSet.rawActualDistance
                }
                nextSet = nextSet.copy(
                    actualWeight = WeightUnit.formatWeight(suggested, displayUnit, displayUnit),
                    rawActualWeight = suggested,
                    rawActualDistance = convertedDistance,
                    actualUnit = displayUnit,
                )
            }
        }
        val nextTimer = if (setType == SetType.DROP && setIndex > 0 && isRestTimerOn(exercise.id, setIndex - 1)) {
            null
        } else {
            restTimer
        }
        return copy(
            exercises = exercises.mapIndexed { i, ex ->
                if (i == exerciseIndex) {
                    ex.copy(sets = ex.sets.mapIndexed { sIdx, s -> if (sIdx == setIndex) nextSet else s })
                } else {
                    ex
                }
            },
            restTimer = nextTimer,
        )
    }

    internal fun toggleCompletedAt(
        exerciseIndex: Int,
        setIndex: Int,
        now: Long = nowMillis(),
        durationSeconds: Long = REST_TIMER_DURATION_SECONDS,
    ): WorkoutSessionUiState {
        val exercise = exercises.getOrNull(exerciseIndex) ?: return this
        val set = exercise.sets.getOrNull(setIndex) ?: return this
        val nowCompleted = !set.completed && set.meetsRequirements(exercise.type)
        val followedByDrop = exercise.sets.getOrNull(setIndex + 1)?.setType == SetType.DROP
        val nextTimer = when {
            nowCompleted && followedByDrop -> null
            nowCompleted -> RestTimerTarget(
                exerciseId = exercise.id,
                setIndex = setIndex,
                startedAtMillis = now,
                endsAtMillis = now + durationSeconds * 1000L,
            )
            isRestTimerOn(exercise.id, setIndex) -> null
            else -> restTimer
        }
        return copy(
            exercises = exercises.mapIndexed { i, ex ->
                if (i == exerciseIndex) {
                    ex.copy(
                        sets = ex.sets.mapIndexed { sIdx, s ->
                            if (sIdx == setIndex) s.copy(completed = nowCompleted) else s
                        },
                    )
                } else {
                    ex
                }
            },
            restTimer = nextTimer,
        )
    }

    internal fun withDisplayUnit(unit: WeightUnit): WorkoutSessionUiState = copy(
        exercises = exercises.map { ex ->
            ex.copy(
                sets = ex.sets.map { it.formattedFor(unit) },
                previousSets = if (ex.previousSetRows.isNotEmpty()) {
                    formatPreviousSets(ex.previousSetRows, ex.exercise, unit)
                } else {
                    ex.previousSets
                },
            )
        },
    )
}

class WorkoutSessionFormState internal constructor(
    private val mode: WorkoutSessionMode,
) {
    constructor(sessionId: Long) : this(WorkoutSessionMode.Live(sessionId))

    internal val sessionId: Long
        get() = when (mode) {
            is WorkoutSessionMode.Live -> mode.sessionId
            is WorkoutSessionMode.EditCompleted -> mode.sessionId
            is WorkoutSessionMode.LogCompleted -> 0L
        }

    private val isLiveSession: Boolean get() = mode is WorkoutSessionMode.Live
    private val isEditingExisting: Boolean get() = mode is WorkoutSessionMode.EditCompleted

    private val _uiState = MutableStateFlow(
        WorkoutSessionUiState(
            isLiveSession = isLiveSession,
            isEditingExisting = isEditingExisting,
        ),
    )
    val uiState: StateFlow<WorkoutSessionUiState> = _uiState.asStateFlow()
    private var nextId = 0L
    private val scope = CoroutineScope(Dispatchers.Main)
    private var restTimerDurationSeconds: Long = REST_TIMER_DURATION_SECONDS
    private var currentWeightUnit: WeightUnit = WeightUnit.METRIC
    private var selectedRoutineId: Long? = null
    private var defaultTitle: String = ""
    private var titleEditedByUser: Boolean = false
    private var initialSnapshot: FormSnapshot? = null

    // Serializes persist() runs so a debounced autosave and an explicit
    // save/finish can never interleave their delete+insert rewrites.
    private val persistMutex = Mutex()
    private var autosaveJob: Job? = null
    private var settingsJob: Job? = null

    // Set once the session is finished or discarded; stops any still-pending
    // autosave from resurrecting rows for a session that no longer exists.
    private var closed = false
    internal val isClosed: Boolean get() = closed

    init {
        settingsJob = scope.launch {
            UserSettingsRepository.settings.collect { settings ->
                restTimerDurationSeconds = settings.restTimerDurationSeconds.toLong()
                val newUnit = settings.weightUnit
                if (newUnit != currentWeightUnit) {
                    currentWeightUnit = newUnit
                    if (!_uiState.value.loading) {
                        updateState(_uiState.value.withDisplayUnit(newUnit))
                    }
                }
            }
        }
        scope.launch {
            currentWeightUnit = UserSettingsRepository.settings.first().weightUnit
            when (mode) {
                is WorkoutSessionMode.LogCompleted -> {
                    defaultTitle = defaultWorkoutTitle(mode.epochDay)
                    initialSnapshot = FormSnapshot(
                        routineId = null,
                        title = defaultTitle,
                        exercises = emptyList(),
                    )
                    if (!closed) {
                        updateState(
                            _uiState.value.copy(
                                loading = false,
                                exercises = emptyList(),
                                startedAt = 0L,
                                isLiveSession = false,
                                isEditingExisting = false,
                                sessionTitle = defaultTitle,
                            ),
                        )
                    }
                }
                is WorkoutSessionMode.Live,
                is WorkoutSessionMode.EditCompleted,
                -> {
                    val targetId = sessionId
                    val session = AppDb.db.workoutSessionDao().findById(targetId)
                    val details = AppDb.db.workoutSessionDetailDao().getForSession(targetId)
                    selectedRoutineId = session?.routineId
                    val routineName = session?.routineId?.let { AppDb.db.routineDao().findById(it)?.name }
                    val fallbackMillis = session?.performedAt ?: nowMillis()
                    defaultTitle = routineName?.trim()?.takeIf { it.isNotEmpty() }
                        ?: defaultWorkoutTitleForMillis(fallbackMillis)
                    val resolvedTitle = resolveWorkoutTitle(session?.title, routineName, fallbackMillis)
                    val grouped = groupSessionDetails(details)
                    val withPrevious = grouped.map { draft ->
                        val rows = loadPreviousSetRows(draft.exercise)
                        draft.copy(
                            previousSetRows = rows,
                            previousSets = formatPreviousSets(rows, draft.exercise, currentWeightUnit),
                        )
                    }
                    val formatted = _uiState.value.copy(
                        loading = false,
                        exercises = withPrevious,
                        startedAt = if (isLiveSession) (session?.performedAt ?: 0L) else 0L,
                        isLiveSession = isLiveSession,
                        isEditingExisting = isEditingExisting,
                        sessionTitle = resolvedTitle,
                    ).withDisplayUnit(currentWeightUnit)
                    if (!isLiveSession) {
                        initialSnapshot = snapshotOf(formatted.exercises, selectedRoutineId, resolvedTitle)
                    }
                    if (!closed) {
                        updateState(formatted)
                    }
                }
            }
        }
    }

    private suspend fun groupSessionDetails(
        details: List<WorkoutSessionDetailEntity>,
    ): List<DraftSessionExercise> {
        // Details arrive in flat position order with sets of the same
        // exercise adjacent (invariant maintained by the creator + saver).
        val grouped = mutableListOf<DraftSessionExercise>()
        var currentExerciseId = -1L
        var currentSets = mutableListOf<DraftSessionSet>()
        var currentExercise: Exercise? = null
        fun flush() {
            val ex = currentExercise ?: return
            grouped.add(
                DraftSessionExercise(
                    id = nextId++,
                    exercise = ex,
                    sets = currentSets.toList().ifEmpty {
                        listOf(DraftSessionSet(targetUnit = currentWeightUnit, actualUnit = currentWeightUnit))
                    },
                ),
            )
        }
        for (detail in details) {
            if (detail.exerciseId != currentExerciseId) {
                flush()
                currentExercise = ExerciseRepository.findById(detail.exerciseId)
                currentExerciseId = detail.exerciseId
                currentSets = mutableListOf()
            }
            currentSets.add(
                DraftSessionSet(
                    targetReps = detail.targetReps?.toString().orEmpty(),
                    targetDuration = formatDurationText(detail.targetDuration),
                    actualReps = detail.actualReps?.toString().orEmpty(),
                    actualDuration = formatDurationText(detail.actualDuration),
                    setType = detail.setType,
                    completed = detail.completed,
                    rawTargetWeight = detail.targetWeight,
                    rawTargetDistance = detail.targetDistance,
                    targetUnit = detail.weightUnit,
                    rawActualWeight = detail.actualWeight,
                    rawActualDistance = detail.actualDistance,
                    actualUnit = detail.weightUnit,
                ),
            )
        }
        flush()
        return grouped
    }

    private fun resolvedTitleForSave(rawTitle: String?): String =
        filterWorkoutTitle(rawTitle.orEmpty()).trim().takeIf { it.isNotEmpty() } ?: defaultTitle

    private fun updateState(newState: WorkoutSessionUiState) {
        val stamped = if (isLiveSession) {
            newState
        } else {
            val dirty = initialSnapshot?.let {
                snapshotOf(
                    newState.exercises,
                    selectedRoutineId,
                    resolvedTitleForSave(newState.sessionTitle),
                ) != it
            } ?: false
            newState.copy(restTimer = null, hasUnsavedChanges = dirty)
        }
        _uiState.value = stamped
        if (isLiveSession) {
            WorkoutSessionRepository.updateActiveRestTimer(stamped.restTimer)
        }
    }

    internal fun closeQuietly() {
        closed = true
        autosaveJob?.cancel()
        settingsJob?.cancel()
    }

    internal suspend fun flushBeforeExternalFinish() {
        val state = _uiState.value
        val wasLoading = state.loading
        closed = true
        autosaveJob?.cancel()
        settingsJob?.cancel()
        updateState(state.copy(saving = false, finished = true, restTimer = null))
        if (!wasLoading) {
            persistMutex.withLock { persist(state) }
        }
    }

    fun updateTitle(title: String) {
        val current = _uiState.value
        if (current.loading || current.saving) return
        titleEditedByUser = true
        val filtered = filterWorkoutTitle(title)
        if (filtered == current.sessionTitle) return
        updateState(current.copy(sessionTitle = filtered))
        scheduleAutosave()
    }

    fun addExercise(exercise: Exercise) {
        val draftId = nextId++
        val current = _uiState.value
        val unit = currentWeightUnit
        updateState(
            current.copy(
                exercises = current.exercises + DraftSessionExercise(
                    id = draftId,
                    exercise = exercise,
                    sets = listOf(DraftSessionSet(targetUnit = unit, actualUnit = unit)),
                ),
            ),
        )
        scheduleAutosave()
        // Previous values come from the db; fill them in once loaded.
        scope.launch {
            val rows = loadPreviousSetRows(exercise)
            if (rows.isEmpty() || closed) return@launch
            val state = _uiState.value
            updateState(
                state.copy(
                    exercises = state.exercises.map {
                        if (it.id == draftId) {
                            it.copy(
                                previousSetRows = rows,
                                previousSets = formatPreviousSets(rows, exercise, currentWeightUnit),
                            )
                        } else {
                            it
                        }
                    },
                ),
            )
        }
    }

    fun addSet(exerciseIndex: Int) {
        val unit = currentWeightUnit
        updateExercise(exerciseIndex) { ex ->
            ex.copy(sets = ex.sets + DraftSessionSet(targetUnit = unit, actualUnit = unit))
        }
    }

    fun removeSet(exerciseIndex: Int, setIndex: Int) {
        updateState(_uiState.value.removeSetAt(exerciseIndex, setIndex))
        scheduleAutosave()
    }

    fun removeExercise(exerciseIndex: Int) {
        updateState(_uiState.value.removeExerciseAt(exerciseIndex))
        scheduleAutosave()
    }

    fun moveExercise(from: Int, to: Int) {
        val current = _uiState.value
        val list = current.exercises.toMutableList()
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        updateState(current.copy(exercises = list))
        scheduleAutosave()
    }

    fun updateActualWeight(exerciseIndex: Int, setIndex: Int, value: String) {
        val weight = value.filterDecimal()
        val unit = currentWeightUnit
        updateSet(exerciseIndex, setIndex) { set, _ ->
            val convertedDistance = if (set.actualUnit != unit) {
                set.rawActualDistance?.let { set.actualUnit.convertDistance(it, unit) }
            } else {
                set.rawActualDistance
            }
            set.copy(
                actualWeight = weight,
                rawActualWeight = weight.toDoubleOrNull(),
                rawActualDistance = convertedDistance,
                actualUnit = unit,
            )
        }
    }

    fun updateActualReps(exerciseIndex: Int, setIndex: Int, value: String) {
        updateActualField(exerciseIndex, setIndex) { it.copy(actualReps = value.filterDigits()) }
    }

    fun updateActualDuration(exerciseIndex: Int, setIndex: Int, value: String) {
        updateActualField(exerciseIndex, setIndex) {
            it.copy(actualDuration = value.filterDurationText())
        }
    }

    fun updateActualDistance(exerciseIndex: Int, setIndex: Int, value: String) {
        val distance = value.filterDistance()
        val unit = currentWeightUnit
        updateActualField(exerciseIndex, setIndex) { set ->
            val convertedWeight = if (set.actualUnit != unit) {
                set.rawActualWeight?.let { set.actualUnit.convertWeight(it, unit) }
            } else {
                set.rawActualWeight
            }
            set.copy(
                actualDistance = distance,
                rawActualDistance = distance.toDoubleOrNull(),
                rawActualWeight = convertedWeight,
                actualUnit = unit,
            )
        }
    }

    /**
     * Applies [transform] and keeps `completed` only while the set still has
     * every field the exercise type requires. If the edit invalidates the set
     * that owns the running rest timer, the timer is cancelled too.
     */
    private fun updateActualField(
        exerciseIndex: Int,
        setIndex: Int,
        transform: (DraftSessionSet) -> DraftSessionSet,
    ) {
        updateState(_uiState.value.updateActualFieldAt(exerciseIndex, setIndex, transform))
        scheduleAutosave()
    }

    fun setSetType(exerciseIndex: Int, setIndex: Int, setType: SetType) {
        updateState(_uiState.value.setSetTypeAt(exerciseIndex, setIndex, setType, currentWeightUnit))
        scheduleAutosave()
    }

    fun toggleCompleted(exerciseIndex: Int, setIndex: Int) {
        if (!isLiveSession) return
        updateState(
            _uiState.value.toggleCompletedAt(
                exerciseIndex = exerciseIndex,
                setIndex = setIndex,
                durationSeconds = restTimerDurationSeconds,
            ),
        )
        scheduleAutosave()
    }

    /** Clears the rest timer once its countdown reaches zero. */
    fun clearRestTimer() {
        val current = _uiState.value
        if (current.restTimer == null) return
        updateState(current.copy(restTimer = null))
    }

    /**
     * Populates exercises and target placeholders from [routineId] when
     * logging a completed workout without starting a live session.
     */
    fun loadRoutine(routineId: Long) {
        if (isLiveSession || closed) return
        scope.launch {
            val routine = AppDb.db.routineDao().findById(routineId) ?: return@launch
            val workSets = AppDb.db.workSetDao().getForRoutine(routineId)
            val unit = currentWeightUnit
            val added = mutableListOf<DraftSessionExercise>()
            for (ws in workSets) {
                val exercise = ExerciseRepository.findById(ws.exerciseId) ?: continue
                val details = AppDb.db.workSetDetailDao().getForWorkSet(ws.id)
                val sets = if (details.isEmpty()) {
                    listOf(DraftSessionSet(targetUnit = unit, actualUnit = unit))
                } else {
                    details.map { detail ->
                        DraftSessionSet(
                            targetReps = detail.targetReps?.toString().orEmpty(),
                            targetDuration = formatDurationText(detail.targetDuration),
                            setType = detail.setType,
                            rawTargetWeight = detail.targetWeight,
                            rawTargetDistance = detail.targetDistance,
                            targetUnit = detail.weightUnit,
                            actualUnit = unit,
                        ).formattedFor(unit)
                    }
                }
                val rows = loadPreviousSetRows(exercise)
                added.add(
                    DraftSessionExercise(
                        id = nextId++,
                        exercise = exercise,
                        sets = sets,
                        previousSetRows = rows,
                        previousSets = formatPreviousSets(rows, exercise, unit),
                    ),
                )
            }
            if (closed || added.isEmpty()) return@launch
            val current = _uiState.value
            if (selectedRoutineId == null && current.exercises.isEmpty()) {
                selectedRoutineId = routineId
            }
            val nextTitle = if (!titleEditedByUser) {
                routine.name
            } else {
                current.sessionTitle ?: routine.name
            }
            updateState(
                current.copy(
                    exercises = current.exercises + added,
                    sessionTitle = nextTitle,
                ),
            )
        }
    }

    /** Rewrite-on-save mirroring RoutineFormState — simpler than diffing. */
    fun save(onSaved: () -> Unit = {}) {
        val state = _uiState.value
        updateState(state.copy(saving = true))
        autosaveJob?.cancel()
        scope.launch {
            persistMutex.withLock { persist(state) }
            updateState(_uiState.value.copy(saving = false))
            onSaved()
        }
    }

    fun finish(onDone: () -> Unit = {}) {
        val state = _uiState.value
        updateState(state.copy(saving = true, restTimer = null))
        closed = true
        autosaveJob?.cancel()
        settingsJob?.cancel()
        scope.launch {
            persistMutex.withLock {
                persist(state)
                WorkoutSessionRepository.finishPersisted(sessionId)
            }
            updateState(_uiState.value.copy(saving = false, finished = true))
            WorkoutSessionRepository.evictFormState(sessionId)
            onDone()
        }
    }

    fun discard(onDone: () -> Unit = {}) {
        updateState(_uiState.value.copy(saving = true, restTimer = null))
        closed = true
        autosaveJob?.cancel()
        settingsJob?.cancel()
        scope.launch {
            persistMutex.withLock { WorkoutSessionRepository.discard(sessionId) }
            updateState(_uiState.value.copy(saving = false, discarded = true))
            WorkoutSessionRepository.evictFormState(sessionId)
            onDone()
        }
    }

    /**
     * Explicit save for timerless completed workouts (Log or Edit mode).
     * Drops sets missing required fields and marks all saved sets completed.
     */
    fun saveCompleted(onDone: () -> Unit = {}) {
        val state = _uiState.value
        if (isLiveSession || !state.canSaveCompleted) return
        _uiState.value = state.copy(saving = true)
        closed = true
        settingsJob?.cancel()
        scope.launch {
            persistMutex.withLock {
                AppDb.db.saveCompletedWorkout(
                    mode = mode,
                    routineId = selectedRoutineId,
                    title = resolvedTitleForSave(state.sessionTitle),
                    exercises = state.exercises,
                    now = nowMillis(),
                )
            }
            GoogleDriveBackupManager.onWorkoutOrJournalChanged()
            _uiState.value = _uiState.value.copy(
                saving = false,
                finished = true,
                hasUnsavedChanges = false,
            )
            onDone()
        }
    }

    /** Deletes an existing completed workout (Edit mode). */
    fun deleteCompletedSession(onDone: () -> Unit = {}) {
        val editMode = mode as? WorkoutSessionMode.EditCompleted ?: return
        val state = _uiState.value
        if (state.loading || state.saving) return
        _uiState.value = state.copy(saving = true)
        closed = true
        settingsJob?.cancel()
        scope.launch {
            persistMutex.withLock {
                WorkoutSessionRepository.deleteCompletedSession(editMode.sessionId)
            }
            _uiState.value = _uiState.value.copy(
                saving = false,
                deleted = true,
                hasUnsavedChanges = false,
            )
            onDone()
        }
    }

    /**
     * Edits are flushed to the database shortly after every mutation so they
     * survive navigating away (the screen's remembered state is lost once it
     * leaves composition) and so the mini bar's finish action operates on
     * current data. Debounced to avoid a full rewrite per keystroke; the
     * persist itself is NonCancellable so a newer autosave can only supersede
     * an older one during the delay, never mid-rewrite.
     */
    private fun scheduleAutosave() {
        if (!isLiveSession || closed || _uiState.value.loading) return
        autosaveJob?.cancel()
        autosaveJob = scope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            val state = _uiState.value
            withContext(NonCancellable) {
                persistMutex.withLock {
                    if (!closed) persist(state)
                }
            }
        }
    }

    private suspend fun persist(state: WorkoutSessionUiState) {
        val now = nowMillis()
        AppDb.db.workoutSessionDao().updateTitle(
            id = sessionId,
            title = resolvedTitleForSave(state.sessionTitle),
            now = now,
        )
        AppDb.db.workoutSessionDetailDao().deleteForSession(sessionId)
        var position = 1
        state.exercises.forEach { draft ->
            val sets = if (draft.sets.isEmpty()) listOf(DraftSessionSet()) else draft.sets
            sets.forEach { set ->
                AppDb.db.workoutSessionDetailDao().insert(
                    buildSessionDetailEntity(
                        sessionId = sessionId,
                        exerciseId = draft.exercise.id,
                        position = position++,
                        set = set,
                        completed = set.completed,
                        now = now,
                    ),
                )
            }
        }
    }

    private suspend fun loadPreviousSetRows(exercise: Exercise): List<PreviousSetRow> =
        AppDb.db.workoutSessionDetailDao()
            .getLatestCompletedSets(PROFILE_ID, exercise.id)

    private fun updateExercise(index: Int, transform: (DraftSessionExercise) -> DraftSessionExercise) {
        val current = _uiState.value
        updateState(
            current.copy(
                exercises = current.exercises.mapIndexed { i, ex -> if (i == index) transform(ex) else ex },
            ),
        )
        scheduleAutosave()
    }

    private fun updateSet(
        exerciseIndex: Int,
        setIndex: Int,
        transform: (DraftSessionSet, ExerciseType) -> DraftSessionSet,
    ) {
        updateExercise(exerciseIndex) { ex ->
            ex.copy(sets = ex.sets.mapIndexed { i, s -> if (i == setIndex) transform(s, ex.type) else s })
        }
    }
}

private data class SetSnapshot(
    val setType: SetType,
    val actualWeight: String,
    val actualReps: String,
    val actualDuration: String,
    val actualDistance: String,
)

private data class ExerciseSnapshot(
    val exerciseId: Long,
    val sets: List<SetSnapshot>,
)

private data class FormSnapshot(
    val routineId: Long?,
    val title: String,
    val exercises: List<ExerciseSnapshot>,
)

private fun snapshotOf(
    exercises: List<DraftSessionExercise>,
    routineId: Long?,
    title: String,
): FormSnapshot =
    FormSnapshot(
        routineId = routineId,
        title = title,
        exercises = exercises.map { ex ->
            ExerciseSnapshot(
                exerciseId = ex.exercise.id,
                sets = ex.sets.map { s ->
                    SetSnapshot(
                        setType = s.setType,
                        actualWeight = s.actualWeight,
                        actualReps = s.actualReps,
                        actualDuration = s.actualDuration,
                        actualDistance = s.actualDistance,
                    )
                },
            )
        },
    )

private fun buildSessionDetailEntity(
    sessionId: Long,
    exerciseId: Long,
    position: Int,
    set: DraftSessionSet,
    completed: Boolean,
    now: Long,
): WorkoutSessionDetailEntity {
    val actualWeight = set.rawActualWeight ?: set.actualWeight.toDoubleOrNull()
    val actualDistance = set.rawActualDistance ?: set.actualDistance.toDoubleOrNull()
    val rawTargetWeight = set.rawTargetWeight ?: set.targetWeight.toDoubleOrNull()
    val rawTargetDistance = set.rawTargetDistance ?: set.targetDistance.toDoubleOrNull()
    val hasActualMeasurement = actualWeight != null || actualDistance != null
    val rowUnit = if (hasActualMeasurement) set.actualUnit else set.targetUnit
    val targetWeight = rawTargetWeight?.let { set.targetUnit.convertWeight(it, rowUnit) }
    val targetDistance = rawTargetDistance?.let { set.targetUnit.convertDistance(it, rowUnit) }
    return WorkoutSessionDetailEntity(
        workoutSessionId = sessionId,
        workSetDetailId = null,
        exerciseId = exerciseId,
        position = position,
        targetWeight = targetWeight,
        targetReps = set.targetReps.toIntOrNull(),
        targetDuration = parseDurationSeconds(set.targetDuration),
        targetDistance = targetDistance,
        actualWeight = actualWeight,
        actualReps = set.actualReps.toIntOrNull(),
        actualDuration = parseDurationSeconds(set.actualDuration),
        actualDistance = actualDistance,
        setType = set.setType,
        weightUnit = rowUnit,
        completed = completed,
        createdAt = now,
        updatedAt = now,
    )
}

internal suspend fun AppDatabase.saveCompletedWorkout(
    mode: WorkoutSessionMode,
    routineId: Long?,
    exercises: List<DraftSessionExercise>,
    now: Long,
    title: String = "",
    zone: TimeZone = TimeZone.currentSystemDefault(),
    todayEpochDay: Long = WeekMath.todayEpochDay(),
): Long {
    val filtered = exercises.mapNotNull { draft ->
        val validSets = draft.sets.filter { set ->
            set.meetsRequirements(draft.type)
        }
        if (validSets.isEmpty()) null else draft to validSets
    }
    require(filtered.any { (draft, sets) -> sets.any { it.setType != SetType.WARMUP } }) {
        "Cannot save a completed workout without at least one valid working set"
    }

    return useWriterConnection { transactor ->
        transactor.immediateTransaction {
            val targetSessionId = when (mode) {
                is WorkoutSessionMode.LogCompleted -> {
                    val performedAt = if (mode.epochDay == todayEpochDay) {
                        now
                    } else {
                        LocalDate.fromEpochDays(mode.epochDay)
                            .atStartOfDayIn(zone)
                            .toEpochMilliseconds() + 12 * 3_600_000L
                    }
                    val resolvedTitle = title.trim().takeIf { it.isNotEmpty() }
                        ?: defaultWorkoutTitleForMillis(performedAt, zone)
                    workoutSessionDao().insert(
                        WorkoutSessionEntity(
                            profileId = PROFILE_ID,
                            routineId = routineId,
                            performedAt = performedAt,
                            completedAt = performedAt,
                            title = resolvedTitle,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                }
                is WorkoutSessionMode.EditCompleted -> {
                    val existing = workoutSessionDao().findById(mode.sessionId)
                    val resolvedTitle = title.trim().takeIf { it.isNotEmpty() }
                        ?: defaultWorkoutTitleForMillis(existing?.performedAt ?: now, zone)
                    workoutSessionDao().updateRoutineTitleAndTimestamp(
                        id = mode.sessionId,
                        routineId = routineId,
                        title = resolvedTitle,
                        now = now,
                    )
                    workoutSessionDetailDao().deleteForSession(mode.sessionId)
                    mode.sessionId
                }
                is WorkoutSessionMode.Live -> error("saveCompletedWorkout does not support Live mode")
            }

            var position = 1
            filtered.forEach { (draft, sets) ->
                sets.forEach { set ->
                    workoutSessionDetailDao().insert(
                        buildSessionDetailEntity(
                            sessionId = targetSessionId,
                            exerciseId = draft.exercise.id,
                            position = position++,
                            set = set,
                            completed = true,
                            now = now,
                        ),
                    )
                }
            }
            targetSessionId
        }
    }
}

private const val AUTOSAVE_DEBOUNCE_MS = 500L

/**
 * Suggests ~80% of [precedingWeight] rounded to a practical plate/dumbbell step
 * in [unit], strictly below [precedingWeight]. Returns null for non-positive
 * weights or when rounding cannot produce a lighter positive load.
 */
internal fun suggestDropSetWeight(precedingWeight: Double, unit: WeightUnit): Double? {
    if (precedingWeight <= 0.0 || !precedingWeight.isFinite()) return null
    val raw = precedingWeight * 0.8
    val step = when (unit) {
        WeightUnit.METRIC -> when {
            precedingWeight >= 20.0 -> 2.5
            precedingWeight >= 10.0 -> 1.0
            else -> 0.5
        }
        WeightUnit.IMPERIAL -> when {
            precedingWeight >= 45.0 -> 5.0
            precedingWeight >= 15.0 -> 2.5
            else -> 1.0
        }
    }
    var snapped = round(raw / step) * step
    if (snapped >= precedingWeight) {
        snapped = floor(raw / step) * step
    }
    if (snapped <= 0.0 || snapped >= precedingWeight) return null
    return round(snapped * 100.0) / 100.0
}

internal fun formatPreviousSets(
    rows: List<PreviousSetRow>,
    exercise: Exercise,
    displayUnit: WeightUnit,
): List<String> {
    val type = ExerciseType.fromValue(exercise.exerciseType) ?: ExerciseType.STRENGTH
    val fields = type.requiredSetFields()
    val unitSuffix = if (displayUnit == WeightUnit.IMPERIAL) "lb" else "kg"
    return rows.map { row ->
        // Blank entries keep the list positionally aligned; the UI
        // shows its placeholder for them.
        listOfNotNull(
            row.actualWeight?.let { "${WeightUnit.formatWeight(it, row.weightUnit, displayUnit)}$unitSuffix" },
            row.actualReps?.takeIf { SetField.REPS in fields }?.let { "x $it" },
            row.actualDuration?.takeIf { SetField.DURATION in fields }?.let { formatDurationHhMm(it) },
            row.actualDistance?.takeIf { SetField.DISTANCE in fields }?.let {
                WeightUnit.formatDistance(it, row.weightUnit, displayUnit)
            },
        ).joinToString(" ")
    }
}
