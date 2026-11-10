# مدیریت خودرو — Native Android

## Current release: 5.1.10 (versionCode 55)

The assistant now defaults to **Local — no API**: supported Persian commands work on-device
without internet, a server, a model download or a paid account. This is a deterministic
command assistant, **not a general-purpose generative language model**. Try `گزارش این ماه`,
`وضعیت خودرو`, or `راهنما`. Writes still require explicit approval.

Online mode remains optional. It needs the deployed **KhodroYar backend**, not a website or
a direct GapGPT/OpenAI URL. Root, `/v1` and full endpoint URLs are normalised to avoid duplicate
paths and HTTP 404. See [assistant guide](docs/AI-ASSISTANT.md) and [release notes](CHANGES_5.1.10.md).
Backend signing secrets and an AI server/API key are unrelated; local mode needs neither an
AI server nor an AI key. Room stays at schema version 6.

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
