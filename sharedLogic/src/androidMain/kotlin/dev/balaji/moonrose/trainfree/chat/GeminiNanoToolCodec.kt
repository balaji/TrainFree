package dev.balaji.moonrose.trainfree.chat

import com.google.mlkit.genai.common.FunctionArgValue
import com.google.mlkit.genai.common.FunctionCall
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Content
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.content
import com.google.mlkit.genai.schema.tools.FunctionDeclaration
import com.google.mlkit.genai.schema.tools.FunctionSchema
import com.google.mlkit.genai.schema.tools.FunctionType

/**
 * Schema declarations, argument decoding, request assembly, and error mapping
 * shared by [GeminiNanoChatProvider] and its host tests.
 */
internal object GeminiNanoToolCodec {

    const val TOOL_TRAINING_SUMMARY = "get_training_summary"
    const val TOOL_PROGRESS_REVIEW = "get_progress_review"
    const val TOOL_PERSONAL_RECORDS = "get_personal_records"
    const val TOOL_EXERCISE_PROGRESSION = "get_exercise_progression"
    const val TOOL_RECENT_WORKOUTS = "get_recent_workouts"
    const val TOOL_WORKOUTS_FOR_DAY = "get_workouts_for_day"
    const val TOOL_PROGRAMS_AND_ROUTINES = "get_programs_and_routines"
    const val TOOL_SEARCH_EXERCISES = "search_exercises"
    const val TOOL_BODY_METRICS = "get_body_metrics_and_profile"
    const val TOOL_CREATE_EXERCISE = "create_exercise"
    const val TOOL_CREATE_PROGRAM = "create_program"
    const val TOOL_ADD_ROUTINE = "add_routine_to_program"
    const val TOOL_UPDATE_ROUTINE = "update_routine"

    private fun stringSchema(description: String, enumValues: List<String>? = null) = FunctionSchema(
        type = FunctionType.STRING,
        description = description,
        enum = enumValues,
    )

    private fun intSchema(description: String) = FunctionSchema(
        type = FunctionType.INTEGER,
        description = description,
    )

    private fun numberSchema(description: String) = FunctionSchema(
        type = FunctionType.NUMBER,
        description = description,
    )

    private fun arraySchema(description: String, items: FunctionSchema) = FunctionSchema(
        type = FunctionType.ARRAY,
        description = description,
        items = items,
    )

    private fun objectSchema(
        properties: Map<String, FunctionSchema> = emptyMap(),
        required: List<String> = emptyList(),
    ) = FunctionSchema(
        type = FunctionType.OBJECT,
        properties = properties.ifEmpty { null },
        required = required.ifEmpty { null },
    )

    private fun exerciseEntrySchema() = objectSchema(
        properties = mapOf(
            "exercise_name" to stringSchema("Exercise name (existing or new)."),
            "sets" to intSchema("Working sets count. Defaults to 3."),
            "reps" to intSchema("Target reps per set."),
            "weight" to numberSchema("Optional target weight in user units."),
            "duration_seconds" to intSchema("Target duration in seconds for timed exercises."),
            "distance" to numberSchema("Target distance in user units."),
            "body_part" to stringSchema("Primary muscle if creating a new exercise."),
            "equipment" to stringSchema("Equipment if disambiguating or creating."),
            "exercise_type" to stringSchema("Type: strength, duration, distance, reps_duration, distance_duration, reps_distance."),
            "other_body_parts" to arraySchema("Optional secondary muscles.", stringSchema("Muscle name.")),
        ),
        required = listOf("exercise_name"),
    )

