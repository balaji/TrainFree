package dev.balaji.moonrose.trainfree.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

@OptIn(ExperimentalTime::class)
class WorkoutTitleTest {

    @Test
    fun defaultWorkoutTitleFormatsZeroPaddedYyyyMmDd() {
        val singleDigitDay = LocalDate(2026, 3, 5).toEpochDays()
        val doubleDigitDay = LocalDate(2026, 11, 15).toEpochDays()

        assertEquals("Workout on 2026/03/05", defaultWorkoutTitle(singleDigitDay))
        assertEquals("Workout on 2026/11/15", defaultWorkoutTitle(doubleDigitDay))
    }

    @Test
    fun defaultWorkoutTitleForMillisUsesProvidedTimezone() {
        val zone = TimeZone.UTC
        val millis = LocalDate(2026, 1, 9).atStartOfDayIn(zone).toEpochMilliseconds() + 3_600_000L

        assertEquals("Workout on 2026/01/09", defaultWorkoutTitleForMillis(millis, zone))
    }

    @Test
    fun filterWorkoutTitleStripsNewlinesPreservesSlashesAndCapsLength() {
        assertEquals(
            "Workout on 2026/11/15",
            filterWorkoutTitle("Workout on 2026/11/15\r\n"),
        )
        assertEquals("Leg/Push", filterWorkoutTitle("Leg/\nPush\r"))
        val tooLong = "A".repeat(MAX_WORKOUT_TITLE_LENGTH + 25)
        assertEquals(MAX_WORKOUT_TITLE_LENGTH, filterWorkoutTitle(tooLong).length)
    }

    @Test
    fun resolveWorkoutTitlePrefersStoredThenRoutineThenDatedDefault() {
        val millis = LocalDate(2026, 11, 15).atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds() +
            12 * 3_600_000L

        assertEquals("Custom Title", resolveWorkoutTitle(" Custom Title ", "Push Day", millis))
        assertEquals("Push Day", resolveWorkoutTitle("   ", "Push Day", millis))
        assertEquals("Workout on 2026/11/15", resolveWorkoutTitle("", null, millis))
    }
}
