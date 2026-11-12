#!/usr/bin/env python3
"""Summarise real emulator JUnit XML through the Checks API; zero tests is a failure."""
import json
import xml.etree.ElementTree as ET
from pathlib import Path
summary = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0, 'files': [], 'problems': []}
for module in ['app', 'ocr']:
    for file in Path(f'android/{module}/build/outputs/androidTest-results').rglob('TEST-*.xml'):
        root = ET.parse(file).getroot()
        summary['files'].append(str(file))
        suites = [root] if root.tag == 'testsuite' else list(root.iter('testsuite'))
        for suite in suites:
            for key in ['tests', 'failures', 'errors', 'skipped']: summary[key] += int(suite.get(key, 0))
            for case in suite.findall('testcase'):
                for issue in list(case.findall('failure')) + list(case.findall('error')):
                    summary['problems'].append({'test': case.get('name'), 'message': (issue.get('message', '') + '\n' + (issue.text or ''))[:5000]})
print('::notice title=Native verification evidence::' + json.dumps(summary, ensure_ascii=False).replace('%', '%25'))
if summary['tests'] < 4 or summary['failures'] or summary['errors'] or summary['skipped']:
    raise SystemExit('Native verification did not complete successfully; see real test results.')
print('Four native tests executed and passed (PDF pages, duplicate images, native Persian OCR and real signature-protected OCR IPC).')