    /** Dynamic tool schemas exposed when Gemini Nano supports `Feature.FUNCTION_CALLING`. */
    fun toolDeclarations(): List<FunctionDeclaration> = listOf(
        FunctionDeclaration(
            name = TOOL_TRAINING_SUMMARY,
            description = "Weekly volume trend, workout frequency/streaks, and body-part breakdown.",
            parameters = objectSchema(
                mapOf("weeks" to intSchema("Lookback weeks, 1 to 12. Defaults to 8.")),
            ),
        ),
        FunctionDeclaration(
            name = TOOL_PROGRESS_REVIEW,
            description = "Review workout history: period volume/session deltas, progressing vs plateaued lifts, untrained muscles, and journal notes.",
            parameters = objectSchema(
                mapOf("days" to intSchema("Lookback window in days, 7 to 180. Defaults to 30.")),
            ),
        ),
        FunctionDeclaration(
            name = TOOL_PERSONAL_RECORDS,
            description = "Personal records across exercises or for one named exercise.",
            parameters = objectSchema(
                mapOf(
                    "exercise_query" to stringSchema("Optional exercise name or abbreviation."),
                    "limit" to intSchema("Max PR rows when unfiltered, 1 to 12. Defaults to 5."),
                ),
            ),
        ),
        FunctionDeclaration(
            name = TOOL_EXERCISE_PROGRESSION,
            description = "Weekly progression and recent working sets for one exercise.",
            parameters = objectSchema(
                properties = mapOf(
                    "exercise_query" to stringSchema("Exercise name or abbreviation."),
                    "weeks" to intSchema("Lookback weeks, 2 to 24. Defaults to 12."),
                ),
                required = listOf("exercise_query"),
            ),
        ),
        FunctionDeclaration(
            name = TOOL_RECENT_WORKOUTS,
            description = "Recent completed workouts with sets, weights, reps, duration, and journal notes. Filterable by routine, exercise, or date range.",
            parameters = objectSchema(
                mapOf(
                    "limit" to intSchema("Max workouts, 1 to 15. Defaults to 5."),
                    "routine_query" to stringSchema("Optional routine name filter."),
                    "days" to intSchema("Optional lookback window in days."),
                    "exercise_query" to stringSchema("Optional exercise name filter."),
                    "start_date" to stringSchema("Optional start date YYYY-MM-DD."),
                    "end_date" to stringSchema("Optional end date YYYY-MM-DD."),
                ),
            ),
        ),
        FunctionDeclaration(
            name = TOOL_WORKOUTS_FOR_DAY,
            description = "Completed workouts and journal note for one specific date.",
            parameters = objectSchema(
                properties = mapOf(
                    "date" to stringSchema("Date as YYYY-MM-DD, \"today\", or \"yesterday\"."),
                    "epoch_day" to intSchema("Optional days since 1970-01-01."),
                ),
            ),
        ),
        FunctionDeclaration(
            name = TOOL_PROGRAMS_AND_ROUTINES,
            description = "Saved programs, routines in split order, target sets, and last performed dates.",
            parameters = objectSchema(
                mapOf(
                    "routine_query" to stringSchema("Optional routine name filter."),
                    "program_query" to stringSchema("Optional program name filter."),
                ),
            ),
        ),
        FunctionDeclaration(
            name = TOOL_SEARCH_EXERCISES,
            description = "Search catalog exercises by name, body part, or equipment for substitutes or exercise metadata.",
            parameters = objectSchema(
                mapOf(
                    "query" to stringSchema("Exercise name or keyword."),
                    "body_part" to stringSchema("Muscle or group like chest, back, legs, arms."),
                    "equipment" to stringSchema("Equipment like barbell, dumbbell, cable, bodyweight."),
                    "limit" to intSchema("Max results, 1 to 12. Defaults to 8."),
                ),
            ),
        ),
        FunctionDeclaration(
            name = TOOL_BODY_METRICS,
            description = "Body weight trend, profile details, and BMI using configured formula.",
            parameters = objectSchema(
                mapOf("days" to intSchema("Lookback days, 7 to 365. Defaults to 90.")),
            ),
        ),
        FunctionDeclaration(
            name = TOOL_CREATE_EXERCISE,
            description = "Create a custom exercise in the catalog with body part focus, equipment, and exercise type.",
            parameters = objectSchema(
                properties = mapOf(
                    "name" to stringSchema("Exercise name."),
                    "body_part" to stringSchema("Primary muscle: back, biceps, calves, chest, core, forearms, full body, glutes, hamstrings, shoulders, thighs, triceps."),
                    "equipment" to stringSchema("Equipment: barbell, dumbbell, cable, machine, smith machine, body weight, kettlebell, band, other."),
                    "exercise_type" to stringSchema("Measurement type: strength, duration, distance, reps_duration, distance_duration, reps_distance."),
                    "other_body_parts" to arraySchema("Optional secondary muscles.", stringSchema("Secondary muscle.")),
                ),
                required = listOf("name", "body_part"),
            ),
        ),
        FunctionDeclaration(
            name = TOOL_CREATE_PROGRAM,
            description = "Create a workout program with one or more routines and exercises. Missing exercises are created automatically.",
            parameters = objectSchema(
                properties = mapOf(
                    "program_name" to stringSchema("Program name."),
                    "routines" to arraySchema(
                        "Workout days/routines in split order.",
                        objectSchema(
                            properties = mapOf(
                                "name" to stringSchema("Routine name (e.g. Push, Upper A)."),
                                "exercises" to arraySchema("Exercises in this routine.", exerciseEntrySchema()),
                            ),
                            required = listOf("name", "exercises"),
                        ),
                    ),
                ),
                required = listOf("program_name", "routines"),
            ),
        ),
        FunctionDeclaration(
            name = TOOL_ADD_ROUTINE,
            description = "Add a new routine to an existing program (or create the program if needed). Missing exercises are created automatically.",
            parameters = objectSchema(
                properties = mapOf(
                    "routine_name" to stringSchema("Routine name."),
                    "program_name" to stringSchema("Target program name (optional if only one program exists)."),
                    "exercises" to arraySchema("Exercises in this routine.", exerciseEntrySchema()),
                ),
                required = listOf("routine_name", "exercises"),
            ),
        ),
        FunctionDeclaration(
            name = TOOL_UPDATE_ROUTINE,
            description = "Edit an existing routine: rename, add, replace, update sets/reps/weights, or remove exercises.",
            parameters = objectSchema(
                properties = mapOf(
                    "routine_name" to stringSchema("Existing routine name."),
                    "program_name" to stringSchema("Optional program name if routine names repeat."),
                    "new_routine_name" to stringSchema("Optional new name for the routine."),
                    "add_exercises" to arraySchema("Exercises to append.", exerciseEntrySchema()),
                    "update_exercises" to arraySchema("Existing exercises whose sets/reps/weight to update.", exerciseEntrySchema()),
                    "replace_exercises" to arraySchema(
                        "Exercises to swap in-place.",
                        objectSchema(
                            properties = mapOf(
                                "target_exercise_name" to stringSchema("Existing exercise in routine to replace."),
                                "new_exercise_name" to stringSchema("Replacement exercise name (existing or new)."),
                                "sets" to intSchema("Optional working sets count."),
                                "reps" to intSchema("Optional target reps."),
                                "weight" to numberSchema("Optional target weight."),
                                "duration_seconds" to intSchema("Optional target duration in seconds."),
                                "distance" to numberSchema("Optional target distance."),
                                "body_part" to stringSchema("Primary muscle if creating a new exercise."),
                                "equipment" to stringSchema("Equipment if disambiguating or creating."),
                                "exercise_type" to stringSchema("Measurement type if creating."),
                                "other_body_parts" to arraySchema("Optional secondary muscles.", stringSchema("Muscle name.")),
                            ),
                            required = listOf("target_exercise_name", "new_exercise_name"),
                        ),
                    ),
                    "remove_exercises" to arraySchema("Exercise names to remove from the routine.", stringSchema("Exercise name.")),
                ),
                required = listOf("routine_name"),
            ),
        ),
    )

