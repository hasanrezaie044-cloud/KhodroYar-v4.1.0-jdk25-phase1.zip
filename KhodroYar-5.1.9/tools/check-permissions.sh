#!/usr/bin/env bash
# Fails the build if the FINAL MERGED manifest contains any permission outside the
# allow-list. Reads the built artifact rather than the source manifest, so
# permissions merged in by a transitive dependency are still caught.
set -euo pipefail

OUT_DIR="${1:-android/app/build/outputs/apk/release}"

# The only permissions this app is allowed to ship with.
# INTERNET + ACCESS_NETWORK_STATE are REQUIRED since 5.1.x: the traffic assistant (Neshan API)
# and, from 5.1.9, the AI assistant (talks only to the owner's own backend over HTTPS).
# They are allowed on purpose; the dangerous ones below stay banned.
ALLOWED=(
  "android.permission.POST_NOTIFICATIONS"
  "android.permission.USE_BIOMETRIC"
  "android.permission.INTERNET"
  "android.permission.ACCESS_NETWORK_STATE"
)

# Explicitly banned; presence of any of these is a hard failure.
BANNED=(
  "android.permission.SYSTEM_ALERT_WINDOW"
  "android.permission.ACCESS_FINE_LOCATION"
  "android.permission.ACCESS_COARSE_LOCATION"
  "android.permission.ACCESS_BACKGROUND_LOCATION"
  "android.permission.READ_CONTACTS"
  "android.permission.WRITE_CONTACTS"
  "android.permission.GET_ACCOUNTS"
  "android.permission.READ_SMS"
  "android.permission.SEND_SMS"
  "android.permission.RECEIVE_SMS"
  "android.permission.READ_PHONE_STATE"
  "android.permission.CALL_PHONE"
  "android.permission.CAMERA"
  "android.permission.RECORD_AUDIO"
  "android.permission.READ_EXTERNAL_STORAGE"
  "android.permission.WRITE_EXTERNAL_STORAGE"
  "android.permission.QUERY_ALL_PACKAGES"
)

# ---- Static audit of the SOURCE manifest (runs without an SDK or a built APK) -------------
MANIFEST="${MANIFEST_PATH:-$(dirname "$0")/../android/app/src/main/AndroidManifest.xml}"
if [[ -f "$MANIFEST" ]]; then
  echo "Static manifest audit: $MANIFEST"
  # Only <uses-permission> lines that are NOT tools:node="remove" are really declared.
  DECLARED=$(grep -E '<uses-permission ' "$MANIFEST" | grep -v 'tools:node="remove"' \
             | grep -oE 'android:name="[^"]+"' | sed -E 's/android:name="([^"]+)"/\1/' | sort -u || true)
  SRC_STATUS=0
  for b in "${BANNED[@]}"; do
    if grep -qx "$b" <<< "$DECLARED"; then echo "::error::BANNED PERMISSION DECLARED IN MANIFEST: $b"; SRC_STATUS=1; fi
  done
  while IFS= read -r p; do
    [[ -z "$p" ]] && continue
    ok=0; for a in "${ALLOWED[@]}"; do [[ "$p" == "$a" ]] && ok=1 && break; done
    if [[ $ok -eq 0 ]]; then echo "::error::MANIFEST PERMISSION NOT IN ALLOW-LIST: $p"; SRC_STATUS=1; fi
  done <<< "$DECLARED"
  # The app really needs these two; a missing one means the online features would break.
  for need in "android.permission.INTERNET" "android.permission.ACCESS_NETWORK_STATE"; do
    if ! grep -qx "$need" <<< "$DECLARED"; then echo "::error::REQUIRED PERMISSION MISSING FROM MANIFEST: $need"; SRC_STATUS=1; fi
  done
  echo "Declared in source manifest:"; echo "$DECLARED" | sed 's/^/  - /'
  if [[ $SRC_STATUS -ne 0 ]]; then echo "❌ Static manifest audit FAILED."; exit 1; fi
  echo "✅ Static manifest audit passed."
  [[ "${STATIC_ONLY:-0}" == "1" ]] && exit 0
fi

AAPT=$(find "${ANDROID_HOME:-/usr/local/lib/android/sdk}/build-tools" -name aapt2 2>/dev/null | sort -r | head -1 || true)
if [[ -z "$AAPT" ]]; then
  echo "aapt2 not found; cannot audit" >&2; exit 1
fi

shopt -s nullglob
APKS=("$OUT_DIR"/*.apk)
if [[ ${#APKS[@]} -eq 0 ]]; then
  echo "No APK in $OUT_DIR — skipping permission audit (AAB-only build)."
  exit 0
fi

STATUS=0
for apk in "${APKS[@]}"; do
  echo "=============================================================="
  echo "Auditing: $apk"
  echo "=============================================================="

  PERMS=$("$AAPT" dump permissions "$apk" \
          | grep -oE "android\.permission\.[A-Z_]+|com\.[a-z.]+permission\.[A-Z_]+" \
          | sort -u || true)

  if [[ -z "$PERMS" ]]; then
    echo "  (no permissions declared)"
  else
    echo "Declared permissions:"
    echo "$PERMS" | sed 's/^/  - /'
  fi

  # Banned check
  for b in "${BANNED[@]}"; do
    if grep -qx "$b" <<< "$PERMS"; then
      echo "::error::BANNED PERMISSION PRESENT: $b"
      STATUS=1
    fi
  done

  # Allow-list check
  while IFS= read -r p; do
    [[ -z "$p" ]] && continue
    ok=0
    for a in "${ALLOWED[@]}"; do [[ "$p" == "$a" ]] && ok=1 && break; done
    if [[ $ok -eq 0 ]]; then
      echo "::error::PERMISSION NOT IN ALLOW-LIST: $p"
      STATUS=1
    fi
  done <<< "$PERMS"

  echo
  echo "Package / version:"
  "$AAPT" dump badging "$apk" | grep -E "^package:|^sdkVersion|^targetSdkVersion" | sed 's/^/  /'
  echo
done

if [[ $STATUS -eq 0 ]]; then
  echo "✅ Permission audit passed — only the allow-listed permissions are present."
else
  echo "❌ Permission audit FAILED."
fi
exit $STATUS
