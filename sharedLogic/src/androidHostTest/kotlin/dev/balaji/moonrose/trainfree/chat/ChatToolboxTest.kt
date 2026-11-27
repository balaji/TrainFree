package dev.balaji.moonrose.trainfree.chat

import dev.balaji.moonrose.trainfree.calendar.WeekStart
import dev.balaji.moonrose.trainfree.db.AiCreateExerciseResult
import dev.balaji.moonrose.trainfree.db.AiCreateProgramResult
import dev.balaji.moonrose.trainfree.db.AiRoutineAddResult
import dev.balaji.moonrose.trainfree.db.AiRoutineUpdateResult
import dev.balaji.moonrose.trainfree.db.CreatedRoutineSummary
import dev.balaji.moonrose.trainfree.db.JournalEntryEntity
import dev.balaji.moonrose.trainfree.db.RecentCompletedSessionRow
import dev.balaji.moonrose.trainfree.db.RoutineLastPerformedRow
import dev.balaji.moonrose.trainfree.db.SessionDetailWithExerciseRow
import dev.balaji.moonrose.trainfree.db.SetPoint
import dev.balaji.moonrose.trainfree.db.WorkSetDetailEntity
import dev.balaji.moonrose.trainfree.db.WorkSetEntity
import dev.balaji.moonrose.trainfree.insights.BodyPartSlice
import dev.balaji.moonrose.trainfree.insights.BodyWeightTrend
import dev.balaji.moonrose.trainfree.insights.ExercisePr
import dev.balaji.moonrose.trainfree.insights.OneRmFormula
import dev.balaji.moonrose.trainfree.insights.SparkPoint
import dev.balaji.moonrose.trainfree.insights.TrainingFrequency
import dev.balaji.moonrose.trainfree.insights.WeeklyVolumePoint
import dev.balaji.moonrose.trainfree.model.Exercise
import dev.balaji.moonrose.trainfree.model.Profile
import dev.balaji.moonrose.trainfree.model.Program
import dev.balaji.moonrose.trainfree.model.Routine
import dev.balaji.moonrose.trainfree.model.SetType
import dev.balaji.moonrose.trainfree.model.UserSettings
import dev.balaji.moonrose.trainfree.model.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

class ChatToolboxTest {
    private val bench = Exercise(
        id = 1L,
        name = "Barbell Bench Press",
        exerciseType = "strength",
        bodyPart = "chest",
        equipment = "barbell",
        builtin = true,
        otherBodyParts = listOf("triceps", "shoulders"),
    )

    private val run = Exercise(
        id = 2L,
        name = "Treadmill Run",
        exerciseType = "distance_duration",
        bodyPart = "full body",
        equipment = "machine",
        builtin = true,
    )

    @Test
    fun `training summary formats consistency, volume delta, and body parts`() {
        val volume = listOf(
            WeeklyVolumePoint(weekStartEpochDay = 20_000L, totalVolume = 4_000.0, setCount = 20, sessionCount = 2),
            WeeklyVolumePoint(weekStartEpochDay = 20_007L, totalVolume = 5_000.0, setCount = 25, sessionCount = 3),
        )
        val frequency = TrainingFrequency(
            currentStreakWeeks = 3,
            longestStreakWeeks = 5,
            sessionsLast7Days = 3,
            sessionsLast30Days = 11,
            avgPerWeek12w = 2.5,
        )
        val bodyParts = listOf(BodyPartSlice("chest", 18), BodyPartSlice("back", 14))

        val out = ChatToolbox.trainingSummaryFrom(volume, frequency, bodyParts, WeightUnit.METRIC)
        assertTrue(out.summary.contains("current streak 3 weeks, longest 5 weeks"))
        assertTrue(out.summary.contains("5000 kg, 25 sets in 3 sessions (+25% vs 4-week avg 4000 kg)"))
        assertTrue(out.summary.contains("chest: 18 sets, back: 14 sets"))
        assertEquals("progress", out.sources.single().kind)
    }

