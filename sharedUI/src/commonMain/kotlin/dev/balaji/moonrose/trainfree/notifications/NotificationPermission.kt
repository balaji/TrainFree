package dev.balaji.moonrose.trainfree.notifications

import androidx.compose.runtime.Composable

enum class NotificationPermissionResult {
    Granted,

    /** Denied at the prompt just shown; the OS may prompt again next time. */
    Denied,

    /**
     * Denied without the OS showing a prompt — the user has to flip the
     * app's notification permission in system settings instead.
     */
    DeniedPermanently,
}

/**
 * Returns a callback that asks the OS for permission to send notifications
 * and reports the outcome through [onResult]. Invoked when the user turns
 * the notifications setting on; the setting is only persisted as enabled
 * when the OS grant succeeds. Always granted below Android 13, where no
 * runtime permission exists.
 */
@Composable
expect fun rememberNotificationPermissionRequester(onResult: (NotificationPermissionResult) -> Unit): () -> Unit

/** Returns a callback that opens the app's notification settings in the OS. */
@Composable
expect fun rememberNotificationSettingsOpener(): () -> Unit

/**
 * Returns whether the app may schedule exact alarms for background rest timer
 * alerts. Always true below Android 12 (API 31) and on iOS.
 */
@Composable
expect fun rememberCanScheduleExactAlarms(): Boolean

/**
 * Returns a callback that opens the OS "Alarms & reminders" permission screen
 * for this app on Android 12+ (falling back to app details if unavailable).
 */
@Composable
expect fun rememberExactAlarmSettingsOpener(): () -> Unit
