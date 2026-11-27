package dev.balaji.moonrose.trainfree.backup

import androidx.compose.runtime.Composable

@Composable
actual fun rememberGoogleDriveAuthRequester(
    onResult: (authorized: Boolean, error: DriveErrorKind?) -> Unit,
): () -> Unit = { onResult(false, DriveErrorKind.UNKNOWN) }
