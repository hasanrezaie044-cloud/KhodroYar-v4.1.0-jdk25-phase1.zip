#!/usr/bin/env python3
"""Verify pinned bundled OCR assets. --fetch is build-time only, never a phone download."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'android/app/src/main/assets/ocr'
FAST = ('tesseract-ocr/tessdata_fast', '87416418657359cb625c412a48b6e1d6d41c29bd')
BEST = ('tesseract-ocr/tessdata_best', 'e12c65a915945e4c28e237a9b52bc4a8f39a0cec')
SOURCES = {'fas.traineddata': BEST, 'ara.traineddata': FAST, 'eng.traineddata': FAST, 'LICENSE': FAST}


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path]))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fetch', action='store_true')
    args = parser.parse_args()
    if args.fetch:
        DEST.mkdir(parents=True, exist_ok=True)
        trees = {}
        files = []
        for name, (repository, commit) in SOURCES.items():
            if (repository, commit) not in trees:
                tree = api(f'repos/{repository}/git/trees/{commit}')
                trees[repository, commit] = {r['path']: r for r in tree['tree']}
            row = trees[repository, commit][name]
            blob = api(f'repos/{repository}/git/blobs/{row["sha"]}')
            data = base64.b64decode(blob['content'])
            assert len(data) == row['size']
            assert hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() == row['sha']
            (DEST / name).write_bytes(data)
            files.append({'name': name, 'repository': repository, 'commit': commit, 'bytes': len(data),
                          'sha256': hashlib.sha256(data).hexdigest(), 'git_blob': row['sha']})
        (DEST / 'manifest.json').write_text(json.dumps({'version': 2, 'license': 'Apache-2.0', 'files': files}, indent=2) + '\n')
    manifest = json.loads((DEST / 'manifest.json').read_text())
    assert manifest['version'] == 2
    assert {e['name'] for e in manifest['files']} == set(SOURCES)
    for entry in manifest['files']:
        assert (entry['repository'], entry['commit']) == SOURCES[entry['name']], entry['name']
        data = (DEST / entry['name']).read_bytes()
        assert len(data) == entry['bytes'], entry['name']
        assert hashlib.sha256(data).hexdigest() == entry['sha256'], entry['name']
        assert hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() == entry['git_blob']
        print(entry['name'], entry['bytes'], entry['sha256'])
    print('Offline Persian best + Arabic/English fast OCR assets: verified')


if __name__ == '__main__':
    main()
