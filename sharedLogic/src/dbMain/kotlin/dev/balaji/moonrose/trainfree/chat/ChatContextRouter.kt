package dev.balaji.moonrose.trainfree.chat

import dev.balaji.moonrose.trainfree.calendar.WeekMath
import dev.balaji.moonrose.trainfree.db.ExerciseRepository
import dev.balaji.moonrose.trainfree.db.InsightsRepository
import dev.balaji.moonrose.trainfree.db.ProgramRepository
import dev.balaji.moonrose.trainfree.model.Exercise
import kotlinx.coroutines.flow.first

/** Port over [ChatToolbox] so [ChatContextRouter] and [GeminiNanoToolCodec] can be unit-tested without Room. */
internal interface ChatToolboxPort {
    suspend fun getTrainingSummary(weeks: Int = 8): ToolOutput
    suspend fun getProgressReview(days: Int = 30): ToolOutput
    suspend fun getPersonalRecords(exerciseQuery: String? = null, limit: Int = 5): ToolOutput
    suspend fun getExerciseProgression(exerciseQuery: String, weeks: Int = 12): ToolOutput
    suspend fun getRecentWorkouts(
        limit: Int = 5,
        routineQuery: String? = null,
        days: Int? = null,
        exerciseQuery: String? = null,
        startDate: String? = null,
        endDate: String? = null,
    ): ToolOutput
    suspend fun getWorkoutsForDay(epochDay: Long? = null, date: String? = null): ToolOutput
    suspend fun getProgramsAndRoutines(routineQuery: String? = null, programQuery: String? = null): ToolOutput
    suspend fun searchExercises(
        query: String? = null,
        bodyPart: String? = null,
        equipment: String? = null,
        limit: Int = 8,
    ): ToolOutput
    suspend fun getBodyMetricsAndProfile(days: Int = 90): ToolOutput
    suspend fun createExercise(
        name: String,
        bodyPart: String,
        equipment: String? = null,
        exerciseType: String? = null,
        otherBodyParts: List<String> = emptyList(),
    ): ToolOutput
    suspend fun createProgram(
        programName: String,
        routines: List<AiRoutineInput>,
    ): ToolOutput
    suspend fun addRoutineToProgram(
        routineName: String,
        exercises: List<AiExerciseInput>,
        programName: String? = null,
    ): ToolOutput
    suspend fun updateRoutine(
        routineName: String,
        programName: String? = null,
        newRoutineName: String? = null,
        addExercises: List<AiExerciseInput> = emptyList(),
        replaceExercises: List<AiReplaceExerciseInput> = emptyList(),
        updateExercises: List<AiExerciseInput> = emptyList(),
        removeExercises: List<String> = emptyList(),
    ): ToolOutput
}

internal object LiveChatToolboxPort : ChatToolboxPort {
    override suspend fun getTrainingSummary(weeks: Int) = ChatToolbox.getTrainingSummary(weeks)
    override suspend fun getProgressReview(days: Int) = ChatToolbox.getProgressReview(days)
    override suspend fun getPersonalRecords(exerciseQuery: String?, limit: Int) =
        ChatToolbox.getPersonalRecords(exerciseQuery, limit)
    override suspend fun getExerciseProgression(exerciseQuery: String, weeks: Int) =
        ChatToolbox.getExerciseProgression(exerciseQuery, weeks)
    override suspend fun getRecentWorkouts(
        limit: Int,
        routineQuery: String?,
        days: Int?,
        exerciseQuery: String?,
        startDate: String?,
        endDate: String?,
    ) = ChatToolbox.getRecentWorkouts(limit, routineQuery, days, exerciseQuery, startDate, endDate)
    override suspend fun getWorkoutsForDay(epochDay: Long?, date: String?) =
        ChatToolbox.getWorkoutsForDay(epochDay, date)
    override suspend fun getProgramsAndRoutines(routineQuery: String?, programQuery: String?) =
        ChatToolbox.getProgramsAndRoutines(routineQuery, programQuery)
    override suspend fun searchExercises(
        query: String?,
        bodyPart: String?,
        equipment: String?,
        limit: Int,
    ) = ChatToolbox.searchExercises(query, bodyPart, equipment, limit)
    override suspend fun getBodyMetricsAndProfile(days: Int) =
        ChatToolbox.getBodyMetricsAndProfile(days)
    override suspend fun createExercise(
        name: String,
        bodyPart: String,
        equipment: String?,
        exerciseType: String?,
        otherBodyParts: List<String>,
    ) = ChatToolbox.createExercise(name, bodyPart, equipment, exerciseType, otherBodyParts)
    override suspend fun createProgram(programName: String, routines: List<AiRoutineInput>) =
        ChatToolbox.createProgram(programName, routines)
    override suspend fun addRoutineToProgram(
        routineName: String,
        exercises: List<AiExerciseInput>,
        programName: String?,
    ) = ChatToolbox.addRoutineToProgram(routineName, exercises, programName)
    override suspend fun updateRoutine(
        routineName: String,
        programName: String?,
        newRoutineName: String?,
        addExercises: List<AiExerciseInput>,
        replaceExercises: List<AiReplaceExerciseInput>,
        updateExercises: List<AiExerciseInput>,
        removeExercises: List<String>,
    ) = ChatToolbox.updateRoutine(
        routineName = routineName,
        programName = programName,
        newRoutineName = newRoutineName,
        addExercises = addExercises,
        replaceExercises = replaceExercises,
        updateExercises = updateExercises,
        removeExercises = removeExercises,
    )
}

