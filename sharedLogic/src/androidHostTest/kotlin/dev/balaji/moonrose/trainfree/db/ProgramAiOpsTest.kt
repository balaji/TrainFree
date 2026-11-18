package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.chat.AiExerciseInput
import dev.balaji.moonrose.trainfree.chat.AiReplaceExerciseInput
import dev.balaji.moonrose.trainfree.chat.AiRoutineInput
import dev.balaji.moonrose.trainfree.chat.ChatToolbox
import dev.balaji.moonrose.trainfree.model.Exercise
import dev.balaji.moonrose.trainfree.model.Program
import dev.balaji.moonrose.trainfree.model.Routine
import dev.balaji.moonrose.trainfree.model.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone

private class InMemoryProgramAiStore : ProgramAiStore {
    private var nextExerciseId = 10L
    private var nextProgramId = 1L
    private var nextRoutineId = 1L
    private var nextWorkSetId = 1L
    private var nextDetailId = 1L

    val exercises = mutableListOf(
        Exercise(1L, "Barbell Bench Press", "strength", "chest", "barbell", true, listOf("triceps", "shoulders")),
        Exercise(2L, "Skull Crusher", "strength", "triceps", "barbell", true),
        Exercise(3L, "Pull-Up", "strength", "back", "body weight", true, listOf("biceps")),
        Exercise(4L, "Face Pull", "strength", "shoulders", "cable", true, listOf("back")),
        Exercise(5L, "Dumbbell Lateral Raise", "strength", "shoulders", "dumbbell", true),
        Exercise(6L, "Plank", "duration", "core", "body weight", true),
    )
    val programs = mutableListOf<Program>()
    val routines = mutableListOf<Routine>()
    val workSets = mutableListOf<WorkSetEntity>()
    val details = mutableListOf<WorkSetDetailEntity>()

    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()

    override suspend fun getAllExercises(): List<Exercise> = exercises.toList()

    override suspend fun insertExercise(entity: ExerciseEntity): Long {
        val id = nextExerciseId++
        exercises += Exercise(
            id = id,
            name = entity.name,
            exerciseType = entity.exerciseType,
            bodyPart = entity.bodyPart,
            equipment = entity.equipment,
            builtin = entity.builtin,
            otherBodyParts = entity.otherBodyParts.orEmpty(),
        )
        return id
    }

    override suspend fun getCompletedSetCountsByExercise(): Map<Long, Int> = mapOf(1L to 24)

    override suspend fun getAllPrograms(): List<Program> = programs.toList()

    override suspend fun insertProgram(name: String, now: Long): Long {
        val id = nextProgramId++
        programs += Program(id, name)
        return id
    }

    override suspend fun getAllRoutines(): List<Routine> = routines.toList()

    override suspend fun nextRoutinePosition(programId: Long): Int =
        (routines.filter { it.programId == programId }.maxOfOrNull { it.position } ?: 0) + 1

    override suspend fun insertRoutine(programId: Long, name: String, position: Int, now: Long): Long {
        val id = nextRoutineId++
        routines += Routine(id, name, programId, position)
        return id
    }

    override suspend fun renameRoutine(routineId: Long, name: String, now: Long) {
        val idx = routines.indexOfFirst { it.id == routineId }
        if (idx >= 0) routines[idx] = routines[idx].copy(name = name)
    }

    override suspend fun getWorkSetsForRoutine(routineId: Long): List<WorkSetEntity> =
        workSets.filter { it.routineId == routineId }.sortedBy { it.position }

    override suspend fun getWorkSetDetailsForWorkSets(workSetIds: List<Long>): List<WorkSetDetailEntity> =
        details.filter { it.workSetId in workSetIds }.sortedWith(compareBy({ it.workSetId }, { it.position }))

    override suspend fun deleteWorkSetsForRoutine(routineId: Long) {
        val removedIds = workSets.filter { it.routineId == routineId }.map { it.id }.toSet()
        workSets.removeAll { it.routineId == routineId }
        details.removeAll { it.workSetId in removedIds }
    }

