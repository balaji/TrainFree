import SwiftUI
import SharedUI
import UniformTypeIdentifiers

struct BackupFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }

    var text: String

    init(text: String = "") {
        self.text = text
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents,
              let text = String(data: data, encoding: .utf8) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        self.text = text
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

/// Shared SwiftUI coordinator for manual JSON backup export and import, used by
/// both `ProfileView` and `ProfileSetupView`.
@Observable
final class BackupTransferController {
    let state = BackupTransferState()
    var uiState = BackupUiState(working: false, pendingImport: nil, error: nil, notice: nil)
    var showExporter = false
    var showImporter = false
    var exportDocument = BackupFileDocument()
    var exportFilename = "trainfree-backup.json"

    func startExport() {
        state.prepareExport { [weak self] filename, json in
            guard let self else { return }
            self.exportFilename = filename
            self.exportDocument = BackupFileDocument(text: json)
            self.showExporter = true
        }
    }

    func startImport() {
        showImporter = true
    }

    func handleExportResult(_ result: Result<URL, Error>) {
        switch result {
        case .success:
            state.onExportCompleted(success: true)
        case .failure(let error):
            if (error as NSError).code != NSUserCancelledError {
                state.onExportCompleted(success: false)
            }
        }
    }

    func handleImportResult(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let url = urls.first else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer {
                if scoped {
                    url.stopAccessingSecurityScopedResource()
                }
            }
            let maxBytes = Int(BackupRepositoryKt.MAX_BACKUP_FILE_BYTES)
            if let fileSize = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
               fileSize > maxBytes {
                state.onFileReadError()
                return
            }
            guard let data = try? Data(contentsOf: url),
                  data.count <= maxBytes,
                  let text = String(data: data, encoding: .utf8) else {
                state.onFileReadError()
                return
            }
            state.inspectImport(json: text)
        case .failure:
            state.onFileReadError()
        }
    }
}

private struct BackupTransferModifier: ViewModifier {
    @Bindable var controller: BackupTransferController

    func body(content: Content) -> some View {
        content
            .task {
                for await value in controller.state.uiState {
                    controller.uiState = value
                }
            }
            .fileExporter(
                isPresented: $controller.showExporter,
                document: controller.exportDocument,
                contentType: .json,
                defaultFilename: controller.exportFilename
            ) { result in
                controller.handleExportResult(result)
            }
            .fileImporter(
                isPresented: $controller.showImporter,
                allowedContentTypes: [.json],
                allowsMultipleSelection: false
            ) { result in
                controller.handleImportResult(result)
            }
            .background {
                Color.clear
                    .alert(
                        L10n.restoreBackupTitle,
                        isPresented: Binding(
                            get: { controller.uiState.pendingImport != nil },
                            set: { if !$0 { controller.state.cancelImport() } }
                        )
                    ) {
                        Button(L10n.restore, role: .destructive) {
                            controller.state.confirmImport()
                        }
                        .disabled(controller.uiState.working)
                        Button(L10n.cancel, role: .cancel) {
                            controller.state.cancelImport()
                        }
                        .disabled(controller.uiState.working)
                    } message: {
                        if let summary = controller.uiState.pendingImport?.summary {
                            Text(
                                L10n.restoreBackupMessage(
                                    Self.formatDate(summary.exportedAt),
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
                        L10n.backupErrorTitle,
                        isPresented: Binding(
                            get: { controller.uiState.error != nil },
                            set: { if !$0 { controller.state.dismissFeedback() } }
                        )
                    ) {
                        Button(L10n.ok, role: .cancel) {
                            controller.state.dismissFeedback()
                        }
                    } message: {
                        if let error = controller.uiState.error {
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
                            Text(L10n.backupNoticeLabel(notice))
                        }
                    }
            }
    }

    private static func formatDate(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(millis) / 1000.0)
        return DateFormatter.localizedString(from: date, dateStyle: .medium, timeStyle: .none)
    }
}

extension View {
    func backupTransferSupport(_ controller: BackupTransferController) -> some View {
        modifier(BackupTransferModifier(controller: controller))
    }
}
