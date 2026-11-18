package dev.balaji.moonrose.trainfree.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.balaji.moonrose.trainfree.db.DaySession
import dev.balaji.moonrose.trainfree.db.JournalEditorState
import dev.balaji.moonrose.trainfree.journal.MarkdownEditResult
import dev.balaji.moonrose.trainfree.journal.MarkdownFormatHelper
import dev.balaji.moonrose.trainfree.model.WeightUnit
import dev.balaji.moonrose.trainfree.navigation.JournalEditorRoute
import dev.balaji.moonrose.trainfree.navigation.PlatformBackHandler
import org.jetbrains.compose.resources.stringResource
import dev.balaji.moonrose.trainfree.sharedui.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalEditorScreen(
    route: JournalEditorRoute,
    onGoBack: () -> Unit,
) {
    val editorState = remember(route.epochDay) { JournalEditorState(route.epochDay) }
    val uiState by editorState.uiState.collectAsState()

    if (uiState.saved || uiState.deleted) {
        LaunchedEffect(Unit) { onGoBack() }
        return
    }

    var fieldValue by remember(route.epochDay) { mutableStateOf(TextFieldValue(uiState.text)) }
    LaunchedEffect(uiState.loading, uiState.text) {
        if (!uiState.loading && fieldValue.text != uiState.text) {
            fieldValue = TextFieldValue(uiState.text, TextRange(uiState.text.length))
        }
    }

    var previewMode by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var selectedWorkout by remember { mutableStateOf<DaySession?>(null) }

    val attemptBack = {
        if (uiState.hasUnsavedChanges) showDiscardConfirm = true else onGoBack()
    }
    PlatformBackHandler(enabled = !uiState.saving && !uiState.saved && !uiState.deleted) {
        attemptBack()
    }

    fun applyFormat(transform: (String, Int, Int) -> MarkdownEditResult) {
        val result = transform(fieldValue.text, fieldValue.selection.start, fieldValue.selection.end)
        fieldValue = TextFieldValue(
            text = result.text,
            selection = TextRange(result.selectionStart, result.selectionEnd),
        )
        if (result.text != uiState.text) {
            editorState.updateText(result.text)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(formatDayTitle(route.epochDay)) },
                navigationIcon = {
                    IconButton(onClick = attemptBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                },
                actions = {
                    if (uiState.hasExistingEntry) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(Res.string.delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    TextButton(
                        onClick = { editorState.save() },
                        enabled = uiState.canSave,
                    ) {
                        Text(stringResource(Res.string.save))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        if (uiState.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = !previewMode,
                        onClick = { previewMode = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text(stringResource(Res.string.journal_tab_edit))
                    }
                    SegmentedButton(
                        selected = previewMode,
                        onClick = { previewMode = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text(stringResource(Res.string.journal_tab_preview))
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(Res.string.journal_word_count, uiState.wordCount, uiState.maxWords),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (uiState.isOverWordLimit) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (uiState.workouts.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    uiState.workouts.forEach { workout ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(role = Role.Button) { selectedWorkout = workout }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FitnessCenter,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = workout.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            if (!previewMode) {
                Surface(tonalElevation = 1.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val boldLabel = stringResource(Res.string.journal_format_bold)
                        val italicLabel = stringResource(Res.string.journal_format_italic)
                        val headingLabel = stringResource(Res.string.journal_format_heading)
                        val listLabel = stringResource(Res.string.journal_format_list)
                        TextButton(
                            onClick = { applyFormat(MarkdownFormatHelper::toggleBold) },
                            modifier = Modifier.semantics { contentDescription = boldLabel },
                        ) {
                            Text("B", fontWeight = FontWeight.Bold)
                        }
                        TextButton(
                            onClick = { applyFormat(MarkdownFormatHelper::toggleItalic) },
                            modifier = Modifier.semantics { contentDescription = italicLabel },
                        ) {
                            Text("I", fontStyle = FontStyle.Italic)
                        }
                        TextButton(
                            onClick = { applyFormat(MarkdownFormatHelper::toggleHeading) },
                            modifier = Modifier.semantics { contentDescription = headingLabel },
                        ) {
                            Text("H2", fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(
                            onClick = { applyFormat(MarkdownFormatHelper::toggleBulletList) },
                            modifier = Modifier.semantics { contentDescription = listLabel },
                        ) {
                            Text("•")
                        }
                    }
                }
                HorizontalDivider()
                TextField(
                    value = fieldValue,
                    onValueChange = { next ->
                        fieldValue = next
                        if (next.text != uiState.text) {
                            editorState.updateText(next.text)
                        }
                    },
                    placeholder = { Text(stringResource(Res.string.journal_editor_placeholder)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        errorContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                HorizontalDivider()
                if (uiState.text.isBlank()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(Res.string.journal_preview_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        MarkdownText(
                            markdown = uiState.text,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(Res.string.discard_changes_title)) },
            text = { Text(stringResource(Res.string.discard_changes_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    onGoBack()
                }) {
                    Text(stringResource(Res.string.discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(Res.string.keep_editing))
                }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(Res.string.delete_journal_title)) },
            text = { Text(stringResource(Res.string.delete_journal_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    editorState.delete()
                }) {
                    Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    selectedWorkout?.let { workout ->
        WorkoutSetDetailsDialog(
            session = workout,
            weightUnit = uiState.weightUnit,
            onDismiss = { selectedWorkout = null },
        )
    }
}

@Composable
private fun WorkoutSetDetailsDialog(
    session: DaySession,
    weightUnit: WeightUnit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(session.title) },
        text = {
            if (session.exercises.isEmpty()) {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    session.exercises.forEachIndexed { index, exercise ->
                        if (index > 0) {
                            HorizontalDivider()
                        }
                        ExerciseSummaryRow(exercise, weightUnit)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.close))
            }
        },
    )
}
