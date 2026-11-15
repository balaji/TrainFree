package dev.balaji.moonrose.trainfree.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.balaji.moonrose.trainfree.calendar.CalendarDay
import dev.balaji.moonrose.trainfree.calendar.CalendarWeek
import dev.balaji.moonrose.trainfree.calendar.WeekStart
import dev.balaji.moonrose.trainfree.db.CalendarRepository
import dev.balaji.moonrose.trainfree.db.JournalDaySummary
import dev.balaji.moonrose.trainfree.db.UserSettingsRepository
import dev.balaji.moonrose.trainfree.navigation.LocalBottomFabPadding
import dev.balaji.moonrose.trainfree.navigation.LocalUseNativeNavigation
import org.jetbrains.compose.resources.stringResource
import dev.balaji.moonrose.trainfree.sharedui.resources.*

private val WeekNumberWidth = 32.dp
private val JournalDotColor = Color(0xFF8E24AA)

@Composable
private fun weekdayLabels(weekStartsMonday: Boolean): List<String> {
    val sundayFirst = listOf(
        Res.string.weekday_sun_short,
        Res.string.weekday_mon_short,
        Res.string.weekday_tue_short,
        Res.string.weekday_wed_short,
        Res.string.weekday_thu_short,
        Res.string.weekday_fri_short,
        Res.string.weekday_sat_short,
    ).map { stringResource(it) }
    return if (weekStartsMonday) sundayFirst.drop(1) + sundayFirst.first() else sundayFirst
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onDayClick: (epochDay: Long) -> Unit = {},
    onLogWorkout: (epochDay: Long) -> Unit = {},
    onEditJournal: (epochDay: Long) -> Unit = {},
) {
    // Local state, not a route: toggling between the calendar grid and the
    // journal list must not grow the navigation back stack.
    var showSessionList by rememberSaveable { mutableStateOf(false) }
    if (LocalUseNativeNavigation.current) {
        // The native (SwiftUI) shell renders its own calendar screen with the
        // toggle in its navigation bar, so no top-bar action is needed here.
        CalendarContent(
            modifier = Modifier.padding(WindowInsets.safeDrawing.asPaddingValues()),
            onDayClick = onDayClick,
            onLogWorkout = onLogWorkout,
            onEditJournal = onEditJournal,
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(Res.string.tab_journal)) },
                    actions = {
                        IconButton(onClick = { showSessionList = !showSessionList }) {
                            Icon(
                                if (showSessionList) Icons.Filled.CalendarMonth else Icons.AutoMirrored.Filled.List,
                                contentDescription = if (showSessionList) stringResource(Res.string.show_calendar) else stringResource(Res.string.show_workout_history),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            if (showSessionList) {
                JournalDayList(Modifier.padding(padding), onDayClick)
            } else {
                CalendarContent(
                    modifier = Modifier.padding(padding),
                    onDayClick = onDayClick,
                    onLogWorkout = onLogWorkout,
                    onEditJournal = onEditJournal,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarContent(
    modifier: Modifier,
    onDayClick: (Long) -> Unit,
    onLogWorkout: (Long) -> Unit,
    onEditJournal: (Long) -> Unit,
) {
    val model by CalendarRepository.calendar.collectAsState(initial = null)
    val settings by UserSettingsRepository.settings.collectAsState(initial = null)
    // Early-return until the first emission so the list state below is created
    // with the current week index already known.
    val current = model ?: return
    val weekStartsMonday = settings?.weekStart == WeekStart.MONDAY
    var selectedDay by remember { mutableStateOf<CalendarDay?>(null) }

    Column(modifier.fillMaxSize()) {
        WeekdayHeader(weekStartsMonday)
        HorizontalDivider()
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = (current.currentWeekIndex - 2).coerceAtLeast(0),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomFabPadding.current),
        ) {
            items(current.weeks, key = { it.weekStartEpochDay }) { week ->
                WeekRow(week, onSelectDay = { selectedDay = it })
            }
        }
    }

    selectedDay?.let { day ->
        DayActionSheet(
            day = day,
            onDismiss = { selectedDay = null },
            onLogWorkout = {
                selectedDay = null
                onLogWorkout(day.epochDay)
            },
            onEditExistingWorkout = {
                selectedDay = null
                onDayClick(day.epochDay)
            },
            onWriteJournal = {
                selectedDay = null
                onEditJournal(day.epochDay)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayActionSheet(
    day: CalendarDay,
    onDismiss: () -> Unit,
    onLogWorkout: () -> Unit,
    onEditExistingWorkout: () -> Unit,
    onWriteJournal: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = formatDayTitle(day.epochDay),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            if (!day.isFuture) {
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.log_workout_title)) },
                    leadingContent = {
                        Icon(
                            Icons.Filled.FitnessCenter,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier.clickable(onClick = onLogWorkout),
                )
                if (day.hasCompletedWorkout) {
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.edit_workout_title)) },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier = Modifier.clickable(onClick = onEditExistingWorkout),
                    )
                }
            }
            ListItem(
                headlineContent = { Text(stringResource(Res.string.journal)) },
                leadingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.Assignment,
                        contentDescription = null,
                        tint = JournalDotColor,
                    )
                },
                modifier = Modifier.clickable(onClick = onWriteJournal),
            )
        }
    }
}

