import SwiftUI
import SharedUI

private let weekNumberWidth: CGFloat = 32
private let weekdayLabelsSundayFirst = L10n.weekdaysShortSundayFirst
private let weekdayLabelsMondayFirst = L10n.weekdaysShortMondayFirst

private struct IdentifiableEpochDay: Identifiable, Hashable {
    let id: Int64
}

private struct IdentifiableCalendarDay: Identifiable {
    let day: CalendarDay
    var id: Int64 { day.epochDay }
}

private enum PendingDaySheet {
    case logWorkout(Int64)
    case writeJournal(Int64)
}

struct CalendarView: View {
    @State private var model: CalendarModel?
    @State private var settings: UserSettings?
    @State private var showingSessionList = false
    @State private var actionDay: IdentifiableCalendarDay?
    @State private var pendingDaySheet: PendingDaySheet?
    @State private var pushedDayDetail: IdentifiableEpochDay?
    @State private var logWorkoutDay: IdentifiableEpochDay?
    @State private var journalEditorDay: IdentifiableEpochDay?

    private var weekStartsMonday: Bool { settings?.weekStart == WeekStart.monday }

    var body: some View {
        Group {
            if showingSessionList {
                JournalDayListView()
            } else if let model {
                calendarContent(model)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L10n.tabJournal)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showingSessionList.toggle()
                } label: {
                    Image(systemName: showingSessionList ? "calendar" : "list.bullet")
                }
                .accessibilityLabel(showingSessionList ? L10n.showCalendar : L10n.showWorkoutHistory)
            }
        }
        .navigationDestination(for: Int64.self) { epochDay in
            DayDetailView(epochDay: epochDay)
        }
        .navigationDestination(item: $pushedDayDetail) { target in
            DayDetailView(epochDay: target.id)
        }
        .sheet(item: $actionDay, onDismiss: presentPendingSheet) { target in
            DayActionSheetView(
                day: target.day,
                title: Self.dayTitle(target.id),
                onLogWorkout: {
                    pendingDaySheet = .logWorkout(target.id)
                    actionDay = nil
                },
                onEditExistingWorkout: {
                    actionDay = nil
                    pushedDayDetail = IdentifiableEpochDay(id: target.id)
                },
                onWriteJournal: {
                    pendingDaySheet = .writeJournal(target.id)
                    actionDay = nil
                }
            )
        }
        .sheet(item: $logWorkoutDay) { target in
            NavigationStack {
                WorkoutSessionView(
                    mode: .logCompleted(epochDay: target.id, title: L10n.logWorkoutTitle)
                )
            }
        }
        .sheet(item: $journalEditorDay) { target in
            JournalEditorView(epochDay: target.id)
        }
        .task {
            for await m in CalendarRepository.shared.calendar {
                model = m
            }
        }
        .task {
            for await value in UserSettingsRepository.shared.settings {
                settings = value
            }
        }
    }

    private func calendarContent(_ model: CalendarModel) -> some View {
        VStack(spacing: 0) {
            weekdayHeader
            Divider()
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(model.weeks, id: \.weekStartEpochDay) { week in
                            WeekRowView(week: week) { day in
                                actionDay = IdentifiableCalendarDay(day: day)
                            }
                            .id(week.weekStartEpochDay)
                        }
                    }
                }
                .floatingWorkoutMenuClearance()
                .onAppear {
                    let index = max(Int(model.currentWeekIndex) - 2, 0)
                    guard model.weeks.indices.contains(index) else { return }
                    let target = model.weeks[index].weekStartEpochDay
                    // Deferred so LazyVStack has laid out before scrolling.
                    DispatchQueue.main.async {
                        proxy.scrollTo(target, anchor: .top)
                    }
                }
            }
        }
    }

    private var weekdayHeader: some View {
        let labels = weekStartsMonday ? weekdayLabelsMondayFirst : weekdayLabelsSundayFirst
        return HStack(spacing: 0) {
            Text(L10n.weekNumberAbbrev)
                .frame(width: weekNumberWidth)
            ForEach(labels, id: \.self) { label in
                Text(label)
                    .frame(maxWidth: .infinity)
            }
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 8)
        .padding(.vertical, 8)
    }

    private func presentPendingSheet() {
        guard let pending = pendingDaySheet else { return }
        pendingDaySheet = nil
        switch pending {
        case .logWorkout(let epochDay):
            logWorkoutDay = IdentifiableEpochDay(id: epochDay)
        case .writeJournal(let epochDay):
            journalEditorDay = IdentifiableEpochDay(id: epochDay)
        }
    }

    private static func dayTitle(_ epochDay: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(epochDay) * 86400)
        return dayFormatter.string(from: date)
    }

    private static let dayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MMM d, yyyy"
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter
    }()
}

private struct WeekRowView: View {
    let week: CalendarWeek
    let onTapDay: (CalendarDay) -> Void

    var body: some View {
        HStack(spacing: 0) {
            Text("\(week.weekNumber)")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .frame(width: weekNumberWidth)
            ForEach(week.days, id: \.epochDay) { day in
                DayCellView(day: day, onTap: { onTapDay(day) })
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.horizontal, 8)
    }
}

/// Month + 2-digit year abbreviation for day 1 of a month, e.g. "Nov'26".
private func monthYearAbbreviation(_ monthOfYear: Int32, _ year: Int32) -> String {
    let month = L10n.monthAbbreviation(Int(monthOfYear))
    let shortYear = ((Int(year) % 100) + 100) % 100
    return L10n.monthYearShort(month, String(format: "%02d", shortYear))
}

private struct DayCellView: View {
    let day: CalendarDay
    let onTap: () -> Void

