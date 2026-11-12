#!/usr/bin/env python3
"""Make bounded compiler diagnostics visible via the checks API, even when log downloads fail."""
from pathlib import Path
import re
import sys


def escape(text):
    return text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


for filename in sys.argv[1:]:
    path = Path(filename)
    if not path.is_file():
        continue
    seen = set()
    lines = path.read_text(errors='replace').splitlines()
    for index, line in enumerate(lines):
        if line.startswith('* What went wrong:'):
            detail = '\n'.join(lines[index:index+25]).split('* Try:')[0]
            print('::error title=Android Gradle task failure::' + escape(detail[:3800]))
        if line.startswith('INSTRUMENTATION_FAILED:') or 'Test run failed to complete' in line:
            print('::error title=Android instrumentation failure::' + escape(line[:2000]))
        if not re.match(r'^(e: |.*\berror: |ERROR: )', line) or line in seen:
            continue
        seen.add(line)
        print('::error title=Android compiler::' + escape(line[:1000]))
        if len(seen) >= 25:
            break
