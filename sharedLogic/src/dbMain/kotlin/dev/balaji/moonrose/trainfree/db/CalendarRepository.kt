package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.calendar.CalendarModel
import dev.balaji.moonrose.trainfree.calendar.WeekMath
import dev.balaji.moonrose.trainfree.journal.journalPreviewSnippet
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * One calendar day with at least one completed workout or journal entry, for
 * the journal list view. Primitive-typed for the SKIE bridge.
 */
data class JournalDaySummary(
    val epochDay: Long,
    /** Workout titles in session order. */
    val routineNames: List<String>,
    val hasJournal: Boolean,
    val journalPreview: String,
)

object CalendarRepository {
    /**
     * Weekly calendar of completed workout sessions and journal entries:
     * continuous weeks (start day per user_settings week-start) from
     * min(earliest activity, one year ago) through four weeks from now.
     * Workout days are keyed on performed_at in the device timezone at emission
     * time; journal days are keyed on epoch_day directly.
     */
    @OptIn(ExperimentalTime::class)
    val calendar: Flow<CalendarModel> =
        combine(
            AppDb.db.workoutSessionDao().observeCompletedPerformedAt(PROFILE_ID),
            AppDb.db.journalEntryDao().observeAllEpochDays(PROFILE_ID),
            UserSettingsRepository.settings,
        ) { performedAt, journalEpochDays, settings ->
            val weekStart = settings.weekStart
            val zone = TimeZone.currentSystemDefault()
            val completedDays = performedAt.mapTo(mutableSetOf()) { millis ->
                Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date.toEpochDays()
            }
            val journalDays = journalEpochDays.toSet()
            val today = WeekMath.todayEpochDay()
            val currentWeekStart = WeekMath.weekStartOnOrBefore(today, weekStart)
            val earliestActivity = minOf(
                completedDays.minOrNull() ?: today,
                journalDays.minOrNull() ?: today,
                today - 364,
            )
            val start = WeekMath.weekStartOnOrBefore(earliestActivity, weekStart)
            val end = currentWeekStart + 7 * 4 + 6
            val weeks = WeekMath.buildWeeks(
                startEpochDay = start,
                endEpochDay = end,
                completedDays = completedDays,
                todayEpochDay = today,
                weekStart = weekStart,
                journalDays = journalDays,
            )
            CalendarModel(
                weeks = weeks,
                currentWeekIndex = weeks.indexOfFirst { it.weekStartEpochDay == currentWeekStart },
            )
        }

    /**
     * Days with completed sessions and/or journal entries, most recent first.
     */
    @OptIn(ExperimentalTime::class)
    val journalDays: Flow<List<JournalDaySummary>> =
        combine(
            AppDb.db.workoutSessionDao().observeCompletedWithRoutineName(PROFILE_ID),
            AppDb.db.journalEntryDao().observeAll(PROFILE_ID),
        ) { sessionRows, journalRows ->
            val zone = TimeZone.currentSystemDefault()
            val workoutsByDay = sessionRows
                .groupBy { row ->
                    Instant.fromEpochMilliseconds(row.performedAt).toLocalDateTime(zone).date.toEpochDays()
                }
                .mapValues { (_, sessions) ->
                    sessions.asReversed().map {
                        resolveWorkoutTitle(it.title, it.routineName, it.performedAt)
                    }
                }
            val journalsByDay = journalRows.associateBy { it.epochDay }
            val allDays = (workoutsByDay.keys + journalsByDay.keys).sortedDescending()
            allDays.map { epochDay ->
                val journalContent = journalsByDay[epochDay]?.content.orEmpty()
                JournalDaySummary(
                    epochDay = epochDay,
                    routineNames = workoutsByDay[epochDay].orEmpty(),
                    hasJournal = journalContent.isNotBlank(),
                    journalPreview = journalPreviewSnippet(journalContent),
                )
            }
        }
}