    /** Dispatches one model [FunctionCall] onto [toolbox]. */
    suspend fun executeToolCall(
        call: FunctionCall,
        toolbox: ChatToolboxPort = LiveChatToolboxPort,
    ): ToolOutput {
        val args = call.arguments
        return when (call.name) {
            TOOL_TRAINING_SUMMARY -> toolbox.getTrainingSummary(
                weeks = args.intArg("weeks") ?: 8,
            )
            TOOL_PROGRESS_REVIEW -> toolbox.getProgressReview(
                days = args.intArg("days") ?: 30,
            )
            TOOL_PERSONAL_RECORDS -> toolbox.getPersonalRecords(
                exerciseQuery = args.stringArg("exercise_query"),
                limit = args.intArg("limit") ?: 5,
            )
            TOOL_EXERCISE_PROGRESSION -> toolbox.getExerciseProgression(
                exerciseQuery = args.stringArg("exercise_query").orEmpty(),
                weeks = args.intArg("weeks") ?: 12,
            )
            TOOL_RECENT_WORKOUTS -> toolbox.getRecentWorkouts(
                limit = args.intArg("limit") ?: 5,
                routineQuery = args.stringArg("routine_query"),
                days = args.intArg("days"),
                exerciseQuery = args.stringArg("exercise_query"),
                startDate = args.stringArg("start_date"),
                endDate = args.stringArg("end_date"),
            )
            TOOL_WORKOUTS_FOR_DAY -> toolbox.getWorkoutsForDay(
                epochDay = args.longArg("epoch_day"),
                date = args.stringArg("date"),
            )
            TOOL_PROGRAMS_AND_ROUTINES -> toolbox.getProgramsAndRoutines(
                routineQuery = args.stringArg("routine_query"),
                programQuery = args.stringArg("program_query"),
            )
            TOOL_SEARCH_EXERCISES -> toolbox.searchExercises(
                query = args.stringArg("query"),
                bodyPart = args.stringArg("body_part"),
                equipment = args.stringArg("equipment"),
                limit = args.intArg("limit") ?: 8,
            )
            TOOL_BODY_METRICS -> toolbox.getBodyMetricsAndProfile(
                days = args.intArg("days") ?: 90,
            )
            TOOL_CREATE_EXERCISE -> toolbox.createExercise(
                name = args.stringArg("name").orEmpty(),
                bodyPart = args.stringArg("body_part").orEmpty(),
                equipment = args.stringArg("equipment"),
                exerciseType = args.stringArg("exercise_type"),
                otherBodyParts = args.stringListArg("other_body_parts"),
            )
            TOOL_CREATE_PROGRAM -> toolbox.createProgram(
                programName = args.stringArg("program_name").orEmpty(),
                routines = args.mapListArg("routines").mapNotNull(::decodeRoutineInput),
            )
            TOOL_ADD_ROUTINE -> toolbox.addRoutineToProgram(
                routineName = args.stringArg("routine_name").orEmpty(),
                exercises = args.mapListArg("exercises").mapNotNull(::decodeExerciseInput),
                programName = args.stringArg("program_name"),
            )
            TOOL_UPDATE_ROUTINE -> toolbox.updateRoutine(
                routineName = args.stringArg("routine_name").orEmpty(),
                programName = args.stringArg("program_name"),
                newRoutineName = args.stringArg("new_routine_name"),
                addExercises = args.mapListArg("add_exercises").mapNotNull(::decodeExerciseInput),
                replaceExercises = args.mapListArg("replace_exercises").mapNotNull(::decodeReplaceExerciseInput),
                updateExercises = args.mapListArg("update_exercises").mapNotNull(::decodeExerciseInput),
                removeExercises = args.stringListArg("remove_exercises"),
            )
            else -> ToolOutput("Unknown tool: ${call.name}")
        }
    }

