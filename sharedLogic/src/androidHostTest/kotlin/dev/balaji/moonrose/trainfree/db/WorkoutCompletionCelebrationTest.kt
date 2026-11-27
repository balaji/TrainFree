package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.insights.OneRmFormula
import dev.balaji.moonrose.trainfree.model.CELEBRATORY_EMOJIS
import dev.balaji.moonrose.trainfree.model.NewOneRepMaxAchievement
import dev.balaji.moonrose.trainfree.model.SetType
import dev.balaji.moonrose.trainfree.model.WeightUnit
import dev.balaji.moonrose.trainfree.model.buildWorkoutCompletionCelebration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkoutCompletionCelebrationTest {

    private fun detail(
        exerciseId: Long,
        position: Int,
        setType: SetType = SetType.NORMAL,
        actualWeight: Double? = 60.0,
        actualReps: Int? = 8,
        weightUnit: WeightUnit = WeightUnit.METRIC,
    ): WorkoutSessionDetailEntity = WorkoutSessionDetailEntity(
        workoutSessionId = 1L,
        workSetDetailId = null,
        exerciseId = exerciseId,
        position = position,
        targetWeight = null,
        targetReps = null,
        targetDuration = null,
        targetDistance = null,
        actualWeight = actualWeight,
        actualReps = actualReps,
        actualDuration = null,
        actualDistance = null,
        setType = setType,
        weightUnit = weightUnit,
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
    fun summarizeCompletedSessionExcludesWarmupsAndDropSetsWhenNormalSetsExist() {
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
        assertEquals(2, celebration.completedSets)
        assertEquals(2, celebration.exerciseCount)
        assertEquals(2400L, celebration.durationSeconds)
        assertTrue(celebration.emoji in CELEBRATORY_EMOJIS)
    }

    @Test
    fun summarizeCompletedSessionCountsDropSetsWhenNoNormalSetsExist() {
        val details = listOf(
            detail(exerciseId = 10L, position = 1, setType = SetType.WARMUP),
            detail(exerciseId = 10L, position = 2, setType = SetType.DROP),
            detail(exerciseId = 20L, position = 3, setType = SetType.DROP),
        )

        val celebration = summarizeCompletedSession(
            sessionId = 6L,
            workoutTitle = "Finisher",
            performedAtMillis = 10_000L,
            completedAtMillis = 610_000L,
            keptDetails = details,
        )

        assertEquals(2, celebration.completedSets)
        assertEquals(2, celebration.exerciseCount)
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

    @Test
    fun buildCelebrationUsesTrophyEmojiWhenNewOneRepMaxesExist() {
        val pr = NewOneRepMaxAchievement(
            exerciseId = 10L,
            exerciseName = "Barbell Bench Press",
            formattedNewOneRm = "110",
            formattedGain = "5",
            newOneRm = 110.0,
            previousOneRm = 105.0,
        )
        for (seed in 0L until CELEBRATORY_EMOJIS.size.toLong()) {
            val celebration = buildWorkoutCompletionCelebration(
                sessionId = 3L,
                workoutTitle = "Push Day",
                completedSets = 4,
                exerciseCount = 1,
                durationSeconds = 900L,
                seed = seed,
                newOneRepMaxes = listOf(pr),
                weightUnit = WeightUnit.IMPERIAL,
            )
            assertEquals("🏆", celebration.emoji)
            assertEquals(listOf(pr), celebration.newOneRepMaxes)
            assertEquals(WeightUnit.IMPERIAL, celebration.weightUnit)
        }
    }

    @Test
    fun detectNewOneRepMaxesReturnsEmptyWhenNoPriorHistory() {
        val details = listOf(
            detail(exerciseId = 10L, position = 1, actualWeight = 100.0, actualReps = 5),
        )
        val result = detectNewOneRepMaxes(
            keptDetails = details,
            priorSets = emptyList(),
            exerciseNames = mapOf(10L to "Barbell Squat"),
            formula = OneRmFormula.EPLEY,
            displayUnit = WeightUnit.METRIC,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun detectNewOneRepMaxesDetectsPrsPreservesWorkoutOrderAndPicksBestSetPerExercise() {
        val details = listOf(
            // Exercise 20 appears first: prior best 100*1 = 100. Best here: 90*(1+5/30) = 105 (+5)
            detail(exerciseId = 20L, position = 1, actualWeight = 85.0, actualReps = 5),
            detail(exerciseId = 20L, position = 2, actualWeight = 90.0, actualReps = 5),
            // Exercise 30 ties prior best (120*1 = 120): should not count as a new PR
            detail(exerciseId = 30L, position = 3, actualWeight = 120.0, actualReps = 1),
            // Exercise 40 below prior best (150 vs 160): should not count
            detail(exerciseId = 40L, position = 4, actualWeight = 150.0, actualReps = 1),
            // Exercise 10 appears second PR: prior best 80*1 = 80. Best here: 80*(1+3/30) = 88 (+8)
            detail(exerciseId = 10L, position = 5, actualWeight = 80.0, actualReps = 3),
        )
        val prior = listOf(
            ExerciseSetLoadRow(exerciseId = 10L, actualWeight = 80.0, actualReps = 1, weightUnit = WeightUnit.METRIC),
            ExerciseSetLoadRow(exerciseId = 20L, actualWeight = 100.0, actualReps = 1, weightUnit = WeightUnit.METRIC),
            ExerciseSetLoadRow(exerciseId = 30L, actualWeight = 100.0, actualReps = 6, weightUnit = WeightUnit.METRIC),
            ExerciseSetLoadRow(exerciseId = 40L, actualWeight = 160.0, actualReps = 1, weightUnit = WeightUnit.METRIC),
        )
        val names = mapOf(
            10L to "Overhead Press",
            20L to "Barbell Bench Press",
            30L to "Barbell Squat",
            40L to "Deadlift",
        )

        val result = detectNewOneRepMaxes(
            keptDetails = details,
            priorSets = prior,
            exerciseNames = names,
            formula = OneRmFormula.EPLEY,
            displayUnit = WeightUnit.METRIC,
        )

        assertEquals(2, result.size)
        assertEquals(20L, result[0].exerciseId)
        assertEquals("Barbell Bench Press", result[0].exerciseName)
        assertEquals("105", result[0].formattedNewOneRm)
        assertEquals("5", result[0].formattedGain)

        assertEquals(10L, result[1].exerciseId)
        assertEquals("Overhead Press", result[1].exerciseName)
        assertEquals("88", result[1].formattedNewOneRm)
        assertEquals("8", result[1].formattedGain)
    }

    @Test
    fun detectNewOneRepMaxesIgnoresWarmupsAndDropSets() {
        val details = listOf(
            // Heavy warmup on exercise 10 must be ignored
            detail(exerciseId = 10L, position = 1, setType = SetType.WARMUP, actualWeight = 200.0, actualReps = 1),
            detail(exerciseId = 10L, position = 2, setType = SetType.NORMAL, actualWeight = 90.0, actualReps = 1),
            // Heavy / high-rep drop set on exercise 20 must also be ignored
            detail(exerciseId = 20L, position = 3, setType = SetType.DROP, actualWeight = 105.0, actualReps = 1),
            // Normal set on exercise 30 beats prior 100
            detail(exerciseId = 30L, position = 4, setType = SetType.NORMAL, actualWeight = 105.0, actualReps = 1),
        )
        val prior = listOf(
            ExerciseSetLoadRow(exerciseId = 10L, actualWeight = 100.0, actualReps = 1, weightUnit = WeightUnit.METRIC),
            ExerciseSetLoadRow(exerciseId = 20L, actualWeight = 100.0, actualReps = 1, weightUnit = WeightUnit.METRIC),
            ExerciseSetLoadRow(exerciseId = 30L, actualWeight = 100.0, actualReps = 1, weightUnit = WeightUnit.METRIC),
        )
        val names = mapOf(10L to "Squat", 20L to "Lat Pulldown", 30L to "Cable Row")

        val result = detectNewOneRepMaxes(
            keptDetails = details,
            priorSets = prior,
            exerciseNames = names,
            formula = OneRmFormula.EPLEY,
            displayUnit = WeightUnit.METRIC,
        )

        assertEquals(1, result.size)
        assertEquals(30L, result[0].exerciseId)
        assertEquals("105", result[0].formattedNewOneRm)
        assertEquals("5", result[0].formattedGain)
    }

    @Test
    fun detectNewOneRepMaxesHandlesUnitConversionAndFormula() {
        // Prior 100 kg = ~220.462 lb. Current 220.5 lb rounds to 220.5 tenths, same as 220.5 tenths for prior (tie -> no PR).
        // Exercise 20: Prior 100 kg (~220.5 lb), current 200 lb x 5 reps with Brzycki: 200 * 36 / (37 - 5) = 225 lb (+4.5 lb).
        val details = listOf(
            detail(
                exerciseId = 10L,
                position = 1,
                actualWeight = 220.5,
                actualReps = 1,
                weightUnit = WeightUnit.IMPERIAL,
            ),
            detail(
                exerciseId = 20L,
                position = 2,
                actualWeight = 200.0,
                actualReps = 5,
                weightUnit = WeightUnit.IMPERIAL,
            ),
        )
        val prior = listOf(
            ExerciseSetLoadRow(exerciseId = 10L, actualWeight = 100.0, actualReps = 1, weightUnit = WeightUnit.METRIC),
            ExerciseSetLoadRow(exerciseId = 20L, actualWeight = 100.0, actualReps = 1, weightUnit = WeightUnit.METRIC),
        )
        val names = mapOf(10L to "Row", 20L to "Bench Press")

        val result = detectNewOneRepMaxes(
            keptDetails = details,
            priorSets = prior,
            exerciseNames = names,
            formula = OneRmFormula.BRZYCKI,
            displayUnit = WeightUnit.IMPERIAL,
        )

        assertEquals(1, result.size)
        assertEquals(20L, result[0].exerciseId)
        assertEquals("225", result[0].formattedNewOneRm)
        assertEquals("4.5", result[0].formattedGain)
    }

    @Test
    fun detectNewOneRepMaxesIgnoresUnweightedOrZeroRepSets() {
        val details = listOf(
            detail(exerciseId = 10L, position = 1, actualWeight = null, actualReps = 15),
            detail(exerciseId = 10L, position = 2, actualWeight = 0.0, actualReps = 15),
            detail(exerciseId = 10L, position = 3, actualWeight = 100.0, actualReps = 0),
        )
        val prior = listOf(
            ExerciseSetLoadRow(exerciseId = 10L, actualWeight = 50.0, actualReps = 1, weightUnit = WeightUnit.METRIC),
        )
        val result = detectNewOneRepMaxes(
            keptDetails = details,
            priorSets = prior,
            exerciseNames = mapOf(10L to "Pull-Up"),
            formula = OneRmFormula.EPLEY,
            displayUnit = WeightUnit.METRIC,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun summarizeCompletedSessionForwardsNewOneRepMaxesAndWeightUnit() {
        val pr = NewOneRepMaxAchievement(
            exerciseId = 10L,
            exerciseName = "Deadlift",
            formattedNewOneRm = "180",
            formattedGain = "10",
            newOneRm = 180.0,
            previousOneRm = 170.0,
        )
        val celebration = summarizeCompletedSession(
            sessionId = 9L,
            workoutTitle = "Pull Day",
            performedAtMillis = 10_000L,
            completedAtMillis = 610_000L,
            keptDetails = listOf(detail(exerciseId = 10L, position = 1, actualWeight = 180.0, actualReps = 1)),
            newOneRepMaxes = listOf(pr),
            weightUnit = WeightUnit.IMPERIAL,
        )
        assertEquals("🏆", celebration.emoji)
        assertEquals(listOf(pr), celebration.newOneRepMaxes)
        assertEquals(WeightUnit.IMPERIAL, celebration.weightUnit)
    }
}
