#!/usr/bin/env bash
# SDK-independent checks only. This does not claim compilation or APK verification.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
echo '=== Source/static verification (not an Android build) ==='
git diff --check
python3 tools/version.py
STATIC_ONLY=1 bash tools/check-permissions.sh
python3 -m unittest discover -s tools -p 'test_*.py' -v
python3 tools/check-secrets.py --history
python3 - <<'PY'
from pathlib import Path
import re
import xml.etree.ElementTree as ET
root = Path('.')
for xml in root.glob('android/**/src/main/**/*.xml'): ET.parse(xml)
source = (root / 'android/app/build.gradle').read_text()
for check in ['ANDROID_KEYSTORE_PATH', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD', 'signingConfig signingConfigs.release', 'minifyEnabled true', 'shrinkResources true', 'JavaVersion.VERSION_25', 'JavaLanguageVersion.of(25)']:
    assert check in source, check
assert "applicationId 'com.khodroyar.app'" in source
schema = (root / 'android/app/src/main/java/com/khodroyar/app/data/db/AppDatabase.kt').read_text()
assert 'version = 6' in schema and 'fallbackToDestructiveMigration' not in schema
for sourcefile in (root / 'android/app/src/main/java/com/khodroyar/app/reconciliation').glob('*.kt'):
    text = sourcefile.read_text()
    assert not re.search(r'\.(?:addService|updateService|deleteService|clear|insertAll)\(', text), sourcefile
assert (root / 'android/gradle/wrapper/gradle-wrapper.jar').is_file()
print('XML, unchanged package/Room schema, read-only reconciliation, original signing configuration and wrapper checked.')
PY
echo 'Static checks passed. Android unit tests, R8, APK metadata and signing require the release CI or an SDK/JDK installation.'