    @Test
    fun `personal records and progression support both strength and cardio exercises`() {
        val settings = UserSettings(weightUnit = WeightUnit.IMPERIAL, oneRmFormula = OneRmFormula.EPLEY)
        val strengthSets = listOf(
            SetPoint(20_000L, 1000L, 1L, "Barbell Bench Press", "chest", 185.0, 5, 0, 0.0),
            SetPoint(20_007L, 2000L, 1L, "Barbell Bench Press", "chest", 195.0, 5, 0, 0.0),
            SetPoint(20_014L, 3000L, 1L, "Barbell Bench Press", "chest", 205.0, 3, 0, 0.0),
        )
        val prOut = ChatToolbox.exercisePrFrom(bench, strengthSets, settings)
        assertTrue(prOut.summary.contains("lb"))
        assertTrue(prOut.summary.contains("Heaviest set: 205 lb x 3 reps"))
        assertEquals(1L, prOut.sources.single().exerciseId)

        val progOut = ChatToolbox.exerciseProgressionFrom(
            exercise = bench,
            sets = strengthSets,
            weeks = 6,
            todayEpochDay = 20_015L,
            settings = settings,
        )
        assertTrue(progOut.summary.contains("Est. 1RM trend:"))
        assertTrue(progOut.summary.contains("Most recent sessions:"))
        assertTrue(progOut.summary.contains("205 lb x 3r"))

        val cardioSets = listOf(
            SetPoint(20_010L, 4000L, 2L, "Treadmill Run", "full body", 0.0, 0, 1500, 3.1),
        )
        val cardioPr = ChatToolbox.exercisePrFrom(run, cardioSets, settings)
        assertTrue(cardioPr.summary.contains("Longest duration: 25 min"))
        assertTrue(cardioPr.summary.contains("Longest distance: 3.1 mi"))

        assertEquals("45s", ChatToolbox.formatDuration(45))
        assertEquals("1h 5m", ChatToolbox.formatDuration(3900))
    }

    @Test
    fun `global personal records list formats all exercises with unit and formula`() {
        val prs = listOf(
            ExercisePr(1L, "Barbell Bench Press", 100.0, 5, 20_000L, 116.7),
        )
        val out = ChatToolbox.personalRecordsFrom(prs, WeightUnit.METRIC, "epley")
        assertTrue(out.summary.contains("epley formula"))
        assertTrue(out.summary.contains("100 kg x 5 reps"))
        assertEquals(1L, out.sources.single().exerciseId)
    }

    @Test
    fun `recent workouts groups adjacent exercises, annotates drop sets, and dedupes day sources`() {
        val zone = TimeZone.UTC
        val startMillis = LocalDate(2026, 11, 10).atStartOfDayIn(zone).toEpochMilliseconds()
        val endMillis = startMillis + 45 * 60_000L
        val sessions = listOf(
            RecentCompletedSessionRow(
                id = 10L,
                routineId = 5L,
                routineName = "Push Day",
                performedAt = startMillis,
                completedAt = endMillis,
            ),
        )
        val details = listOf(
            SessionDetailWithExerciseRow(10L, 1L, "Barbell Bench Press", "strength", 80.0, 8, null, null, SetType.NORMAL, WeightUnit.METRIC),
            SessionDetailWithExerciseRow(10L, 1L, "Barbell Bench Press", "strength", 135.0, 10, null, null, SetType.DROP, WeightUnit.IMPERIAL),
        )

        val out = ChatToolbox.recentWorkoutsFrom(sessions, details, zone, WeightUnit.METRIC)
        assertTrue(out.summary.contains("Nov 10, 2026 · Push Day (45 min)"))
        assertTrue(out.summary.contains("Barbell Bench Press: 80 kg x 8r, 61.2 kg x 10r [drop]"))
        assertEquals("day", out.sources.single().kind)
        assertEquals(LocalDate(2026, 11, 10).toEpochDays(), out.sources.single().epochDay)
    }

