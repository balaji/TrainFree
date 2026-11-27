package dev.balaji.moonrose.trainfree.backup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope

@Composable
actual fun rememberGoogleDriveAuthRequester(
    onResult: (authorized: Boolean, error: DriveErrorKind?) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val latestOnResult by rememberUpdatedState(onResult)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            latestOnResult(false, DriveErrorKind.CANCELLED)
            return@rememberLauncherForActivityResult
        }
        val activity = context.findActivity()
        if (activity == null) {
            latestOnResult(false, DriveErrorKind.UNKNOWN)
            return@rememberLauncherForActivityResult
        }
        val authResult = runCatching {
            Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(result.data)
        }.getOrNull()
        val token = authResult?.accessToken
        if (!token.isNullOrBlank()) {
            latestOnResult(true, null)
        } else {
            latestOnResult(false, DriveErrorKind.AUTH_REQUIRED)
        }
    }

    return remember(context, launcher) {
        {
            val activity = context.findActivity()
            if (activity == null) {
                latestOnResult(false, DriveErrorKind.UNKNOWN)
            } else {
                val request = AuthorizationRequest.Builder()
                    .setRequestedScopes(listOf(Scope(DRIVE_BACKUP_SCOPE)))
                    .build()
                Identity.getAuthorizationClient(activity)
                    .authorize(request)
                    .addOnSuccessListener { authResult ->
                        if (authResult.hasResolution()) {
                            val intentSender = authResult.pendingIntent?.intentSender
                            if (intentSender != null) {
                                launcher.launch(IntentSenderRequest.Builder(intentSender).build())
                            } else {
                                latestOnResult(false, DriveErrorKind.UNKNOWN)
                            }
                        } else if (!authResult.accessToken.isNullOrBlank()) {
                            latestOnResult(true, null)
                        } else {
                            latestOnResult(false, DriveErrorKind.AUTH_REQUIRED)
                        }
                    }
                    .addOnFailureListener {
                        latestOnResult(false, DriveErrorKind.UNKNOWN)
                    }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
