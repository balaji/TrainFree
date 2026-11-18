package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.model.CELEBRATORY_EMOJIS
import dev.balaji.moonrose.trainfree.model.SetType
import dev.balaji.moonrose.trainfree.model.buildWorkoutCompletionCelebration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkoutCompletionCelebrationTest {

    private fun detail(
        exerciseId: Long,
        position: Int,
        setType: SetType = SetType.NORMAL,
    ): WorkoutSessionDetailEntity = WorkoutSessionDetailEntity(
        workoutSessionId = 1L,
        workSetDetailId = null,
        exerciseId = exerciseId,
        position = position,
        targetWeight = null,
        targetReps = null,
        targetDuration = null,
        targetDistance = null,
        actualWeight = 60.0,
        actualReps = 8,
        actualDuration = null,
        actualDistance = null,
        setType = setType,
        completed = true,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    @Test
    fun buildCelebrationHandlesPositiveZeroAndNegativeSeedsAndClampsCounts() {
        val seeds = listOf(0L, 1L, 42L, -1L, -999_999L, Long.MIN_VALUE, Long.MAX_VALUE)
        for (seed in seeds) {
            val celebration = buildWorkoutCompletionCelebration(
                sessionId = 7L,
                workoutTitle = "Push Day",
                completedSets = -3,
                exerciseCount = -1,
                durationSeconds = -120L,
                seed = seed,
            )
            assertEquals(7L, celebration.sessionId)
            assertEquals("Push Day", celebration.workoutTitle)
            assertEquals(0, celebration.completedSets)
            assertEquals(0, celebration.exerciseCount)
            assertEquals(0L, celebration.durationSeconds)
            assertTrue(celebration.emoji in CELEBRATORY_EMOJIS)
        }
    }

    @Test
    fun buildCelebrationCyclesThroughAllEmojis() {
        val seenEmojis = (0L until CELEBRATORY_EMOJIS.size.toLong()).map { seed ->
            buildWorkoutCompletionCelebration(
                sessionId = 1L,
                workoutTitle = "Leg Day",
                completedSets = 5,
                exerciseCount = 2,
                durationSeconds = 1800L,
                seed = seed,
            ).emoji
        }.toSet()
        assertEquals(CELEBRATORY_EMOJIS.toSet(), seenEmojis)
    }

    @Test
    fun summarizeCompletedSessionExcludesWarmupsWhenWorkingSetsExist() {
        val details = listOf(
            detail(exerciseId = 10L, position = 1, setType = SetType.WARMUP),
            detail(exerciseId = 10L, position = 2, setType = SetType.NORMAL),
            detail(exerciseId = 10L, position = 3, setType = SetType.DROP),
            detail(exerciseId = 20L, position = 4, setType = SetType.WARMUP),
            detail(exerciseId = 30L, position = 5, setType = SetType.NORMAL),
        )

        val celebration = summarizeCompletedSession(
            sessionId = 42L,
            workoutTitle = "Upper Body",
            performedAtMillis = 10_000L,
            completedAtMillis = 2_410_000L,
            keptDetails = details,
        )

        assertEquals(42L, celebration.sessionId)
        assertEquals("Upper Body", celebration.workoutTitle)
        assertEquals(3, celebration.completedSets)
        assertEquals(2, celebration.exerciseCount)
        assertEquals(2400L, celebration.durationSeconds)
        assertTrue(celebration.emoji in CELEBRATORY_EMOJIS)
    }

    @Test
    fun summarizeCompletedSessionFallsBackToKeptSetsWhenWarmupOnlyAndClampsNegativeDuration() {
        val details = listOf(
            detail(exerciseId = 10L, position = 1, setType = SetType.WARMUP),
            detail(exerciseId = 10L, position = 2, setType = SetType.WARMUP),
        )

        val celebration = summarizeCompletedSession(
            sessionId = 5L,
            workoutTitle = "Mobility",
            performedAtMillis = 50_000L,
            completedAtMillis = 10_000L,
            keptDetails = details,
        )

        assertEquals(2, celebration.completedSets)
        assertEquals(1, celebration.exerciseCount)
        assertEquals(0L, celebration.durationSeconds)
    }
}
