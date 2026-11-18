package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.health.ExternalWeightSample
import dev.balaji.moonrose.trainfree.health.HealthSyncAvailability
import dev.balaji.moonrose.trainfree.health.HealthSyncProvider
import dev.balaji.moonrose.trainfree.model.WeightUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

private const val DEFAULT_WEIGHT_LOOKBACK_DAYS = 90L
private const val MILLIS_PER_DAY = 86_400_000L
private const val DUPLICATE_TIMESTAMP_WINDOW_MILLIS = 60_000L
private const val DEFAULT_WORKOUT_TITLE = "Strength Training"

/**
 * Coordinates best-effort health sync between TrainFree's local Room DB and
 * the platform's [HealthSyncProvider] (Apple HealthKit on iOS, Google Health
 * Connect on Android).
 *
 * Sync is strictly opt-in via [UserSettingsRepository] and never blocks or
 * fails local DB operations.
 */
object HealthSyncManager {
    @Volatile
    private var provider: HealthSyncProvider? = null
    private val workoutSyncMutex = Mutex()
    private val weightImportMutex = Mutex()

    fun register(provider: HealthSyncProvider) {
        this.provider = provider
    }

    suspend fun availability(): HealthSyncAvailability =
        provider?.availability() ?: HealthSyncAvailability.UNAVAILABLE

    suspend fun hasWorkoutWritePermission(): Boolean =
        provider?.hasWorkoutWritePermission() ?: false

    suspend fun hasWeightPermissions(): Boolean =
        provider?.hasWeightPermissions() ?: false

    /**
     * Writes a live completed workout session to Apple Health / Health Connect.
     * Deliberately only called from live session finish — journal-logged
     * workouts never call this.
     */
    suspend fun onWorkoutFinished(sessionId: Long) {
        val activeProvider = provider ?: return
        try {
            if (!UserSettingsRepository.settings.first().healthSyncWorkouts) return
            val session = AppDb.db.workoutSessionDao().findById(sessionId) ?: return
            writeSessionIfEligible(session, activeProvider)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Health sync is best-effort; never fail workout completion.
        }
    }

    /**
     * Backfills all eligible completed live workouts from `workout_sessions` to
     * Apple Health / Health Connect. Platform providers use deterministic sync
     * identifiers (`trainfree_session_<id>`), so already-synced workouts are
     * upserted/deduplicated without creating duplicates.
     */
    suspend fun syncWorkoutsToHealth(): Int {
        val activeProvider = provider ?: return 0
        return workoutSyncMutex.withLock {
            try {
                if (!UserSettingsRepository.settings.first().healthSyncWorkouts) return@withLock 0
                val sessions = AppDb.db.workoutSessionDao().getAll()
                syncWorkoutEntities(sessions, activeProvider)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                0
            }
        }
    }

    /**
     * Deletes a previously synced workout session from Apple Health / Health Connect.
     */
    suspend fun onWorkoutDeleted(sessionId: Long) {
        val activeProvider = provider ?: return
        try {
            if (!UserSettingsRepository.settings.first().healthSyncWorkouts) return
            activeProvider.deleteStrengthWorkout(sessionId = sessionId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Health sync is best-effort; never fail local deletion.
        }
    }

    internal suspend fun writeSessionIfEligible(
        session: WorkoutSessionEntity,
        activeProvider: HealthSyncProvider,
    ): Boolean {
        if (session.testData) return false
        val completedAt = session.completedAt ?: return false
        if (completedAt <= session.performedAt) return false
        val title = session.title.ifBlank { DEFAULT_WORKOUT_TITLE }
        return activeProvider.writeStrengthWorkout(
            sessionId = session.id,
            title = title,
            startMillis = session.performedAt,
            endMillis = completedAt,
        )
    }

    internal suspend fun syncWorkoutEntities(
        sessions: List<WorkoutSessionEntity>,
        activeProvider: HealthSyncProvider,
    ): Int {
        var synced = 0
        for (session in sessions) {
            if (writeSessionIfEligible(session, activeProvider)) {
                synced++
            }
        }
        return synced
    }

    /**
     * Writes a newly logged body-weight entry to Apple Health / Health Connect.
     */
    suspend fun onBodyWeightLogged(
        metricId: Long,
        weight: Double,
        weightUnit: WeightUnit,
        loggedAt: Long,
    ) {
        val activeProvider = provider ?: return
        try {
            if (!UserSettingsRepository.settings.first().healthSyncWeight) return
            activeProvider.writeBodyWeight(
                metricId = metricId,
                weightKg = weightUnit.toKilograms(weight),
                loggedAtMillis = loggedAt,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Best-effort; local DB entry is already saved.
        }
    }

    /**
     * Imports new body-weight samples from Apple Health / Health Connect into
     * `body_metrics`, deduplicating by `external_id` and near-identical
     * timestamps. Returns the number of newly inserted entries.
     */
    suspend fun syncBodyWeightsFromHealth(lookbackDays: Long = DEFAULT_WEIGHT_LOOKBACK_DAYS): Int {
        val activeProvider = provider ?: return 0
        return weightImportMutex.withLock {
            try {
                val settings = UserSettingsRepository.settings.first()
                if (!settings.healthSyncWeight) return@withLock 0
                val now = nowMillis()
                val sinceMillis = UserSettingsRepository.lastHealthWeightSyncAt()
                    ?: (now - lookbackDays * MILLIS_PER_DAY)
                val samples = activeProvider.readBodyWeights(sinceMillis)
                val inserted = importExternalWeightSamples(samples, settings.weightUnit, now)
                UserSettingsRepository.setLastHealthWeightSyncAt(now)
                inserted
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                0
            }
        }
    }

    internal suspend fun importExternalWeightSamples(
        samples: List<ExternalWeightSample>,
        targetUnit: WeightUnit,
        now: Long = nowMillis(),
    ): Int {
        if (samples.isEmpty()) return 0
        val dao = AppDb.db.bodyMetricDao()
        var inserted = 0
        for (sample in samples) {
            if (sample.weightKg <= 0.0 || sample.externalId.isBlank()) continue
            if (dao.findByExternalId(sample.externalId) != null) continue
            if (dao.hasNearTimestamp(PROFILE_ID, sample.loggedAtMillis, DUPLICATE_TIMESTAMP_WINDOW_MILLIS)) continue
            val convertedWeight = WeightUnit.METRIC.convertWeight(sample.weightKg, targetUnit)
            dao.insert(
                BodyMetricEntity(
                    profileId = PROFILE_ID,
                    weight = convertedWeight,
                    weightUnit = targetUnit,
                    loggedAt = sample.loggedAtMillis,
                    externalId = sample.externalId,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            inserted++
        }
        if (inserted > 0) {
            dao.latest(PROFILE_ID)?.let {
                AppDb.db.profileDao().updateWeight(it.weightUnit.toKilograms(it.weight), now)
            }
        }
        return inserted
    }
}
