import SwiftUI
import SharedUI
import UserNotifications

@main
struct iOSApp: App {
    private static let notificationPermissionPromptedKey = "notification_permission_prompted"

    @Environment(\.scenePhase) private var scenePhase
    @State private var appearance: Appearance = .system
    @State private var systemColorScheme: ColorScheme = .light

    var body: some Scene {
        WindowGroup {
            ContentView()
                .observeSystemColorScheme($systemColorScheme)
                .preferredColorScheme(preferredScheme)
                .task {
                    HealthSyncManager.shared.register(provider: IosHealthSyncProvider.shared)
                    GoogleDriveBackupManager.shared.register(provider: IosGoogleDriveBackupProvider.shared)
                    seedTestDataIfRequested()
                    Task {
                        await requestInitialNotificationPermissionIfNeeded()
                    }
                    Task {
                        try? await HealthSyncManager.shared.reconcilePermissions()
                        _ = try? await HealthSyncManager.shared.syncBodyWeightsFromHealth(lookbackDays: 90)
                    }
                    Task {
                        _ = try? await GoogleDriveBackupManager.shared.retryPendingSyncIfNeeded()
                    }
                    for await settings in UserSettingsRepository.shared.settings {
                        appearance = settings.appearance
                    }
                }
                .onChange(of: scenePhase) { _, newPhase in
                    if newPhase == .active {
                        Task {
                            try? await HealthSyncManager.shared.reconcilePermissions()
                            _ = try? await GoogleDriveBackupManager.shared.retryPendingSyncIfNeeded()
                        }
                    }
                }
        }
    }

    private func requestInitialNotificationPermissionIfNeeded() async {
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: Self.notificationPermissionPromptedKey) else { return }
        defaults.set(true, forKey: Self.notificationPermissionPromptedKey)

        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .notDetermined else { return }

        let granted = (try? await center.requestAuthorization(options: [.alert, .badge, .sound])) ?? false
        if granted {
            try? await UserSettingsRepository.shared.setNotificationsEnabled(enabled: true)
        }
    }

    // Resolves "system" to a concrete scheme (rather than nil) so it behaves
    // consistently with ProfileSheet's own resolution; see SystemColorScheme.swift.
    private var preferredScheme: ColorScheme? {
        switch appearance {
        case .light: .light
        case .dark: .dark
        case .system: systemColorScheme
        }
    }

    // Test harness hook: launching with `-seedTestData <days>` (surfaced via
    // the UserDefaults argument domain) on a fresh debug install lands the app
    // on a populated profile (see scripts/run-ios.py --seed).
    private func seedTestDataIfRequested() {
        #if DEBUG
        let days = UserDefaults.standard.integer(forKey: "seedTestData")
        if days > 0 {
            DevSeed.shared.seedIfNoProfile(days: Int32(days))
        }
        #endif
    }
}
