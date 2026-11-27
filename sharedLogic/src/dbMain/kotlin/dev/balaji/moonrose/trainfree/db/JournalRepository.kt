package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.journal.MAX_JOURNAL_WORDS
import dev.balaji.moonrose.trainfree.journal.countJournalWords
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object JournalRepository {
    fun observeForDay(epochDay: Long): Flow<String?> =
        AppDb.db.journalEntryDao().observeForDay(PROFILE_ID, epochDay).map { it?.content }

    suspend fun getForDay(epochDay: Long): String? =
        AppDb.db.journalEntryDao().findForDay(PROFILE_ID, epochDay)?.content

    suspend fun save(epochDay: Long, content: String) {
        AppDb.db.saveJournal(epochDay, content, nowMillis())
        GoogleDriveBackupManager.onWorkoutOrJournalChanged()
    }

    suspend fun delete(epochDay: Long) {
        AppDb.db.journalEntryDao().deleteForDay(PROFILE_ID, epochDay)
        GoogleDriveBackupManager.onWorkoutOrJournalChanged()
    }
}

/**
 * Persists [content] for [epochDay], or deletes any existing row when
 * [content] is blank so empty journal records are never stored.
 */
internal suspend fun AppDatabase.saveJournal(
    epochDay: Long,
    content: String,
    now: Long,
) {
    if (content.isBlank()) {
        journalEntryDao().deleteForDay(PROFILE_ID, epochDay)
        return
    }
    require(countJournalWords(content) <= MAX_JOURNAL_WORDS) {
        "Journal entry exceeds $MAX_JOURNAL_WORDS words"
    }
    val existing = journalEntryDao().findForDay(PROFILE_ID, epochDay)
    if (existing != null) {
        journalEntryDao().updateContent(existing.id, content, now)
    } else {
        journalEntryDao().insert(
            JournalEntryEntity(
                profileId = PROFILE_ID,
                epochDay = epochDay,
                content = content,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}
