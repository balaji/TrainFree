import SwiftUI
import SharedUI

/// Shared SwiftUI coordinator for optional automatic Google Drive backup and
/// restore, used by both `ProfileView` and `ProfileSetupView`.
@Observable
final class GoogleDriveBackupController {
    let state = GoogleDriveBackupState()
    var uiState = GoogleDriveBackupUiState(
        working: false,
        pendingEnableConflict: nil,
        pendingRestore: nil,
        driveError: nil,
        backupError: nil,
        notice: nil
    )
    var managerSyncing = false

    var busy: Bool { uiState.working || managerSyncing }

    func startEnable() {
        Task { @MainActor in
            let failure = await IosGoogleDriveBackupProvider.shared.signInInteractively()
            state.onEnableAuthResult(authorized: failure == nil, failure: failure)
        }
    }

    func startReconnect() {
        Task { @MainActor in
            let failure = await IosGoogleDriveBackupProvider.shared.signInInteractively()
            state.onReconnectAuthResult(authorized: failure == nil, failure: failure)
        }
    }

    func startRestore(fromOnboarding: Bool) {
        Task { @MainActor in
            let failure = await IosGoogleDriveBackupProvider.shared.signInInteractively()
            state.onRestoreAuthResult(authorized: failure == nil, failure: failure, fromOnboarding: fromOnboarding)
        }
    }

    func syncNow() {
        state.syncNow()
    }

    func disable() {
        state.disable()
    }

    static func formatDate(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(millis) / 1000.0)
        return DateFormatter.localizedString(from: date, dateStyle: .medium, timeStyle: .none)
    }

    static func formatTimestamp(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(millis) / 1000.0)
        return DateFormatter.localizedString(from: date, dateStyle: .medium, timeStyle: .short)
    }
}

private struct GoogleDriveBackupModifier: ViewModifier {
    @Bindable var controller: GoogleDriveBackupController

    func body(content: Content) -> some View {
        content
            .task {
                for await value in controller.state.uiState {
                    controller.uiState = value
                }
            }
            .task {
                for await value in GoogleDriveBackupManager.shared.isSyncing {
                    controller.managerSyncing = value.boolValue
                }
            }
            .background {
                Color.clear
                    .alert(
                        L10n.googleDriveConflictTitle,
                        isPresented: Binding(
                            get: { controller.uiState.pendingEnableConflict != nil },
                            set: { if !$0 { controller.state.cancelEnableConflict() } }
                        )
                    ) {
                        Button(L10n.restore) {
                            controller.state.confirmEnableRestore()
                        }
                        .disabled(controller.uiState.working)
                        Button(L10n.googleDriveOverwrite, role: .destructive) {
                            controller.state.confirmEnableOverwrite()
                        }
                        .disabled(controller.uiState.working)
                        Button(L10n.cancel, role: .cancel) {
                            controller.state.cancelEnableConflict()
                        }
                        .disabled(controller.uiState.working)
                    } message: {
                        if let summary = controller.uiState.pendingEnableConflict?.summary {
                            Text(
                                L10n.googleDriveConflictMessage(
                                    GoogleDriveBackupController.formatDate(summary.exportedAt),
                                    Int(summary.workoutCount),
                                    Int(summary.programCount),
                                    Int(summary.journalEntryCount)
                                )
                            )
                        }
                    }
            }
            .background {
                Color.clear
                    .alert(
                        L10n.restoreBackupTitle,
                        isPresented: Binding(
                            get: { controller.uiState.pendingRestore != nil },
                            set: { if !$0 { controller.state.cancelRestore() } }
                        )
                    ) {
                        Button(L10n.restore, role: .destructive) {
                            controller.state.confirmRestore()
                        }
                        .disabled(controller.uiState.working)
                        Button(L10n.cancel, role: .cancel) {
                            controller.state.cancelRestore()
                        }
                        .disabled(controller.uiState.working)
                    } message: {
                        if let summary = controller.uiState.pendingRestore?.summary {
                            Text(
                                L10n.restoreBackupMessage(
                                    GoogleDriveBackupController.formatDate(summary.exportedAt),
                                    Int(summary.workoutCount),
                                    Int(summary.programCount),
                                    Int(summary.journalEntryCount)
                                )
                            )
                        }
                    }
            }
            .background {
                Color.clear
                    .alert(
                        L10n.googleDriveErrorTitle,
                        isPresented: Binding(
                            get: { controller.uiState.driveError != nil },
                            set: { if !$0 { controller.state.dismissFeedback() } }
                        )
                    ) {
                        Button(L10n.ok, role: .cancel) {
                            controller.state.dismissFeedback()
                        }
                    } message: {
                        if let error = controller.uiState.driveError {
                            Text(L10n.driveErrorLabel(error))
                        }
                    }
            }
            .background {
                Color.clear
                    .alert(
                        L10n.backupErrorTitle,
                        isPresented: Binding(
                            get: { controller.uiState.backupError != nil },
                            set: { if !$0 { controller.state.dismissFeedback() } }
                        )
                    ) {
                        Button(L10n.ok, role: .cancel) {
                            controller.state.dismissFeedback()
                        }
                    } message: {
                        if let error = controller.uiState.backupError {
                            Text(L10n.backupErrorLabel(error))
                        }
                    }
            }
            .background {
                Color.clear
                    .alert(
                        "",
                        isPresented: Binding(
                            get: { controller.uiState.notice != nil },
                            set: { if !$0 { controller.state.dismissFeedback() } }
                        )
                    ) {
                        Button(L10n.ok, role: .cancel) {
                            controller.state.dismissFeedback()
                        }
                    } message: {
                        if let notice = controller.uiState.notice {
                            Text(L10n.driveNoticeLabel(notice))
                        }
                    }
            }
    }
}

extension View {
    func googleDriveBackupSupport(_ controller: GoogleDriveBackupController) -> some View {
        modifier(GoogleDriveBackupModifier(controller: controller))
    }
}
