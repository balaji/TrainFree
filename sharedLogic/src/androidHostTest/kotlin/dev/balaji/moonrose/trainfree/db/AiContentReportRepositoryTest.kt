package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.chat.AiContentReport
import dev.balaji.moonrose.trainfree.chat.AiContentReportReason
import dev.balaji.moonrose.trainfree.chat.AiContentReportSource
import dev.balaji.moonrose.trainfree.chat.createAiContentReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiContentReportRepositoryTest {

    @Test
    fun `decode and append round-trips reports and recovers from malformed json`() {
        assertTrue(decodeAiContentReports(null).isEmpty())
        assertTrue(decodeAiContentReports("  ").isEmpty())
        assertTrue(decodeAiContentReports("{broken json").isEmpty())

        val first = createAiContentReport(
            source = AiContentReportSource.CHAT,
            contentId = "msg-2",
            reasonKey = AiContentReportReason.OFFENSIVE,
            note = "  rude wording  ",
            contentText = "Sample output",
            reportedAtMillis = 1_000L,
        )
        val json1 = appendAiContentReport(null, first)
        assertEquals(listOf(first), decodeAiContentReports(json1))

        val second = createAiContentReport(
            source = AiContentReportSource.INSIGHT,
            contentId = "volume",
            reasonKey = "unknown_key",
            note = "",
            contentText = "Insight text",
            reportedAtMillis = 2_000L,
        )
        assertEquals(AiContentReportReason.OTHER, second.reasonKey)

        val json2 = appendAiContentReport(json1, second)
        assertEquals(listOf(first, second), decodeAiContentReports(json2))

        // Malformed existing JSON resets cleanly instead of crashing.
        val recovered = appendAiContentReport("[invalid", second)
        assertEquals(listOf(second), decodeAiContentReports(recovered))
    }

    @Test
    fun `append caps stored reports to maxEntries evicting oldest first`() {
        var json: String? = null
        val total = MAX_STORED_REPORTS + 5
        for (i in 1..total) {
            val report = AiContentReport(
                source = AiContentReportSource.CHAT,
                contentId = "msg-$i",
                reasonKey = AiContentReportReason.INACCURATE,
                note = "note $i",
                contentExcerpt = "excerpt $i",
                reportedAtMillis = i.toLong(),
            )
            json = appendAiContentReport(json, report)
        }

        val stored = decodeAiContentReports(json)
        assertEquals(MAX_STORED_REPORTS, stored.size)
        assertEquals("msg-6", stored.first().contentId)
        assertEquals("msg-$total", stored.last().contentId)
    }

    @Test
    fun `createAiContentReport bounds note and excerpt lengths`() {
        val longNote = "n".repeat(AiContentReport.MAX_NOTE_CHARS + 50)
        val longText = "t".repeat(AiContentReport.MAX_EXCERPT_CHARS + 50)
        val report = createAiContentReport(
            source = AiContentReportSource.CHAT,
            contentId = "msg-1",
            reasonKey = AiContentReportReason.UNSAFE_ADVICE,
            note = "  $longNote  ",
            contentText = "  $longText  ",
            reportedAtMillis = 42L,
        )
        assertEquals(AiContentReport.MAX_NOTE_CHARS, report.note.length)
        assertEquals(AiContentReport.MAX_EXCERPT_CHARS, report.contentExcerpt.length)
    }
}
