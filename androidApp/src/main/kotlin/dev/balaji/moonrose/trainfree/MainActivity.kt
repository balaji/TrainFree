package dev.balaji.moonrose.trainfree

import android.Manifest
import android.app.LocaleManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import dev.balaji.moonrose.trainfree.db.DbContextHolder
import dev.balaji.moonrose.trainfree.db.DevSeed
import dev.balaji.moonrose.trainfree.db.HealthSyncManager
import dev.balaji.moonrose.trainfree.db.UserSettingsRepository
import dev.balaji.moonrose.trainfree.health.AndroidHealthSyncProvider
import kotlinx.coroutines.launch
import java.util.Locale

private const val PREFS_NAME = "trainfree_prefs"
private const val KEY_NOTIFICATION_PERMISSION_PROMPTED = "notification_permission_prompted"

class MainActivity : ComponentActivity() {
    private val initialNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            lifecycleScope.launch {
                UserSettingsRepository.setNotificationsEnabled(granted)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        sanitizeDefaultLocale()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DbContextHolder.appContext = applicationContext
        HealthSyncManager.register(AndroidHealthSyncProvider(applicationContext))
        maybeSeedTestData()

        setContent {
            App(showDeveloperTools = BuildConfig.ENABLE_TEST_DATA)
        }
        requestInitialNotificationPermissionIfNeeded()
    }

    private fun requestInitialNotificationPermissionIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_PROMPTED, false)) return
        prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_PROMPTED, true).apply()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        initialNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Compose Multiplatform's PluralRuleList.getInstance returns an empty rule
    // list when Locale.current.language is not a recognized CLDR language (e.g.
    // "und", "empty", or blank after a malformed `cmd locale set-app-locales`
    // override), which then throws NoSuchElementException on the first
    // pluralStringResource() call.
    private fun sanitizeDefaultLocale() {
        val isoLanguages = Locale.getISOLanguages().toHashSet()
        fun Locale.isValidAppLocale(): Boolean = language in isoLanguages

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = getSystemService(LocaleManager::class.java)
            val appLocales = localeManager?.applicationLocales
            if (appLocales != null && !appLocales.isEmpty) {
                val validAppLocales = (0 until appLocales.size())
                    .map { appLocales[it] }
                    .filter { it.isValidAppLocale() }
                if (validAppLocales.size != appLocales.size()) {
                    localeManager.applicationLocales = if (validAppLocales.isEmpty()) {
                        LocaleList.getEmptyLocaleList()
                    } else {
                        LocaleList(*validAppLocales.toTypedArray())
                    }
                }
            }
        }
        val currentList = LocaleList.getDefault()
        if (currentList.isEmpty || !currentList[0].isValidAppLocale()) {
            val systemLocales = Resources.getSystem().configuration.locales
            val valid = buildList {
                for (i in 0 until currentList.size()) {
                    val locale = currentList[i]
                    if (locale.isValidAppLocale()) add(locale)
                }
                for (i in 0 until systemLocales.size()) {
                    val locale = systemLocales[i]
                    if (locale.isValidAppLocale() && locale !in this) add(locale)
                }
                if (isEmpty()) add(Locale.US)
            }
            val sanitized = LocaleList(*valid.toTypedArray())
            LocaleList.setDefault(sanitized)
            Locale.setDefault(sanitized[0])
            resources.configuration.setLocales(sanitized)
        }
    }

    // Test harness hook: `am start ... --ei seedTestData <days>` on a fresh
    // debuggable install lands the app on a populated profile (see
    // scripts/run-android.py --seed).
    private fun maybeSeedTestData() {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return
        val days = intent?.getIntExtra("seedTestData", 0) ?: 0
        if (days > 0) DevSeed.seedIfNoProfile(days)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
