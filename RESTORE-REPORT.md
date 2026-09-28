# Restore / Fix / Improve — phase report

Baseline: `car-management-native-android-audit-final` (native Kotlin + Compose + Room,
Gradle/EAS release pipeline). No rewrite: the architecture, package name
(`com.khodroyar.app`), Room schema (version 1, unchanged), core business logic in
`core/`, exporters, security and build configuration were kept as they were.

## Changed
| File | Change |
|---|---|
| `ui/screens/CarManagerScreens.kt` | Replaced by focused screen files (below). The old single file held placeholder screens: static calendar, typed Jalali dates, no live income, no rate editor, no notifications. |
| `ui/screens/Shell.kt` | New shell: RTL, bottom nav, service sheet host, snackbar/Toast host, privacy-mode provider, reminder re-sync. |
| `ui/screens/CalendarScreen.kt` | New. `selectedDate`-driven interactive Jalali calendar. |
| `ui/screens/ServiceFormSheet.kt` | New. Modal kept, internals reworked, live income, quick mode, edit + delete. |
| `ui/screens/RatesScreen.kt` | New. Full rate engine editor, per-year, Save + Recalculate. |
| `ui/screens/FuelScreen.kt` | New. Type chips, litres → amount computed live. |
| `ui/screens/MaintenanceScreen.kt` | New. Seven preset categories restored. |
| `ui/screens/FinanceScreen.kt` | New. Monthly overview + loans/installments + personal purchases + personal income. |
| `ui/screens/ReportsScreen.kt` | New. Jalali range pickers, 3-sheet Excel, PDF, save-to-device. |
| `ui/screens/SettingsScreen.kt` | New. Backup to device, restore preview, reminders, holidays, personnel, PIN, theme, privacy, goal. |
| `ui/screens/DashboardScreen.kt` | New. Today, 14-day comparison, week, averages, previous month, goal progress, annual stats, oil status, personnel, privacy. |
| `ui/screens/ServicesScreen.kt` | New. Search + type filter + edit entry point. |
| `ui/screens/AuthScreens.kt` | New. Login screen using the supplied artwork; PIN/biometric behaviour unchanged. |
| `ui/components/JalaliPickers.kt` | New. Month grid, date-picker dialog, `JalaliDateField`, `TimeField`. Single Jalali source stays `core/jalali/Jalali.kt`. |
| `ui/components/Ui.kt` | New. Shared cards/fields/chips/collapsible, money formatting via `Analytics.formatNumber`, privacy masking, ToastController. |
| `data/prefs/SettingsStore.kt` | New. Appearance, privacy, goal, reminder switches, expiry dates, backup interval. |
| `notifications/*` | New. Channels, permission check, test notification, daily WorkManager evaluator for all legacy reminder types. |
| `data/backup/BackupFiles.kt` | New. Document-picker write/read (SAF), internal copy, share. |
| `data/repo/AppRepository.kt` | Extended only: `saveRatesForYear`, `recalculateYear`, `countServicesInYear`, `servicesForDate*`, `*ByPrefixOnce`, `loansOnce`, `fuelLiters`, `updateLoan`, `markOverdueInstallments`, settings surface, suggestions + settings in backup, settings restore. |
| `data/db/Daos.kt` | Added `SELECT DISTINCT field FROM suggestions` (additive, no schema change). |
| `data/legacy/LegacyBackup.kt` | Suggestion import accepts any field key (legacy three still work). |
| `MainActivity.kt` | Same structure; theme choice now read from persisted settings (`system` still follows the OS). |
| `CarManagerApp.kt` | `Configuration.Provider` for on-demand WorkManager init (the manifest strips the startup initializer) + channel creation + reminder sync. |
| `android/app/build.gradle` | Added `jsr305` + `error_prone_annotations`; version 4.1.0 (22). R8/minify/shrink untouched. |
| `android/app/proguard-rules.pro` | Keep rules for the worker + `dontwarn` for the annotation artifacts. |
| `app.json` | Icon, adaptive icon and splash now point at the supplied assets. |
| `res/mipmap-*`, `res/drawable-nodpi` | Generated icon + login assets. |

