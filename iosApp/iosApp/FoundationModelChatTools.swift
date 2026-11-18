import Foundation
import FoundationModels
import SharedUI

/// Thread-safe accumulator collecting structured [ChatSourceRef] pills across
/// tool calls made during one model turn.
final class ChatSourceCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var refs: [ChatSourceRef] = []

    func append(_ newRefs: [ChatSourceRef]) {
        guard !newRefs.isEmpty else { return }
        lock.lock()
        refs.append(contentsOf: newRefs)
        lock.unlock()
    }

    func drain() -> [ChatSourceRef] {
        lock.lock()
        let snapshot = refs
        refs.removeAll()
        lock.unlock()

        var seen = Set<String>()
        return snapshot.filter { ref in
            seen.insert("\(ref.kind):\(ref.exerciseId):\(ref.epochDay):\(ref.programId):\(ref.routineId):\(ref.label)").inserted
        }
    }
}

/// Builds the `Tool` set backed by Kotlin's `ChatToolbox` for a session.
func makeFoundationChatTools(collector: ChatSourceCollector) -> [any Tool] {
    [
        GetTrainingSummaryTool(collector: collector),
        GetProgressReviewTool(collector: collector),
        GetPersonalRecordsTool(collector: collector),
        GetExerciseProgressionTool(collector: collector),
        GetRecentWorkoutsTool(collector: collector),
        GetWorkoutsForDayTool(collector: collector),
        GetProgramsAndRoutinesTool(collector: collector),
        SearchExercisesTool(collector: collector),
        GetBodyMetricsAndProfileTool(collector: collector),
        CreateExerciseTool(collector: collector),
        CreateProgramTool(collector: collector),
        AddRoutineToProgramTool(collector: collector),
        UpdateRoutineTool(collector: collector),
    ]
}

private func executeTool(
    collector: ChatSourceCollector,
    block: () async throws -> ToolOutput
) async throws -> String {
    let output = try await block()
    collector.append(output.sources)
    return output.summary
}

struct GetTrainingSummaryTool: Tool {
    let name = "get_training_summary"
    let description = "Weekly training volume trend, workout frequency/streaks, and body-part breakdown."
    let collector: ChatSourceCollector

    @Generable
    struct Arguments {
        @Guide(description: "Lookback window in weeks, 1 to 12. Defaults to 8.")
        var weeks: Int?
    }

    func call(arguments: Arguments) async throws -> String {
        try await executeTool(collector: collector) {
            try await ChatToolbox.shared.getTrainingSummary(weeks: Int32(arguments.weeks ?? 8))
        }
    }
}

struct GetProgressReviewTool: Tool {
    let name = "get_progress_review"
    let description = "Review workout history: period volume/session deltas, progressing vs plateaued lifts, untrained muscles, and journal notes."
    let collector: ChatSourceCollector

    @Generable
    struct Arguments {
        @Guide(description: "Lookback window in days, 7 to 180. Defaults to 30.")
        var days: Int?
    }

    func call(arguments: Arguments) async throws -> String {
        try await executeTool(collector: collector) {
            try await ChatToolbox.shared.getProgressReview(days: Int32(arguments.days ?? 30))
        }
    }
}

struct GetPersonalRecordsTool: Tool {
    let name = "get_personal_records"
    let description = "Personal records across exercises or for one named exercise."
    let collector: ChatSourceCollector

    @Generable
    struct Arguments {
        @Guide(description: "Optional exercise name or abbreviation (e.g. bench, OHP, RDL).")
        var exerciseQuery: String?
        @Guide(description: "Max PR rows when unfiltered, 1 to 12. Defaults to 5.")
        var limit: Int?
    }

    func call(arguments: Arguments) async throws -> String {
        try await executeTool(collector: collector) {
            try await ChatToolbox.shared.getPersonalRecords(
                exerciseQuery: arguments.exerciseQuery,
                limit: Int32(arguments.limit ?? 5)
            )
        }
    }
}

struct GetExerciseProgressionTool: Tool {
    let name = "get_exercise_progression"
    let description = "Weekly progression and recent working sets for one exercise."
    let collector: ChatSourceCollector

