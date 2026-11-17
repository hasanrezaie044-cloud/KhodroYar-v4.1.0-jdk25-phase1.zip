#!/usr/bin/env python3
"""Verify the explicit online-only transition; never downloads a model."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'android/app/src/main'
assert not list(MAIN.rglob('*.traineddata')), 'Offline OCR models must not be packaged'
assert not (MAIN / 'assets/ocr').exists(), 'Retired OCR assets must be absent'
assert not (MAIN / 'java/com/khodroyar/app/workreport/OfflineReportOcr.kt').exists()
for path in (ROOT / 'android/app/build.gradle', ROOT / 'android/app/proguard-rules.pro'):
    text = path.read_text()
    assert 'tesseract4android' not in text.lower(), 'Retired native OCR dependency/keep rules'
contract = (MAIN / 'aidl/com/farsitel/bazaar/IUpdateCheckService.aidl').read_text()
assert contract.index('getVersionCode(') < contract.index('getRemoteVersionCode(')
assert contract.count('long get') == 2, 'Preserve the official first two Binder transactions'
assert (ROOT / 'android/bazaar-fixture/src/main/aidl/com/farsitel/bazaar/IUpdateCheckService.aidl').read_text() == contract
assert (MAIN / 'res/raw/bazaar_updater_license.txt').stat().st_size > 10_000
print('Online-only report reader: no bundled OCR engine/models; Bazaar IPC contract/licence present')
