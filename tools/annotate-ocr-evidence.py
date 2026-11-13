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

if len(sys.argv) > 2:
    company_path = Path(sys.argv[2])
    if company_path.name != 'company-template-ocr.json' or not company_path.is_file() or company_path.stat().st_size > 1_000_000:
        raise SystemExit('Missing or oversized synthetic fixed-company-form evidence')
    company = json.loads(company_path.read_text())
    if company.get('environment') != 'Synthetic fixed recurring form; three PDF pages plus repeated PNG; native offline engine':
        raise SystemExit('Refusing to annotate anything except the known disposable synthetic company fixture')
    notice('Native fixed-company extracted rows', json.dumps(company['rows'], ensure_ascii=False)[:24000])
    diagnostics = company.get('diagnostics', [])
    notice('Native fixed-company header diagnostics', '\n'.join(m for m in diagnostics if m.startswith('Page '))[:24000])
    notice('Native fixed-company cell diagnostics', '\n'.join(m for m in diagnostics if 'Fixed-form ' in m or m.startswith('Printed '))[:56000])

notice('Actual bundled OCR fixture', evidence['environment'])
for index, page in enumerate(evidence['pages']):
    message = ' | '.join(f'{w["text"]}@{w["x"]:.3f},{w["y"]:.3f} ({w["confidence"]:.0f}%)' for w in page['words'])
    notice(f'Actual OCR geometry page {index+1}', message[:16000])
notice('Actual OCR extracted rows', json.dumps(evidence['rows'], ensure_ascii=False)[:24000])

messages = '\n'.join(evidence.get('diagnostics', []))
notice('Native OCR diagnostics', messages[:56000])
for extra in sorted(path.parent.glob('numeric-font-*.json')):
    data = json.loads(extra.read_text())
    notice('Native numeric-font diagnostics ' + extra.stem, json.dumps(data, ensure_ascii=False)[:10000])
