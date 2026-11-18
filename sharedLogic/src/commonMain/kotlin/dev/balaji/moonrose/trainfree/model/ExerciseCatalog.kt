package dev.balaji.moonrose.trainfree.model

/**
 * Canonical lowercase keys persisted in `exercises.body_part`,
 * `exercises.other_body_parts`, and `exercises.equipment`. Both Compose and
 * SwiftUI pickers and AI tool normalization share these exact sets.
 */
object ExerciseCatalog {
    val BODY_PARTS: List<String> = listOf(
        "back",
        "biceps",
        "calves",
        "chest",
        "core",
        "forearms",
        "full body",
        "glutes",
        "hamstrings",
        "shoulders",
        "thighs",
        "triceps",
    )

    val EQUIPMENT: List<String> = listOf(
        "machine",
        "body weight",
        "cable",
        "barbell",
        "smith machine",
        "dumbbell",
        "kettlebell",
        "other",
        "band",
    )

    private val BODY_PART_SET = BODY_PARTS.toSet()
    private val EQUIPMENT_SET = EQUIPMENT.toSet()

    private val BODY_PART_ALIASES = mapOf(
        "legs" to "thighs",
        "leg" to "thighs",
        "quads" to "thighs",
        "quadriceps" to "thighs",
        "quad" to "thighs",
        "thigh" to "thighs",
        "lower body" to "thighs",
        "adductors" to "thighs",
        "abductors" to "thighs",
        "abs" to "core",
        "abdominals" to "core",
        "obliques" to "core",
        "midsection" to "core",
        "delts" to "shoulders",
        "delt" to "shoulders",
        "deltoids" to "shoulders",
        "shoulder" to "shoulders",
        "rear delts" to "shoulders",
        "front delts" to "shoulders",
        "side delts" to "shoulders",
        "lats" to "back",
        "upper back" to "back",
        "lower back" to "back",
        "traps" to "back",
        "rhomboids" to "back",
        "pecs" to "chest",
        "pectorals" to "chest",
        "bicep" to "biceps",
        "tricep" to "triceps",
        "forearm" to "forearms",
        "calf" to "calves",
        "glute" to "glutes",
        "hamstring" to "hamstrings",
        "arms" to "biceps",
        "fullbody" to "full body",
        "cardio" to "full body",
        "conditioning" to "full body",
    )

    private val EQUIPMENT_ALIASES = mapOf(
        "bodyweight" to "body weight",
        "bw" to "body weight",
        "calisthenics" to "body weight",
        "none" to "body weight",
        "bb" to "barbell",
        "bar" to "barbell",
        "ez bar" to "barbell",
        "db" to "dumbbell",
        "dumbbells" to "dumbbell",
        "kb" to "kettlebell",
        "kettlebells" to "kettlebell",
        "cables" to "cable",
        "pulley" to "cable",
        "machines" to "machine",
        "smith" to "smith machine",
        "bands" to "band",
        "resistance band" to "band",
        "resistance bands" to "band",
    )

    private val EXERCISE_TYPE_ALIASES = mapOf(
        "strength" to ExerciseType.STRENGTH.value,
        "reps" to ExerciseType.STRENGTH.value,
        "weight" to ExerciseType.STRENGTH.value,
        "weighted" to ExerciseType.STRENGTH.value,
        "hypertrophy" to ExerciseType.STRENGTH.value,
        "duration" to ExerciseType.DURATION.value,
        "time" to ExerciseType.DURATION.value,
        "timed" to ExerciseType.DURATION.value,
        "hold" to ExerciseType.DURATION.value,
        "isometric" to ExerciseType.DURATION.value,
        "distance" to ExerciseType.DISTANCE.value,
        "reps_duration" to ExerciseType.REPS_DURATION.value,
        "reps duration" to ExerciseType.REPS_DURATION.value,
        "distance_duration" to ExerciseType.DISTANCE_DURATION.value,
        "distance duration" to ExerciseType.DISTANCE_DURATION.value,
        "cardio" to ExerciseType.DISTANCE_DURATION.value,
        "reps_distance" to ExerciseType.REPS_DISTANCE.value,
        "reps distance" to ExerciseType.REPS_DISTANCE.value,
        "carry" to ExerciseType.REPS_DISTANCE.value,
    )

    /** Normalizes a free-form body-part label to a canonical catalog key. */
    fun normalizeBodyPart(raw: String?): String {
        val cleaned = normalizeKey(raw) ?: return "full body"
        if (cleaned in BODY_PART_SET) return cleaned
        return BODY_PART_ALIASES[cleaned] ?: "full body"
    }

    /**
     * Normalizes optional secondary muscle labels, excluding `"full body"`,
     * unrecognized values, and [primaryBodyPart].
     */
    fun normalizeOtherBodyParts(raw: List<String>, primaryBodyPart: String): List<String> =
        raw.mapNotNull { entry ->
            val cleaned = normalizeKey(entry) ?: return@mapNotNull null
            when {
                cleaned in BODY_PART_SET -> cleaned
                else -> BODY_PART_ALIASES[cleaned]
            }
        }
            .filter { it != "full body" && it != primaryBodyPart }
            .distinct()

    /** Normalizes an optional equipment string; returns null only when blank/omitted. */
    fun normalizeEquipment(raw: String?): String? {
        val cleaned = normalizeKey(raw) ?: return null
        if (cleaned in EQUIPMENT_SET) return cleaned
        return EQUIPMENT_ALIASES[cleaned] ?: "other"
    }

    /** Normalizes an exercise type string to a canonical [ExerciseType.value], defaulting to `"strength"`. */
    fun normalizeExerciseType(raw: String?): String {
        val cleaned = normalizeKey(raw)?.replace('-', '_') ?: return ExerciseType.STRENGTH.value
        ExerciseType.fromValue(cleaned)?.let { return it.value }
        return EXERCISE_TYPE_ALIASES[cleaned.replace('_', ' ')]
            ?: EXERCISE_TYPE_ALIASES[cleaned]
            ?: ExerciseType.STRENGTH.value
    }

    private fun normalizeKey(raw: String?): String? =
        raw?.trim()
            ?.lowercase()
            ?.replace('_', ' ')
            ?.split(' ')
            ?.filter { it.isNotEmpty() }
            ?.joinToString(" ")
            ?.takeIf { it.isNotEmpty() }
}
