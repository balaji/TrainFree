package dev.balaji.moonrose.trainfree.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.balaji.moonrose.trainfree.model.SetType
import dev.balaji.moonrose.trainfree.model.WeightUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * End-to-end tests exercising [WorkoutSessionService] and
 * [WorkoutSessionFormState] against a real Room/SQLite database on iOS.
 */
class WorkoutSessionEndToEndTest {
    private val dbPath = NSTemporaryDirectory() + "workout-e2e-${NSUUID().UUIDString}.db"

    @OptIn(ExperimentalForeignApi::class)
    @AfterTest
    fun tearDown() {
        val fm = NSFileManager.defaultManager
        listOf(dbPath, "$dbPath-wal", "$dbPath-shm").forEach { path ->
            if (fm.fileExistsAtPath(path)) {
                fm.removeItemAtPath(path, null)
            }
        }
    }

    private data class SeedIds(
        val benchExerciseId: Long,
        val plankExerciseId: Long,
        val routineId: Long,
    )

    private fun openTestDb(): AppDatabase =
        Room.databaseBuilder<AppDatabase>(name = dbPath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()

    private suspend fun seedRoutineFixtures(db: AppDatabase): SeedIds {
        val now = 1_000L
        db.profileDao().upsert(
            ProfileEntity(
                id = PROFILE_ID,
                age = 30,
                gender = "male",
                weight = 80.0,
                height = 180.0,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val benchId = db.exerciseDao().insert(
            ExerciseEntity(
                name = "Barbell Bench Press",
                exerciseType = "strength",
                bodyPart = "chest",
                otherBodyParts = listOf("triceps", "shoulders"),
                equipment = "barbell",
                builtin = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val plankId = db.exerciseDao().insert(
            ExerciseEntity(
                name = "Plank",
                exerciseType = "duration",
                bodyPart = "core",
                otherBodyParts = null,
                equipment = "body weight",
                builtin = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val programId = db.programDao().insert(
            ProgramEntity(
                profileId = PROFILE_ID,
                name = "Strength Block",
                createdAt = now,
                updatedAt = now,
            ),
        )
        val routineId = db.routineDao().insert(
            RoutineEntity(
                programId = programId,
                name = "Push Day",
                position = 1,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val benchWorkSetId = db.workSetDao().insert(
            WorkSetEntity(
                routineId = routineId,
                exerciseId = benchId,
                position = 1,
                createdAt = now,
                updatedAt = now,
            ),
        )
        db.workSetDetailDao().insert(
            WorkSetDetailEntity(
                workSetId = benchWorkSetId,
                position = 1,
                targetWeight = 40.0,
                targetReps = 10,
                targetDuration = null,
                targetDistance = null,
                setType = SetType.WARMUP,
                weightUnit = WeightUnit.METRIC,
                createdAt = now,
                updatedAt = now,
            ),
        )
        db.workSetDetailDao().insert(
            WorkSetDetailEntity(
                workSetId = benchWorkSetId,
                position = 2,
                targetWeight = 80.0,
                targetReps = 5,
                targetDuration = null,
                targetDistance = null,
                setType = SetType.NORMAL,
                weightUnit = WeightUnit.METRIC,
                createdAt = now,
                updatedAt = now,
            ),
        )
        db.workSetDetailDao().insert(
            WorkSetDetailEntity(
                workSetId = benchWorkSetId,
                position = 3,
                targetWeight = 80.0,
                targetReps = 5,
                targetDuration = null,
                targetDistance = null,
                setType = SetType.NORMAL,
                weightUnit = WeightUnit.METRIC,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val plankWorkSetId = db.workSetDao().insert(
            WorkSetEntity(
                routineId = routineId,
                exerciseId = plankId,
                position = 2,
                createdAt = now,
                updatedAt = now,
            ),
        )
        db.workSetDetailDao().insert(
            WorkSetDetailEntity(
                workSetId = plankWorkSetId,
                position = 1,
                targetWeight = null,
                targetReps = null,
                targetDuration = 120,
                targetDistance = null,
                setType = SetType.NORMAL,
                weightUnit = WeightUnit.METRIC,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return SeedIds(
            benchExerciseId = benchId,
            plankExerciseId = plankId,
            routineId = routineId,
        )
    }

    private suspend fun awaitCondition(
        timeoutMs: Long = 5_000L,
        message: String = "Timed out waiting for condition",
        predicate: () -> Boolean,
    ) {
        val start = TimeSource.Monotonic.markNow()
        while (!predicate()) {
            if (start.elapsedNow().inWholeMilliseconds >= timeoutMs) {
                fail(message)
            }
            delay(10L)
        }
    }

    @Test
    fun liveRoutineSessionFullLifecyclePersistsAndPrunesOnFinish() = runBlocking {
        val db = openTestDb()
        var clock = 1_794_744_000_000L
        val finishedCallbacks = mutableListOf<Long>()
        val deletedCallbacks = mutableListOf<Long>()
        val service = WorkoutSessionService(
            db = db,
            dispatcher = Dispatchers.Default,
            onWorkoutFinished = { finishedCallbacks += it },
            onWorkoutDeleted = { deletedCallbacks += it },
            nowProvider = { clock },
            autosaveDebounceMs = 20L,
        )
        var form: WorkoutSessionFormState? = null
        try {
            val seed = seedRoutineFixtures(db)

            val sessionId = service.startFromRoutine(seed.routineId)
            val createdSession = db.workoutSessionDao().findById(sessionId)
            assertNotNull(createdSession)
            assertEquals("Push Day", createdSession.title)
            assertNull(createdSession.completedAt)
            assertEquals(4, db.workoutSessionDetailDao().getForSession(sessionId).size)
            awaitCondition(message = "activeSession did not emit created session") {
                service.activeSession.value?.sessionId == sessionId
            }

            val liveForm = service.formState(sessionId).also { form = it }
            awaitCondition(message = "form did not finish loading") { !liveForm.uiState.value.loading }

            assertEquals("Push Day", liveForm.uiState.value.sessionTitle)
            assertEquals(2, liveForm.uiState.value.exercises.size)
            assertEquals(3, liveForm.uiState.value.exercises[0].sets.size)
            assertEquals(1, liveForm.uiState.value.exercises[1].sets.size)
            assertFalse(liveForm.uiState.value.hasCompletedSets)
            assertFalse(service.hasCompletedSets(sessionId))

            // Warmup set
            liveForm.updateActualWeight(0, 0, "40")
            liveForm.updateActualReps(0, 0, "10")
            liveForm.toggleCompleted(0, 0)
            assertFalse(liveForm.uiState.value.hasCompletedSets)
            assertEquals(liveForm.uiState.value.exercises[0].id, service.activeRestTimer.value?.exerciseId)
            assertEquals(0, service.activeRestTimer.value?.setIndex)

            // 1st working bench set
            liveForm.updateActualWeight(0, 1, "82.5")
            liveForm.updateActualReps(0, 1, "5")
            liveForm.toggleCompleted(0, 1)
            assertEquals(1, liveForm.uiState.value.restTimer?.setIndex)
            assertEquals(1, service.activeRestTimer.value?.setIndex)

            // Leave 2nd working bench set incomplete (weight typed, no reps, unchecked)
            liveForm.updateActualWeight(0, 2, "82.5")

            // Plank duration set (bare minutes "2" -> 120s)
            liveForm.updateActualDuration(1, 0, "2")
            liveForm.toggleCompleted(1, 0)
            assertEquals(liveForm.uiState.value.exercises[1].id, service.activeRestTimer.value?.exerciseId)
            assertEquals(0, service.activeRestTimer.value?.setIndex)

            liveForm.updateTitle("Heavy Push")
            assertTrue(service.hasCompletedSets(sessionId))

            var saved = false
            liveForm.save { saved = true }
            awaitCondition(message = "explicit form.save() did not complete") { saved }

            assertEquals("Heavy Push", db.workoutSessionDao().findById(sessionId)?.title)
            assertEquals(4, db.workoutSessionDetailDao().getForSession(sessionId).size)

            // Advance 45 minutes and finish
            clock += 2_700_000L
            liveForm.finish()
            awaitCondition(message = "form.finish() did not complete") { liveForm.uiState.value.finished }

            val finishedSession = db.workoutSessionDao().findById(sessionId)
            assertNotNull(finishedSession)
            assertEquals(clock, finishedSession.completedAt)
            assertEquals("Heavy Push", finishedSession.title)

            val persistedDetails = db.workoutSessionDetailDao().getForSession(sessionId)
            assertEquals(3, persistedDetails.size)
            assertEquals(seed.benchExerciseId, persistedDetails[0].exerciseId)
            assertEquals(SetType.WARMUP, persistedDetails[0].setType)
            assertEquals(40.0, persistedDetails[0].actualWeight)
            assertEquals(10, persistedDetails[0].actualReps)
            assertTrue(persistedDetails[0].completed)

            assertEquals(seed.benchExerciseId, persistedDetails[1].exerciseId)
            assertEquals(SetType.NORMAL, persistedDetails[1].setType)
            assertEquals(82.5, persistedDetails[1].actualWeight)
            assertEquals(5, persistedDetails[1].actualReps)
            assertTrue(persistedDetails[1].completed)

            assertEquals(seed.plankExerciseId, persistedDetails[2].exerciseId)
            assertEquals(SetType.NORMAL, persistedDetails[2].setType)
            assertEquals(120, persistedDetails[2].actualDuration)
            assertTrue(persistedDetails[2].completed)

            awaitCondition(message = "activeSession did not clear after finish") {
                service.activeSession.value == null
            }
            assertNull(service.activeRestTimer.value)

            val celebration = service.completionCelebration.value
            assertNotNull(celebration)
            assertEquals(sessionId, celebration.sessionId)
            assertEquals("Heavy Push", celebration.workoutTitle)
            assertEquals(2, celebration.completedSets)
            assertEquals(2, celebration.exerciseCount)
            assertEquals(2700L, celebration.durationSeconds)
            assertEquals(listOf(sessionId), finishedCallbacks)
            assertTrue(deletedCallbacks.isEmpty())
        } finally {
            form?.closeQuietly()
            service.close()
            db.close()
        }
    }

    @Test
    fun externalMiniBarFinishFlushesPendingFormEdits() = runBlocking {
        val db = openTestDb()
        var clock = 1_794_744_000_000L
        val finishedCallbacks = mutableListOf<Long>()
        val service = WorkoutSessionService(
            db = db,
            dispatcher = Dispatchers.Default,
            onWorkoutFinished = { finishedCallbacks += it },
            nowProvider = { clock },
            // Keep autosave far in the future so only flushBeforeExternalFinish persists edits.
            autosaveDebounceMs = 60_000L,
        )
        var form: WorkoutSessionFormState? = null
        try {
            val seed = seedRoutineFixtures(db)
            val sessionId = service.startFromRoutine(seed.routineId)
            val liveForm = service.formState(sessionId).also { form = it }
            awaitCondition(message = "form did not finish loading") { !liveForm.uiState.value.loading }

            liveForm.updateActualWeight(0, 1, "85")
            liveForm.updateActualReps(0, 1, "6")
            liveForm.toggleCompleted(0, 1)
            assertTrue(service.hasCompletedSets(sessionId))

            clock += 600_000L
            service.finish(sessionId)

            assertTrue(liveForm.uiState.value.finished)
            val finishedSession = db.workoutSessionDao().findById(sessionId)
            assertNotNull(finishedSession)
            assertEquals(clock, finishedSession.completedAt)

            val persistedDetails = db.workoutSessionDetailDao().getForSession(sessionId)
            assertEquals(1, persistedDetails.size)
            assertEquals(seed.benchExerciseId, persistedDetails[0].exerciseId)
            assertEquals(85.0, persistedDetails[0].actualWeight)
            assertEquals(6, persistedDetails[0].actualReps)
            assertTrue(persistedDetails[0].completed)
            assertEquals(listOf(sessionId), finishedCallbacks)
        } finally {
            form?.closeQuietly()
            service.close()
            db.close()
        }
    }

    @Test
    fun resumeOrStartReusesMatchingSessionAndDiscardCascadesDelete() = runBlocking {
        val db = openTestDb()
        val finishedCallbacks = mutableListOf<Long>()
        val service = WorkoutSessionService(
            db = db,
            dispatcher = Dispatchers.Default,
            onWorkoutFinished = { finishedCallbacks += it },
            nowProvider = { 1_794_744_000_000L },
            autosaveDebounceMs = 20L,
        )
        var form: WorkoutSessionFormState? = null
        try {
            val seed = seedRoutineFixtures(db)
            val sessionId = service.startFromRoutine(seed.routineId)
            assertEquals(sessionId, service.resumeOrStart(seed.routineId))

            awaitCondition(message = "activeSession did not emit session") {
                service.activeSession.value?.sessionId == sessionId
            }

            val liveForm = service.formState(sessionId).also { form = it }
            awaitCondition(message = "form did not finish loading") { !liveForm.uiState.value.loading }

            liveForm.discard()
            awaitCondition(message = "form.discard() did not complete") { liveForm.uiState.value.discarded }

            assertNull(db.workoutSessionDao().findById(sessionId))
            assertTrue(db.workoutSessionDetailDao().getForSession(sessionId).isEmpty())
            awaitCondition(message = "activeSession did not clear after discard") {
                service.activeSession.value == null
            }
            assertTrue(finishedCallbacks.isEmpty())
        } finally {
            form?.closeQuietly()
            service.close()
            db.close()
        }
    }

    @Test
    fun logEditAndDeleteCompletedWorkoutWithPreviousSetLookup() = runBlocking {
        val db = openTestDb()
        var clock = 1_700_000_000_000L
        val finishedCallbacks = mutableListOf<Long>()
        val deletedCallbacks = mutableListOf<Long>()
        val service = WorkoutSessionService(
            db = db,
            dispatcher = Dispatchers.Default,
            onWorkoutFinished = { finishedCallbacks += it },
            onWorkoutDeleted = { deletedCallbacks += it },
            nowProvider = { clock },
            autosaveDebounceMs = 20L,
        )
        var priorForm: WorkoutSessionFormState? = null
        var logForm: WorkoutSessionFormState? = null
        var editForm: WorkoutSessionFormState? = null
        var editForm2: WorkoutSessionFormState? = null
        try {
            val seed = seedRoutineFixtures(db)

            // Finish a prior live workout so previous-set lookup has real completed history.
            val priorSessionId = service.startFromRoutine(seed.routineId)
            val livePrior = service.formState(priorSessionId).also { priorForm = it }
            awaitCondition(message = "priorForm did not load") { !livePrior.uiState.value.loading }
            livePrior.updateActualWeight(0, 1, "80")
            livePrior.updateActualReps(0, 1, "5")
            livePrior.toggleCompleted(0, 1)
            clock += 600_000L
            livePrior.finish()
            awaitCondition(message = "priorForm did not finish") { livePrior.uiState.value.finished }
            val finishedBefore = finishedCallbacks.size
            assertEquals(1, finishedBefore)

            // Log a completed workout via journal log mode.
            val log = service.newCompletedLogFormState(epochDay = 20_500L).also { logForm = it }
            awaitCondition(message = "logForm did not load") { !log.uiState.value.loading }
            log.loadRoutine(seed.routineId)
            awaitCondition(message = "logForm did not populate routine exercises") {
                log.uiState.value.exercises.isNotEmpty()
            }

            assertEquals(listOf("80kg x 5"), log.uiState.value.exercises[0].previousSets)
            log.updateActualWeight(0, 1, "82.5")
            log.updateActualReps(0, 1, "5")
            assertTrue(log.uiState.value.canSaveCompleted)

            clock = 1_794_744_000_000L
            log.saveCompleted()
            awaitCondition(message = "logForm.saveCompleted() did not finish") { log.uiState.value.finished }

            val allSessions = db.workoutSessionDao().getAll()
            assertEquals(2, allSessions.size)
            val loggedSession = allSessions.first { it.id != priorSessionId }
            assertNotNull(loggedSession.completedAt)
            assertEquals(loggedSession.performedAt, loggedSession.completedAt)
            assertEquals("Push Day", loggedSession.title)

            val loggedDetails = db.workoutSessionDetailDao().getForSession(loggedSession.id)
            assertEquals(1, loggedDetails.size)
            assertEquals(seed.benchExerciseId, loggedDetails[0].exerciseId)
            assertEquals(82.5, loggedDetails[0].actualWeight)
            assertEquals(5, loggedDetails[0].actualReps)
            assertTrue(loggedDetails[0].completed)
            // Journal-logged workouts must not invoke onWorkoutFinished.
            assertEquals(finishedBefore, finishedCallbacks.size)

            // Edit the logged completed workout in-place.
            val edit = service.editCompletedFormState(loggedSession.id).also { editForm = it }
            awaitCondition(message = "editForm did not load") { !edit.uiState.value.loading }
            edit.updateActualReps(0, 0, "8")
            edit.updateTitle("Edited Push")
            assertTrue(edit.uiState.value.canSaveCompleted)

            edit.saveCompleted()
            awaitCondition(message = "editForm.saveCompleted() did not finish") { edit.uiState.value.finished }

            val updatedSession = db.workoutSessionDao().findById(loggedSession.id)
            assertEquals("Edited Push", updatedSession?.title)
            val updatedDetails = db.workoutSessionDetailDao().getForSession(loggedSession.id)
            assertEquals(1, updatedDetails.size)
            assertEquals(8, updatedDetails[0].actualReps)

            // Delete the completed workout.
            val edit2 = service.editCompletedFormState(loggedSession.id).also { editForm2 = it }
            awaitCondition(message = "editForm2 did not load") { !edit2.uiState.value.loading }
            edit2.deleteCompletedSession()
            awaitCondition(message = "editForm2.deleteCompletedSession() did not delete") {
                edit2.uiState.value.deleted
            }

            assertNull(db.workoutSessionDao().findById(loggedSession.id))
            assertTrue(db.workoutSessionDetailDao().getForSession(loggedSession.id).isEmpty())
            assertEquals(listOf(loggedSession.id), deletedCallbacks)
        } finally {
            priorForm?.closeQuietly()
            logForm?.closeQuietly()
            editForm?.closeQuietly()
            editForm2?.closeQuietly()
            service.close()
            db.close()
        }
    }
}
