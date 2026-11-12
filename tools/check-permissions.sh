#!/usr/bin/env bash
# Audit the source XML and the final merged APK, including transitive permissions.
# Network permissions remain REQUIRED for existing traffic / optional online assistant.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="${MANIFEST_PATH:-$ROOT/android/app/src/main/AndroidManifest.xml}"
OUT_DIR="${1:-$ROOT/android/app/build/outputs/apk/release}"
python3 "$ROOT/tools/check-permissions.py" --manifest "$MANIFEST"
[[ "${STATIC_ONLY:-0}" == "1" ]] && exit 0
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
AAPT="${AAPT2:-$(find "$SDK/build-tools" -name aapt2 2>/dev/null | sort -V | tail -1 || true)}"
[[ -x "$AAPT" ]] || { echo 'aapt2 not found; merged APK NOT audited' >&2; exit 1; }
shopt -s nullglob
APKS=("$OUT_DIR"/*.apk)
[[ ${#APKS[@]} -gt 0 ]] || { echo "No APK in $OUT_DIR; merged APK NOT audited" >&2; exit 1; }
for apk in "${APKS[@]}"; do
  echo "Merged permission audit: $apk"
  PERMS_FILE=$(mktemp)
  "$AAPT" dump permissions "$apk" > "$PERMS_FILE"
  python3 "$ROOT/tools/check-permissions.py" --aapt "$PERMS_FILE"
  rm -f "$PERMS_FILE"
  "$AAPT" dump badging "$apk" | grep -E '^package:|^sdkVersion|^targetSdkVersion'
done
