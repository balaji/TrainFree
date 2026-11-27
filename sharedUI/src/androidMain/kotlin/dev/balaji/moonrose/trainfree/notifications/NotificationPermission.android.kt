package dev.balaji.moonrose.trainfree.notifications

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
actual fun rememberNotificationPermissionRequester(
    onResult: (NotificationPermissionResult) -> Unit,
): () -> Unit {
    val activity = LocalContext.current.findActivity()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onResult(
            when {
                granted -> NotificationPermissionResult.Granted
                // Rationale false after a denial means the OS auto-denied
                // without prompting (or the user picked "don't ask again").
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true ->
                    NotificationPermissionResult.Denied
                else -> NotificationPermissionResult.DeniedPermanently
            },
        )
    }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onResult(NotificationPermissionResult.Granted)
        }
    }
}

@Composable
actual fun rememberNotificationSettingsOpener(): () -> Unit {
    val context = LocalContext.current
    return {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
actual fun rememberCanScheduleExactAlarms(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val alarmManager = remember(context) {
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    }
    var canSchedule by remember(alarmManager) {
        mutableStateOf(alarmManager?.canScheduleExactAlarms() ?: true)
    }
    DisposableEffect(lifecycleOwner, alarmManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canSchedule = alarmManager?.canScheduleExactAlarms() ?: true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return canSchedule
}

actual fun isExactAlarmSettingSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
actual fun rememberExactAlarmSettingsOpener(): () -> Unit {
    val context = LocalContext.current
    return remember(context) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val exactAlarmIntent = Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val opened = runCatching { context.startActivity(exactAlarmIntent) }.isSuccess
                if (!opened) {
                    val detailsIntent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(detailsIntent) }
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