    /** Formats the final user turn text with optional pre-fetched context. */
    fun buildUserPrompt(request: ChatRequest, inlineInstructions: String? = null): String {
        val body = buildString {
            if (request.preloadedContext.isNotBlank()) {
                append("Context from Train Free:\n")
                append(request.preloadedContext.trim())
                append("\n\nQuestion: ")
            }
            append(request.userMessage)
        }
        return if (inlineInstructions.isNullOrBlank()) {
            body
        } else {
            "$inlineInstructions\n\n$body"
        }
    }

    /** Converts [ChatRequest] history and current prompt into ML Kit [Content] turns. */
    fun buildInitialContents(
        request: ChatRequest,
        supportsSystemPrompt: Boolean,
    ): MutableList<Content> {
        val contents = mutableListOf<Content>()
        var prependedInlineInstructions = false

        for (turn in request.history) {
            val role = if (turn.role == ChatMessage.ROLE_USER) Content.Role.USER else Content.Role.MODEL
            val text = if (!supportsSystemPrompt && !prependedInlineInstructions && role == Content.Role.USER) {
                prependedInlineInstructions = true
                "${request.instructions}\n\n${turn.text}"
            } else {
                turn.text
            }
            contents += content(role) { text(text) }
        }

        val trailingInstructions = if (!supportsSystemPrompt && !prependedInlineInstructions) {
            request.instructions
        } else {
            null
        }
        contents += content(Content.Role.USER) {
            text(buildUserPrompt(request, trailingInstructions))
        }
        return contents
    }

    /** Builds a [GenerateContentRequest] with optional system instruction and dynamic tools. */
    fun buildGenerateContentRequest(
        contents: List<Content>,
        instructions: String,
        supportsSystemPrompt: Boolean,
        tools: List<FunctionDeclaration>,
    ): GenerateContentRequest = GenerateContentRequest.Builder(contents).apply {
        if (supportsSystemPrompt && instructions.isNotBlank()) {
            systemInstruction = SystemInstruction(instructions)
        }
        if (tools.isNotEmpty()) {
            setTools(tools)
        }
    }.build()

    /** Maps a thrown error into a language-neutral [ChatMessage] error key, or null when cancelled. */
    fun classifyErrorKey(throwable: Throwable): String? {
        if (throwable is GenAiException) {
            return when (throwable.errorCode) {
                GenAiException.ErrorCode.CANCELLED -> null
                GenAiException.ErrorCode.REQUEST_TOO_LARGE,
                GenAiException.ErrorCode.MAX_TURNS_EXCEEDED,
                -> ChatMessage.ERROR_CONTEXT_TOO_LONG
                GenAiException.ErrorCode.NOT_AVAILABLE,
                GenAiException.ErrorCode.NOT_SUPPORTED,
                GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
                GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
                -> ChatMessage.ERROR_UNAVAILABLE
                else -> ChatMessage.ERROR_GENERATION_FAILED
            }
        }
        val msg = throwable.message?.lowercase().orEmpty()
        return if ("too large" in msg || "context" in msg && "token" in msg) {
            ChatMessage.ERROR_CONTEXT_TOO_LONG
        } else {
            ChatMessage.ERROR_GENERATION_FAILED
        }
    }
}

