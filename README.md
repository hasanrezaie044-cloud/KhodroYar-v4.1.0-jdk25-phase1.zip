# مدیریت خودرو — Native Android

## Current release: 5.5.1 (versionCode 62)

**Service-dialogue correction:** a follow-up such as `ن سرویس شب بود` now revises the
current service's explicit category and rebuilds its approval instead of being rejected
as unrelated. Tonight/last night are dates, not tariff guesses; a 9pm schedule is not
a NIGHT-category correction. Only the requested category changes in an app-owned
preset snapshot; route/km/hours still require real saved fields and fresh approval.
The original preset, rates, database and financial/export calculations are unchanged.
The ALBERT header now distinguishes not-loaded, loaded and lightweight fallback states;
loaded is not a claim that its suggestion decided every message.
See [5.5.1 fixes and limits](CHANGES_5.5.1.md).

**Real offline Persian ALBERT + optional Cafe Bazaar update warning.** The pinned
`HooshvareLab/albert-fa-zwnj-base-v2` encoder now runs on-device with a trained,
quantized app-intent head. It is not fastText, an API wrapper or a general chatbot.
The model only suggests a task domain; existing clarification, validated slots,
grounded tools and fresh approval still control every write. Low-memory devices
retain the clearly labelled lightweight mode. No runtime model download or key.

An official own-package Bazaar service check can show a dismissible warning only
when a greater numeric versionCode is reported. The action opens the existing
**https://cafebazaar.ir/app/com.khodroyar.app** listing, with browser fallback.
Missing Bazaar/cache/network errors do not block startup or invent an update.
Manual check and offline licence notices are under Settings → About.

Read the [5.5.0 changes](CHANGES_5.5.0.md) and [model/build/test contract](docs/OFFLINE-ALBERT.md).
The whole signed APK must remain **below 100,000,000 bytes**, not merely the model.
Source/model provenance, licences, native execution and package/signing/size gates
are checked in CI. No physical-phone RAM/speed or live Bazaar publication claim.

The five 5.4.0 upgrades are preserved, on the same native app and Room 6 data:
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
not a physical S23 FE/weak-phone, battery or Doze benchmark. A verified 5.4.0 source/history
checkpoint was retained before the ALBERT changes (older checkpoints remain too).
Install a signed update **over** the existing app; do not uninstall.


**Orbit / مدار: a complete native UI refresh**, with bundled Persian Vazirmatn typography,
calm light/dark palettes, redesigned navigation, dashboard, forms, conversation and authentication.
All charts now share a native interactive system: zero-based axes, RTL-aware selection,
full-value readouts, expense/income partitions, honest empty states and privacy masking.
No chart library or continuous login animation was added. The 5.5.0 ALBERT assets
are bundled at build time, not downloaded on the installed device.

Read the [5.3.0 changes](CHANGES_5.3.0.md), [design and test contract](docs/ORBIT-DESIGN.md)
and [font licence notices](THIRD_PARTY_NOTICES.md). The 5.2.0 agent functionality below is preserved.

**Device-local conversational agent and daily automations**, no AI API key or downloaded LLM.
Short Persian slot-filling conversations, recurring expenses/trips, explicit traffic tools and
allow-listed tab navigation share the real app repositories and approval gate. A task manager
shows pause/resume, amount edits, deletion of rules (not records), schedule and last result.
The signed **whole APK must stay below 100,000,000 bytes** in CI.

Read the [local agent guide](docs/LOCAL-AGENT.md) and [5.2.0 changes](CHANGES_5.2.0.md).
Daily work uses inexact WorkManager windows, not an always-running model. No fabricated missed-day
backfill; stable occurrence IDs + INSERT IGNORE/readback protect retries. Traffic remains an
explicit online Neshan data request requiring its own configured key, not an AI provider call.

The assistant has three **explicit** modes:

- **Local — no AI API** (default): bundled Persian ALBERT intent classification, bounded
  dialogue and approved automations; no AI key/server/runtime model download. Expense/service
  processing is offline; explicitly requested traffic data is online. **Not** a general-purpose
  chatbot. Low-memory/native failures retain the prior lightweight assistant. Try `راهنما`.
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

Prepare the pinned model assets and native tokenizer source first, following
[docs/OFFLINE-ALBERT.md](docs/OFFLINE-ALBERT.md). The GitHub signed-release workflow
already does this, using the existing signing secrets. Then:

```bash
cd android
./gradlew testDebugUnitTest   # logic parity and safety gate
./gradlew assembleRelease    # existing release signing environment required
```

Bare local/EAS builds without the prepared `android/.cache/offline-assets/albert/`
will fail clearly rather than distribute a model-free APK labelled as ALBERT.

EAS uses the checked-in `android/` Gradle project directly. It does not use Expo
prebuild or a custom Expo workflow:

```bash
eas build --platform android --profile production
eas build --platform android --profile preview
```

Or push to `main` and let GitHub Actions do it.