    override suspend fun insertWorkSet(routineId: Long, exerciseId: Long, position: Int, now: Long): Long {
        val id = nextWorkSetId++
        workSets += WorkSetEntity(id, routineId, exerciseId, position, now, now)
        return id
    }

    override suspend fun insertWorkSetDetail(detail: WorkSetDetailEntity): Long {
        val id = nextDetailId++
        details += detail.copy(id = id)
        return id
    }
}

class ProgramAiOpsTest {
    @Test
    fun createsCustomExerciseWithNormalizedCatalogMetadataAndAvoidsDuplicates() = runBlocking {
        val store = InMemoryProgramAiStore()
        val created = store.createCustomExercise(
            name = "  Bulgarian Split Squat ",
            bodyPart = "legs",
            equipment = "dumbbells",
            exerciseType = "strength",
            otherBodyParts = listOf("glutes", "thighs", "full body", "glutes"),
            now = 2_000L,
        )
        val res = assertIs<AiCreateExerciseResult.Created>(created)
        assertEquals("Bulgarian Split Squat", res.exercise.name)
        assertEquals("thighs", res.exercise.bodyPart)
        assertEquals("dumbbell", res.exercise.equipment)
        assertEquals("strength", res.exercise.exerciseType)
        assertEquals(listOf("glutes"), res.exercise.otherBodyParts)

        val duplicate = store.createCustomExercise(
            name = "bulgarian split squat",
            bodyPart = "thighs",
            now = 3_000L,
        )
        val dupRes = assertIs<AiCreateExerciseResult.AlreadyExists>(duplicate)
        assertEquals(res.exercise.id, dupRes.exercise.id)
    }

    @Test
    fun createsMultiDayProgramReusingCatalogExercisesAndAutoCreatingMissingOnes() = runBlocking {
        val store = InMemoryProgramAiStore()
        val result = store.createAiProgram(
            programName = "3-Day Full Body",
            routines = listOf(
                AiRoutineInput(
                    name = "Day 1",
                    exercises = listOf(
                        AiExerciseInput(exerciseName = "Barbell Bench Press", sets = 4, reps = 6, weight = 80.0),
                        AiExerciseInput(
                            exerciseName = "Pendulum Squat",
                            sets = 3,
                            reps = 10,
                            bodyPart = "quads",
                            equipment = "machine",
                            otherBodyParts = listOf("glutes"),
                        ),
                    ),
                ),
                AiRoutineInput(
                    name = "Day 2",
                    exercises = listOf(
                        AiExerciseInput(
                            exerciseName = "Plank",
                            sets = 3,
                            durationSeconds = 60,
                            exerciseType = "duration",
                        ),
                    ),
                ),
            ),
            unit = WeightUnit.METRIC,
            now = 5_000L,
        )

        val created = assertIs<AiCreateProgramResult.Created>(result)
        assertEquals("3-Day Full Body", created.program.name)
        assertEquals(2, created.routines.size)
        assertEquals(listOf("Pendulum Squat"), created.createdExercises.map { it.name })

        val pendulum = store.exercises.firstOrNull { it.name == "Pendulum Squat" }
        assertNotNull(pendulum)
        assertEquals("thighs", pendulum.bodyPart)
        assertEquals("machine", pendulum.equipment)
        assertEquals(listOf("glutes"), pendulum.otherBodyParts)

        val day1WorkSets = store.getWorkSetsForRoutine(created.routines[0].routine.id)
        assertEquals(2, day1WorkSets.size)
        val benchDetails = store.getWorkSetDetailsForWorkSets(listOf(day1WorkSets[0].id))
        assertEquals(4, benchDetails.size)
        assertEquals(6, benchDetails.first().targetReps)
        assertEquals(80.0, benchDetails.first().targetWeight)

        val day2WorkSets = store.getWorkSetsForRoutine(created.routines[1].routine.id)
        val plankDetails = store.getWorkSetDetailsForWorkSets(listOf(day2WorkSets.single().id))
        assertEquals(3, plankDetails.size)
        assertEquals(60, plankDetails.first().targetDuration)
    }

