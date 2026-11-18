package dev.balaji.moonrose.trainfree.chat

import dev.balaji.moonrose.trainfree.calendar.WeekMath
import dev.balaji.moonrose.trainfree.model.Exercise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private class RecordingToolbox : ChatToolboxPort {
    val calls = mutableListOf<String>()

    override suspend fun getTrainingSummary(weeks: Int): ToolOutput {
        calls += "summary:$weeks"
        return ToolOutput("Summary block", listOf(ChatSourceRef("progress", "Progress overview")))
    }

    override suspend fun getProgressReview(days: Int): ToolOutput {
        calls += "review:$days"
        return ToolOutput("Progress review block", listOf(ChatSourceRef("progress", "Progress overview")))
    }

    override suspend fun getPersonalRecords(exerciseQuery: String?, limit: Int): ToolOutput {
        calls += "prs:${exerciseQuery.orEmpty()}"
        return ToolOutput("PR block for ${exerciseQuery ?: "all"}", listOf(ChatSourceRef("exercise", exerciseQuery ?: "All", 1L)))
    }

    override suspend fun getExerciseProgression(exerciseQuery: String, weeks: Int): ToolOutput {
        calls += "prog:$exerciseQuery"
        return ToolOutput("Progression block for $exerciseQuery", listOf(ChatSourceRef("exercise", exerciseQuery, 1L)))
    }

    override suspend fun getRecentWorkouts(
        limit: Int,
        routineQuery: String?,
        days: Int?,
        exerciseQuery: String?,
        startDate: String?,
        endDate: String?,
    ): ToolOutput {
        calls += "recent:${routineQuery.orEmpty()}"
        return ToolOutput("Recent workouts block", listOf(ChatSourceRef("day", "Nov 10", epochDay = 20_000L)))
    }

    override suspend fun getWorkoutsForDay(epochDay: Long?, date: String?): ToolOutput {
        calls += "day:${epochDay ?: date}"
        return ToolOutput("Day ${epochDay ?: date} block", listOf(ChatSourceRef("day", "Day", epochDay = epochDay ?: 20_000L)))
    }

    override suspend fun getProgramsAndRoutines(routineQuery: String?, programQuery: String?): ToolOutput {
        calls += "programs:${programQuery.orEmpty()}:${routineQuery.orEmpty()}"
        return ToolOutput("Programs block", listOf(ChatSourceRef("program", "PPL")))
    }

    override suspend fun searchExercises(
        query: String?,
        bodyPart: String?,
        equipment: String?,
        limit: Int,
    ): ToolOutput {
        calls += "search:q=${query.orEmpty()},bp=${bodyPart.orEmpty()},eq=${equipment.orEmpty()}"
        return ToolOutput("Exercise search block")
    }

    override suspend fun getBodyMetricsAndProfile(days: Int): ToolOutput {
        calls += "body:$days"
        return ToolOutput("Body metrics block")
    }

    override suspend fun createExercise(
        name: String,
        bodyPart: String,
        equipment: String?,
        exerciseType: String?,
        otherBodyParts: List<String>,
    ): ToolOutput = ToolOutput("Created $name")

    override suspend fun createProgram(programName: String, routines: List<AiRoutineInput>): ToolOutput =
        ToolOutput("Created $programName")

    override suspend fun addRoutineToProgram(
        routineName: String,
        exercises: List<AiExerciseInput>,
        programName: String?,
    ): ToolOutput = ToolOutput("Added $routineName")

    override suspend fun updateRoutine(
        routineName: String,
        programName: String?,
        newRoutineName: String?,
        addExercises: List<AiExerciseInput>,
        replaceExercises: List<AiReplaceExerciseInput>,
        updateExercises: List<AiExerciseInput>,
        removeExercises: List<String>,
    ): ToolOutput = ToolOutput("Updated $routineName")
}

class ChatContextRouterTest {
    private val bench = Exercise(1L, "Barbell Bench Press", "strength", "chest", "barbell", true)
    private val dbBench = Exercise(2L, "Dumbbell Bench Press", "strength", "chest", "dumbbell", true)
    private val ohp = Exercise(3L, "Barbell Overhead Press", "strength", "shoulders", "barbell", true)
    private val catalog = listOf(bench, dbBench, ohp)
    private val trained = mapOf(1L to 30, 3L to 12)

    private fun router(toolbox: RecordingToolbox = RecordingToolbox()) = ChatContextRouter(
        toolbox = toolbox,
        loadExercises = { catalog },
        loadTrainedCounts = { trained },
        loadRoutineNames = { listOf("Push Day", "Pull Day") },
        loadProgramNames = { listOf("PPL Split") },
    )

    @Test
    fun `routes OHP PR query to personal records without triggering body metrics`() = runBlocking {
        val tb = RecordingToolbox()
        val out = router(tb).route("What is my best OHP PR?")
        assertEquals(listOf("prs:Barbell Overhead Press"), tb.calls)
        assertTrue(out.summary.contains("PR block for Barbell Overhead Press"))
    }

