package dev.balaji.moonrose.trainfree.db

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Seeds the exercise catalog when the database is first created, and ensures
 * builtin exercises are populated on open (e.g. after a destructive migration,
 * where Room skips [onCreate]).
 */
internal object ExerciseSeedCallback : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) {
        seed(connection)
    }

    override fun onOpen(connection: SQLiteConnection) {
        if (hasBuiltinExercises(connection)) return
        connection.execSQL("BEGIN EXCLUSIVE TRANSACTION")
        runCatching {
            if (!hasBuiltinExercises(connection)) {
                seed(connection)
            }
        }.onSuccess {
            connection.execSQL("END TRANSACTION")
        }.onFailure {
            connection.execSQL("ROLLBACK TRANSACTION")
            throw it
        }
    }

    private fun hasBuiltinExercises(connection: SQLiteConnection): Boolean =
        connection.prepare("SELECT EXISTS(SELECT 1 FROM exercises WHERE builtin != 0)").use {
            it.step() && it.getLong(0) != 0L
        }

    private fun seed(connection: SQLiteConnection) {
        val now = nowMillis()
        SEED_EXERCISES.forEach { seed ->
            val otherBodyParts = seed.otherBodyParts.joinToString(",")
            connection.prepare(
                "INSERT OR IGNORE INTO exercises " +
                    "(name, exercise_type, body_part, other_body_parts, equipment, " +
                    "builtin, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.bindText(1, seed.name)
                statement.bindText(2, seed.type)
                statement.bindText(3, seed.bodyPart)
                statement.bindText(4, otherBodyParts)
                statement.bindText(5, seed.equipment)
                statement.bindLong(6, 1L)
                statement.bindLong(7, now)
                statement.bindLong(8, now)
                statement.step()
            }
        }
    }

    internal fun migrateBuiltinExercises(connection: SQLiteConnection) {
        val now = nowMillis()
        RENAMED_SEED_EXERCISES.forEach { (oldName, newName) ->
            connection.prepare(
                "UPDATE OR IGNORE exercises SET name = ?, updated_at = ? " +
                    "WHERE name = ? AND builtin != 0",
            ).use { statement ->
                statement.bindText(1, newName)
                statement.bindLong(2, now)
                statement.bindText(3, oldName)
                statement.step()
            }
        }
        SEED_EXERCISES.forEach { seed ->
            val otherBodyParts = seed.otherBodyParts.joinToString(",")
            connection.prepare(
                "UPDATE exercises SET exercise_type = ?, body_part = ?, " +
                    "other_body_parts = ?, equipment = ?, updated_at = ? " +
                    "WHERE name = ? AND builtin != 0",
            ).use { statement ->
                statement.bindText(1, seed.type)
                statement.bindText(2, seed.bodyPart)
                statement.bindText(3, otherBodyParts)
                statement.bindText(4, seed.equipment)
                statement.bindLong(5, now)
                statement.bindText(6, seed.name)
                statement.step()
            }
        }
    }
}

private data class Seed(
    val name: String,
    val type: String,
    val bodyPart: String,
    val otherBodyParts: List<String>,
    val equipment: String,
)

private val RENAMED_SEED_EXERCISES = listOf(
    "One-Arm Dumbbell Row" to "Single-Arm Dumbbell Row",
    "Dumbbell Shrug-Press Combo (Bradford Press)" to "Bradford Press",
    "Dumbbell Bicep Curl" to "Dumbbell Biceps Curl",
    "Chin-Up (Biceps Focus)" to "Close-Grip Chin-Up",
    "Dumbbell Sissy Squat Hybrid (Cyclist Squat)" to "Cyclist Squat",
    "Standing Cable Lat Prayer Pushdown" to "Kneeling Cable Lat Prayer",
)

