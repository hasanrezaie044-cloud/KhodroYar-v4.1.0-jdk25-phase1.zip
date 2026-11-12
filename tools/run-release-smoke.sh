#!/usr/bin/env bash
# Actual release target; no debug host and no weakening of minification/signing.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EVIDENCE="${RUNNER_TEMP:-/tmp}/khodroyar-release-smoke"
mkdir -p "$EVIDENCE"
cd "$ROOT/android"
# Stop BEFORE installing/uninstalling anything if a real phone is attached.
HARDWARE=$(adb shell getprop ro.hardware | tr -d '\r')
case "$HARDWARE" in
  ranchu|goldfish) ;;
  *) echo "::error::Synthetic tests require a disposable emulator; refusing to install/reset on this device"; exit 2 ;;
esac
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true
BEFORE=$(sha256sum app/build/outputs/apk/release/app-release.apk | awk '{print $1}')
set +e
timeout 18m ./gradlew connectedReleaseAndroidTest -PkhodroyarTestBuildType=release \
  -Pandroid.testInstrumentationRunnerArguments.class=com.khodroyar.app.ui.ReleaseApkSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.timeout_msec=600000 \
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
adb logcat -d -t 500 > "$EVIDENCE/logcat.txt"
# UTP uninstalls the APK; use shared synthetic Downloads, NOT app-specific files.
adb pull /sdcard/Download/KhodroYar-Release-Smoke/. "$EVIDENCE/" || RESULT=1
python3 - "$EVIDENCE" <<'PY'
import json, sys
from pathlib import Path
stages = sorted(Path(sys.argv[1]).glob('stage-*.json'))
if stages:
    text = stages[-1].read_text().replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print('::notice title=Signed-release last reached stage::' + text[:3800])
PY
if ! python3 - "$EVIDENCE/runtime.json" <<'PY'
import json, sys
from pathlib import Path
p = Path(sys.argv[1])
if not p.is_file() or p.stat().st_size > 65536:
    raise SystemExit('Missing signed-release runtime trace')
evidence = json.loads(p.read_text())
text = json.dumps(evidence, ensure_ascii=False).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print('::notice title=Actual signed-release runtime trace::' + text)
assert evidence['passed'] is True, 'Signed-release runtime assertions did not pass'
PY
then
  RESULT=1
fi
if [ "$RESULT" -eq 0 ]; then
  python3 - app/build/outputs/apk/release/verification.json "$EVIDENCE/runtime.json" <<'PY'
import json, sys
from pathlib import Path
proof_path = Path(sys.argv[1])
proof = json.loads(proof_path.read_text())
runtime = json.loads(Path(sys.argv[2]).read_text())
assert runtime['passed'] is True
proof['signed_runtime'] = runtime
proof_path.write_text(json.dumps(proof, indent=2) + '\n')
PY
  echo "::notice title=Signed release runtime verification::Non-debuggable originally signed R8 APK launched, real Persian OCR with radios disabled and actual SAF picker passed; gesture navigation; exact audited APK hash preserved."
fi
exit "$RESULT"
