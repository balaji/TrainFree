package dev.balaji.moonrose.trainfree.screens

import androidx.compose.runtime.Composable

/**
 * Returns a launcher that opens the OS save-file picker for [filename] and
 * writes [json] to the chosen destination. Invokes [onCompleted] with `true`
 * on write success or `false` on write failure; user cancellation is a no-op.
 */
@Composable
internal expect fun rememberBackupExporter(
    onCompleted: (Boolean) -> Unit,
): (filename: String, json: String) -> Unit

/**
 * Returns a launcher that opens the OS file picker to select a backup JSON
 * file and reads its UTF-8 text. User cancellation is a no-op.
 */
@Composable
internal expect fun rememberBackupImporter(
    onReadSuccess: (String) -> Unit,
    onReadError: () -> Unit,
): () -> Unit