    @Test
    fun addsRoutineToProgramAndUpdatesRoutineExercisesInPlace() = runBlocking {
        val store = InMemoryProgramAiStore()
        val created = assertIs<AiCreateProgramResult.Created>(
            store.createAiProgram(
                programName = "PPL",
                routines = listOf(
                    AiRoutineInput(
                        name = "Push",
                        exercises = listOf(
                            AiExerciseInput("Barbell Bench Press", sets = 3, reps = 8),
                            AiExerciseInput("Skull Crusher", sets = 3, reps = 12),
                        ),
                    ),
                ),
                unit = WeightUnit.METRIC,
                now = 6_000L,
            ),
        )

        val added = assertIs<AiRoutineAddResult.Added>(
            store.addAiRoutineToProgram(
                routineName = "Pull",
                exercises = listOf(
                    AiExerciseInput("Pull-Up", sets = 3, reps = 8),
                    AiExerciseInput("Face Pull", sets = 3, reps = 15),
                ),
                programName = null,
                unit = WeightUnit.METRIC,
                now = 7_000L,
            ),
        )
        assertEquals("PPL", added.program.name)
        assertEquals(2, added.routine.position)

        val updated = assertIs<AiRoutineUpdateResult.Updated>(
            store.updateAiRoutine(
                routineName = "Push",
                programName = "PPL",
                newRoutineName = "Heavy Push",
                replaceExercises = listOf(
                    AiReplaceExerciseInput(
                        targetExerciseName = "Skull Crusher",
                        newExerciseName = "Bayesian Cable Pushdown",
                        bodyPart = "triceps",
                        equipment = "cable",
                    ),
                ),
                updateExercises = listOf(
                    AiExerciseInput("Barbell Bench Press", sets = 4, reps = 5),
                ),
                addExercises = listOf(
                    AiExerciseInput("Dumbbell Lateral Raise", sets = 3, reps = 15),
                ),
                unit = WeightUnit.METRIC,
                now = 8_000L,
            ),
        )

        assertEquals("Push" to "Heavy Push", updated.renamedFromTo)
        assertEquals(listOf("Skull Crusher" to "Bayesian Cable Pushdown"), updated.replaced)
        assertEquals(listOf("Barbell Bench Press"), updated.updated)
        assertEquals(listOf("Dumbbell Lateral Raise"), updated.added)
        assertEquals(listOf("Bayesian Cable Pushdown"), updated.createdExercises.map { it.name })

        val persistedRoutine = store.routines.first { it.id == created.routines.single().routine.id }
        assertEquals("Heavy Push", persistedRoutine.name)

        val workSets = store.getWorkSetsForRoutine(persistedRoutine.id)
        val exercisesById = store.exercises.associateBy { it.id }
        assertEquals(
            listOf("Barbell Bench Press", "Bayesian Cable Pushdown", "Dumbbell Lateral Raise"),
            workSets.map { exercisesById[it.exerciseId]?.name },
        )
        val benchDetails = store.getWorkSetDetailsForWorkSets(listOf(workSets[0].id))
        assertEquals(4, benchDetails.size)
        assertEquals(5, benchDetails.first().targetReps)
        val pushdownDetails = store.getWorkSetDetailsForWorkSets(listOf(workSets[1].id))
        assertEquals(3, pushdownDetails.size)

        val allWs = store.routines.associate { it.id to store.getWorkSetsForRoutine(it.id) }
        val allDetailMap = store.details.groupBy { it.workSetId }
        val readOut = ChatToolbox.programsAndRoutinesFrom(
            programs = store.programs,
            routines = store.routines,
            workSetsByRoutine = allWs,
            detailsByWorkSet = allDetailMap,
            exercisesById = exercisesById,
            lastPerformed = emptyList(),
            unit = WeightUnit.METRIC,
            zone = TimeZone.UTC,
            includeEmptyPrograms = true,
        )
        assertTrue(readOut.summary.contains("Program: PPL"))
        assertTrue(readOut.summary.contains("Heavy Push (never performed):"))
        assertTrue(readOut.summary.contains("Bayesian Cable Pushdown 3 sets"))
        assertTrue(readOut.summary.contains("[triceps]"))
        assertTrue(readOut.summary.contains("Pull (never performed):"))
    }
}
