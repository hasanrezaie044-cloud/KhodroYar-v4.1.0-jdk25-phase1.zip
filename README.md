# مدیریت خودرو — Native Android

## Current source: 5.6.2 (versionCode 78), incrementally upgraded from 5.6.1 / 5.5.8

Same `com.khodroyar.app`, Room 6, financial formulas and original signing identity:

- **Predictable, user-selected AI**: ordinary chat never evaluates models. A cold greeting
  makes one normal inference POST, with a short prompt, no old history/tool schemas and
  no automatic fallback. Connection checks are metadata-only. Optional manual evaluation
  requires a separate request/token warning and confirmation (one model, at most four
  synthetic requests). No live account/credits are used in development or tests.
  Durable install-wide request/input/output/token budgets, bounded agent/tool turns,
  staged native schemas, shorter history and key-free usage/latency diagnostics guard
  dispatch. Auth/billing/quota/region failures are never retried or routed around.
- **Explicit providers, truthful free conditions**: OpenRouter retains fresh all-zero
  pricing, key guardrails, endpoints and zero-price routing caps. Groq, native Gemini
  and Cloudflare Workers AI have fixed official adapters with conditional free-account,
  model and region gates; no provider changes automatically. Free quota is not free
  inference on a paid account. Gemini Free app-data tools are blocked for privacy.
  Cerebras, Hugging Face and Iranian alternatives are shown with payment/unverified
  reasons, not falsely activated as no-card-free. See the [current AI guide](docs/AI-MODEL-MANAGEMENT.fa.md)
  and [official-source provider matrix](docs/AI-PROVIDERS.fa.md). Mock compatibility is
  not live account, Persian quality, billing-plan or Iran-access verification.
- **Existing native-tool agent**: all previous typed app tools, approvals, snapshots,
  transactions, readback and quick-mileage journal remain integrated. A model can only
  propose a change; the app owns every explicit approval and verified result. Individual/
  bulk services, genuine stored rates/Jalali calendars, financial comparisons, read-only
  company-report analysis, insurance/maintenance and offline/manual work are unchanged.
  No backend is required. Metadata caches are at most 60 seconds; six-hour OpenRouter
  background work is metadata-only, never an inference/evaluation. See the [historical
  tool/security architecture](docs/OPENROUTER-AGENT.fa.md).
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
- **Traffic compatibility restored**: all six roads use the working 5.5.6 Neshan
  request (`type=car`, origin, destination) and the exact old leg duration/distance
  parser. The 5.5.7 waypoint parameters and mandatory polyline/geometry checks are
  removed. Original routes and the sourced endpoints/directions of Keshvari, Meysami
  and Habibollahi stay. Neshan chooses the actual route between those endpoints;
  this is an estimated travel time, not point-by-point highway congestion. A native
  mocked-transport test covers all six visible statuses and manual refresh using
  the old reply shape without geometry. No personal key or live Neshan call is used.
- **Privacy & disclaimer v5**: shared in-app and Bazaar-ready Persian text covers
  responsibility for inputs, incomplete/wrong OCR, unofficial outputs, optional third-party
  services and decisions based on results. Previous acceptance must be renewed explicitly; each provider/key needs renewed provider/data consent; keys stay Keystore-encrypted and history is provider/account-scoped.
- **Cafe Bazaar updates preserved**: asynchronous entry notice and user-triggered Bazaar
  action, Settings → About manual check; no APK self-installer or developer-account access.
  Existing guarded record commands, approvals, charts, exports and IME/navigation safety remain.
- **Full-screen assistant**: no app bottom tabs, oversized status card or workspace bars on
  chat. New chat/history/clear/automation/sections and integrated AI/model settings live
  behind the gear. Back and all other app navigation remain available.

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

The current assistant has two **explicit** choices:

- **OpenRouter — verified free only** (initial choice for new/legacy-online settings):
  enter your own key only in the Keystore-encrypted settings. No request before fresh consent;
  the explicit connection test uses a synthetic native tool probe, not financial records.
  Only freshly verified `openrouter/free` / `:free` tool-capable models are selectable/requested.
  No credit purchase, subscription, automatic model substitution or paid fallback exists.
  Availability, provider data policies and daily limits remain under OpenRouter's control.
- **Local — no AI API**: preserved bounded Persian commands and approved automations; no
  AI key/server/model download. Explicitly selected local mode remains local on upgrade.
  Expense/service processing is offline; explicitly requested Neshan traffic is online.
  This mode is **not** a general-purpose language model. Try `راهنما`.

Legacy GapGPT/backend protocol classes, keys and historical chats are retained for compatibility,
but are not active choices or fallback providers in the new free-only chat. The `backend/`
folder and its regression tests are preserved; users do **not** need to deploy it.
Read [current agent setup and limits](docs/OPENROUTER-AGENT.fa.md) and
[API-free local commands](docs/LOCAL-AGENT.md). Room stays at schema version 6.
Each create/edit/delete requires a fresh exact preview and approval; a recurring rule receives
scoped permission once, then runs until paused/deleted/expired.
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
