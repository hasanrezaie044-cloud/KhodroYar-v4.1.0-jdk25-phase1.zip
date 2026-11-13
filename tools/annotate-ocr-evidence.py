#!/usr/bin/env python3
"""Bounded SYNTHETIC mocked-network report evidence, never private/live OCR text."""
import json
from pathlib import Path
import sys

path = Path(sys.argv[1])
if path.name != 'online-report.json' or not path.is_file() or path.stat().st_size > 16384:
    raise SystemExit('Missing or oversized mocked online-report evidence')
evidence = json.loads(path.read_text())
assert evidence['environment'] == 'Synthetic report; mocked FastOCR transport; NOT live recognition'
assert evidence['passed'] is True and evidence['rows'] == 5 and evidence['pdf_uploads'] == 3 and evidence['requests'] == 18
assert evidence['consent_cancel_sent_nothing'] is True and evidence['local_reparse_sent_nothing'] is True
assert evidence['days'] == ['1405/05/10', '1405/05/11'] and evidence['unchanged_app_services'] == 7
text = json.dumps(evidence, ensure_ascii=False).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print('::notice title=Online report mocked-transport integration (NOT live OCR)::' + text)
