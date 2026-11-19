package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.chat.AiContentReport
import kotlinx.serialization.json.Json

internal const val MAX_STORED_REPORTS = 50

private val reportJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Decodes stored local AI content reports, returning an empty list if [json]
 * is null, blank, or malformed.
 */
internal fun decodeAiContentReports(json: String?): List<AiContentReport> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching { reportJson.decodeFromString<List<AiContentReport>>(json) }
        .getOrDefault(emptyList())
}

/**
 * Appends [report] to [existingJson] and keeps at most the newest [maxEntries]
 * entries so local storage stays bounded without a Room schema migration.
 */
internal fun appendAiContentReport(
    existingJson: String?,
    report: AiContentReport,
    maxEntries: Int = MAX_STORED_REPORTS,
): String {
    val updated = if (maxEntries <= 0) {
        emptyList()
    } else {
        (decodeAiContentReports(existingJson) + report).takeLast(maxEntries)
    }
    return reportJson.encodeToString(updated)
}

/**
 * Persists user-submitted flags on AI-generated outputs locally in
 * `user_settings` (never transmitted off-device and excluded from user backups).
 */
object AiContentReportRepository {
    suspend fun record(report: AiContentReport) {
        val dao = AppDb.db.userSettingDao()
        val now = nowMillis()
        val existing = dao.find(SETTING_AI_CONTENT_REPORTS)
        val encoded = appendAiContentReport(existing?.value, report)
        dao.upsert(
            UserSettingEntity(
                key = SETTING_AI_CONTENT_REPORTS,
                value = encoded,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    suspend fun getAll(): List<AiContentReport> =
        decodeAiContentReports(AppDb.db.userSettingDao().find(SETTING_AI_CONTENT_REPORTS)?.value)
}
