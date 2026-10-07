package com.khodroyar.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Non-record app settings: appearance, privacy mode, monthly income goal, reminder
 * switches and the expiry dates the reminders are derived from, plus the saved
 * "auto service" preset (route/km/hours/times/tolls) used by the quick-fill button
 * and the optional daily auto-registration worker.
 *
 * These lived in AsyncStorage keys in the legacy app (appearance / goals /
 * reminderPrefs / settings). They are intentionally kept OUT of Room: a handful of
 * scalars, read synchronously at first composition, and no schema migration needed.
 *
 * The PIN is NOT stored here — it stays in EncryptedSharedPreferences
 * (security/PinStore) and is never exported, matching legacy behaviour.
 */
data class AppSettings(
    val themeMode: String = THEME_SYSTEM,
    /** Colour theme key - see AppPalette.fromWire. */
    val themeColor: String = THEME_COLOR_DEFAULT,
    val language: String = LANGUAGE_FA,
    /** Owner first name — collected on first launch; used for personal greetings. */
    val userFirstName: String = "",
    /** Owner last name — collected on first launch. */
    val userLastName: String = "",
    /**
     * Live vehicle odometer (کیلومتر فعلی خودرو).
     * Auto-increments when services with km are registered; also manually editable.
     * Base for all five periodic service remaining calculations:
     * remaining = nextReplacementKm - vehicleCurrentKm
     */
    val vehicleCurrentKm: Double = 0.0,
    val oilCurrentKm: Double = 0.0,
    val oilNextKm: Double = 0.0,
    /** Odometer at last timing-belt replacement / next due odometer. */
    val beltCurrentKm: Double = 0.0,
    val beltNextKm: Double = 0.0,
    val sparkCurrentKm: Double = 0.0,
    val sparkNextKm: Double = 0.0,
    val brakeCurrentKm: Double = 0.0,
    val brakeNextKm: Double = 0.0,
    val filterCurrentKm: Double = 0.0,
    val filterNextKm: Double = 0.0,
    /** When true, rental profile: Daily Salary & Leave rates + related service types. */
    val isRental: Boolean = false,
    val privacyMode: Boolean = false,
    val monthlyIncomeGoal: Double = 0.0,
    // reminder switches — each independently switchable
    val remindDailySummary: Boolean = false,
    val remindWeeklySummary: Boolean = false,
    val remindMonthlySummary: Boolean = false,
    val remindInstallments: Boolean = true,
    val remindOilChange: Boolean = true,
    val remindBodyInsurance: Boolean = true,
    val remindVehicleInsurance: Boolean = true,
    val remindInspection: Boolean = true,
    val remindBackup: Boolean = true,
    // expiry dates driving the 14-day / 3-day reminders, Jalali "YYYY/MM/DD"
    val bodyInsuranceDate: String = "",
    val vehicleInsuranceDate: String = "",
    val inspectionDate: String = "",
    val backupIntervalDays: Int = 14,
    val lastBackupAt: Long = 0L,
    // saved "ثبت خودکار" (auto service) preset — filled in once on ServiceFormScreen,
    // then reused either by the quick-fill button or, if autoServiceEnabled, by
    // AutoServiceWorker every day.
    val autoServicePresetSaved: Boolean = false,
    val autoServiceEnabled: Boolean = false,
    val autoServiceType: String = "",
    val autoServiceCarType: String = "",
    val autoServiceOrigin: String = "",
    val autoServiceDestination: String = "",
    /** Passenger / contact name reused by the quick-fill button and the daily worker. */
    val autoServicePassengers: String = "",
    val autoServiceKm: Double = 0.0,
    val autoServiceHours: Double = 0.0,
    val autoServiceStartTime: String = "",
    val autoServiceEndTime: String = "",
    val autoServiceTollCount: Int = 0,
    // run-state bookkeeping only — deliberately excluded from backup, same as
    // lastBackupAt: it describes this device's worker history, not user configuration.
    val autoServiceLastRunDate: String = "",
    /** Named service presets (JSON list of ServicePreset). Max ~8 kept by UI. */
    val servicePresetsJson: String = "[]",
) {
    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val LANGUAGE_FA = "fa"
        const val LANGUAGE_EN = "en"
        const val THEME_COLOR_DEFAULT = "petrol"
    }

    /** Full display name, trimmed. Empty when profile not set yet. */
    val displayName: String
        get() = listOf(userFirstName, userLastName).filter { it.isNotBlank() }.joinToString(" ").trim()

    val hasProfile: Boolean
        get() = userFirstName.isNotBlank()

    val anyReminderEnabled: Boolean
        get() = remindDailySummary || remindWeeklySummary || remindMonthlySummary ||
            remindInstallments || remindOilChange || remindBodyInsurance ||
            remindVehicleInsurance || remindInspection || remindBackup

    /** User-entered odometer at last replacement for a tracked maintenance type. */
    fun currentKmFor(typeWire: String): Double = when (typeWire) {
        "oil-change" -> oilCurrentKm
        "timing-belt" -> beltCurrentKm
        "spark-plug" -> sparkCurrentKm
        "brake-pad" -> brakeCurrentKm
        "fuel-filter" -> filterCurrentKm
        else -> 0.0
    }

    /** User-entered next-due odometer for a tracked maintenance type. */
    fun nextKmFor(typeWire: String): Double = when (typeWire) {
        "oil-change" -> oilNextKm
        "timing-belt" -> beltNextKm
        "spark-plug" -> sparkNextKm
        "brake-pad" -> brakeNextKm
        "fuel-filter" -> filterNextKm
        else -> 0.0
    }

    fun withTrackedKm(typeWire: String, current: Double, next: Double): AppSettings = when (typeWire) {
        "oil-change" -> copy(oilCurrentKm = current, oilNextKm = next)
        "timing-belt" -> copy(beltCurrentKm = current, beltNextKm = next)
        "spark-plug" -> copy(sparkCurrentKm = current, sparkNextKm = next)
        "brake-pad" -> copy(brakeCurrentKm = current, brakeNextKm = next)
        "fuel-filter" -> copy(filterCurrentKm = current, filterNextKm = next)
        else -> this
    }
}

class SettingsStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("car_manager_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val current: AppSettings get() = _state.value

    private fun read(): AppSettings = AppSettings(
        themeMode = prefs.getString(K_THEME, AppSettings.THEME_SYSTEM) ?: AppSettings.THEME_SYSTEM,
        themeColor = prefs.getString(K_THEME_COLOR, AppSettings.THEME_COLOR_DEFAULT) ?: AppSettings.THEME_COLOR_DEFAULT,
        language = prefs.getString(K_LANGUAGE, AppSettings.LANGUAGE_FA) ?: AppSettings.LANGUAGE_FA,
        userFirstName = prefs.getString(K_USER_FIRST, "") ?: "",
        userLastName = prefs.getString(K_USER_LAST, "") ?: "",
        vehicleCurrentKm = prefs.getFloat(K_VEHICLE_KM, 0f).toDouble().let { v ->
            // migrate: if vehicle km never set, seed from oil current
            if (v > 0) v else prefs.getFloat(K_OIL_CURRENT, 0f).toDouble()
        },
        oilCurrentKm = prefs.getFloat(K_OIL_CURRENT, 0f).toDouble(),
        oilNextKm = prefs.getFloat(K_OIL_NEXT, 0f).toDouble(),
        beltCurrentKm = prefs.getFloat(K_BELT_CURRENT, 0f).toDouble(),
        beltNextKm = prefs.getFloat(K_BELT_NEXT, 0f).toDouble(),
        sparkCurrentKm = prefs.getFloat(K_SPARK_CURRENT, 0f).toDouble(),
        sparkNextKm = prefs.getFloat(K_SPARK_NEXT, 0f).toDouble(),
        brakeCurrentKm = prefs.getFloat(K_BRAKE_CURRENT, 0f).toDouble(),
        brakeNextKm = prefs.getFloat(K_BRAKE_NEXT, 0f).toDouble(),
        filterCurrentKm = prefs.getFloat(K_FILTER_CURRENT, 0f).toDouble(),
        filterNextKm = prefs.getFloat(K_FILTER_NEXT, 0f).toDouble(),
        isRental = prefs.getBoolean(K_RENTAL, false),
        privacyMode = prefs.getBoolean(K_PRIVACY, false),
        monthlyIncomeGoal = prefs.getFloat(K_GOAL, 0f).toDouble(),
        remindDailySummary = prefs.getBoolean(K_R_DAILY, false),
        remindWeeklySummary = prefs.getBoolean(K_R_WEEKLY, false),
        remindMonthlySummary = prefs.getBoolean(K_R_MONTHLY, false),
        remindInstallments = prefs.getBoolean(K_R_INSTALL, true),
        remindOilChange = prefs.getBoolean(K_R_OIL, true),
        remindBodyInsurance = prefs.getBoolean(K_R_BODY, true),
        remindVehicleInsurance = prefs.getBoolean(K_R_VEHICLE, true),
        remindInspection = prefs.getBoolean(K_R_INSPECT, true),
        remindBackup = prefs.getBoolean(K_R_BACKUP, true),
        bodyInsuranceDate = prefs.getString(K_D_BODY, "") ?: "",
        vehicleInsuranceDate = prefs.getString(K_D_VEHICLE, "") ?: "",
        inspectionDate = prefs.getString(K_D_INSPECT, "") ?: "",
        backupIntervalDays = prefs.getInt(K_BACKUP_DAYS, 14),
        lastBackupAt = prefs.getLong(K_BACKUP_AT, 0L),
        autoServicePresetSaved = prefs.getBoolean(K_AUTO_SAVED, false),
        autoServiceEnabled = prefs.getBoolean(K_AUTO_ENABLED, false),
        autoServiceType = prefs.getString(K_AUTO_TYPE, "") ?: "",
        autoServiceCarType = prefs.getString(K_AUTO_CARTYPE, "") ?: "",
        autoServiceOrigin = prefs.getString(K_AUTO_ORIGIN, "") ?: "",
        autoServiceDestination = prefs.getString(K_AUTO_DEST, "") ?: "",
        autoServicePassengers = prefs.getString(K_AUTO_PASSENGERS, "") ?: "",
        autoServiceKm = prefs.getFloat(K_AUTO_KM, 0f).toDouble(),
        autoServiceHours = prefs.getFloat(K_AUTO_HOURS, 0f).toDouble(),
        autoServiceStartTime = prefs.getString(K_AUTO_START, "") ?: "",
        autoServiceEndTime = prefs.getString(K_AUTO_END, "") ?: "",
        autoServiceTollCount = prefs.getInt(K_AUTO_TOLL, 0),
        autoServiceLastRunDate = prefs.getString(K_AUTO_LAST_RUN, "") ?: "",
        servicePresetsJson = prefs.getString(K_PRESETS, "[]") ?: "[]",
    )

    private fun write(s: AppSettings) {
        prefs.edit()
            .putString(K_THEME, s.themeMode)
            .putString(K_THEME_COLOR, s.themeColor)
            .putString(K_LANGUAGE, s.language)
            .putString(K_USER_FIRST, s.userFirstName)
            .putString(K_USER_LAST, s.userLastName)
            .putFloat(K_VEHICLE_KM, s.vehicleCurrentKm.toFloat())
            .putFloat(K_OIL_CURRENT, s.oilCurrentKm.toFloat())
            .putFloat(K_OIL_NEXT, s.oilNextKm.toFloat())
            .putFloat(K_BELT_CURRENT, s.beltCurrentKm.toFloat())
            .putFloat(K_BELT_NEXT, s.beltNextKm.toFloat())
            .putFloat(K_SPARK_CURRENT, s.sparkCurrentKm.toFloat())
            .putFloat(K_SPARK_NEXT, s.sparkNextKm.toFloat())
            .putFloat(K_BRAKE_CURRENT, s.brakeCurrentKm.toFloat())
            .putFloat(K_BRAKE_NEXT, s.brakeNextKm.toFloat())
            .putFloat(K_FILTER_CURRENT, s.filterCurrentKm.toFloat())
            .putFloat(K_FILTER_NEXT, s.filterNextKm.toFloat())
            .putBoolean(K_RENTAL, s.isRental)
            .putBoolean(K_PRIVACY, s.privacyMode)
            .putFloat(K_GOAL, s.monthlyIncomeGoal.toFloat())
            .putBoolean(K_R_DAILY, s.remindDailySummary)
            .putBoolean(K_R_WEEKLY, s.remindWeeklySummary)
            .putBoolean(K_R_MONTHLY, s.remindMonthlySummary)
            .putBoolean(K_R_INSTALL, s.remindInstallments)
            .putBoolean(K_R_OIL, s.remindOilChange)
            .putBoolean(K_R_BODY, s.remindBodyInsurance)
            .putBoolean(K_R_VEHICLE, s.remindVehicleInsurance)
            .putBoolean(K_R_INSPECT, s.remindInspection)
            .putBoolean(K_R_BACKUP, s.remindBackup)
            .putString(K_D_BODY, s.bodyInsuranceDate)
            .putString(K_D_VEHICLE, s.vehicleInsuranceDate)
            .putString(K_D_INSPECT, s.inspectionDate)
            .putInt(K_BACKUP_DAYS, s.backupIntervalDays)
            .putLong(K_BACKUP_AT, s.lastBackupAt)
            .putBoolean(K_AUTO_SAVED, s.autoServicePresetSaved)
            .putBoolean(K_AUTO_ENABLED, s.autoServiceEnabled)
            .putString(K_AUTO_TYPE, s.autoServiceType)
            .putString(K_AUTO_CARTYPE, s.autoServiceCarType)
            .putString(K_AUTO_ORIGIN, s.autoServiceOrigin)
            .putString(K_AUTO_DEST, s.autoServiceDestination)
            .putString(K_AUTO_PASSENGERS, s.autoServicePassengers)
            .putFloat(K_AUTO_KM, s.autoServiceKm.toFloat())
            .putFloat(K_AUTO_HOURS, s.autoServiceHours.toFloat())
            .putString(K_AUTO_START, s.autoServiceStartTime)
            .putString(K_AUTO_END, s.autoServiceEndTime)
            .putInt(K_AUTO_TOLL, s.autoServiceTollCount)
            .putString(K_AUTO_LAST_RUN, s.autoServiceLastRunDate)
            .putString(K_PRESETS, s.servicePresetsJson)
            .apply()
        _state.value = s
    }

    fun update(transform: (AppSettings) -> AppSettings) = write(transform(_state.value))

    fun markBackupDone() = update { it.copy(lastBackupAt = System.currentTimeMillis()) }

    /** JSON fragment embedded in the backup file. No PIN, no security material. */
    fun toBackupJson(): String {
        val s = _state.value
        fun b(v: Boolean) = if (v) "true" else "false"
        fun q(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return "{" +
            "\"themeMode\":\"" + s.themeMode + "\"," +
            "\"themeColor\":\"" + s.themeColor + "\"," +
            "\"language\":\"" + s.language + "\"," +
            "\"userFirstName\":" + q(s.userFirstName) + "," +
            "\"userLastName\":" + q(s.userLastName) + "," +
            "\"oilCurrentKm\":" + s.oilCurrentKm + "," +
            "\"oilNextKm\":" + s.oilNextKm + "," +
            "\"beltCurrentKm\":" + s.beltCurrentKm + "," +
            "\"beltNextKm\":" + s.beltNextKm + "," +
            "\"sparkCurrentKm\":" + s.sparkCurrentKm + "," +
            "\"sparkNextKm\":" + s.sparkNextKm + "," +
            "\"brakeCurrentKm\":" + s.brakeCurrentKm + "," +
            "\"brakeNextKm\":" + s.brakeNextKm + "," +
            "\"filterCurrentKm\":" + s.filterCurrentKm + "," +
            "\"filterNextKm\":" + s.filterNextKm + "," +
            "\"privacyMode\":" + b(s.privacyMode) + "," +
            "\"monthlyIncomeGoal\":" + s.monthlyIncomeGoal + "," +
            "\"remindDailySummary\":" + b(s.remindDailySummary) + "," +
            "\"remindWeeklySummary\":" + b(s.remindWeeklySummary) + "," +
            "\"remindMonthlySummary\":" + b(s.remindMonthlySummary) + "," +
            "\"remindInstallments\":" + b(s.remindInstallments) + "," +
            "\"remindOilChange\":" + b(s.remindOilChange) + "," +
            "\"remindBodyInsurance\":" + b(s.remindBodyInsurance) + "," +
            "\"remindVehicleInsurance\":" + b(s.remindVehicleInsurance) + "," +
            "\"remindInspection\":" + b(s.remindInspection) + "," +
            "\"remindBackup\":" + b(s.remindBackup) + "," +
            "\"bodyInsuranceDate\":\"" + s.bodyInsuranceDate + "\"," +
            "\"vehicleInsuranceDate\":\"" + s.vehicleInsuranceDate + "\"," +
            "\"inspectionDate\":\"" + s.inspectionDate + "\"," +
            "\"backupIntervalDays\":" + s.backupIntervalDays + "," +
            "\"autoServicePresetSaved\":" + b(s.autoServicePresetSaved) + "," +
            "\"autoServiceEnabled\":" + b(s.autoServiceEnabled) + "," +
            "\"autoServiceType\":" + q(s.autoServiceType) + "," +
            "\"autoServiceCarType\":" + q(s.autoServiceCarType) + "," +
            "\"autoServiceOrigin\":" + q(s.autoServiceOrigin) + "," +
            "\"autoServiceDestination\":" + q(s.autoServiceDestination) + "," +
            "\"autoServicePassengers\":" + q(s.autoServicePassengers) + "," +
            "\"autoServiceKm\":" + s.autoServiceKm + "," +
            "\"autoServiceHours\":" + s.autoServiceHours + "," +
            "\"autoServiceStartTime\":" + q(s.autoServiceStartTime) + "," +
            "\"autoServiceEndTime\":" + q(s.autoServiceEndTime) + "," +
            "\"autoServiceTollCount\":" + s.autoServiceTollCount + "," +
            "\"servicePresetsJson\":" + q(s.servicePresetsJson) +
            "}"
    }

    /** Applies a restored settings block. Missing keys keep their current value. */
    fun applyBackup(
        themeMode: String?, themeColor: String? = null, language: String?,
        userFirstName: String? = null, userLastName: String? = null,
        oilCurrentKm: Double?, oilNextKm: Double?, privacyMode: Boolean?,
        monthlyIncomeGoal: Double?,
        remindDailySummary: Boolean?, remindWeeklySummary: Boolean?, remindMonthlySummary: Boolean?,
        remindInstallments: Boolean?, remindOilChange: Boolean?, remindBodyInsurance: Boolean?,
        remindVehicleInsurance: Boolean?, remindInspection: Boolean?, remindBackup: Boolean?,
        bodyInsuranceDate: String?, vehicleInsuranceDate: String?, inspectionDate: String?,
        backupIntervalDays: Int?,
        autoServicePresetSaved: Boolean? = null, autoServiceEnabled: Boolean? = null,
        autoServiceType: String? = null, autoServiceCarType: String? = null,
        autoServiceOrigin: String? = null, autoServiceDestination: String? = null,
        autoServicePassengers: String? = null,
        autoServiceKm: Double? = null, autoServiceHours: Double? = null,
        autoServiceStartTime: String? = null, autoServiceEndTime: String? = null,
        autoServiceTollCount: Int? = null,
        servicePresetsJson: String? = null,
    ) = update { c ->
        c.copy(
            themeMode = themeMode ?: c.themeMode,
            themeColor = themeColor ?: c.themeColor,
            language = language ?: c.language,
            userFirstName = userFirstName ?: c.userFirstName,
            userLastName = userLastName ?: c.userLastName,
            oilCurrentKm = oilCurrentKm ?: c.oilCurrentKm,
            oilNextKm = oilNextKm ?: c.oilNextKm,
            privacyMode = privacyMode ?: c.privacyMode,
            monthlyIncomeGoal = monthlyIncomeGoal ?: c.monthlyIncomeGoal,
            remindDailySummary = remindDailySummary ?: c.remindDailySummary,
            remindWeeklySummary = remindWeeklySummary ?: c.remindWeeklySummary,
            remindMonthlySummary = remindMonthlySummary ?: c.remindMonthlySummary,
            remindInstallments = remindInstallments ?: c.remindInstallments,
            remindOilChange = remindOilChange ?: c.remindOilChange,
            remindBodyInsurance = remindBodyInsurance ?: c.remindBodyInsurance,
            remindVehicleInsurance = remindVehicleInsurance ?: c.remindVehicleInsurance,
            remindInspection = remindInspection ?: c.remindInspection,
            remindBackup = remindBackup ?: c.remindBackup,
            bodyInsuranceDate = bodyInsuranceDate ?: c.bodyInsuranceDate,
            vehicleInsuranceDate = vehicleInsuranceDate ?: c.vehicleInsuranceDate,
            inspectionDate = inspectionDate ?: c.inspectionDate,
            backupIntervalDays = backupIntervalDays ?: c.backupIntervalDays,
            autoServicePresetSaved = autoServicePresetSaved ?: c.autoServicePresetSaved,
            autoServiceEnabled = autoServiceEnabled ?: c.autoServiceEnabled,
            autoServiceType = autoServiceType ?: c.autoServiceType,
            autoServiceCarType = autoServiceCarType ?: c.autoServiceCarType,
            autoServiceOrigin = autoServiceOrigin ?: c.autoServiceOrigin,
            autoServiceDestination = autoServiceDestination ?: c.autoServiceDestination,
            autoServicePassengers = autoServicePassengers ?: c.autoServicePassengers,
            autoServiceKm = autoServiceKm ?: c.autoServiceKm,
            autoServiceHours = autoServiceHours ?: c.autoServiceHours,
            autoServiceStartTime = autoServiceStartTime ?: c.autoServiceStartTime,
            autoServiceEndTime = autoServiceEndTime ?: c.autoServiceEndTime,
            autoServiceTollCount = autoServiceTollCount ?: c.autoServiceTollCount,
            servicePresetsJson = servicePresetsJson ?: c.servicePresetsJson,
        )
    }

    companion object {
        private const val K_THEME = "themeMode"
        private const val K_THEME_COLOR = "themeColor"
        private const val K_LANGUAGE = "language"
        private const val K_USER_FIRST = "userFirstName"
        private const val K_USER_LAST = "userLastName"
        private const val K_VEHICLE_KM = "vehicleCurrentKm"
        private const val K_OIL_CURRENT = "oilCurrentKm"
        private const val K_OIL_NEXT = "oilNextKm"
        private const val K_BELT_CURRENT = "beltCurrentKm"
        private const val K_BELT_NEXT = "beltNextKm"
        private const val K_SPARK_CURRENT = "sparkCurrentKm"
        private const val K_SPARK_NEXT = "sparkNextKm"
        private const val K_BRAKE_CURRENT = "brakeCurrentKm"
        private const val K_BRAKE_NEXT = "brakeNextKm"
        private const val K_FILTER_CURRENT = "filterCurrentKm"
        private const val K_FILTER_NEXT = "filterNextKm"
        private const val K_RENTAL = "isRental"
        private const val K_PRIVACY = "privacyMode"
        private const val K_GOAL = "monthlyIncomeGoal"
        private const val K_R_DAILY = "remindDailySummary"
        private const val K_R_WEEKLY = "remindWeeklySummary"
        private const val K_R_MONTHLY = "remindMonthlySummary"
        private const val K_R_INSTALL = "remindInstallments"
        private const val K_R_OIL = "remindOilChange"
        private const val K_R_BODY = "remindBodyInsurance"
        private const val K_R_VEHICLE = "remindVehicleInsurance"
        private const val K_R_INSPECT = "remindInspection"
        private const val K_R_BACKUP = "remindBackup"
        private const val K_D_BODY = "bodyInsuranceDate"
        private const val K_D_VEHICLE = "vehicleInsuranceDate"
        private const val K_D_INSPECT = "inspectionDate"
        private const val K_BACKUP_DAYS = "backupIntervalDays"
        private const val K_BACKUP_AT = "lastBackupAt"
        private const val K_AUTO_SAVED = "autoServicePresetSaved"
        private const val K_AUTO_ENABLED = "autoServiceEnabled"
        private const val K_AUTO_TYPE = "autoServiceType"
        private const val K_AUTO_CARTYPE = "autoServiceCarType"
        private const val K_AUTO_ORIGIN = "autoServiceOrigin"
        private const val K_AUTO_DEST = "autoServiceDestination"
        private const val K_AUTO_PASSENGERS = "autoServicePassengers"
        private const val K_AUTO_KM = "autoServiceKm"
        private const val K_AUTO_HOURS = "autoServiceHours"
        private const val K_AUTO_START = "autoServiceStartTime"
        private const val K_AUTO_END = "autoServiceEndTime"
        private const val K_AUTO_TOLL = "autoServiceTollCount"
        private const val K_AUTO_LAST_RUN = "autoServiceLastRunDate"
        private const val K_PRESETS = "servicePresetsJson"

        @Volatile private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore = instance ?: synchronized(this) {
            instance ?: SettingsStore(context).also { instance = it }
        }
    }
}
