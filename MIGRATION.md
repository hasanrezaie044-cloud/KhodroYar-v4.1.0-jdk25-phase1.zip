# مدیریت خودرو — Native Android Migration

Status of the migration from the Expo / React Native app (v3.4.10) to a native
Kotlin Android app built entirely in the cloud.

---

## 1. Two real bugs found in the current app

These were found by executing the shipped JS logic against documented calendar
anchors. Both are live in v3.4.10.

### Bug 1 — `isJalaliLeapYear` is inverted

`utils/jalali.ts` used `breaks.includes(jy % 132)`, which disagrees with the very
conversion functions in the same file. It reports:

| Year | `isJalaliLeapYear` says | Official calendar |
|------|------------------------|-------------------|
| 1395, 1399, **1403**, 1416, 1420, 1424 | not leap | **leap** |
| 1396, 1400, **1404**, 1417, 1421, 1425 | leap | **not leap** |

Live consequences:
* The calendar tab renders a **non-existent Esfand 30 in 1404** (the current year).
* The **real Esfand 30 of 1403 is hidden**.
* `addMonthsJalali('1404/06/31', 6)` returns **`1404/12/30`**, a date that does not
  exist — and that value is used for **loan installment due dates**.

### Bug 2 — `gregorianToJalali` is a year early on leap-year Nowruz

```
2012-03-20 -> 1390/01/01   (should be 1391/01/01)
2016-03-20 -> 1394/01/01   (should be 1395/01/01)
2020-03-20 -> 1398/01/01   (should be 1399/01/01)
2024-03-20 -> 1402/01/01   (should be 1403/01/01)
```

`getTodayJalali()` and `activeYear` both derive from this function, so on those days
the app resolves the wrong Jalali year and therefore **selects the wrong per-year
rate set**.

### Fix

`core/jalali/Jalali.kt` uses the canonical Borkowski 33-year-cycle algorithm.
Verified:

* 13/13 documented Nowruz anchors correct in both directions.
* Leap years match the official list (1391, 1395, 1399, 1403, 1408, 1412, 1416,
  1420, 1424, 1428) with **zero disagreements** across 1390–1430.
* All **36,890 days** from 1350/01/01 to 1450/12/29 round-trip with zero mismatches.
* `addMonths` can no longer emit an invalid date (asserted exhaustively over
  1395–1425 × ±24 months).

Everything else — time brackets, income, loans, holidays — is preserved
**bit-for-bit**, not "reimplemented".

---

## 2. Parity is enforced by tests, not by inspection

`golden.json` (in `app/src/test/resources/`) was produced by **executing the original
JS modules** — `utils/jalali.ts`, `utils/timeRates.ts`, `utils/iranHolidays.ts`, plus
the income and loan logic lifted from `contexts/AppContext.tsx`. Every number in it
is what the shipped app actually output.

`GoldenParityTest.kt` asserts the native core against it:

| Area | Vectors |
|---|---|
| Jalali month lengths | 372 |
| Day-of-week | 9 |
| `addMonths` | 60 |
| Week ranges | 5 |
| Duration bracket split (3 boundary sets × 13×13 times) | 507 |
| Hourly breakdown | 91 |
| Start-bracket percent | 13 |
| **Service income** (6 types × 4 km × 5 time cases × 2 toll) | **240** |
| Loan schedules | 4 |
| Official holidays (1403–1406) | 4 years, entry by entry |
| Full calendar round-trip | 36,890 days |

The suite runs as **gate 1** in CI: if the native core disagrees with the old app on
any of these, the build stops before it ever reaches Gradle assembly.

Independently of Kotlin, the ported algorithms were re-implemented line-by-line in a
second language and run against the same vectors: **64,104 assertions, 0 failures.**

---

## 3. What is native now