private val SEED_EXERCISES = listOf(
    Seed("Barbell Bench Press", "strength", "chest", listOf("triceps", "shoulders"), "barbell"),
    Seed("Barbell Incline Bench Press", "strength", "chest", listOf("shoulders", "triceps"), "barbell"),
    Seed("Barbell Decline Bench Press", "strength", "chest", listOf("triceps", "shoulders"), "barbell"),
    Seed("Close-Grip Bench Press", "strength", "triceps", listOf("chest", "shoulders"), "barbell"),
    Seed("Barbell Floor Press", "strength", "chest", listOf("triceps", "shoulders"), "barbell"),
    Seed("Barbell Pullover", "strength", "chest", listOf("back", "triceps"), "barbell"),
    Seed("Dumbbell Bench Press", "strength", "chest", listOf("triceps", "shoulders"), "dumbbell"),
    Seed("Incline Dumbbell Press", "strength", "chest", listOf("shoulders", "triceps"), "dumbbell"),
    Seed("Decline Dumbbell Press", "strength", "chest", listOf("triceps", "shoulders"), "dumbbell"),
    Seed("Dumbbell Fly", "strength", "chest", listOf("shoulders"), "dumbbell"),
    Seed("Incline Dumbbell Fly", "strength", "chest", listOf("shoulders"), "dumbbell"),
    Seed("Dumbbell Pullover", "strength", "chest", listOf("back", "triceps"), "dumbbell"),
    Seed("Dumbbell Squeeze Press", "strength", "chest", listOf("triceps", "shoulders"), "dumbbell"),
    Seed("Smith Machine Bench Press", "strength", "chest", listOf("triceps", "shoulders"), "smith machine"),
    Seed("Smith Machine Incline Press", "strength", "chest", listOf("shoulders", "triceps"), "smith machine"),
    Seed("Cable Crossover", "strength", "chest", listOf("shoulders"), "cable"),
    Seed("Low Cable Crossover", "strength", "chest", listOf("shoulders"), "cable"),
    Seed("Cable Chest Press", "strength", "chest", listOf("triceps", "shoulders"), "cable"),
    Seed("Single-Arm Cable Fly", "strength", "chest", listOf("core", "shoulders"), "cable"),
    Seed("Pec Deck Machine Fly", "strength", "chest", listOf("shoulders"), "machine"),
    Seed("Machine Chest Press", "strength", "chest", listOf("triceps", "shoulders"), "machine"),
    Seed("Push-Up", "strength", "chest", listOf("triceps", "shoulders", "core"), "body weight"),
    Seed("Wide-Grip Push-Up", "strength", "chest", listOf("triceps", "shoulders", "core"), "body weight"),
    Seed("Diamond Push-Up", "strength", "triceps", listOf("chest", "shoulders", "core"), "body weight"),
    Seed("Decline Push-Up", "strength", "chest", listOf("shoulders", "triceps", "core"), "body weight"),
    Seed("Incline Push-Up", "strength", "chest", listOf("triceps", "shoulders"), "body weight"),
    Seed("Plyometric Push-Up", "strength", "chest", listOf("triceps", "shoulders", "core"), "body weight"),
    Seed("Chest Dip", "strength", "chest", listOf("triceps", "shoulders"), "body weight"),
    Seed("Kettlebell Floor Press", "strength", "chest", listOf("triceps", "shoulders"), "kettlebell"),
    Seed("Svend Press", "strength", "chest", listOf("shoulders", "triceps"), "other"),
    Seed("Resistance Band Chest Press", "strength", "chest", listOf("triceps", "shoulders"), "band"),
    Seed("Conventional Deadlift", "strength", "back", listOf("glutes", "hamstrings", "thighs", "forearms", "core"), "barbell"),
    Seed("Sumo Deadlift", "strength", "back", listOf("glutes", "thighs", "hamstrings", "forearms"), "barbell"),
    Seed("Romanian Deadlift", "strength", "hamstrings", listOf("glutes", "back", "forearms"), "barbell"),
    Seed("Barbell Bent-Over Row", "strength", "back", listOf("biceps", "shoulders", "forearms", "core"), "barbell"),
    Seed("Pendlay Row", "strength", "back", listOf("biceps", "shoulders", "core"), "barbell"),
    Seed("Yates Row", "strength", "back", listOf("biceps", "shoulders", "forearms"), "barbell"),
    Seed("T-Bar Row", "strength", "back", listOf("biceps", "shoulders"), "barbell"),
    Seed("Barbell Shrug", "strength", "back", listOf("forearms"), "barbell"),
    Seed("Rack Pull", "strength", "back", listOf("glutes", "hamstrings", "forearms"), "barbell"),
    Seed("Good Morning", "strength", "hamstrings", listOf("glutes", "back"), "barbell"),
    Seed("Meadows Row", "strength", "back", listOf("biceps", "shoulders"), "barbell"),
    Seed("Single-Arm Dumbbell Row", "strength", "back", listOf("biceps", "shoulders", "core"), "dumbbell"),
    Seed("Chest-Supported Dumbbell Row", "strength", "back", listOf("biceps", "shoulders"), "dumbbell"),
    Seed("Dumbbell Shrug", "strength", "back", listOf("forearms"), "dumbbell"),
    Seed("Dumbbell Deadlift", "strength", "back", listOf("glutes", "hamstrings", "thighs"), "dumbbell"),
    Seed("Kroc Row", "strength", "back", listOf("biceps", "forearms", "shoulders"), "dumbbell"),
    Seed("Pull-Up", "strength", "back", listOf("biceps", "forearms", "core"), "body weight"),
    Seed("Chin-Up", "strength", "back", listOf("biceps", "forearms"), "body weight"),
    Seed("Neutral-Grip Pull-Up", "strength", "back", listOf("biceps", "forearms"), "body weight"),
    Seed("Wide-Grip Pull-Up", "strength", "back", listOf("biceps", "shoulders"), "body weight"),
    Seed("Inverted Row", "strength", "back", listOf("biceps", "shoulders", "core"), "body weight"),
    Seed("Back Extension", "strength", "back", listOf("glutes", "hamstrings"), "body weight"),
    Seed("Superman Hold", "duration", "back", listOf("glutes"), "body weight"),
    Seed("Lat Pulldown", "strength", "back", listOf("biceps", "forearms"), "cable"),
    Seed("Close-Grip Lat Pulldown", "strength", "back", listOf("biceps"), "cable"),
    Seed("Reverse-Grip Lat Pulldown", "strength", "back", listOf("biceps"), "cable"),
    Seed("Straight-Arm Pulldown", "strength", "back", listOf("core"), "cable"),
    Seed("Seated Cable Row", "strength", "back", listOf("biceps", "shoulders"), "cable"),
    Seed("Wide-Grip Seated Cable Row", "strength", "back", listOf("shoulders", "biceps"), "cable"),
    Seed("Single-Arm Cable Row", "strength", "back", listOf("biceps", "shoulders", "core"), "cable"),
    Seed("Face Pull", "strength", "shoulders", listOf("back"), "cable"),
    Seed("Cable Shrug", "strength", "back", listOf("forearms"), "cable"),
    Seed("Machine Row", "strength", "back", listOf("biceps", "shoulders"), "machine"),
    Seed("Machine-Assisted Pull-Up", "strength", "back", listOf("biceps"), "machine"),
    Seed("Smith Machine Bent-Over Row", "strength", "back", listOf("biceps", "shoulders"), "smith machine"),
    Seed("Kettlebell Single-Arm Row", "strength", "back", listOf("biceps", "shoulders", "core"), "kettlebell"),
    Seed("Renegade Row", "strength", "back", listOf("biceps", "core", "chest", "shoulders", "triceps"), "dumbbell"),
    Seed("Barbell Overhead Press", "strength", "shoulders", listOf("triceps", "core"), "barbell"),
    Seed("Seated Barbell Shoulder Press", "strength", "shoulders", listOf("triceps"), "barbell"),
    Seed("Behind-the-Neck Press", "strength", "shoulders", listOf("triceps"), "barbell"),
    Seed("Push Press", "strength", "shoulders", listOf("triceps", "thighs", "core"), "barbell"),
    Seed("Barbell Upright Row", "strength", "shoulders", listOf("back", "biceps"), "barbell"),
    Seed("Landmine Press", "strength", "shoulders", listOf("chest", "triceps", "core"), "barbell"),
    Seed("Barbell Front Raise", "strength", "shoulders", listOf("core"), "barbell"),
    Seed("Dumbbell Shoulder Press", "strength", "shoulders", listOf("triceps"), "dumbbell"),
    Seed("Arnold Press", "strength", "shoulders", listOf("triceps"), "dumbbell"),
    Seed("Dumbbell Lateral Raise", "strength", "shoulders", listOf("back"), "dumbbell"),
    Seed("Dumbbell Front Raise", "strength", "shoulders", emptyList(), "dumbbell"),
    Seed("Bent-Over Reverse Fly", "strength", "shoulders", listOf("back"), "dumbbell"),
    Seed("Seated Dumbbell Rear Delt Fly", "strength", "shoulders", listOf("back"), "dumbbell"),
    Seed("Dumbbell Upright Row", "strength", "shoulders", listOf("back", "biceps"), "dumbbell"),
    Seed("Cuban Press", "strength", "shoulders", listOf("back"), "dumbbell"),
    Seed("Bradford Press", "strength", "shoulders", listOf("triceps", "back"), "barbell"),
    Seed("Smith Machine Shoulder Press", "strength", "shoulders", listOf("triceps"), "smith machine"),
    Seed("Smith Machine Upright Row", "strength", "shoulders", listOf("back", "biceps"), "smith machine"),
    Seed("Cable Lateral Raise", "strength", "shoulders", listOf("back"), "cable"),
    Seed("Cable Front Raise", "strength", "shoulders", emptyList(), "cable"),
    Seed("Cable Rear Delt Fly", "strength", "shoulders", listOf("back"), "cable"),
    Seed("Cable Upright Row", "strength", "shoulders", listOf("back", "biceps"), "cable"),
    Seed("Machine Shoulder Press", "strength", "shoulders", listOf("triceps"), "machine"),
    Seed("Machine Lateral Raise", "strength", "shoulders", listOf("back"), "machine"),
    Seed("Reverse Pec Deck", "strength", "shoulders", listOf("back"), "machine"),
    Seed("Pike Push-Up", "strength", "shoulders", listOf("triceps", "core"), "body weight"),
    Seed("Handstand Push-Up", "strength", "shoulders", listOf("triceps", "core"), "body weight"),
    Seed("Wall Walk", "strength", "shoulders", listOf("core", "chest"), "body weight"),
    Seed("Kettlebell Overhead Press", "strength", "shoulders", listOf("triceps", "core"), "kettlebell"),
    Seed("Kettlebell Push Press", "strength", "shoulders", listOf("triceps", "thighs"), "kettlebell"),
    Seed("Kettlebell Halo", "strength", "shoulders", listOf("core", "triceps"), "kettlebell"),
    Seed("Band Pull-Apart", "strength", "shoulders", listOf("back"), "band"),
    Seed("Front Plate Raise", "strength", "shoulders", listOf("core"), "other"),
    Seed("Barbell Curl", "strength", "biceps", listOf("forearms"), "barbell"),
    Seed("EZ-Bar Curl", "strength", "biceps", listOf("forearms"), "barbell"),
    Seed("Barbell Preacher Curl", "strength", "biceps", listOf("forearms"), "barbell"),
    Seed("Barbell Drag Curl", "strength", "biceps", listOf("forearms"), "barbell"),
    Seed("Reverse Barbell Curl", "strength", "biceps", listOf("forearms"), "barbell"),
    Seed("Dumbbell Biceps Curl", "strength", "biceps", listOf("forearms"), "dumbbell"),
    Seed("Hammer Curl", "strength", "biceps", listOf("forearms"), "dumbbell"),
    Seed("Incline Dumbbell Curl", "strength", "biceps", listOf("forearms"), "dumbbell"),
    Seed("Concentration Curl", "strength", "biceps", listOf("forearms"), "dumbbell"),
    Seed("Zottman Curl", "strength", "biceps", listOf("forearms"), "dumbbell"),
    Seed("Spider Curl", "strength", "biceps", listOf("forearms"), "dumbbell"),
    Seed("Cable Curl", "strength", "biceps", listOf("forearms"), "cable"),
    Seed("Cable Rope Hammer Curl", "strength", "biceps", listOf("forearms"), "cable"),
    Seed("High Cable Curl", "strength", "biceps", listOf("forearms"), "cable"),
    Seed("Machine Preacher Curl", "strength", "biceps", listOf("forearms"), "machine"),
    Seed("Close-Grip Chin-Up", "strength", "biceps", listOf("back", "forearms"), "body weight"),
    Seed("Resistance Band Curl", "strength", "biceps", listOf("forearms"), "band"),
    Seed("Kettlebell Curl", "strength", "biceps", listOf("forearms"), "kettlebell"),
    Seed("Skull Crusher", "strength", "triceps", emptyList(), "barbell"),
    Seed("Incline Skull Crusher", "strength", "triceps", emptyList(), "barbell"),
    Seed("JM Press", "strength", "triceps", listOf("chest", "shoulders"), "barbell"),
    Seed("Lying Dumbbell Triceps Extension", "strength", "triceps", emptyList(), "dumbbell"),
    Seed("Overhead Dumbbell Triceps Extension", "strength", "triceps", listOf("core"), "dumbbell"),
    Seed("Dumbbell Kickback", "strength", "triceps", emptyList(), "dumbbell"),
    Seed("Tate Press", "strength", "triceps", listOf("chest"), "dumbbell"),
    Seed("Cable Triceps Pushdown", "strength", "triceps", emptyList(), "cable"),
    Seed("Rope Pushdown", "strength", "triceps", emptyList(), "cable"),
    Seed("Overhead Cable Triceps Extension", "strength", "triceps", listOf("core"), "cable"),
    Seed("Single-Arm Reverse-Grip Pushdown", "strength", "triceps", emptyList(), "cable"),
    Seed("Bench Dip", "strength", "triceps", listOf("chest", "shoulders"), "body weight"),
    Seed("Triceps Dip", "strength", "triceps", listOf("chest", "shoulders"), "body weight"),
    Seed("Close-Grip Push-Up", "strength", "triceps", listOf("chest", "shoulders", "core"), "body weight"),
    Seed("Bodyweight Triceps Extension", "strength", "triceps", listOf("core", "shoulders"), "body weight"),
    Seed("Machine Triceps Extension", "strength", "triceps", emptyList(), "machine"),
    Seed("Machine Dip", "strength", "triceps", listOf("chest", "shoulders"), "machine"),
    Seed("Smith Machine Close-Grip Bench Press", "strength", "triceps", listOf("chest", "shoulders"), "smith machine"),
    Seed("Band Triceps Pushdown", "strength", "triceps", emptyList(), "band"),
    Seed("Diamond Knee Push-Up", "strength", "triceps", listOf("chest", "shoulders", "core"), "body weight"),
    Seed("Barbell Back Squat", "strength", "thighs", listOf("glutes", "hamstrings", "calves", "core"), "barbell"),
    Seed("Barbell Front Squat", "strength", "thighs", listOf("glutes", "core"), "barbell"),
    Seed("Barbell Box Squat", "strength", "thighs", listOf("glutes", "hamstrings"), "barbell"),
    Seed("Barbell Pause Squat", "strength", "thighs", listOf("glutes", "core"), "barbell"),
    Seed("Zercher Squat", "strength", "thighs", listOf("glutes", "core", "biceps"), "barbell"),
    Seed("Barbell Lunge", "strength", "thighs", listOf("glutes", "hamstrings", "calves"), "barbell"),
    Seed("Barbell Reverse Lunge", "strength", "thighs", listOf("glutes", "hamstrings"), "barbell"),
    Seed("Barbell Walking Lunge", "strength", "thighs", listOf("glutes", "hamstrings", "calves", "core"), "barbell"),
    Seed("Barbell Bulgarian Split Squat", "strength", "thighs", listOf("glutes", "hamstrings"), "barbell"),
    Seed("Barbell Step-Up", "strength", "thighs", listOf("glutes", "calves"), "barbell"),
    Seed("Barbell Hack Squat", "strength", "thighs", listOf("glutes", "forearms"), "barbell"),
    Seed("Overhead Squat", "strength", "thighs", listOf("shoulders", "core", "glutes"), "barbell"),
    Seed("Goblet Squat", "strength", "thighs", listOf("glutes", "core"), "dumbbell"),
    Seed("Dumbbell Lunge", "strength", "thighs", listOf("glutes", "hamstrings", "forearms"), "dumbbell"),
    Seed("Dumbbell Bulgarian Split Squat", "strength", "thighs", listOf("glutes", "hamstrings"), "dumbbell"),
    Seed("Dumbbell Step-Up", "strength", "thighs", listOf("glutes", "calves"), "dumbbell"),
    Seed("Dumbbell Squat", "strength", "thighs", listOf("glutes", "forearms"), "dumbbell"),
    Seed("Cyclist Squat", "strength", "thighs", listOf("glutes"), "dumbbell"),
    Seed("Smith Machine Squat", "strength", "thighs", listOf("glutes", "hamstrings"), "smith machine"),
    Seed("Smith Machine Split Squat", "strength", "thighs", listOf("glutes", "hamstrings"), "smith machine"),
    Seed("Smith Machine Front Squat", "strength", "thighs", listOf("glutes", "core"), "smith machine"),
    Seed("Leg Press", "strength", "thighs", listOf("glutes", "hamstrings"), "machine"),
    Seed("Single-Leg Press", "strength", "thighs", listOf("glutes", "hamstrings"), "machine"),
    Seed("Hack Squat Machine", "strength", "thighs", listOf("glutes"), "machine"),
    Seed("Leg Extension", "strength", "thighs", emptyList(), "machine"),
    Seed("Belt Squat", "strength", "thighs", listOf("glutes", "hamstrings"), "machine"),
    Seed("Machine Sissy Squat", "strength", "thighs", listOf("core"), "machine"),
    Seed("Bodyweight Squat", "strength", "thighs", listOf("glutes", "calves"), "body weight"),
    Seed("Jump Squat", "strength", "thighs", listOf("glutes", "calves"), "body weight"),
    Seed("Pistol Squat", "strength", "thighs", listOf("glutes", "core", "calves"), "body weight"),
    Seed("Walking Lunge", "strength", "thighs", listOf("glutes", "hamstrings", "calves"), "body weight"),
    Seed("Split Squat", "strength", "thighs", listOf("glutes", "hamstrings"), "body weight"),
    Seed("Wall Sit", "duration", "thighs", listOf("glutes"), "body weight"),
    Seed("Sissy Squat", "strength", "thighs", listOf("core"), "body weight"),
    Seed("Step-Up", "strength", "thighs", listOf("glutes", "calves"), "body weight"),
    Seed("Kettlebell Goblet Squat", "strength", "thighs", listOf("glutes", "core"), "kettlebell"),
    Seed("Kettlebell Front Rack Squat", "strength", "thighs", listOf("glutes", "core"), "kettlebell"),
    Seed("Kettlebell Lunge Pass-Through", "strength", "thighs", listOf("glutes", "core", "forearms"), "kettlebell"),
    Seed("Cable Squat", "strength", "thighs", listOf("glutes"), "cable"),
    Seed("Sled Push", "duration", "thighs", listOf("glutes", "calves", "core"), "other"),
    Seed("Stiff-Leg Deadlift", "strength", "hamstrings", listOf("glutes", "back"), "barbell"),
    Seed("Barbell Hip Thrust", "strength", "glutes", listOf("hamstrings", "thighs"), "barbell"),
    Seed("Barbell Glute Bridge", "strength", "glutes", listOf("hamstrings"), "barbell"),
    Seed("Deficit Deadlift", "strength", "back", listOf("glutes", "hamstrings", "thighs", "forearms"), "barbell"),
    Seed("Snatch-Grip Deadlift", "strength", "back", listOf("glutes", "hamstrings", "thighs", "forearms"), "barbell"),
    Seed("Single-Leg Romanian Deadlift", "strength", "hamstrings", listOf("glutes", "back", "core", "forearms"), "dumbbell"),
    Seed("Dumbbell Romanian Deadlift", "strength", "hamstrings", listOf("glutes", "back", "forearms"), "dumbbell"),
    Seed("Nordic Hamstring Curl", "strength", "hamstrings", listOf("calves", "glutes"), "body weight"),
    Seed("Glute Ham Raise", "strength", "hamstrings", listOf("glutes", "calves", "back"), "machine"),
    Seed("Lying Leg Curl", "strength", "hamstrings", listOf("calves"), "machine"),
    Seed("Seated Leg Curl", "strength", "hamstrings", emptyList(), "machine"),
    Seed("Standing Single-Leg Curl", "strength", "hamstrings", emptyList(), "machine"),
    Seed("Cable Pull-Through", "strength", "glutes", listOf("hamstrings"), "cable"),
    Seed("Cable Kickback", "strength", "glutes", listOf("hamstrings"), "cable"),
    Seed("Cable Hip Abduction", "strength", "glutes", emptyList(), "cable"),
    Seed("Hip Abduction Machine", "strength", "glutes", emptyList(), "machine"),
    Seed("Hip Adduction Machine", "strength", "thighs", emptyList(), "machine"),
    Seed("Kettlebell Swing", "strength", "glutes", listOf("hamstrings", "back", "core", "shoulders", "forearms"), "kettlebell"),
    Seed("Single-Leg Glute Bridge", "strength", "glutes", listOf("hamstrings", "core"), "body weight"),
    Seed("Frog Pump", "strength", "glutes", emptyList(), "body weight"),
    Seed("Reverse Hyperextension", "strength", "glutes", listOf("hamstrings", "back"), "machine"),
    Seed("Smith Machine Hip Thrust", "strength", "glutes", listOf("hamstrings"), "smith machine"),
    Seed("Donkey Kick", "strength", "glutes", listOf("core"), "body weight"),
    Seed("Fire Hydrant", "strength", "glutes", listOf("core"), "body weight"),
    Seed("Standing Calf Raise", "strength", "calves", emptyList(), "machine"),
    Seed("Seated Calf Raise", "strength", "calves", emptyList(), "machine"),
    Seed("Barbell Standing Calf Raise", "strength", "calves", listOf("core"), "barbell"),
    Seed("Dumbbell Single-Leg Calf Raise", "strength", "calves", emptyList(), "dumbbell"),
    Seed("Smith Machine Calf Raise", "strength", "calves", emptyList(), "smith machine"),
    Seed("Leg Press Calf Raise", "strength", "calves", emptyList(), "machine"),
    Seed("Donkey Calf Raise", "strength", "calves", emptyList(), "machine"),
    Seed("Bodyweight Calf Raise", "strength", "calves", emptyList(), "body weight"),
    Seed("Tibialis Raise", "strength", "calves", emptyList(), "body weight"),
    Seed("Barbell Wrist Curl", "strength", "forearms", emptyList(), "barbell"),
    Seed("Barbell Reverse Wrist Curl", "strength", "forearms", emptyList(), "barbell"),
    Seed("Dumbbell Wrist Curl", "strength", "forearms", emptyList(), "dumbbell"),
    Seed("Farmer's Walk", "duration", "forearms", listOf("back", "core", "calves"), "dumbbell"),
    Seed("Suitcase Carry", "duration", "forearms", listOf("core", "back"), "kettlebell"),
    Seed("Plate Pinch", "duration", "forearms", emptyList(), "other"),
    Seed("Wrist Roller", "strength", "forearms", listOf("shoulders"), "other"),
    Seed("Dead Hang", "duration", "forearms", listOf("back", "shoulders"), "body weight"),
    Seed("Cable Wrist Curl", "strength", "forearms", emptyList(), "cable"),
    Seed("Plank", "duration", "core", listOf("shoulders", "glutes"), "body weight"),
    Seed("Side Plank", "duration", "core", listOf("shoulders", "glutes"), "body weight"),
    Seed("Crunch", "strength", "core", emptyList(), "body weight"),
    Seed("Bicycle Crunch", "strength", "core", emptyList(), "body weight"),
    Seed("Reverse Crunch", "strength", "core", emptyList(), "body weight"),
    Seed("Sit-Up", "strength", "core", listOf("thighs"), "body weight"),
    Seed("V-Up", "strength", "core", listOf("thighs"), "body weight"),
    Seed("Hollow Body Hold", "duration", "core", listOf("thighs"), "body weight"),
    Seed("Leg Raise", "strength", "core", listOf("thighs"), "body weight"),
    Seed("Hanging Knee Raise", "strength", "core", listOf("thighs", "forearms"), "body weight"),
    Seed("Hanging Leg Raise", "strength", "core", listOf("thighs", "forearms"), "body weight"),
    Seed("Toes-to-Bar", "strength", "core", listOf("thighs", "back", "forearms"), "body weight"),
    Seed("Mountain Climber", "duration", "core", listOf("shoulders", "thighs"), "body weight"),
    Seed("Dead Bug", "strength", "core", listOf("thighs"), "body weight"),
    Seed("Bird Dog", "strength", "core", listOf("glutes", "back"), "body weight"),
    Seed("Russian Twist", "strength", "core", listOf("thighs"), "body weight"),
    Seed("Flutter Kick", "duration", "core", listOf("thighs"), "body weight"),
    Seed("Ab Wheel Rollout", "strength", "core", listOf("back", "shoulders"), "other"),
    Seed("Barbell Rollout", "strength", "core", listOf("back", "shoulders"), "barbell"),
    Seed("Landmine Rotation", "strength", "core", listOf("shoulders"), "barbell"),
    Seed("Weighted Sit-Up", "strength", "core", listOf("thighs"), "other"),
    Seed("Cable Crunch", "strength", "core", emptyList(), "cable"),
    Seed("Cable Woodchopper", "strength", "core", listOf("shoulders"), "cable"),
    Seed("Low-to-High Cable Chop", "strength", "core", listOf("shoulders"), "cable"),
    Seed("Pallof Press", "strength", "core", listOf("shoulders"), "cable"),
    Seed("Ab Crunch Machine", "strength", "core", emptyList(), "machine"),
    Seed("Torso Rotation Machine", "strength", "core", emptyList(), "machine"),
    Seed("Roman Chair Sit-Up", "strength", "core", listOf("thighs"), "machine"),
    Seed("Decline Sit-Up", "strength", "core", listOf("thighs"), "body weight"),
    Seed("Dragon Flag", "strength", "core", listOf("back", "glutes"), "body weight"),
    Seed("L-Sit", "duration", "core", listOf("thighs", "triceps", "shoulders"), "body weight"),
    Seed("Suitcase Deadlift", "strength", "core", listOf("thighs", "glutes", "hamstrings", "back", "forearms"), "kettlebell"),
    Seed("Stir the Pot", "strength", "core", listOf("shoulders"), "other"),
    Seed("Power Clean", "strength", "full body", listOf("thighs", "glutes", "back", "shoulders"), "barbell"),
    Seed("Hang Clean", "strength", "full body", listOf("thighs", "glutes", "hamstrings", "back", "shoulders", "forearms"), "barbell"),
    Seed("Clean and Press", "strength", "full body", listOf("shoulders", "thighs", "glutes", "back", "triceps"), "barbell"),
    Seed("Barbell Snatch", "strength", "full body", listOf("thighs", "glutes", "back", "shoulders"), "barbell"),
    Seed("Power Snatch", "strength", "full body", listOf("thighs", "glutes", "shoulders", "back"), "barbell"),
    Seed("Barbell Thruster", "strength", "full body", listOf("thighs", "glutes", "shoulders", "triceps"), "barbell"),
    Seed("Barbell High Pull", "strength", "full body", listOf("thighs", "glutes", "hamstrings", "back", "shoulders"), "barbell"),
    Seed("Landmine Squat to Press", "strength", "full body", listOf("thighs", "shoulders", "core"), "barbell"),
    Seed("Dumbbell Thruster", "strength", "full body", listOf("thighs", "shoulders", "triceps"), "dumbbell"),
    Seed("Dumbbell Snatch", "strength", "full body", listOf("thighs", "glutes", "hamstrings", "back", "shoulders"), "dumbbell"),
    Seed("Dumbbell Clean and Press", "strength", "full body", listOf("shoulders", "thighs", "glutes", "back", "triceps"), "dumbbell"),
    Seed("Man Maker", "strength", "full body", listOf("chest", "back", "thighs", "shoulders", "core"), "dumbbell"),
    Seed("Devil Press", "strength", "full body", listOf("chest", "shoulders", "glutes", "core"), "dumbbell"),
    Seed("Kettlebell Clean", "strength", "full body", listOf("glutes", "hamstrings", "back", "shoulders", "forearms", "core"), "kettlebell"),
    Seed("Kettlebell Snatch", "strength", "full body", listOf("thighs", "glutes", "hamstrings", "back", "shoulders", "forearms"), "kettlebell"),
    Seed("Turkish Get-Up", "strength", "full body", listOf("shoulders", "core", "glutes"), "kettlebell"),
    Seed("Kettlebell Goblet Clean", "strength", "full body", listOf("glutes", "thighs", "core"), "kettlebell"),
    Seed("Kettlebell Clean and Jerk", "strength", "full body", listOf("shoulders", "thighs", "glutes", "triceps"), "kettlebell"),
    Seed("Double Kettlebell Front Squat", "strength", "thighs", listOf("glutes", "core"), "kettlebell"),
    Seed("Kettlebell Windmill", "strength", "full body", listOf("core", "shoulders", "hamstrings"), "kettlebell"),
    Seed("Kettlebell Figure-8", "strength", "full body", listOf("core", "forearms", "glutes"), "kettlebell"),
    Seed("Burpee", "strength", "full body", listOf("chest", "thighs", "core"), "body weight"),
    Seed("Bear Crawl", "duration", "full body", listOf("shoulders", "core", "thighs"), "body weight"),
    Seed("Broad Jump", "strength", "thighs", listOf("glutes", "hamstrings", "calves"), "body weight"),
    Seed("Box Jump", "strength", "thighs", listOf("glutes", "hamstrings", "calves"), "body weight"),
    Seed("Sled Drag", "duration", "thighs", listOf("glutes", "hamstrings", "calves", "core"), "other"),
    Seed("Battle Rope Wave", "duration", "full body", listOf("shoulders", "core", "forearms"), "other"),
    Seed("Medicine Ball Slam", "strength", "full body", listOf("core", "shoulders", "back"), "other"),
    Seed("Wall Ball", "strength", "full body", listOf("thighs", "shoulders", "core"), "other"),
    Seed("Tire Flip", "strength", "full body", listOf("glutes", "back", "thighs", "biceps"), "other"),
    Seed("Sandbag Carry", "duration", "full body", listOf("core", "forearms", "back", "thighs", "glutes", "shoulders"), "other"),
    Seed("Smith Machine Inverted Row", "strength", "back", listOf("biceps", "shoulders", "core"), "smith machine"),
    Seed("Cable Reverse Fly (High Pulley)", "strength", "shoulders", listOf("back"), "cable"),
    Seed("Trap Bar Deadlift", "strength", "full body", listOf("thighs", "glutes", "back", "forearms"), "barbell"),
    Seed("Jefferson Squat", "strength", "thighs", listOf("glutes", "core"), "barbell"),
    Seed("Resistance Band Squat", "strength", "thighs", listOf("glutes"), "band"),
    Seed("Resistance Band Deadlift", "strength", "hamstrings", listOf("glutes", "back"), "band"),
    Seed("Resistance Band Row", "strength", "back", listOf("biceps", "shoulders"), "band"),
    Seed("Glute Kickback Machine", "strength", "glutes", listOf("hamstrings"), "machine"),
    Seed("Pull-Over Machine", "strength", "back", listOf("chest", "triceps"), "machine"),
    Seed("Kneeling Cable Lat Prayer", "strength", "back", listOf("core"), "cable"),
    Seed("Seal Row", "strength", "back", listOf("biceps", "shoulders"), "barbell"),
    Seed("Dumbbell Around the World", "strength", "shoulders", listOf("chest", "back"), "dumbbell"),
    Seed("Cable Y-Raise", "strength", "shoulders", listOf("back"), "cable"),
    Seed("Reverse Nordic Curl", "strength", "thighs", listOf("core"), "body weight"),
    Seed("Copenhagen Plank", "duration", "core", listOf("thighs"), "body weight"),
    Seed("Outdoor Run", "distance_duration", "thighs", listOf("glutes", "hamstrings", "calves"), "body weight"),
    Seed("Open Swim", "distance_duration", "full body", listOf("back", "shoulders", "core"), "body weight"),
    Seed("Outdoor Cycling", "distance_duration", "thighs", listOf("glutes", "calves"), "other"),
    Seed("Hike", "distance_duration", "thighs", listOf("glutes", "calves"), "body weight"),
    Seed("Treadmill Run", "distance_duration", "thighs", listOf("glutes", "hamstrings", "calves"), "machine"),
    Seed("Stationary Bike", "distance_duration", "thighs", listOf("glutes", "calves"), "machine"),
    Seed("Rowing Machine", "distance_duration", "full body", listOf("back", "thighs", "glutes", "core"), "machine"),
    Seed("Stair Climber", "duration", "thighs", listOf("glutes", "calves"), "machine"),
    Seed("Elliptical Trainer", "distance_duration", "full body", listOf("thighs", "glutes", "calves"), "machine"),
    Seed("Dumbbell Walking Lunge", "strength", "thighs", listOf("glutes", "hamstrings", "forearms"), "dumbbell"),
)