    @Generable
    struct Arguments {
        @Guide(description: "Exercise name or abbreviation.")
        var exerciseQuery: String
        @Guide(description: "Lookback window in weeks, 2 to 24. Defaults to 12.")
        var weeks: Int?
    }

    func call(arguments: Arguments) async throws -> String {
        try await executeTool(collector: collector) {
            try await ChatToolbox.shared.getExerciseProgression(
                exerciseQuery: arguments.exerciseQuery,
                weeks: Int32(arguments.weeks ?? 12)
            )
        }
    }
}

struct GetRecentWorkoutsTool: Tool {
    let name = "get_recent_workouts"
    let description = "Recent completed workouts with date, duration, routine, working sets, and journal notes."
    let collector: ChatSourceCollector

    @Generable
    struct Arguments {
        @Guide(description: "Max workouts to return, 1 to 15. Defaults to 5.")
        var limit: Int?
        @Guide(description: "Optional routine name filter.")
        var routineQuery: String?
        @Guide(description: "Optional lookback window in days.")
        var days: Int?
        @Guide(description: "Optional exercise name filter.")
        var exerciseQuery: String?
        @Guide(description: "Optional start date YYYY-MM-DD.")
        var startDate: String?
        @Guide(description: "Optional end date YYYY-MM-DD.")
        var endDate: String?
    }

    func call(arguments: Arguments) async throws -> String {
        try await executeTool(collector: collector) {
            try await ChatToolbox.shared.getRecentWorkouts(
                limit: Int32(arguments.limit ?? 5),
                routineQuery: arguments.routineQuery,
                days: arguments.days.map { KotlinInt(int: Int32($0)) },
                exerciseQuery: arguments.exerciseQuery,
                startDate: arguments.startDate,
                endDate: arguments.endDate
            )
        }
    }
}

struct GetWorkoutsForDayTool: Tool {
    let name = "get_workouts_for_day"
    let description = "Completed workouts and journal note logged on one specific date."
    let collector: ChatSourceCollector

    @Generable
    struct Arguments {
        @Guide(description: "Date as YYYY-MM-DD, \"today\", or \"yesterday\".")
        var date: String?
        @Guide(description: "Optional day index as days since 1970-01-01.")
        var epochDay: Int?
    }

    func call(arguments: Arguments) async throws -> String {
        try await executeTool(collector: collector) {
            try await ChatToolbox.shared.getWorkoutsForDay(
                epochDay: arguments.epochDay.map { KotlinLong(longLong: Int64($0)) },
                date: arguments.date
            )
        }
    }
}

struct GetProgramsAndRoutinesTool: Tool {
    let name = "get_programs_and_routines"
    let description = "Programs, routines in order, target sets/weights, and last-performed dates."
    let collector: ChatSourceCollector

    @Generable
    struct Arguments {
        @Guide(description: "Optional routine name filter.")
        var routineQuery: String?
        @Guide(description: "Optional program name filter.")
        var programQuery: String?
    }

    func call(arguments: Arguments) async throws -> String {
        try await executeTool(collector: collector) {
            try await ChatToolbox.shared.getProgramsAndRoutines(
                routineQuery: arguments.routineQuery,
                programQuery: arguments.programQuery
            )
        }
    }
}

struct SearchExercisesTool: Tool {
    let name = "search_exercises"
    let description = "Search the user's exercise catalog by name, body part, or equipment for substitutes or exercise metadata."
    let collector: ChatSourceCollector

    @Generable
    struct Arguments {
        @Guide(description: "Optional exercise name or keyword.")
        var query: String?
        @Guide(description: "Optional muscle or group such as chest, back, legs, or arms.")
        var bodyPart: String?
        @Guide(description: "Optional equipment such as barbell, dumbbell, cable, or bodyweight.")
        var equipment: String?
        @Guide(description: "Max results, 1 to 12. Defaults to 8.")
        var limit: Int?
    }

    func call(arguments: Arguments) async throws -> String {
        try await executeTool(collector: collector) {
            try await ChatToolbox.shared.searchExercises(
                query: arguments.query,
                bodyPart: arguments.bodyPart,
                equipment: arguments.equipment,
                limit: Int32(arguments.limit ?? 8)
            )
        }
    }
}

