# KhodroYar 4.7.1 — Build/Release fixes

## Gradle signing
- Renamed local signing vars (`ksPath`, `ksPassword`, `ksAlias`, `ksKeyPassword`) to avoid Groovy DSL clash with `storePassword` / `keyAlias` methods.

## GitHub Actions
- Removed preflight `./gradlew help` / `build --dry-run` (ran before keystore env was set).
- Removed `assembleDebug` step from release workflow.
- Release job order: secrets validate → decode keystore → `assembleRelease` only.

## Kotlin compile
- ReportsScreen: `tr("جمع کل")` hoisted to `totalLabel` before non-composable `buildWorkbook()`.
- ServiceFormScreen: single `attachmentPath` state declared **before** `pickImage` launcher.

## Unchanged
- applicationId `com.khodroyar.app`
- No debug/fallback signing
- Features, DB, UI logic preserved
