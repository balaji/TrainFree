package dev.balaji.moonrose.trainfree.health

import androidx.compose.runtime.Composable

/**
 * Launches the OS Health permission prompt for workout-write or body-weight
 * read/write permissions, invoking [onResult] with whether all requested
 * permissions were granted.
 */
@Composable
expect fun rememberHealthWorkoutPermissionRequester(
    onResult: (granted: Boolean) -> Unit,
): () -> Unit

@Composable
expect fun rememberHealthWeightPermissionRequester(
    onResult: (granted: Boolean) -> Unit,
): () -> Unit

@Composable
expect fun rememberHealthSettingsOpener(): () -> Unit