struct GetBodyMetricsAndProfileTool: Tool {
    let name = "get_body_metrics_and_profile"
    let description = "Body weight trend, profile details, and BMI computed with the configured formula."
    let collector: ChatSourceCollector

    @Generable
    struct Arguments {
        @Guide(description: "Lookback window in days, 7 to 365. Defaults to 90.")
        var days: Int?
    }

    func call(arguments: Arguments) async throws -> String {
        try await executeTool(collector: collector) {
            try await ChatToolbox.shared.getBodyMetricsAndProfile(days: Int32(arguments.days ?? 90))
        }
    }
}

@Generable
struct AiRoutineExerciseArgument {
    @Guide(description: "Exercise name (existing or new).")
    var exerciseName: String
    @Guide(description: "Working sets count. Defaults to 3.")
    var sets: Int?
    @Guide(description: "Target reps per set.")
    var reps: Int?
    @Guide(description: "Optional target weight in user units.")
    var weight: Double?
    @Guide(description: "Target duration in seconds for timed exercises.")
    var durationSeconds: Int?
    @Guide(description: "Target distance in user units.")
    var distance: Double?
    @Guide(description: "Primary muscle if creating a new exercise.")
    var bodyPart: String?
    @Guide(description: "Equipment if disambiguating or creating.")
    var equipment: String?
    @Guide(description: "Measurement type: strength, duration, distance, reps_duration, distance_duration, reps_distance.")
    var exerciseType: String?
    @Guide(description: "Optional secondary muscles.")
    var otherBodyParts: [String]?

    func toKotlin() -> AiExerciseInput {
        AiExerciseInput(
            exerciseName: exerciseName,
            sets: Int32(sets ?? 3),
            reps: reps.map { KotlinInt(int: Int32($0)) },
            weight: weight.map { KotlinDouble(double: $0) },
            durationSeconds: durationSeconds.map { KotlinInt(int: Int32($0)) },
            distance: distance.map { KotlinDouble(double: $0) },
            warmupSets: 0,
            bodyPart: bodyPart,
            otherBodyParts: otherBodyParts ?? [],
            equipment: equipment,
            exerciseType: exerciseType
        )
    }
}

@Generable
struct AiRoutineArgument {
    @Guide(description: "Routine name, e.g. Push or Upper A.")
    var name: String
    @Guide(description: "Exercises in this routine.")
    var exercises: [AiRoutineExerciseArgument]

    func toKotlin() -> AiRoutineInput {
        AiRoutineInput(
            name: name,
            exercises: exercises.map { $0.toKotlin() }
        )
    }
}

@Generable
struct AiReplaceExerciseArgument {
    @Guide(description: "Existing exercise in the routine to replace.")
    var targetExerciseName: String
    @Guide(description: "Replacement exercise name (existing or new).")
    var newExerciseName: String
    @Guide(description: "Optional working sets count.")
    var sets: Int?
    @Guide(description: "Optional target reps.")
    var reps: Int?
    @Guide(description: "Optional target weight.")
    var weight: Double?
    @Guide(description: "Optional target duration in seconds.")
    var durationSeconds: Int?
    @Guide(description: "Optional target distance.")
    var distance: Double?
    @Guide(description: "Primary muscle if creating a new exercise.")
    var bodyPart: String?
    @Guide(description: "Equipment if disambiguating or creating.")
    var equipment: String?
    @Guide(description: "Measurement type if creating.")
    var exerciseType: String?
    @Guide(description: "Optional secondary muscles.")
    var otherBodyParts: [String]?

    func toKotlin() -> AiReplaceExerciseInput {
        AiReplaceExerciseInput(
            targetExerciseName: targetExerciseName,
            newExerciseName: newExerciseName,
            sets: sets.map { KotlinInt(int: Int32($0)) },
            reps: reps.map { KotlinInt(int: Int32($0)) },
            weight: weight.map { KotlinDouble(double: $0) },
            durationSeconds: durationSeconds.map { KotlinInt(int: Int32($0)) },
            distance: distance.map { KotlinDouble(double: $0) },
            bodyPart: bodyPart,
            otherBodyParts: otherBodyParts ?? [],
            equipment: equipment,
            exerciseType: exerciseType
        )
    }
}

