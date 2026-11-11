# Offline Persian ALBERT · 5.5.1 / code 62

## What is actually integrated

`HooshvareLab/albert-fa-zwnj-base-v2`, **not fastText**, not an API call and not a
rule-only parser renamed as a model. The upstream is a pretrained fill-mask model.
The original ALBERT encoder is retained/frozen. A small supervised app-intent head
is trained on original authored examples, then the graph is converted to ONNX and
its shared matrices/embeddings are quantized to int8. The unused mask/pooler heads
are not used for task understanding. This is an encoder classifier, **not a
text-generating LLM**.

Source lock: `tools/offline-albert/source-lock.json`:

- Revision `2315c0d4ddd6bd68f7703c5651978706bf8aff37`, declared **Apache-2.0**.
- Original PyTorch source: 44,903,266 bytes, SHA256
  `6d68f3f623302e00bea37f6cdc4b6c01cb28fbd936367319c2a6ad2a6dd7a6f5`.
- Original SentencePiece model: 857,476 bytes, SHA256
  `903319b1a4a7e58e49383764d33897a7f49784510247d68438e4f3bff25b01f1`.
- Config/card Git blob hashes and byte lengths are pinned too. No remotely
  supplied Python/model code is executed.

CPU runtime: `com.microsoft.onnxruntime:onnxruntime-android:1.23.2`. Tokenization:
unmodified SentencePiece 0.2.1 at
`31646a467d2051eb904e0b45de3a73e91fe1c1e3`, built with the NDK behind a small JNI
adapter. The pinned source archive has a checked SHA256. This does **not** substitute
an approximate JVM tokenizer. Runtime UTF-8 uses bytes, not modified JNI UTF-8.

## Bounded responsibility

Eight fixed labels: expense, fuel, maintenance, saved-preset service/trip,
vehicle/maintenance status, automation health, current/previous period comparison,
and other. The classifier cannot create a tool name, arbitrary Intent, identifier,
amount, date, recurrence schedule, report number or authorization.

Flow:

1. First short typed task → local normalization → original SentencePiece → the
   **actual bundled ALBERT ONNX session**. No chat/record history is model input.
2. Calibrated score and top-two margin, fixed domain allow-list, negation/question/
   hypothetical/compound-input guards → optional domain hint. Low confidence,
   unsupported or >64-token text abstains. The full message is never silently cut
   to fit the model. Short follow-ups for an existing draft bypass reclassification.
3. The prior domain dialogue collects missing fields. Existing explicit domain
   recognition wins over contradictory entry hints. Model-suggested entry wording
   still needs a plausible entry request/performed-action statement.
4. `AgentActionValidator` and the existing allow-listed tools remain authoritative.
   Grounded reads use actual app data. Every write stops at `AgentApprovalGate` and
   a fresh review; a model prediction is never consent. The model cannot auto-run
   unapproved rules, bypass deletion confirmation or modify finance calculations.

The model does not infer oil deadlines. Existing deterministic mileage/history
logic produces remaining distance/eligible approximate dates. Approved rules use
the prior inexact WorkManager scheduling; no model stays running in the background.

## Memory and weak devices

- Lazy load after the first typed local request, not at application startup.
- Uncompressed APK asset is mmap-backed; no second model file is extracted to
  private storage. Asset SHA256/lengths are checked before inference.
- One inference at a time, one intra-op/one inter-op CPU thread, fixed 64-token
  shape, basic graph optimization, memory-pattern/CPU-arena retention disabled.
- Cancellation is checked before/after native execution. A result from a cancelled
  turn cannot reopen a draft or create an approval card.
- Model session and tokenizer are released off-main when the workspace leaves the
  foreground/is disposed or its ViewModel clears. Leaving a screen does **not**
  pause previously approved recurring rules.
- On Android low-RAM/low-memory devices or <384 MiB currently available system
  memory, the model is not loaded; previous deterministic local functionality
  remains. Native/asset/memory failures also fall back. The assistant header labels
  lightweight mode rather than pretending ALBERT ran.
- ARM64, older ARMv7 and x86_64 native libraries are included. This is not a claim
  of compatibility with obsolete 32-bit Intel Android devices.

The memory gate is defensive, **not a measured peak-RAM guarantee**. Int8 file
size is not runtime RAM. API35 x86_64 debug-emulator timings/PSS, when present in
CI, are not Samsung S23 FE, battery, Doze or weak-phone benchmarks. Physical phone
measurements and a live Bazaar-installed-device check remain publication tests.

## Data, evaluation and reproducible build

`tools/offline-albert/intents.json` contains original authored task examples, not
user conversations, app records or scraped private data. Train/validation/test are
separately authored and duplicate normalized text is rejected. Only train adds
polite prefix/suffix variants. There are 522 train rows with those variants,
34 validation examples and 50 heldout examples.