private fun decodeRoutineInput(map: Map<String, FunctionArgValue>): AiRoutineInput? {
    val name = map.stringArg("name") ?: return null
    val exercises = map.mapListArg("exercises").mapNotNull(::decodeExerciseInput)
    return AiRoutineInput(name = name, exercises = exercises)
}

private fun decodeExerciseInput(map: Map<String, FunctionArgValue>): AiExerciseInput? {
    val name = map.stringArg("exercise_name") ?: map.stringArg("name") ?: return null
    return AiExerciseInput(
        exerciseName = name,
        sets = map.intArg("sets") ?: 3,
        reps = map.intArg("reps"),
        weight = map.doubleArg("weight"),
        durationSeconds = map.intArg("duration_seconds"),
        distance = map.doubleArg("distance"),
        bodyPart = map.stringArg("body_part"),
        otherBodyParts = map.stringListArg("other_body_parts"),
        equipment = map.stringArg("equipment"),
        exerciseType = map.stringArg("exercise_type"),
    )
}

private fun decodeReplaceExerciseInput(map: Map<String, FunctionArgValue>): AiReplaceExerciseInput? {
    val target = map.stringArg("target_exercise_name") ?: return null
    val replacement = map.stringArg("new_exercise_name") ?: return null
    return AiReplaceExerciseInput(
        targetExerciseName = target,
        newExerciseName = replacement,
        sets = map.intArg("sets"),
        reps = map.intArg("reps"),
        weight = map.doubleArg("weight"),
        durationSeconds = map.intArg("duration_seconds"),
        distance = map.doubleArg("distance"),
        bodyPart = map.stringArg("body_part"),
        otherBodyParts = map.stringListArg("other_body_parts"),
        equipment = map.stringArg("equipment"),
        exerciseType = map.stringArg("exercise_type"),
    )
}

private fun Map<String, FunctionArgValue>.stringArg(key: String): String? =
    when (val value = this[key]) {
        is FunctionArgValue.StringValue -> value.value.takeIf { it.isNotBlank() }
        is FunctionArgValue.IntValue -> value.value.toString()
        is FunctionArgValue.LongValue -> value.value.toString()
        is FunctionArgValue.DoubleValue -> value.value.toString()
        else -> null
    }

private fun Map<String, FunctionArgValue>.intArg(key: String): Int? =
    when (val value = this[key]) {
        is FunctionArgValue.IntValue -> value.value
        is FunctionArgValue.LongValue -> value.value.toInt()
        is FunctionArgValue.DoubleValue -> value.value.toInt()
        is FunctionArgValue.StringValue -> value.value.trim().toIntOrNull()
        else -> null
    }

private fun Map<String, FunctionArgValue>.longArg(key: String): Long? =
    when (val value = this[key]) {
        is FunctionArgValue.LongValue -> value.value
        is FunctionArgValue.IntValue -> value.value.toLong()
        is FunctionArgValue.DoubleValue -> value.value.toLong()
        is FunctionArgValue.StringValue -> value.value.trim().toLongOrNull()
        else -> null
    }

private fun Map<String, FunctionArgValue>.doubleArg(key: String): Double? =
    when (val value = this[key]) {
        is FunctionArgValue.DoubleValue -> value.value
        is FunctionArgValue.IntValue -> value.value.toDouble()
        is FunctionArgValue.LongValue -> value.value.toDouble()
        is FunctionArgValue.StringValue -> value.value.trim().toDoubleOrNull()
        else -> null
    }

private fun Map<String, FunctionArgValue>.stringListArg(key: String): List<String> =
    when (val value = this[key]) {
        is FunctionArgValue.ListValue -> value.values.mapNotNull { item ->
            (item as? FunctionArgValue.StringValue)?.value?.trim()?.takeIf { it.isNotEmpty() }
        }
        is FunctionArgValue.StringValue -> value.value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        else -> emptyList()
    }

private fun Map<String, FunctionArgValue>.mapListArg(key: String): List<Map<String, FunctionArgValue>> =
    when (val value = this[key]) {
        is FunctionArgValue.ListValue -> value.values.mapNotNull { item ->
            (item as? FunctionArgValue.MapValue)?.map
        }
        else -> emptyList()
    }
