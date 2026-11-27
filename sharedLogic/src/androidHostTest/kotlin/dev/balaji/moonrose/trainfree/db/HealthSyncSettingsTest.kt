package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.health.ExternalWeightSample
import dev.balaji.moonrose.trainfree.health.HealthSyncAvailability
import dev.balaji.moonrose.trainfree.health.HealthSyncProvider
import dev.balaji.moonrose.trainfree.model.UserSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class HealthSyncSettingsTest {
    private class FakeHealthSyncProvider(
        private val availabilityResult: HealthSyncAvailability = HealthSyncAvailability.AVAILABLE,
        private val workoutWritePermitted: Boolean = true,
        private val weightPermitted: Boolean = true,
    ) : HealthSyncProvider {
        data class WrittenWorkout(
            val sessionId: Long,
            val title: String,
            val startMillis: Long,
            val endMillis: Long,
        )

        val writtenWorkouts = mutableListOf<WrittenWorkout>()
        val deletedSessionIds = mutableListOf<Long>()

        override suspend fun availability(): HealthSyncAvailability = availabilityResult

        override suspend fun hasWorkoutWritePermission(): Boolean = workoutWritePermitted

        override suspend fun hasWeightPermissions(): Boolean = weightPermitted

        override suspend fun writeStrengthWorkout(
            sessionId: Long,
            title: String,
            startMillis: Long,
            endMillis: Long,
        ): Boolean {
            writtenWorkouts += WrittenWorkout(sessionId, title, startMillis, endMillis)
            return true
        }

        override suspend fun deleteStrengthWorkout(sessionId: Long): Boolean {
            deletedSessionIds += sessionId
            return true
        }

        override suspend fun writeBodyWeight(
            metricId: Long,
            weightKg: Double,
            loggedAtMillis: Long,
        ): Boolean = true

        override suspend fun readBodyWeights(sinceMillis: Long): List<ExternalWeightSample> = emptyList()
    }

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

    @Test
    fun syncWorkoutEntitiesBackfillsEligibleLiveSessionsOnly() = runBlocking {
        val provider = FakeHealthSyncProvider()
        val sessions = listOf(
            // Eligible live workout with explicit title.
            WorkoutSessionEntity(
                id = 1L,
                profileId = PROFILE_ID,
                routineId = null,
                performedAt = 1_000L,
                completedAt = 3_601_000L,
                title = "Push Day",
                testData = false,
                createdAt = 1_000L,
                updatedAt = 3_601_000L,
            ),
            // Eligible live workout with blank title -> falls back to "Strength Training".
            WorkoutSessionEntity(
                id = 2L,
                profileId = PROFILE_ID,
                routineId = null,
                performedAt = 10_000L,
                completedAt = 20_000L,
                title = "",
                testData = false,
                createdAt = 10_000L,
                updatedAt = 20_000L,
            ),
            // Skipped: still in progress (completedAt == null).
            WorkoutSessionEntity(
                id = 3L,
                profileId = PROFILE_ID,
                routineId = null,
                performedAt = 30_000L,
                completedAt = null,
                title = "Active",
                testData = false,
                createdAt = 30_000L,
                updatedAt = 30_000L,
            ),
            // Skipped: manual journal log (completedAt == performedAt, zero duration).
            WorkoutSessionEntity(
                id = 4L,
                profileId = PROFILE_ID,
                routineId = null,
                performedAt = 40_000L,
                completedAt = 40_000L,
                title = "Logged Past",
                testData = false,
                createdAt = 40_000L,
                updatedAt = 40_000L,
            ),
            // Skipped: developer test data.
            WorkoutSessionEntity(
                id = 5L,
                profileId = PROFILE_ID,
                routineId = null,
                performedAt = 50_000L,
                completedAt = 80_000L,
                title = "Seeded",
                testData = true,
                createdAt = 50_000L,
                updatedAt = 80_000L,
            ),
        )

        val count = HealthSyncManager.syncWorkoutEntities(sessions, provider)

        assertEquals(2, count)
        assertEquals(
            listOf(
                FakeHealthSyncProvider.WrittenWorkout(1L, "Push Day", 1_000L, 3_601_000L),
                FakeHealthSyncProvider.WrittenWorkout(2L, "Strength Training", 10_000L, 20_000L),
            ),
            provider.writtenWorkouts,
        )
    }

    @Test
    fun reconcilePermissionsDisablesBothWhenBothPermissionsRevoked() = runBlocking {
        var workoutsDisabled = false
        var weightDisabled = false
        HealthSyncManager.reconcilePermissions(
            settings = UserSettings(healthSyncWorkouts = true, healthSyncWeight = true),
            activeProvider = FakeHealthSyncProvider(
                workoutWritePermitted = false,
                weightPermitted = false,
            ),
            disableWorkouts = { workoutsDisabled = true },
            disableWeight = { weightDisabled = true },
        )
        assertTrue(workoutsDisabled)
        assertTrue(weightDisabled)
    }

    @Test
    fun reconcilePermissionsDisablesOnlyRevokedSetting() = runBlocking {
        var workoutsDisabled = false
        var weightDisabled = false
        HealthSyncManager.reconcilePermissions(
            settings = UserSettings(healthSyncWorkouts = true, healthSyncWeight = true),
            activeProvider = FakeHealthSyncProvider(
                workoutWritePermitted = false,
                weightPermitted = true,
            ),
            disableWorkouts = { workoutsDisabled = true },
            disableWeight = { weightDisabled = true },
        )
        assertTrue(workoutsDisabled)
        assertFalse(weightDisabled)
    }

    @Test
    fun reconcilePermissionsDisablesBothWhenHealthUnavailable() = runBlocking {
        var workoutsDisabled = false
        var weightDisabled = false
        HealthSyncManager.reconcilePermissions(
            settings = UserSettings(healthSyncWorkouts = true, healthSyncWeight = true),
            activeProvider = FakeHealthSyncProvider(
                availabilityResult = HealthSyncAvailability.NOT_INSTALLED,
                workoutWritePermitted = true,
                weightPermitted = true,
            ),
            disableWorkouts = { workoutsDisabled = true },
            disableWeight = { weightDisabled = true },
        )
        assertTrue(workoutsDisabled)
        assertTrue(weightDisabled)
    }

    @Test
    fun reconcilePermissionsKeepsBothWhenPermissionsGranted() = runBlocking {
        var workoutsDisabled = false
        var weightDisabled = false
        HealthSyncManager.reconcilePermissions(
            settings = UserSettings(healthSyncWorkouts = true, healthSyncWeight = true),
            activeProvider = FakeHealthSyncProvider(
                workoutWritePermitted = true,
                weightPermitted = true,
            ),
            disableWorkouts = { workoutsDisabled = true },
            disableWeight = { weightDisabled = true },
        )
        assertFalse(workoutsDisabled)
        assertFalse(weightDisabled)
    }

    @Test
    fun reconcilePermissionsNoOpsWhenSettingsAlreadyDisabled() = runBlocking {
        var workoutsDisabled = false
        var weightDisabled = false
        HealthSyncManager.reconcilePermissions(
            settings = UserSettings(healthSyncWorkouts = false, healthSyncWeight = false),
            activeProvider = FakeHealthSyncProvider(
                availabilityResult = HealthSyncAvailability.UNAVAILABLE,
                workoutWritePermitted = false,
                weightPermitted = false,
            ),
            disableWorkouts = { workoutsDisabled = true },
            disableWeight = { weightDisabled = true },
        )
        assertFalse(workoutsDisabled)
        assertFalse(weightDisabled)
    }
}