The head uses frozen-encoder CLS features, train-only standardization and a
balanced logistic classifier. Validation selects head regularization plus a
confidence/margin pair with zero accepted validation errors. The authored holdout
is then reported, including mistakes/abstentions. CI fails if raw holdout accuracy
is <70%, fewer than 16 cases are accepted or accepted precision is <90%. **These
small authored-set thresholds are regression gates, not a general Persian
understanding, real-user accuracy or safety certification.** Independent Kotlin
safety/approval tests still matter; a high-confidence negative example can be
misclassified by the raw head.

All installed-app model files are produced once per CI run by
`.github/workflows/offline-albert.yml`, verified and consumed by **both** the signed
release and native tests in that run. Source checkouts use immutable commit SHA,
not a moving branch head. The manifest binds assets to the build-code/dataset/
requirements/source-lock hash and contains each file's bytes/SHA256. Floating-point
training/runtime details can change final bytes; do not assume cross-host bitwise
reproducibility. The source and per-run provenance are verifiable.

Build in a network-enabled Python environment (build-time only):

```sh
python -m venv .venv
. .venv/bin/activate
python -m pip install torch==2.8.0+cpu --index-url https://download.pytorch.org/whl/cpu
python -m pip install -r tools/offline-albert/requirements.txt
python tools/offline-albert/build_model.py \
  --source "$HOME/.cache/khodroyar-albert-source" \
  --output android/.cache/offline-assets/albert
python tools/offline-albert/verify_assets.py
python tools/offline-albert/prepare_sentencepiece.py
# JDK25, SDK36/build-tools36.0.0, NDK28.2.13676358, CMake3.22.1:
cd android
./gradlew testDebugUnitTest assembleDebug
# Existing release signing environment is used for ./gradlew assembleRelease.
```

Alternatively download this exact commit's `khodroyar-offline-albert` CI artifact
into `android/.cache/offline-assets/`, then run verification/native-source
preparation. Do not copy assets from an unrelated run/source revision. Large model,
native-source and build artifacts are intentionally ignored rather than committed
to Git. The GitHub release workflow performs all preparation before compiling.
Bare `assembleRelease` without prepared assets now fails clearly; the installed
APK never downloads them itself. The sandbox may not access the model host, which
is why conversion runs on the existing GitHub CI infrastructure.

On every signed build: total **APK <100,000,000 bytes**, model integrity, all three
ABIs, full licences, 64-bit native ELF 16-KB segment alignment, ZIP page alignment,
package/version, merged permission allow-list and the existing signing identity
are verified. Native tests compare all build-time tokenizer fixtures and actually
run the bundled classifier plus the approval/negative-input boundary. Test
artifacts include authored evaluation, tokenizer parity and debug-emulator evidence;
an older successful release is never evidence for this integration.

## Licences and privacy

Full Apache/MIT/dependency terms, source card and modification notice are bundled
and readable offline in Settings → About → Offline model & licences; see
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). No comprehensive upstream
training-data rights audit, legal opinion or guaranteed marketplace approval is
claimed.

Local inference has no network/telemetry/API key. Existing optional direct GapGPT,
backend and online traffic capabilities remain separately configured/consented.
Do **not** describe the whole app as incapable of network transmission. In
particular the Bazaar listing/privacy copy should be reconciled with those optional
features before publication.

## Cafe Bazaar update notice

Official interface: `com.farsitel.bazaar.IUpdateCheckService`, bound explicitly to
package `com.farsitel.bazaar` with action
`com.farsitel.bazaar.service.UpdateCheckService.BIND`.

- After authentication on foreground open, check the **caller's own package** only.
  Compare numeric `versionCode`, not `versionName` or scraped store text. Only a
  strictly newer code can trigger the dismissible warning. `-1`, same/older version,
  missing/disabled Bazaar, offline/cache/service errors never become fake updates.
- IPC is off-main, bounded by a four-second coroutine timeout. A single daemon
  worker/no queue prevents accumulating stuck Binder threads. Cancellation, failed
  binding, rotation/background, disconnect and timeout unbind in `finally`.
- No polling/worker added. Automatic rechecks are limited to foreground entries
  (30-minute session interval). “Later” snoozes the same code for 24 hours; a still
  newer code/manual check can prompt again. No forced update or automatic install.
- User tap opens `bazaar://details?id=com.khodroyar.app` in Bazaar; if unavailable,
  fallback is exactly **https://cafebazaar.ir/app/com.khodroyar.app**. No arbitrary
  remotely supplied URL. A visible error is retained if neither store nor browser
  can open. Manual check and listing link are in Settings → About.
- Only the package name is supplied to Bazaar, never chat/financial data. No broad
  package query, storage, install-APK, accessibility or new dangerous permission.
- Bazaar's cache can delay a newly approved release. Uploading an unapproved build
  is not proof that users can download it. Already-installed old app versions need
  one initial upgrade containing this feature before future notices can appear.
- Tests simulate a newer/missing Bazaar and browser fallback. They do not prove an
  actual newer Bazaar publication was detected on a physical device.

Official references: [update-check guide](https://developers.cafebazaar.ir/fa/guidelines/feature/update-check),
[AIDL](https://s.cafebazaar.ir/2/resources/aidl/IUpdateCheckService.aidl).
