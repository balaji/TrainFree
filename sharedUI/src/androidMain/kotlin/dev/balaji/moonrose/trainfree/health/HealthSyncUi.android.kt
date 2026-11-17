package dev.balaji.moonrose.trainfree.health

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController

@Composable
actual fun rememberHealthWorkoutPermissionRequester(
    onResult: (granted: Boolean) -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        onResult(AndroidHealthSyncProvider.WORKOUT_WRITE_PERMISSION in granted)
    }
    return remember(launcher) {
        { launcher.launch(setOf(AndroidHealthSyncProvider.WORKOUT_WRITE_PERMISSION)) }
    }
}

@Composable
actual fun rememberHealthWeightPermissionRequester(
    onResult: (granted: Boolean) -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        onResult(AndroidHealthSyncProvider.WEIGHT_PERMISSIONS.all { it in granted })
    }
    return remember(launcher) {
        { launcher.launch(AndroidHealthSyncProvider.WEIGHT_PERMISSIONS) }
    }
}

@Composable
actual fun rememberHealthSettingsOpener(): () -> Unit {
    val context = LocalContext.current
    return remember(context) {
        {
            val settingsIntent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = context.packageManager.resolveActivity(settingsIntent, 0) != null
            if (resolved) {
                context.startActivity(settingsIntent)
            } else {
                val playIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.google.android.apps.healthdata"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(playIntent) }
            }
        }
    }
}
