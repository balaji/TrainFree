package dev.balaji.moonrose.trainfree.chat

import kotlinx.serialization.Serializable

/** Language-neutral reason keys for user reports on on-device AI responses. */
object AiContentReportReason {
    const val OFFENSIVE = "offensive"
    const val UNSAFE_ADVICE = "unsafe_advice"
    const val INACCURATE = "inaccurate"
    const val OTHER = "other"

    val ALL: List<String> = listOf(OFFENSIVE, UNSAFE_ADVICE, INACCURATE, OTHER)

    /** Maps blank or unrecognized reason keys to [OTHER]. */
    fun normalize(reasonKey: String): String {
        val trimmed = reasonKey.trim()
        return if (trimmed in ALL) trimmed else OTHER
    }
}

/** Language-neutral identifiers for which AI surface produced a reported response. */
object AiContentReportSource {
    const val CHAT = "chat"
    const val INSIGHT = "insight"
}

/** Local-only user report for an on-device AI response or insight narration. */
@Serializable
data class AiContentReport(
    val source: String,
    val contentId: String,
    val reasonKey: String,
    val note: String = "",
    val contentExcerpt: String = "",
    val reportedAtMillis: Long,
) {
    companion object {
        const val MAX_NOTE_CHARS = 300
        internal const val MAX_EXCERPT_CHARS = 240
    }
}

/** Builds a bounded, trimmed [AiContentReport] with a normalized reason key. */
internal fun createAiContentReport(
    source: String,
    contentId: String,
    reasonKey: String,
    note: String,
    contentText: String,
    reportedAtMillis: Long,
): AiContentReport = AiContentReport(
    source = source,
    contentId = contentId,
    reasonKey = AiContentReportReason.normalize(reasonKey),
    note = note.trim().take(AiContentReport.MAX_NOTE_CHARS),
    contentExcerpt = contentText.trim().take(AiContentReport.MAX_EXCERPT_CHARS),
    reportedAtMillis = reportedAtMillis,
)
