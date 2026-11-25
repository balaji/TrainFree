package dev.balaji.moonrose.trainfree

import dev.balaji.moonrose.trainfree.model.Profile
import dev.balaji.moonrose.trainfree.screens.formatCountdown
import dev.balaji.moonrose.trainfree.screens.formatMonthDay
import dev.balaji.moonrose.trainfree.screens.formatMonthYear
import dev.balaji.moonrose.trainfree.screens.initialBmiInputs
import dev.balaji.moonrose.trainfree.screens.progressionYAxisBounds
import dev.balaji.moonrose.trainfree.screens.remainingSecondsUntil
import dev.balaji.moonrose.trainfree.screens.verticalLineCanvasX
import dev.balaji.moonrose.trainfree.screens.yAxisBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedUICommonTest {

    @Test
    fun formatCountdownFormatsMinutesAndPaddedSeconds() {
        assertEquals("2:00", formatCountdown(120L))
        assertEquals("1:05", formatCountdown(65L))
        assertEquals("0:09", formatCountdown(9L))
        assertEquals("0:00", formatCountdown(0L))
        assertEquals("0:00", formatCountdown(-5L))
    }

    @Test
    fun remainingSecondsUntilCeilsSubSecondRemainders() {
        val endsAt = 200_000L
        assertEquals(120L, remainingSecondsUntil(endsAtMillis = endsAt, nowMillis = 80_000L))
        assertEquals(120L, remainingSecondsUntil(endsAtMillis = endsAt, nowMillis = 80_001L))
        assertEquals(119L, remainingSecondsUntil(endsAtMillis = endsAt, nowMillis = 81_000L))
        assertEquals(1L, remainingSecondsUntil(endsAtMillis = endsAt, nowMillis = 199_999L))
        assertEquals(0L, remainingSecondsUntil(endsAtMillis = endsAt, nowMillis = 200_000L))
        assertEquals(0L, remainingSecondsUntil(endsAtMillis = endsAt, nowMillis = 205_000L))
    }

    @Test
    fun formatMonthDayAndYearUseDayPartsAndLocalizedMonths() {
        val nov14 = 20771L // 2026-11-14
        val may03 = 20211L // 2025-05-03
        assertEquals("Nov-14", formatMonthDay(nov14))
        assertEquals("Nov 26", formatMonthYear(nov14))

        val swedishMonths = listOf("Jan", "Feb", "Mar", "Apr", "Maj", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dec")
        assertEquals("Maj-03", formatMonthDay(may03, swedishMonths))
        assertEquals("Maj 25", formatMonthYear(may03, swedishMonths))
    }

    @Test
    fun yAxisBoundsExpandsFlatAndNarrowSeriesToAtLeastTwoUnits() {
        assertEquals(99.0 to 101.0, yAxisBounds(100.0, 100.0))
        assertEquals(99.0 to 101.0, yAxisBounds(100.0, 101.0))
        val (lo, hi) = yAxisBounds(5.6, 7.2)
        assertTrue(lo <= 5.6 && hi >= 7.2 && hi - lo >= 2.0)
        assertEquals(80.0 to 120.0, yAxisBounds(80.0, 120.0))
    }

    @Test
    fun verticalLineCanvasXAlignsTargetXToGrid() {
        assertEquals(
            20f,
            verticalLineCanvasX(
                targetX = 100.0,
                minX = 100.0,
                xStep = 7.0,
                drawingStart = 20f,
                xSpacing = 40f,
                layoutDirectionMultiplier = 1,
            ),
        )
        assertEquals(
            100f,
            verticalLineCanvasX(
                targetX = 114.0,
                minX = 100.0,
                xStep = 7.0,
                drawingStart = 20f,
                xSpacing = 40f,
                layoutDirectionMultiplier = 1,
            ),
        )
        assertEquals(
            140f,
            verticalLineCanvasX(
                targetX = 121.0,
                minX = 100.0,
                xStep = 7.0,
                drawingStart = 20f,
                xSpacing = 40f,
                layoutDirectionMultiplier = 1,
            ),
        )
        assertEquals(
            120f,
            verticalLineCanvasX(
                targetX = 114.0,
                minX = 100.0,
                xStep = 7.0,
                drawingStart = 200f,
                xSpacing = 40f,
                layoutDirectionMultiplier = -1,
            ),
        )
        assertNull(
            verticalLineCanvasX(
                targetX = 114.0,
                minX = 100.0,
                xStep = 0.0,
                drawingStart = 20f,
                xSpacing = 40f,
                layoutDirectionMultiplier = 1,
            ),
        )
    }

    @Test
    fun progressionYAxisBoundsSupportsNegativePctChangeRanges() {
        assertEquals(-6.0 to -4.0, progressionYAxisBounds(-5.0, -5.0))
        val (lo, hi) = progressionYAxisBounds(-1.2, 0.3)
        assertTrue(lo <= -1.2 && hi >= 0.3 && hi - lo >= 2.0)
        assertEquals(-15.0 to 20.0, progressionYAxisBounds(-15.0, 20.0))
    }

    @Test
    fun initialBmiInputsReturnsInputsOnlyWhenWeightAndHeightArePositiveFinite() {
        val complete = Profile(id = 1L, age = 30, gender = null, weight = 99.0, height = 183.0)
        assertEquals(99.0 to 183.0, initialBmiInputs(complete))

        assertNull(initialBmiInputs(null))
        assertNull(initialBmiInputs(complete.copy(weight = null)))
        assertNull(initialBmiInputs(complete.copy(height = null)))
        assertNull(initialBmiInputs(complete.copy(weight = 0.0)))
        assertNull(initialBmiInputs(complete.copy(weight = -1.0)))
        assertNull(initialBmiInputs(complete.copy(weight = Double.NaN)))
        assertNull(initialBmiInputs(complete.copy(weight = Double.POSITIVE_INFINITY)))
        assertNull(initialBmiInputs(complete.copy(height = 0.0)))
        assertNull(initialBmiInputs(complete.copy(height = -1.0)))
        assertNull(initialBmiInputs(complete.copy(height = Double.NaN)))
        assertNull(initialBmiInputs(complete.copy(height = Double.POSITIVE_INFINITY)))
    }
}
