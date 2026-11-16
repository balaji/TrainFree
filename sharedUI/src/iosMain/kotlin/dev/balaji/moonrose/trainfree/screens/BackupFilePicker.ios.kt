package dev.balaji.moonrose.trainfree.screens

import androidx.compose.runtime.Composable

// Native SwiftUI views use `.fileExporter` / `.fileImporter` directly.
@Composable
internal actual fun rememberBackupExporter(
    onCompleted: (Boolean) -> Unit,
): (filename: String, json: String) -> Unit = { _, _ -> }

@Composable
internal actual fun rememberBackupImporter(
    onReadSuccess: (String) -> Unit,
    onReadError: () -> Unit,
): () -> Unit = {}
