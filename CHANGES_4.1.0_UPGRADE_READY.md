# KhodroYar 4.1.0 — Upgrade-ready maintenance

This package is based on the previous `vehicle-app-v4.0.1-gradlew-fix.zip` and keeps the native architecture, Room database name, business logic, R8/minified release build, Java 17 and cloud-only Gradle workflow.

## Release identity
- Android applicationId: `com.khodroyar.app` (the existing market v4 package)
- versionName: `4.1.0`
- versionCode: `23` (higher than the previous 22)
- Existing GitHub signing secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

Keeping the same applicationId and signing certificate makes the release eligible to upgrade the existing installation instead of being installed as a second package.

## Requested UI/feature changes
- First-run tutorial for every main tab and every tools-panel destination.
- Home dashboard removes the Reports/Export shortcut and Calendar/Daily-services shortcut.
- Home places Monthly Income Goal and Oil Change status in the main flow.
- Privacy mode on Home now leaves only the core amount/mileage/hours/service-count metrics visible; other dashboard sections are hidden.
- Oil-change tracker has Current Mileage and Next Oil-change Mileage. Current mileage automatically advances from recorded service mileage. When current mileage reaches the configured next mileage, the due/overdue state is shown and the existing reminder system can notify the user.
- Persian and English language selection is available in Settings. Navigation, tools, tutorial and major screen labels switch language and the whole layout switches RTL/LTR immediately.
- Existing collapsed-by-default Settings sections and collapsed Home sections remain intact.
- Existing loan auto-removal when all installments are paid remains intact.
- Existing zero rate defaults, manual work-hours entry, yearly rate isolation, signing workflow, permission audit, and R8 configuration are preserved.

## Compatibility / data
- Room database filename remains `car_management.db`.
- Existing SharedPreferences keys remain unchanged; the new language/oil settings are additive.
- Backup restore accepts old files because the new settings keys are optional.
- No location/GPS, SYSTEM_ALERT_WINDOW or INTERNET permission was added.
