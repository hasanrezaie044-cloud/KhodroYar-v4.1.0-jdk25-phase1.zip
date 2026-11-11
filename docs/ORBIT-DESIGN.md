# Orbit / مدار — native UI design system

## Design contract

Native Jetpack Compose/Material 3, not a web skin. Cool neutral backgrounds, flat bordered surfaces, a restrained ocean-green default, slate hero panels and purpose-specific semantic accents. Four locally packaged Vazirmatn weights provide consistent Persian shaping without network access. The legacy palette wire values, light/dark/system choice and language preference remain valid.

- `ui/theme/CarManagerTheme.kt`: colour roles, palettes, Persian typography, spacing and shape tokens. `LocalDarkTheme` also governs app system-bar contrast.
- `ui/components/Ui.kt` and `OrbitUi.kt`: flat cards, icon badges, hero metrics, sections, fields, selections, switches, tables and empty states. Existing screen APIs remain compatible.
- `ui/screens/Shell.kt`: theme-aware toolbar, native Material 3 five-destination navigation and toolkit drawer. Existing notifications, overlays, calendar quick entries and return-to-agent routing stay wired.
- `ui/screens/LoginScene.kt`: static vector gate background, secure native password input and fingerprint retry. No perpetual scene clock or hidden status bar. PIN checking and biometric approval stay in `AuthScreens.kt`.

## Screen coverage

Dashboard, calendar, reports, financial overview, loan chart, service registration, settings, assistant conversation, automation workspace, traffic and all shared entry/detail/list screens inherit the new type/surface/field system. Dashboard actions are real navigation, not decorative controls. Financial, calendar and service hero panels use on-dark metrics rather than nested bright tiles. Settings exposes Appearance initially; it never forces a new palette onto a saved preference.

## Chart contract

`ChartGeometry.kt` is pure display geometry with JVM regression tests. It does not change records, money rounding, income calculation or exported aggregates.

1. Zero is included in each scale. Signed values remain signed; empty/zero/nonfinite ranges cannot divide by zero.
2. The same index mapping drives Canvas positions and hit testing. Home explicitly advances time **left to right** even inside the Persian RTL screen; other chart callers can choose their own timeline direction without changing text direction. One point is centred. Sparse axis labels preserve first/last. Home omits duplicate point chips but retains touch and previous/next accessibility actions; other charts keep all selectable captions below.
3. Piecewise cubic controls lie inside each pair's rectangle, so interpolation cannot overshoot its values. Area closes to the actual zero line.
4. A selected-point readout reserves space above the chart. Tapping anywhere across the plot chooses the nearest x position. Screen readers can choose each caption or use previous/next-point actions.
5. Axis K/M/B abbreviations have an explicit key; selected values retain full numbers and units. Installment markers identify the month in which a loan was received, not another payment series.
6. The monthly income goal has its own Home card, not a duplicate line that dwarfs the trend. Other chart callers may provide a goal explicitly; when present it is included in the scale.
7. Donut legend rows contain name, percentage and amount. Empty/invalid/nonpositive slices have no area. Centre/outside/gap taps do not select a slice. Report expenses and income are separate partitions; an aggregate is never drawn alongside its own parts.
8. Financial charts are replaced by a privacy message when masked, including accessibility semantics. No fake unit slice is rendered for empty reports.
9. Short, one-shot fades use Compose animation timing. No chart runtime, native model or continuously animated backdrop was added.

### Shared-screen audit

Fuel, insurance, maintenance (including its tracked-service editor), personnel detail, rates,
offline search and attachment previews retain their repository calls and confirmation flows.
Their forms, field colours, selection controls, cards, headings, metrics and empty states use
the shared Orbit tokens/components, rather than a second screen-specific theme. This is a
source-level coverage audit, not a claim that every secondary workflow was exercised on a phone.

## Phone layout refinement · 5.3.1

- Home no longer subscribes to annual aggregate flows or renders recent service records. These appear in Reports; annual queries start only while that section is open. The same SQL aggregates and latest-five ordering are retained.
- The toolbar keeps only the Home date as secondary text. Automatic tutorial dialogs, drawer slogans/descriptions, settings slogans, duplicate trend amounts, and the duplicate report-total card are removed. Approval, legal/online consent and actionable errors are not removed.
- Standard Material 3 navigation replaces the custom bar, with exactly one selected destination, short registration label, Android navigation-bar insets and IME hiding.
- 16dp page/card spacing, wrapping 48dp chart controls, vertically stacked report dates at narrow/enlarged widths, a horizontal table with readable 48dp rows, and separate service-card amount lines avoid cramped columns on a phone.
- Snapshot 5.3.0 at `673611b` was independently saved before this refinement; the original 5.2.0 checkpoint remains intact.

## Data and safety boundaries

No database migration, permission addition, provider request change, automation change or approval bypass. Package and signing configuration remain the same. PDF/XLSX continue to use the existing aggregation and export implementations. A saved legacy colour choice is preserved; the new Orbit palette is the default for new/unknown choices only. Backup 5.2.0 was verified and delivered before UI work began.

## Validation

- `bash VERIFY.sh`, `git diff --check`, `cd backend && npm test`.
- Real JDK 25 Android compilation, JVM regression tests and signed release/permission/package/version/100 MB gates in `.github/workflows/android-release.yml`.
- New JVM suites: display geometry (20 cases), report partitions (4), palette wire compatibility and text contrast (4, across all 16 light/dark palette combinations).
- Eleven native Compose smoke tests in `src/androidTest`: real repository fixtures and tab/tool navigation, privacy, RTL point selection at 320dp/1.35× text, signed-value LTR/dark chart selection, donut selection, empty states, native password input/biometric callback, explicit terms consent and usable dark navigation at 320dp/enlarged text, Home's left/right selection in both line/bar modes, relocated annual/recent records and narrow-screen table scrolling. First-use preferences are cleared, so tests also exercise navigation without automatic tutorial dialogs. Twenty-four PNGs are captured from the actual Compose surface after frame synchronization, not drawn as mockups. The compact app capture is cropped to its genuine 320dp test surface; the bar capture scrolls the plot into view.
- `.github/workflows/android-release.yml` also calls `android-ui-smoke.yml` from the same ref, so the new workflow can be exercised on an open PR branch before it exists on the default branch. JUnit failures and missing screenshots fail the UI job. The test helper publishes synthetic PNGs to the emulator’s shared Downloads via MediaStore (app-specific directories are removed by UTP); the runner gathers them **before** the emulator shuts down.
- Manual runs also publish seven bounded WebP previews as check notices for visual review when artifact-download hosts are unreachable; these are compressed views of real emulator PNGs, never generated mockups. Notices are split below the checks API’s 4096-character message limit, with at most six pieces per preview. Pillow is a CI-only image tool and is not an Android dependency.
- Fixtures refuse to run on a physical phone; they replace data only in a disposable API 35 emulator. No screenshot contains personal data or credentials.

A successful build or emulator run is not a claim of physical Samsung testing, battery/Doze benchmarking, live Neshan/GapGPT validation or universal accessibility certification. Check 320dp widths, enlarged system text, keyboard visibility, TalkBack, light/dark, RTL/LTR, empty states and privacy on a physical device before store distribution.
