package dev.balaji.moonrose.trainfree.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.balaji.moonrose.trainfree.chat.ChatSeedContext
import dev.balaji.moonrose.trainfree.navigation.ExerciseDetailRoute
import dev.balaji.moonrose.trainfree.db.BarbellKeyboardState
import dev.balaji.moonrose.trainfree.db.DraftSessionExercise
import dev.balaji.moonrose.trainfree.db.DraftSessionSet
import dev.balaji.moonrose.trainfree.db.ProgramRepository
import dev.balaji.moonrose.trainfree.db.REST_TIMER_DURATION_SECONDS
import dev.balaji.moonrose.trainfree.db.UserSettingsRepository
import dev.balaji.moonrose.trainfree.db.WorkoutSessionFormState
import dev.balaji.moonrose.trainfree.db.WorkoutSessionRepository
import dev.balaji.moonrose.trainfree.db.WorkoutSessionUiState
import dev.balaji.moonrose.trainfree.model.SetField
import dev.balaji.moonrose.trainfree.model.SetType
import dev.balaji.moonrose.trainfree.model.UserSettings
import dev.balaji.moonrose.trainfree.model.WeightUnit
import dev.balaji.moonrose.trainfree.model.displaySetFields
import dev.balaji.moonrose.trainfree.navigation.LocalUseNativeNavigation
import dev.balaji.moonrose.trainfree.navigation.PlatformBackHandler
import dev.balaji.moonrose.trainfree.navigation.WorkoutEditRoute
import dev.balaji.moonrose.trainfree.navigation.WorkoutLogRoute
import dev.balaji.moonrose.trainfree.navigation.WorkoutSessionRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import dev.balaji.moonrose.trainfree.sharedui.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutSessionScreen(
    route: WorkoutSessionRoute,
    onGoBack: () -> Unit,
    onOpenChat: ((ChatSeedContext) -> Unit)? = null,
) {
    // "Resume or start" happens once per navigation entry; if the active
    // session already matches this route, use its id immediately so returning
    // to an in-progress workout doesn't flash a loading spinner.
    var sessionId by remember(route.routineId) {
        mutableStateOf(
            WorkoutSessionRepository.activeSession.value
                ?.takeIf { it.routineId == route.routineId }
                ?.sessionId,
        )
    }
    LaunchedEffect(route.routineId) {
        if (sessionId == null) {
            sessionId = WorkoutSessionRepository.resumeOrStart(routineId = route.routineId)
        }
    }

    val currentId = sessionId
    if (currentId == null) {
        WorkoutSessionShell(title = route.title, onGoBack = onGoBack) { padding ->
            CenteredMessage(padding) { CircularProgressIndicator() }
        }
        return
    }

    val formState = remember(currentId) { WorkoutSessionRepository.formState(currentId) }
    val uiState by formState.uiState.collectAsState()

    if (uiState.finished || uiState.discarded) {
        LaunchedEffect(Unit) { onGoBack() }
        return
    }

    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showNothingToSave by remember { mutableStateOf(false) }
    val elapsedSeconds = rememberElapsedSeconds(uiState.startedAt)

    WorkoutSessionShell(
        title = uiState.sessionTitle?.takeIf { it.isNotBlank() } ?: route.title,
        elapsedSeconds = elapsedSeconds.takeIf { uiState.startedAt > 0 },
        onGoBack = onGoBack,
        onDiscard = { showDiscardConfirm = true },
        onFinish = {
            if (uiState.hasCompletedSets) formState.finish() else showNothingToSave = true
        },
        finishEnabled = !uiState.saving && !uiState.loading,
    ) { padding ->
        WorkoutSessionBody(
            formState = formState,
            uiState = uiState,
            onOpenChat = onOpenChat,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        )
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(Res.string.discard_workout_title)) },
            text = { Text(stringResource(Res.string.discard_workout_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    formState.discard()
                }) {
                    Text(stringResource(Res.string.discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    if (showNothingToSave) {
        AlertDialog(
            onDismissRequest = { showNothingToSave = false },
            title = { Text(stringResource(Res.string.nothing_to_save_title)) },
            text = { Text(stringResource(Res.string.nothing_to_save_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showNothingToSave = false
                    formState.discard()
                }) {
                    Text(stringResource(Res.string.yes), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNothingToSave = false }) {
                    Text(stringResource(Res.string.no))
                }
            },
        )
    }
}

@Composable
fun WorkoutLogScreen(
    route: WorkoutLogRoute,
    onGoBack: () -> Unit,
    onOpenChat: ((ChatSeedContext) -> Unit)? = null,
) {
    val formState = remember(route.epochDay) {
        WorkoutSessionRepository.newCompletedLogFormState(route.epochDay)
    }
    CompletedWorkoutSessionScreen(
        defaultTitle = stringResource(Res.string.log_workout_title),
        formState = formState,
        allowRoutineTemplate = true,
        onGoBack = onGoBack,
        onOpenChat = onOpenChat,
    )
}

@Composable
fun WorkoutEditScreen(
    route: WorkoutEditRoute,
    onGoBack: () -> Unit,
    onOpenChat: ((ChatSeedContext) -> Unit)? = null,
) {
    val formState = remember(route.sessionId) {
        WorkoutSessionRepository.editCompletedFormState(route.sessionId)
    }
    CompletedWorkoutSessionScreen(
        defaultTitle = stringResource(Res.string.edit_workout_title),
        formState = formState,
        allowRoutineTemplate = false,
        onGoBack = onGoBack,
        onOpenChat = onOpenChat,
    )
}

@Composable
private fun CompletedWorkoutSessionScreen(
    defaultTitle: String,
    formState: WorkoutSessionFormState,
    allowRoutineTemplate: Boolean,
    onGoBack: () -> Unit,
    onOpenChat: ((ChatSeedContext) -> Unit)? = null,
) {
    val uiState by formState.uiState.collectAsState()
    if (uiState.finished || uiState.discarded || uiState.deleted) {
        LaunchedEffect(Unit) { onGoBack() }
        return
    }

    var showDiscardChanges by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val attemptBack = {
        if (uiState.hasUnsavedChanges) showDiscardChanges = true else onGoBack()
    }
    PlatformBackHandler(enabled = !uiState.saving && !uiState.deleted && !uiState.finished) {
        attemptBack()
    }

    WorkoutSessionShell(
        title = uiState.sessionTitle?.takeIf { it.isNotBlank() } ?: defaultTitle,
        elapsedSeconds = null,
        onGoBack = attemptBack,
        onDelete = if (uiState.isEditingExisting) ({ showDeleteConfirm = true }) else null,
        onFinish = { formState.saveCompleted() },
        finishEnabled = uiState.canSaveCompleted,
        finishContentDescription = stringResource(Res.string.save),
    ) { padding ->
        WorkoutSessionBody(
            formState = formState,
            uiState = uiState,
            allowRoutineTemplate = allowRoutineTemplate,
            onOpenChat = onOpenChat,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        )
    }

    if (showDiscardChanges) {
        AlertDialog(
            onDismissRequest = { showDiscardChanges = false },
            title = { Text(stringResource(Res.string.discard_changes_title)) },
            text = { Text(stringResource(Res.string.discard_changes_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardChanges = false
                    onGoBack()
                }) {
                    Text(stringResource(Res.string.discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardChanges = false }) {
                    Text(stringResource(Res.string.keep_editing))
                }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(Res.string.delete_workout_title)) },
            text = { Text(stringResource(Res.string.delete_workout_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    formState.deleteCompletedSession()
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutSessionShell(
    title: String,
    elapsedSeconds: Long? = null,
    onGoBack: () -> Unit,
    onDiscard: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onFinish: (() -> Unit)? = null,
    finishEnabled: Boolean = false,
    finishContentDescription: String? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (LocalUseNativeNavigation.current) {
        content(WindowInsets.safeDrawing.asPaddingValues())
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (elapsedSeconds != null) {
                                Text(
                                    formatElapsed(elapsedSeconds),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onGoBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
                        }
                    },
                    actions = {
                        if (onDiscard != null) {
                            IconButton(onClick = onDiscard) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(Res.string.discard_workout),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (onDelete != null) {
                            IconButton(onClick = onDelete) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(Res.string.delete_workout),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (onFinish != null) {
                            IconButton(onClick = onFinish, enabled = finishEnabled) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = finishContentDescription ?: stringResource(Res.string.finish_workout),
                                    tint = if (finishEnabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    },
                                )
                            }
                        }
                    },
                )
            },
        ) { padding -> content(padding) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutSessionBody(
    formState: WorkoutSessionFormState,
    uiState: WorkoutSessionUiState,
    allowRoutineTemplate: Boolean = false,
    onOpenChat: ((ChatSeedContext) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }
    var showRoutinePicker by remember { mutableStateOf(false) }
    var selectedExerciseRoute by remember { mutableStateOf<ExerciseDetailRoute?>(null) }
    val pickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val routineSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val reorder = remember { SessionReorderState() }
    val rowHeight = 56.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val plateKeyboard = remember(formState) { BarbellKeyboardState(formState) }
    val plateState by plateKeyboard.uiState.collectAsState()
    val settings by UserSettingsRepository.settings.collectAsState(initial = UserSettings())
    var listHeightPx by remember { mutableIntStateOf(0) }
    val restTimer = uiState.restTimer.takeIf { uiState.isLiveSession }
    var restRemainingSeconds by remember(restTimer) {
        mutableLongStateOf(restTimer?.let { remainingSecondsUntil(it.endsAtMillis) } ?: 0L)
    }
    LaunchedEffect(restTimer) {
        if (restTimer == null) {
            restRemainingSeconds = 0L
            return@LaunchedEffect
        }
        while (true) {
            val remaining = remainingSecondsUntil(restTimer.endsAtMillis)
            restRemainingSeconds = remaining
            if (remaining <= 0L) break
            delay(250L)
        }
    }

    LaunchedEffect(plateState.visible) {
        if (plateState.visible) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            uiState.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            else -> Column(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { listHeightPx = it.height },
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    item(key = "workout_title") {
                        OutlinedTextField(
                            value = uiState.sessionTitle.orEmpty(),
                            onValueChange = { formState.updateTitle(it) },
                            label = { Text(stringResource(Res.string.workout_title)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .onFocusChanged { if (it.isFocused) plateKeyboard.close() },
                        )
                    }
                    itemsIndexed(uiState.exercises, key = { _, ex -> ex.id }) { exIndex, exercise ->
                        SessionExerciseCard(
                            exercise = exercise,
                            exIndex = exIndex,
                            weightUnit = settings.weightUnit,
                            isLiveSession = uiState.isLiveSession,
                            listHeightPx = listHeightPx,
                            // Which of this card's sets the plate keypad is
                            // editing, or null when it targets another card.
                            plateKeypadSetIndex = plateState.setIndex.takeIf {
                                plateState.visible && plateState.exerciseIndex == exIndex
                            },
                            restTimerSetIndex = restTimer?.setIndex?.takeIf {
                                restTimer.exerciseId == exercise.id && restRemainingSeconds > 0L
                            },
                            restRemainingSeconds = restRemainingSeconds,
                            restTotalSeconds = restTimer?.durationSeconds ?: REST_TIMER_DURATION_SECONDS,
                            restTimerDurationSeconds = settings.restTimerDurationSeconds,
                            onOpenPlateKeypad = { setIdx, currentValue ->
                                // Dismiss the system IME and drop text-field focus
                                // so the two keyboards never overlap and refocusing
                                // the same text field later still fires onFocusChanged.
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                plateKeyboard.open(exIndex, setIdx, exercise.exercise.id, currentValue)
                            },
                            // A focused text field brings up the system IME;
                            // never stack it under the plate panel.
                            onFieldFocused = { plateKeyboard.close() },
                            onOpenExercise = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                plateKeyboard.close()
                                selectedExerciseRoute = ExerciseDetailRoute(
                                    exerciseId = exercise.exercise.id,
                                    title = exercise.exercise.name,
                                )
                            },
                            onAddSet = { formState.addSet(exIndex) },
                            onRemoveSet = { setIdx -> formState.removeSet(exIndex, setIdx) },
                            onUpdateWeight = { setIdx, v -> formState.updateActualWeight(exIndex, setIdx, v) },
                            onUpdateReps = { setIdx, v -> formState.updateActualReps(exIndex, setIdx, v) },
                            onUpdateDuration = { setIdx, v ->
                                formState.updateActualDuration(exIndex, setIdx, v)
                            },
                            onUpdateDistance = { setIdx, v -> formState.updateActualDistance(exIndex, setIdx, v) },
                            onSetTypeChange = { setIdx, setType -> formState.setSetType(exIndex, setIdx, setType) },
                            onToggleCompleted = { setIdx ->
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                plateKeyboard.close()
                                formState.toggleCompleted(exIndex, setIdx)
                            },
                            onDelete = { formState.removeExercise(exIndex) },
                            onReorderStart = { startRootY ->
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                plateKeyboard.close()
                                reorder.activeIndex = exIndex
                                reorder.lastActiveIndex = exIndex
                                reorder.fingerRootY = startRootY
                                reorder.startFingerY = startRootY
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onReorderDrag = { dy -> reorder.fingerRootY += dy },
                            onReorderEnd = {
                                val from = reorder.activeIndex
                                val to = reorder.targetIndex(rowHeightPx, uiState.exercises.size)
                                reorder.activeIndex = null
                                if (from != null && to != null && from != to) {
                                    formState.moveExercise(from, to)
                                }
                            },
                            onReorderCancel = { reorder.activeIndex = null },
                        )
                    }

                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                onClick = {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    plateKeyboard.close()
                                    showPicker = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(Res.string.add_exercise_caps))
                            }
                            if (allowRoutineTemplate) {
                                OutlinedButton(
                                    onClick = {
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        plateKeyboard.close()
                                        showRoutinePicker = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(Res.string.copy_from_routine))
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = plateState.visible) {
                    BarbellPlateKeypad(state = plateState, keyboard = plateKeyboard)
                }
            }
        }

        AnimatedVisibility(
            visible = reorder.activeIndex != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            SessionReorderOverlay(
                exercises = uiState.exercises,
                reorder = reorder,
                rowHeight = rowHeight,
            )
        }
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = pickerSheetState,
        ) {
            ExercisesScreen(
                onNavigate = {},
                onExerciseSelected = { exercise ->
                    formState.addExercise(exercise)
                    scope.launch { pickerSheetState.hide() }.invokeOnCompletion { showPicker = false }
                },
            )
        }
    }

    if (showRoutinePicker) {
        ModalBottomSheet(
            onDismissRequest = { showRoutinePicker = false },
            sheetState = routineSheetState,
        ) {
            RoutineTemplatePickerSheet(
                onRoutineSelected = { routineId ->
                    formState.loadRoutine(routineId)
                    scope.launch { routineSheetState.hide() }.invokeOnCompletion { showRoutinePicker = false }
                },
            )
        }
    }

    selectedExerciseRoute?.let { detailRoute ->
        Dialog(
            onDismissRequest = { selectedExerciseRoute = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                ExerciseDetailScreen(
                    route = detailRoute,
                    onGoBack = { selectedExerciseRoute = null },
                    onOpenChat = onOpenChat,
                )
            }
        }
    }
}

@Composable
private fun RoutineTemplatePickerSheet(
    onRoutineSelected: (Long) -> Unit,
) {
    val programs by ProgramRepository.programs.collectAsState(initial = emptyList())
    val routines by ProgramRepository.routines.collectAsState(initial = emptyList())
    val routinesByProgram = remember(routines) { routines.groupBy { it.programId } }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = stringResource(Res.string.copy_from_routine),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        HorizontalDivider()
        if (routines.isEmpty()) {
            Text(
                text = stringResource(Res.string.no_programs_yet),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                programs.forEach { program ->
                    val programRoutines = routinesByProgram[program.id].orEmpty()
                    if (programRoutines.isNotEmpty()) {
                        item(key = "program_${program.id}") {
                            Text(
                                text = program.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                        programRoutines.forEach { routine ->
                            item(key = "routine_${routine.id}") {
                                ListItem(
                                    headlineContent = { Text(routine.name) },
                                    modifier = Modifier.clickable { onRoutineSelected(routine.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Mirrors ReorderState in RoutineDetailScreen — duplicated to keep this change scoped. */
private class SessionReorderState {
    var activeIndex by mutableStateOf<Int?>(null)
    var fingerRootY by mutableFloatStateOf(0f)
    var startFingerY by mutableFloatStateOf(0f)
    var naturalListTop by mutableFloatStateOf(0f)
    var overlayBottom by mutableFloatStateOf(0f)
    var lastActiveIndex = 0

    fun anchoredListTop(rowHeightPx: Float, count: Int): Float {
        val active = activeIndex ?: lastActiveIndex
        val maxTop = (overlayBottom - count * rowHeightPx).coerceAtLeast(naturalListTop)
        return (startFingerY - (active + 0.5f) * rowHeightPx).coerceIn(naturalListTop, maxTop)
    }

    fun targetIndex(rowHeightPx: Float, count: Int): Int? = activeIndex?.let {
        ((fingerRootY - anchoredListTop(rowHeightPx, count)) / rowHeightPx)
            .toInt()
            .coerceIn(0, count - 1)
    }
}

@Composable
private fun SessionReorderOverlay(
    exercises: List<DraftSessionExercise>,
    reorder: SessionReorderState,
    rowHeight: Dp,
) {
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    val activeIndex = reorder.activeIndex
    val targetIndex = activeIndex?.let { reorder.targetIndex(rowHeightPx, exercises.size) ?: it }
    val anchoredTop = reorder.anchoredListTop(rowHeightPx, exercises.size)
    val columnShift = anchoredTop - reorder.naturalListTop

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { reorder.overlayBottom = it.boundsInRoot().bottom },
    ) {
        Column {
            Text(
                stringResource(Res.string.reorder_exercises),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Box(
                modifier = Modifier.onGloballyPositioned {
                    reorder.naturalListTop = it.positionInRoot().y
                },
            ) {
                Column(
                    modifier = Modifier.offset { IntOffset(0, columnShift.roundToInt()) },
                ) {
                    exercises.forEachIndexed { index, exercise ->
                        val shift by animateFloatAsState(
                            when {
                                activeIndex == null || targetIndex == null || index == activeIndex -> 0f
                                activeIndex < index && index <= targetIndex -> -rowHeightPx
                                targetIndex <= index && index < activeIndex -> rowHeightPx
                                else -> 0f
                            },
                        )
                        SessionReorderRow(
                            name = exercise.exercise.name,
                            rowHeight = rowHeight,
                            modifier = Modifier.graphicsLayer {
                                translationY = shift
                                alpha = if (index == activeIndex) 0f else 1f
                            },
                        )
                    }
                }

                if (activeIndex != null) {
                    SessionReorderRow(
                        name = exercises[activeIndex].exercise.name,
                        rowHeight = rowHeight,
                        modifier = Modifier
                            .offset {
                                val maxY = (exercises.size - 1) * rowHeightPx
                                val yInList = (reorder.fingerRootY - anchoredTop - rowHeightPx / 2)
                                    .coerceIn(0f, maxY)
                                IntOffset(0, (yInList + columnShift).roundToInt())
                            }
                            .graphicsLayer { shadowElevation = 12f }
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionReorderRow(name: String, rowHeight: Dp, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Default.Menu,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionExerciseCard(
    exercise: DraftSessionExercise,
    exIndex: Int,
    weightUnit: WeightUnit,
    isLiveSession: Boolean,
    listHeightPx: Int,
    plateKeypadSetIndex: Int?,
    restTimerSetIndex: Int?,
    restRemainingSeconds: Long,
    restTotalSeconds: Long,
    restTimerDurationSeconds: Int,
    onOpenPlateKeypad: (Int, String) -> Unit,
    onFieldFocused: () -> Unit,
    onOpenExercise: () -> Unit,
    onAddSet: () -> Unit,
    onRemoveSet: (Int) -> Unit,
    onUpdateWeight: (Int, String) -> Unit,
    onUpdateReps: (Int, String) -> Unit,
    onUpdateDuration: (Int, String) -> Unit,
    onUpdateDistance: (Int, String) -> Unit,
    onSetTypeChange: (Int, SetType) -> Unit,
    onToggleCompleted: (Int) -> Unit,
    onDelete: () -> Unit,
    onReorderStart: (Float) -> Unit,
    onReorderDrag: (Float) -> Unit,
    onReorderEnd: () -> Unit,
    onReorderCancel: () -> Unit,
) {
    var headerRootY by remember { mutableFloatStateOf(0f) }
    var showMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { headerRootY = it.positionInRoot().y }
                .pointerInput(exIndex) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset -> onReorderStart(headerRootY + offset.y) },
                        onDrag = { change, delta ->
                            change.consume()
                            onReorderDrag(delta.y)
                        },
                        onDragEnd = { onReorderEnd() },
                        onDragCancel = { onReorderCancel() },
                    )
                }
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = exercise.exercise.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenExercise)
                    .padding(vertical = 8.dp),
            )
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(Res.string.exercise_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(Res.string.delete_exercise),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.column_set),
                modifier = Modifier.width(36.dp),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(Res.string.column_previous),
                modifier = Modifier.weight(1.2f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(
                    when (weightUnit) {
                        WeightUnit.METRIC -> Res.string.column_kg
                        WeightUnit.IMPERIAL -> Res.string.column_lb
                    },
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
            exercise.type.displaySetFields().forEach { field ->
                Text(
                    setFieldColumnLabel(field, weightUnit),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
            }
            if (isLiveSession) {
                Spacer(Modifier.width(40.dp))
            }
        }

        exercise.sets.forEachIndexed { setIdx, set ->
            SessionSetRow(
                set = set,
                fields = exercise.type.displaySetFields(),
                workingSetNumber = exercise.workingSetNumber(setIdx),
                previous = exercise.previousSet(setIdx),
                isLiveSession = isLiveSession,
                canComplete = exercise.isSetReadyToComplete(setIdx),
                usesPlateKeypad = exercise.usesPlateKeyboard,
                plateKeypadActive = plateKeypadSetIndex == setIdx,
                listHeightPx = listHeightPx,
                onOpenPlateKeypad = { onOpenPlateKeypad(setIdx, set.actualWeight) },
                onFieldFocused = onFieldFocused,
                onDelete = { onRemoveSet(setIdx) },
                onSetTypeChange = { onSetTypeChange(setIdx, it) },
                onUpdateWeight = { onUpdateWeight(setIdx, it) },
                onUpdateReps = { onUpdateReps(setIdx, it) },
                onUpdateDuration = { onUpdateDuration(setIdx, it) },
                onUpdateDistance = { onUpdateDistance(setIdx, it) },
                onToggleCompleted = { onToggleCompleted(setIdx) },
            )
            if (isLiveSession) {
                if (restTimerSetIndex == setIdx) {
                    RestTimerCountdownRow(
                        remainingSeconds = restRemainingSeconds,
                        totalSeconds = restTotalSeconds,
                    )
                } else {
                    RestTimerIndicatorRow(durationSeconds = restTimerDurationSeconds)
                }
            }
        }

        TextButton(
            onClick = onAddSet,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Text(
                stringResource(Res.string.add_set_caps),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SessionSetRow(
    set: DraftSessionSet,
    fields: List<SetField>,
    workingSetNumber: Int,
    previous: String?,
    isLiveSession: Boolean,
    canComplete: Boolean,
    usesPlateKeypad: Boolean,
    plateKeypadActive: Boolean,
    listHeightPx: Int,
    onOpenPlateKeypad: () -> Unit,
    onFieldFocused: () -> Unit,
    onDelete: () -> Unit,
    onSetTypeChange: (SetType) -> Unit,
    onUpdateWeight: (String) -> Unit,
    onUpdateReps: (String) -> Unit,
    onUpdateDuration: (String) -> Unit,
    onUpdateDistance: (String) -> Unit,
    onToggleCompleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    var focusedFields by remember { mutableStateOf(emptySet<String>()) }
    var focusTrigger by remember { mutableIntStateOf(0) }
    var rowHeightPx by remember { mutableIntStateOf(0) }
    val isEditing = plateKeypadActive || focusedFields.isNotEmpty()
    val pinnableContainer = LocalPinnableContainer.current
    DisposableEffect(isEditing) {
        val handle = if (isEditing) pinnableContainer?.pin() else null
        onDispose { handle?.release() }
    }
    val extraBottomPx = with(LocalDensity.current) { 12.dp.toPx() }
    suspend fun bringRowIntoView() {
        val targetRect = if (rowHeightPx > 0) {
            Rect(0f, 0f, 1f, rowHeightPx + extraBottomPx)
        } else {
            null
        }
        bringIntoViewRequester.bringIntoView(targetRect)
    }
    LaunchedEffect(isEditing, focusTrigger) {
        if (isEditing) {
            bringRowIntoView()
        }
    }
    LaunchedEffect(isEditing, listHeightPx) {
        if (isEditing && listHeightPx > 0) {
            delay(60L)
            bringRowIntoView()
        }
    }
    val actionWidth = 72.dp
    val actionWidthPx = with(LocalDensity.current) { actionWidth.toPx() }
    val anchors = remember(actionWidthPx) {
        DraggableAnchors {
            SessionSwipeAnchor.Closed at 0f
            SessionSwipeAnchor.Open at -actionWidthPx
        }
    }
    val swipeState = remember {
        AnchoredDraggableState(initialValue = SessionSwipeAnchor.Closed, anchors = anchors)
    }

    val haptics = LocalHapticFeedback.current
    val rowTint by animateColorAsState(
        when {
            !isLiveSession || !set.completed -> Color.Transparent
            set.setType == SetType.WARMUP -> CompletedWarmupPurple.copy(alpha = 0.20f)
            else -> CompletedGreen.copy(alpha = 0.20f)
        },
    )
    // Brief scale pop when the row transitions to completed, as a visual cue.
    val popScale = remember { Animatable(1f) }
    var completedSeen by remember { mutableStateOf(set.completed) }
    LaunchedEffect(set.completed) {
        if (set.completed && !completedSeen) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            popScale.animateTo(1.04f, tween(durationMillis = 110))
            popScale.animateTo(1f, tween(durationMillis = 160))
        }
        completedSeen = set.completed
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .onSizeChanged { rowHeightPx = it.height },
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.error),
            contentAlignment = Alignment.CenterEnd,
        ) {
            IconButton(
                onClick = {
                    scope.launch { swipeState.animateTo(SessionSwipeAnchor.Closed) }
                    onDelete()
                },
                modifier = Modifier.width(actionWidth).fillMaxHeight(),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(Res.string.remove_set),
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipeState.requireOffset().roundToInt(), 0) }
                .anchoredDraggable(swipeState, Orientation.Horizontal)
                .graphicsLayer {
                    scaleX = popScale.value
                    scaleY = popScale.value
                }
                .background(MaterialTheme.colorScheme.surface)
                .background(rowTint)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SetTypeCell(
                setType = set.setType,
                workingSetNumber = workingSetNumber,
                onSetTypeChange = onSetTypeChange,
                cellWidth = 36.dp,
            )
            Text(
                previous ?: "—",
                modifier = Modifier.weight(1.2f).padding(horizontal = 4.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (usesPlateKeypad) {
                // Not a text field: the plate keypad drives the value, so
                // there is no system IME to fight with.
                PlateWeightCell(
                    value = set.actualWeight,
                    target = set.targetWeight,
                    active = plateKeypadActive,
                    onClick = {
                        focusTrigger++
                        onOpenPlateKeypad()
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
            } else {
                CompactActualInput(
                    value = set.actualWeight,
                    target = set.targetWeight,
                    onValueChange = onUpdateWeight,
                    keyboardType = KeyboardType.Decimal,
                    onFocused = {
                        focusTrigger++
                        onFieldFocused()
                    },
                    onFocusStateChanged = { focused ->
                        focusedFields = if (focused) focusedFields + "weight" else focusedFields - "weight"
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
            }
            fields.forEach { field ->
                val fieldKey = field.name
                when (field) {
                    SetField.REPS -> CompactActualInput(
                        value = set.actualReps,
                        target = set.targetReps,
                        onValueChange = onUpdateReps,
                        keyboardType = KeyboardType.Number,
                        onFocused = {
                            focusTrigger++
                            onFieldFocused()
                        },
                        onFocusStateChanged = { focused ->
                            focusedFields = if (focused) focusedFields + fieldKey else focusedFields - fieldKey
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    )
                    SetField.DURATION -> CompactActualInput(
                        value = set.actualDuration,
                        target = set.targetDuration,
                        emptyPlaceholder = stringResource(Res.string.duration_placeholder),
                        onValueChange = onUpdateDuration,
                        keyboardType = KeyboardType.Number,
                        onFocused = {
                            focusTrigger++
                            onFieldFocused()
                        },
                        onFocusStateChanged = { focused ->
                            focusedFields = if (focused) focusedFields + fieldKey else focusedFields - fieldKey
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    )
                    SetField.DISTANCE -> CompactActualInput(
                        value = set.actualDistance,
                        target = set.targetDistance,
                        onValueChange = onUpdateDistance,
                        keyboardType = KeyboardType.Decimal,
                        onFocused = {
                            focusTrigger++
                            onFieldFocused()
                        },
                        onFocusStateChanged = { focused ->
                            focusedFields = if (focused) focusedFields + fieldKey else focusedFields - fieldKey
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    )
                }
            }
            if (isLiveSession) {
                Checkbox(
                    checked = set.completed,
                    onCheckedChange = { onToggleCompleted() },
                    // A set needs every field its exercise type requires before
                    // it can be completed; completed stays enabled so stale rows
                    // can still be unchecked.
                    enabled = set.completed || canComplete,
                    colors = CheckboxDefaults.colors(checkedColor = CompletedGreen),
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}

/** Green shared by the completed-row tint and the checked checkbox. */
private val CompletedGreen = Color(0xFF43A047)

/** Purple tint used for a completed warm-up set row background. */
private val CompletedWarmupPurple = Color(0xFF8E24AA)

/**
 * Returns a callback that starts [WorkoutSessionRoute] immediately when idle,
 * or prompts the user to dismiss or discard the active workout first.
 */
@Composable
fun rememberStartWorkoutHandler(
    onLaunch: (WorkoutSessionRoute) -> Unit,
): (WorkoutSessionRoute) -> Unit {
    val activeSession by WorkoutSessionRepository.activeSession.collectAsState()
    val latestOnLaunch by rememberUpdatedState(onLaunch)
    val scope = rememberCoroutineScope()
    var pendingRoute by remember { mutableStateOf<WorkoutSessionRoute?>(null) }
    var discarding by remember { mutableStateOf(false) }

    if (pendingRoute != null) {
        AlertDialog(
            onDismissRequest = { if (!discarding) pendingRoute = null },
            title = { Text(stringResource(Res.string.workout_in_progress_title)) },
            confirmButton = {
                TextButton(
                    enabled = !discarding,
                    onClick = {
                        val target = pendingRoute ?: return@TextButton
                        val session = activeSession ?: run {
                            pendingRoute = null
                            latestOnLaunch(target)
                            return@TextButton
                        }
                        discarding = true
                        scope.launch {
                            try {
                                WorkoutSessionRepository.discard(session.sessionId)
                            } finally {
                                discarding = false
                                pendingRoute = null
                            }
                            latestOnLaunch(target)
                        }
                    },
                ) {
                    Text(
                        stringResource(Res.string.discard_workout),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !discarding,
                    onClick = { pendingRoute = null },
                ) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    return { route ->
        if (activeSession != null) {
            pendingRoute = route
        } else {
            latestOnLaunch(route)
        }
    }
}

/**
 * Compact countdown bar shown immediately under the set that triggered the
 * rest timer. Automatically hides when the timer finishes or moves.
 */
@Composable
private fun RestTimerCountdownRow(remainingSeconds: Long, totalSeconds: Long) {
    val progress = (remainingSeconds.toFloat() / totalSeconds.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CompletedGreen.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.rest_timer_countdown, formatCountdown(remainingSeconds)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/**
 * Thin non-interactive divider row shown below a set to indicate the
 * configured rest timer duration (e.g. `---------- Rest timer: 2:00 ----------`).
 */
@Composable
internal fun RestTimerIndicatorRow(durationSeconds: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = stringResource(
                Res.string.rest_timer_indicator,
                formatCountdown(durationSeconds.toLong()),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * Weight cell for barbell exercises. Styled like [CompactActualInput] but a
 * plain clickable — tapping it opens the plate keypad instead of an IME.
 */
@Composable
private fun PlateWeightCell(
    value: String,
    target: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .border(
                width = 1.dp,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = RoundedCornerShape(4.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            value.ifEmpty { target.ifBlank { "0" } },
            style = MaterialTheme.typography.bodyMedium.copy(
                textAlign = TextAlign.Center,
                color = if (value.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            ),
        )
    }
}

@Composable
private fun CompactActualInput(
    value: String,
    target: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    // Shown when there is no target either — "0" for plain numbers, "h:mm"
    // for the duration box.
    emptyPlaceholder: String = "0",
    onFocused: (() -> Unit)? = null,
    onFocusStateChanged: ((Boolean) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    if (onFocused != null) {
        LaunchedEffect(interactionSource, onFocused) {
            interactionSource.interactions.collect { interaction ->
                if (interaction is PressInteraction.Release) {
                    onFocused()
                }
            }
        }
    }
    // Target shows as the placeholder so the user sees what they were aiming
    // for without it being submitted as their actual value.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = if (onFocused == null && onFocusStateChanged == null) {
            modifier
        } else {
            modifier.onFocusChanged {
                onFocusStateChanged?.invoke(it.isFocused)
                if (it.isFocused) onFocused?.invoke()
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        interactionSource = interactionSource,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (value.isEmpty()) {
                    Text(
                        target.ifBlank { emptyPlaceholder },
                        // Never wrap: narrow cells (the hh/mm pair) would
                        // otherwise stack a two-digit placeholder vertically.
                        maxLines = 1,
                        softWrap = false,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
                innerTextField()
            }
        },
    )
}

private enum class SessionSwipeAnchor { Closed, Open }