    @Test
    fun `routes dumbbell substitute question for bench press`() = runBlocking {
        val tb = RecordingToolbox()
        router(tb).route("What dumbbell substitute can I use instead of bench press?")
        assertTrue(tb.calls.any { it == "search:q=,bp=chest,eq=dumbbell" })
    }

    @Test
    fun `distinguishes lifting weight question from body weight and BMI`() {
        val r = router()
        val liftPlan = r.analyzePrompt("What weight did I use on bench press last time?", ChatSeedContext(), catalog, trained, emptyList())
        assertEquals("Barbell Bench Press", liftPlan.exerciseQuery)
        assertFalse(liftPlan.wantsBodyMetrics)
        assertTrue(liftPlan.wantsProgression)

        val bodyPlan = r.analyzePrompt("How is my body weight and BMI trending?", ChatSeedContext(), catalog, trained, emptyList())
        assertNull(bodyPlan.exerciseQuery)
        assertTrue(bodyPlan.wantsBodyMetrics)
    }

    @Test
    fun `routes routine mention and day or exercise seed contexts`() = runBlocking {
        val tbRoutine = RecordingToolbox()
        router(tbRoutine).route("When was my last workout on Push Day?")
        assertEquals(listOf("recent:Push Day"), tbRoutine.calls)

        val tbDay = RecordingToolbox()
        router(tbDay).route("Summarize this workout", ChatSeedContext(kind = "day", epochDay = 20_100L))
        assertEquals(listOf("day:20100"), tbDay.calls)

        val tbExercise = RecordingToolbox()
        router(tbExercise).route(
            "How do I perform this with good form?",
            ChatSeedContext(kind = "exercise", exerciseId = 1L, exerciseName = "Barbell Bench Press"),
        )
        assertEquals(listOf("search:q=Barbell Bench Press,bp=,eq="), tbExercise.calls)

        val tbExplain = RecordingToolbox()
        router(tbExplain).route(
            "What is Barbell Bench Press?",
            ChatSeedContext(kind = "exercise", exerciseId = 1L, exerciseName = "Barbell Bench Press"),
        )
        assertEquals(listOf("search:q=Barbell Bench Press,bp=,eq="), tbExplain.calls)

        val tbUnknownForm = RecordingToolbox()
        val unknownOut = router(tbUnknownForm).route("How do I perform a Jefferson Curl?")
        assertTrue(tbUnknownForm.calls.isEmpty())
        assertEquals("", unknownOut.summary)
    }

    @Test
    fun `unmatched prompt falls back to training summary and recent workouts and respects char cap`() = runBlocking {
        val tbSummary = RecordingToolbox()
        router(tbSummary).route("How am I doing?")
        assertEquals(listOf("summary:8"), tbSummary.calls)

        val tbFallback = RecordingToolbox()
        val out = router(tbFallback).route("Hey coach", maxContextChars = 20)
        assertEquals(listOf("summary:8", "recent:"), tbFallback.calls)
        assertTrue(out.summary.length <= 20)
    }

    @Test
    fun `routes program name mention and routine suggestion prompt without misrouting to substitutes`() = runBlocking {
        val tbProgram = RecordingToolbox()
        router(tbProgram).route("What is in my PPL Split program?")
        assertEquals(listOf("programs:PPL Split:"), tbProgram.calls)

        val tbSuggest = RecordingToolbox()
        router(tbSuggest).route("Suggest me a workout routine")
        assertTrue("programs::" in tbSuggest.calls)
        assertTrue("summary:8" in tbSuggest.calls)
        assertFalse(tbSuggest.calls.any { it.startsWith("search:") })
    }

    @Test
    fun `routes workout history progress review and yesterday query`() = runBlocking {
        val tbReview = RecordingToolbox()
        router(tbReview).route("Review my progress from my workout history")
        assertTrue("review:30" in tbReview.calls)
        assertTrue("recent:" in tbReview.calls)

        val tbYesterday = RecordingToolbox()
        val yesterday = WeekMath.todayEpochDay() - 1L
        router(tbYesterday).route("What did I do yesterday?")
        assertEquals(listOf("day:$yesterday"), tbYesterday.calls)
    }

    @Test
    fun `read-only exercise form seed routes non-English prompts to form metadata only`() = runBlocking {
        val tb = RecordingToolbox()
        router(tb).route(
            "Tekniktips för Barbell Bench Press",
            ChatSeedContext(
                kind = "exercise",
                exerciseId = 1L,
                exerciseName = "Barbell Bench Press",
                autoPrompt = "Tekniktips för Barbell Bench Press",
                readOnly = true,
            ),
        )
        assertEquals(listOf("search:q=Barbell Bench Press,bp=,eq="), tb.calls)
    }
}
