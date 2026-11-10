# مدیریت خودرو — Native Android

## Current release: 5.1.11 (versionCode 56)

The assistant has three **explicit** modes:

- **Local — no API** (default): supported Persian commands, no internet/key/server/model download.
  A deterministic command assistant, **not** a general-purpose language model. Try `راهنما`.
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
account/server. Room stays at schema version 6; writes/deletions still require user approval.

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
