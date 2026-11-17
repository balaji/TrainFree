package dev.balaji.moonrose.trainfree.health

/**
 * A body-weight measurement read from Apple HealthKit or Google Health Connect.
 * [externalId] is the platform record's stable unique identifier used to
 * deduplicate repeated imports.
 */
data class ExternalWeightSample(
    val externalId: String,
    val weightKg: Double,
    val loggedAtMillis: Long,
)

enum class HealthSyncAvailability {
    AVAILABLE,
    NOT_INSTALLED,
    UNAVAILABLE,
}

/**
 * Platform bridge for syncing live completed strength workouts and body-weight
 * entries with Apple HealthKit (iOS) or Google Health Connect (Android).
 *
 * Implementations must be safe to call off the main thread and never throw on
 * transient OS/permission failures (return `false` / empty list instead).
 */
interface HealthSyncProvider {
    suspend fun availability(): HealthSyncAvailability

    suspend fun hasWorkoutWritePermission(): Boolean

    suspend fun hasWeightPermissions(): Boolean

    suspend fun writeStrengthWorkout(
        sessionId: Long,
        title: String,
        startMillis: Long,
        endMillis: Long,
    ): Boolean

    suspend fun writeBodyWeight(
        metricId: Long,
        weightKg: Double,
        loggedAtMillis: Long,
    ): Boolean

    suspend fun readBodyWeights(sinceMillis: Long): List<ExternalWeightSample>
}
