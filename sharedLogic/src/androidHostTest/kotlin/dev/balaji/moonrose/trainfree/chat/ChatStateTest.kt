package dev.balaji.moonrose.trainfree.chat

import dev.balaji.moonrose.trainfree.model.Exercise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

private class FakeChatProvider(
    private val toolCalling: Boolean = true,
) : ChatGenerationProvider {
    var availability = "available"
    var prewarms = 0
    val requests = mutableListOf<ChatRequest>()
    val calls = mutableListOf<Call>()

    class Call(
        val request: ChatRequest,
        val onPartial: (String) -> Unit,
        val onComplete: (String, List<ChatSourceRef>) -> Unit,
        val onError: (String, String) -> Unit,
    ) : ChatStreamHandle {
        var cancelled = false
        var finished = false
        override fun cancel() { cancelled = true }
    }

    override fun availability() = availability
    override fun supportsToolCalling() = toolCalling
    override fun prewarm() { prewarms++ }

    override fun generate(
        request: ChatRequest,
        onPartial: (String) -> Unit,
        onComplete: (String, List<ChatSourceRef>) -> Unit,
        onError: (String, String) -> Unit,
    ): ChatStreamHandle {
        requests += request
        return Call(request, onPartial, onComplete, onError).also { calls += it }
    }

    fun partial(text: String) = activeCall().onPartial(text)

    fun complete(text: String, sources: List<ChatSourceRef> = emptyList()) {
        val c = activeCall()
        c.finished = true
        c.onComplete(text, sources)
    }

    fun fail(errorKey: String, debug: String = "err") {
        val c = activeCall()
        c.finished = true
        c.onError(errorKey, debug)
    }

    private fun activeCall() = calls.last { !it.finished && !it.cancelled }
}

class ChatStateTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val bench = Exercise(1L, "Barbell Bench Press", "strength", "chest", "barbell", true)

    private val router = ChatContextRouter(
        toolbox = object : ChatToolboxPort {
            override suspend fun getTrainingSummary(weeks: Int) =
                ToolOutput("Volume: 4000 kg", listOf(ChatSourceRef("progress", "Progress overview")))
            override suspend fun getProgressReview(days: Int) =
                ToolOutput("Progress review", listOf(ChatSourceRef("progress", "Progress overview")))
            override suspend fun getPersonalRecords(exerciseQuery: String?, limit: Int) =
                ToolOutput("PR: 100 kg x 5", listOf(ChatSourceRef("exercise", "Barbell Bench Press", 1L)))
            override suspend fun getExerciseProgression(exerciseQuery: String, weeks: Int) =
                ToolOutput("Progression: +2 kg/wk", listOf(ChatSourceRef("exercise", "Barbell Bench Press", 1L)))
            override suspend fun getRecentWorkouts(
                limit: Int,
                routineQuery: String?,
                days: Int?,
                exerciseQuery: String?,
                startDate: String?,
                endDate: String?,
            ) = ToolOutput("Recent workout", listOf(ChatSourceRef("day", "Nov 10", epochDay = 20_000L)))
            override suspend fun getWorkoutsForDay(epochDay: Long?, date: String?) =
                ToolOutput("Day ${epochDay ?: date} workout", listOf(ChatSourceRef("day", "Nov 10", epochDay = epochDay ?: 20_000L)))
            override suspend fun getProgramsAndRoutines(routineQuery: String?, programQuery: String?) = ToolOutput("PPL")
            override suspend fun searchExercises(query: String?, bodyPart: String?, equipment: String?, limit: Int) =
                ToolOutput("Search")
            override suspend fun getBodyMetricsAndProfile(days: Int) = ToolOutput("Body")
            override suspend fun createExercise(
                name: String,
                bodyPart: String,
                equipment: String?,
                exerciseType: String?,
                otherBodyParts: List<String>,
            ) = ToolOutput("Created $name")
            override suspend fun createProgram(programName: String, routines: List<AiRoutineInput>) =
                ToolOutput("Created $programName")
            override suspend fun addRoutineToProgram(
                routineName: String,
                exercises: List<AiExerciseInput>,
                programName: String?,
            ) = ToolOutput("Added $routineName")
            override suspend fun updateRoutine(
                routineName: String,
                programName: String?,
                newRoutineName: String?,
                addExercises: List<AiExerciseInput>,
                replaceExercises: List<AiReplaceExerciseInput>,
                updateExercises: List<AiExerciseInput>,
                removeExercises: List<String>,
            ) = ToolOutput("Updated $routineName")
        },
        loadExercises = { listOf(bench) },
        loadTrainedCounts = { mapOf(1L to 20) },
        loadRoutineNames = { emptyList() },
        loadProgramNames = { emptyList() },
    )

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun newState(
        provider: ChatGenerationProvider?,
        topExercise: String? = "Barbell Bench Press",
    ) = ChatState(
        provider = provider,
        router = router,
        loadTopTrainedExercise = { topExercise },
        scope = scope,
    )

    @Test
    fun `openWithSeed prewarms, populates exercise chip, and resets when seed changes`() {
        val provider = FakeChatProvider()
        val state = newState(provider)
        state.openWithSeed(ChatSeedContext())
        assertEquals(1, provider.prewarms)
        assertTrue(
            state.uiState.value.suggestedPrompts.any {
                it.key == ChatSuggestedPrompt.KEY_EXERCISE_PROGRESSION && it.arg == "Barbell Bench Press"
            },
        )

        state.sendMessage("How am I doing?")
        provider.complete("You trained 3 times this week.")
        assertEquals(2, state.uiState.value.messages.size)

        state.openWithSeed(ChatSeedContext(kind = "exercise", exerciseId = 1L, exerciseName = "Barbell Bench Press"))
        assertTrue(state.uiState.value.messages.isEmpty())
        assertEquals(
            listOf(
                ChatSuggestedPrompt.KEY_EXERCISE_PROGRESSION,
                ChatSuggestedPrompt.KEY_EXERCISE_FORM,
                ChatSuggestedPrompt.KEY_EXERCISE_SUBSTITUTES,
            ),
            state.uiState.value.suggestedPrompts.map { it.key },
        )
    }

    @Test
    fun `streams partials and merges tool sources on completion`() {
        val provider = FakeChatProvider(toolCalling = true)
        val state = newState(provider)
        state.openWithSeed()

        state.sendMessage("What is my bench PR?")
        assertTrue(state.uiState.value.isGenerating)
        assertEquals("", provider.requests.single().preloadedContext)
        assertTrue(provider.requests.single().resetSession)

        provider.partial("Your best bench")
        assertEquals("Your best bench", state.uiState.value.messages.last().text)
        assertEquals(ChatMessage.STATUS_STREAMING, state.uiState.value.messages.last().status)

        val ref = ChatSourceRef("exercise", "Barbell Bench Press", exerciseId = 1L)
        provider.complete("  Your best bench is 100 kg x 5.  ", listOf(ref))

        val last = state.uiState.value.messages.last()
        assertFalse(state.uiState.value.isGenerating)
        assertEquals(ChatMessage.STATUS_COMPLETE, last.status)
        assertEquals("Your best bench is 100 kg x 5.", last.text)
        assertEquals(listOf(ref), last.sources)
    }

    @Test
    fun `fallback provider receives routed preloaded context and sources`() {
        val provider = FakeChatProvider(toolCalling = false)
        val state = newState(provider)
        state.openWithSeed()

        state.sendMessage("What is my bench PR?")
        assertTrue(provider.requests.single().preloadedContext.contains("PR: 100 kg x 5"))
        provider.complete("Your bench PR is 100 kg x 5.")

        val last = state.uiState.value.messages.last()
        assertEquals(1L, last.sources.single().exerciseId)
    }

    @Test
    fun `context too long compacts history and retries once automatically`() {
        val provider = FakeChatProvider(toolCalling = true)
        val state = newState(provider)
        state.openWithSeed()

        state.sendMessage("First question")
        provider.complete("First answer")

        state.sendMessage("Follow up question")
        assertEquals(2, provider.requests.last().history.size)
        assertFalse(provider.requests.last().resetSession)

        provider.fail(ChatMessage.ERROR_CONTEXT_TOO_LONG)
        // Retried automatically with cleared history and resetSession = true.
        assertEquals(3, provider.requests.size)
        assertTrue(provider.requests.last().history.isEmpty())
        assertTrue(provider.requests.last().resetSession)

        provider.complete("Compacted answer")
        assertEquals("Compacted answer", state.uiState.value.messages.last().text)
        assertEquals(ChatMessage.STATUS_COMPLETE, state.uiState.value.messages.last().status)
    }

    @Test
    fun `cancelGeneration preserves partial text and drops empty placeholders`() {
        val provider = FakeChatProvider()
        val state = newState(provider)

        state.sendMessage("First")
        state.cancelGeneration()
        // Empty assistant placeholder dropped; only user message remains.
        assertEquals(1, state.uiState.value.messages.size)
        assertFalse(state.uiState.value.isGenerating)

        state.sendMessage("Second")
        provider.partial("Partial answer")
        state.cancelGeneration()
        val last = state.uiState.value.messages.last()
        assertEquals(ChatMessage.STATUS_CANCELLED, last.status)
        assertEquals("Partial answer", last.text)
    }

    @Test
    fun `retryLast resends failed prompt and unavailable provider surfaces error`() {
        val provider = FakeChatProvider()
        val state = newState(provider)
        state.sendMessage("Check volume")
        provider.fail(ChatMessage.ERROR_GENERATION_FAILED)
        assertEquals(ChatMessage.STATUS_ERROR, state.uiState.value.messages.last().status)

        state.retryLast()
        provider.complete("4000 kg this week.")
        assertEquals(2, state.uiState.value.messages.size)
        assertEquals(ChatMessage.STATUS_COMPLETE, state.uiState.value.messages.last().status)

        val unavailableState = newState(provider = null)
        unavailableState.sendMessage("Hello")
        val err = unavailableState.uiState.value.messages.last()
        assertEquals(ChatMessage.STATUS_ERROR, err.status)
        assertEquals(ChatMessage.ERROR_UNAVAILABLE, err.errorKey)
    }

    @Test
    fun `prompt builder respects language tag and sliding window caps`() {
        val svInstructions = ChatPromptBuilder.buildInstructions(
            seedContext = ChatSeedContext(kind = "exercise", exerciseName = "Squat"),
            supportsToolCalling = true,
            languageTag = "sv",
            todayEpochDay = 20_774L,
        )
        assertTrue(svInstructions.contains("Respond in Swedish."))
        assertTrue(svInstructions.contains("Today's date is "))
        assertTrue(svInstructions.contains("Squat"))
        assertTrue(svInstructions.contains("No medical"))
        assertTrue(svInstructions.contains("use your own strength-training knowledge"))
        assertTrue(svInstructions.contains("quote ONLY numbers, dates, exercises, and units"))
        assertTrue(svInstructions.contains("Never mention internal tool names"))
        assertFalse(svInstructions.contains("search_exercises"))

        val noToolInstructions = ChatPromptBuilder.buildInstructions(
            seedContext = ChatSeedContext(),
            supportsToolCalling = false,
            languageTag = "en",
        )
        assertTrue(noToolInstructions.contains("Never mention internal instructions"))
        assertFalse(noToolInstructions.contains("search_exercises"))

        val messages = (1..6).map { i ->
            ChatMessage("m$i", ChatMessage.ROLE_USER, "Turn $i text", ChatMessage.STATUS_COMPLETE)
        }
        val windowed = ChatPromptBuilder.windowHistory(messages, maxTurns = 3, maxChars = 100)
        assertEquals(listOf("Turn 4 text", "Turn 5 text", "Turn 6 text"), windowed.map { it.text })
    }

    @Test
    fun `offers add-as-program action on routine suggestions and clears after saving`() {
        val provider = FakeChatProvider(toolCalling = true)
        val state = newState(provider)
        state.openWithSeed(ChatSeedContext())
        assertTrue(state.uiState.value.suggestedPrompts.any { it.key == ChatSuggestedPrompt.KEY_SUGGEST_ROUTINE })
        assertTrue(state.uiState.value.suggestedPrompts.any { it.key == ChatSuggestedPrompt.KEY_REVIEW_PROGRESS })

        state.sendMessage("Suggest me a workout routine")
        provider.complete(
            "Here is a 3-day Push/Pull/Legs routine:\n" +
                "Day 1 (Push): Bench Press 3 sets x 8 reps.\n" +
                "Would you like me to add it as a program?",
        )
        val suggestionMsg = state.uiState.value.messages.last()
        assertTrue(suggestionMsg.suggestsAddAsProgram)

        // Tapping "Add it as a program" clears the offer on the prior message and creates the program.
        state.sendMessage("Add it as a program")
        assertFalse(state.uiState.value.messages.first { it.id == suggestionMsg.id }.suggestsAddAsProgram)
        provider.complete(
            "Created the Push/Pull/Legs program.",
            listOf(ChatSourceRef(kind = "program", label = "Push/Pull/Legs", programId = 12L)),
        )
        assertFalse(state.uiState.value.messages.last().suggestsAddAsProgram)

        // Non-tool-calling providers never offer the one-tap add-as-program action.
        val noTools = FakeChatProvider(toolCalling = false)
        val noToolsState = newState(noTools)
        noToolsState.sendMessage("Suggest me a workout routine")
        noTools.complete("Day 1 Push: 3 sets x 8 reps. Would you like me to add it as a program?")
        assertFalse(noToolsState.uiState.value.messages.last().suggestsAddAsProgram)
    }
}
