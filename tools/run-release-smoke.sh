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
# The already-built original target is not rebuilt, re-signed or made debuggable.
# Use raw AndroidJUnitRunner protocol so a Gradle/UTP collection stall cannot
# disguise whether the exact external signed test actually started and completed.
set -e
APK=app/build/outputs/apk/release/app-release.apk
TEST_APK=release-smoke/build/outputs/apk/release/release-smoke-release.apk
test -s "$TEST_APK"
STORE_APK=bazaar-fixture/build/outputs/apk/release/bazaar-fixture-release.apk
test -s "$STORE_APK"
AAPT="$ANDROID_HOME/build-tools/${ANDROID_BUILD_TOOLS:-36.0.0}/aapt2"
HARNESS_PACKAGE=$("$AAPT" dump badging "$TEST_APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
case "$HARNESS_PACKAGE" in
  com.khodroyar.app.releasesmoke|com.khodroyar.app.releasesmoke.test) ;;
  *) echo "::error::Unexpected standalone test package: $HARNESS_PACKAGE"; exit 1 ;;
esac
check_installed_target_hash() {
  local path installed
  path=$(adb shell pm path com.khodroyar.app | tr -d '\r' | sed -n 's/^package://p')
  [[ "$path" =~ ^/data/app/[A-Za-z0-9_./~+=-]+/base\.apk$ ]] || {
    echo "::error::Expected exactly the original installed base APK"; return 1;
  }
  installed=$(adb shell sha256sum "$path" | tr -d '\r' | awk '{print $1}')
  test "$installed" = "$BEFORE" || {
    echo "::error::Installed target differs from the original audited release APK"; return 1;
  }
  printf '%s\n' "$installed" > "$EVIDENCE/target-installed-sha256.txt"
}
APKSIGNER="$ANDROID_HOME/build-tools/${ANDROID_BUILD_TOOLS:-36.0.0}/apksigner"
TEST_CERT=$("$APKSIGNER" verify --print-certs "$TEST_APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
test "$TEST_CERT" = "03bfbc3575667403c3259fae345f3eb1ad3045373cb62123be376ef4898caaf0" || {
  echo "::error::Disposable instrumentation APK signing identity unexpectedly changed"; exit 1;
}
# Install only on the already-verified disposable emulator. It is a zero-network,
# clearly labelled IPC fixture, NOT the real marketplace and never delivered.
if adb shell pm path com.farsitel.bazaar | grep -q '^package:'; then
  echo "::error::Refusing to replace an existing Bazaar installation"; exit 1
fi
adb install --no-incremental -r "$STORE_APK"
adb install --no-incremental -r "$APK"
adb install --no-incremental -r -t "$TEST_APK"
# Fail before running unless Android registers the harness against ITSELF. It
# must never inject runner/shared-runtime classes into the minified production app.
adb shell pm list instrumentation | tr -d '\r' | grep -Fx \
  "instrumentation:$HARNESS_PACKAGE/androidx.test.runner.AndroidJUnitRunner (target=$HARNESS_PACKAGE)" || {
  echo "::error::Release smoke must be self-instrumenting, not attached to the production package"; exit 1;
}
check_installed_target_hash
echo "::notice title=Signed test installation::Original non-debuggable audited APK and same-signed self-instrumenting accessibility harness installed on disposable emulator; independent process and class loader."
# Distinguish a genuine launcher startup from instrumentation bootstrap. Never
# make the target debuggable or change its signature to collect this evidence.
adb logcat -c
set +e
timeout --signal=TERM --kill-after=10s 60s adb shell am start -W -n com.khodroyar.app/.MainActivity \
  2>&1 | tee "$EVIDENCE/normal-launch.txt"
LAUNCH_RESULT=${PIPESTATUS[0]}
set -e
echo "::notice title=Original APK normal launcher exit::Exit $LAUNCH_RESULT; this alone is not passing review/Bazaar evidence."
adb shell am force-stop com.khodroyar.app
set +e
timeout --signal=TERM --kill-after=30s 12m adb shell am instrument -w -r \
  -e class com.khodroyar.app.ui.ReleaseApkSmokeTest \
  -e timeout_msec 600000 \
  "$HARNESS_PACKAGE/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$EVIDENCE/instrumentation.log"
RESULT=${PIPESTATUS[0]}
set -e
if [ "$LAUNCH_RESULT" -ne 0 ]; then RESULT=1; fi
echo "::notice title=Signed instrumentation command exit::Exit $RESULT; the raw protocol, actual assertions, runtime trace and original hash must also pass."
if ! python3 "$ROOT/tools/summarize-instrumentation.py" "$EVIDENCE/instrumentation.log" \
  "$ROOT/android/app/build/outputs/androidTest-results/connected/manual-release/TEST-com.khodroyar.app.ui.ReleaseApkSmokeTest.xml"; then
  RESULT=1
fi
if ! python3 "$ROOT/tools/summarize-tests.py" "$ROOT/android/app/build/outputs/androidTest-results/connected" "Signed release online-only review and Bazaar IPC tests"; then
  RESULT=1
fi
AFTER=$(sha256sum app/build/outputs/apk/release/app-release.apk | awk '{print $1}')
if [ "$BEFORE" != "$AFTER" ]; then
  echo "::error::Tested release APK differs from the already audited candidate"
  RESULT=1
fi
if ! check_installed_target_hash; then RESULT=1; fi
adb logcat -d -t 1500 > "$EVIDENCE/logcat.txt"
adb logcat -d -b crash > "$EVIDENCE/crash.txt"
adb shell dumpsys dropbox --print data_app_anr > "$EVIDENCE/anr.txt"
python3 - "$EVIDENCE" <<'PY'
import re, sys
from pathlib import Path
root = Path(sys.argv[1])
text = (root / 'anr.txt').read_text(errors='replace')
# Only the generated emulator app's own startup dump, not unrelated OS entries.
own = [part for part in re.split(r'^Cmd line: ', text, flags=re.M)[1:]
       if part.splitlines()[0].strip() == 'com.khodroyar.app']
for part in own[-2:]:
    match = re.search(r'^"main"[^\n]*', part, re.M)
    if not match:
        continue
    section = part[match.start():match.start()+6500].split('\n"', 1)[0]
    for index in range(0, len(section), 3200):
        value = section[index:index+3200].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print(f'::notice title=Actual startup main-thread ANR {index//3200+1}::' + value)
crash = (root / 'crash.txt').read_text(errors='replace')
if crash.strip():
    value = crash[-3500:].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print('::notice title=Actual signed-process crash buffer::' + value)
PY
# Preserve shared synthetic evidence; never collect private user app data.
adb pull /sdcard/Download/KhodroYar-Release-Smoke/. "$EVIDENCE/" || RESULT=1
python3 - "$EVIDENCE" <<'PY'
import json, sys
from pathlib import Path
from xml.etree import ElementTree as ET
root = Path(sys.argv[1])
stages = sorted(root.glob('stage-*.json'))
if stages:
    text = stages[-1].read_text().replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print('::notice title=Signed-release last reached stage::' + text[:3800])
# Only this disposable emulator's generated test UI, never a connected phone.
# Expose the actual stopping window instead of inferring it from a missing ID.
if (root / 'runtime.json').is_file() and (root / 'last-window.xml').is_file():
    runtime = json.loads((root / 'runtime.json').read_text())
    if runtime.get('passed') is not True:
        nodes = []
        for node in ET.parse(root / 'last-window.xml').iter('node'):
            values = {key: node.get(key, '')[:200] for key in ('resource-id', 'text', 'content-desc', 'class', 'enabled', 'bounds')}
            if values['text'] or values['content-desc'] or values['resource-id']:
                nodes.append(values)
        text = json.dumps(nodes[:50], ensure_ascii=False)[:9000]
        for index in range(0, len(text), 3000):
            piece = text[index:index+3000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
            print(f'::notice title=Actual final synthetic accessibility window {index//3000+1}::' + piece)
PY
if ! python3 - "$EVIDENCE/runtime.json" <<'PY'
import json, sys
from pathlib import Path
p = Path(sys.argv[1])
if not p.is_file() or p.stat().st_size > 65536:
    print('::error title=Missing signed-release runtime proof::The actual APK runtime test did not publish its required trace; no handoff is allowed.')
    raise SystemExit('Missing signed-release runtime trace')
evidence = json.loads(p.read_text())
text = json.dumps(evidence, ensure_ascii=False).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print('::notice title=Actual signed-release runtime trace::' + text)
# Reuse the very same strict runtime schema enforced by the publication guard.
import importlib.util
spec = importlib.util.spec_from_file_location('publication', Path('..') / 'tools/public-apk-release.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
module.validate_runtime(evidence)

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
  echo "::notice title=Signed release runtime verification::Non-debuggable originally signed R8 APK: actual SAF, FastOCR consent/cancel, synthetic text review/counts and Bazaar IPC/deep link with a simulated store passed. NO live OCR/real Bazaar claim; exact APK hash preserved."
fi
exit "$RESULT"
