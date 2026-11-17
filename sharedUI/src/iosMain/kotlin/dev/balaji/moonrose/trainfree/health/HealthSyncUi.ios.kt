package dev.balaji.moonrose.trainfree.health

import androidx.compose.runtime.Composable

@Composable
actual fun rememberHealthWorkoutPermissionRequester(
    onResult: (granted: Boolean) -> Unit,
): () -> Unit = { onResult(false) }

@Composable
actual fun rememberHealthWeightPermissionRequester(
    onResult: (granted: Boolean) -> Unit,
): () -> Unit = { onResult(false) }

@Composable
actual fun rememberHealthSettingsOpener(): () -> Unit = {}
