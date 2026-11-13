# مدیریت خودرو — Native Android

## Current source: 5.5.7 (versionCode 74), based on restored 5.4.0

Same `com.khodroyar.app`, Room 6, financial formulas and original signing identity:

- **Count-first company reports**: Reports → Company service counts shows a compact
  date/company/app/difference table. Difference is **app − company**: company 2 and
  app 3 on `1405/07/05` gives **+1**, even when all km/hours cells are blank. No
  mandatory metric entry or review checkbox in this flow; row/metric review and source
  pages remain optional dialogs. The report is deliberately absent from Tools.
- **Extraction and verification**: preserve genuine dated rows across month boundaries,
  repeated dates, missing metrics, wrapped/HTML cells and three-digit ordinals. Never
  infer missing company rows from application totals. Explicit daily corrections are
  marked `*`, reversible and stored separately in private draft version 5 (loads 1–5).
  They never create, edit or delete application services. Equal counts do not prove
  OCR coverage or service identity. Source warnings remain available without blocking counts.
- **Online-only image OCR**: no native/offline OCR engine, models or runtime model
  download. FastOCR uses the user's encrypted personal key and explicit consent for
  **each** send. Saved text can be reparsed locally without another upload/charge;
  ordinary records, calculations and the local agent still work offline.
- **Isfahan traffic**: add Shahid Keshvari, Shahid Meysami and Shahid Habibollahi to the
  three existing routes. Sourced endpoints, intermediate waypoints and response-geometry
  checks cover one stated direction of each full named road, not both carriageways or
  point-by-point congestion. These are travel-time estimates, not live-provider validation.
- **Privacy & disclaimer v2**: shared in-app and Bazaar-ready Persian text covers
  responsibility for inputs, incomplete/wrong OCR, unofficial outputs, optional third-party
  services and decisions based on results. Existing v1 acceptance must be renewed explicitly.
- **Cafe Bazaar updates preserved**: asynchronous entry notice and user-triggered Bazaar
  action, Settings → About manual check; no APK self-installer or developer-account access.
  Existing guarded record commands, approvals, charts, exports and IME/navigation safety remain.

Guides: [FastOCR/daily counts (Persian)](docs/FASTOCR-ASSIST.md),
[route sources and limitations](docs/TRAFFIC-ROUTES.md),
[Bazaar-ready privacy policy](docs/PRIVACY-POLICY.fa.md),
[Bazaar publishing/update contract](docs/BAZAAR-UPDATES.md), [changes](CHANGELOG.md),
[third-party notices](THIRD_PARTY_NOTICES.md).

Verification is commit-specific: JVM contracts, native integration with a **mock FastOCR
transport**, narrow/large-font RTL UI, and the exact signed/R8 APK driven through real form
submissions, SAF, consent/cancel and **synthetic text**. The signed test verifies the exact
2/3/+1 example, reversible corrections and blank optional metrics. A CI-only simulated
store tests actual Binder/deep-link routing; this is not live Bazaar publishing or a live
OCR/traffic quality test. No private report/API key enters CI. Release publication waits
for both runtime jobs and original-signature/size/audit/readback checks. The delivered
APK alone must stay **below 30,000,000 bytes**; test/store APKs are never delivered.

## Preserved 5.4.0 baseline (versionCode 60)

Five practical upgrades, on the same native app and Room 6 data:
- **Quick add**: actual-history drafts, frequent recorded amounts, separate review/confirmation,
  reprice repeated trips for their new date, and guarded recent-entry correction/confirmed undo.
- **Local agent**: repeat yesterday's identified expense after fresh approval, compare equal-length
  month/week periods, and inspect persisted automation results. No AI API/model download needed.
- **Reports**: equal-period cash comparison, vehicle-only net/km and net/hour, and the largest
  recorded cost increases with optional details; the existing PDF/XLSX maths stay intact.
- **Maintenance**: remaining mileage, evidence-gated approximate dates, and typed entry actions
  from status cards and reminders. Insufficient history does not produce an invented date.
- **Android polish**: refreshed native surfaces/charts/floating navigation, enlarged-font quick
  forms, bounded SQL reads, a recoverable quick-mileage handoff and point-in-time background
  diagnostics. WorkManager timing is inexact; a late task is not automatically a failed one.

Home's timeline still advances **left to right** in Persian. Annual statistics and the latest
five services stay **only in Reports**. Consent, units, approval/destructive confirmations and
real errors remain; redundant tutorial copy does not return. See the [5.4.0 changes](CHANGES_5.4.0.md)
and the [earlier 5.3.1 layout changes](CHANGES_5.3.1.md).

