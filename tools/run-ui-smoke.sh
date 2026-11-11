#!/usr/bin/env bash
# Called while the ephemeral emulator is still alive; preserve the Gradle exit code.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCREENSHOTS="${RUNNER_TEMP:-/tmp}/orbit-ui-screenshots"
mkdir -p "$SCREENSHOTS"
cd "$ROOT/android"
set +e
./gradlew connectedDebugAndroidTest --stacktrace 2>&1 | tee "${RUNNER_TEMP:-/tmp}/khodroyar-ui-tests.log"
RESULT=${PIPESTATUS[0]}
set -e
python3 "$ROOT/tools/annotate-build-errors.py" "${RUNNER_TEMP:-/tmp}/khodroyar-ui-tests.log"
if ! python3 "$ROOT/tools/summarize-tests.py" "$ROOT/android/app/build/outputs/androidTest-results/connected" "Android emulator UI tests"; then
    RESULT=1
fi
if ! adb pull /sdcard/Download/KhodroYar-UI-Smoke/. "$SCREENSHOTS/"; then
    RESULT=1
fi
COUNT=$(find "$SCREENSHOTS" -type f -name '*.png' | wc -l)
echo "::notice title=Native UI screenshots::$COUNT PNGs captured from API 35 emulator"
if [ "$COUNT" -lt 37 ]; then
    echo "::error::Expected 37 native UI screenshots; found $COUNT"
    RESULT=1
fi
if [ -f "$SCREENSHOTS/performance-12000.json" ]; then
    echo "::notice title=12000-record emulator measurement::$(cat "$SCREENSHOTS/performance-12000.json")"
else
    echo "::error::Missing 12000-record emulator measurement"
    RESULT=1
fi
if [ -f "$SCREENSHOTS/albert-emulator.json" ]; then
    echo "::notice title=Actual bundled ALBERT emulator execution::$(cat "$SCREENSHOTS/albert-emulator.json")"
else
    echo "::error::Missing actual ALBERT native execution and tokenizer parity evidence"
    RESULT=1
fi
exit "$RESULT"
