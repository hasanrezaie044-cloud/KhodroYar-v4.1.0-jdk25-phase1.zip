#!/usr/bin/env python3
"""Bind a prepared asset artifact to THIS source and check every distributed asset."""
import hashlib
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
root = Path(sys.argv[1] if len(sys.argv) > 1 else 'android/.cache/offline-assets/albert')
meta = json.loads((root / 'manifest.json').read_text())
lock = json.loads((HERE / 'source-lock.json').read_text())
assert meta['revision'] == lock['revision'] and meta['model'] == lock['model']
assert meta['maxTokens'] == 64 and meta['license'] == 'Apache-2.0'
inputs = hashlib.sha256()
for name in ('build_model.py', 'intents.json', 'requirements.txt', 'source-lock.json'):
    inputs.update(name.encode()); inputs.update((HERE / name).read_bytes())
assert meta['sourceInputsSha256'] == inputs.hexdigest(), 'Model artifact was prepared from different source inputs'
for name, expected in meta['assets'].items():
    assert Path(name).name == name
    data = (root / name).read_bytes()
    assert len(data) == expected['bytes'] and hashlib.sha256(data).hexdigest() == expected['sha256'], name
assert (root / 'intent-int8.onnx').stat().st_size < 45_000_000
report = json.loads((root / 'evaluation.json').read_text())
assert report['holdoutAccepted'] >= 16 and report['holdoutAcceptedCorrect'] / report['holdoutAccepted'] >= .9
print('Verified offline ALBERT asset set, provenance and authored evaluation:', meta['revision'])
