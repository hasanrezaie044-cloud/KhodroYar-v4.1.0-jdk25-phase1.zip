# KhodroYar 4.1.0 — JDK 25 build migration (Phase 1)

Scope of this change set: **build stack only.** Zero application source files were
touched. `AUDIT → UPGRADE → FIX` is complete for the build system; `MODERNIZE → ADD`
(UI + new features) is Phase 2 and is deliberately not started here — see
"Why the UI work is separate" at the bottom.

## 1. Audit findings

| Area | Finding |
|---|---|
| Size | 43 Kotlin files, 11,272 LOC, single `:app` module |
| Build | AGP 8.7.2, Kotlin 2.0.21, KSP 2.0.21-1.0.25, Gradle 8.11.1, JDK 17 |
| DB | Room 2.6.1, **version 1**, 10 entities, `exportSchema = false`, WAL journal |
| Blocker | Gradle 8.11.1 cannot run on JDK 25 (Java 25 daemon support starts at Gradle 9.1.0) |
| Blocker | AGP 8.7.2 is not compatible with the Gradle 9.x line → AGP 9.x required |
| Blocker | Gradle 9.1–9.3 still print `Kotlin does not yet support 25 JDK target, falling back to JVM_24`; the embedded Kotlin only reaches 2.3.0 in **Gradle 9.4.0** |
| Leftovers | `app.json`, `eas.json`, `package.json`, `assets/` are vestigial Expo files, and `app/build.gradle` still conditionally applies `eas-build.gradle`. **Left in place** per the no-removal rule. |
| Dead code | `MaintenanceOpsDao` is declared in `Daos.kt` but not registered on `AppDatabase`. Harmless. **Left in place.** |
| Note | There is no Insurance entity/screen. "Insurance" exists only as reminder settings in `SettingsStore` + `Notifications`. A real insurance feature is *new work*, not a regression risk. |

## 2. Version matrix chosen (all stable, nothing blind-bumped)

| Component | Was | Now | Why this exact version |
|---|---|---|---|
| JDK | 17 | **25** | Requested. Daemon + toolchain. |
| Gradle | 8.11.1 | **9.4.1** | 9.1.0 = first with Java 25 daemon support; **9.4.0** = first embedding Kotlin 2.3.0, which removes the JVM_24 fallback warning; 9.4.1 = minimum for AGP 9.2. |
| AGP | 8.7.2 | **9.2.0** | Latest *stable* AGP whose minimum Gradle (9.4.1) is a released version. AGP 9.4 was still alpha at time of writing. |
| Kotlin | 2.0.21 | **2.3.10** (via AGP built-in Kotlin) | First line with a real JVM target 25. Supplied by AGP; not declared separately. |
| Compose compiler | 2.0.21 | **2.3.10** | Must track KGP. |
| KSP | 2.0.21-1.0.25 | **2.3.10** | New single-number scheme. 2.3.10 specifically ships *"Fix R-class resolution in KSP when AGP 9 built-in Kotlin is enabled"* — required for this project's Room + AGP 9 combination. |
| compileSdk / buildTools | 35 / 35.0.0 | **36 / 36.0.0** | AGP 9 floor. |
| targetSdk | 35 | **35 (unchanged)** | Raising it changes runtime behaviour (edge-to-edge enforcement etc.). That is a separate, testable change, not something to hide inside a build migration. |
| Room | 2.6.1 | **2.8.4** | Latest stable. Schema and DB version untouched. |

## 3. AGP 9 breaking changes handled

1. **Built-in Kotlin.** AGP 9 applies KGP itself; `org.jetbrains.kotlin.android` is now an
   error alongside the new DSL. Removed from both build files.
2. **`android.kotlinOptions {}` is gone.** `jvmTarget` is deliberately *not* re-declared:
   under built-in Kotlin it defaults to `android.compileOptions.targetCompatibility`, so
   Java and Kotlin targets can no longer drift apart.
3. **`defaultConfig.resourceConfigurations` removed** → `androidResources { localeFilters += ['fa','en'] }`.
   Same effect: only `fa` + `en` resources are packaged.
4. **New DSL / legacy variant API.** Nothing in this project used `applicationVariants` or
   custom build logic, so `android.newDsl=true` (the AGP 9 default) is left on. No opt-out
   flags were added — `android.builtInKotlin=false` / `android.newDsl=false` stop working
   in AGP 10 and would reintroduce the deprecated APIs.
5. **Java toolchain pinned to 25** so a dev machine on a different JDK still produces
   identical bytecode to CI.

## 4. Data safety

No entity, DAO, `@Database` annotation or DB version changed — `Entities.kt`,
`Daos.kt` and `AppDatabase.kt` are **byte-identical** to the originals. Room version stays
at **1**, so there is no migration to run and no path by which existing records can be
touched. `fallbackToDestructiveMigration` is absent and was never added.

## 5. CI / release

`.github/workflows/android-release.yml`:

* JDK 25 Temurin, plus a hard gate that **fails the build** if the runner is not on 25.
* A build-script gate that fails if `VERSION_17` ever reappears or `VERSION_25` disappears.
* Gradle now comes from the repo wrapper (the pinned `gradle-version:` input was removed)
  so CI and local builds cannot drift.
* Added the fail-fast pair recommended for AGP 9 migrations: `./gradlew help` then
  `./gradlew build --dry-run`, before anything expensive runs.
* Added `assembleDebug` as an explicit step (previously only release was compiled).
* Release verification additionally rejects an APK signed with the Android debug key, and
  asserts `mapping.txt` exists, which proves R8 actually ran.
* **Reusable**: `workflow_call` with `ref` / `artifact-name` / `run-tests` inputs, the four
  secrets declared, and `version-name` as an output.
* The four secret names are unchanged and no step prints a secret value.
* Still APK-only. No AAB. Signing config, R8 and resource shrinking untouched.

## 6. Verification

`tools/verify-upgrade.sh <path-to-previous-release>` runs 49 offline checks — build-stack
correctness, AGP 9 compliance, release-identity preservation, and a regression diff that
asserts every original file, all 975 Kotlin top-level declarations, the Room schema, the
manifest and the ProGuard rules survived. Current result: **49/49 pass, 0 declarations lost.**

**What is NOT verified here:** nothing was compiled. Producing a real APK needs the
Android SDK and the Maven/Gradle dependency graph, so the first true proof is the GitHub
Actions run. That is exactly what the new fail-fast steps are positioned to catch.

Two things to expect on that first run:
1. `gradle-wrapper.properties` has **no `distributionSha256Sum`**. The old hash was for
   8.11.1 and a wrong one hard-fails the build. Paste the 9.4.1 hash from
   <https://gradle.org/release-checksums/> to restore wrapper verification.
2. The pinned AndroidX/Compose/Room versions are stable releases, but if any single
   coordinate fails to resolve on the runner, it is a one-line version bump, not a
   design problem.

## 7. Why the UI work is separate

Phase 2 (modern dashboard, Material 3 redesign, reports, fuel/maintenance/insurance
features) rewrites and adds several thousand lines of Compose across 11k LOC. That work
needs a compile-run-fix loop to be trustworthy. Landing it in the same change set as an
AGP 8→9 + JDK 17→25 migration would also make any CI failure ambiguous: build stack or new
code? This phase is intentionally small, reviewable and independently green first.
