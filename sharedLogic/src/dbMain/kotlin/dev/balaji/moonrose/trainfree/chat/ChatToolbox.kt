package dev.balaji.moonrose.trainfree.chat

import dev.balaji.moonrose.trainfree.calendar.WeekMath
import dev.balaji.moonrose.trainfree.db.AiCreateExerciseResult
import dev.balaji.moonrose.trainfree.db.AiCreateProgramResult
import dev.balaji.moonrose.trainfree.db.AiRoutineAddResult
import dev.balaji.moonrose.trainfree.db.AiRoutineUpdateResult
import dev.balaji.moonrose.trainfree.db.AppDb
import dev.balaji.moonrose.trainfree.db.BodyMetricsRepository
import dev.balaji.moonrose.trainfree.db.CompletedSetRow
import dev.balaji.moonrose.trainfree.db.ExerciseRepository
import dev.balaji.moonrose.trainfree.db.InsightsRepository
import dev.balaji.moonrose.trainfree.db.JournalEntryEntity
import dev.balaji.moonrose.trainfree.db.PROFILE_ID
import dev.balaji.moonrose.trainfree.db.ProfileRepository
import dev.balaji.moonrose.trainfree.db.RecentCompletedSessionRow
import dev.balaji.moonrose.trainfree.db.RoutineLastPerformedRow
import dev.balaji.moonrose.trainfree.db.SessionDetailWithExerciseRow
import dev.balaji.moonrose.trainfree.db.SetPoint
import dev.balaji.moonrose.trainfree.db.UserSettingsRepository
import dev.balaji.moonrose.trainfree.db.WorkSetDetailEntity
import dev.balaji.moonrose.trainfree.db.WorkSetEntity
import dev.balaji.moonrose.trainfree.db.addAiRoutineToProgram
import dev.balaji.moonrose.trainfree.db.bodyPartBreakdownFrom
import dev.balaji.moonrose.trainfree.db.convertedTo
import dev.balaji.moonrose.trainfree.db.createAiProgram
import dev.balaji.moonrose.trainfree.db.createCustomExercise
import dev.balaji.moonrose.trainfree.db.millisToEpochDay
import dev.balaji.moonrose.trainfree.db.personalRecordsFrom
import dev.balaji.moonrose.trainfree.db.progressionFrom
import dev.balaji.moonrose.trainfree.db.singleExerciseWeeklyDistance
import dev.balaji.moonrose.trainfree.db.singleExerciseWeeklyDuration
import dev.balaji.moonrose.trainfree.db.singleExerciseWeeklyVolume
import dev.balaji.moonrose.trainfree.db.startOfDayMillis
import dev.balaji.moonrose.trainfree.db.toSetPoint
import dev.balaji.moonrose.trainfree.db.updateAiRoutine
import dev.balaji.moonrose.trainfree.insights.BodyPartSlice
import dev.balaji.moonrose.trainfree.insights.BodyWeightTrend
import dev.balaji.moonrose.trainfree.insights.InsightMath.formatNumber
import dev.balaji.moonrose.trainfree.insights.TrainingFrequency
import dev.balaji.moonrose.trainfree.insights.WeeklyVolumePoint
import dev.balaji.moonrose.trainfree.model.Exercise
import dev.balaji.moonrose.trainfree.model.ExerciseCatalog
import dev.balaji.moonrose.trainfree.model.ExerciseType
import dev.balaji.moonrose.trainfree.model.HeightConversion
import dev.balaji.moonrose.trainfree.model.Profile
import dev.balaji.moonrose.trainfree.model.Program
import dev.balaji.moonrose.trainfree.model.Routine
import dev.balaji.moonrose.trainfree.model.SetType
import dev.balaji.moonrose.trainfree.model.UserSettings
import dev.balaji.moonrose.trainfree.model.WeightUnit
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Deterministic toolbox for the on-device AI chat assistant, covering both
 * read queries (history, PRs, progression, routines, exercises, metrics) and
 * write actions (creating custom exercises, programs, and routines, and
 * editing existing routines).
 *
 * Every numeric calculation, unit formatting, date formatting, and fuzzy
 * exercise resolution happens here in Kotlin so the language model never
 * has to compute or convert statistics. All outputs are compact and bounded
 * to fit comfortably inside a ~4K-token on-device context window.
 */
object ChatToolbox {

    /** Weekly volume, training frequency/streaks, and primary body-part distribution. */
    suspend fun getTrainingSummary(weeks: Int = 8): ToolOutput {
        val clampedWeeks = weeks.coerceIn(1, 12)
        val settings = UserSettingsRepository.settings.first()
        val volume = InsightsRepository.weeklyVolume(clampedWeeks)
        val frequency = InsightsRepository.trainingFrequency()
        val bodyParts = InsightsRepository.bodyPartBreakdown(days = 90)
        return trainingSummaryFrom(
            volume = volume,
            frequency = frequency,
            bodyParts = bodyParts,
            unit = settings.weightUnit,
        )
    }

    /**
     * Personal records across exercises (or for one fuzzy-matched [exerciseQuery]).
     * Strength exercises rank by estimated 1RM (using the user's configured formula);
     * duration/distance exercises report best single-set time or distance when queried.
     */
    suspend fun getPersonalRecords(
        exerciseQuery: String? = null,
        limit: Int = 5,
    ): ToolOutput {
        val clampedLimit = limit.coerceIn(1, 12)
        val settings = UserSettingsRepository.settings.first()
        val query = exerciseQuery?.trim()?.takeIf { it.isNotEmpty() }

        if (query == null) {
            val prs = InsightsRepository.personalRecords(limit = clampedLimit)
            return personalRecordsFrom(prs, settings.weightUnit, settings.oneRmFormula.value)
        }

        return when (val match = resolveExercise(query)) {
            is ExerciseMatchResult.NotFound ->
                ToolOutput("No exercise matching \"$query\" was found in the catalog.")
            is ExerciseMatchResult.Ambiguous -> ambiguousOutput(query, match.candidates)
            is ExerciseMatchResult.Matched -> {
                val sets = loadExerciseSets(match.exercise.id, startMillis = 0L, targetUnit = settings.weightUnit)
                exercisePrFrom(match.exercise, sets, settings)
            }
        }
    }

    /**
     * Progression over the last [weeks] weeks for one fuzzy-matched exercise,
     * plus its most recent working sessions for concrete weight/rep recall.
     */
    suspend fun getExerciseProgression(
        exerciseQuery: String,
        weeks: Int = 12,
    ): ToolOutput {
        val clampedWeeks = weeks.coerceIn(2, 24)
        val query = exerciseQuery.trim()
        if (query.isEmpty()) return ToolOutput("Specify an exercise name to check progression.")

        return when (val match = resolveExercise(query)) {
            is ExerciseMatchResult.NotFound ->
                ToolOutput("No exercise matching \"$query\" was found in the catalog.")
            is ExerciseMatchResult.Ambiguous -> ambiguousOutput(query, match.candidates)
            is ExerciseMatchResult.Matched -> {
                val settings = UserSettingsRepository.settings.first()
                val today = WeekMath.todayEpochDay()
                val firstWeekStart = WeekMath.weekStartOnOrBefore(today, settings.weekStart) - 7L * (clampedWeeks - 1)
                val sets = loadExerciseSets(match.exercise.id, startOfDayMillis(firstWeekStart), settings.weightUnit)
                exerciseProgressionFrom(
                    exercise = match.exercise,
                    sets = sets,
                    weeks = clampedWeeks,
                    todayEpochDay = today,
                    settings = settings,
                )
            }
        }
    }

