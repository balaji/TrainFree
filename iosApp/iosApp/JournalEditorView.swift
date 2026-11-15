import SwiftUI
import UIKit
import SharedUI

struct JournalEditorView: View {
    @Environment(\.dismiss) private var dismiss
    let epochDay: Int64

    @State private var editorState: JournalEditorState
    @State private var uiState: JournalEditorUiState
    @State private var selection = NSRange(location: 0, length: 0)
    @State private var previewMode = false
    @State private var showDiscardConfirm = false
    @State private var showDeleteConfirm = false

    init(epochDay: Int64) {
        self.epochDay = epochDay
        let state = JournalEditorState(epochDay: epochDay)
        _editorState = State(initialValue: state)
        _uiState = State(initialValue: state.uiState.value)
    }

    private var dayTitle: String {
        let date = Date(timeIntervalSince1970: Double(epochDay) * 86400)
        let formatter = DateFormatter()
        formatter.dateFormat = "MMM d, yyyy"
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter.string(from: date)
    }

    private func attemptClose() {
        if uiState.hasUnsavedChanges {
            showDiscardConfirm = true
        } else {
            dismiss()
        }
    }

    private func applyFormat(_ transform: (String, Int32, Int32) -> MarkdownEditResult) {
        let start = Int32(selection.location)
        let end = Int32(selection.location + selection.length)
        let result = transform(uiState.text, start, end)
        selection = NSRange(
            location: Int(result.selectionStart),
            length: max(0, Int(result.selectionEnd - result.selectionStart))
        )
        if result.text != uiState.text {
            editorState.updateText(text: result.text)
        }
    }

