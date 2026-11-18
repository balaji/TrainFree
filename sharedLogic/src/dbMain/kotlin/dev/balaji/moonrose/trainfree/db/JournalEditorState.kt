package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.journal.MAX_JOURNAL_WORDS
import dev.balaji.moonrose.trainfree.journal.countJournalWords
import dev.balaji.moonrose.trainfree.model.WeightUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class JournalEditorUiState(
    val loading: Boolean = true,
    val epochDay: Long = 0L,
    val text: String = "",
    val wordCount: Int = 0,
    val maxWords: Int = MAX_JOURNAL_WORDS,
    val hasExistingEntry: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val deleted: Boolean = false,
    val workouts: List<DaySession> = emptyList(),
    val weightUnit: WeightUnit = WeightUnit.METRIC,
) {
    val isOverWordLimit: Boolean get() = wordCount > maxWords
    val canSave: Boolean get() = !loading && !saving && hasUnsavedChanges && !isOverWordLimit
}

internal data class JournalDayWorkouts(
    val sessions: List<DaySession> = emptyList(),
    val weightUnit: WeightUnit = WeightUnit.METRIC,
)

internal interface JournalStore {
    suspend fun getForDay(epochDay: Long): String?
    suspend fun getWorkoutsForDay(epochDay: Long): JournalDayWorkouts = JournalDayWorkouts()
    suspend fun save(epochDay: Long, content: String)
    suspend fun delete(epochDay: Long)
}

private object RoomJournalStore : JournalStore {
    override suspend fun getForDay(epochDay: Long): String? =
        JournalRepository.getForDay(epochDay)

    override suspend fun getWorkoutsForDay(epochDay: Long): JournalDayWorkouts {
        val weightUnit = WeightUnit.fromValue(AppDb.db.userSettingDao().find(SETTING_WEIGHT_UNIT)?.value)
        return JournalDayWorkouts(
            sessions = loadDaySessions(epochDay, weightUnit),
            weightUnit = weightUnit,
        )
    }

    override suspend fun save(epochDay: Long, content: String) =
        JournalRepository.save(epochDay, content)

    override suspend fun delete(epochDay: Long) =
        JournalRepository.delete(epochDay)
}

/**
 * Shared in-memory state holder for writing/editing one day's markdown
 * journal entry. Changes stay in memory until [save] is called; blank entries
 * are never stored and delete any existing entry on save.
 */
class JournalEditorState internal constructor(
    private val epochDay: Long,
    private val store: JournalStore,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    constructor(epochDay: Long) : this(epochDay, RoomJournalStore)

    private val scope = CoroutineScope(dispatcher)
    private var initialText: String = ""
    private var hasExistingEntry: Boolean = false

    private val _uiState = MutableStateFlow(JournalEditorUiState(epochDay = epochDay))
    val uiState: StateFlow<JournalEditorUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            val loaded = store.getForDay(epochDay)
            val dayWorkouts = store.getWorkoutsForDay(epochDay)
            initialText = loaded.orEmpty()
            hasExistingEntry = loaded != null && loaded.isNotBlank()
            _uiState.value = JournalEditorUiState(
                loading = false,
                epochDay = epochDay,
                text = initialText,
                wordCount = countJournalWords(initialText),
                hasExistingEntry = hasExistingEntry,
                hasUnsavedChanges = false,
                workouts = dayWorkouts.sessions,
                weightUnit = dayWorkouts.weightUnit,
            )
        }
    }

    fun updateText(text: String) {
        val current = _uiState.value
        if (current.loading || current.saving) return
        val dirty = if (hasExistingEntry) text != initialText else text.isNotBlank()
        _uiState.value = current.copy(
            text = text,
            wordCount = countJournalWords(text),
            hasUnsavedChanges = dirty,
        )
    }

    fun save(onDone: () -> Unit = {}) {
        val current = _uiState.value
        if (!current.canSave) return
        _uiState.value = current.copy(saving = true)
        scope.launch {
            store.save(epochDay, current.text)
            val savedNonBlank = current.text.isNotBlank()
            initialText = if (savedNonBlank) current.text else ""
            hasExistingEntry = savedNonBlank
            _uiState.value = _uiState.value.copy(
                saving = false,
                saved = true,
                hasExistingEntry = savedNonBlank,
                hasUnsavedChanges = false,
            )
            onDone()
        }
    }

    fun delete(onDone: () -> Unit = {}) {
        val current = _uiState.value
        if (current.loading || current.saving || !hasExistingEntry) return
        _uiState.value = current.copy(saving = true)
        scope.launch {
            store.delete(epochDay)
            initialText = ""
            hasExistingEntry = false
            _uiState.value = _uiState.value.copy(
                saving = false,
                deleted = true,
                hasExistingEntry = false,
                hasUnsavedChanges = false,
            )
            onDone()
        }
    }
}
