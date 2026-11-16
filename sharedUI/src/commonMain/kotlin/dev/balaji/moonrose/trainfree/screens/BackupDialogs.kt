package dev.balaji.moonrose.trainfree.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.balaji.moonrose.trainfree.db.BackupTransferState
import dev.balaji.moonrose.trainfree.db.BackupUiState
import dev.balaji.moonrose.trainfree.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Shared confirmation, error, and completion dialogs for [BackupTransferState],
 * used by both [ProfileScreen] and [ProfileSetupFlow].
 */
@Composable
internal fun BackupDialogs(
    state: BackupUiState,
    backupState: BackupTransferState,
) {
    state.pendingImport?.let { pending ->
        val summary = pending.summary
        val dateText = formatDayTitle(summary.exportedEpochDay)
        AlertDialog(
            onDismissRequest = { if (!state.working) backupState.cancelImport() },
            title = { Text(stringResource(Res.string.restore_backup_title)) },
            text = {
                Text(
                    stringResource(
                        Res.string.restore_backup_message,
                        dateText,
                        summary.workoutCount,
                        summary.programCount,
                        summary.journalEntryCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.working,
                    onClick = { backupState.confirmImport() },
                ) {
                    Text(
                        stringResource(
                            if (state.working) Res.string.restoring else Res.string.restore,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.working,
                    onClick = { backupState.cancelImport() },
                ) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = { backupState.dismissFeedback() },
            title = { Text(stringResource(Res.string.backup_error_title)) },
            text = { Text(backupErrorLabel(error)) },
            confirmButton = {
                TextButton(onClick = { backupState.dismissFeedback() }) {
                    Text(stringResource(Res.string.ok))
                }
            },
        )
    }

    state.notice?.let { notice ->
        AlertDialog(
            onDismissRequest = { backupState.dismissFeedback() },
            text = { Text(backupNoticeLabel(notice)) },
            confirmButton = {
                TextButton(onClick = { backupState.dismissFeedback() }) {
                    Text(stringResource(Res.string.ok))
                }
            },
        )
    }
}