    var body: some View {
        NavigationStack {
            Group {
                if uiState.loading {
                    ProgressView()
                } else {
                    VStack(spacing: 0) {
                        HStack {
                            Picker("", selection: $previewMode) {
                                Text(L10n.journalTabEdit).tag(false)
                                Text(L10n.journalTabPreview).tag(true)
                            }
                            .pickerStyle(.segmented)
                            .frame(maxWidth: 220)

                            Spacer()

                            Text(L10n.journalWordCount(Int(uiState.wordCount), Int(uiState.maxWords)))
                                .font(.caption)
                                .monospacedDigit()
                                .foregroundStyle(uiState.isOverWordLimit ? Color.red : Color.secondary)
                        }
                        .padding(.horizontal)
                        .padding(.vertical, 8)

                        if !previewMode {
                            Divider()
                            HStack(spacing: 20) {
                                Button {
                                    applyFormat { text, start, end in
                                        MarkdownFormatHelper.shared.toggleBold(
                                            text: text,
                                            selectionStart: start,
                                            selectionEnd: end
                                        )
                                    }
                                } label: {
                                    Text("B").bold()
                                }
                                .accessibilityLabel(L10n.journalFormatBold)

                                Button {
                                    applyFormat { text, start, end in
                                        MarkdownFormatHelper.shared.toggleItalic(
                                            text: text,
                                            selectionStart: start,
                                            selectionEnd: end
                                        )
                                    }
                                } label: {
                                    Text("I").italic()
                                }
                                .accessibilityLabel(L10n.journalFormatItalic)

                                Button {
                                    applyFormat { text, start, end in
                                        MarkdownFormatHelper.shared.toggleHeading(
                                            text: text,
                                            selectionStart: start,
                                            selectionEnd: end
                                        )
                                    }
                                } label: {
                                    Text("H2").fontWeight(.semibold)
                                }
                                .accessibilityLabel(L10n.journalFormatHeading)

                                Button {
                                    applyFormat { text, start, end in
                                        MarkdownFormatHelper.shared.toggleBulletList(
                                            text: text,
                                            selectionStart: start,
                                            selectionEnd: end
                                        )
                                    }
                                } label: {
                                    Image(systemName: "list.bullet")
                                }
                                .accessibilityLabel(L10n.journalFormatList)

                                Spacer()
                            }
                            .padding(.horizontal)
                            .padding(.vertical, 8)
                            .background(Color(.secondarySystemBackground))

                            Divider()

                            ZStack(alignment: .topLeading) {
                                MarkdownUITextView(
                                    text: Binding(
                                        get: { uiState.text },
                                        set: { editorState.updateText(text: $0) }
                                    ),
                                    selection: $selection
                                )
                                if uiState.text.isEmpty {
                                    Text(L10n.journalEditorPlaceholder)
                                        .foregroundStyle(.tertiary)
                                        .padding(.horizontal, 16)
                                        .padding(.vertical, 14)
                                        .allowsHitTesting(false)
                                }
                            }
                        } else {
                            Divider()
                            if uiState.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                ContentUnavailableView(L10n.journalPreviewEmpty, systemImage: "doc.text")
                            } else {
                                ScrollView {
                                    MarkdownTextView(markdown: uiState.text)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .padding()
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle(dayTitle)
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(uiState.hasUnsavedChanges)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.cancel) {
                        attemptClose()
                    }
                }
                if uiState.hasExistingEntry {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(role: .destructive) {
                            showDeleteConfirm = true
                        } label: {
                            Image(systemName: "trash")
                        }
                        .tint(.red)
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button(L10n.save) {
                        editorState.save { }
                    }
                    .disabled(!uiState.canSave)
                }
            }
            .alert(L10n.discardChangesTitle, isPresented: $showDiscardConfirm) {
                Button(L10n.discard, role: .destructive) {
                    dismiss()
                }
                Button(L10n.keepEditing, role: .cancel) {}
            } message: {
                Text(L10n.discardChangesMessage)
            }
            .alert(L10n.deleteJournalTitle, isPresented: $showDeleteConfirm) {
                Button(L10n.delete, role: .destructive) {
                    editorState.delete { }
                }
                Button(L10n.cancel, role: .cancel) {}
            } message: {
                Text(L10n.deleteJournalMessage)
            }
            .task {
                for await value in editorState.uiState {
                    uiState = value
                    if value.saved || value.deleted {
                        dismiss()
                        return
                    }
                }
            }
        }
    }
}

private struct MarkdownUITextView: UIViewRepresentable {
    @Binding var text: String
    @Binding var selection: NSRange

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIView(context: Context) -> UITextView {
        let tv = UITextView()
        tv.delegate = context.coordinator
        tv.font = UIFont.preferredFont(forTextStyle: .body)
        tv.adjustsFontForContentSizeCategory = true
        tv.backgroundColor = .clear
        tv.textContainerInset = UIEdgeInsets(top: 12, left: 12, bottom: 12, right: 12)
        tv.alwaysBounceVertical = true
        tv.text = text
        return tv
    }

    func updateUIView(_ uiView: UITextView, context: Context) {
        if uiView.text != text {
            uiView.text = text
        }
        let clampedLocation = min(selection.location, (uiView.text as NSString).length)
        let maxLen = (uiView.text as NSString).length - clampedLocation
        let clamped = NSRange(location: clampedLocation, length: min(selection.length, maxLen))
        if uiView.selectedRange != clamped {
            context.coordinator.isUpdatingSelection = true
            uiView.selectedRange = clamped
            context.coordinator.isUpdatingSelection = false
        }
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: MarkdownUITextView
        var isUpdatingSelection = false

        init(_ parent: MarkdownUITextView) {
            self.parent = parent
        }

        func textViewDidChange(_ textView: UITextView) {
            parent.text = textView.text ?? ""
            parent.selection = textView.selectedRange
        }

        func textViewDidChangeSelection(_ textView: UITextView) {
            guard !isUpdatingSelection else { return }
            let current = textView.selectedRange
            if parent.selection != current {
                DispatchQueue.main.async {
                    self.parent.selection = current
                }
            }
        }
    }
}
