#!/usr/bin/env bash
# Called while the ephemeral emulator is still alive; preserve the Gradle exit code.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCREENSHOTS="${RUNNER_TEMP:-/tmp}/orbit-ui-screenshots"
mkdir -p "$SCREENSHOTS"
cd "$ROOT/android"
# Stop BEFORE installing/uninstalling anything if a real phone is attached.
HARDWARE=$(adb shell getprop ro.hardware | tr -d '\r')
case "$HARDWARE" in
  ranchu|goldfish) ;;
  *) echo "::error::Synthetic tests require a disposable emulator; refusing to install/reset on this device"; exit 2 ;;
esac
# Exercise actual software IME rather than relying on a hidden hardware keyboard.
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true
set +e
./gradlew :app:connectedDebugAndroidTest --stacktrace 2>&1 | tee "${RUNNER_TEMP:-/tmp}/khodroyar-ui-tests.log"
RESULT=${PIPESTATUS[0]}
set -e
python3 "$ROOT/tools/annotate-build-errors.py" "${RUNNER_TEMP:-/tmp}/khodroyar-ui-tests.log"
if ! python3 "$ROOT/tools/summarize-tests.py" "$ROOT/android/app/build/outputs/androidTest-results/connected" "Android emulator UI tests"; then
    RESULT=1
fi
if ! adb pull /sdcard/Download/KhodroYar-UI-Smoke/. "$SCREENSHOTS/"; then
    RESULT=1
fi
# MediaStore Downloads survives UTP's automatic uninstall; app-specific files do not.
python3 "$ROOT/tools/annotate-ocr-evidence.py" "$SCREENSHOTS/online-report.json" || RESULT=1
COUNT=$(find "$SCREENSHOTS" -type f -name '*.png' ! -name 'fixture-*' | wc -l)
echo "::notice title=Native UI screenshots::$COUNT PNGs captured from API 35 emulator"
if [ "$COUNT" -lt 33 ]; then
    echo "::error::Expected 33 native UI screenshots; found $COUNT"
    RESULT=1
fi
if [ -f "$SCREENSHOTS/performance-12000.json" ]; then
    echo "::notice title=12000-record emulator measurement::$(cat "$SCREENSHOTS/performance-12000.json")"
else
    echo "::error::Missing 12000-record emulator measurement"
    RESULT=1
fi
exit "$RESULT"