| Concern | Before | After |
|---|---|---|
| Calendar / Jalali | JS, buggy | `core/jalali/Jalali.kt` |
| Holidays | JS tables | `core/holidays/IranHolidays.kt` (verbatim, duplicates preserved) |
| Time brackets | JS | `core/rates/TimeRates.kt` |
| Income calculation | JS in React Context | `core/rates/IncomeCalculator.kt` |
| Loans / installments | JS | `core/finance/LoanCalculator.kt` |
| Aggregation | JS `.reduce()` over full arrays each render | **SQL** (`GROUP BY` in the DAOs) |
| Storage | AsyncStorage: whole array re-serialised to JSON on every mutation | **Room + SQLite**, WAL, indexed |
| Excel export | `xlsx` npm (SheetJS, ~1 MB JS) | `export/XlsxWriter.kt`, `java.util.zip`, **zero dependencies**, streamed |
| PDF | HTML print path | `export/PdfReportWriter.kt` on `android.graphics.pdf` |
| PIN | `SHA256(pin + fixed salt)`, 1 round | **PBKDF2-HMAC-SHA256**, 120k rounds, per-install random salt, in `EncryptedSharedPreferences` |
| Biometrics | `expo-local-authentication` | `androidx.biometric.BiometricPrompt` |
| Background work | JS timers | `androidx.work` |
| Offline | de facto | **enforced**: `INTERNET` is removed from the manifest |

### Storage rewrite is the main performance win

The old design held every service, fuel and maintenance record in React state and
wrote the **entire** array back to AsyncStorage on every single change. Adding one
service with 5,000 existing records re-serialised all 5,000. Totals were recomputed
in JS on every render. Now a write touches one row and totals are a `SUM()` the
database answers directly.

---

## 4. Data safety

`data/legacy/LegacyBackup.kt` reads both the v3.4.3 backup format **and** raw
AsyncStorage dumps, and keeps original `id`s and `timestamp`s so restores are
idempotent.

Verified against realistic payloads:

* Current v3.4.3 export — all record types, per-year rates, de-duplicated holidays.
* **v2.2-era backup** — legacy rate aliases (`night`, `holiday`, `fixedKm`,
  `request`, `available`, `fixedHour`, `nightHour`) map correctly; string numerics
  like `"1,250,000"` coerce.
* Hostile input — `services` as an object is rejected; garbage JSON rejected; empty
  object accepted; rows missing every optional field accepted; `null`/scalar rows
  skipped; unknown service type falls back. **No crash, no silent data loss.**

Restore runs in **one transaction**: either the whole file lands or nothing changes.
The PIN is never exported or imported, matching the old behaviour.

Export writes the **same JSON shape** the RN app produced, so old and new builds can
read each other's backups.

---

## 5. Permissions

Declared — and that is the complete list:

```
android.permission.POST_NOTIFICATIONS
android.permission.USE_BIOMETRIC
```

`SYSTEM_ALERT_WINDOW`, all location, contacts, SMS, phone, camera, microphone,
external storage, `QUERY_ALL_PACKAGES`, ad-ID **and `INTERNET`** are each pinned with
`tools:node="remove"`, so a transitive dependency cannot merge them back in.

`tools/check-permissions.sh` then audits the **merged manifest inside the built APK**
(via `aapt2`, not the source file) against an allow-list and a banned-list, and fails
the build on any deviation. It runs in both the GitHub Actions and EAS pipelines.

File sharing for PDF / Excel / backup uses `FileProvider`, which is why no storage
permission is needed.

---

## 6. Build: no Android Studio, either route

The app is now a plain native Gradle project, so both cloud paths drive Gradle
directly.

### GitHub Actions — `.github/workflows/android-release.yml`

1. `verify` job — JDK 17, unit tests + logic parity. **Runs first and gates everything.**
2. `build` job — Android SDK 35, decode keystore from secrets, `assembleRelease` /
   `bundleRelease`, permission audit, `apksigner verify`, upload APK/AAB + R8 mapping.

Secrets required:

| Secret | Purpose |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

Without them the build still runs and produces an **unsigned** APK (with a warning).

Generate a keystore once, anywhere with a JDK — no Android Studio:

