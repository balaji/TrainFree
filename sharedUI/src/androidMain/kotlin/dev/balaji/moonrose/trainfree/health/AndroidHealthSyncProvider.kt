package dev.balaji.moonrose.trainfree.health

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class AndroidHealthSyncProvider(
    private val context: Context,
) : HealthSyncProvider {
    private val client: HealthConnectClient?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        ) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }

    override suspend fun availability(): HealthSyncAvailability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return HealthSyncAvailability.UNAVAILABLE
        }
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthSyncAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthSyncAvailability.NOT_INSTALLED
            else -> HealthSyncAvailability.UNAVAILABLE
        }
    }

    override suspend fun hasWorkoutWritePermission(): Boolean {
        val hc = client ?: return false
        return try {
            val granted = hc.permissionController.getGrantedPermissions()
            WORKOUT_WRITE_PERMISSION in granted
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun hasWeightPermissions(): Boolean {
        val hc = client ?: return false
        return try {
            val granted = hc.permissionController.getGrantedPermissions()
            WEIGHT_PERMISSIONS.all { it in granted }
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun writeStrengthWorkout(
        sessionId: Long,
        title: String,
        startMillis: Long,
        endMillis: Long,
    ): Boolean {
        val hc = client ?: return false
        if (!hasWorkoutWritePermission()) return false
        if (endMillis <= startMillis) return false
        return try {
            val start = Instant.ofEpochMilli(startMillis)
            val end = Instant.ofEpochMilli(endMillis)
            val record = ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = zoneOffsetAt(start),
                endTime = end,
                endZoneOffset = zoneOffsetAt(end),
                metadata = Metadata.manualEntry(clientRecordId = workoutClientRecordId(sessionId)),
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
                title = title,
            )
            hc.insertRecords(listOf(record))
            true
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun deleteStrengthWorkout(sessionId: Long): Boolean {
        val hc = client ?: return false
        if (!hasWorkoutWritePermission()) return false
        return try {
            hc.deleteRecords(
                recordType = ExerciseSessionRecord::class,
                recordIdsList = emptyList(),
                clientRecordIdsList = listOf(workoutClientRecordId(sessionId)),
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun workoutClientRecordId(sessionId: Long): String = "trainfree_session_$sessionId"

    override suspend fun writeBodyWeight(
        metricId: Long,
        weightKg: Double,
        loggedAtMillis: Long,
    ): Boolean {
        val hc = client ?: return false
        if (WEIGHT_WRITE_PERMISSION !in hc.permissionController.getGrantedPermissions()) return false
        if (weightKg <= 0.0) return false
        return try {
            val instant = Instant.ofEpochMilli(loggedAtMillis)
            val record = WeightRecord(
                time = instant,
                zoneOffset = zoneOffsetAt(instant),
                weight = Mass.kilograms(weightKg),
                metadata = Metadata.manualEntry(clientRecordId = "trainfree_weight_$metricId"),
            )
            hc.insertRecords(listOf(record))
            true
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun readBodyWeights(sinceMillis: Long): List<ExternalWeightSample> {
        val hc = client ?: return emptyList()
        if (WEIGHT_READ_PERMISSION !in hc.permissionController.getGrantedPermissions()) return emptyList()
        return try {
            val ownPackage = context.packageName
            val results = mutableListOf<ExternalWeightSample>()
            var pageToken: String? = null
            do {
                val response = hc.readRecords(
                    ReadRecordsRequest(
                        recordType = WeightRecord::class,
                        timeRangeFilter = TimeRangeFilter.after(Instant.ofEpochMilli(sinceMillis)),
                        pageToken = pageToken,
                    ),
                )
                for (record in response.records) {
                    if (record.metadata.dataOrigin.packageName == ownPackage) continue
                    results += ExternalWeightSample(
                        externalId = record.metadata.id,
                        weightKg = record.weight.inKilograms,
                        loggedAtMillis = record.time.toEpochMilli(),
                    )
                }
                pageToken = response.pageToken
            } while (pageToken != null)
            results
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun zoneOffsetAt(instant: Instant): ZoneOffset =
        ZoneId.systemDefault().rules.getOffset(instant)

    companion object {
        val WORKOUT_WRITE_PERMISSION: String =
            HealthPermission.getWritePermission(ExerciseSessionRecord::class)
        val WEIGHT_READ_PERMISSION: String =
            HealthPermission.getReadPermission(WeightRecord::class)
        val WEIGHT_WRITE_PERMISSION: String =
            HealthPermission.getWritePermission(WeightRecord::class)
        val WEIGHT_PERMISSIONS: Set<String> = setOf(WEIGHT_READ_PERMISSION, WEIGHT_WRITE_PERMISSION)
    }
}
