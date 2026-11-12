#!/usr/bin/env bash
# Actual release target; no debug host and no weakening of minification/signing.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EVIDENCE="${RUNNER_TEMP:-/tmp}/khodroyar-release-smoke"
mkdir -p "$EVIDENCE"
cd "$ROOT/android"
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true
BEFORE=$(sha256sum app/build/outputs/apk/release/app-release.apk | awk '{print $1}')
set +e
./gradlew connectedReleaseAndroidTest -PkhodroyarTestBuildType=release \
  -Pandroid.testInstrumentationRunnerArguments.class=com.khodroyar.app.ui.ReleaseApkSmokeTest \
  --stacktrace 2>&1 | tee "${RUNNER_TEMP:-/tmp}/khodroyar-signed-runtime.log"
RESULT=${PIPESTATUS[0]}
set -e
python3 "$ROOT/tools/annotate-build-errors.py" "${RUNNER_TEMP:-/tmp}/khodroyar-signed-runtime.log"
if ! python3 "$ROOT/tools/summarize-tests.py" "$ROOT/android/app/build/outputs/androidTest-results/connected" "Signed release native OCR tests"; then
  RESULT=1
fi
AFTER=$(sha256sum app/build/outputs/apk/release/app-release.apk | awk '{print $1}')
if [ "$BEFORE" != "$AFTER" ]; then
  echo "::error::Tested release APK differs from the already audited candidate"
  RESULT=1
fi
adb logcat -d -t 400 > "$EVIDENCE/logcat.txt"
adb pull /sdcard/Android/data/com.khodroyar.app/files/release-smoke/. "$EVIDENCE/" || RESULT=1
if [ "$RESULT" -eq 0 ]; then
  echo "::notice title=Signed release runtime verification::Non-debuggable originally signed R8 APK launched, real Persian OCR with radios disabled and actual SAF picker passed; gesture navigation; exact audited APK hash preserved."
fi
exit "$RESULT"
