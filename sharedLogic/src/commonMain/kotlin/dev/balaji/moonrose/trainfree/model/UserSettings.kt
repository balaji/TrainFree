package dev.balaji.moonrose.trainfree.model

import dev.balaji.moonrose.trainfree.backup.DriveErrorKind
import dev.balaji.moonrose.trainfree.calendar.WeekStart
import dev.balaji.moonrose.trainfree.insights.BmiClassification
import dev.balaji.moonrose.trainfree.insights.BmiFormula
import dev.balaji.moonrose.trainfree.insights.InsightMath
import dev.balaji.moonrose.trainfree.insights.OneRmFormula

/**
 * App color scheme. Persisted as the lowercase [value] string in
 * user_settings.
 */
enum class Appearance(val value: String) {
    LIGHT("light"),
    DARK("dark"),

    /** Follow the OS appearance. */
    SYSTEM("system"),
    ;

    companion object {
        fun fromValue(value: String?): Appearance = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

/**
 * App typeface option (used on Android). Persisted as the lowercase [value]
 * string in user_settings.
 */
enum class AppFont(val value: String) {
    SYSTEM_UI("system_ui"),
    GOOGLE_SANS("google_sans"),
    INTER("inter"),
    ;

    companion object {
        fun fromValue(value: String?): AppFont = entries.firstOrNull { it.value == value } ?: SYSTEM_UI
    }
}

/**
 * Proportional font-size multipliers for [AppFontSize].
 *
 * Active preset (Option 3 — +1.5pt / +3pt on 16.sp base body text):
 * - [FONT_SIZE_LARGER_SCALE] = 1.10f
 * - [FONT_SIZE_LARGEST_SCALE] = 1.20f
 *
 * Alternative preset to experiment with (Option 1 — +2pt / +4pt on 16.sp base body text):
 * - [FONT_SIZE_LARGER_SCALE] = 1.125f
 * - [FONT_SIZE_LARGEST_SCALE] = 1.25f
 */
const val FONT_SIZE_DEFAULT_SCALE = 1.0f
const val FONT_SIZE_LARGER_SCALE = 1.10f
const val FONT_SIZE_LARGEST_SCALE = 1.20f

/**
 * App font-size scale option (used on Android). Persisted as the lowercase
 * [value] string in user_settings.
 */
enum class AppFontSize(val value: String, val scaleFactor: Float) {
    DEFAULT("default", FONT_SIZE_DEFAULT_SCALE),
    LARGER("larger", FONT_SIZE_LARGER_SCALE),
    LARGEST("largest", FONT_SIZE_LARGEST_SCALE),
    ;

    companion object {
        fun fromValue(value: String?): AppFontSize = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}

/**
 * Weight and distance display units. Persisted as the lowercase [value] string
 * in user_settings and on recorded work-set, session-set, and body-metric rows
 * so each logged number keeps the unit it was entered in and converts cleanly
 * whenever the display preference changes.
 */
enum class WeightUnit(val value: String) {
    METRIC("metric"),
    IMPERIAL("imperial"),
    ;

    /** A weight the user typed in this unit, expressed in kilograms. */
    fun toKilograms(value: Double): Double = if (this == IMPERIAL) value * KG_PER_LB else value

    /** A weight in kilograms, expressed in this unit for display. */
    fun fromKilograms(kg: Double): Double = if (this == IMPERIAL) kg / KG_PER_LB else kg

    /** Converts a weight recorded in `this` unit into [target]. */
    fun convertWeight(value: Double, target: WeightUnit): Double =
        if (this == target) value else target.fromKilograms(toKilograms(value))

    /** A distance the user typed in this unit, expressed in kilometers. */
    fun toKilometers(value: Double): Double = if (this == IMPERIAL) value * KM_PER_MILE else value

    /** A distance in kilometers, expressed in this unit for display. */
    fun fromKilometers(km: Double): Double = if (this == IMPERIAL) km / KM_PER_MILE else km

    /** Converts a distance recorded in `this` unit into [target]. */
    fun convertDistance(value: Double, target: WeightUnit): Double =
        if (this == target) value else target.fromKilometers(toKilometers(value))

    companion object {
        private const val KG_PER_LB = 0.45359237
        private const val KM_PER_MILE = 1.609344

        fun fromValue(value: String?): WeightUnit = entries.firstOrNull { it.value == value } ?: METRIC

        /**
         * Formats [value] recorded in [from] for display in [to]. Preserves the
         * exact recorded number when no conversion is needed; otherwise rounds to
         * 1 decimal place.
         */
        fun formatWeight(value: Double, from: WeightUnit, to: WeightUnit): String =
            if (from == to) PlateMath.formatWeight(value) else InsightMath.formatNumber(from.convertWeight(value, to), 1)

        /**
         * Formats [value] recorded in [from] for display in [to]. Preserves the
         * exact recorded number when no conversion is needed; otherwise rounds to
         * 1 decimal place.
         */
        fun formatDistance(value: Double, from: WeightUnit, to: WeightUnit): String =
            if (from == to) PlateMath.formatWeight(value) else InsightMath.formatNumber(from.convertDistance(value, to), 1)
    }
}

/**
 * Sound played when the between-set rest timer finishes. Persisted as the
 * lowercase [value] string in user_settings. The named tones are Apache-2.0
 * AOSP notification sounds bundled on both platforms.
 */
enum class RestTimerSound(val value: String) {
    /** Silent — vibrate only, no audio chime. */
    NONE("none"),
    /** Platform's default notification/alert chime. */
    DEFAULT("default"),
    PROXIMA("proxima"),
    MIRA("mira"),
    ANTARES("antares"),
    ALTAIR("altair"),
    DENEB("deneb"),
    ;

    companion object {
        fun fromValue(value: String?): RestTimerSound = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}

const val MIN_REST_TIMER_SECONDS = 60
const val MAX_REST_TIMER_SECONDS = 300
const val REST_TIMER_STEP_SECONDS = 30
const val DEFAULT_REST_TIMER_SECONDS = 120

/** Snaps [seconds] to the nearest valid 30-second step within 60..300. */
fun clampRestTimerSeconds(seconds: Int): Int {
    val clamped = seconds.coerceIn(MIN_REST_TIMER_SECONDS, MAX_REST_TIMER_SECONDS)
    val offset = clamped - MIN_REST_TIMER_SECONDS
    val snappedOffset = ((offset + REST_TIMER_STEP_SECONDS / 2) / REST_TIMER_STEP_SECONDS) * REST_TIMER_STEP_SECONDS
    return (MIN_REST_TIMER_SECONDS + snappedOffset).coerceIn(MIN_REST_TIMER_SECONDS, MAX_REST_TIMER_SECONDS)
}

/**
 * One user-configured progression card on the Progress tab: which exercises
 * its chart overlays and how its y-axis scales. Persisted in user_settings.
 */
data class ProgressionCardConfig(
    /** 1–3 exercise names, chart order. */
    val exercises: List<String>,
    /** Chart y-axis mode: % change from each series' first week vs raw weight. */
    val showPctChange: Boolean = false,
)

/**
 * Typed view of the user_settings key-value table. Absent or unrecognized
 * rows yield these defaults, so no rows are ever seeded.
 */
data class UserSettings(
    val weekStart: WeekStart = WeekStart.SUNDAY,
    val appearance: Appearance = Appearance.SYSTEM,
    val weightUnit: WeightUnit = WeightUnit.METRIC,
    /** Formula used everywhere an estimated 1RM is shown or ranked. */
    val oneRmFormula: OneRmFormula = OneRmFormula.EPLEY,
    /** Formula used everywhere a BMI is computed or shown. */
    val bmiFormula: BmiFormula = BmiFormula.STANDARD,
    /** Cutoffs used everywhere a BMI is categorized or banded. */
    val bmiClassification: BmiClassification = BmiClassification.WHO,
    /**
     * Whether the user opted in to notifications. Off by default; enabling
     * it triggers the OS permission prompt. Controls whether a background
     * push notification is sent when the between-set rest timer finishes.
     */
    val notificationsEnabled: Boolean = false,
    /**
     * Whether the one-time prompt to allow Android exact alarms has already
     * been shown when starting a rest timer with notifications enabled.
     */
    val exactAlarmPrompted: Boolean = false,
    /** Whether live completed workouts are written to Apple Health / Health Connect. */
    val healthSyncWorkouts: Boolean = false,
    /** Whether body weight is synced two-way with Apple Health / Health Connect. */
    val healthSyncWeight: Boolean = false,
    /** Duration in seconds of the between-set rest timer (60..300 in 30s steps). */
    val restTimerDurationSeconds: Int = DEFAULT_REST_TIMER_SECONDS,
    /** Audio tone played when the between-set rest timer reaches zero. */
    val restTimerSound: RestTimerSound = RestTimerSound.DEFAULT,
    /** App typeface option (used on Android). */
    val appFont: AppFont = AppFont.SYSTEM_UI,
    /** App font-size scale option (used on Android). */
    val appFontSize: AppFontSize = AppFontSize.DEFAULT,
    /**
     * Progression cards the user configured on the Progress tab, display
     * order. Empty means "never configured" — the Progress tab then defaults
     * to one card charting the top-volume exercise.
     */
    val progressionCards: List<ProgressionCardConfig> = emptyList(),
    /** Whether workouts/journal changes are automatically backed up to Google Drive. */
    val googleDriveSyncEnabled: Boolean = false,
    /** Google account the Drive backup is connected to, when known. */
    val googleDriveAccountEmail: String? = null,
    /** Epoch millis of the last successful Drive upload. */
    val googleDriveLastSyncedAt: Long? = null,
    /** Whether local changes are waiting to be uploaded to Drive. */
    val googleDrivePendingSync: Boolean = false,
    /** Most recent Drive failure, cleared on the next successful upload. */
    val googleDriveLastError: DriveErrorKind? = null,
)
