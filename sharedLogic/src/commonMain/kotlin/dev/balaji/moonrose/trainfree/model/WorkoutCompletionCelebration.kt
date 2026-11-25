package dev.balaji.moonrose.trainfree.model

/**
 * One exercise where the user set a new all-time estimated 1RM during a
 * completed workout, formatted in [WorkoutCompletionCelebration.weightUnit].
 */
data class NewOneRepMaxAchievement(
    val exerciseId: Long,
    /** Exercise name — catalog/user data, shown verbatim. */
    val exerciseName: String,
    /** New estimated 1RM in [WorkoutCompletionCelebration.weightUnit], pre-formatted (e.g. "102.5"). */
    val formattedNewOneRm: String,
    /** Positive improvement over previous all-time best in [WorkoutCompletionCelebration.weightUnit], pre-formatted (e.g. "5"). */
    val formattedGain: String,
    val newOneRm: Double,
    val previousOneRm: Double,
)

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
    val newOneRepMaxes: List<NewOneRepMaxAchievement> = emptyList(),
    val weightUnit: WeightUnit = WeightUnit.METRIC,
)

val CELEBRATORY_EMOJIS: List<String> = listOf("🎉", "💪", "🔥", "🏆", "🙌")

private const val TROPHY_EMOJI = "🏆"

fun buildWorkoutCompletionCelebration(
    sessionId: Long,
    workoutTitle: String,
    completedSets: Int,
    exerciseCount: Int,
    durationSeconds: Long,
    seed: Long,
    newOneRepMaxes: List<NewOneRepMaxAchievement> = emptyList(),
    weightUnit: WeightUnit = WeightUnit.METRIC,
): WorkoutCompletionCelebration {
    val emoji = if (newOneRepMaxes.isNotEmpty()) {
        TROPHY_EMOJI
    } else {
        CELEBRATORY_EMOJIS[seed.mod(CELEBRATORY_EMOJIS.size)]
    }
    return WorkoutCompletionCelebration(
        sessionId = sessionId,
        workoutTitle = workoutTitle,
        completedSets = completedSets.coerceAtLeast(0),
        exerciseCount = exerciseCount.coerceAtLeast(0),
        durationSeconds = durationSeconds.coerceAtLeast(0L),
        emoji = emoji,
        newOneRepMaxes = newOneRepMaxes,
        weightUnit = weightUnit,
    )
}
