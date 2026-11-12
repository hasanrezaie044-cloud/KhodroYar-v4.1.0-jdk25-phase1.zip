#!/usr/bin/env python3
"""Provision optional language DATA, separately from all release APKs. Can be copied offline."""
import argparse
import base64
import hashlib
import json
import os
import urllib.request
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--output', type=Path, default=ROOT / 'deliverables/ocr-data/fas.traineddata')
    args = parser.parse_args()
    lock = json.loads((ROOT / 'docs/persian-ocr-data-lock.json').read_text())
    url = f"https://api.github.com/repos/{lock['repository']}/contents/{lock['path']}?ref={lock['revision']}"
    headers = {'Accept': 'application/vnd.github+json', 'User-Agent': 'KhodroYar-data-provisioner'}
    if os.environ.get('GITHUB_TOKEN'): headers['Authorization'] = 'Bearer ' + os.environ['GITHUB_TOKEN']
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=45) as response: meta = json.load(response)
    data = base64.b64decode(meta['content'])
    assert len(data) == lock['bytes'] and hashlib.sha256(data).hexdigest() == lock['sha256'], 'Persian data integrity check failed'
    args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_bytes(data)
    print(f"Verified official fas language data: {len(data)} bytes, SHA-256 {lock['sha256']}")

if __name__ == '__main__': main()
