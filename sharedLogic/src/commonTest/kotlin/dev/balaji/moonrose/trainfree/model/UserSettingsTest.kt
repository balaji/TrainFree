package dev.balaji.moonrose.trainfree.model

import dev.balaji.moonrose.trainfree.calendar.WeekStart
import kotlin.test.Test
import kotlin.test.assertEquals

class UserSettingsTest {
    @Test
    fun weekStartFromValue() {
        assertEquals(WeekStart.MONDAY, WeekStart.fromValue("monday"))
        assertEquals(WeekStart.SUNDAY, WeekStart.fromValue("sunday"))
        assertEquals(WeekStart.SUNDAY, WeekStart.fromValue(null))
        assertEquals(WeekStart.SUNDAY, WeekStart.fromValue("garbage"))
    }

    @Test
    fun appearanceFromValue() {
        assertEquals(Appearance.LIGHT, Appearance.fromValue("light"))
        assertEquals(Appearance.DARK, Appearance.fromValue("dark"))
        assertEquals(Appearance.SYSTEM, Appearance.fromValue("system"))
        assertEquals(Appearance.SYSTEM, Appearance.fromValue(null))
        assertEquals(Appearance.SYSTEM, Appearance.fromValue("sepia"))
    }

    @Test
    fun weightUnitFromValue() {
        assertEquals(WeightUnit.METRIC, WeightUnit.fromValue("metric"))
        assertEquals(WeightUnit.IMPERIAL, WeightUnit.fromValue("imperial"))
        assertEquals(WeightUnit.METRIC, WeightUnit.fromValue(null))
        assertEquals(WeightUnit.METRIC, WeightUnit.fromValue("stone"))
    }

    @Test
    fun appFontFromValue() {
        assertEquals(AppFont.SYSTEM_UI, AppFont.fromValue("system_ui"))
        assertEquals(AppFont.GOOGLE_SANS, AppFont.fromValue("google_sans"))
        assertEquals(AppFont.INTER, AppFont.fromValue("inter"))
        assertEquals(AppFont.SYSTEM_UI, AppFont.fromValue(null))
        assertEquals(AppFont.SYSTEM_UI, AppFont.fromValue("comic_sans"))
    }

    @Test
    fun appFontSizeFromValue() {
        assertEquals(AppFontSize.DEFAULT, AppFontSize.fromValue("default"))
        assertEquals(AppFontSize.LARGER, AppFontSize.fromValue("larger"))
        assertEquals(AppFontSize.LARGEST, AppFontSize.fromValue("largest"))
        assertEquals(AppFontSize.DEFAULT, AppFontSize.fromValue(null))
        assertEquals(AppFontSize.DEFAULT, AppFontSize.fromValue("huge"))
        assertEquals(FONT_SIZE_DEFAULT_SCALE, AppFontSize.DEFAULT.scaleFactor)
        assertEquals(FONT_SIZE_LARGER_SCALE, AppFontSize.LARGER.scaleFactor)
        assertEquals(FONT_SIZE_LARGEST_SCALE, AppFontSize.LARGEST.scaleFactor)
    }

    @Test
    fun everyEnumEntryRoundTripsThroughItsValueKey() {
        WeekStart.entries.forEach { assertEquals(it, WeekStart.fromValue(it.value)) }
        Appearance.entries.forEach { assertEquals(it, Appearance.fromValue(it.value)) }
        WeightUnit.entries.forEach { assertEquals(it, WeightUnit.fromValue(it.value)) }
        AppFont.entries.forEach { assertEquals(it, AppFont.fromValue(it.value)) }
        AppFontSize.entries.forEach { assertEquals(it, AppFontSize.fromValue(it.value)) }
    }
}
