package dev.balaji.moonrose.trainfree.db

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PendingBackupImport(
    val summary: BackupSummary,
    val rawJson: String,
)

enum class BackupNotice {
    EXPORT_SUCCEEDED,
    IMPORT_SUCCEEDED,
}

data class BackupUiState(
    val working: Boolean = false,
    val pendingImport: PendingBackupImport? = null,
    val error: BackupError? = null,
    val notice: BackupNotice? = null,
)

/**
 * Shared export/import state holder consumed by both Android Compose and iOS
 * SwiftUI so backup validation, confirmation, and error reporting behave
 * identically on both platforms.
 */
class BackupTransferState internal constructor(
    private val exportBackupJson: suspend () -> String,
    private val suggestFilename: () -> String,
    private val inspectBackupJson: (String) -> BackupInspectResult,
    private val restoreBackupJson: suspend (String) -> BackupError?,
    private val scope: CoroutineScope,
) {
    constructor() : this(
        exportBackupJson = { BackupRepository.exportJson() },
        suggestFilename = { BackupRepository.suggestedFilename() },
        inspectBackupJson = { BackupRepository.inspect(it) },
        restoreBackupJson = { BackupRepository.restore(it) },
        scope = CoroutineScope(Dispatchers.Main),
    )

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    fun prepareExport(onReady: (filename: String, json: String) -> Unit) {
        if (_uiState.value.working) return
        _uiState.update { it.copy(working = true, error = null, notice = null) }
        scope.launch {
            val result = runCatching {
                suggestFilename() to exportBackupJson()
            }
            result.onSuccess { (filename, json) ->
                _uiState.update { it.copy(working = false) }
                onReady(filename, json)
            }.onFailure {
                _uiState.update { it.copy(working = false, error = BackupError.EXPORT_FAILED) }
            }
        }
    }

    fun onExportCompleted(success: Boolean) {
        _uiState.update {
            if (success) {
                it.copy(working = false, notice = BackupNotice.EXPORT_SUCCEEDED, error = null)
            } else {
                it.copy(working = false, error = BackupError.EXPORT_FAILED, notice = null)
            }
        }
    }

    fun onFileReadError() {
        _uiState.update { it.copy(working = false, pendingImport = null, error = BackupError.INVALID_FILE) }
    }

    fun inspectImport(json: String) {
        if (_uiState.value.working) return
        when (val result = inspectBackupJson(json)) {
            is BackupInspectResult.Valid -> {
                _uiState.update {
                    it.copy(
                        pendingImport = PendingBackupImport(summary = result.summary, rawJson = json),
                        error = null,
                        notice = null,
                    )
                }
            }
            is BackupInspectResult.Invalid -> {
                _uiState.update {
                    it.copy(pendingImport = null, error = result.error, notice = null)
                }
            }
        }
    }

    fun cancelImport() {
        _uiState.update { it.copy(pendingImport = null) }
    }

    fun confirmImport() {
        val pending = _uiState.value.pendingImport ?: return
        if (_uiState.value.working) return
        _uiState.update { it.copy(working = true, error = null, notice = null) }
        scope.launch {
            val error = restoreBackupJson(pending.rawJson)
            _uiState.update {
                if (error == null) {
                    it.copy(
                        working = false,
                        pendingImport = null,
                        notice = BackupNotice.IMPORT_SUCCEEDED,
                        error = null,
                    )
                } else {
                    it.copy(
                        working = false,
                        pendingImport = null,
                        error = error,
                        notice = null,
                    )
                }
            }
        }
    }

    fun dismissFeedback() {
        _uiState.update { it.copy(error = null, notice = null) }
    }
}
