#!/usr/bin/env python3
"""Verify checked-in offline OCR assets. Fetch only with --fetch; never runs on a phone."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'android/app/src/main/assets/ocr'
COMMIT = '87416418657359cb625c412a48b6e1d6d41c29bd'
REPOSITORY = 'tesseract-ocr/tessdata_fast'


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path]))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fetch', action='store_true')
    args = parser.parse_args()
    if args.fetch:
        DEST.mkdir(parents=True, exist_ok=True)
        tree = api(f'repos/{REPOSITORY}/git/trees/{COMMIT}')
        entries = {r['path']: r for r in tree['tree']}
        files = []
        for name in ['fas.traineddata', 'ara.traineddata', 'eng.traineddata', 'LICENSE']:
            row = entries[name]
            blob = api(f'repos/{REPOSITORY}/git/blobs/{row["sha"]}')
            data = base64.b64decode(blob['content'])
            assert len(data) == row['size']
            assert hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() == row['sha']
            (DEST / name).write_bytes(data)
            files.append({'name': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(), 'git_blob': row['sha']})
        (DEST / 'manifest.json').write_text(json.dumps({'repository': REPOSITORY, 'commit': COMMIT, 'license': 'Apache-2.0', 'files': files}, indent=2) + '\n')
    manifest = json.loads((DEST / 'manifest.json').read_text())
    assert manifest['commit'] == COMMIT
    for entry in manifest['files']:
        data = (DEST / entry['name']).read_bytes()
        assert len(data) == entry['bytes'], entry['name']
        assert hashlib.sha256(data).hexdigest() == entry['sha256'], entry['name']
        print(entry['name'], entry['bytes'], entry['sha256'])
    print('Offline Persian / Arabic / English OCR assets: verified')


if __name__ == '__main__':
    main()
