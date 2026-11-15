# Journal Feature Implementation Plan

Status: **Implementing**

## Overview

Transform the "History" tab into **Journal**, combining workout logging/editing with daily markdown journaling so users can reflect on training, log missed workouts retroactively, and edit logged sessions on both Android and iOS.

## Storage Decision: Room Database vs. Separate Files

**Decision: Store daily journal entries inside the local Room SQLite database (`journal_entries` table).**

Why DB storage is the right fit here:
1. **Small bounded payload**: With a 750-word limit (~4–6 KB of UTF-8 text per entry), even 10 years of daily entries is ~15 MB—negligible for SQLite.
2. **Reactive UI updates**: Room `Flow` queries automatically notify the calendar grid, journal list, and day detail views on save or delete across both platforms with zero file-watcher plumbing.
3. **Sync parity**: Matches the local-first architecture in `AGENTS.md` (snake_case columns, `profile_id` scoping, `created_at` / `updated_at` epoch millis) for future 1:1 sync with `moonrose`.
4. **Atomic lifecycle**: No orphaned files when entries are cleared or deleted; clearing app data or deleting the database handles everything in one place.
5. **Future photo support**: Deferred optional photos (one per day) can still live on disk as compressed image files referenced by a file path column in a future migration, keeping binary blobs out of SQLite.

Empty journal rule: saving blank/whitespace-only content deletes any existing `journal_entries` row for that day and never inserts an empty row.

## Database Schema (v18 → v19)

Add `JournalEntryEntity` in `sharedLogic/src/dbMain/.../db/Entities.kt`:
- Table: `journal_entries`
- Columns:
  - `id: Long` (`@PrimaryKey(autoGenerate = true)`)
  - `profile_id: Long` (FK → `profile.id`, `ON DELETE CASCADE`)
  - `epoch_day: Long` (`LocalDate.toEpochDays()`, timezone-independent calendar day key)
  - `content: String` (raw markdown text)
  - `created_at: Long`
  - `updated_at: Long`
- Unique index on `(profile_id, epoch_day)` so at most one journal exists per day per profile.

Bump `AppDatabase` version `18 → 19`, add `MIGRATION_18_19`, export `19.json`, and add `Migration18To19Test` (for both `androidHostTest` and `iosTest`).

## Shared Logic (`sharedLogic`)

### 1. `commonMain`
- **`CalendarDay` & `WeekMath`** (`WeekMath.kt`):
  - Add `year: Int` and `hasJournal: Boolean` to `CalendarDay`.
  - Update `WeekMath.buildWeeks(...)` to accept `journalDays: Set<Long> = emptySet()`.
- **`JournalMath` & `MarkdownFormatHelper`** (`journal/JournalMath.kt`):
  - `MAX_JOURNAL_WORDS = 750`.
  - `countJournalWords(text: String): Int` (counts `\S+` tokens).
  - `journalPreviewSnippet(markdown: String, maxChars: Int = 120): String` (strips common markdown syntax into a clean single-line preview for the list view).
  - Pure selection-based markdown formatting helpers (`applyInlineFormat`, `applyLinePrefix`) for Bold (`**...**`), Italic (`*...*`), Heading (`## `), and Bullet List (`- `).

### 2. `dbMain`
- **`JournalEntryDao`** (`Daos.kt`) & **`JournalRepository`** (`JournalRepository.kt`):
  - Observe journal epoch days for the calendar grid.
  - Observe all journal entries for the list view.
  - Observe/fetch a single day's entry (`epochDay`).
  - `save(epochDay, content)`: if `content.isBlank()`, delete existing row; otherwise upsert (preserving `createdAt` when updating).
  - `delete(epochDay)`.
- **`JournalEditorState`** (`JournalEditorState.kt`):
  - `StateFlow<JournalEditorUiState>` holding `loading`, `text`, `initialText`, `wordCount`, `isOverLimit`, `hasExistingEntry`, `hasUnsavedChanges`, `canSave`, `saving`, `saved`, `deleted`.
  - Explicit `save()`, `delete()`, and `updateText(text)` actions (no autosave).
- **`CalendarRepository`** (`CalendarRepository.kt`):
  - `calendar`: combines completed workout timestamps, journal epoch days, and user settings to build `CalendarModel`.
  - `journalDays`: combines completed workout days and journal entries into a most-recent-first `Flow<List<JournalDaySummary>>` with routine names and journal preview snippets.
- **`DayDetailState`** (`DayDetailState.kt`):
  - Include `journalContent: String?` and `isFuture: Boolean` in `DayDetailUiState`.
  - Provide `deleteSession(sessionId: Long)` for removing a logged workout.
