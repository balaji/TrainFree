import SwiftUI
import SharedUI

struct AiContentReportTarget: Identifiable {
    let id: String
}

/// In-app moderation sheet allowing users to flag offensive, unsafe, or
/// inaccurate on-device AI responses and save the report locally.
struct AiContentReportSheet: View {
    let onSubmit: (String, String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selectedReason: String? = nil
    @State private var note = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(AiContentReportReason.shared.ALL, id: \.self) { reasonKey in
                        Button {
                            selectedReason = reasonKey
                        } label: {
                            HStack {
                                Text(L10n.aiReportReasonLabel(reasonKey))
                                    .foregroundStyle(.primary)
                                Spacer()
                                if selectedReason == reasonKey {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(.tint)
                                }
                            }
                        }
                    }
                } footer: {
                    Text(L10n.aiReportDialogSubtitle)
                }

                Section {
                    TextField(L10n.aiReportNotePlaceholder, text: $note, axis: .vertical)
                        .lineLimit(2...4)
                        .onChange(of: note) { _, newValue in
                            let maxLen = Int(AiContentReport.companion.MAX_NOTE_CHARS)
                            if newValue.count > maxLen {
                                note = String(newValue.prefix(maxLen))
                            }
                        }
                }
            }
            .navigationTitle(L10n.aiReportDialogTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.cancel) {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.aiReportSubmit) {
                        guard let reason = selectedReason else { return }
                        onSubmit(reason, note)
                        dismiss()
                    }
                    .disabled(selectedReason == nil)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
