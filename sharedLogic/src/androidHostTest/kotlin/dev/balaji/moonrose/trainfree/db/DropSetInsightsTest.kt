package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.calendar.WeekStart
import dev.balaji.moonrose.trainfree.insights.OneRmFormula
import dev.balaji.moonrose.trainfree.model.SetType
import dev.balaji.moonrose.trainfree.model.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DropSetInsightsTest {

    @Test
    fun weeklyVolumeAndBodyPartBreakdownKeepVolumeButExcludeDropSetsFromSetCounts() {
        val today = 20_010L
        val sets = listOf(
            SetPoint(today - 7, 1_000L, 1L, "Barbell Bench Press", "chest", 100.0, 5, 0, 0.0, WeightUnit.METRIC, SetType.NORMAL),
            SetPoint(today - 7, 1_000L, 1L, "Barbell Bench Press", "chest", 60.0, 10, 0, 0.0, WeightUnit.METRIC, SetType.DROP),
            SetPoint(today, 2_000L, 1L, "Barbell Bench Press", "chest", 100.0, 5, 0, 0.0, WeightUnit.METRIC, SetType.NORMAL),
            SetPoint(today, 2_000L, 1L, "Barbell Bench Press", "chest", 50.0, 12, 0, 0.0, WeightUnit.METRIC, SetType.DROP),
            SetPoint(today, 2_000L, 1L, "Barbell Bench Press", "chest", 40.0, 15, 0, 0.0, WeightUnit.METRIC, SetType.DROP),
        )

        val weekly = weeklyVolumeFrom(sets, weeks = 2, todayEpochDay = today)
        assertEquals(2, weekly.size)
        // Prior 7-day window: 100*5 + 60*10 = 1100 volume, 1 normal set.
        assertEquals(1100.0, weekly[0].totalVolume, 0.01)
        assertEquals(1, weekly[0].setCount)
        assertEquals(1, weekly[0].sessionCount)
        // Current 7-day window: 100*5 + 50*12 + 40*15 = 1700 volume, 1 normal set.
        assertEquals(1700.0, weekly[1].totalVolume, 0.01)
        assertEquals(1, weekly[1].setCount)
        assertEquals(1, weekly[1].sessionCount)

        val breakdown = bodyPartBreakdownFrom(sets)
        assertEquals(1, breakdown.size)
        assertEquals("chest", breakdown[0].bodyPart)
        assertEquals(2, breakdown[0].setCount)
    }

    @Test
    fun oneRmPrsAndProgressionIgnoreDropSetsEvenWithHigherEstimated1Rm() {
        val today = 20_020L
        // Normal sets: 100 kg x 5 -> Epley e1RM = 116.67.
        // Drop set: 80 kg x 20 -> Epley e1RM = 133.33 (higher, must be ignored for e1RM/PRs).
        // Drop-only exercise (Lateral Raise): must not appear in PRs, progression, or eligible options.
        val sets = listOf(
            SetPoint(today - 7, 1_000L, 1L, "Barbell Bench Press", "chest", 95.0, 5, 0, 0.0, WeightUnit.METRIC, SetType.NORMAL),
            SetPoint(today, 2_000L, 1L, "Barbell Bench Press", "chest", 100.0, 5, 0, 0.0, WeightUnit.METRIC, SetType.NORMAL),
            SetPoint(today, 2_000L, 1L, "Barbell Bench Press", "chest", 80.0, 20, 0, 0.0, WeightUnit.METRIC, SetType.DROP),
            SetPoint(today - 7, 1_000L, 2L, "Dumbbell Lateral Raise", "shoulders", 10.0, 15, 0, 0.0, WeightUnit.METRIC, SetType.DROP),
            SetPoint(today, 2_000L, 2L, "Dumbbell Lateral Raise", "shoulders", 10.0, 18, 0, 0.0, WeightUnit.METRIC, SetType.DROP),
        )

        val prs = personalRecordsFrom(sets, limit = 5, formula = OneRmFormula.EPLEY)
        assertEquals(1, prs.size)
        assertEquals(1L, prs[0].exerciseId)
        assertEquals(100.0, prs[0].weight, 0.01)
        assertEquals(5, prs[0].reps)

        val prog = progressionFrom(sets, "Barbell Bench Press", today, WeekStart.SUNDAY, OneRmFormula.EPLEY)
        assertNotNull(prog)
        assertEquals(OneRmFormula.EPLEY.estimate(100.0, 5), prog.weeklyBest1Rm.last().value, 0.01)
        assertNull(progressionFrom(sets, "Dumbbell Lateral Raise", today, WeekStart.SUNDAY, OneRmFormula.EPLEY))

        val weekly1Rm = singleExerciseWeeklyOneRm(
            sets.filter { it.exerciseId == 1L },
            weeks = 2,
            todayEpochDay = today,
            weekStart = WeekStart.SUNDAY,
            formula = OneRmFormula.EPLEY,
        )
        assertEquals(OneRmFormula.EPLEY.estimate(100.0, 5), weekly1Rm.last().value, 0.01)

        val weeklyVol = singleExerciseWeeklyVolume(
            sets.filter { it.exerciseId == 1L },
            weeks = 2,
            todayEpochDay = today,
            weekStart = WeekStart.SUNDAY,
        )
        assertEquals(100.0 * 5 + 80.0 * 20, weeklyVol.last().value, 0.01)

        val eligible = eligibleProgressionExercises(sets, today, WeekStart.SUNDAY)
        assertEquals(listOf("Barbell Bench Press"), eligible)
        assertTrue("Dumbbell Lateral Raise" !in eligible)
    }
}
