package dev.balaji.moonrose.trainfree.backup

import androidx.compose.runtime.Composable

/**
 * Returns a launcher that ensures Google Drive (`drive.file`) authorization,
 * showing the Google Play services consent/account picker when needed, and
 * invokes [onResult] with whether authorization succeeded or why it stopped.
 */
@Composable
expect fun rememberGoogleDriveAuthRequester(
    onResult: (authorized: Boolean, error: DriveErrorKind?) -> Unit,
): () -> Unit
