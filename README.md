# مدیریت خودرو — Native Android

## Current release: 5.3.0 (versionCode 58)

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
The signed **whole APK must stay below 100,000,000 bytes** in CI.

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