@Composable
private fun WeekdayHeader(weekStartsMonday: Boolean) {
    val labels = weekdayLabels(weekStartsMonday)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        HeaderLabel(stringResource(Res.string.week_number_abbrev), Modifier.width(WeekNumberWidth))
        labels.forEach { label ->
            HeaderLabel(label, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeaderLabel(text: String, modifier: Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun WeekRow(week: CalendarWeek, onSelectDay: (CalendarDay) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            week.weekNumber.toString(),
            modifier = Modifier.width(WeekNumberWidth),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        week.days.forEach { day ->
            DayCell(day, onSelectDay, Modifier.weight(1f))
        }
    }
}

/**
 * Flat, most-recent-first list of days with a completed workout and/or journal
 * entry. Rows navigate to the day detail screen.
 */
@Composable
private fun JournalDayList(modifier: Modifier, onDayClick: (Long) -> Unit) {
    val days by CalendarRepository.journalDays.collectAsState(initial = null)
    val current = days ?: return

    if (current.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.no_journal_or_workouts), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = LocalBottomFabPadding.current),
    ) {
        items(current, key = { it.epochDay }) { day ->
            JournalDayRow(day, onDayClick)
            HorizontalDivider()
        }
    }
}

@Composable
private fun JournalDayRow(day: JournalDaySummary, onDayClick: (Long) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onDayClick(day.epochDay) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(formatDayTitle(day.epochDay), style = MaterialTheme.typography.bodyLarge)
        if (day.routineNames.isNotEmpty()) {
            Text(
                day.routineNames.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (day.hasJournal) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(JournalDotColor),
                )
                Text(
                    day.journalPreview.ifBlank { stringResource(Res.string.journal_entry_badge) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DayCell(day: CalendarDay, onSelectDay: (CalendarDay) -> Unit, modifier: Modifier) {
    val hasActivity = day.hasCompletedWorkout || day.hasJournal
    Column(
        modifier = modifier
            .background(
                if (day.isWeekend) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) else Color.Transparent,
            )
            .clickable { onSelectDay(day) }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Always rendered (blank off the 1st) to keep row heights uniform.
        Text(
            if (day.dayOfMonth == 1) monthYearAbbreviation(day.monthOfYear, day.year) else " ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false,
        )
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    if (day.isToday) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (hasActivity) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    day.isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                    day.isFuture -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!day.hasCompletedWorkout && !day.hasJournal) {
                Box(Modifier.size(5.dp))
            } else {
                if (day.hasCompletedWorkout) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                if (day.hasJournal) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(JournalDotColor),
                    )
                }
            }
        }
    }
}
