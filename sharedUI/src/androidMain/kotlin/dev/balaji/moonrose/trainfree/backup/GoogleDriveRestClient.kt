package dev.balaji.moonrose.trainfree.backup

import dev.balaji.moonrose.trainfree.db.MAX_BACKUP_FILE_BYTES
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal class DriveUnauthorizedException : Exception("Google Drive returned HTTP 401")
internal class DriveQuotaExceededException : Exception("Google Drive storage quota exceeded")
internal class DriveNotFoundException : Exception("Google Drive file not found")
internal class DriveApiException(val statusCode: Int, message: String) : Exception(message)

internal data class DriveFileMeta(
    val id: String,
)

/**
 * Lightweight Google Drive REST API v3 client built on [HttpURLConnection] and
 * `org.json` to avoid extra HTTP/JSON dependencies and R8 reflection rules.
 */
internal object GoogleDriveRestClient {
    private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
    private const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
    private const val DRIVE_ABOUT_URL = "https://www.googleapis.com/drive/v3/about"
    private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
    private const val JSON_MIME_TYPE = "application/json"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    fun fetchAccountEmail(accessToken: String): String? {
        val url = "$DRIVE_ABOUT_URL?fields=${encode("user(emailAddress)")}"
        val body = requestJson("GET", url, accessToken)
        val user = JSONObject(body).optJSONObject("user") ?: return null
        return user.optString("emailAddress").takeIf { it.isNotBlank() }
    }

    fun findBackupFile(accessToken: String, knownFileId: String?): DriveFileMeta? {
        if (!knownFileId.isNullOrBlank()) {
            val verified = verifyFileExists(accessToken, knownFileId)
            if (verified != null) return verified
        }
        val byPropertyQuery =
            "appProperties has { key='$DRIVE_APP_PROPERTY_KEY' and value='$DRIVE_APP_PROPERTY_VALUE' } and trashed = false"
        searchFiles(accessToken, byPropertyQuery).firstOrNull()?.let { return it }

        val folderId = findFolder(accessToken) ?: return null
        val byNameQuery =
            "'${escapeQueryValue(folderId)}' in parents and name = '${escapeQueryValue(DRIVE_BACKUP_FILENAME)}' and trashed = false"
        return searchFiles(accessToken, byNameQuery).firstOrNull()
    }

    fun uploadBackup(accessToken: String, json: String, knownFileId: String?): DriveFileMeta {
        val existing = findBackupFile(accessToken, knownFileId)
        if (existing != null) {
            return patchMedia(accessToken, existing.id, json)
        }
        val folderId = findFolder(accessToken) ?: createFolder(accessToken)
        return createMultipartFile(accessToken, folderId, json)
    }

    fun downloadBackup(accessToken: String, fileId: String): String {
        val url = "$DRIVE_FILES_URL/${encode(fileId)}?alt=media"
        return requestString("GET", url, accessToken, maxBytes = MAX_BACKUP_FILE_BYTES)
    }

    private fun verifyFileExists(accessToken: String, fileId: String): DriveFileMeta? {
        val url = "$DRIVE_FILES_URL/${encode(fileId)}?fields=${encode("id,trashed")}"
        return try {
            val obj = JSONObject(requestJson("GET", url, accessToken))
            if (obj.optBoolean("trashed", false)) null else DriveFileMeta(obj.getString("id"))
        } catch (_: DriveNotFoundException) {
            null
        }
    }

    private fun findFolder(accessToken: String): String? {
        val query =
            "name = '${escapeQueryValue(DRIVE_BACKUP_FOLDER_NAME)}' and mimeType = '$FOLDER_MIME_TYPE' and trashed = false"
        return searchFiles(accessToken, query).firstOrNull()?.id
    }

    private fun createFolder(accessToken: String): String {
        val payload = JSONObject()
            .put("name", DRIVE_BACKUP_FOLDER_NAME)
            .put("mimeType", FOLDER_MIME_TYPE)
            .toString()
        val response = requestJson(
            method = "POST",
            urlString = "$DRIVE_FILES_URL?fields=id",
            accessToken = accessToken,
            contentType = "application/json; charset=UTF-8",
            bodyBytes = payload.toByteArray(Charsets.UTF_8),
        )
        return JSONObject(response).getString("id")
    }

