package dev.balaji.moonrose.trainfree.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.balaji.moonrose.trainfree.db.MAX_BACKUP_FILE_BYTES
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberBackupExporter(
    onCompleted: (Boolean) -> Unit,
): (filename: String, json: String) -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val latestOnCompleted by rememberUpdatedState(onCompleted)
    var pendingJson by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val json = pendingJson
        pendingJson = null
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: error("Could not open output stream")
                    stream.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                }.isSuccess
            }
            latestOnCompleted(ok)
        }
    }

    return { filename, json ->
        pendingJson = json
        launcher.launch(filename)
    }
}

@Composable
internal actual fun rememberBackupImporter(
    onReadSuccess: (String) -> Unit,
    onReadError: () -> Unit,
): () -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val latestOnSuccess by rememberUpdatedState(onReadSuccess)
    val latestOnError by rememberUpdatedState(onReadError)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: error("Could not open input stream")
                    stream.use { input ->
                        val buffer = ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val read = input.read(chunk)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_BACKUP_FILE_BYTES) {
                                error("Backup file exceeds max size")
                            }
                            buffer.write(chunk, 0, read)
                        }
                        buffer.toByteArray().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text != null) {
                latestOnSuccess(text)
            } else {
                latestOnError()
            }
        }
    }

    return {
        // Document providers (including Google Drive) may tag .json files as
        // application/octet-stream or text/plain; validate content after picking.
        launcher.launch(arrayOf("*/*"))
    }
}
