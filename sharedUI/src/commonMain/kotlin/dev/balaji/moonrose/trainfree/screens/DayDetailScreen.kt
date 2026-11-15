package dev.balaji.moonrose.trainfree.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.balaji.moonrose.trainfree.chat.ChatSeedContext
import dev.balaji.moonrose.trainfree.db.DayDetailState
import dev.balaji.moonrose.trainfree.db.DayDetailUiState
import dev.balaji.moonrose.trainfree.db.DaySession
import dev.balaji.moonrose.trainfree.db.DaySessionExercise
import dev.balaji.moonrose.trainfree.model.SetType
import dev.balaji.moonrose.trainfree.model.WeightUnit
import dev.balaji.moonrose.trainfree.navigation.DayDetailRoute
import dev.balaji.moonrose.trainfree.navigation.LocalBottomFabPadding
import dev.balaji.moonrose.trainfree.navigation.LocalUseNativeNavigation
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import dev.balaji.moonrose.trainfree.sharedui.resources.*

private val JournalAccentColor = Color(0xFF8E24AA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailScreen(
    route: DayDetailRoute,
    onGoBack: () -> Unit,
    onOpenChat: ((ChatSeedContext) -> Unit)? = null,
    onLogWorkout: () -> Unit = {},
    onEditSession: (Long) -> Unit = {},
    onEditJournal: () -> Unit = {},
) {
    val title = formatDayTitle(route.epochDay)
    if (LocalUseNativeNavigation.current) {
        DayDetailContent(
            route = route,
            onOpenChat = onOpenChat,
            onLogWorkout = onLogWorkout,
            onEditSession = onEditSession,
            onEditJournal = onEditJournal,
            modifier = Modifier.padding(WindowInsets.safeDrawing.asPaddingValues()),
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onGoBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
                        }
                    },
                )
            },
        ) { padding ->
            DayDetailContent(
                route = route,
                onOpenChat = onOpenChat,
                onLogWorkout = onLogWorkout,
                onEditSession = onEditSession,
                onEditJournal = onEditJournal,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun DayDetailContent(
    route: DayDetailRoute,
    onOpenChat: ((ChatSeedContext) -> Unit)?,
    onLogWorkout: () -> Unit,
    onEditSession: (Long) -> Unit,
    onEditJournal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(route.epochDay) { DayDetailState(route.epochDay) }
    val uiState by state.uiState.collectAsState(initial = DayDetailUiState())
    val scope = rememberCoroutineScope()
    var sessionPendingDelete by remember { mutableStateOf<DaySession?>(null) }

    if (uiState.loading) {
        CenteredMessage(PaddingValues(0.dp)) { CircularProgressIndicator() }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = 16.dp + LocalBottomFabPadding.current,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (onOpenChat != null && uiState.sessions.isNotEmpty()) {
            item(key = "ask_coach") {
                OutlinedButton(
                    onClick = {
                        onOpenChat(ChatSeedContext(kind = "day", epochDay = route.epochDay))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.chat_ask_workout))
                }
            }
        }

        if (uiState.sessions.isEmpty()) {
            item(key = "empty_workouts") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(Res.string.no_workouts_this_day),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!uiState.isFuture) {
                        OutlinedButton(
                            onClick = onLogWorkout,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(Res.string.log_completed_workout))
                        }
                    }
                }
            }
        } else {
            items(uiState.sessions, key = { it.id }) { session ->
                SessionSummaryCard(
                    session = session,
                    weightUnit = uiState.weightUnit,
                    onEdit = { onEditSession(session.id) },
                    onDelete = { sessionPendingDelete = session },
                )
            }
            if (!uiState.isFuture) {
                item(key = "add_another_workout") {
                    OutlinedButton(
                        onClick = onLogWorkout,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.log_completed_workout))
                    }
                }
            }
        }

        item(key = "journal_section") {
            JournalSummaryCard(
                content = uiState.journalContent,
                onEditJournal = onEditJournal,
            )
        }
    }

    sessionPendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { sessionPendingDelete = null },
            title = { Text(stringResource(Res.string.delete_workout_title)) },
            text = { Text(stringResource(Res.string.delete_workout_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            state.deleteSession(target.id)
                            sessionPendingDelete = null
                        }
                    },
                ) {
                    Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionPendingDelete = null }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun JournalSummaryCard(
    content: String?,
    onEditJournal: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(JournalAccentColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(Res.string.journal),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEditJournal) {
                    Text(
                        stringResource(
                            if (content != null) Res.string.edit_journal else Res.string.write_journal,
                        ),
                    )
                }
            }
            if (content != null) {
                MarkdownText(
                    markdown = content,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SessionSummaryCard(
    session: DaySession,
    weightUnit: WeightUnit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                session.routineName ?: stringResource(Res.string.workout),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(Res.string.edit_workout_title),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(Res.string.delete_workout),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        session.exercises.forEach { exercise ->
            ExerciseSummaryRow(exercise, weightUnit)
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ExerciseSummaryRow(exercise: DaySessionExercise, weightUnit: WeightUnit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(exercise.name, style = MaterialTheme.typography.bodyLarge)
        var workingSetNumber = 0
        exercise.sets.forEach { set ->
            if (set.setType == SetType.NORMAL) workingSetNumber++
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (set.setType == SetType.NORMAL) {
                        stringResource(Res.string.set_number, workingSetNumber)
                    } else {
                        setTypeLabel(set.setType)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val weight = set.actualWeight.ifBlank { set.targetWeight }
                val reps = set.actualReps.ifBlank { set.targetReps }
                val duration = set.actualDuration.ifBlank { set.targetDuration }
                val distance = set.actualDistance.ifBlank { set.targetDistance }
                val weightRes = if (weightUnit == WeightUnit.METRIC) Res.string.weight_kg_value else Res.string.weight_lb_value
                val distanceRes = if (weightUnit == WeightUnit.METRIC) Res.string.distance_km_value else Res.string.distance_mi_value
                Text(
                    listOfNotNull(
                        weight.takeIf { it.isNotBlank() }?.let { stringResource(weightRes, it) },
                        reps.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.reps_value, it) },
                        duration.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.duration_value, it) },
                        distance.takeIf { it.isNotBlank() }?.let { stringResource(distanceRes, it) },
                    ).joinToString(" · ").ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