- **`WorkoutSessionFormState` & `WorkoutSessionRepository`** (`WorkoutSessionFormState.kt`, `WorkoutSessionRepository.kt`):
  - Support a `WorkoutEditorMode`:
    - `LIVE`: existing live workout behavior (elapsed timer, rest timer, checkboxes, autosave).
    - `LOG_COMPLETED(epochDay)`: starts in-memory with no DB row, no timers, no checkboxes; optional `loadRoutine(routineId)` helper; explicit `saveCompleted()` writes `WorkoutSessionEntity` (`performedAt` = `nowMillis()` if today, else local noon on `epochDay`; `completedAt = performedAt`) and all sets meeting `meetsRequirements(type)` with `completed = true` inside a transaction (dropping incomplete/empty sets).
    - `EDIT_COMPLETED(sessionId)`: loads existing completed session into memory, no timers, no checkboxes, no autosave; explicit `saveCompleted()` rewrites valid sets (`completed = true`, dropping incomplete sets) while preserving original `performedAt`, `completedAt`, `routineId`, and `testData`; `deleteSession()` deletes the session row.
  - Track `hasUnsavedChanges` and `hasValidSetsToSave` (at least one non-warmup set meeting `meetsRequirements`).

## Platform UI (`sharedUI` Compose & `iosApp` SwiftUI)

### 1. Tab & Calendar (`CalendarScreen.kt`, `CalendarView.swift`, `App.kt`, `ContentView.swift`)
- Rename the tab and screen title from **History** to **Journal** (keep the calendar icon).
- Month label on the 1st of each month displays `mmm'yy` (e.g., `Nov'26`), single-line without wrapping.
- Day numbers render in **bold** when `hasCompletedWorkout || hasJournal`.
- Indicators below day numbers: primary/tint dot for workout, purple dot for journal (both side-by-side when both exist, invisible placeholder when neither so row height stays uniform).
- Every day is tappable and opens a popup/sheet with the formatted date header:
  - Today or past (`!day.isFuture`): **Log completed workout**, **Edit existing workout**, **Write journal**.
  - Future (`day.isFuture`): **Write journal** only.
- List-view toggle shows every day with a workout or journal, displaying routine names and/or the journal preview snippet, tapping through to `DayDetail`.

### 2. Day Detail (`DayDetailScreen.kt`, `DayDetailView.swift`)
- Lists all logged workouts for the day, each with an **Edit** button (opens `WorkoutSession` in `EDIT_COMPLETED` mode) and a **Delete** button (with confirmation).
- Shows the day's **Journal** section (rendered via `MarkdownText` / `MarkdownTextView` if present, with an Edit/Write action).
- When no workouts exist on a past/today date, displays a clear empty state with a "Log completed workout" button.

### 3. Log & Edit Workout (`WorkoutSessionScreen.kt`, `WorkoutSessionView.swift`)
- When `isLiveSession == false`:
  - Hide the elapsed workout timer, rest timer countdowns, and set completion checkboxes/row tints.
  - Show **Cancel** (prompts discard confirmation if `hasUnsavedChanges`) and **Save** (enabled when `hasValidSetsToSave && (hasUnsavedChanges || !isEditingExisting)` — specifically when valid sets exist and changes were made).
  - In `EDIT_COMPLETED` mode, show a **Delete workout** button with confirmation.
  - In `LOG_COMPLETED` mode, include a **"Copy from routine"** option alongside **"Add exercise"** so users can log either ad-hoc or from a template.

### 4. Journal Editor (`JournalEditorScreen.kt`, `JournalEditorView.swift`)
- Header with formatted date, **Cancel** (confirms discard if `hasUnsavedChanges`), optional **Delete** (when editing an existing saved entry, with confirmation), and **Save** (disabled when `!hasUnsavedChanges || isOverLimit || saving`).
- Segmented **Edit / Preview** toggle; Preview renders via `MarkdownText` (Android) / `MarkdownTextView` (iOS).
- Markdown formatting toolbar (**Bold**, **Italic**, **Heading**, **List**) operating on the current text selection.
- Live `"N / 750 words"` counter that turns red when `wordCount > 750`.

## Localization

Add all new and updated strings to:
- `sharedUI/src/commonMain/composeResources/values/strings.xml`
- `sharedUI/src/commonMain/composeResources/values-sv/strings.xml`
- `sharedUI/src/commonMain/kotlin/dev/balaji/moonrose/trainfree/screens/LogicLabels.kt`
- `iosApp/iosApp/L10n.swift`
- `iosApp/iosApp/Localizable.xcstrings` (`en` and `sv`)

## Verification

- Unit tests in `sharedLogic` (`commonTest`, `androidHostTest`, `iosTest`):
  - `WeekMathTest` (year + `hasJournal` flags)
  - `JournalMathTest` (word count, preview snippet, markdown formatting helpers)
  - `JournalEditorStateTest` (word limit, dirty state, save, blank-delete, delete)
  - `WorkoutSessionLogEditTest` (in-memory edits, dropping empty sets, `completed = true`, preserving `performedAt`, delete)
  - `Migration18To19Test` (schema & migration validation)
- Run `./gradlew :sharedLogic:testAndroidHostTest :sharedUI:testAndroidHostTest :androidApp:assembleDebug`.