    @Test
    fun `recent workouts uses custom title or defaults empty workout to Workout on yyyy mm dd`() {
        val zone = TimeZone.UTC
        val startMillis = LocalDate(2026, 11, 10).atStartOfDayIn(zone).toEpochMilliseconds()
        val endMillis = startMillis + 30 * 60_000L
        val sessions = listOf(
            RecentCompletedSessionRow(
                id = 10L,
                routineId = 5L,
                routineName = "Push Day",
                title = "Heavy Bench Focus",
                performedAt = startMillis,
                completedAt = endMillis,
            ),
            RecentCompletedSessionRow(
                id = 11L,
                routineId = null,
                routineName = null,
                title = "",
                performedAt = startMillis,
                completedAt = endMillis,
            ),
        )

        val out = ChatToolbox.recentWorkoutsFrom(sessions, emptyList(), zone, WeightUnit.METRIC)
        assertTrue(out.summary.contains("Nov 10, 2026 · Heavy Bench Focus (30 min)"))
        assertTrue(out.summary.contains("Nov 10, 2026 · Workout on 2026/11/10 (30 min)"))
    }

    @Test
    fun `programs and routines excludes warmups from working set count and shows last done date`() {
        val zone = TimeZone.UTC
        val lastMillis = LocalDate(2026, 11, 8).atStartOfDayIn(zone).toEpochMilliseconds()
        val programs = listOf(Program(1L, "PPL"))
        val routines = listOf(
            Routine(10L, "Push", 1L, 1),
            Routine(11L, "Pull", 1L, 2),
        )
        val workSets = mapOf(
            10L to listOf(WorkSetEntity(100L, 10L, 1L, 1, 0L, 0L)),
            11L to emptyList(),
        )
        val details = mapOf(
            100L to listOf(
                WorkSetDetailEntity(1L, 100L, 1, 40.0, 10, null, null, SetType.WARMUP, WeightUnit.METRIC, 0L, 0L),
                WorkSetDetailEntity(2L, 100L, 2, 80.0, 8, null, null, SetType.NORMAL, WeightUnit.METRIC, 0L, 0L),
                WorkSetDetailEntity(3L, 100L, 3, 80.0, 8, null, null, SetType.NORMAL, WeightUnit.METRIC, 0L, 0L),
                WorkSetDetailEntity(4L, 100L, 4, 60.0, 10, null, null, SetType.DROP, WeightUnit.METRIC, 0L, 0L),
            ),
        )
        val lastPerformed = listOf(RoutineLastPerformedRow(10L, lastMillis))

        val out = ChatToolbox.programsAndRoutinesFrom(
            programs = programs,
            routines = routines,
            workSetsByRoutine = workSets,
            detailsByWorkSet = details,
            exercisesById = mapOf(1L to bench),
            lastPerformed = lastPerformed,
            unit = WeightUnit.METRIC,
            zone = zone,
        )
        assertTrue(out.summary.contains("Push (last done Nov 8, 2026): Barbell Bench Press 2 sets + 1 drop (target 80 kg x 8r)"))
        assertTrue(out.summary.contains("Pull (never performed): no exercises"))

        val imperialOut = ChatToolbox.programsAndRoutinesFrom(
            programs = programs,
            routines = routines,
            workSetsByRoutine = workSets,
            detailsByWorkSet = details,
            exercisesById = mapOf(1L to bench),
            lastPerformed = lastPerformed,
            unit = WeightUnit.IMPERIAL,
            zone = zone,
        )
        assertTrue(imperialOut.summary.contains("target 176.4 lb x 8r"))
    }

    @Test
    fun `exercise search formats metadata and logged set count`() {
        val out = ChatToolbox.exerciseSearchFrom(listOf(bench), mapOf(1L to 12))
        assertTrue(out.summary.contains("secondary: triceps, shoulders"))
        assertTrue(out.summary.contains("12 logged sets"))
    }

