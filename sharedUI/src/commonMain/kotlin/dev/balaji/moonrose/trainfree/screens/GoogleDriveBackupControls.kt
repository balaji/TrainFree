package dev.balaji.moonrose.trainfree.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.balaji.moonrose.trainfree.backup.DriveBackupAvailability
import dev.balaji.moonrose.trainfree.backup.DriveErrorKind
import dev.balaji.moonrose.trainfree.backup.rememberGoogleDriveAuthRequester
import dev.balaji.moonrose.trainfree.db.GoogleDriveBackupManager
import dev.balaji.moonrose.trainfree.db.GoogleDriveBackupState
import dev.balaji.moonrose.trainfree.db.GoogleDriveBackupUiState
import dev.balaji.moonrose.trainfree.model.UserSettings
import dev.balaji.moonrose.trainfree.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun GoogleDriveBackupControls(settings: UserSettings) {
    val availability by produceState(DriveBackupAvailability.UNAVAILABLE) {
        value = GoogleDriveBackupManager.availability()
    }
    if (availability == DriveBackupAvailability.UNAVAILABLE) return

    val driveState = remember { GoogleDriveBackupState() }
    val uiState by driveState.uiState.collectAsState()
    val managerSyncing by GoogleDriveBackupManager.isSyncing.collectAsState()
    val busy = uiState.working || managerSyncing

    val requestEnableAuth = rememberGoogleDriveAuthRequester { authorized, error ->
        driveState.onEnableAuthResult(authorized, error)
    }
    val requestReconnectAuth = rememberGoogleDriveAuthRequester { authorized, error ->
        driveState.onReconnectAuthResult(authorized, error)
    }
    val requestRestoreAuth = rememberGoogleDriveAuthRequester { authorized, error ->
        driveState.onRestoreAuthResult(authorized, error, fromOnboarding = false)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.google_drive_auto_backup),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Switch(
                checked = settings.googleDriveSyncEnabled,
                enabled = !uiState.working,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        requestEnableAuth()
                    } else {
                        driveState.disable()
                    }
                },
            )
        }

        if (settings.googleDriveSyncEnabled) {
            settings.googleDriveAccountEmail?.let { email ->
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val needsReconnect = settings.googleDriveLastError == DriveErrorKind.AUTH_REQUIRED
            val statusText: String? = when {
                busy -> stringResource(Res.string.google_drive_syncing)
                needsReconnect -> stringResource(Res.string.google_drive_reconnect_needed)
                settings.googleDrivePendingSync -> stringResource(Res.string.google_drive_pending_sync)
                settings.googleDriveLastSyncedAt != null -> stringResource(
                    Res.string.google_drive_last_synced,
                    formatDriveSyncTimestamp(settings.googleDriveLastSyncedAt!!),
                )
                else -> null
            }
            if (statusText != null) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (needsReconnect) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        if (needsReconnect) requestReconnectAuth() else driveState.syncNow()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (needsReconnect) Res.string.google_drive_reconnect else Res.string.google_drive_sync_now,
                        ),
                    )
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = requestRestoreAuth,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(Res.string.google_drive_restore))
                }
            }
        }
    }

    GoogleDriveBackupDialogs(state = uiState, driveState = driveState)
}

/**
 * Shared confirmation, conflict, error, and notice dialogs for
 * [GoogleDriveBackupState], used by both [ProfileScreen] and [ProfileSetupFlow].
 */
@Composable
internal fun GoogleDriveBackupDialogs(
    state: GoogleDriveBackupUiState,
    driveState: GoogleDriveBackupState,
) {
    state.pendingEnableConflict?.let { pending ->
        val summary = pending.summary
        val dateText = formatDayTitle(summary.exportedEpochDay)
        AlertDialog(
            onDismissRequest = { if (!state.working) driveState.cancelEnableConflict() },
            title = { Text(stringResource(Res.string.google_drive_conflict_title)) },
            text = {
                Text(
                    stringResource(
                        Res.string.google_drive_conflict_message,
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
                    onClick = { driveState.confirmEnableOverwrite() },
                ) {
                    Text(stringResource(Res.string.google_drive_overwrite))
                }
                TextButton(
                    enabled = !state.working,
                    onClick = { driveState.confirmEnableRestore() },
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
                    onClick = { driveState.cancelEnableConflict() },
                ) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    state.pendingRestore?.let { pending ->
        val summary = pending.summary
        val dateText = formatDayTitle(summary.exportedEpochDay)
        AlertDialog(
            onDismissRequest = { if (!state.working) driveState.cancelRestore() },
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
                    onClick = { driveState.confirmRestore() },
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
                    onClick = { driveState.cancelRestore() },
                ) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    state.driveError?.let { error ->
        AlertDialog(
            onDismissRequest = { driveState.dismissFeedback() },
            title = { Text(stringResource(Res.string.google_drive_error_title)) },
            text = { Text(driveErrorLabel(error)) },
            confirmButton = {
                TextButton(onClick = { driveState.dismissFeedback() }) {
                    Text(stringResource(Res.string.ok))
                }
            },
        )
    }

    state.backupError?.let { error ->
        AlertDialog(
            onDismissRequest = { driveState.dismissFeedback() },
            title = { Text(stringResource(Res.string.backup_error_title)) },
            text = { Text(backupErrorLabel(error)) },
            confirmButton = {
                TextButton(onClick = { driveState.dismissFeedback() }) {
                    Text(stringResource(Res.string.ok))
                }
            },
        )
    }

    state.notice?.let { notice ->
        AlertDialog(
            onDismissRequest = { driveState.dismissFeedback() },
            text = { Text(driveNoticeLabel(notice)) },
            confirmButton = {
                TextButton(onClick = { driveState.dismissFeedback() }) {
                    Text(stringResource(Res.string.ok))
                }
            },
        )
    }
}
