#!/usr/bin/env bash
# Disposable emulator only. No production service records or real statement images.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
CI_CHECK_NAME=native-install bash tools/ci-gradle.sh :app:installDebug :ocr:installDebug
DATA="android/ocr/src/androidTest/assets/fas.traineddata"
test -s "$DATA"
adb push "$DATA" /data/local/tmp/khodroyar-fas.traineddata
adb shell run-as com.khodroyar.ocr mkdir -p files/tesseract/tessdata
adb shell run-as com.khodroyar.ocr cp /data/local/tmp/khodroyar-fas.traineddata files/tesseract/tessdata/fas.traineddata
adb shell rm -f /data/local/tmp/khodroyar-fas.traineddata
# Separate invocations prevent concurrent instrumentation sessions on the same emulator.
CI_CHECK_NAME=native-app-tests bash tools/ci-gradle.sh :app:connectedDebugAndroidTest
CI_CHECK_NAME=native-ocr-tests bash tools/ci-gradle.sh :ocr:connectedDebugAndroidTest
