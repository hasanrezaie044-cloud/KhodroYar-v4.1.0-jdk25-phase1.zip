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
if ! adb pull /sdcard/Android/data/com.khodroyar.app/files/orbit-ui/. "$SCREENSHOTS/"; then
    RESULT=1
fi
COUNT=$(find "$SCREENSHOTS" -type f -name '*.png' | wc -l)
echo "::notice title=Native UI screenshots::$COUNT PNGs captured from API 35 emulator"
if [ "$COUNT" -lt 17 ]; then
    echo "::error::Expected 17 native UI screenshots; found $COUNT"
    RESULT=1
fi
exit "$RESULT"
