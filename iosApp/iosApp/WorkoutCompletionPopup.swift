import SwiftUI
import SharedUI

/// Celebratory modal affirmation overlay presented when a live workout session finishes.
struct WorkoutCompletionPopupModifier: ViewModifier {
    let celebration: WorkoutCompletionCelebration?
    let onDismiss: () -> Void

    func body(content: Content) -> some View {
        content.overlay {
            ZStack {
                if let celebration {
                    Color.black.opacity(0.45)
                        .ignoresSafeArea()
                        .transition(.opacity)
                        .onTapGesture(perform: onDismiss)

                    WorkoutCompletionCard(celebration: celebration, onDismiss: onDismiss)
                        .transition(.scale(scale: 0.9).combined(with: .opacity))
                }
            }
            .animation(.spring(response: 0.35, dampingFraction: 0.82), value: celebration?.sessionId)
        }
    }
}

private struct WorkoutCompletionCard: View {
    let celebration: WorkoutCompletionCelebration
    let onDismiss: () -> Void

    @State private var popped = false

    private var summaryText: String {
        var parts = [
            L10n.exerciseCount(Int(celebration.exerciseCount)),
            L10n.exerciseSetCount(Int(celebration.completedSets)),
        ]
        if celebration.durationSeconds > 0 {
            parts.append(formatElapsed(celebration.durationSeconds))
        }
        return parts.joined(separator: " · ")
    }

    var body: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(Color.accentColor.opacity(0.14))
                    .frame(width: 88, height: 88)
                Text(celebration.emoji)
                    .font(.system(size: 48))
                    .scaleEffect(popped ? 1.0 : 0.55)
            }

            Text(L10n.workoutCompleteTitle)
                .font(.title2.weight(.bold))
                .multilineTextAlignment(.center)

            if !celebration.workoutTitle.isEmpty {
                Text(celebration.workoutTitle)
                    .font(.headline)
                    .foregroundStyle(Color.accentColor)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
            }

            if celebration.exerciseCount > 0 && celebration.completedSets > 0 {
                Text(summaryText)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 6)
                    .background(Color(.secondarySystemBackground), in: Capsule())
            }

            Button(action: onDismiss) {
                Text(L10n.ok)
                    .fontWeight(.semibold)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .buttonBorderShape(.capsule)
            .padding(.top, 4)
        }
        .padding(24)
        .frame(maxWidth: 320)
        .background(
            Color(.systemBackground),
            in: RoundedRectangle(cornerRadius: 28, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.08), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.2), radius: 24, y: 12)
        .padding(.horizontal, 24)
        .onAppear {
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            withAnimation(.spring(response: 0.42, dampingFraction: 0.58)) {
                popped = true
            }
        }
    }

    private func formatElapsed(_ totalSeconds: Int64) -> String {
        let clamped = max(0, Int(totalSeconds))
        let hours = clamped / 3600
        let minutes = (clamped % 3600) / 60
        let seconds = clamped % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            return String(format: "%02d:%02d", minutes, seconds)
        }
    }
}

extension View {
    func workoutCompletionPopup(
        celebration: WorkoutCompletionCelebration?,
        onDismiss: @escaping () -> Void
    ) -> some View {
        modifier(WorkoutCompletionPopupModifier(celebration: celebration, onDismiss: onDismiss))
    }
}
