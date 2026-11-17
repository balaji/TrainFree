import Foundation
import HealthKit
import SharedUI

/// `HealthSyncProvider` backed by Apple HealthKit (`HKHealthStore`).
///
/// Writes live completed strength workouts (`HKWorkout` with
/// `.traditionalStrengthTraining`) and performs two-way body-weight sync
/// (`HKQuantityType(.bodyMass)`).
final class IosHealthSyncProvider: HealthSyncProvider {
    static let shared = IosHealthSyncProvider()

    private let store = HKHealthStore()
    private let workoutType = HKObjectType.workoutType()
    private let bodyMassType = HKQuantityType(.bodyMass)

    private init() {}

    func availability() async throws -> HealthSyncAvailability {
        HKHealthStore.isHealthDataAvailable() ? .available : .unavailable
    }

    func hasWorkoutWritePermission() async throws -> KotlinBoolean {
        guard HKHealthStore.isHealthDataAvailable() else { return KotlinBoolean(bool: false) }
        return KotlinBoolean(bool: store.authorizationStatus(for: workoutType) == .sharingAuthorized)
    }

    func hasWeightPermissions() async throws -> KotlinBoolean {
        guard HKHealthStore.isHealthDataAvailable() else { return KotlinBoolean(bool: false) }
        // Note: Apple hides read-permission status for privacy; we can only
        // verify write authorization directly.
        return KotlinBoolean(bool: store.authorizationStatus(for: bodyMassType) == .sharingAuthorized)
    }

    /// Requests HealthKit authorization for workout writes and/or body-mass
    /// read+write. Returns whether the requested share authorization succeeded.
    func requestWorkoutAuthorization() async -> Bool {
        guard HKHealthStore.isHealthDataAvailable() else { return false }
        do {
            try await store.requestAuthorization(toShare: [workoutType], read: [])
            return store.authorizationStatus(for: workoutType) == .sharingAuthorized
        } catch {
            return false
        }
    }

    func requestWeightAuthorization() async -> Bool {
        guard HKHealthStore.isHealthDataAvailable() else { return false }
        do {
            try await store.requestAuthorization(toShare: [bodyMassType], read: [bodyMassType])
            return store.authorizationStatus(for: bodyMassType) == .sharingAuthorized
        } catch {
            return false
        }
    }

    func writeStrengthWorkout(
        sessionId: Int64,
        title: String,
        startMillis: Int64,
        endMillis: Int64
    ) async throws -> KotlinBoolean {
        guard HKHealthStore.isHealthDataAvailable() else { return KotlinBoolean(bool: false) }
        guard store.authorizationStatus(for: workoutType) == .sharingAuthorized else {
            return KotlinBoolean(bool: false)
        }
        guard endMillis > startMillis else { return KotlinBoolean(bool: false) }

        let startDate = Date(timeIntervalSince1970: Double(startMillis) / 1000.0)
        let endDate = Date(timeIntervalSince1970: Double(endMillis) / 1000.0)
        let config = HKWorkoutConfiguration()
        config.activityType = .traditionalStrengthTraining

        let builder = HKWorkoutBuilder(healthStore: store, configuration: config, device: .local())
        do {
            try await builder.beginCollection(at: startDate)
            let metadata: [String: Any] = [
                HKMetadataKeySyncIdentifier: "trainfree_session_\(sessionId)",
                HKMetadataKeySyncVersion: 1,
                HKMetadataKeyWorkoutBrandName: title,
            ]
            try await builder.addMetadata(metadata)
            try await builder.endCollection(at: endDate)
            _ = try await builder.finishWorkout()
            return KotlinBoolean(bool: true)
        } catch {
            return KotlinBoolean(bool: false)
        }
    }

    func writeBodyWeight(
        metricId: Int64,
        weightKg: Double,
        loggedAtMillis: Int64
    ) async throws -> KotlinBoolean {
        guard HKHealthStore.isHealthDataAvailable() else { return KotlinBoolean(bool: false) }
        guard store.authorizationStatus(for: bodyMassType) == .sharingAuthorized else {
            return KotlinBoolean(bool: false)
        }
        guard weightKg > 0 else { return KotlinBoolean(bool: false) }

        let date = Date(timeIntervalSince1970: Double(loggedAtMillis) / 1000.0)
        let quantity = HKQuantity(unit: .gramUnit(with: .kilo), doubleValue: weightKg)
        let metadata: [String: Any] = [
            HKMetadataKeySyncIdentifier: "trainfree_weight_\(metricId)",
            HKMetadataKeySyncVersion: 1,
        ]
        let sample = HKQuantitySample(
            type: bodyMassType,
            quantity: quantity,
            start: date,
            end: date,
            metadata: metadata
        )
        do {
            try await store.save(sample)
            return KotlinBoolean(bool: true)
        } catch {
            return KotlinBoolean(bool: false)
        }
    }

    func readBodyWeights(sinceMillis: Int64) async throws -> [ExternalWeightSample] {
        guard HKHealthStore.isHealthDataAvailable() else { return [] }
        let sinceDate = Date(timeIntervalSince1970: Double(sinceMillis) / 1000.0)
        let predicate = HKQuery.predicateForSamples(withStart: sinceDate, end: nil, options: .strictStartDate)
        let sort = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)
        let ownBundleId = Bundle.main.bundleIdentifier

        return await withCheckedContinuation { continuation in
            let query = HKSampleQuery(
                sampleType: bodyMassType,
                predicate: predicate,
                limit: HKObjectQueryNoLimit,
                sortDescriptors: [sort]
            ) { _, samples, _ in
                let quantitySamples = (samples as? [HKQuantitySample]) ?? []
                let mapped: [ExternalWeightSample] = quantitySamples.compactMap { sample in
                    if let ownBundleId, sample.sourceRevision.source.bundleIdentifier == ownBundleId {
                        return nil
                    }
                    let kg = sample.quantity.doubleValue(for: .gramUnit(with: .kilo))
                    let millis = Int64((sample.startDate.timeIntervalSince1970 * 1000.0).rounded())
                    return ExternalWeightSample(
                        externalId: sample.uuid.uuidString,
                        weightKg: kg,
                        loggedAtMillis: millis
                    )
                }
                continuation.resume(returning: mapped)
            }
            store.execute(query)
        }
    }
}
