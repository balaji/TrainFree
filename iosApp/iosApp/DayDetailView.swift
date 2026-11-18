import SwiftUI
import SharedUI

private struct IdentifiableSessionId: Identifiable {
    let id: Int64
}

/// Summary of the completed workout session(s) and optional journal entry for a
/// calendar day, bound to the shared `DayDetailState` via SKIE's Swift `Flow`
/// bridge.
struct DayDetailView: View {
    let epochDay: Int64
    @State private var state: DayDetailState
    @State private var uiState = DayDetailUiState(
        loading: true,
        sessions: [],
        journalContent: nil,
        isFuture: false,
        weightUnit: .metric
    )
    @State private var showLogWorkout = false
    @State private var editingSession: IdentifiableSessionId?
    @State private var sessionPendingDelete: DaySession?
    @State private var showDeleteConfirm = false
    @State private var sessionPendingRename: DaySession?
    @State private var renameText = ""
    @State private var showRenameAlert = false

    init(epochDay: Int64) {
        self.epochDay = epochDay
        _state = State(initialValue: DayDetailState(epochDay: epochDay))
    }

    var body: some View {
        Group {
            if uiState.loading {
                ProgressView()
            } else {
                List {
                    if !uiState.isFuture {
                        Section {
                            Button {
                                showLogWorkout = true
                            } label: {
                                Label(L10n.logCompletedWorkout, systemImage: "plus")
                            }
                        }
                    }

                    if uiState.sessions.isEmpty {
                        Section {
                            Text(L10n.noWorkoutsThisDay)
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        ForEach(uiState.sessions, id: \.id) { session in
                            Section {
                                ForEach(Array(session.exercises.enumerated()), id: \.offset) { _, exercise in
                                    ExerciseSummaryRow(exercise: exercise, weightUnit: uiState.weightUnit)
                                }
                            } header: {
                                HStack {
                                    Button {
                                        sessionPendingRename = session
                                        renameText = session.title
                                        showRenameAlert = true
                                    } label: {
                                        Text(session.title)
                                            .font(.headline)
                                            .foregroundStyle(Color.accentColor)
                                            .textCase(nil)
                                    }
                                    .buttonStyle(.plain)
                                    Spacer()
                                    Button {
                                        editingSession = IdentifiableSessionId(id: session.id)
                                    } label: {
                                        Image(systemName: "pencil")
                                    }
                                    .accessibilityLabel(L10n.editWorkoutTitle)

                                    Button(role: .destructive) {
                                        sessionPendingDelete = session
                                        showDeleteConfirm = true
                                    } label: {
                                        Image(systemName: "trash")
                                    }
                                    .tint(.red)
                                    .accessibilityLabel(L10n.deleteWorkout)
                                }
                            }
                        }
                    }
                }
                .floatingWorkoutMenuClearance()
            }
        }
        .navigationTitle(dayTitle)
        .toolbarTitleDisplayMode(.inline)
        .sheet(isPresented: $showLogWorkout) {
            NavigationStack {
                WorkoutSessionView(
                    mode: .logCompleted(epochDay: epochDay, title: L10n.logWorkoutTitle)
                )
            }
        }
        .sheet(item: $editingSession) { target in
            NavigationStack {
                WorkoutSessionView(
                    mode: .editCompleted(sessionId: target.id, title: L10n.editWorkoutTitle)
                )
            }
        }
        .alert(L10n.renameWorkout, isPresented: $showRenameAlert, presenting: sessionPendingRename) { target in
            TextField(L10n.workoutTitle, text: Binding(
                get: { renameText },
                set: { renameText = FormValidationKt.filterWorkoutTitle(value: $0) }
            ))
            Button(L10n.save) {
                let nextTitle = renameText
                Task {
                    try? await state.renameSession(sessionId: target.id, title: nextTitle)
                }
            }
            Button(L10n.cancel, role: .cancel) {}
        }
        .alert(L10n.deleteWorkoutTitle, isPresented: $showDeleteConfirm, presenting: sessionPendingDelete) { target in
            Button(L10n.delete, role: .destructive) {
                Task {
                    try? await state.deleteSession(sessionId: target.id)
                }
            }
            Button(L10n.cancel, role: .cancel) {}
        } message: { _ in
            Text(L10n.deleteWorkoutMessage)
        }
        .task {
            for await value in state.uiState {
                uiState = value
            }
        }
    }

    private var dayTitle: String {
        let date = Date(timeIntervalSince1970: Double(epochDay) * 86400)
        let formatter = DateFormatter()
        formatter.dateFormat = "MMM d, yyyy"
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter.string(from: date)
    }
}

struct ExerciseSummaryRow: View {
    let exercise: DaySessionExercise
    let weightUnit: WeightUnit

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(exercise.name)
                .font(.body)
            ForEach(Array(exercise.sets.enumerated()), id: \.offset) { index, set in
                HStack {
                    Text(label(at: index, set))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(summary(for: set))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 2)
    }

    /// Only normal sets take a number; warm-up and drop sets are named instead,
    /// matching the Compose day detail.
    private func label(at index: Int, _ set: DaySessionSet) -> String {
        guard set.setType == .normal else { return L10n.setTypeLabel(set.setType) }
        let workingNumber = exercise.sets.prefix(index + 1).filter { $0.setType == .normal }.count
        return L10n.setNumber(workingNumber)
    }

    private func summary(for set: DaySessionSet) -> String {
        let weight = set.actualWeight.isEmpty ? set.targetWeight : set.actualWeight
        let reps = set.actualReps.isEmpty ? set.targetReps : set.actualReps
        let duration = set.actualDuration.isEmpty ? set.targetDuration : set.actualDuration
        let distance = set.actualDistance.isEmpty ? set.targetDistance : set.actualDistance
        var parts: [String] = []
        if !weight.isEmpty {
            parts.append(weightUnit == .imperial ? L10n.weightLbValue(weight) : L10n.weightKgValue(weight))
        }
        if !reps.isEmpty {
            parts.append(L10n.repsValue(reps))
        }
        if !duration.isEmpty {
            parts.append(L10n.durationValue(duration))
        }
        if !distance.isEmpty {
            parts.append(weightUnit == .imperial ? L10n.distanceMiValue(distance) : L10n.distanceKmValue(distance))
        }
        return parts.isEmpty ? "—" : parts.joined(separator: " · ")
    }
}