internal data class RoutedPlan(
    val exerciseQuery: String? = null,
    val routineQuery: String? = null,
    val programQuery: String? = null,
    val bodyPart: String? = null,
    val equipment: String? = null,
    val wantsPr: Boolean = false,
    val wantsProgression: Boolean = false,
    val wantsForm: Boolean = false,
    val wantsSubstitutes: Boolean = false,
    val wantsRecentWorkouts: Boolean = false,
    val wantsPrograms: Boolean = false,
    val wantsBodyMetrics: Boolean = false,
    val wantsSummary: Boolean = false,
    val wantsProgressReview: Boolean = false,
    val specificDayOffset: Int? = null,
)

/**
 * Deterministic intent & entity pre-fetcher used on Android devices whose
 * Gemini Nano build lacks `Feature.FUNCTION_CALLING` (and to seed Day/Exercise
 * detail launches with instant grounded context).
 */
internal class ChatContextRouter(
    private val toolbox: ChatToolboxPort = LiveChatToolboxPort,
    private val loadExercises: suspend () -> List<Exercise> = { ExerciseRepository.getAll() },
    private val loadTrainedCounts: suspend () -> Map<Long, Int> = { InsightsRepository.completedSetCountsByExercise() },
    private val loadRoutineNames: suspend () -> List<String> = { ProgramRepository.routines.first().map { it.name } },
    private val loadProgramNames: suspend () -> List<String> = { ProgramRepository.programs.first().map { it.name } },
) {
    suspend fun route(
        message: String,
        seedContext: ChatSeedContext = ChatSeedContext(),
        maxContextChars: Int = MAX_PRELOADED_CHARS,
    ): ToolOutput {
        val exercises = loadExercises()
        val counts = loadTrainedCounts()
        val routineNames = loadRoutineNames()
        val programNames = loadProgramNames()
        val plan = analyzePrompt(message, seedContext, exercises, counts, routineNames, programNames)
        return executePlan(plan, seedContext, exercises, maxContextChars)
    }

    internal suspend fun executePlan(
        plan: RoutedPlan,
        seedContext: ChatSeedContext,
        exercises: List<Exercise>,
        maxContextChars: Int = MAX_PRELOADED_CHARS,
    ): ToolOutput {
        val outputs = mutableListOf<ToolOutput>()

        if (seedContext.kind == "day" && seedContext.epochDay > 0L) {
            outputs += toolbox.getWorkoutsForDay(epochDay = seedContext.epochDay)
        } else if (plan.specificDayOffset != null) {
            outputs += toolbox.getWorkoutsForDay(epochDay = WeekMath.todayEpochDay() + plan.specificDayOffset)
        }

        val ex = plan.exerciseQuery
        if (ex != null) {
            if (plan.wantsPr) outputs += toolbox.getPersonalRecords(exerciseQuery = ex, limit = 5)
            if (plan.wantsProgression || (!plan.wantsPr && !plan.wantsForm && !plan.wantsSubstitutes)) {
                outputs += toolbox.getExerciseProgression(exerciseQuery = ex, weeks = 12)
            }
            if (plan.wantsForm || (seedContext.kind == "exercise" && !plan.wantsSubstitutes)) {
                outputs += toolbox.searchExercises(query = ex, limit = 1)
            }
            if (plan.wantsSubstitutes) {
                val resolved = (ExerciseMatcher.resolve(ex, exercises) as? ExerciseMatchResult.Matched)?.exercise
                val subBodyPart = plan.bodyPart ?: resolved?.bodyPart
                outputs += toolbox.searchExercises(
                    bodyPart = subBodyPart,
                    equipment = plan.equipment,
                    limit = 6,
                )
            }
        } else {
            if (plan.wantsPr) outputs += toolbox.getPersonalRecords(exerciseQuery = null, limit = 5)
            if (plan.wantsSubstitutes || plan.bodyPart != null || plan.equipment != null) {
                outputs += toolbox.searchExercises(
                    bodyPart = plan.bodyPart,
                    equipment = plan.equipment,
                    limit = 6,
                )
            }
        }

        if (plan.wantsRecentWorkouts && seedContext.kind != "day" && plan.specificDayOffset == null) {
            outputs += toolbox.getRecentWorkouts(
                limit = 4,
                routineQuery = plan.routineQuery,
                exerciseQuery = ex?.takeIf { plan.wantsProgressReview },
            )
        }
        if (plan.wantsPrograms || plan.programQuery != null || (plan.routineQuery != null && !plan.wantsRecentWorkouts)) {
            outputs += toolbox.getProgramsAndRoutines(
                routineQuery = plan.routineQuery,
                programQuery = plan.programQuery,
            )
        }
        if (plan.wantsBodyMetrics) {
            outputs += toolbox.getBodyMetricsAndProfile(days = 90)
        }
        if (plan.wantsProgressReview && ex == null) {
            outputs += toolbox.getProgressReview(days = 30)
            if (!plan.wantsRecentWorkouts && seedContext.kind != "day") {
                outputs += toolbox.getRecentWorkouts(limit = 4)
            }
        } else if (plan.wantsSummary) {
            outputs += toolbox.getTrainingSummary(weeks = 8)
        }

        if (outputs.isEmpty() && !plan.wantsForm) {
            when (seedContext.kind) {
                "exercise" -> if (seedContext.exerciseName.isNotBlank()) {
                    outputs += toolbox.getExerciseProgression(seedContext.exerciseName, weeks = 12)
                    outputs += toolbox.getPersonalRecords(seedContext.exerciseName, limit = 3)
                }
                "day" -> if (seedContext.epochDay > 0L) {
                    outputs += toolbox.getWorkoutsForDay(seedContext.epochDay)
                }
                else -> {
                    outputs += toolbox.getTrainingSummary(weeks = 8)
                    outputs += toolbox.getRecentWorkouts(limit = 3)
                }
            }
        }

        val combinedText = buildString {
            for (out in outputs) {
                if (out.summary.isBlank()) continue
                if (isNotEmpty()) append("\n\n")
                if (length + out.summary.length > maxContextChars) {
                    val remaining = (maxContextChars - length).coerceAtLeast(0)
                    if (remaining > 40) append(out.summary.take(remaining - 3).trimEnd()).append("...")
                    break
                }
                append(out.summary)
            }
        }
        val sources = outputs
            .flatMap { it.sources }
            .distinctBy { Triple(it.kind, it.exerciseId, it.epochDay) to it.label }
        return ToolOutput(summary = combinedText, sources = sources)
    }

    internal fun analyzePrompt(
        message: String,
        seedContext: ChatSeedContext,
        exercises: List<Exercise>,
        trainedCounts: Map<Long, Int>,
        routineNames: List<String>,
        programNames: List<String> = emptyList(),
    ): RoutedPlan {
        val norm = ExerciseMatcher.normalize(message)
        val padded = " $norm "

        val matchedExercise = findExerciseMention(norm, exercises, trainedCounts)
            ?: seedContext.takeIf { it.kind == "exercise" && it.exerciseName.isNotBlank() }?.exerciseName

        val matchedRoutine = routineNames
            .sortedByDescending { it.length }
            .firstOrNull { routine ->
                val rNorm = ExerciseMatcher.normalize(routine)
                rNorm.length >= 3 && padded.contains(" $rNorm ")
            }

        val matchedProgram = programNames
            .sortedByDescending { it.length }
            .firstOrNull { program ->
                val pNorm = ExerciseMatcher.normalize(program)
                pNorm.length >= 3 && padded.contains(" $pNorm ")
            }

        val bodyPart = BODY_PART_KEYWORDS.firstOrNull { padded.contains(" $it ") }
        val equipment = EQUIPMENT_KEYWORDS.firstOrNull { padded.contains(" $it ") }

        val wantsPr = containsAny(padded, PR_KEYWORDS)
        val wantsProgression = containsAny(padded, PROGRESSION_KEYWORDS)
        val wantsPrograms = containsAny(padded, PROGRAM_KEYWORDS) || matchedProgram != null
        val wantsSubstitutes = containsAny(padded, SUBSTITUTE_KEYWORDS) ||
            (padded.contains(" suggest ") && (!wantsPrograms || matchedExercise != null))
        val wantsRecentWorkouts = containsAny(padded, RECENT_WORKOUT_KEYWORDS)
        val hasBodyMetricWord = containsAny(padded, BODY_METRIC_KEYWORDS)
        val wantsBodyMetrics = hasBodyMetricWord && (
            padded.contains(" body weight ") ||
                padded.contains(" bodyweight ") ||
                padded.contains(" bmi ") ||
                padded.contains(" height ") ||
                padded.contains(" profile ") ||
                matchedExercise == null
            )
        val wantsSummary = containsAny(padded, SUMMARY_KEYWORDS) ||
            (wantsPrograms && (padded.contains(" suggest ") || padded.contains(" recommend ")))
        val wantsProgressReview = matchedExercise == null && containsAny(padded, PROGRESS_REVIEW_KEYWORDS)
        val specificDayOffset = when {
            padded.contains(" yesterday ") -> -1
            padded.contains(" today ") && (wantsRecentWorkouts || padded.contains(" workout ") || padded.contains(" did ")) -> 0
            else -> null
        }
        val asksExerciseExplanation = containsAny(padded, EXPLANATION_KEYWORDS) &&
            !padded.contains(" my ") &&
            !wantsPr &&
            !wantsProgression &&
            !wantsSubstitutes &&
            !wantsRecentWorkouts &&
            !wantsPrograms &&
            !wantsBodyMetrics &&
            !wantsSummary &&
            !wantsProgressReview
        val wantsForm = containsAny(padded, FORM_KEYWORDS) || asksExerciseExplanation

        return RoutedPlan(
            exerciseQuery = matchedExercise,
            routineQuery = matchedRoutine,
            programQuery = matchedProgram,
            bodyPart = bodyPart,
            equipment = equipment,
            wantsPr = wantsPr,
            wantsProgression = wantsProgression,
            wantsForm = wantsForm,
            wantsSubstitutes = wantsSubstitutes,
            wantsRecentWorkouts = wantsRecentWorkouts,
            wantsPrograms = wantsPrograms,
            wantsBodyMetrics = wantsBodyMetrics,
            wantsSummary = wantsSummary,
            wantsProgressReview = wantsProgressReview,
            specificDayOffset = specificDayOffset,
        )
    }

    private fun findExerciseMention(
        normMessage: String,
        exercises: List<Exercise>,
        trainedCounts: Map<Long, Int>,
    ): String? {
        val tokens = normMessage.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val maxN = minOf(4, tokens.size)
        for (n in maxN downTo 1) {
            for (i in 0..(tokens.size - n)) {
                val phraseTokens = tokens.subList(i, i + n)
                if (n == 1 && phraseTokens[0] in SINGLE_WORD_STOPWORDS) continue
                if (phraseTokens.all { it in SINGLE_WORD_STOPWORDS }) continue
                val phrase = phraseTokens.joinToString(" ")
                when (val res = ExerciseMatcher.resolve(phrase, exercises, trainedCounts)) {
                    is ExerciseMatchResult.Matched -> return res.exercise.name
                    is ExerciseMatchResult.Ambiguous -> {
                        if (n >= 2 || phrase in KNOWN_EXERCISE_ABBREVIATIONS) {
                            return phrase
                        }
                    }
                    is ExerciseMatchResult.NotFound -> Unit
                }
            }
        }
        return null
    }

    private fun containsAny(paddedNorm: String, keywords: List<String>): Boolean =
        keywords.any { paddedNorm.contains(" $it ") }

    private companion object {
        const val MAX_PRELOADED_CHARS = 2800

        val KNOWN_EXERCISE_ABBREVIATIONS = setOf("ohp", "rdl", "sldl", "bp", "dl")

        val SINGLE_WORD_STOPWORDS = setOf(
            "a", "an", "the", "my", "me", "i", "is", "are", "was", "were", "am",
            "do", "did", "does", "can", "could", "should", "would", "what", "when",
            "which", "how", "why", "who", "in", "on", "at", "to", "for", "of",
            "with", "from", "by", "this", "that", "it", "any", "some", "best",
            "last", "recent", "next", "much", "many", "more", "less", "weight",
            "reps", "sets", "workout", "workouts", "exercise", "exercises",
            "routine", "routines", "program", "programs", "lift", "lifted",
            "train", "trained", "training", "barbell", "dumbbell", "cable",
            "machine", "kettlebell", "band", "chest", "back", "legs", "arms",
            "shoulders", "biceps", "triceps", "core", "abs", "glutes", "calves",
            "hamstrings", "thighs", "quads", "forearms", "body", "press", "row",
            "curl", "raise", "fly", "squat", "deadlift",
        ).let { base ->
            // Allow core lift nouns like "squat" and "deadlift" to match when
            // they uniquely resolve via trained history, while still blocking
            // generic modifiers like "press", "row", "barbell", "chest".
            base - setOf("squat", "deadlift")
        }

        val BODY_PART_KEYWORDS = listOf(
            "upper body", "lower body", "posterior chain", "full body",
            "hamstrings", "shoulders", "forearms", "triceps", "biceps",
            "thighs", "glutes", "calves", "chest", "back", "core", "legs",
            "arms", "quads", "abs",
        )

        val EQUIPMENT_KEYWORDS = listOf(
            "smith machine", "body weight", "bodyweight", "kettlebell",
            "dumbbell", "dumbbells", "barbell", "machine", "cable", "band",
            "db", "bb", "kb",
        )

        val PR_KEYWORDS = listOf(
            "pr", "prs", "personal record", "personal records", "record", "records",
            "1rm", "one rep max", "heaviest", "strongest", "max", "best",
        )
        val PROGRESSION_KEYWORDS = listOf(
            "progress", "progressing", "progression", "trend", "trending",
            "plateau", "plateaued", "stuck", "stronger", "improving",
            "last time", "over time", "getting stronger",
        )
        val FORM_KEYWORDS = listOf(
            "form", "how to", "how do i", "technique", "cue", "cues",
            "perform", "instruction", "instructions", "explain", "describe",
            "mistakes", "muscles worked", "what muscles",
        )
        val EXPLANATION_KEYWORDS = listOf(
            "what is", "what are", "tell me about",
        )
        val SUBSTITUTE_KEYWORDS = listOf(
            "substitute", "substitutes", "substitution", "alternative",
            "alternatives", "replace", "replacement", "instead", "swap",
            "similar", "what exercises", "which exercises",
        )
        val RECENT_WORKOUT_KEYWORDS = listOf(
            "last workout", "recent", "yesterday", "today", "this week",
            "last session", "sessions", "what did i do", "when did i",
            "did i lift", "did i train", "last time i",
        )
        val PROGRAM_KEYWORDS = listOf(
            "program", "programs", "routine", "routines", "split", "plan",
            "next workout", "what s next", "whats next", "due for",
        )
        val BODY_METRIC_KEYWORDS = listOf(
            "weight", "body weight", "bodyweight", "bmi", "height", "profile", "weigh",
        )
        val SUMMARY_KEYWORDS = listOf(
            "summary", "overview", "consistency", "consistent", "streak",
            "frequency", "volume", "how often", "neglect", "neglecting",
            "body part", "muscle", "how am i doing",
        )
        val PROGRESS_REVIEW_KEYWORDS = listOf(
            "review", "workout history", "training history", "history",
            "overall progress", "review my progress", "plateau", "plateaued",
            "stalled", "stuck", "neglect", "neglecting", "balance", "imbalance",
            "this month", "last month", "compare",
        )
    }
}
