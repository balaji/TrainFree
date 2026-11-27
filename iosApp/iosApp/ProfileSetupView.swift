import SwiftUI
import SharedUI

/// Native SwiftUI replacement for the Compose `ProfileSetupFlow`: a welcome
/// step with a "Create a Profile" link that leads to the form. Once the
/// profile is saved, `ContentView`'s `ProfileState` observer switches to the
/// main tabs, so this view doesn't navigate anywhere itself.
struct ProfileSetupView: View {
    @State private var showForm = false
    @State private var unit: WeightUnit = .metric
    @State private var backupController = BackupTransferController()
    @State private var driveController = GoogleDriveBackupController()

    var body: some View {
        Group {
            if showForm {
                VStack {
                    Text(L10n.createAProfile)
                        .font(.title2)
                        .padding(.top, 32)
                    ProfileFormView(unit: unit, saveLabel: L10n.createProfile)
                }
            } else {
                VStack(spacing: 8) {
                    Text(L10n.appName)
                        .font(.largeTitle)
                    Text(L10n.appTagline)
                        .font(.body)
                        .foregroundStyle(.secondary)
                    Button(L10n.createAProfile) {
                        showForm = true
                    }
                    .padding(.top, 8)
                    Button(L10n.restoreFromBackup) {
                        backupController.startImport()
                    }
                    .disabled(backupController.uiState.working || driveController.busy)
                    Button(L10n.googleDriveRestore) {
                        driveController.startRestore(fromOnboarding: true)
                    }
                    .disabled(backupController.uiState.working || driveController.busy)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .backupTransferSupport(backupController)
        .googleDriveBackupSupport(driveController)
        .task {
            for await settings in UserSettingsRepository.shared.settings {
                unit = settings.weightUnit
            }
        }
    }
}
