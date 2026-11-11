# Orbit / مدار — native UI design system

## Design contract

Native Jetpack Compose/Material 3, not a web skin. Cool neutral backgrounds, flat bordered surfaces, a restrained ocean-green default, slate hero panels and purpose-specific semantic accents. Four locally packaged Vazirmatn weights provide consistent Persian shaping without network access. The legacy palette wire values, light/dark/system choice and language preference remain valid.

- `ui/theme/CarManagerTheme.kt`: colour roles, palettes, Persian typography, spacing and shape tokens. `LocalDarkTheme` also governs app system-bar contrast.
- `ui/components/Ui.kt` and `OrbitUi.kt`: flat cards, icon badges, hero metrics, sections, fields, selections, switches, tables and empty states. Existing screen APIs remain compatible.
- `ui/screens/Shell.kt`: theme-aware toolbar, anchored five-destination navigation and toolkit drawer. Existing notifications, overlays, calendar quick entries and return-to-agent routing stay wired.
- `ui/screens/LoginScene.kt`: static vector gate background, secure native password input and fingerprint retry. No perpetual scene clock or hidden status bar. PIN checking and biometric approval stay in `AuthScreens.kt`.

## Screen coverage

Dashboard, calendar, reports, financial overview, loan chart, service registration, settings, assistant conversation, automation workspace, traffic and all shared entry/detail/list screens inherit the new type/surface/field system. Dashboard actions are real navigation, not decorative controls. Financial, calendar and service hero panels use on-dark metrics rather than nested bright tiles. Settings exposes Appearance initially; it never forces a new palette onto a saved preference.

## Chart contract

`ChartGeometry.kt` is pure display geometry with JVM regression tests. It does not change records, money rounding, income calculation or exported aggregates.

1. Zero is included in each scale. Signed values remain signed; empty/zero/nonfinite ranges cannot divide by zero.
2. The same chronological index mapping drives Canvas positions and hit testing. RTL starts at the right; LTR at the left. One point is centred. Sparse axis labels preserve first/last; **all** points have selectable captions below.
3. Piecewise cubic controls lie inside each pair's rectangle, so interpolation cannot overshoot its values. Area closes to the actual zero line.
4. A selected-point readout reserves space above the chart. Tapping anywhere across the plot chooses the nearest x position. Screen readers can choose each caption or use previous/next-point actions.
5. Axis K/M/B abbreviations have an explicit key; selected values retain full numbers and units. Installment markers identify the month in which a loan was received, not another payment series.
6. Monthly income goal only appears next to a monthly income series. It is included in that chart's scale.
7. Donut legend rows contain name, percentage and amount. Empty/invalid/nonpositive slices have no area. Centre/outside/gap taps do not select a slice. Report expenses and income are separate partitions; an aggregate is never drawn alongside its own parts.
8. Financial charts are replaced by a privacy message when masked, including accessibility semantics. No fake unit slice is rendered for empty reports.
9. Short, one-shot fades use Compose animation timing. No chart runtime, native model or continuously animated backdrop was added.

## Data and safety boundaries

No database migration, permission addition, provider request change, automation change or approval bypass. Package and signing configuration remain the same. PDF/XLSX continue to use the existing aggregation and export implementations. A saved legacy colour choice is preserved; the new Orbit palette is the default for new/unknown choices only. Backup 5.2.0 was verified and delivered before UI work began.

## Validation

- `bash VERIFY.sh`, `git diff --check`, `cd backend && npm test`.
- Real JDK 25 Android compilation, JVM regression tests and signed release/permission/package/version/100 MB gates in `.github/workflows/android-release.yml`.
- New JVM suites: display geometry (20 cases), report partitions (4), palette wire compatibility and text contrast (4, across all 16 light/dark palette combinations).
- Eight native Compose smoke tests in `src/androidTest`: real repository fixtures and tab/tool navigation, privacy, RTL point selection at 320dp/1.35× text, signed-value LTR/dark chart selection, donut selection, empty states, native password input/biometric callback, explicit terms consent and usable dark navigation at 320dp/enlarged text. Nineteen PNGs are captured by Android's screenshot API, not drawn as mockups.
- `.github/workflows/android-release.yml` also calls `android-ui-smoke.yml` from the same ref, so the new workflow can be exercised on an open PR branch before it exists on the default branch. JUnit failures and missing screenshots fail the UI job. The test helper publishes synthetic PNGs to the emulator’s shared Downloads via MediaStore (app-specific directories are removed by UTP); the runner gathers them **before** the emulator shuts down.
- Fixtures refuse to run on a physical phone; they replace data only in a disposable API 35 emulator. No screenshot contains personal data or credentials.

A successful build or emulator run is not a claim of physical Samsung testing, battery/Doze benchmarking, live Neshan/GapGPT validation or universal accessibility certification. Check 320dp widths, enlarged system text, keyboard visibility, TalkBack, light/dark, RTL/LTR, empty states and privacy on a physical device before store distribution.
