package dev.balaji.moonrose.trainfree.model

/**
 * Celebratory summary emitted once when a live workout finishes, rendered by
 * the platform shell as a modal pop-up.
 */
data class WorkoutCompletionCelebration(
    val sessionId: Long,
    val workoutTitle: String,
    val completedSets: Int,
    val exerciseCount: Int,
    val durationSeconds: Long,
    val emoji: String,
)

val CELEBRATORY_EMOJIS: List<String> = listOf("🎉", "💪", "🔥", "🏆", "🙌")

fun buildWorkoutCompletionCelebration(
    sessionId: Long,
    workoutTitle: String,
    completedSets: Int,
    exerciseCount: Int,
    durationSeconds: Long,
    seed: Long,
): WorkoutCompletionCelebration {
    val emojiIndex = seed.mod(CELEBRATORY_EMOJIS.size)
    return WorkoutCompletionCelebration(
        sessionId = sessionId,
        workoutTitle = workoutTitle,
        completedSets = completedSets.coerceAtLeast(0),
        exerciseCount = exerciseCount.coerceAtLeast(0),
        durationSeconds = durationSeconds.coerceAtLeast(0L),
        emoji = CELEBRATORY_EMOJIS[emojiIndex],
    )
}