    /**
     * Comprehensive progress review over the trailing [days] window (7..180, default 30)
     * compared with the prior matching window: session/set/volume deltas, progressing vs
     * plateaued exercises, trained vs untrained muscle groups, and recent journal notes.
     */
    suspend fun getProgressReview(days: Int = 30): ToolOutput {
        val clampedDays = days.coerceIn(7, 180)
        val settings = UserSettingsRepository.settings.first()
        val zone = TimeZone.currentSystemDefault()
        val today = WeekMath.todayEpochDay()
        val lookbackDays = maxOf(clampedDays * 2, 56)
        val startDay = today - lookbackDays + 1L
        val startMillis = startOfDayMillis(startDay, zone)
        val endMillis = startOfDayMillis(today + 1L, zone)

        val db = AppDb.db
        val sets = db.workoutSessionDetailDao()
            .getCompletedSets(PROFILE_ID, startMillis)
            .map { it.toSetPoint(zone).convertedTo(settings.weightUnit) }
        val sessions = db.workoutSessionDao()
            .getCompletedWithRoutineInRange(PROFILE_ID, startMillis, endMillis)
        val journal = db.journalEntryDao()
            .getInRange(PROFILE_ID, today - clampedDays + 1L, today)

        return progressReviewFrom(
            sets = sets,
            sessions = sessions,
            journalEntries = journal,
            days = clampedDays,
            todayEpochDay = today,
            settings = settings,
            zone = zone,
        )
    }