struct CreateExerciseTool: Tool {
    let name = "create_exercise"
    let description = "Create a custom exercise in the catalog with body part focus, equipment, and exercise type."
    let collector: ChatSourceCollector

    @Generable
    struct Arguments {
        @Guide(description: "Exercise name.")
        var name: String
        @Guide(description: "Primary muscle: back, biceps, calves, chest, core, forearms, full body, glutes, hamstrings, shoulders, thighs, triceps.")
        var bodyPart: String
        @Guide(description: "Equipment: barbell, dumbbell, cable, machine, smith machine, body weight, kettlebell, band, other.")
        var equipment: String?
        @Guide(description: "Measurement type: strength, duration, distance, reps_duration, distance_duration, reps_distance.")
        var exerciseType: String?
        @Guide(description: "Optional secondary muscles.")
        var otherBodyParts: [String]?
    }

    func call(arguments: Arguments) async throws -> String {
        try await executeTool(collector: collector) {
            try await ChatToolbox.shared.createExercise(
                name: arguments.name,
                bodyPart: arguments.bodyPart,
                equipment: arguments.equipment,
                exerciseType: arguments.exerciseType,
                otherBodyParts: arguments.otherBodyParts ?? []
            )
        }
    }
}

struct CreateProgramTool: Tool {
    let name = "create_program"
    let description = "Create a workout program with one or more routines and exercises. Missing exercises are created automatically."
    let collector: ChatSourceCollector

    @Generable
    struct Arguments {
        @Guide(description: "Program name.")
        var programName: String
        @Guide(description: "Workout days or routines in split order.")
        var routines: [AiRoutineArgument]
    }

    func call(arguments: Arguments) async throws -> String {
        try await executeTool(collector: collector) {
            try await ChatToolbox.shared.createProgram(
                programName: arguments.programName,
                routines: arguments.routines.map { $0.toKotlin() }
            )
        }
    }
}

struct AddRoutineToProgramTool: Tool {
    let name = "add_routine_to_program"
    let description = "Add a new routine to an existing program (or create the program if needed). Missing exercises are created automatically."
    let collector: ChatSourceCollector

    @Generable
    struct Arguments {
        @Guide(description: "Routine name.")
        var routineName: String
        @Guide(description: "Target program name (optional if only one program exists).")
        var programName: String?
        @Guide(description: "Exercises in this routine.")
        var exercises: [AiRoutineExerciseArgument]
    }

    func call(arguments: Arguments) async throws -> String {
        try await executeTool(collector: collector) {
            try await ChatToolbox.shared.addRoutineToProgram(
                routineName: arguments.routineName,
                exercises: arguments.exercises.map { $0.toKotlin() },
                programName: arguments.programName
            )
        }
    }
}

struct UpdateRoutineTool: Tool {
    let name = "update_routine"
    let description = "Edit an existing routine: rename, add, replace, update sets/reps/weights, or remove exercises."
    let collector: ChatSourceCollector

    @Generable
    struct Arguments {
        @Guide(description: "Existing routine name.")
        var routineName: String
        @Guide(description: "Optional program name if routine names repeat.")
        var programName: String?
        @Guide(description: "Optional new name for the routine.")
        var newRoutineName: String?
        @Guide(description: "Exercises to append.")
        var addExercises: [AiRoutineExerciseArgument]?
        @Guide(description: "Exercises to swap in-place.")
        var replaceExercises: [AiReplaceExerciseArgument]?
        @Guide(description: "Existing exercises whose sets, reps, or weights to update.")
        var updateExercises: [AiRoutineExerciseArgument]?
        @Guide(description: "Exercise names to remove from the routine.")
        var removeExercises: [String]?
    }

    func call(arguments: Arguments) async throws -> String {
        try await executeTool(collector: collector) {
            try await ChatToolbox.shared.updateRoutine(
                routineName: arguments.routineName,
                programName: arguments.programName,
                newRoutineName: arguments.newRoutineName,
                addExercises: (arguments.addExercises ?? []).map { $0.toKotlin() },
                replaceExercises: (arguments.replaceExercises ?? []).map { $0.toKotlin() },
                updateExercises: (arguments.updateExercises ?? []).map { $0.toKotlin() },
                removeExercises: arguments.removeExercises ?? []
            )
        }
    }
}
