package dev.balaji.moonrose.trainfree.journal

const val MAX_JOURNAL_WORDS = 750

private val WORD_REGEX = Regex("""\S+""")
private val HEADING_PREFIX_REGEX = Regex("""^#{1,6}[ \t]+""")
private val LIST_PREFIX_REGEX = Regex("""^(?:[-*+•]|\d+[.)])[ \t]+""")
private val QUOTE_PREFIX_REGEX = Regex("""^>[ \t]?""")
private val INLINE_LINK_REGEX = Regex("""\[([^\]]+)\]\([^)]*\)""")
private val INLINE_EMPHASIS_REGEX = Regex("""(\*\*|__|~~|\*|_|`)""")

/** Counts whitespace-delimited tokens in [text]. */
fun countJournalWords(text: String): Int =
    if (text.isBlank()) 0 else WORD_REGEX.findAll(text).count()

/**
 * Collapses [markdown] into a compact plain-text preview for list rows,
 * stripping headings, list markers, emphasis, and extra whitespace.
 */
fun journalPreviewSnippet(markdown: String, maxChars: Int = 120): String {
    if (markdown.isBlank()) return ""
    val collapsed = markdown
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .map { line ->
            line.trim()
                .replace(HEADING_PREFIX_REGEX, "")
                .replace(LIST_PREFIX_REGEX, "")
                .replace(QUOTE_PREFIX_REGEX, "")
                .replace(INLINE_LINK_REGEX, "$1")
                .replace(INLINE_EMPHASIS_REGEX, "")
                .trim()
        }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    if (collapsed.length <= maxChars) return collapsed
    return collapsed.take(maxChars).trimEnd() + "…"
}

/** Result of applying a toolbar markdown edit, with updated UTF-16 selection bounds. */
data class MarkdownEditResult(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

/**
 * Pure text + selection transforms for the markdown formatting toolbar,
 * shared between Android Compose (`TextFieldValue`) and iOS (`UITextView`).
 */
object MarkdownFormatHelper {
    fun toggleBold(text: String, selectionStart: Int, selectionEnd: Int): MarkdownEditResult =
        toggleInlineWrapper(text, selectionStart, selectionEnd, "**")

    fun toggleItalic(text: String, selectionStart: Int, selectionEnd: Int): MarkdownEditResult =
        toggleInlineWrapper(text, selectionStart, selectionEnd, "*")

    fun toggleHeading(text: String, selectionStart: Int, selectionEnd: Int): MarkdownEditResult =
        toggleLinePrefix(text, selectionStart, selectionEnd, "## ")

    fun toggleBulletList(text: String, selectionStart: Int, selectionEnd: Int): MarkdownEditResult =
        toggleLinePrefix(text, selectionStart, selectionEnd, "- ")

    private fun toggleInlineWrapper(
        text: String,
        rawStart: Int,
        rawEnd: Int,
        marker: String,
    ): MarkdownEditResult {
        val len = text.length
        val start = minOf(rawStart, rawEnd).coerceIn(0, len)
        val end = maxOf(rawStart, rawEnd).coerceIn(0, len)
        val mLen = marker.length

        if (start == end) {
            // Unwrap if cursor is directly inside an empty marker pair (e.g. **|**).
            if (start >= mLen && end + mLen <= len &&
                text.substring(start - mLen, start) == marker &&
                text.substring(end, end + mLen) == marker
            ) {
                val updated = text.removeRange(end, end + mLen).removeRange(start - mLen, start)
                return MarkdownEditResult(updated, start - mLen, start - mLen)
            }
            val updated = text.substring(0, start) + marker + marker + text.substring(end)
            return MarkdownEditResult(updated, start + mLen, start + mLen)
        }

        val selected = text.substring(start, end)
        // Selected text itself starts and ends with the marker.
        if (selected.length >= 2 * mLen &&
            selected.startsWith(marker) &&
            selected.endsWith(marker) &&
            !(marker == "*" && selected.startsWith("**") && selected.endsWith("**"))
        ) {
            val inner = selected.substring(mLen, selected.length - mLen)
            val updated = text.substring(0, start) + inner + text.substring(end)
            return MarkdownEditResult(updated, start, start + inner.length)
        }

        // Markers sit immediately outside the selection.
        if (start >= mLen && end + mLen <= len &&
            text.substring(start - mLen, start) == marker &&
            text.substring(end, end + mLen) == marker &&
            !(marker == "*" &&
                start >= 2 && text.substring(start - 2, start) == "**" &&
                end + 2 <= len && text.substring(end, end + 2) == "**")
        ) {
            val updated = text.removeRange(end, end + mLen).removeRange(start - mLen, start)
            return MarkdownEditResult(updated, start - mLen, end - mLen)
        }

        val updated = text.substring(0, start) + marker + selected + marker + text.substring(end)
        return MarkdownEditResult(updated, start + mLen, end + mLen)
    }

    private fun toggleLinePrefix(
        text: String,
        rawStart: Int,
        rawEnd: Int,
        prefix: String,
    ): MarkdownEditResult {
        val len = text.length
        val start = minOf(rawStart, rawEnd).coerceIn(0, len)
        val end = maxOf(rawStart, rawEnd).coerceIn(0, len)

        val blockStart = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
        // When selection ends right after a newline (and is non-empty), don't include the trailing line.
        val effectiveEnd = if (end > start && text.getOrNull(end - 1) == '\n') end - 1 else end
        val nextNewline = text.indexOf('\n', effectiveEnd)
        val blockEnd = if (nextNewline == -1) len else nextNewline

        val block = text.substring(blockStart, blockEnd)
        val lines = block.split('\n')
        val allHavePrefix = lines.all { it.startsWith(prefix) }

        val updatedLines = if (allHavePrefix) {
            lines.map { it.removePrefix(prefix) }
        } else {
            lines.map { line -> if (line.startsWith(prefix)) line else prefix + line }
        }
        val updatedBlock = updatedLines.joinToString("\n")
        val updatedText = text.substring(0, blockStart) + updatedBlock + text.substring(blockEnd)

        val deltaFirst = if (allHavePrefix) -prefix.length else {
            if (lines.first().startsWith(prefix)) 0 else prefix.length
        }
        val totalDelta = updatedBlock.length - block.length
        val newStart = (start + deltaFirst).coerceIn(blockStart, blockStart + updatedBlock.length)
        val newEnd = if (start == end) newStart else (end + totalDelta).coerceIn(newStart, blockStart + updatedBlock.length)
        return MarkdownEditResult(updatedText, newStart, newEnd)
    }
}