    /**
     * Recent completed workout sessions with routine name, duration, working sets
     * (warm-ups excluded), and any journal notes on those days. Optionally filter
     * by [routineQuery], trailing [days], [exerciseQuery], or [startDate]..[endDate].
     */
    suspend fun getRecentWorkouts(
        limit: Int = 5,
        routineQuery: String? = null,
        days: Int? = null,
        exerciseQuery: String? = null,
        startDate: String? = null,
        endDate: String? = null,
    ): ToolOutput {
        val clampedLimit = limit.coerceIn(1, 15)
        val settings = UserSettingsRepository.settings.first()
        val zone = TimeZone.currentSystemDefault()
        val today = WeekMath.todayEpochDay()

        val parsedStart = startDate?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            parseDateEpochDay(raw, today) ?: return ToolOutput("Could not parse start date \"$raw\". Use YYYY-MM-DD.")
        }
        val parsedEnd = endDate?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            parseDateEpochDay(raw, today) ?: return ToolOutput("Could not parse end date \"$raw\". Use YYYY-MM-DD.")
        }

        val routineFilter = routineQuery?.trim()?.takeIf { it.isNotEmpty() }
        val exerciseFilter = exerciseQuery?.trim()?.takeIf { it.isNotEmpty() }
        val matchedExerciseIds: Set<Long>? = if (exerciseFilter != null) {
            when (val m = resolveExercise(exerciseFilter)) {
                is ExerciseMatchResult.Matched -> setOf(m.exercise.id)
                is ExerciseMatchResult.Ambiguous -> m.candidates.map { it.id }.toSet()
                is ExerciseMatchResult.NotFound -> null
            }
        } else {
            null
        }

        val db = AppDb.db
        val rawSessions = if (parsedStart != null || parsedEnd != null) {
            val sMillis = parsedStart?.let { startOfDayMillis(it, zone) } ?: 0L
            val eMillis = parsedEnd?.let { startOfDayMillis(it + 1L, zone) } ?: startOfDayMillis(today + 1L, zone)
            db.workoutSessionDao()
                .getCompletedWithRoutineInRange(PROFILE_ID, sMillis, eMillis)
                .sortedByDescending { it.performedAt }
        } else {
            val sinceMillis = days?.let { startOfDayMillis(today - it.coerceIn(1, 365), zone) } ?: 0L
            val fetchLimit = if (routineFilter != null || exerciseFilter != null) 80 else clampedLimit
            db.workoutSessionDao().getRecentCompletedWithRoutine(PROFILE_ID, sinceMillis, fetchLimit)
        }

        val routineFiltered = if (routineFilter == null) {
            rawSessions
        } else {
            rawSessions.filter {
                it.title.contains(routineFilter, ignoreCase = true) ||
                    it.routineName?.contains(routineFilter, ignoreCase = true) == true
            }
        }

        if (routineFiltered.isEmpty()) {
            return ToolOutput(
                when {
                    routineFilter != null -> "No completed workouts found matching routine \"$routineFilter\"."
                    parsedStart != null || parsedEnd != null -> "No completed workouts found in that date range."
                    else -> "No completed workouts logged yet."
                },
            )
        }

        val allDetails = db.workoutSessionDetailDao().getCompletedForSessions(routineFiltered.map { it.id })
        val sessions = if (exerciseFilter == null) {
            routineFiltered.take(clampedLimit)
        } else {
            val detailsBySession = allDetails.groupBy { it.workoutSessionId }
            routineFiltered.filter { session ->
                val sessionDetails = detailsBySession[session.id].orEmpty()
                if (matchedExerciseIds != null) {
                    sessionDetails.any { it.exerciseId in matchedExerciseIds }
                } else {
                    sessionDetails.any { it.exerciseName.contains(exerciseFilter, ignoreCase = true) }
                }
            }.take(clampedLimit)
        }

        if (sessions.isEmpty()) {
            return ToolOutput("No completed workouts found including \"$exerciseFilter\".")
        }

        val sessionIds = sessions.map { it.id }.toSet()
        val finalDetails = allDetails.filter { it.workoutSessionId in sessionIds }
        val epochDays = sessions.map { millisToEpochDay(it.performedAt, zone) }
        val journalByDay = if (epochDays.isEmpty()) {
            emptyMap()
        } else {
            db.journalEntryDao()
                .getInRange(PROFILE_ID, epochDays.min(), epochDays.max())
                .associate { it.epochDay to it.content }
        }

        return recentWorkoutsFrom(
            sessions = sessions,
            details = finalDetails,
            zone = zone,
            unit = settings.weightUnit,
            journalByDay = journalByDay,
        )
    }

    /** Completed workouts and any journal entry logged on one specific day ([date] or [epochDay]). */
    suspend fun getWorkoutsForDay(
        epochDay: Long? = null,
        date: String? = null,
    ): ToolOutput {
        val today = WeekMath.todayEpochDay()
        val targetDay = when {
            date != null && date.isNotBlank() ->
                parseDateEpochDay(date, today)
                    ?: return ToolOutput("Could not parse date \"$date\". Use YYYY-MM-DD, \"today\", or \"yesterday\".")
            epochDay != null && epochDay > 0L -> epochDay
            else -> today
        }

        val settings = UserSettingsRepository.settings.first()
        val zone = TimeZone.currentSystemDefault()
        val startMillis = startOfDayMillis(targetDay, zone)
        val endMillis = startOfDayMillis(targetDay + 1L, zone)
        val db = AppDb.db
        val sessions = db.workoutSessionDao()
            .getCompletedWithRoutineInRange(PROFILE_ID, startMillis, endMillis)
        val journal = db.journalEntryDao().findForDay(PROFILE_ID, targetDay)?.content?.trim()?.takeIf { it.isNotEmpty() }

        if (sessions.isEmpty() && journal == null) {
            return ToolOutput("No completed workouts or journal notes logged on ${shortDate(targetDay)}.")
        }
        if (sessions.isEmpty() && journal != null) {
            return ToolOutput(
                summary = "No completed workouts on ${shortDate(targetDay)}.\nJournal note: \"${clipNote(journal)}\"",
                sources = listOf(ChatSourceRef(kind = "day", label = shortDate(targetDay), epochDay = targetDay)),
            )
        }
        val details = db.workoutSessionDetailDao().getCompletedForSessions(sessions.map { it.id })
        val journalMap = if (journal != null) mapOf(targetDay to journal) else emptyMap()
        return recentWorkoutsFrom(
            sessions = sessions,
            details = details,
            zone = zone,
            unit = settings.weightUnit,
            journalByDay = journalMap,
        )
    }

    /**
     * Programs, their routines in order, target exercises/sets, and when each routine
     * was last performed. Optionally narrow to a specific [programQuery] or [routineQuery].
     */
    suspend fun getProgramsAndRoutines(
        routineQuery: String? = null,
        programQuery: String? = null,
    ): ToolOutput {
        val db = AppDb.db
        val settings = UserSettingsRepository.settings.first()
        val programs = db.programDao().getAll().map { Program(it.id, it.name) }
        val routines = db.routineDao().getAll().map { Routine(it.id, it.name, it.programId, it.position) }
        if (programs.isEmpty()) {
            return ToolOutput("No workout programs or routines have been created yet.")
        }

        val progFilter = programQuery?.trim()?.takeIf { it.isNotEmpty() }
        val targetPrograms = if (progFilter != null) {
            programs.filter { it.name.contains(progFilter, ignoreCase = true) }
        } else {
            programs
        }
        if (targetPrograms.isEmpty()) {
            return ToolOutput("No programs matching \"$progFilter\" were found.")
        }
        val targetProgramIds = targetPrograms.map { it.id }.toSet()

        val routineFilter = routineQuery?.trim()?.takeIf { it.isNotEmpty() }
        val scopedRoutines = routines.filter { it.programId in targetProgramIds }
        val targetRoutines = if (routineFilter != null) {
            scopedRoutines.filter { it.name.contains(routineFilter, ignoreCase = true) }
        } else {
            scopedRoutines
        }
        if (routineFilter != null && targetRoutines.isEmpty()) {
            return ToolOutput("No routines matching \"$routineFilter\" were found.")
        }

        val workSetsByRoutine = mutableMapOf<Long, List<WorkSetEntity>>()
        val allWorkSetIds = mutableListOf<Long>()
        for (routine in targetRoutines) {
            val ws = db.workSetDao().getForRoutine(routine.id)
            workSetsByRoutine[routine.id] = ws
            ws.forEach { allWorkSetIds.add(it.id) }
        }

        val detailsByWorkSet = if (allWorkSetIds.isEmpty()) {
            emptyMap()
        } else {
            db.workSetDetailDao().getForWorkSets(allWorkSetIds).groupBy { it.workSetId }
        }
        val exercisesById = ExerciseRepository.getAll().associateBy { it.id }
        val lastPerformed = db.workoutSessionDao().getLastPerformedByRoutine(PROFILE_ID)

        return programsAndRoutinesFrom(
            programs = targetPrograms,
            routines = targetRoutines,
            workSetsByRoutine = workSetsByRoutine,
            detailsByWorkSet = detailsByWorkSet,
            exercisesById = exercisesById,
            lastPerformed = lastPerformed,
            unit = settings.weightUnit,
            zone = TimeZone.currentSystemDefault(),
            includeEmptyPrograms = routineFilter == null,
        )
    }

    /** Creates a custom exercise in the catalog with normalized body part, equipment, and type. */
    suspend fun createExercise(
        name: String,
        bodyPart: String,
        equipment: String? = null,
        exerciseType: String? = null,
        otherBodyParts: List<String> = emptyList(),
    ): ToolOutput = createExerciseResultToOutput(
        AppDb.db.createCustomExercise(
            name = name,
            bodyPart = bodyPart,
            equipment = equipment,
            exerciseType = exerciseType,
            otherBodyParts = otherBodyParts,
        ),
    )

    /**
     * Creates a new workout program with one or more routines and their exercises/sets.
     * Exercises not yet in the catalog are automatically created as custom exercises.
     */
    suspend fun createProgram(
        programName: String,
        routines: List<AiRoutineInput>,
    ): ToolOutput {
        val settings = UserSettingsRepository.settings.first()
        val result = AppDb.db.createAiProgram(
            programName = programName,
            routines = routines,
            unit = settings.weightUnit,
        )
        return createProgramResultToOutput(result, settings.weightUnit)
    }

    /** Adds a new routine to an existing (or newly created) program, auto-creating missing exercises. */
    suspend fun addRoutineToProgram(
        routineName: String,
        exercises: List<AiExerciseInput>,
        programName: String? = null,
    ): ToolOutput {
        val settings = UserSettingsRepository.settings.first()
        val result = AppDb.db.addAiRoutineToProgram(
            routineName = routineName,
            exercises = exercises,
            programName = programName,
            unit = settings.weightUnit,
        )
        return routineAddResultToOutput(result, settings.weightUnit)
    }

    /**
     * Modifies an existing routine: rename, replace exercises in-place, update sets/reps/weights,
     * remove exercises, or add new exercises (auto-creating any missing from the catalog).
     */
    suspend fun updateRoutine(
        routineName: String,
        programName: String? = null,
        newRoutineName: String? = null,
        addExercises: List<AiExerciseInput> = emptyList(),
        replaceExercises: List<AiReplaceExerciseInput> = emptyList(),
        updateExercises: List<AiExerciseInput> = emptyList(),
        removeExercises: List<String> = emptyList(),
    ): ToolOutput {
        val settings = UserSettingsRepository.settings.first()
        val result = AppDb.db.updateAiRoutine(
            routineName = routineName,
            programName = programName,
            newRoutineName = newRoutineName,
            addExercises = addExercises,
            replaceExercises = replaceExercises,
            updateExercises = updateExercises,
            removeExercises = removeExercises,
            unit = settings.weightUnit,
        )
        return routineUpdateResultToOutput(result, settings.weightUnit)
    }

    /**
     * Searches the exercise catalog by name/abbreviation, body part (including
     * secondary muscles and group aliases like "legs" or "arms"), and equipment.
     */
    suspend fun searchExercises(
        query: String? = null,
        bodyPart: String? = null,
        equipment: String? = null,
        limit: Int = 8,
    ): ToolOutput {
        val exercises = ExerciseRepository.getAll()
        val counts = InsightsRepository.completedSetCountsByExercise()
        val matches = ExerciseMatcher.search(
            exercises = exercises,
            query = query,
            bodyPart = bodyPart,
            equipment = equipment,
            trainedSetCounts = counts,
            limit = limit,
        )
        return exerciseSearchFrom(matches, counts)
    }

    /** Body-weight trend, profile measurements, and BMI (computed via configured formula). */
    suspend fun getBodyMetricsAndProfile(days: Int = 90): ToolOutput {
        val clampedDays = days.coerceIn(7, 365)
        val settings = UserSettingsRepository.settings.first()
        val profile = ProfileRepository.profile.first()
        val trend = BodyMetricsRepository.trend(clampedDays)
        return bodyMetricsAndProfileFrom(profile, trend, settings)
    }

    // ---- Shared DB helpers ----

    private suspend fun resolveExercise(query: String): ExerciseMatchResult {
        val exercises = ExerciseRepository.getAll()
        val counts = InsightsRepository.completedSetCountsByExercise()
        return ExerciseMatcher.resolve(query, exercises, counts)
    }

    private suspend fun loadExerciseSets(
        exerciseId: Long,
        startMillis: Long,
        targetUnit: WeightUnit,
    ): List<SetPoint> {
        val zone = TimeZone.currentSystemDefault()
        return AppDb.db.workoutSessionDetailDao()
            .getCompletedSetsForExercise(PROFILE_ID, exerciseId, startMillis)
            .map { it.toSetPoint(zone).convertedTo(targetUnit) }
    }

    // ---- Pure formatting & analytics builders (unit-tested without Room) ----

    internal fun trainingSummaryFrom(
        volume: List<WeeklyVolumePoint>,
        frequency: TrainingFrequency,
        bodyParts: List<BodyPartSlice>,
        unit: WeightUnit,
    ): ToolOutput {
        val unitText = weightUnitLabel(unit)
        val totalSets = volume.sumOf { it.setCount }
        val lines = mutableListOf<String>()

        lines += "Consistency: current streak ${frequency.currentStreakWeeks} weeks, " +
            "longest ${frequency.longestStreakWeeks} weeks; " +
            "${frequency.sessionsLast7Days} sessions in last 7 days, " +
            "${frequency.sessionsLast30Days} in last 30 days " +
            "(avg ${formatNumber(frequency.avgPerWeek12w)}/week over 12 weeks)."

        if (totalSets == 0) {
            lines += "Volume: no weighted/completed sets recorded in the last ${volume.size} weeks."
        } else {
            val latest = volume.last()
            val prior = volume.dropLast(1).takeLast(4).filter { it.sessionCount > 0 }
            val priorAvg = if (prior.isNotEmpty()) prior.map { it.totalVolume }.average() else 0.0
            val vsAvg = if (priorAvg > 0.0) {
                val pct = (latest.totalVolume - priorAvg) / priorAvg * 100.0
                val sign = if (pct >= 0) "+" else ""
                " ($sign${formatNumber(pct, 0)}% vs 4-week avg ${formatNumber(priorAvg, 0)} $unitText)"
            } else {
                ""
            }
            lines += "This 7-day window: ${formatNumber(latest.totalVolume, 0)} $unitText, " +
                "${latest.setCount} sets in ${latest.sessionCount} sessions$vsAvg."
            val activeWeeks = volume.filter { it.setCount > 0 }.takeLast(4)
            if (activeWeeks.size > 1) {
                lines += "Recent weekly volume: " + activeWeeks.joinToString("; ") {
                    "${shortDate(it.weekStartEpochDay)}: ${formatNumber(it.totalVolume, 0)} $unitText (${it.setCount} sets)"
                }
            }
        }

        if (bodyParts.isNotEmpty()) {
            val top = bodyParts.take(6).joinToString(", ") { "${it.bodyPart}: ${it.setCount} sets" }
            lines += "90-day body-part distribution (primary): $top."
        }

        return ToolOutput(
            summary = lines.joinToString("\n"),
            sources = listOf(ChatSourceRef(kind = "progress", label = "Progress overview")),
        )
    }

    internal fun personalRecordsFrom(
        prs: List<dev.balaji.moonrose.trainfree.insights.ExercisePr>,
        unit: WeightUnit,
        formulaName: String,
    ): ToolOutput {
        if (prs.isEmpty()) {
            return ToolOutput("No completed weighted sets found yet to compute personal records.")
        }
        val unitText = weightUnitLabel(unit)
        val lines = mutableListOf("Top personal records (by estimated 1RM, $formulaName formula):")
        val sources = mutableListOf<ChatSourceRef>()
        for (pr in prs) {
            lines += "- ${pr.exerciseName}: best ${formatNumber(pr.weight)} $unitText x ${pr.reps} reps " +
                "(est. 1RM ${formatNumber(pr.est1Rm)} $unitText) on ${shortDate(pr.epochDay)}"
            sources += ChatSourceRef(kind = "exercise", label = pr.exerciseName, exerciseId = pr.exerciseId)
        }
        return ToolOutput(summary = lines.joinToString("\n"), sources = sources)
    }

    internal fun exercisePrFrom(
        exercise: Exercise,
        sets: List<SetPoint>,
        settings: UserSettings,
    ): ToolOutput {
        if (sets.isEmpty()) {
            return ToolOutput(
                summary = "No completed working sets logged for ${exercise.name} yet.",
                sources = listOf(ChatSourceRef(kind = "exercise", label = exercise.name, exerciseId = exercise.id)),
            )
        }
        val unitText = weightUnitLabel(settings.weightUnit)
        val distText = distanceUnitLabel(settings.weightUnit)
        val type = ExerciseType.fromValue(exercise.exerciseType) ?: ExerciseType.STRENGTH
        val lines = mutableListOf("Personal records for ${exercise.name}:")

        val weighted = sets.filter { it.weight > 0.0 && it.reps > 0 }
        if (weighted.isNotEmpty()) {
            val best1Rm = weighted.maxBy { settings.oneRmFormula.estimate(it.weight, it.reps) }
            val est = settings.oneRmFormula.estimate(best1Rm.weight, best1Rm.reps)
            lines += "- Best est. 1RM (${settings.oneRmFormula.value}): ${formatNumber(est)} $unitText " +
                "(${formatNumber(best1Rm.weight)} $unitText x ${best1Rm.reps} reps on ${shortDate(best1Rm.epochDay)})"
            val heaviest = weighted.maxWith(compareBy<SetPoint> { it.weight }.thenBy { it.reps })
            if (heaviest != best1Rm) {
                lines += "- Heaviest set: ${formatNumber(heaviest.weight)} $unitText x ${heaviest.reps} reps " +
                    "on ${shortDate(heaviest.epochDay)}"
            }
        }
        if (type == ExerciseType.DURATION || type == ExerciseType.REPS_DURATION || type == ExerciseType.DISTANCE_DURATION) {
            sets.filter { it.duration > 0 }.maxByOrNull { it.duration }?.let { best ->
                lines += "- Longest duration: ${formatDuration(best.duration)} on ${shortDate(best.epochDay)}"
            }
        }
        if (type == ExerciseType.DISTANCE || type == ExerciseType.REPS_DISTANCE || type == ExerciseType.DISTANCE_DURATION) {
            sets.filter { it.distance > 0.0 }.maxByOrNull { it.distance }?.let { best ->
                lines += "- Longest distance: ${formatNumber(best.distance, 2)} $distText on ${shortDate(best.epochDay)}"
            }
        }
        if (lines.size == 1) {
            lines += "- ${sets.size} completed sets logged, but no weighted/timed/distance records matched."
        }
        return ToolOutput(
            summary = lines.joinToString("\n"),
            sources = listOf(ChatSourceRef(kind = "exercise", label = exercise.name, exerciseId = exercise.id)),
        )
    }

    internal fun exerciseProgressionFrom(
        exercise: Exercise,
        sets: List<SetPoint>,
        weeks: Int,
        todayEpochDay: Long,
        settings: UserSettings,
    ): ToolOutput {
        val ref = ChatSourceRef(kind = "exercise", label = exercise.name, exerciseId = exercise.id)
        if (sets.isEmpty()) {
            return ToolOutput(
                summary = "No completed sets for ${exercise.name} in the last $weeks weeks.",
                sources = listOf(ref),
            )
        }

        val unitText = weightUnitLabel(settings.weightUnit)
        val distText = distanceUnitLabel(settings.weightUnit)
        val type = ExerciseType.fromValue(exercise.exerciseType) ?: ExerciseType.STRENGTH
        val lines = mutableListOf("${exercise.name} (last $weeks weeks, ${sets.size} working sets):")

        val prog = progressionFrom(sets, exercise.name, todayEpochDay, settings.weekStart, settings.oneRmFormula)
        if (prog != null) {
            val trendText = if (prog.plateau) {
                "plateau"
            } else {
                val sign = if (prog.slopePerWeek >= 0) "+" else ""
                "$sign${formatNumber(prog.slopePerWeek)} $unitText/week"
            }
            lines += "Est. 1RM trend: $trendText."
            val recent1Rm = prog.weeklyBest1Rm.takeLast(6)
            lines += "Weekly best est. 1RM: " + recent1Rm.joinToString("; ") {
                "${shortDate(it.epochDay)}: ${formatNumber(it.value)} $unitText"
            }
            val weeklyVol = singleExerciseWeeklyVolume(sets, weeks, todayEpochDay, settings.weekStart)
                .filter { it.value > 0.0 }
                .takeLast(4)
            if (weeklyVol.isNotEmpty()) {
                lines += "Recent weekly volume: " + weeklyVol.joinToString("; ") {
                    "${shortDate(it.epochDay)}: ${formatNumber(it.value, 0)} $unitText"
                }
            }
        }

        if (type == ExerciseType.DURATION || type == ExerciseType.REPS_DURATION || type == ExerciseType.DISTANCE_DURATION) {
            val weeklyDur = singleExerciseWeeklyDuration(sets, weeks, todayEpochDay, settings.weekStart)
                .filter { it.value > 0.0 }
                .takeLast(4)
            if (weeklyDur.isNotEmpty()) {
                lines += "Recent weekly duration: " + weeklyDur.joinToString("; ") {
                    "${shortDate(it.epochDay)}: ${formatDuration(it.value.toInt())}"
                }
            }
        }
        if (type == ExerciseType.DISTANCE || type == ExerciseType.REPS_DISTANCE || type == ExerciseType.DISTANCE_DURATION) {
            val weeklyDist = singleExerciseWeeklyDistance(sets, weeks, todayEpochDay, settings.weekStart)
                .filter { it.value > 0.0 }
                .takeLast(4)
            if (weeklyDist.isNotEmpty()) {
                lines += "Recent weekly distance: " + weeklyDist.joinToString("; ") {
                    "${shortDate(it.epochDay)}: ${formatNumber(it.value, 2)} $distText"
                }
            }
        }

        // Include up to 3 most recent sessions' actual sets for concrete recall.
        val recentSessions = sets.groupBy { it.sessionKey }
            .entries
            .sortedByDescending { it.key }
            .take(3)
        if (recentSessions.isNotEmpty()) {
            lines += "Most recent sessions:"
            for ((_, sessionSets) in recentSessions) {
                val day = shortDate(sessionSets.first().epochDay)
                val formattedSets = sessionSets.joinToString(", ") { s ->
                    formatSetValues(s.weight, s.reps, s.duration, s.distance, type, unitText, distText)
                }
                lines += "- $day: $formattedSets"
            }
        }

        return ToolOutput(summary = lines.joinToString("\n"), sources = listOf(ref))
    }

    internal fun progressReviewFrom(
        sets: List<SetPoint>,
        sessions: List<RecentCompletedSessionRow>,
        journalEntries: List<JournalEntryEntity>,
        days: Int,
        todayEpochDay: Long,
        settings: UserSettings,
        zone: TimeZone,
    ): ToolOutput {
        val currentStart = todayEpochDay - days + 1L
        val priorStart = todayEpochDay - 2L * days + 1L

        val currentSessions = sessions.filter { millisToEpochDay(it.performedAt, zone) >= currentStart }
        val priorSessions = sessions.filter {
            val d = millisToEpochDay(it.performedAt, zone)
            d in priorStart until currentStart
        }
        val currentSets = sets.filter { it.epochDay >= currentStart }
        val priorSets = sets.filter { it.epochDay in priorStart until currentStart }

        if (currentSessions.isEmpty() && currentSets.isEmpty() && journalEntries.isEmpty()) {
            return ToolOutput(
                summary = "No workouts logged in the last $days days (prior period had ${priorSessions.size} sessions).",
                sources = listOf(ChatSourceRef(kind = "progress", label = "Progress overview")),
            )
        }

        val unitText = weightUnitLabel(settings.weightUnit)
        val distText = distanceUnitLabel(settings.weightUnit)
        val lines = mutableListOf("Progress review (last $days days vs prior $days days):")

        val curVol = currentSets.sumOf { it.weight * it.reps }
        val prevVol = priorSets.sumOf { it.weight * it.reps }
        val volDelta = pctDeltaLabel(curVol, prevVol)
        val sessDelta = pctDeltaLabel(currentSessions.size.toDouble(), priorSessions.size.toDouble())
        val avgMin = currentSessions
            .map { ((it.completedAt - it.performedAt) / 60_000L).coerceAtLeast(0L) }
            .filter { it > 0L }
            .takeIf { it.isNotEmpty() }
            ?.average()
        val durNote = avgMin?.let { ", avg ${formatNumber(it, 0)} min/workout" }.orEmpty()

        lines += "- Activity: ${currentSessions.size} sessions (vs ${priorSessions.size}$sessDelta), " +
            "${currentSets.size} working sets (vs ${priorSets.size})$durNote."
        if (curVol > 0.0 || prevVol > 0.0) {
            lines += "- Volume: ${formatNumber(curVol, 0)} $unitText (vs ${formatNumber(prevVol, 0)} $unitText$volDelta)."
        }

        val activeWeightedNames = currentSets
            .filter { it.weight > 0.0 && it.reps > 0 }
            .map { it.exerciseName }
            .distinct()
        val progressions = activeWeightedNames.mapNotNull { name ->
            progressionFrom(sets, name, todayEpochDay, settings.weekStart, settings.oneRmFormula)
        }.filter { it.weeklyBest1Rm.size >= 2 }

        val progressing = progressions
            .filter { !it.plateau && it.slopePerWeek > 0.0 }
            .sortedByDescending { it.slopePerWeek }
            .take(4)
        val stalledOrDipping = progressions
            .filter { it.plateau || it.slopePerWeek <= 0.0 }
            .sortedByDescending { p ->
                currentSets.filter { it.exerciseId == p.exerciseId }.sumOf { it.weight * it.reps }
            }
            .take(4)

        val sources = mutableListOf(ChatSourceRef(kind = "progress", label = "Progress overview"))
        val seenExerciseIds = mutableSetOf<Long>()

        if (progressing.isNotEmpty()) {
            lines += "- Progressing lifts: " + progressing.joinToString("; ") { p ->
                val latest = p.weeklyBest1Rm.last().value
                if (seenExerciseIds.add(p.exerciseId)) {
                    sources += ChatSourceRef(kind = "exercise", label = p.exerciseName, exerciseId = p.exerciseId)
                }
                "${p.exerciseName} (+${formatNumber(p.slopePerWeek)} $unitText/wk, est. 1RM ${formatNumber(latest)} $unitText)"
            } + "."
        }
        if (stalledOrDipping.isNotEmpty()) {
            lines += "- Plateaued/dipping lifts: " + stalledOrDipping.joinToString("; ") { p ->
                val latest = p.weeklyBest1Rm.last().value
                val trend = if (p.plateau) "plateau" else "${formatNumber(p.slopePerWeek)} $unitText/wk"
                if (seenExerciseIds.add(p.exerciseId)) {
                    sources += ChatSourceRef(kind = "exercise", label = p.exerciseName, exerciseId = p.exerciseId)
                }
                "${p.exerciseName} ($trend, est. 1RM ${formatNumber(latest)} $unitText)"
            } + "."
        }

        val cardioSummary = currentSets
            .filter { (it.duration > 0 || it.distance > 0.0) && (it.weight <= 0.0 || it.reps <= 0) }
            .groupBy { it.exerciseName }
            .entries
            .sortedByDescending { it.value.size }
            .take(3)
            .map { (name, exSets) ->
                val totalDur = exSets.sumOf { it.duration }
                val totalDist = exSets.sumOf { it.distance }
                val metrics = listOfNotNull(
                    totalDist.takeIf { it > 0.0 }?.let { "${formatNumber(it, 2)} $distText" },
                    totalDur.takeIf { it > 0 }?.let { formatDuration(it) },
                ).joinToString(" / ")
                "$name ($metrics)"
            }
        if (cardioSummary.isNotEmpty()) {
            lines += "- Cardio/timed: ${cardioSummary.joinToString("; ")}."
        }

        val bodyParts = bodyPartBreakdownFrom(currentSets)
        if (bodyParts.isNotEmpty()) {
            val trainedMap = bodyParts.associate { it.bodyPart to it.setCount }
            lines += "- Muscle sets (last $days days): " +
                bodyParts.joinToString(", ") { "${it.bodyPart}: ${it.setCount}" } + "."
            val untrained = (ExerciseCatalog.BODY_PARTS - "full body").filter { (trainedMap[it] ?: 0) == 0 }
            if (untrained.isNotEmpty()) {
                lines += "- Untrained primary muscles in window: ${untrained.joinToString(", ")}."
            }
        }

        if (journalEntries.isNotEmpty()) {
            val notes = journalEntries.take(3).joinToString("; ") { entry ->
                "${shortDate(entry.epochDay)}: \"${clipNote(entry.content, 90)}\""
            }
            lines += "- Recent journal notes: $notes."
        }

        return ToolOutput(summary = lines.joinToString("\n"), sources = sources)
    }

    internal fun recentWorkoutsFrom(
        sessions: List<RecentCompletedSessionRow>,
        details: List<SessionDetailWithExerciseRow>,
        zone: TimeZone,
        unit: WeightUnit,
        journalByDay: Map<Long, String> = emptyMap(),
    ): ToolOutput {
        val unitText = weightUnitLabel(unit)
        val distText = distanceUnitLabel(unit)
        val detailsBySession = details.groupBy { it.workoutSessionId }
        val lines = mutableListOf("Recent completed workouts (most recent first):")
        val sources = mutableListOf<ChatSourceRef>()
        val seenDays = mutableSetOf<Long>()
        val notedDays = mutableSetOf<Long>()

        for (session in sessions) {
            val epochDay = millisToEpochDay(session.performedAt, zone)
            val dateLabel = shortDate(epochDay)
            val title = session.title.trim().takeIf { it.isNotEmpty() }
                ?: session.routineName?.trim()?.takeIf { it.isNotEmpty() }
                ?: dev.balaji.moonrose.trainfree.db.defaultWorkoutTitle(epochDay)
            val durationMin = ((session.completedAt - session.performedAt) / 60_000L).coerceAtLeast(0L)
            val durText = if (durationMin > 0L) " (${durationMin} min)" else ""

            val sessionDetails = detailsBySession[session.id].orEmpty()
            val exerciseText = if (sessionDetails.isEmpty()) {
                "no working sets logged"
            } else {
                groupSessionExercises(sessionDetails).joinToString("; ") { (name, rows) ->
                    val type = ExerciseType.fromValue(rows.first().exerciseType) ?: ExerciseType.STRENGTH
                    val setSummary = rows.joinToString(", ") { r ->
                        val base = formatSetValues(
                            weight = r.actualWeight?.let { r.weightUnit.convertWeight(it, unit) } ?: 0.0,
                            reps = r.actualReps ?: 0,
                            duration = r.actualDuration ?: 0,
                            distance = r.actualDistance?.let { r.weightUnit.convertDistance(it, unit) } ?: 0.0,
                            type = type,
                            unitText = unitText,
                            distText = distText,
                        )
                        if (r.setType == SetType.DROP) "$base [drop]" else base
                    }
                    "$name: $setSummary"
                }
            }
            val noteSuffix = if (notedDays.add(epochDay)) {
                journalByDay[epochDay]?.trim()?.takeIf { it.isNotEmpty() }?.let { " | Note: \"${clipNote(it, 90)}\"" }.orEmpty()
            } else {
                ""
            }
            lines += "- $dateLabel · $title$durText — $exerciseText$noteSuffix"
            if (seenDays.add(epochDay)) {
                sources += ChatSourceRef(kind = "day", label = "$dateLabel · $title", epochDay = epochDay)
            }
        }

        return ToolOutput(summary = lines.joinToString("\n"), sources = sources)
    }

    internal fun programsAndRoutinesFrom(
        programs: List<Program>,
        routines: List<Routine>,
        workSetsByRoutine: Map<Long, List<WorkSetEntity>>,
        detailsByWorkSet: Map<Long, List<WorkSetDetailEntity>>,
        exercisesById: Map<Long, Exercise>,
        lastPerformed: List<RoutineLastPerformedRow>,
        unit: WeightUnit,
        zone: TimeZone,
        includeEmptyPrograms: Boolean = false,
    ): ToolOutput {
        val unitText = weightUnitLabel(unit)
        val distText = distanceUnitLabel(unit)
        val lastDayByRoutine = lastPerformed.associate {
            it.routineId to millisToEpochDay(it.lastPerformedAt, zone)
        }

        val lines = mutableListOf("Programs and routines:")
        val sources = mutableListOf<ChatSourceRef>()
        val byProgram = routines.groupBy { it.programId }

        for (program in programs) {
            val programRoutines = byProgram[program.id].orEmpty()
            if (programRoutines.isEmpty() && !includeEmptyPrograms) continue
            lines += "Program: ${program.name}"
            sources += ChatSourceRef(kind = "program", label = program.name)
            if (programRoutines.isEmpty()) {
                lines += "- (no routines yet)"
                continue
            }
            for (routine in programRoutines.sortedBy { it.position }) {
                val lastText = lastDayByRoutine[routine.id]?.let { "last done ${shortDate(it)}" } ?: "never performed"
                val workSets = workSetsByRoutine[routine.id].orEmpty()
                if (workSets.isEmpty()) {
                    lines += "- ${routine.name} ($lastText): no exercises"
                    continue
                }
                val exSummary = workSets.joinToString("; ") { ws ->
                    val exercise = exercisesById[ws.exerciseId]
                    val name = exercise?.name ?: "Exercise #${ws.exerciseId}"
                    val details = detailsByWorkSet[ws.id].orEmpty().filter { it.setType != SetType.WARMUP }
                    val allDetails = detailsByWorkSet[ws.id].orEmpty()
                    val workingCount = details.size
                    val type = ExerciseType.fromValue(exercise?.exerciseType) ?: ExerciseType.STRENGTH
                    val formattedTargets = details.map { d ->
                        formatSetValues(
                            weight = d.targetWeight?.let { d.weightUnit.convertWeight(it, unit) } ?: 0.0,
                            reps = d.targetReps ?: 0,
                            duration = d.targetDuration ?: 0,
                            distance = d.targetDistance?.let { d.weightUnit.convertDistance(it, unit) } ?: 0.0,
                            type = type,
                            unitText = unitText,
                            distText = distText,
                        )
                    }.filter { it.isNotEmpty() }
                    val targetPreview = when {
                        formattedTargets.isEmpty() -> ""
                        formattedTargets.distinct().size == 1 -> " (target ${formattedTargets.first()})"
                        else -> " (targets ${formattedTargets.joinToString(" / ")})"
                    }
                    val countLabel = if (workingCount > 0) "$workingCount sets" else "${allDetails.size} warmup/empty"
                    val bodyPartSuffix = exercise?.bodyPart?.let { " [$it]" }.orEmpty()
                    "$name $countLabel$targetPreview$bodyPartSuffix"
                }
                lines += "- ${routine.name} ($lastText): $exSummary"
            }
        }

        return ToolOutput(summary = lines.joinToString("\n"), sources = sources)
    }

    internal fun createExerciseResultToOutput(result: AiCreateExerciseResult): ToolOutput = when (result) {
        is AiCreateExerciseResult.Invalid -> ToolOutput(result.message)
        is AiCreateExerciseResult.AlreadyExists -> {
            val ex = result.exercise
            val meta = exerciseMetadataSummary(ex)
            ToolOutput(
                summary = "Exercise \"${ex.name}\" already exists in your catalog ($meta).",
                sources = listOf(ChatSourceRef(kind = "exercise", label = ex.name, exerciseId = ex.id)),
            )
        }
        is AiCreateExerciseResult.Created -> {
            val ex = result.exercise
            val meta = exerciseMetadataSummary(ex)
            ToolOutput(
                summary = "Created exercise \"${ex.name}\" ($meta).",
                sources = listOf(ChatSourceRef(kind = "exercise", label = ex.name, exerciseId = ex.id)),
            )
        }
    }

    internal fun createProgramResultToOutput(
        result: AiCreateProgramResult,
        unit: WeightUnit,
    ): ToolOutput = when (result) {
        is AiCreateProgramResult.Invalid -> ToolOutput(result.message)
        is AiCreateProgramResult.Created -> {
            val lines = mutableListOf(
                "Created program \"${result.program.name}\" with ${result.routines.size} routines:",
            )
            for (r in result.routines) {
                lines += "- ${r.routine.name}: ${formatRoutineExercisesSummary(r.exercises, unit)}"
            }
            if (result.createdExercises.isNotEmpty()) {
                val createdList = result.createdExercises.joinToString(", ") {
                    "${it.name} (${it.bodyPart ?: "full body"}${it.equipment?.let { eq -> ", $eq" } ?: ""})"
                }
                lines += "New exercises added to catalog: $createdList."
            }
            val sources = buildList {
                add(ChatSourceRef(kind = "program", label = result.program.name, programId = result.program.id))
                result.createdExercises.take(4).forEach { ex ->
                    add(ChatSourceRef(kind = "exercise", label = ex.name, exerciseId = ex.id))
                }
            }
            ToolOutput(summary = lines.joinToString("\n"), sources = sources)
        }
    }

    internal fun routineAddResultToOutput(
        result: AiRoutineAddResult,
        unit: WeightUnit,
    ): ToolOutput = when (result) {
        is AiRoutineAddResult.Invalid -> ToolOutput(result.message)
        is AiRoutineAddResult.AmbiguousProgram -> ToolOutput(
            "Multiple programs match: ${result.candidates.joinToString(", ")}. Specify the exact program name.",
        )
        is AiRoutineAddResult.ProgramRequired -> ToolOutput(
            "You have multiple programs (${result.candidates.joinToString(", ")}). Specify which program to add this routine to.",
        )
        is AiRoutineAddResult.Added -> {
            val actionVerb = if (result.programCreated) {
                "Created program \"${result.program.name}\" with routine \"${result.routine.name}\""
            } else {
                "Added routine \"${result.routine.name}\" to program \"${result.program.name}\""
            }
            val lines = mutableListOf(
                "$actionVerb: ${formatRoutineExercisesSummary(result.exercises, unit)}.",
            )
            if (result.createdExercises.isNotEmpty()) {
                val createdList = result.createdExercises.joinToString(", ") { it.name }
                lines += "New exercises added to catalog: $createdList."
            }
            val sources = buildList {
                add(
                    ChatSourceRef(
                        kind = "program",
                        label = result.program.name,
                        programId = result.program.id,
                        routineId = result.routine.id,
                    ),
                )
                result.createdExercises.take(4).forEach { ex ->
                    add(ChatSourceRef(kind = "exercise", label = ex.name, exerciseId = ex.id))
                }
            }
            ToolOutput(summary = lines.joinToString("\n"), sources = sources)
        }
    }

    internal fun routineUpdateResultToOutput(
        result: AiRoutineUpdateResult,
        unit: WeightUnit,
    ): ToolOutput = when (result) {
        is AiRoutineUpdateResult.Invalid -> ToolOutput(result.message)
        is AiRoutineUpdateResult.NotFound -> ToolOutput(
            "No routine matching \"${result.query}\" was found.",
        )
        is AiRoutineUpdateResult.Ambiguous -> ToolOutput(
            "Multiple routines match \"${result.query}\": ${result.candidates.joinToString(", ")}. Specify the program name.",
        )
        is AiRoutineUpdateResult.Updated -> {
            val changes = buildList {
                result.renamedFromTo?.let { (oldName, newName) -> add("renamed \"$oldName\" to \"$newName\"") }
                if (result.replaced.isNotEmpty()) {
                    add("replaced " + result.replaced.joinToString(", ") { (a, b) -> "$a -> $b" })
                }
                if (result.updated.isNotEmpty()) add("updated ${result.updated.joinToString(", ")}")
                if (result.removed.isNotEmpty()) add("removed ${result.removed.joinToString(", ")}")
                if (result.added.isNotEmpty()) add("added ${result.added.joinToString(", ")}")
            }
            val lines = mutableListOf<String>()
            if (changes.isEmpty()) {
                lines += "No matching changes were applied to \"${result.routine.name}\" (${result.program.name})."
            } else {
                lines += "Updated \"${result.routine.name}\" in \"${result.program.name}\": ${changes.joinToString("; ")}."
            }
            if (result.notFoundInRoutine.isNotEmpty()) {
                lines += "Not found in routine: ${result.notFoundInRoutine.joinToString(", ")}."
            }
            if (result.createdExercises.isNotEmpty()) {
                lines += "New exercises added to catalog: ${result.createdExercises.joinToString(", ") { it.name }}."
            }
            lines += if (result.finalExercises.isEmpty()) {
                "Routine now has no exercises."
            } else {
                "Updated routine: ${formatRoutineExercisesSummary(result.finalExercises, unit)}."
            }
            val sources = buildList {
                add(
                    ChatSourceRef(
                        kind = "program",
                        label = result.program.name,
                        programId = result.program.id,
                        routineId = result.routine.id,
                    ),
                )
                result.createdExercises.take(4).forEach { ex ->
                    add(ChatSourceRef(kind = "exercise", label = ex.name, exerciseId = ex.id))
                }
            }
            ToolOutput(summary = lines.joinToString("\n"), sources = sources)
        }
    }

    private fun exerciseMetadataSummary(ex: Exercise): String = listOfNotNull(
        ex.bodyPart?.let { "primary: $it" },
        ex.otherBodyParts.takeIf { it.isNotEmpty() }?.let { "secondary: ${it.joinToString(", ")}" },
        ex.equipment?.let { "equipment: $it" },
        ex.exerciseType?.let { "type: $it" },
    ).joinToString(", ")

    private fun formatRoutineExercisesSummary(
        exercises: List<Pair<Exercise, List<WorkSetDetailEntity>>>,
        unit: WeightUnit,
    ): String {
        val unitText = weightUnitLabel(unit)
        val distText = distanceUnitLabel(unit)
        return exercises.joinToString("; ") { (exercise, details) ->
            val working = details.filter { it.setType != SetType.WARMUP }
            val type = ExerciseType.fromValue(exercise.exerciseType) ?: ExerciseType.STRENGTH
            val preview = working.firstOrNull()?.let { d ->
                formatSetValues(
                    weight = d.targetWeight?.let { d.weightUnit.convertWeight(it, unit) } ?: 0.0,
                    reps = d.targetReps ?: 0,
                    duration = d.targetDuration ?: 0,
                    distance = d.targetDistance?.let { d.weightUnit.convertDistance(it, unit) } ?: 0.0,
                    type = type,
                    unitText = unitText,
                    distText = distText,
                )
            }.orEmpty()
            val setsLabel = if (preview.isNotEmpty()) "${working.size}x $preview" else "${working.size} sets"
            "${exercise.name} ($setsLabel)"
        }
    }

    internal fun exerciseSearchFrom(
        matches: List<Exercise>,
        trainedSetCounts: Map<Long, Int>,
    ): ToolOutput {
        if (matches.isEmpty()) {
            return ToolOutput("No matching exercises found in the catalog.")
        }
        val lines = mutableListOf("Matching exercises (${matches.size}):")
        val sources = mutableListOf<ChatSourceRef>()
        for (ex in matches) {
            val meta = listOfNotNull(
                ex.bodyPart?.let { "primary: $it" },
                ex.otherBodyParts.takeIf { it.isNotEmpty() }?.let { "secondary: ${it.joinToString(", ")}" },
                ex.equipment?.let { "equipment: $it" },
                ex.exerciseType?.let { "type: $it" },
                trainedSetCounts[ex.id]?.takeIf { it > 0 }?.let { "$it logged sets" },
            ).joinToString(" | ")
            lines += "- ${ex.name} ($meta)"
            sources += ChatSourceRef(kind = "exercise", label = ex.name, exerciseId = ex.id)
        }
        return ToolOutput(summary = lines.joinToString("\n"), sources = sources)
    }

    internal fun bodyMetricsAndProfileFrom(
        profile: Profile?,
        trend: BodyWeightTrend?,
        settings: UserSettings,
    ): ToolOutput {
        val unitText = weightUnitLabel(settings.weightUnit)
        val lines = mutableListOf<String>()

        if (profile != null) {
            val parts = buildList {
                profile.age?.let { add("age: $it") }
                profile.gender?.let { add("gender: $it") }
                profile.weight?.let {
                    add("current weight: ${formatNumber(settings.weightUnit.fromKilograms(it))} $unitText")
                }
                profile.height?.let {
                    val heightText = when (settings.weightUnit) {
                        WeightUnit.METRIC -> "${formatNumber(it, 0)} cm"
                        WeightUnit.IMPERIAL -> HeightConversion.cmToFeetInches(it).let { fi ->
                            "${fi.feet} ft ${fi.inches} in"
                        }
                    }
                    add("height: $heightText")
                }
            }
            if (parts.isNotEmpty()) {
                lines += "Profile: ${parts.joinToString(", ")}."
            } else {
                lines += "Profile: configured."
            }

            val weightKg = trend?.latest?.let { settings.weightUnit.toKilograms(it) } ?: profile.weight
            val heightCm = profile.height
            if (weightKg != null && weightKg > 0.0 && heightCm != null && heightCm > 0.0) {
                val bmi = settings.bmiFormula.bmi(weightKg, heightCm)
                val category = settings.bmiClassification.of(bmi).name.lowercase().replace('_', ' ')
                lines += "BMI (${settings.bmiFormula.value}, ${settings.bmiClassification.value}): " +
                    "${formatNumber(bmi)} ($category)."
            }
        } else {
            lines += "Profile: not configured."
        }

        if (trend != null) {
            val sign = if (trend.change30Days >= 0) "+" else ""
            lines += "Body-weight trend (${trend.points.size} entries): latest ${formatNumber(trend.latest)} $unitText " +
                "on ${shortDate(trend.latestEpochDay)}, " +
                "30-day change $sign${formatNumber(trend.change30Days)} $unitText, " +
                "slope ${formatNumber(trend.slopePerWeek, 2)} $unitText/week."
        } else {
            lines += "Body-weight log: no entries recorded in this window."
        }

        return ToolOutput(
            summary = lines.joinToString("\n"),
            sources = listOf(ChatSourceRef(kind = "progress", label = "Body metrics")),
        )
    }

    // ---- Formatting helpers ----

    private fun ambiguousOutput(query: String, candidates: List<Exercise>): ToolOutput {
        val names = candidates.joinToString(", ") { it.name }
        return ToolOutput(
            summary = "Multiple exercises match \"$query\": $names. Ask the user which one they mean or check each by exact name.",
            sources = candidates.map { ChatSourceRef(kind = "exercise", label = it.name, exerciseId = it.id) },
        )
    }

    private fun groupSessionExercises(
        details: List<SessionDetailWithExerciseRow>,
    ): List<Pair<String, List<SessionDetailWithExerciseRow>>> {
        val grouped = mutableListOf<Pair<String, MutableList<SessionDetailWithExerciseRow>>>()
        var currentExerciseId = -1L
        for (row in details) {
            if (row.exerciseId != currentExerciseId) {
                currentExerciseId = row.exerciseId
                grouped += row.exerciseName to mutableListOf(row)
            } else {
                grouped.last().second += row
            }
        }
        return grouped
    }

    private fun formatSetValues(
        weight: Double,
        reps: Int,
        duration: Int,
        distance: Double,
        type: ExerciseType,
        unitText: String,
        distText: String,
    ): String {
        val parts = mutableListOf<String>()
        if (weight > 0.0) parts += "${formatNumber(weight)} $unitText"
        when (type) {
            ExerciseType.STRENGTH -> if (reps > 0) parts += "${reps}r"
            ExerciseType.DURATION -> if (duration > 0) parts += formatDuration(duration)
            ExerciseType.DISTANCE -> if (distance > 0.0) parts += "${formatNumber(distance, 2)} $distText"
            ExerciseType.REPS_DURATION -> {
                if (reps > 0) parts += "${reps}r"
                if (duration > 0) parts += formatDuration(duration)
            }
            ExerciseType.REPS_DISTANCE -> {
                if (reps > 0) parts += "${reps}r"
                if (distance > 0.0) parts += "${formatNumber(distance, 2)} $distText"
            }
            ExerciseType.DISTANCE_DURATION -> {
                if (distance > 0.0) parts += "${formatNumber(distance, 2)} $distText"
                if (duration > 0) parts += formatDuration(duration)
            }
        }
        return when {
            parts.size >= 2 && weight > 0.0 && reps > 0 ->
                "${parts.first()} x ${parts.drop(1).joinToString(" / ")}"
            else -> parts.joinToString(" / ")
        }
    }

    private fun weightUnitLabel(unit: WeightUnit): String = when (unit) {
        WeightUnit.METRIC -> "kg"
        WeightUnit.IMPERIAL -> "lb"
    }

    private fun distanceUnitLabel(unit: WeightUnit): String = when (unit) {
        WeightUnit.METRIC -> "km"
        WeightUnit.IMPERIAL -> "mi"
    }

    internal fun formatDuration(seconds: Int): String {
        val totalMinutes = seconds / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val remSeconds = seconds % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            minutes > 0 && remSeconds > 0 -> "${minutes}m ${remSeconds}s"
            minutes > 0 -> "${minutes} min"
            else -> "${seconds}s"
        }
    }

    internal fun parseDateEpochDay(raw: String, todayEpochDay: Long = WeekMath.todayEpochDay()): Long? {
        val trimmed = raw.trim().lowercase()
        return when (trimmed) {
            "" -> null
            "today" -> todayEpochDay
            "yesterday" -> todayEpochDay - 1L
            "tomorrow" -> todayEpochDay + 1L
            else -> trimmed.toLongOrNull() ?: runCatching { LocalDate.parse(trimmed).toEpochDays() }.getOrNull()
        }
    }

    internal fun isoDate(epochDay: Long): String = LocalDate.fromEpochDays(epochDay).toString()

    private fun pctDeltaLabel(current: Double, previous: Double): String {
        if (previous <= 0.0) return ""
        val pct = (current - previous) / previous * 100.0
        val sign = if (pct >= 0.0) "+" else ""
        return " ($sign${formatNumber(pct, 0)}%)"
    }

    private fun clipNote(text: String, maxChars: Int = 120): String {
        val singleLine = text.replace('\n', ' ').replace('\r', ' ').trim()
        return if (singleLine.length <= maxChars) singleLine else singleLine.take(maxChars - 3).trimEnd() + "..."
    }

    internal fun shortDate(epochDay: Long): String {
        val parts = WeekMath.dayParts(epochDay)
        return "${MONTH_ABBREVIATIONS[parts.monthOfYear - 1]} ${parts.dayOfMonth}, ${parts.year}"
    }

    private val MONTH_ABBREVIATIONS =
        listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
}
