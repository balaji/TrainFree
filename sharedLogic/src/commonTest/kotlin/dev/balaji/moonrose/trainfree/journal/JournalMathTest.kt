package dev.balaji.moonrose.trainfree.journal

import dev.balaji.moonrose.trainfree.calendar.WeekMath
import dev.balaji.moonrose.trainfree.calendar.WeekStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class JournalMathTest {
    @Test
    fun countsWhitespaceDelimitedWords() {
        assertEquals(0, countJournalWords(""))
        assertEquals(0, countJournalWords("   \n\t  "))
        assertEquals(3, countJournalWords("Heavy squat day"))
        assertEquals(5, countJournalWords("## Upper\n- Bench 3x5\n"))
        assertEquals(MAX_JOURNAL_WORDS, countJournalWords(List(MAX_JOURNAL_WORDS) { "word" }.joinToString(" ")))
    }

    @Test
    fun buildsPlainTextPreviewSnippet() {
        assertEquals("", journalPreviewSnippet("   \n "))
        val md = """
            ## Push Day
            - **Bench Press**: 3x5 @ 100kg
            - Felt *great* today [notes](https://example.com)
        """.trimIndent()
        assertEquals("Push Day Bench Press: 3x5 @ 100kg Felt great today notes", journalPreviewSnippet(md))

        val longText = List(40) { "benchmark" }.joinToString(" ")
        val clipped = journalPreviewSnippet(longText, maxChars = 30)
        assertTrue(clipped.endsWith("…"))
        assertTrue(clipped.length <= 31)
    }

    @Test
    fun toolbarFormattingWrapsUnwrapsAndTogglesLines() {
        val bolded = MarkdownFormatHelper.toggleBold("squat day", 0, 5)
        assertEquals("**squat** day", bolded.text)
        assertEquals(2, bolded.selectionStart)
        assertEquals(7, bolded.selectionEnd)

        val unbolded = MarkdownFormatHelper.toggleBold(bolded.text, bolded.selectionStart, bolded.selectionEnd)
        assertEquals("squat day", unbolded.text)

        val italicCursor = MarkdownFormatHelper.toggleItalic("note", 4, 4)
        assertEquals("note**", italicCursor.text)
        assertEquals(5, italicCursor.selectionStart)

        val headed = MarkdownFormatHelper.toggleHeading("Leg day\nSquats", 0, 0)
        assertEquals("## Leg day\nSquats", headed.text)

        val unheaded = MarkdownFormatHelper.toggleHeading(headed.text, 0, 0)
        assertEquals("Leg day\nSquats", unheaded.text)

        val bulleted = MarkdownFormatHelper.toggleBulletList("A\nB", 0, 3)
        assertEquals("- A\n- B", bulleted.text)
    }

    @Test
    fun weekMathPopulatesYearAndHasJournal() {
        val dayWithWorkout = LocalDate(2026, 11, 1).toEpochDays()
        val dayWithJournal = LocalDate(2026, 11, 2).toEpochDays()
        val weeks = WeekMath.buildWeeks(
            startEpochDay = dayWithWorkout,
            endEpochDay = dayWithJournal,
            completedDays = setOf(dayWithWorkout),
            todayEpochDay = dayWithJournal,
            weekStart = WeekStart.SUNDAY,
            journalDays = setOf(dayWithJournal),
        )
        val days = weeks.flatMap { it.days }.associateBy { it.epochDay }
        val nov1 = days.getValue(dayWithWorkout)
        assertEquals(2026, nov1.year)
        assertEquals(11, nov1.monthOfYear)
        assertEquals(1, nov1.dayOfMonth)
        assertTrue(nov1.hasCompletedWorkout)
        assertFalse(nov1.hasJournal)

        val nov2 = days.getValue(dayWithJournal)
        assertEquals(2026, nov2.year)
        assertFalse(nov2.hasCompletedWorkout)
        assertTrue(nov2.hasJournal)
    }
}
