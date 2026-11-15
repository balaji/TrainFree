package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.journal.MAX_JOURNAL_WORDS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

class JournalEditorStateTest {
    private class FakeJournalStore(initial: Map<Long, String> = emptyMap()) : JournalStore {
        val entries = initial.toMutableMap()
        var saveCalls = 0
        var deleteCalls = 0

        override suspend fun getForDay(epochDay: Long): String? = entries[epochDay]

        override suspend fun save(epochDay: Long, content: String) {
            saveCalls++
            if (content.isBlank()) {
                entries.remove(epochDay)
            } else {
                entries[epochDay] = content
            }
        }

        override suspend fun delete(epochDay: Long) {
            deleteCalls++
            entries.remove(epochDay)
        }
    }

    @Test
    fun emptyDayTracksDirtyAndWordLimit() {
        val store = FakeJournalStore()
        val state = JournalEditorState(20_000L, store, Dispatchers.Unconfined)

        val loaded = state.uiState.value
        assertFalse(loaded.loading)
        assertFalse(loaded.hasExistingEntry)
        assertFalse(loaded.hasUnsavedChanges)
        assertFalse(loaded.canSave)

        state.updateText("   \n ")
        assertFalse(state.uiState.value.hasUnsavedChanges)
        assertFalse(state.uiState.value.canSave)

        val tooLong = List(MAX_JOURNAL_WORDS + 1) { "rep" }.joinToString(" ")
        state.updateText(tooLong)
        assertEquals(MAX_JOURNAL_WORDS + 1, state.uiState.value.wordCount)
        assertTrue(state.uiState.value.isOverWordLimit)
        assertFalse(state.uiState.value.canSave)

        state.updateText("Solid deadlift session")
        assertTrue(state.uiState.value.hasUnsavedChanges)
        assertTrue(state.uiState.value.canSave)

        var doneCalled = false
        state.save { doneCalled = true }
        assertTrue(doneCalled)
        assertTrue(state.uiState.value.saved)
        assertTrue(state.uiState.value.hasExistingEntry)
        assertFalse(state.uiState.value.hasUnsavedChanges)
        assertEquals("Solid deadlift session", store.entries[20_000L])
    }

    @Test
    fun existingEntryBlankSaveDeletesRowAndExplicitDeleteWorks() {
        val store = FakeJournalStore(mapOf(20_001L to "Initial note"))
        val state = JournalEditorState(20_001L, store, Dispatchers.Unconfined)

        assertTrue(state.uiState.value.hasExistingEntry)
        assertFalse(state.uiState.value.hasUnsavedChanges)

        state.updateText("   ")
        assertTrue(state.uiState.value.hasUnsavedChanges)
        assertTrue(state.uiState.value.canSave)
        state.save()
        assertNull(store.entries[20_001L])
        assertFalse(state.uiState.value.hasExistingEntry)

        val store2 = FakeJournalStore(mapOf(20_002L to "Second note"))
        val state2 = JournalEditorState(20_002L, store2, Dispatchers.Unconfined)
        var deletedCallback = false
        state2.delete { deletedCallback = true }
        assertTrue(deletedCallback)
        assertTrue(state2.uiState.value.deleted)
        assertNull(store2.entries[20_002L])
        assertEquals(1, store2.deleteCalls)
    }
}
