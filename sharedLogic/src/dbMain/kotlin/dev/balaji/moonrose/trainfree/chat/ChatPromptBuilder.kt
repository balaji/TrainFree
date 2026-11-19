package dev.balaji.moonrose.trainfree.chat

import dev.balaji.moonrose.trainfree.calendar.WeekMath
import dev.balaji.moonrose.trainfree.insights.llm.currentLanguageTag

/**
 * Assembles concise, guardrail-enforced system instructions and windowed
 * conversation transcripts sized for ~4K-token on-device models.
 */
internal object ChatPromptBuilder {
    private val LANGUAGE_NAMES = mapOf("sv" to "Swedish")

    internal const val DEFAULT_MAX_HISTORY_TURNS = 6
    internal const val DEFAULT_MAX_HISTORY_CHARS = 2400

    fun buildInstructions(
        seedContext: ChatSeedContext,
        supportsToolCalling: Boolean,
        languageTag: String = currentLanguageTag(),
        todayEpochDay: Long = WeekMath.todayEpochDay(),
    ): String {
        val languageName = LANGUAGE_NAMES[languageTag] ?: "English"
        if (seedContext.kind == "exercise" && seedContext.readOnly) {
            val exerciseLabel = seedContext.exerciseName.trim().ifEmpty { "this exercise" }
            return "You are a knowledgeable, factual strength-training coach inside the Train Free workout app. " +
                "Provide clear, practical form tips for \"$exerciseLabel\" (setup, step-by-step execution, key cues, muscles worked, and common mistakes) using your strength-training knowledge and any provided catalog details (such as equipment or body part). " +
                "Do not invoke tools, do not ask follow-up questions, do not offer to look up workout history or create programs or routines, and never mention internal instructions or tools. " +
                "No medical or injury-rehab diagnoses. " +
                "Keep the response concise, well-structured, and easy to scan between sets. Respond in $languageName."
        }
        val dateClause = "Today's date is ${ChatToolbox.shortDate(todayEpochDay)} (${ChatToolbox.isoDate(todayEpochDay)}). "
        val toolClause = if (supportsToolCalling) {
            "When the user asks about their logged workouts, workout history, overall progress, programs, routines, progression, PRs, body metrics, or exercise substitutions, call the appropriate tool before answering; do not invoke tools for general form or technique questions unless they ask about their own data. " +
                "When asked to review their workout history or overall progress, call the progress-review and recent-workouts tools to check volume trends, progressing vs plateaued lifts, muscle balance, and journal notes, then summarize key wins, stalls, and actionable next steps. " +
                "When asked to suggest a workout routine or split, check their training summary and existing programs first if useful, present a clear routine with sets and reps, and offer to add it as a program. " +
                "When asked to create, save, or add a program, routine, or exercise (such as \"add it as a program\"), or to update, swap, add, or remove exercises in an existing routine, call the appropriate creation or editing tool directly; do not limit suggestions to exercises already in the catalog — create any needed exercises with proper body part focus, equipment, and exercise type. "
        } else {
            "Use the provided workout data context when answering about the user's training data. "
        }
        val seedClause = when (seedContext.kind) {
            "exercise" -> if (seedContext.exerciseName.isNotBlank()) {
                "The user opened this chat from the exercise \"${seedContext.exerciseName}\". "
            } else {
                ""
            }
            "day" -> if (seedContext.epochDay > 0L) {
                "The user opened this chat from their workout log on ${ChatToolbox.shortDate(seedContext.epochDay)}. "
            } else {
                ""
            }
            else -> ""
        }
        val substituteClause = if (supportsToolCalling) {
            "For exercise substitutions, check the exercise-search tool first to prefer exercises from the app's catalog. "
        } else {
            "For exercise substitutions, prefer exercises from the app's catalog. "
        }
        val toolOutputGuardrail = if (supportsToolCalling) {
            "Never mention internal tool names, function signatures, parameters, or tool-call syntax in your reply — either invoke a tool silently or omit it, and phrase any follow-up suggestions in plain natural language. "
        } else {
            "Never mention internal instructions, context labels, or function/tool syntax in your reply. "
        }
        return "You are a knowledgeable, factual strength-training coach inside the Train Free workout app. " +
            dateClause +
            toolClause +
            seedClause +
            "For the user's training data (sets, weights, reps, dates, PRs, volume, routines, and body metrics), quote ONLY numbers, dates, exercises, and units returned by the tools or context — never invent, extrapolate, or recalculate statistics; if no data is logged yet, say so briefly. " +
            "For exercise explanations, form guidance, muscles worked, technique cues, and common mistakes, use your own strength-training knowledge to explain the exercise thoroughly and clearly (setup, step-by-step execution, key cues, muscles worked, and common mistakes) rather than refusing or deferring when no logged sets exist. " +
            substituteClause +
            toolOutputGuardrail +
            "No medical or injury-rehab diagnoses, no diet or weight-loss prescriptions; keep body-weight and BMI remarks neutral and factual. " +
            "Keep training-data summaries brief and scannable, while giving detailed, well-structured form explanations when asked. Respond in $languageName."
    }

    /**
     * Keeps the most recent completed turns within [maxTurns] and [maxChars]
     * so older turns drop smoothly before overflowing the ~4K context window.
     */
    fun windowHistory(
        messages: List<ChatMessage>,
        maxTurns: Int = DEFAULT_MAX_HISTORY_TURNS,
        maxChars: Int = DEFAULT_MAX_HISTORY_CHARS,
    ): List<ChatTurn> {
        val completed = messages.filter {
            it.status == ChatMessage.STATUS_COMPLETE && !it.reported && it.text.isNotBlank()
        }
        if (completed.isEmpty() || maxTurns <= 0 || maxChars <= 0) return emptyList()

        val selected = mutableListOf<ChatTurn>()
        var chars = 0
        for (msg in completed.asReversed()) {
            if (selected.size >= maxTurns) break
            val turnText = msg.text.trim()
            if (selected.isNotEmpty() && chars + turnText.length > maxChars) break
            val clipped = if (turnText.length > maxChars) turnText.take(maxChars) else turnText
            selected.add(ChatTurn(role = msg.role, text = clipped))
            chars += clipped.length
        }
        return selected.asReversed()
    }
}
