# مدیریت خودرو — Native Android

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

## Current release and offline reconciliation

The source of truth for the release version is `android/version.properties`, not the
repository/archive name. See `docs/version-lineage.json` for the inspected source and
previous release lineage. The original lightweight command assistant is restored from
pre-ALBERT history; the existing optional online assistant and traffic features remain.
No ALBERT/ONNX/SentencePiece runtime or language model is bundled.

**مغایرت‌گیری کارکرد** is an additional Persian RTL tool. It compares actual, read-only
Room service records with reviewed statement rows by Jalali day, service count,
kilometres and hours. See [the workflow, OCR setup and limitations](docs/OFFLINE-RECONCILIATION.md).
The optional `android/ocr` companion is signed with the same identity and has no network
permission. Its verified Persian language data is provisioned separately.

## Build

JDK 25, Android SDK 36 / build-tools 36.0.0 and the checked-in Gradle 9.4.1 wrapper are required.

```bash
bash VERIFY.sh                          # static checks only; not a build
cd android
./gradlew :app:testDebugUnitTest         # includes golden parity, restored assistant and reconciliation
./gradlew :app:compileDebugKotlin :ocr:compileDebugKotlin
./gradlew :app:assembleRelease :ocr:assembleRelease
```

Release builds require the **existing** `ANDROID_KEYSTORE_PATH`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` environment
variables. There is no debug-key fallback and no replacement signing identity.
Do not commit a keystore or credentials.

The existing GitHub Actions release workflow supports manual dispatch on the working
branch. It compiles Kotlin/KSP/Compose, runs JVM and backend tests, builds signed/R8
APKs, checks merged permissions, package/version, certificate continuity against real
previous APKs, exact APK sizes, embedded credential patterns, Android lint, and native
Persian OCR/document-import instrumentation on a disposable emulator. Consult the
actual run/evidence for success or failure; source inspection alone is not a build result.