    private fun searchFiles(accessToken: String, query: String): List<DriveFileMeta> {
        val url = buildString {
            append(DRIVE_FILES_URL)
            append("?q=").append(encode(query))
            append("&spaces=drive")
            append("&fields=").append(encode("files(id,trashed)"))
            append("&orderBy=").append(encode("modifiedTime desc"))
            append("&pageSize=10")
        }
        val response = requestJson("GET", url, accessToken)
        val files = JSONObject(response).optJSONArray("files") ?: return emptyList()
        return buildList {
            for (i in 0 until files.length()) {
                val item = files.optJSONObject(i) ?: continue
                if (!item.optBoolean("trashed", false)) {
                    val id = item.optString("id")
                    if (id.isNotBlank()) add(DriveFileMeta(id))
                }
            }
        }
    }

    private fun patchMedia(accessToken: String, fileId: String, json: String): DriveFileMeta {
        val url = "$DRIVE_UPLOAD_URL/${encode(fileId)}?uploadType=media&fields=id"
        val response = requestJson(
            method = "PATCH",
            urlString = url,
            accessToken = accessToken,
            contentType = "application/json; charset=UTF-8",
            bodyBytes = json.toByteArray(Charsets.UTF_8),
        )
        return DriveFileMeta(JSONObject(response).getString("id"))
    }

    private fun createMultipartFile(accessToken: String, folderId: String, json: String): DriveFileMeta {
        val boundary = "trainfree_${UUID.randomUUID().toString().replace("-", "")}"
        val metadata = JSONObject()
            .put("name", DRIVE_BACKUP_FILENAME)
            .put("parents", JSONArray().put(folderId))
            .put("mimeType", JSON_MIME_TYPE)
            .put("appProperties", JSONObject().put(DRIVE_APP_PROPERTY_KEY, DRIVE_APP_PROPERTY_VALUE))
            .toString()

        val body = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(json).append("\r\n")
            append("--").append(boundary).append("--\r\n")
        }.toByteArray(Charsets.UTF_8)

        val response = requestJson(
            method = "POST",
            urlString = "$DRIVE_UPLOAD_URL?uploadType=multipart&fields=id",
            accessToken = accessToken,
            contentType = "multipart/related; boundary=$boundary",
            bodyBytes = body,
        )
        return DriveFileMeta(JSONObject(response).getString("id"))
    }

    private fun requestJson(
        method: String,
        urlString: String,
        accessToken: String,
        contentType: String? = null,
        bodyBytes: ByteArray? = null,
    ): String = requestString(method, urlString, accessToken, contentType, bodyBytes, maxBytes = 2 * 1024 * 1024)

    private fun requestString(
        method: String,
        urlString: String,
        accessToken: String,
        contentType: String? = null,
        bodyBytes: ByteArray? = null,
        maxBytes: Int = MAX_BACKUP_FILE_BYTES,
    ): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $accessToken")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            if (bodyBytes != null) {
                doOutput = true
                setFixedLengthStreamingMode(bodyBytes.size)
            }
        }
        return try {
            if (bodyBytes != null) {
                conn.outputStream.use { it.write(bodyBytes) }
            }
            val status = conn.responseCode
            if (status in 200..299) {
                conn.inputStream.use { readUtf8Bounded(it, maxBytes) }
            } else {
                val errBody = conn.errorStream?.use { runCatching { readUtf8Bounded(it, 64 * 1024) }.getOrNull() }.orEmpty()
                throw mapHttpError(status, errBody)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun mapHttpError(status: Int, errorBody: String): Throwable = when {
        status == 401 -> DriveUnauthorizedException()
        status == 404 -> DriveNotFoundException()
        status == 403 && (
            errorBody.contains("storageQuotaExceeded", ignoreCase = true) ||
                errorBody.contains("quotaExceeded", ignoreCase = true)
            ) -> DriveQuotaExceededException()
        else -> DriveApiException(status, "Drive API HTTP $status: $errorBody")
    }

    private fun readUtf8Bounded(stream: java.io.InputStream, maxBytes: Int): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            total += read
            if (total > maxBytes) throw IOException("Response exceeds $maxBytes bytes")
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray().toString(Charsets.UTF_8)
    }

    private fun escapeQueryValue(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