    @Test
    fun `body metrics converts imperial weight to kg for BMI`() {
        // Profile stores canonical kg (80.0) and cm (180.0); trend values are in display unit (lb).
        // Standard BMI = 80 / 1.8^2 = 24.69 -> Normal (WHO).
        val profile = Profile(1L, 30, "male", 80.0, 180.0)
        val trend = BodyWeightTrend(
            points = listOf(SparkPoint(20_000L, 178.0), SparkPoint(20_020L, 176.37)),
            latest = 176.37,
            latestEpochDay = 20_020L,
            change30Days = -1.63,
            slopePerWeek = -0.57,
        )
        val settings = UserSettings(weightUnit = WeightUnit.IMPERIAL)

        val out = ChatToolbox.bodyMetricsAndProfileFrom(profile, trend, settings)
        assertTrue(out.summary.contains("current weight: 176.4 lb"))
        assertTrue(out.summary.contains("height: 5 ft 11 in"))
        assertTrue(out.summary.contains("BMI (standard, who): 24.7 (normal)"))
        assertTrue(out.summary.contains("30-day change -1.6 lb"))

        val profileOnly = ChatToolbox.bodyMetricsAndProfileFrom(profile, null, settings)
        assertTrue(profileOnly.summary.contains("BMI (standard, who): 24.7 (normal)"))
    }

    @Test
    fun `write tool outputs format created exercises, programs, and routine updates with source pills`() {
        val customEx = Exercise(
            id = 55L,
            name = "Bulgarian Split Squat",
            exerciseType = "strength",
            bodyPart = "thighs",
            equipment = "dumbbell",
            builtin = false,
            otherBodyParts = listOf("glutes"),
        )
        val exOut = ChatToolbox.createExerciseResultToOutput(AiCreateExerciseResult.Created(customEx))
        assertTrue(exOut.summary.contains("Created exercise \"Bulgarian Split Squat\""))
        assertTrue(exOut.summary.contains("primary: thighs"))
        assertEquals(55L, exOut.sources.single().exerciseId)

        val prog = Program(id = 7L, name = "Lower Focus")
        val routine = Routine(id = 12L, name = "Day 1", programId = 7L, position = 1)
        val detail = WorkSetDetailEntity(1L, 10L, 1, null, 10, null, null, SetType.NORMAL, WeightUnit.METRIC, 0L, 0L)
        val progOut = ChatToolbox.createProgramResultToOutput(
            AiCreateProgramResult.Created(
                program = prog,
                routines = listOf(CreatedRoutineSummary(routine, listOf(customEx to listOf(detail)))),
                createdExercises = listOf(customEx),
            ),
            WeightUnit.METRIC,
        )
        assertTrue(progOut.summary.contains("Created program \"Lower Focus\" with 1 routines"))
        assertTrue(progOut.summary.contains("Bulgarian Split Squat (1x 10r)"))
        assertTrue(progOut.summary.contains("New exercises added to catalog: Bulgarian Split Squat"))
        assertEquals(7L, progOut.sources.first().programId)
        assertEquals(55L, progOut.sources.last().exerciseId)

        val updOut = ChatToolbox.routineUpdateResultToOutput(
            AiRoutineUpdateResult.Updated(
                program = prog,
                routine = routine,
                renamedFromTo = null,
                replaced = listOf("Barbell Back Squat" to "Bulgarian Split Squat"),
                updated = emptyList(),
                removed = emptyList(),
                added = emptyList(),
                notFoundInRoutine = emptyList(),
                createdExercises = listOf(customEx),
                finalExercises = listOf(customEx to listOf(detail)),
            ),
            WeightUnit.METRIC,
        )
        assertTrue(updOut.summary.contains("replaced Barbell Back Squat -> Bulgarian Split Squat"))
        assertEquals(12L, updOut.sources.first().routineId)
    }

