package dev.balaji.moonrose.trainfree.db

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthSyncSettingsTest {
    @Test
    fun healthSyncDefaultsToFalseWhenUnset() {
        val settings = emptyList<UserSettingEntity>().toUserSettings()
        assertFalse(settings.healthSyncWorkouts)
        assertFalse(settings.healthSyncWeight)
    }

    @Test
    fun healthSyncFlagsMapFromUserSettingEntities() {
        val settings = listOf(
            UserSettingEntity(SETTING_HEALTH_SYNC_WORKOUTS, "true", 1L, 1L),
            UserSettingEntity(SETTING_HEALTH_SYNC_WEIGHT, "true", 1L, 1L),
        ).toUserSettings()
        assertTrue(settings.healthSyncWorkouts)
        assertTrue(settings.healthSyncWeight)
    }
}