    private var hasActivity: Bool { day.hasCompletedWorkout || day.hasJournal }

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 2) {
                // Blank placeholder keeps row heights uniform.
                Text(day.dayOfMonth == 1 ? monthYearAbbreviation(day.monthOfYear, day.year) : " ")
                    .font(.caption2)
                    .foregroundStyle(.tint)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                Text("\(day.dayOfMonth)")
                    .font(.body.weight(hasActivity ? .bold : .regular))
                    .foregroundStyle(day.isFuture ? AnyShapeStyle(.tertiary) : AnyShapeStyle(.primary))
                    .frame(width: 28, height: 28)
                    .background(
                        day.isToday ? AnyShapeStyle(.tint.opacity(0.2)) : AnyShapeStyle(.clear),
                        in: Circle(),
                    )
                HStack(spacing: 3) {
                    if !day.hasCompletedWorkout && !day.hasJournal {
                        Circle()
                            .fill(.clear)
                            .frame(width: 5, height: 5)
                    } else {
                        if day.hasCompletedWorkout {
                            Circle()
                                .fill(.tint)
                                .frame(width: 5, height: 5)
                        }
                        if day.hasJournal {
                            Circle()
                                .fill(Color.purple)
                                .frame(width: 5, height: 5)
                        }
                    }
                }
            }
            .padding(.vertical, 4)
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
        .background(day.isWeekend ? Color(.secondarySystemBackground) : Color.clear)
    }
}

// MARK: - Journal day list

/// Flat, most-recent-first list of days with a completed workout and/or
/// journal entry, from the shared `journalDays` flow.
private struct JournalDayListView: View {
    @State private var days: [JournalDaySummary]?

    var body: some View {
        Group {
            if let days {
                if days.isEmpty {
                    ContentUnavailableView(L10n.noJournalOrWorkouts, systemImage: "book.closed")
                } else {
                    List(days, id: \.epochDay) { day in
                        NavigationLink(value: day.epochDay) {
                            JournalDayRow(day: day)
                        }
                    }
                    .floatingWorkoutMenuClearance()
                }
            } else {
                ProgressView()
            }
        }
        .task {
            for await value in CalendarRepository.shared.journalDays {
                days = value
            }
        }
    }
}

private struct JournalDayRow: View {
    let day: JournalDaySummary

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(Self.dayFormatter.string(from: Date(timeIntervalSince1970: Double(day.epochDay) * 86400)))
                .font(.body)
            if !day.routineNames.isEmpty {
                Text(day.routineNames.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if day.hasJournal {
                HStack(spacing: 6) {
                    Circle()
                        .fill(Color.purple)
                        .frame(width: 6, height: 6)
                    Text(day.journalPreview.isEmpty ? L10n.journalEntryBadge : day.journalPreview)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
        }
        .padding(.vertical, 2)
    }

    private static let dayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MMM d, yyyy"
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter
    }()
}

private struct DayActionSheetView: View {
    let day: CalendarDay
    let title: String
    let onLogWorkout: () -> Void
    let onEditExistingWorkout: () -> Void
    let onWriteJournal: () -> Void

    @State private var sheetHeight: CGFloat

    init(
        day: CalendarDay,
        title: String,
        onLogWorkout: @escaping () -> Void,
        onEditExistingWorkout: @escaping () -> Void,
        onWriteJournal: @escaping () -> Void
    ) {
        self.day = day
        self.title = title
        self.onLogWorkout = onLogWorkout
        self.onEditExistingWorkout = onEditExistingWorkout
        self.onWriteJournal = onWriteJournal
        _sheetHeight = State(initialValue: day.isFuture ? 130 : (day.hasCompletedWorkout ? 235 : 182))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(.headline)
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 12)
            Divider()
            VStack(spacing: 0) {
                if !day.isFuture {
                    DayActionRow(
                        title: L10n.logWorkoutTitle,
                        systemImage: "dumbbell.fill",
                        iconColor: .accentColor,
                        action: onLogWorkout
                    )
                    if day.hasCompletedWorkout {
                        DayActionRow(
                            title: L10n.editWorkoutTitle,
                            systemImage: "pencil",
                            iconColor: .accentColor,
                            action: onEditExistingWorkout
                        )
                    }
                }
                DayActionRow(
                    title: L10n.journal,
                    systemImage: "list.clipboard.fill",
                    iconColor: .purple,
                    action: onWriteJournal
                )
            }
            .padding(.vertical, 4)
        }
        .onGeometryChange(for: CGFloat.self) { proxy in
            proxy.size.height
        } action: { newHeight in
            if newHeight > 0 {
                sheetHeight = newHeight
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .presentationDetents([.height(sheetHeight)])
        .presentationDragIndicator(.visible)
    }
}

private struct DayActionRow: View {
    let title: String
    let systemImage: String
    let iconColor: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: systemImage)
                    .font(.body.weight(.medium))
                    .foregroundStyle(iconColor)
                    .frame(width: 24, alignment: .center)
                Text(title)
                    .font(.body)
                    .foregroundStyle(.primary)
                Spacer()
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(DayActionRowButtonStyle())
    }
}

private struct DayActionRowButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color(.tertiarySystemFill) : .clear)
    }
}

#Preview {
    CalendarView()
}
