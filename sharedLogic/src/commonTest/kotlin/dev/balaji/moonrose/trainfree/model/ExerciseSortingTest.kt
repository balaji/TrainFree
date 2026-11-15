package dev.balaji.moonrose.trainfree.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ExerciseSortingTest {
    private fun exercise(id: Long, name: String) = Exercise(
        id = id,
        name = name,
        exerciseType = "strength",
        bodyPart = "chest",
        equipment = "barbell",
        builtin = true,
    )

    @Test
    fun performedExercisesComeFirstSortedBySetCountDescending() {
        val arnold = exercise(1L, "Arnold Press")
        val bench = exercise(2L, "Bench Press")
        val curl = exercise(3L, "Curl")
        val deadlift = exercise(4L, "Deadlift")

        val sorted = sortExercisesBySetCount(
            exercises = listOf(arnold, bench, curl, deadlift),
            setCounts = mapOf(
                bench.id to 5,
                deadlift.id to 12,
                curl.id to 0,
            ),
        )

        assertEquals(listOf(deadlift, bench, arnold, curl), sorted)
    }

    @Test
    fun equalSetCountsAlphabetizeCaseInsensitively() {
        val zebra = exercise(1L, "Zebra Row")
        val alpha = exercise(2L, "alpha Raise")
        val middle = exercise(3L, "Middle Press")
        val unperformedB = exercise(4L, "biceps Curl")
        val unperformedA = exercise(5L, "Air Squat")

        val sorted = sortExercisesBySetCount(
            exercises = listOf(zebra, unperformedB, alpha, unperformedA, middle),
            setCounts = mapOf(
                zebra.id to 4,
                alpha.id to 4,
                middle.id to 4,
                unperformedB.id to 0,
            ),
        )

        assertEquals(
            listOf(alpha, middle, zebra, unperformedA, unperformedB),
            sorted,
        )
    }
}