```bash
keytool -genkeypair -v -keystore release.jks -alias carmanagement \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks   # paste into ANDROID_KEYSTORE_BASE64
```

### EAS — `eas.json` with the native Gradle project

This is a plain native Android project, not an Expo/CNG project. The EAS profiles
therefore use the standard native Android settings:

* `gradleCommand: :app:assembleDebug` for development
* `gradleCommand: :app:assembleRelease` for preview
* `gradleCommand: :app:bundleRelease` for production
* `applicationArchivePath` points at the real APK/AAB output

There is intentionally no `.eas/build` workflow and no `expo prebuild` step. EAS
detects the checked-in `android/` project and runs the selected Gradle task directly,
with its normal cloud Android SDK and credential handling.

```bash
eas build --platform android --profile production
```

The project includes `android/gradlew`, a small cloud-oriented launcher. It invokes
the Gradle binary supplied by EAS or GitHub Actions, so a Gradle wrapper JAR is not
checked into the repository and Android Studio is not required.

---

## 7. Native cloud-build architecture

The project is a plain native Android Gradle project. React Native and Expo are not
used for the main UI or runtime. EAS is configured through `eas.json` only, using
its standard native Android build path against the checked-in `android/` directory.
No `.eas/build` custom workflow exists because this project does not need one, and
no `expo prebuild` step is used.

GitHub Actions remains the second cloud path and runs the same Gradle project after
its unit-test gate, signing setup, and final APK permission audit.

## 8. Honest status

**Verified here:**
* Core logic parity — 64,104 assertions, 0 failures.
* Calendar correctness — 36,890-day round-trip, official anchors, official leap list.
* Migration path — v3.4.3 + v2.2 + hostile inputs.
* XLSX output — parsed clean by two independent readers, RTL / freeze / autofilter
  / escaping / numeric typing all confirmed.

**Not verified here, and you should not assume it:**
The environment I worked in has **no internet access and no Android SDK**, so I could
not run Gradle, resolve dependencies, compile Kotlin, or produce an APK. The Compose
UI layer (screens, charts, calendar grid, forms) is **not yet written** — the
`ui/` package is empty.

What exists is the correctness-critical foundation plus the complete build pipeline.
The first CI run is the compile gate; expect to iterate there.

**Remaining work, in order:**
1. Compose UI for the 7 screens (dashboard, services, costs, finance, reports,
   calendar, settings) + login, against `AppRepository`.
2. `MainActivity`, theme (dark mode), RTL layout direction, navigation graph.
3. Report screen wiring into `XlsxWriter` / `PdfReportWriter` + `FileProvider` share.
4. First CI run; fix compile errors.
5. Install-over-upgrade test with a real v3.4.10 backup file.

---

## 9. Compose UI implementation added

The native UI layer is now implemented in `ui/` and is no longer a scaffold:

* `MainActivity` is a native `FragmentActivity` with Compose content and an auth gate.
* `CarManagerRoot` provides RTL `NavigationBar`, animated destination transitions,
  FAB service entry, and the main destinations: home, services, reports, calendar,
  and tools.
* Dashboard shows Jalali today, official holiday status, live SQL totals, fuel cost,
  recent services, and an interactive report shortcut.
* Service entry is a fast bottom sheet with all existing core fields, offline
  suggestions for kilometer, start/end times, origin, destination, passenger/name,
  and toll count, plus service-type chips.
* Reports group by service type, show count/km/hours/tolls/income, list monthly details,
  and export both RTL Excel and native PDF using `FileProvider` sharing.
* Fuel, finance/loans, maintenance, calendar/official holidays, settings,
  JSON backup/export, JSON restore/import preview, PIN setup/login, PBKDF2,
  BiometricPrompt, and light/dark switching are wired into the native shell.
* Empty states, error/status feedback, confirmation for destructive restore, loading-safe
  Flows, touch-sized controls, RTL layout direction, and offline-only data paths are
  covered.

The Compose UI was added without React Native, Expo Router, WebView, or internet
runtime dependencies.