Build/test evidence is commit-specific. API 35 emulator coverage includes a 12,000-record fixture,
not a physical S23 FE/weak-phone, battery, Doze or memory benchmark. A saved 5.3.1 checkpoint is
retained before these edits. Install a signed update **over** the existing app; do not uninstall.


**Orbit / مدار: a complete native UI refresh**, with bundled Persian Vazirmatn typography,
calm light/dark palettes, redesigned navigation, dashboard, forms, conversation and authentication.
All charts now share a native interactive system: zero-based axes, RTL-aware selection,
full-value readouts, expense/income partitions, honest empty states and privacy masking.
No chart library, downloaded model or continuous login animation was added.

Read the [5.3.0 changes](CHANGES_5.3.0.md), [design and test contract](docs/ORBIT-DESIGN.md)
and [font licence notices](THIRD_PARTY_NOTICES.md). The 5.2.0 agent functionality below is preserved.

**Device-local conversational agent and daily automations**, no AI API key or downloaded LLM.
Short Persian slot-filling conversations, recurring expenses/trips, explicit traffic tools and
allow-listed tab navigation share the real app repositories and approval gate. A task manager
shows pause/resume, amount edits, deletion of rules (not records), schedule and last result.
The signed **whole APK must stay below 30,000,000 bytes** in CI.

Read the [local agent guide](docs/LOCAL-AGENT.md) and [5.2.0 changes](CHANGES_5.2.0.md).
Daily work uses inexact WorkManager windows, not an always-running model. No fabricated missed-day
backfill; stable occurrence IDs + INSERT IGNORE/readback protect retries. Traffic remains an
explicit online Neshan data request requiring its own configured key, not an AI provider call.

The assistant has three **explicit** modes:

- **Local — no AI API** (default): bounded Persian dialogue and approved automations; no
  AI key/server/model download. Expense/service processing is offline; explicitly requested
  traffic data is online. **Not** a general-purpose language model. Try `راهنما`.
- **Direct GapGPT**: the documented OpenAI Chat Completions API at `https://api.gapgpt.app/v1`
  or the user-selected `https://api.gapapi.com/v1` CDN. Needs the owner's real API key and credit.
  The personal key is entered only in-app and stored with Android Keystore-backed encryption,
  never embedded in the APK or included in backups. An explicit connection test sends one
  small, potentially billable completion; screen open does not send it automatically.
- **KhodroYar backend**: optional deployed `backend/`, with the provider key on the server and
  a separate optional client token. Recommended for distributing an app using a server-owned key.

Direct GapGPT uses **`POST /v1/chat/completions` + `choices[0].message.content`**. The custom
backend uses **`POST /v1/chat` + `{content, model}`**. They must not be mixed: entering GapGPT's
host as the custom backend was still causing 404 in 5.1.10 and is now blocked before any request.

See the [assistant guide](docs/AI-ASSISTANT.md), [protocol comparison/release notes](CHANGES_5.1.11.md),
and [API-free local commands](CHANGES_5.1.10.md). Android signing secrets do not provision an AI
account/server. Room stays at schema version 6. Each write/deletion requires user approval;
a recurring rule receives scoped permission once, then runs until paused/deleted/expired.
Turning off chat or deleting history does not revoke approved rules; use the task manager.

Native Kotlin rewrite of the Expo/React Native vehicle-management app, now with the full Compose UI shell.
Built entirely in the cloud — no Android Studio required.

```
android/          native Gradle project (Kotlin, Room, Compose)
  app/src/main/java/com/khodroyar/app/
    core/         calendar, holidays, rate brackets, income, loans, analytics
    data/         Room entities/DAOs, repository, legacy backup importer
    export/       dependency-free XLSX writer, native PDF writer
    security/     PBKDF2 PIN store, BiometricPrompt
    ui/           premium RTL Compose UI, navigation, forms, reports, auth
  app/src/test/   golden-vector parity suite vs. the old JS implementation
.github/workflows/android-release.yml
tools/check-permissions.sh
MIGRATION.md      bugs found, parity evidence, build setup, honest status
```

Start with **MIGRATION.md** — it covers two real bugs found in the current app, how
parity is proven, and exactly what is and is not done.

## Build

```bash
cd android && gradle testDebugUnitTest   # logic parity gate
cd android && gradle assembleRelease     # release APK
```

EAS uses the checked-in `android/` Gradle project directly. It does not use Expo
prebuild or a custom Expo workflow:

```bash
eas build --platform android --profile production
eas build --platform android --profile preview
```

Or push to `main` and let GitHub Actions do it.
