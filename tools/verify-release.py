#!/usr/bin/env python3
"""Verify real built APKs with Android build-tools. Unsigned/debug artifacts fail closed."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--ocr', type=Path)
    parser.add_argument('--baseline', action='append', default=[], type=Path)
    parser.add_argument('--output', type=Path, default=Path('.verification/release-verification.json'))
    args = parser.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '')))
    tools = sdk / 'build-tools' / '36.0.0'
    aapt = str(tools / 'aapt2'); signer = str(tools / 'apksigner')
    props = dict(x.split('=', 1) for x in (ROOT / 'android/version.properties').read_text().splitlines() if '=' in x)
    application = re.search(r"applicationId\s+'([^']+)'", (ROOT / 'android/app/build.gradle').read_text())[1]
    def inspect(path):
        badging = subprocess.check_output([aapt, 'dump', 'badging', str(path)], text=True)
        meta = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
        assert meta, 'Package metadata not found'
        signed = subprocess.check_output([signer, 'verify', '--verbose', '--print-certs', str(path)], text=True)
        assert 'CN=Android Debug' not in signed, 'Debug identity is forbidden'
        fingerprints = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)', signed)
        assert fingerprints, 'No verified release signer'
        return {'file': str(path), 'applicationId': meta[1], 'versionCode': int(meta[2]), 'versionName': meta[3], 'bytes': path.stat().st_size,
                'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'certificateSha256': fingerprints, 'apksigner': signed}
    final = inspect(args.apk)
    assert final['applicationId'] == application
    assert final['versionCode'] == int(props['versionCode']) and final['versionName'] == props['versionName']
    with zipfile.ZipFile(args.apk) as archive:
        names = archive.namelist()
        assert not any('albert' in x.lower() or 'sentencepiece' in x.lower() or 'onnxruntime' in x.lower() or x.endswith(('.onnx', '.traineddata')) for x in names), 'Heavy model/runtime leaked into primary APK'
        assert not any('tesseract' in x.lower() for x in names), 'Optional OCR runtime leaked into primary APK'
    evidence = {'final': final, 'baselines': [], 'comparisonConvention': 'app minus company', 'roomSchemaVersion': 6}
    for path in args.baseline:
        baseline = inspect(path)
        assert baseline['applicationId'] == application
        assert baseline['versionCode'] < final['versionCode'], 'Would downgrade an existing installation'
        assert baseline['certificateSha256'] == final['certificateSha256'], 'Release signing identity changed'
        baseline['differenceBytes'] = final['bytes'] - baseline['bytes']
        baseline['differencePercent'] = round(100 * baseline['differenceBytes'] / baseline['bytes'], 3)
        evidence['baselines'].append(baseline)
    if args.ocr:
        ocr = inspect(args.ocr)
        assert ocr['applicationId'] == 'com.khodroyar.ocr' and ocr['certificateSha256'] == final['certificateSha256']
        manifest = subprocess.check_output([aapt, 'dump', 'permissions', str(args.ocr)], text=True)
        for forbidden in ['INTERNET', 'ACCESS_NETWORK_STATE', 'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE', 'CAMERA', 'RECORD_AUDIO']:
            assert 'android.permission.' + forbidden not in manifest
        evidence['optionalOcr'] = ocr
    suites = list((ROOT / 'android/app/build/test-results').rglob('TEST-*.xml'))
    evidence['unitTests'] = {'suites': len(suites), 'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    for file in suites:
        suite = ET.parse(file).getroot()
        for key in ['tests', 'failures', 'errors', 'skipped']: evidence['unitTests'][key] += int(suite.attrib.get(key, 0))
    assert suites and evidence['unitTests']['tests'] > 0
    assert evidence['unitTests']['failures'] == 0 and evidence['unitTests']['errors'] == 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2, ensure_ascii=False) + '\n')
    # Bounded, accessible through the checks API even if downloading artifacts is restricted.
    compact = {**evidence, 'final': {k: v for k, v in final.items() if k != 'apksigner'}, 'baselines': [{k: v for k, v in b.items() if k != 'apksigner'} for b in evidence['baselines']]}
    if 'optionalOcr' in compact: compact['optionalOcr'] = {k: v for k, v in compact['optionalOcr'].items() if k != 'apksigner'}
    print('::notice title=Release verification evidence::' + json.dumps(compact, ensure_ascii=False))
    print('Verified package, dynamic version, release signature continuity, APK sizes and JVM test results.')

if __name__ == '__main__': main()