## Restored
Interactive Jalali calendar with month navigation, day selection and per-day data
binding · daily expense breakdown (collapsible, with personal-expense delete) · quick
service registration for the selected day · Jalali date picker on every date field and
a time picker on every time field · live service income with a visible breakdown ·
complete rate engine (km/hour/fuel/toll rates, night & dawn percentages and
boundaries, vehicle type, km intervals) · selectable time-bonus service types
(default night + fixed) · per-year rates with nearest-earlier-year inheritance ·
Save + Recalculate scoped to one year with confirmation and affected count · fuel
amount computed from litres × rate · seven maintenance presets · loan interest,
schedule, due dates, paid state, progress, overdue marking · finance overview with
month navigation, personal purchase and income categories · report date ranges from
the calendar · Excel export with services/fuel/maintenance sheets · backup that
really writes a file to the device (SAF) plus share · restore preview (version, date,
per-type counts) with explicit confirmation · suggestions and app settings inside the
backup · full reminder set (daily/weekly/monthly summaries, installments, oil change,
body insurance, third-party insurance, technical inspection, periodic backup) with
14-day and 3-day expiry windows and a test-notification button · dashboard
comparisons, goal progress, annual stats, oil status · manual holidays with year
grouping · personnel · privacy mode.

## Fixed
Calendar showed only the current month with no selection, so every "daily" figure was
today's · date fields required hand-typed Jalali strings · service form had no live
total, no mission fields, no vehicle, no time pickers, and the save button sat below a
long scroll · fuel amount was typed manually and fuel type was hardcoded `gov` ·
maintenance type was free text, presets gone · loans had no interest, date or
schedule UI and were saved with `interestPercent = 0` · rates were not editable at all
from the UI · reports took the month as free text · backup could only be shared, never
saved · notifications did not exist · suggestions were re-derived from the service list
instead of the suggestions table and were never exported.

## Preserved
`core/jalali`, `core/rates`, `core/finance`, `core/holidays`, `core/analytics`
(untouched) · `export/XlsxWriter`, `export/PdfReportWriter` · `security/PinStore`
(PBKDF2, 120k) and `BiometricGate` · Room entities/DAOs and schema version 1 ·
`LegacyBackup` import paths and JSON shape · `AndroidManifest` permission policy
(only `POST_NOTIFICATIONS` + `USE_BIOMETRIC`; `SYSTEM_ALERT_WINDOW`, all location and
`INTERNET` still hard-removed) · Gradle/EAS/R8 configuration · `MainActivity`
`setContent` structure · golden parity test suite.

## Assets
`icon.png` → `mipmap-*/ic_launcher.png`, `ic_launcher_round.png` (circular),
`ic_launcher_foreground.png` (artwork inside the 72/108dp safe zone) plus
`mipmap-anydpi-v26/ic_launcher(_round).xml` adaptive icons over
`@color/ic_launcher_background` (#05090B), and `assets/icon.png` /
`assets/adaptive-icon.png` for the Expo config. `login-background.png` → converted to
`res/drawable-nodpi/login_background.webp` (1080 px wide, WebP q82, ~49 KB) and used
as the login background; `assets/login-background.png` / `assets/splash.png` kept for
the Expo config. Both images were re-encoded/resized only — no redesign.

## Build
Verified in this environment: Kotlin structure (39 files, balanced, no undeclared
`AppRepository` / component / resource / enum references), every `R.*` reference
resolves, all XML and JSON parse, and the business logic re-run against
`golden.json` (see `parity` section of this report's commit message).

NOT verified here: Gradle assembly. This environment has no Android SDK, no Gradle
distribution and no network, so `:app:assembleRelease`, `:app:bundleRelease` and
`eas build` could not be executed, and no APK/AAB was produced. R8/minify/shrink,
Java 17, EAS profiles and `image: latest` are unchanged from the configuration that
last built successfully. First CI/EAS run is the compile gate for the new UI files.

## One extra fix found while validating
`app/src/test/resources/golden.json` still contained two loan due dates of
`1404/12/30` — a date that does not exist and that only the old, inverted
`isJalaliLeapYear` could produce. `core/finance/LoanCalculator` (correctly) emits
`1404/12/29`, so `GoldenParityTest.loanSchedulesMatchGolden` would have failed the
CI gate before Gradle ever ran. Those two `dueDate` values were regenerated with the
corrected calendar; installment amounts, totals and paid flags were not touched.
Re-running the whole vector set (calendar, month lengths, weekday, addMonths, week
ranges, duration split, hourly breakdown, start bracket, 240 income vectors, loan
schedules, holidays, plus the 36,890-day round-trip, the official leap list and the
addMonths validity sweep) gives **101,510 assertions, 0 failures**.
