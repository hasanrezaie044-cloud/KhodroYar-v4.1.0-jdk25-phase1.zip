#!/usr/bin/env python3
"""Expose only the disposable emulator's synthetic OCR evidence, not user documents."""
import json
from pathlib import Path
import sys


def notice(title, message):
    message = message.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f'::notice title={title}::{message}')


path = Path(sys.argv[1])
if not path.is_file() or path.stat().st_size > 1_000_000:
    raise SystemExit('Missing or oversized native synthetic OCR evidence')
evidence = json.loads(path.read_text())
notice('Actual bundled OCR fixture', evidence['environment'])
for index, page in enumerate(evidence['pages']):
    message = ' | '.join(f'{w["text"]}@{w["x"]:.3f},{w["y"]:.3f} ({w["confidence"]:.0f}%)' for w in page['words'])
    notice(f'Actual OCR geometry page {index+1}', message[:16000])
notice('Actual OCR extracted rows', json.dumps(evidence['rows'], ensure_ascii=False)[:24000])

for index, message in enumerate(evidence.get('diagnostics', [])):
    notice(f'Native OCR diagnostic {index+1}', message[:12000])