    @Test
    fun `progress review compares windows, identifies progressing vs plateaued lifts, untrained muscles, and journal notes`() {
        val zone = TimeZone.UTC
        val today = LocalDate(2026, 11, 17).toEpochDays()
        fun millisAt(day: Long) = LocalDate.fromEpochDays(day).atStartOfDayIn(zone).toEpochMilliseconds()

        val sets = listOf(
            // Prior window (more than 30 days ago)
            SetPoint(today - 40, millisAt(today - 40), 1L, "Barbell Bench Press", "chest", 80.0, 5, 0, 0.0),
            // Current 30-day window: bench trending up, OHP flat (plateau)
            SetPoint(today - 20, millisAt(today - 20), 1L, "Barbell Bench Press", "chest", 85.0, 5, 0, 0.0),
            SetPoint(today - 10, millisAt(today - 10), 1L, "Barbell Bench Press", "chest", 90.0, 5, 0, 0.0),
            SetPoint(today - 2, millisAt(today - 2), 1L, "Barbell Bench Press", "chest", 95.0, 5, 0, 0.0),
            SetPoint(today - 20, millisAt(today - 20), 2L, "Barbell Overhead Press", "shoulders", 50.0, 5, 0, 0.0),
            SetPoint(today - 10, millisAt(today - 10), 2L, "Barbell Overhead Press", "shoulders", 50.0, 5, 0, 0.0),
            SetPoint(today - 2, millisAt(today - 2), 2L, "Barbell Overhead Press", "shoulders", 50.0, 5, 0, 0.0),
        )
        val sessions = listOf(
            RecentCompletedSessionRow(1L, null, "Push", "", millisAt(today - 40), millisAt(today - 40) + 40 * 60_000L),
            RecentCompletedSessionRow(2L, null, "Push", "", millisAt(today - 20), millisAt(today - 20) + 50 * 60_000L),
            RecentCompletedSessionRow(3L, null, "Push", "", millisAt(today - 10), millisAt(today - 10) + 50 * 60_000L),
            RecentCompletedSessionRow(4L, null, "Push", "", millisAt(today - 2), millisAt(today - 2) + 50 * 60_000L),
        )
        val journal = listOf(
            JournalEntryEntity(1L, 1L, today - 2, "Bench felt super crisp today!", 0L, 0L),
        )

        val out = ChatToolbox.progressReviewFrom(
            sets = sets,
            sessions = sessions,
            journalEntries = journal,
            days = 30,
            todayEpochDay = today,
            settings = UserSettings(weightUnit = WeightUnit.METRIC),
            zone = zone,
        )

        assertTrue(out.summary.contains("Progress review (last 30 days vs prior 30 days):"))
        assertTrue(out.summary.contains("3 sessions (vs 1 (+200%))"))
        assertTrue(out.summary.contains("avg 50 min/workout"))
        assertTrue(out.summary.contains("Progressing lifts: Barbell Bench Press"))
        assertTrue(out.summary.contains("Plateaued/dipping lifts: Barbell Overhead Press (plateau"))
        assertTrue(out.summary.contains("Muscle sets (last 30 days): chest: 3, shoulders: 3."))
        assertTrue(out.summary.contains("Untrained primary muscles in window:"))
        assertTrue(out.summary.contains("calves"))
        assertTrue(out.summary.contains("Recent journal notes:"))
        assertTrue(out.summary.contains("Bench felt super crisp today!"))
        assertEquals("progress", out.sources.first().kind)
        assertTrue(out.sources.any { it.exerciseId == 1L })
        assertTrue(out.sources.any { it.exerciseId == 2L })
    }

    @Test
    fun `parses human and iso dates and attaches journal notes to recent workouts`() {
        val today = LocalDate(2026, 11, 17).toEpochDays()
        assertEquals(today, ChatToolbox.parseDateEpochDay("today", today))
        assertEquals(today - 1, ChatToolbox.parseDateEpochDay("yesterday", today))
        assertEquals(LocalDate(2026, 11, 10).toEpochDays(), ChatToolbox.parseDateEpochDay("2026-11-10", today))
        assertEquals(null, ChatToolbox.parseDateEpochDay("not-a-date", today))

        val zone = TimeZone.UTC
        val day = LocalDate(2026, 11, 10).toEpochDays()
        val startMillis = LocalDate(2026, 11, 10).atStartOfDayIn(zone).toEpochMilliseconds()
        val out = ChatToolbox.recentWorkoutsFrom(
            sessions = listOf(RecentCompletedSessionRow(10L, null, "Push", "", startMillis, startMillis + 45 * 60_000L)),
            details = emptyList(),
            zone = zone,
            unit = WeightUnit.METRIC,
            journalByDay = mapOf(day to "Slept 8 hours, felt great"),
        )
        assertTrue(out.summary.contains("Note: \"Slept 8 hours, felt great\""))
    }
}
